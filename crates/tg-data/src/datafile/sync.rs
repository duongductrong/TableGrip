//! Keeping a data file and its working copy in step (`datafile/DataFileSync.kt`). Changes committed to the working
//! copy are written to the file: right after the table viewer, import or a schema tool commits, and within
//! [`POLL`] of a commit from a console. A change to the file from elsewhere is read back in when the working copy has
//! nothing unsaved, and held for the user to decide when it has. Writes go to a temporary file that then replaces the
//! original, so the file is never half written.

use super::working_copy::{TableState, WorkingCopy};
use super::{DataFileDocument, DataFileError, DataFileFormat, OutputTable, Result};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, Instant, SystemTime};
use tg_core::connection::DataFileOptions;
use tokio::sync::watch;

pub const POLL: Duration = Duration::from_secs(1);
const MAX_RETRY: Duration = Duration::from_secs(30);
const CLOSE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeldReason {
    /// The file changed (or went away) on disk while the working copy had changes of its own.
    ChangedOnDisk,
    /// Writing would lose something the file has, e.g. formulas.
    LosesContent,
}

/// Where a data file stands against its working copy.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DataFileState {
    /// The file holds everything the working copy does. `at` is when it was last written or read, in epoch millis.
    /// `session_only` names tables that exist only in the working copy, because the file holds a single table.
    InSync {
        at: i64,
        session_only: Vec<String>,
    },
    Saving,
    /// Changes are kept in the working copy, not written, until the user decides: `save(true)` writes them anyway,
    /// `reload` discards them for what the file holds.
    Held {
        reason: HeldReason,
        message: String,
    },
    /// The last write or read failed; the working copy keeps the changes, and the next change or save tries again.
    Failed(String),
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

fn in_sync(session_only: Vec<String>) -> DataFileState {
    DataFileState::InSync {
        at: now_millis(),
        session_only,
    }
}

/// Enough of a file's attributes to tell that it changed: size, modification time and identity (a replaced file is
/// new).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileStamp {
    size: u64,
    modified: Option<SystemTime>,
    key: Option<(u64, u64)>,
}

impl FileStamp {
    pub fn of(path: &Path) -> Option<Self> {
        let m = std::fs::metadata(path).ok()?;
        #[cfg(unix)]
        let key = {
            use std::os::unix::fs::MetadataExt;
            Some((m.dev(), m.ino()))
        };
        #[cfg(not(unix))]
        let key = None;
        Some(Self {
            size: m.len(),
            modified: m.modified().ok(),
            key,
        })
    }
}

struct Inner {
    format: DataFileFormat,
    options: DataFileOptions,
    copy: WorkingCopy,
    document: Box<dyn DataFileDocument>,
    /// The tables as the file has them, by lower-case name: what a save compares the working copy against.
    known: HashMap<String, TableState>,
    stamp: Option<FileStamp>,
    seen_version: i64,
    /// A file change already found to clash with unsaved changes, so polling doesn't re-check it every tick.
    held_stamp: Option<FileStamp>,
    /// After a failed write, when polling may try again; doubles up to [`MAX_RETRY`] while it keeps failing.
    retry_at: Option<Instant>,
    retry_delay: Duration,
    /// Tables whose loss of content the user accepted.
    confirmed: HashSet<String>,
}

pub struct DataFileSync {
    /// The data file.
    pub file: PathBuf,
    /// Why changes are never written back, or None. Changes from elsewhere are still read in.
    pub read_only_reason: Option<String>,
    inner: Mutex<Inner>,
    state: watch::Sender<DataFileState>,
    /// Goes up each time the tables are read in again from the file, so open views can refresh.
    revision: watch::Sender<u64>,
    closed: AtomicBool,
}

/// What a save writes: every table, matched to the one the file has, and the file's tables that are gone.
struct Plan {
    /// (state, source) per table.
    tables: Vec<(TableState, Option<TableState>)>,
    written: Vec<TableState>,
    removed: BTreeSet<String>,
    session_only: Vec<String>,
}

impl DataFileSync {
    pub(crate) fn new(
        file: &Path,
        format: DataFileFormat,
        options: DataFileOptions,
        copy: WorkingCopy,
        document: Box<dyn DataFileDocument>,
        read_only_reason: Option<String>,
        stamp: Option<FileStamp>,
    ) -> Result<Arc<Self>> {
        let known = copy
            .read(|c| c.states())?
            .into_iter()
            .map(|s| (s.name.to_lowercase(), s))
            .collect();
        let seen_version = copy.data_version()?;
        Ok(Arc::new(Self {
            file: file.to_owned(),
            read_only_reason,
            inner: Mutex::new(Inner {
                format,
                options,
                copy,
                document,
                known,
                stamp,
                seen_version,
                held_stamp: None,
                retry_at: None,
                retry_delay: POLL,
                confirmed: HashSet::new(),
            }),
            state: watch::channel(in_sync(Vec::new())).0,
            revision: watch::channel(0).0,
            closed: AtomicBool::new(false),
        }))
    }

    pub fn state(&self) -> DataFileState {
        self.state.borrow().clone()
    }

    pub fn subscribe_state(&self) -> watch::Receiver<DataFileState> {
        self.state.subscribe()
    }

    pub fn revision(&self) -> u64 {
        *self.revision.borrow()
    }

    pub fn subscribe_revision(&self) -> watch::Receiver<u64> {
        self.revision.subscribe()
    }

    /// What the user should know about this file, e.g. formulas that saving replaces.
    pub fn notes(&self) -> Vec<String> {
        self.inner.lock().unwrap().document.notes()
    }

    fn name(&self) -> String {
        self.file
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    /// Polls every [`POLL`] on a thread of its own until closed or dropped.
    pub(crate) fn start(self: &Arc<Self>) {
        let weak: Weak<Self> = Arc::downgrade(self);
        std::thread::Builder::new()
            .name("tablegrip-datafile-sync".into())
            .spawn(move || {
                loop {
                    std::thread::sleep(POLL);
                    let Some(sync) = weak.upgrade() else { return };
                    if sync.closed.load(Ordering::SeqCst) {
                        return;
                    }
                    sync.poll();
                }
            })
            .ok();
    }

    /// Writes the working copy's changes to the file, if it has any. Without `force` it holds them instead when the
    /// file changed on disk since it was read, or writing would lose content the user hasn't agreed to lose. Never
    /// fails: failures go to the state.
    pub fn save(&self, force: bool) {
        let mut inner = self.inner.lock().unwrap();
        self.save_locked(&mut inner, force);
    }

    /// Reads the file again, replacing the working copy's tables and discarding their unsaved changes. Never fails.
    pub fn reload(&self) {
        let mut inner = self.inner.lock().unwrap();
        self.reload_locked(&mut inner);
    }

    /// One tick: reads the file in when it changed elsewhere, and writes the working copy out when it changed.
    pub fn poll(&self) {
        let Ok(mut inner) = self.inner.try_lock() else {
            return;
        };
        let current = FileStamp::of(&self.file);
        if current != inner.stamp {
            if current.is_none() {
                if !matches!(self.state(), DataFileState::Held { .. }) {
                    self.hold(HeldReason::ChangedOnDisk, format!("{} was moved or deleted. Save to write it again, or close the connection.", self.name()));
                }
            } else if current == inner.held_stamp {
            } else if !self.has_unsaved_changes(&inner) {
                self.reload_locked(&mut inner);
            } else {
                inner.held_stamp = current;
                self.hold(
                    HeldReason::ChangedOnDisk,
                    format!(
                        "{} changed on disk while you had unsaved changes.",
                        self.name()
                    ),
                );
            }
            return;
        }
        let changed = inner
            .copy
            .data_version()
            .is_ok_and(|v| v != inner.seen_version);
        if changed && inner.retry_at.is_none_or(|at| Instant::now() >= at) {
            self.save_locked(&mut inner, false);
        }
    }

    fn has_unsaved_changes(&self, inner: &Inner) -> bool {
        inner
            .copy
            .read(|c| c.states())
            .map(|states| {
                states.len() != inner.known.len()
                    || states.iter().any(|s| {
                        inner
                            .known
                            .get(&s.name.to_lowercase())
                            .map(|k| &k.fingerprint)
                            != Some(&s.fingerprint)
                    })
            })
            .unwrap_or(false)
    }

    fn hold(&self, reason: HeldReason, message: String) {
        self.state
            .send_replace(DataFileState::Held { reason, message });
    }

    fn save_locked(&self, inner: &mut Inner, force: bool) {
        let version = match inner.copy.data_version() {
            Ok(v) => v,
            Err(e) => return self.failed(inner, &e),
        };
        if self.read_only_reason.is_some() {
            inner.seen_version = version;
            return;
        }
        match self.try_save(inner, force, version) {
            Ok(true) => self.reload_locked(inner),
            Ok(false) => {}
            Err(e) => self.failed(inner, &e),
        }
    }

    /// Left unseen, so the next tick tries again: closing the program that locks the file is enough to save.
    fn failed(&self, inner: &mut Inner, e: &DataFileError) {
        self.state.send_replace(DataFileState::Failed(format!(
            "Couldn't save {}: {}",
            self.name(),
            e.0
        )));
        inner.retry_at = Some(Instant::now() + inner.retry_delay);
        inner.retry_delay = (inner.retry_delay * 2).min(MAX_RETRY);
    }

    /// Returns whether what changed on disk should simply be read in instead.
    fn try_save(&self, inner: &mut Inner, force: bool, version: i64) -> Result<bool> {
        inner.copy.begin_read()?;
        let result = self.try_save_reading(inner, force, version);
        inner.copy.end_read();
        result
    }

    fn try_save_reading(&self, inner: &mut Inner, force: bool, version: i64) -> Result<bool> {
        let states = inner.copy.states()?;
        let original = inner
            .known
            .values()
            .next()
            .map(|s| s.name.clone())
            .unwrap_or_else(|| "the original name".into());
        let plan = plan(inner.document.as_ref(), &original, &inner.known, states)?;
        let changed = plan.tables.iter().any(|(s, src)| {
            src.as_ref()
                .is_none_or(|src| src.fingerprint != s.fingerprint)
        }) || !plan.removed.is_empty();
        if !changed {
            inner.seen_version = version;
            // Nothing of ours is unsaved any more, so what changed on disk can simply be read.
            if !force
                && matches!(
                    self.state(),
                    DataFileState::Held {
                        reason: HeldReason::ChangedOnDisk,
                        ..
                    }
                )
            {
                return Ok(true);
            }
            self.state.send_replace(in_sync(plan.session_only));
            return Ok(false);
        }
        // Held changes wait for a new commit or the user, rather than being re-checked every tick.
        let on_disk = FileStamp::of(&self.file);
        if !force && on_disk != inner.stamp {
            inner.seen_version = version;
            let message = if on_disk.is_none() {
                format!("{} was moved or deleted.", self.name())
            } else {
                format!(
                    "{} changed on disk while you had unsaved changes.",
                    self.name()
                )
            };
            inner.held_stamp = on_disk;
            self.hold(HeldReason::ChangedOnDisk, message);
            return Ok(false);
        }
        let sources: BTreeSet<String> = plan
            .tables
            .iter()
            .filter(|(s, src)| {
                src.as_ref()
                    .is_some_and(|src| src.fingerprint != s.fingerprint)
            })
            .filter_map(|(_, src)| src.as_ref().map(|s| s.name.clone()))
            .filter(|s| !inner.confirmed.contains(&s.to_lowercase()))
            .collect();
        if force {
            inner
                .confirmed
                .extend(sources.iter().map(|s| s.to_lowercase()));
        } else if let Some(reason) = inner.document.confirm_before_writing(&sources) {
            inner.seen_version = version;
            self.hold(HeldReason::LosesContent, reason);
            return Ok(false);
        }
        self.state.send_replace(DataFileState::Saving);
        self.write(inner, &plan)?;
        inner.known = plan
            .written
            .iter()
            .map(|s| (s.name.to_lowercase(), s.clone()))
            .collect();
        inner.stamp = FileStamp::of(&self.file);
        inner.held_stamp = None;
        inner.seen_version = version;
        self.state.send_replace(in_sync(plan.session_only));
        inner.retry_at = None;
        inner.retry_delay = POLL;
        Ok(false)
    }

    fn write(&self, inner: &mut Inner, plan: &Plan) -> Result<()> {
        let directory = self
            .file
            .parent()
            .ok_or_else(|| DataFileError(format!("{} has no folder", self.name())))?;
        let temporary = directory.join(format!(
            ".{}.tablegrip-{:08x}.tmp",
            self.name(),
            std::process::id() ^ now_millis() as u32
        ));
        let Inner { copy, document, .. } = inner;
        let copy: &WorkingCopy = copy;
        let tables: Vec<OutputTable<'_>> = plan
            .tables
            .iter()
            .map(|(state, source)| {
                let name = state.name.clone();
                let columns = state.columns.clone();
                OutputTable {
                    name: state.name.clone(),
                    source: source.as_ref().map(|s| s.name.clone()),
                    changed: source
                        .as_ref()
                        .is_none_or(|s| s.fingerprint != state.fingerprint),
                    columns: state.columns.clone(),
                    rows: Box::new(move || copy.rows(&name, &columns)),
                }
            })
            .collect();
        let result = (|| -> Result<()> {
            document.write(&temporary, &tables, &plan.removed)?;
            // Keep the original's permissions where the file system has them.
            if let Ok(m) = std::fs::metadata(&self.file) {
                let _ = std::fs::set_permissions(&temporary, m.permissions());
            }
            if std::fs::rename(&temporary, &self.file).is_err() {
                std::fs::remove_file(&self.file).map_err(|e| describe(&self.file, &e))?;
                std::fs::rename(&temporary, &self.file).map_err(|e| describe(&self.file, &e))?;
            }
            Ok(())
        })();
        let _ = std::fs::remove_file(&temporary);
        result
    }

    fn reload_locked(&self, inner: &mut Inner) {
        let before = FileStamp::of(&self.file);
        let result = (|| -> Result<()> {
            let mut fresh = inner.format.open(&self.file, &inner.options)?;
            let replacing: Vec<String> = inner.known.values().map(|s| s.name.clone()).collect();
            inner.copy.load(fresh.as_mut(), &replacing, &mut |_| {})?;
            let tables = fresh.tables();
            inner.document = fresh;
            inner.known = inner
                .copy
                .read(|c| c.states())?
                .into_iter()
                .filter(|s| tables.iter().any(|t| t.name.eq_ignore_ascii_case(&s.name)))
                .map(|s| (s.name.to_lowercase(), s))
                .collect();
            inner.stamp = before.clone();
            inner.held_stamp = None;
            inner.confirmed.clear();
            inner.seen_version = inner.copy.data_version()?;
            Ok(())
        })();
        match result {
            Ok(()) => {
                self.revision.send_modify(|r| *r += 1);
                self.state.send_replace(in_sync(Vec::new()));
            }
            Err(e) => {
                inner.held_stamp = before;
                self.state.send_replace(DataFileState::Failed(format!(
                    "Couldn't read {} again: {}",
                    self.name(),
                    e.0
                )));
            }
        }
    }

    /// Stops watching, and writes anything committed since the last write (waiting up to a few seconds). Unsaved
    /// changes that are held or keep failing stay in the working copy, which is then deleted.
    pub fn close(&self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        let deadline = Instant::now() + CLOSE_TIMEOUT;
        let mut inner = loop {
            match self.inner.try_lock() {
                Ok(i) => break Some(i),
                Err(_) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(20))
                }
                Err(_) => break None,
            }
        };
        if let Some(inner) = inner.as_mut()
            && self.read_only_reason.is_none()
            && inner
                .copy
                .data_version()
                .is_ok_and(|v| v != inner.seen_version)
            && !matches!(self.state(), DataFileState::Held { .. })
        {
            self.save_locked(inner, false);
        }
    }
}

fn describe(path: &Path, e: &std::io::Error) -> DataFileError {
    DataFileError(match e.kind() {
        std::io::ErrorKind::NotFound => format!("{} doesn't exist", path.display()),
        std::io::ErrorKind::PermissionDenied => format!(
            "access denied to {} (is it open in another program, or read-only?)",
            path.display()
        ),
        _ => format!("{e} ({})", path.display()),
    })
}

fn plan(
    document: &dyn DataFileDocument,
    original_name: &str,
    known: &HashMap<String, TableState>,
    states: Vec<TableState>,
) -> Result<Plan> {
    let mut unmatched: Vec<TableState> = known.values().cloned().collect();
    unmatched.sort_by(|a, b| a.name.cmp(&b.name));
    let mut matched: Vec<(TableState, Option<TableState>)> = Vec::new();
    for state in &states {
        let same = unmatched
            .iter()
            .position(|k| k.name.eq_ignore_ascii_case(&state.name))
            .map(|i| unmatched.remove(i));
        matched.push((state.clone(), same));
    }
    // A table gone from the file's list and an identical new one is a rename.
    for entry in &mut matched {
        if entry.1.is_some() {
            continue;
        }
        if let Some(i) = unmatched
            .iter()
            .position(|k| k.fingerprint == entry.0.fingerprint)
        {
            entry.1 = Some(unmatched.remove(i));
        }
    }
    if !document.multi_table() {
        let primary = matched
            .iter()
            .position(|(_, s)| s.is_some())
            .or_else(|| (states.len() == 1).then_some(0))
            .ok_or_else(|| {
                DataFileError(if states.is_empty() {
                    "The table is gone; this file holds exactly one table.".into()
                } else {
                    format!(
                        "The file's table is gone and there are several others; this file holds exactly one. Rename the one to save to {original_name}."
                    )
                })
            })?;
        let (state, source) = matched[primary].clone();
        let source = source.or_else(|| known.values().next().cloned());
        let session_only = states
            .iter()
            .filter(|s| s.name != state.name)
            .map(|s| s.name.clone())
            .collect();
        return Ok(Plan {
            tables: vec![(state.clone(), source)],
            written: vec![state],
            removed: BTreeSet::new(),
            session_only,
        });
    }
    Ok(Plan {
        tables: matched,
        written: states,
        removed: unmatched.into_iter().map(|s| s.name).collect(),
        session_only: Vec::new(),
    })
}

//! The Import Connections dialog's state (`connections/ImportConnections.kt`): the sources found on this machine and
//! those the user added, every connection they hold, and which to save. Connections already saved, or found in two
//! tools, start unticked, so importing twice never duplicates anything. Reading the sources is [`scan`], a blocking
//! call the app runs off the UI thread; the readers are tg-data's (S5).

use std::collections::HashMap;
use std::path::Path;

use tg_core::connection::{ConnectionConfig, DriverId};
use tg_data::imports::{self, ImportCandidate, ImportScan, ImportSource};

/// One connection offered for import.
#[derive(Clone, Debug, PartialEq)]
pub struct ImportRow {
    pub candidate: ImportCandidate,
    /// The name of a saved profile for the same database: importing would add a second one.
    pub already_saved: Option<String>,
    /// An earlier row, from another tool, is for the same database.
    pub duplicate: bool,
    pub selected: bool,
    /// Another tool may know the password this one didn't keep: duplicates hand theirs to the row that's imported.
    pub password: Option<String>,
}

impl ImportRow {
    /// Engines that log in with a password and have none to import.
    pub fn needs_password(&self) -> bool {
        self.password.is_none()
            && self.candidate.config.db_type.is_server()
            && self.candidate.config.effective_driver() != DriverId::Etcd
    }
}

/// What one source contributed.
#[derive(Clone, Debug, PartialEq)]
pub struct ImportGroup {
    pub source: ImportSource,
    pub rows: Vec<ImportRow>,
    pub skipped: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct ImportState {
    pub groups: Vec<ImportGroup>,
    pub scanning: bool,
    pub saving: bool,
    /// Imported connections keep the folder they had in their tool as their group.
    pub keep_groups: bool,
    pub message: Option<String>,
}

/// Reads `sources` and fills PostgreSQL passwords from `.pgpass`. Blocking.
pub fn scan(
    sources: Vec<ImportSource>,
    home: &Path,
    env: &HashMap<String, String>,
) -> Vec<(ImportSource, ImportScan)> {
    let pgpass = imports::pg_pass_file(home, env);
    sources
        .into_iter()
        .map(|source| {
            let mut read = imports::read(&source, home);
            read.candidates = imports::fill_passwords_from_pg_pass(read.candidates, &pgpass);
            (source, read)
        })
        .collect()
}

impl ImportState {
    pub fn new() -> Self {
        Self {
            keep_groups: true,
            scanning: true,
            ..Self::default()
        }
    }

    pub fn rows(&self) -> impl Iterator<Item = &ImportRow> {
        self.groups.iter().flat_map(|g| g.rows.iter())
    }

    pub fn selected(&self) -> Vec<&ImportRow> {
        self.rows().filter(|r| r.selected).collect()
    }

    /// A picked file or folder: the source it is, or why it isn't one (the message to show).
    pub fn source_for(&mut self, path: &Path) -> Option<ImportSource> {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        match imports::detect(path) {
            None => {
                self.message = Some(format!(
                    "TableGrip doesn't recognise {name} as a connection list."
                ));
                None
            }
            Some(s) if self.groups.iter().any(|g| g.source.path == s.path) => {
                self.message = Some(format!("{name} is already in the list."));
                None
            }
            Some(s) => {
                self.scanning = true;
                self.message = None;
                Some(s)
            }
        }
    }

    /// Adds what a scan read, checking each candidate against the saved profiles and the rows already listed.
    pub fn append(&mut self, read: Vec<(ImportSource, ImportScan)>, saved: &[ConnectionConfig]) {
        self.scanning = false;
        if !read.is_empty() && read.iter().all(|(_, s)| s.candidates.is_empty()) {
            let names: Vec<String> = read
                .iter()
                .map(|(s, _)| {
                    s.path
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default()
                })
                .collect();
            self.message = Some(format!("No connections found in {}.", names.join(", ")));
        }
        let saved: HashMap<String, String> = saved
            .iter()
            .map(|c| (imports::key_of(c), c.name.clone()))
            .collect();
        // Where each key was first seen: (group, row).
        let mut seen: HashMap<String, (usize, usize)> = HashMap::new();
        for (g, group) in self.groups.iter().enumerate() {
            for (r, row) in group.rows.iter().enumerate() {
                seen.entry(row.candidate.key()).or_insert((g, r));
            }
        }
        for (source, scan) in read {
            let g = self.groups.len();
            let mut rows = Vec::new();
            for candidate in scan.candidates {
                let key = candidate.key();
                let earlier = seen.get(&key).copied();
                if let Some((eg, er)) = earlier {
                    if eg < self.groups.len() {
                        let row = &mut self.groups[eg].rows[er];
                        if row.password.is_none() && candidate.password.is_some() {
                            row.password = candidate.password.clone();
                        }
                    } else if let Some(row) = rows.get_mut(er)
                        && row_password_missing(row)
                    {
                        row_set_password(row, &candidate);
                    }
                }
                let already_saved = saved.get(&key).cloned();
                let duplicate = earlier.is_some();
                seen.entry(key).or_insert((g, rows.len()));
                rows.push(ImportRow {
                    selected: already_saved.is_none() && !duplicate,
                    password: candidate.password.clone(),
                    candidate,
                    already_saved,
                    duplicate,
                });
            }
            if !rows.is_empty() || !scan.skipped.is_empty() {
                self.groups.push(ImportGroup {
                    source,
                    rows,
                    skipped: scan.skipped,
                });
            }
        }
    }

    pub fn toggle(&mut self, group: usize, row: usize) {
        if let Some(r) = self.groups.get_mut(group).and_then(|g| g.rows.get_mut(row)) {
            r.selected = !r.selected;
        }
    }

    /// Ticks every row of a group, or unticks them all when all were ticked.
    pub fn toggle_group(&mut self, group: usize) {
        if let Some(g) = self.groups.get_mut(group) {
            let select = !g.rows.iter().all(|r| r.selected);
            for r in &mut g.rows {
                r.selected = select;
            }
        }
    }

    /// The ticked connections with their password and SSH secret, as they will be saved.
    pub fn chosen(&self) -> Vec<(ConnectionConfig, Option<String>, Option<String>)> {
        self.selected()
            .into_iter()
            .map(|row| {
                let mut config = row.candidate.config.clone();
                if !self.keep_groups {
                    config.group = String::new();
                }
                (
                    config,
                    row.password.clone(),
                    row.candidate.ssh_secret.clone(),
                )
            })
            .collect()
    }

    /// The status line after saving `items`.
    pub fn summary(items: &[(ConnectionConfig, Option<String>, Option<String>)]) -> String {
        let missing = items
            .iter()
            .filter(|(c, p, _)| p.is_none() && c.db_type.is_server())
            .count();
        let head = if items.len() == 1 {
            format!("Imported {}", items[0].0.name)
        } else {
            format!("Imported {} connections", items.len())
        };
        if missing > 0 {
            format!("{head} · {missing} without a password: add it with Edit Connection")
        } else {
            head
        }
    }
}

fn row_password_missing(row: &ImportRow) -> bool {
    row.password.is_none()
}

fn row_set_password(row: &mut ImportRow, candidate: &ImportCandidate) {
    if candidate.password.is_some() {
        row.password = candidate.password.clone();
    }
}

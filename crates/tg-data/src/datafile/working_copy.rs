//! The SQLite database a data file is edited in (`datafile/WorkingCopy.kt`). The explorer, table viewers and consoles
//! use it like any SQLite database (through their own connections); this side loads the file's tables into it and
//! reads them back out, over a connection of its own that the sync owns.
//!
//! Every loaded table gets a leading [`ROW_KEY`] column, `INTEGER PRIMARY KEY`: it's what lets the table viewer edit
//! and delete rows, and it keeps rows in file order. It's never written to the file.

use super::{
    CellValue, ColumnKind, DataFileDocument, DataFileError, DataValues, OutputColumn, Result,
};
use rusqlite::types::ValueRef;
use rusqlite::{Connection, params_from_iter};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// The row key column; `_row_2`, `_row_3`… when the file has a column of that name.
pub const ROW_KEY: &str = "_row";
const BATCH_SIZE: usize = 1_000;

/// A table of the working copy: its columns as the file holds them (the row key left out), and a digest of its content.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableState {
    pub name: String,
    pub columns: Vec<OutputColumn>,
    pub fingerprint: String,
}

pub struct WorkingCopy {
    pub path: PathBuf,
    connection: Connection,
    /// The row key column of each table loaded from the file, by lower-case table name.
    keys: HashMap<String, String>,
}

pub fn quote(name: &str) -> String {
    format!("\"{}\"", name.replace('"', "\"\""))
}

pub fn kind_of(declared: &str) -> ColumnKind {
    if declared.contains("INT") {
        ColumnKind::Integer
    } else if ["REAL", "FLOA", "DOUB", "NUM", "DEC"]
        .iter()
        .any(|t| declared.contains(t))
    {
        ColumnKind::Real
    } else {
        ColumnKind::Text
    }
}

fn is_row_key(name: &str) -> bool {
    name == ROW_KEY
        || name
            .strip_prefix("_row_")
            .is_some_and(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()))
}

impl WorkingCopy {
    pub fn open(path: &Path) -> Result<Self> {
        let connection = Connection::open(path)?;
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.busy_timeout(std::time::Duration::from_millis(10_000))?;
        Ok(Self {
            path: path.to_owned(),
            connection,
            keys: HashMap::new(),
        })
    }

    /// Changes when another connection commits to the database (SQLite's `data_version`).
    pub fn data_version(&self) -> Result<i64> {
        Ok(self
            .connection
            .query_row("PRAGMA data_version", [], |r| r.get(0))?)
    }

    /// Replaces `replacing` (tables that came from the file) with the document's tables, in one transaction: nothing
    /// changes when a table fails to load. `progress` gets the rows loaded so far.
    pub fn load(
        &mut self,
        document: &mut dyn DataFileDocument,
        replacing: &[String],
        progress: &mut dyn FnMut(u64),
    ) -> Result<()> {
        let tables = document.tables();
        self.connection.execute_batch("BEGIN")?;
        let result = (|| -> Result<HashMap<String, String>> {
            let existing: HashMap<String, String> = self
                .table_names()?
                .into_iter()
                .map(|n| (n.to_lowercase(), n))
                .collect();
            for name in replacing.iter().chain(tables.iter().map(|t| &t.name)) {
                if let Some(table) = existing.get(&name.to_lowercase()) {
                    self.connection
                        .execute_batch(&format!("DROP TABLE IF EXISTS {}", quote(table)))?;
                }
            }
            let mut loaded = 0u64;
            let mut keys = HashMap::new();
            for (index, table) in tables.iter().enumerate() {
                let rows = document.rows(index)?;
                let key = load_table(
                    &self.connection,
                    &table.name,
                    &table.columns,
                    rows,
                    &mut || {
                        loaded += 1;
                        if loaded.is_multiple_of(10_000) {
                            progress(loaded);
                        }
                    },
                )?;
                keys.insert(table.name.to_lowercase(), key);
            }
            progress(loaded);
            Ok(keys)
        })();
        match result {
            Ok(keys) => {
                self.connection.execute_batch("COMMIT")?;
                for r in replacing {
                    self.keys.remove(&r.to_lowercase());
                }
                self.keys.extend(keys);
                Ok(())
            }
            Err(e) => {
                let _ = self.connection.execute_batch("ROLLBACK");
                Err(e)
            }
        }
    }

    /// The user's tables, in the order they were created.
    pub fn table_names(&self) -> Result<Vec<String>> {
        let mut st = self.connection.prepare(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY rowid",
        )?;
        let names = st
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<_>>()?;
        Ok(names)
    }

    /// Runs `block` in one read transaction, so every table it reads is from the same moment.
    pub fn read<T>(&self, block: impl FnOnce(&Self) -> Result<T>) -> Result<T> {
        self.connection.execute_batch("BEGIN")?;
        let result = block(self);
        let _ = self.connection.execute_batch("ROLLBACK");
        result
    }

    pub(crate) fn begin_read(&self) -> Result<()> {
        Ok(self.connection.execute_batch("BEGIN")?)
    }

    pub(crate) fn end_read(&self) {
        let _ = self.connection.execute_batch("ROLLBACK");
    }

    /// Every table with its file columns and content digest. Call inside [`read`](Self::read).
    pub fn states(&self) -> Result<Vec<TableState>> {
        self.table_names()?
            .into_iter()
            .map(|n| self.state(&n))
            .collect()
    }

    fn state(&self, name: &str) -> Result<TableState> {
        let columns = self.file_columns(name)?;
        let mut digest = Sha256::new();
        for c in &columns {
            digest.update(format!("{}\u{0}{:?}\u{1}", c.name, c.kind).as_bytes());
        }
        digest.update(b"\x02");
        for row in self.rows(name, &columns)? {
            for value in row {
                match &value {
                    CellValue::Null => digest.update(b"\x03"),
                    CellValue::Blob(b) => {
                        digest.update(b"b");
                        digest.update(b);
                    }
                    other => {
                        let tag = match other {
                            CellValue::Integer(_) => "L",
                            CellValue::Real(_) => "D",
                            _ => "S",
                        };
                        digest.update(
                            format!("{tag}{}", DataValues::text(other).unwrap_or_default())
                                .as_bytes(),
                        );
                    }
                }
                digest.update(b"\x00");
            }
            digest.update(b"\x01");
        }
        let fingerprint = digest
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        Ok(TableState {
            name: name.to_owned(),
            columns,
            fingerprint,
        })
    }

    /// `name`'s columns minus the row key TableGrip added, with each one's kind from its declared type.
    fn file_columns(&self, name: &str) -> Result<Vec<OutputColumn>> {
        let key = self.keys.get(&name.to_lowercase());
        let mut st = self
            .connection
            .prepare(&format!("PRAGMA table_info({})", quote(name)))?;
        let columns = st
            .query_map([], |r| {
                Ok((
                    r.get::<_, String>("name")?,
                    r.get::<_, Option<String>>("type")?
                        .unwrap_or_default()
                        .to_uppercase(),
                    r.get::<_, i64>("pk")? > 0,
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(columns
            .into_iter()
            .filter(|(column, kind, pk)| {
                !(*pk && kind == "INTEGER" && (Some(column) == key || is_row_key(column)))
            })
            .map(|(column, kind, _)| OutputColumn {
                name: column,
                kind: kind_of(&kind),
            })
            .collect())
    }

    /// The rows of `table`, in file order. Call inside [`read`](Self::read).
    pub fn rows(&self, table: &str, columns: &[OutputColumn]) -> Result<Vec<Vec<CellValue>>> {
        let list: Vec<String> = columns.iter().map(|c| quote(&c.name)).collect();
        let select = format!("SELECT {} FROM {}", list.join(", "), quote(table));
        // Tables created WITHOUT ROWID have no rowid to order by; they come back in key order.
        let mut st = match self.connection.prepare(&format!("{select} ORDER BY rowid")) {
            Ok(st) => st,
            Err(_) => self.connection.prepare(&select)?,
        };
        let count = columns.len();
        let rows = st
            .query_map([], |r| {
                (0..count)
                    .map(|i| {
                        Ok(match r.get_ref(i)? {
                            ValueRef::Null => CellValue::Null,
                            ValueRef::Integer(v) => CellValue::Integer(v),
                            ValueRef::Real(v) => CellValue::Real(v),
                            ValueRef::Text(t) => {
                                CellValue::Text(String::from_utf8_lossy(t).into_owned())
                            }
                            ValueRef::Blob(b) => CellValue::Blob(b.to_vec()),
                        })
                    })
                    .collect::<rusqlite::Result<Vec<_>>>()
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Deletes a working copy and its WAL files; call once every connection to it is closed.
    pub fn delete(path: &Path) {
        for suffix in ["", "-wal", "-shm", "-journal"] {
            let mut p = path.as_os_str().to_owned();
            p.push(suffix);
            let _ = std::fs::remove_file(PathBuf::from(p));
        }
    }

    /// A fresh path for `id`'s working copy under `directory`. Its name starts with this process's id, so copies left
    /// behind by a crash are recognised (their process is gone) and deleted here.
    pub fn new_path(directory: &Path, id: &str) -> Result<PathBuf> {
        std::fs::create_dir_all(directory)?;
        clean_up(directory);
        let safe: String = id
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                    c
                } else {
                    '_'
                }
            })
            .take(40)
            .collect();
        let random = format!("{:08x}", rand_u32());
        Ok(directory.join(format!("{}-{safe}-{random}.sqlite", std::process::id())))
    }
}

fn rand_u32() -> u32 {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos()),
    );
    h.finish() as u32
}

fn process_alive(pid: u32) -> bool {
    #[cfg(unix)]
    {
        // Signal 0 checks the process exists without touching it.
        std::process::Command::new("kill")
            .args(["-0", &pid.to_string()])
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
    }
    #[cfg(not(unix))]
    {
        let _ = pid;
        true
    }
}

fn clean_up(directory: &Path) {
    let Ok(entries) = std::fs::read_dir(directory) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let Some(pid) = name.split('-').next().and_then(|p| p.parse::<u32>().ok()) else {
            continue;
        };
        if pid != std::process::id() && !process_alive(pid) {
            let _ = std::fs::remove_file(entry.path());
        }
    }
}

/// Loads one table: first as untyped text into a staging table while each column's kind is worked out, then into the
/// typed table. A column is numeric only when every value already reads exactly as it would be written back, so the
/// round trip never changes the file's text.
fn load_table(
    connection: &Connection,
    name: &str,
    columns: &[String],
    rows: super::SourceRows<'_>,
    on_row: &mut dyn FnMut(),
) -> Result<String> {
    if columns.is_empty() {
        return Err(DataFileError(format!("\"{name}\" has no columns")));
    }
    let staging = format!("_tablegrip_load_{:08x}{:08x}", rand_u32(), rand_u32());
    let staging_columns: Vec<String> = (0..columns.len()).map(|i| format!("c{i}")).collect();
    let quoted: Vec<String> = staging_columns.iter().map(|c| quote(c)).collect();
    connection.execute_batch(&format!(
        "CREATE TEMP TABLE {} ({})",
        quote(&staging),
        quoted.join(", ")
    ))?;
    let mut integer = vec![true; columns.len()];
    let mut real = vec![true; columns.len()];
    let mut non_empty = vec![false; columns.len()];
    {
        let placeholders = vec!["?"; columns.len()].join(", ");
        let mut insert = connection.prepare(&format!(
            "INSERT INTO temp.{} VALUES ({placeholders})",
            quote(&staging)
        ))?;
        let mut batched = 0;
        for row in rows {
            let row = row?;
            for (i, value) in row.iter().take(columns.len()).enumerate() {
                if let Some(v) = value {
                    non_empty[i] = true;
                    if integer[i] && !DataValues::is_integer(v) {
                        integer[i] = false;
                    }
                    if real[i] && !DataValues::is_real(v) {
                        real[i] = false;
                    }
                }
            }
            let mut values: Vec<Option<&str>> = row.iter().map(|v| v.as_deref()).collect();
            values.resize(columns.len(), None);
            insert.execute(params_from_iter(values))?;
            on_row();
            batched += 1;
            if batched == BATCH_SIZE {
                batched = 0;
            }
        }
    }
    // An all-empty column stays text: there's nothing to say it's a number.
    let kinds: Vec<ColumnKind> = (0..columns.len())
        .map(|i| match (non_empty[i], integer[i], real[i]) {
            (false, _, _) => ColumnKind::Text,
            (_, true, _) => ColumnKind::Integer,
            (_, _, true) => ColumnKind::Real,
            _ => ColumnKind::Text,
        })
        .collect();
    let mut key = ROW_KEY.to_owned();
    let mut n = 2;
    while columns.iter().any(|c| c.eq_ignore_ascii_case(&key)) {
        key = format!("{ROW_KEY}_{n}");
        n += 1;
    }
    let mut definitions = vec![format!("{} INTEGER PRIMARY KEY", quote(&key))];
    definitions.extend(
        columns
            .iter()
            .zip(&kinds)
            .map(|(c, k)| format!("{} {}", quote(c), k.sql_type())),
    );
    let names: Vec<String> = columns.iter().map(|c| quote(c)).collect();
    connection.execute_batch(&format!(
        "CREATE TABLE {table} ({defs}); INSERT INTO {table} ({names}) SELECT {staged} FROM temp.{staging} ORDER BY rowid; DROP TABLE temp.{staging};",
        table = quote(name),
        defs = definitions.join(", "),
        names = names.join(", "),
        staged = quoted.join(", "),
        staging = quote(&staging),
    ))?;
    Ok(key)
}

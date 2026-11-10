//! Recognises database files by their content, never by their name (`connection/DatabaseFiles.kt`).

use std::fs::File;
use std::io::Read;
use std::path::Path;
use tg_sql::model::DatabaseType;

const SQLITE_HEADER: &[u8] = b"SQLite format 3\0";
const DUCKDB_MAGIC: &[u8] = b"DUCK";

/// The engine whose file `path` is, or None if it's none TableGrip opens, is empty, or can't be read.
pub fn detect(path: &Path) -> Option<DatabaseType> {
    if !path.is_file() {
        return None;
    }
    let mut header = Vec::with_capacity(16);
    File::open(path)
        .ok()?
        .take(16)
        .read_to_end(&mut header)
        .ok()?;
    if header.starts_with(SQLITE_HEADER) {
        Some(DatabaseType::SQLITE)
    } else if header.len() >= 12 && &header[8..12] == DUCKDB_MAGIC {
        // DuckDB's main header: a checksum, then the magic bytes.
        Some(DatabaseType::DUCKDB)
    } else {
        None
    }
}

/// Why `path` can't be opened as `db_type`, or None if it can. A missing or empty file is fine: SQLite creates the
/// database in it.
pub fn mismatch(path: &Path, db_type: DatabaseType) -> Option<String> {
    let size = if path.is_file() {
        path.metadata().map(|m| m.len()).unwrap_or(0)
    } else {
        0
    };
    if size == 0 {
        return None;
    }
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    match detect(path) {
        Some(found) if found == db_type => None,
        None => Some(format!(
            "{name} isn't a {} database: its header doesn't match.",
            db_type.display_name
        )),
        Some(found) => Some(format!(
            "{name} is a {} database, not {}.",
            found.display_name, db_type.display_name
        )),
    }
}

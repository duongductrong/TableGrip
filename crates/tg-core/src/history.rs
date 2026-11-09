//! Append-only log of executed statements, one JSON object per line (`history/QueryHistory.kt`).

use crate::json::{Baseline, omit_defaults};
use serde::Deserialize;
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryEntry {
    pub connection_id: String,
    pub sql: String,
    /// Epoch milliseconds.
    pub executed_at: i64,
    pub elapsed_millis: i64,
    #[serde(default)]
    pub error: Option<String>,
}

impl HistoryEntry {
    pub fn new(connection_id: &str, sql: &str, executed_at: i64, elapsed_millis: i64) -> Self {
        Self {
            connection_id: connection_id.to_string(),
            sql: sql.to_string(),
            executed_at,
            elapsed_millis,
            error: None,
        }
    }
}

impl Baseline for HistoryEntry {
    fn baseline(&self) -> Self {
        Self::new("", "", 0, 0)
    }
}
omit_defaults!(HistoryEntry {
    connection_id: "connectionId" = req,
    sql: "sql" = req,
    executed_at: "executedAt" = req,
    elapsed_millis: "elapsedMillis" = req,
    error: "error",
});

#[derive(Clone, Debug)]
pub struct QueryHistory {
    file: PathBuf,
}

impl QueryHistory {
    pub fn new(file: impl Into<PathBuf>) -> Self {
        Self { file: file.into() }
    }

    pub fn file(&self) -> &Path {
        &self.file
    }

    pub fn append(&self, entry: &HistoryEntry) -> std::io::Result<()> {
        if let Some(parent) = self.file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let line = serde_json::to_string(entry).map_err(std::io::Error::other)? + "\n";
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.file)?
            .write_all(line.as_bytes())
    }

    /// Most recent first. Corrupt lines (e.g. from a crash mid-write) are skipped.
    pub fn recent(&self, connection_id: Option<&str>, limit: usize) -> Vec<HistoryEntry> {
        let Ok(bytes) = std::fs::read(&self.file) else {
            return Vec::new();
        };
        String::from_utf8_lossy(&bytes)
            .lines()
            .rev()
            .filter_map(|line| serde_json::from_str::<HistoryEntry>(line).ok())
            .filter(|e| connection_id.is_none_or(|id| e.connection_id == id))
            .take(limit)
            .collect()
    }
}

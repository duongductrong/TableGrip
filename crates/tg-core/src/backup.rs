//! Scheduled backups (`backup/Schedule.kt`). The schedules live in settings.json; running them is the app's job.

use serde::Deserialize;
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::json::{Baseline, omit_defaults};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, serde::Serialize)]
pub enum BackupFrequency {
    Hourly,
    Daily,
    Weekly,
}

impl BackupFrequency {
    pub fn label(self) -> &'static str {
        match self {
            Self::Hourly => "Every hour",
            Self::Daily => "Every day",
            Self::Weekly => "Every week",
        }
    }

    pub fn interval(self) -> Duration {
        Duration::from_secs(match self {
            Self::Hourly => 3_600,
            Self::Daily => 86_400,
            Self::Weekly => 7 * 86_400,
        })
    }
}

/// A backup TableGrip makes by itself while it is open. Nothing runs unless `Settings::scheduled_backups` is on.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupSchedule {
    pub id: String,
    pub connection_id: String,
    /// Blank means the connection's own database.
    #[serde(default)]
    pub database: String,
    #[serde(default = "daily")]
    pub frequency: BackupFrequency,
    /// Blank means `~/Backups/<connection>`.
    #[serde(default)]
    pub folder: String,
    /// Newest backups kept in the folder; older ones made by this schedule are deleted. 0 keeps all.
    #[serde(default = "seven")]
    pub keep: i32,
    #[serde(default = "yes")]
    pub enabled: bool,
    /// When it last finished, successfully or not, in epoch milliseconds; None if never.
    #[serde(default)]
    pub last_run: Option<i64>,
    #[serde(default)]
    pub last_error: Option<String>,
}

fn daily() -> BackupFrequency {
    BackupFrequency::Daily
}

fn seven() -> i32 {
    7
}

fn yes() -> bool {
    true
}

impl BackupSchedule {
    pub fn new(id: &str, connection_id: &str) -> Self {
        Self {
            id: id.to_string(),
            connection_id: connection_id.to_string(),
            database: String::new(),
            frequency: BackupFrequency::Daily,
            folder: String::new(),
            keep: 7,
            enabled: true,
            last_run: None,
            last_error: None,
        }
    }

    /// Whether it should run at `now_millis` (epoch milliseconds); a run missed while the app was closed is due as soon
    /// as it opens.
    pub fn is_due(&self, now_millis: i64) -> bool {
        self.enabled
            && match self.last_run {
                None => true,
                Some(last) => {
                    now_millis.saturating_sub(last) >= self.frequency.interval().as_millis() as i64
                }
            }
    }

    /// File names start with this, so pruning only touches this schedule's backups.
    pub fn prefix(&self) -> String {
        format!("scheduled-{}-", self.id)
    }

    /// Deletes all but the newest `keep` backups of `schedule` in `folder`; returns what was deleted.
    pub fn prune(folder: &Path, schedule: &BackupSchedule) -> Vec<PathBuf> {
        if schedule.keep <= 0 || !folder.is_dir() {
            return Vec::new();
        }
        let prefix = schedule.prefix();
        let Ok(entries) = std::fs::read_dir(folder) else {
            return Vec::new();
        };
        let mut mine: Vec<PathBuf> = entries
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| {
                p.file_name()
                    .is_some_and(|n| n.to_string_lossy().starts_with(&prefix))
            })
            .collect();
        mine.sort_by(|a, b| b.file_name().cmp(&a.file_name()));
        let old: Vec<PathBuf> = mine.into_iter().skip(schedule.keep as usize).collect();
        for path in &old {
            discard(path);
        }
        old
    }
}

/// Removes what a failed or cancelled backup left: a file or a folder, ignoring errors.
pub fn discard(target: &Path) {
    let _ = if target.is_dir() {
        std::fs::remove_dir_all(target)
    } else {
        std::fs::remove_file(target)
    };
}

impl Baseline for BackupSchedule {
    fn baseline(&self) -> Self {
        Self {
            id: self.id.clone(),
            connection_id: self.connection_id.clone(),
            ..Self::new("", "")
        }
    }
}
omit_defaults!(BackupSchedule {
    id: "id" = req,
    connection_id: "connectionId" = req,
    database: "database",
    frequency: "frequency",
    folder: "folder",
    keep: "keep",
    enabled: "enabled",
    last_run: "lastRun",
    last_error: "lastError",
});

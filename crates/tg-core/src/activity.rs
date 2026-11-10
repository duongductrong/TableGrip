//! What the activity monitor lists, and which engines it supports (`activity/Activity.kt`). The Kotlin file has no
//! storage, only the monitor: this keeps the data types and the pure rules. The engine-specific queries belong to the
//! drivers (M3).

use chrono::{DateTime, Duration, Utc};
use tg_sql::SqlDialect;
use tg_sql::model::{DatabaseType, EngineFamily, Paradigm};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionState {
    Active,
    /// Waiting on a lock held by another session.
    Blocked,
    IdleInTransaction,
    Idle,
    Other,
}

impl SessionState {
    pub fn label(self) -> &'static str {
        match self {
            Self::Active => "active",
            Self::Blocked => "blocked",
            Self::IdleInTransaction => "idle in transaction",
            Self::Idle => "idle",
            Self::Other => "other",
        }
    }
}

/// One server session, as the activity monitor lists it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SessionActivity {
    /// The server's id for it: the backend pid on PostgreSQL.
    pub id: String,
    pub user: Option<String>,
    pub database: Option<String>,
    pub client: Option<String>,
    pub application: Option<String>,
    pub state: SessionState,
    /// What it's waiting for, e.g. `Lock: transactionid`; None when it isn't.
    pub wait: Option<String>,
    /// The running query, or the last one for idle sessions.
    pub query: Option<String>,
    pub query_start: Option<DateTime<Utc>>,
    pub transaction_start: Option<DateTime<Utc>>,
    /// Sessions holding locks this one waits for.
    pub blocked_by: Vec<String>,
    /// The monitor's own session, which can't be cancelled or terminated from the monitor.
    pub own: bool,
}

impl SessionActivity {
    /// How long the current query (or, when idle in a transaction, the transaction) has run at `now`.
    pub fn running_for(&self, now: DateTime<Utc>) -> Option<Duration> {
        let since = if self.state == SessionState::IdleInTransaction {
            self.transaction_start
        } else if self.state != SessionState::Idle {
            self.query_start
        } else {
            None
        };
        since.map(|s| (now - s).max(Duration::zero()))
    }
}

/// What the monitor read: the sessions and the server's clock, so durations don't depend on the local one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ActivitySnapshot {
    pub sessions: Vec<SessionActivity>,
    pub server_time: DateTime<Utc>,
}

/// Whether an engine has an activity monitor, and why not.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ActivitySupport {
    Supported,
    /// A server engine the monitor doesn't read yet.
    NotYet(String),
    /// Embedded and file databases have no other sessions to watch; the monitor isn't offered at all.
    NotApplicable,
}

impl ActivitySupport {
    pub fn of(db_type: &DatabaseType) -> ActivitySupport {
        if db_type.paradigm != Paradigm::Relational
            || !db_type.is_server()
            || db_type.family == EngineFamily::Generic
        {
            ActivitySupport::NotApplicable
        } else if has_reader(db_type) {
            ActivitySupport::Supported
        } else {
            ActivitySupport::NotYet(format!(
                "The activity monitor doesn't support {} yet. It supports PostgreSQL, MySQL and MariaDB.",
                db_type.display_name
            ))
        }
    }
}

/// Whether the engine has an `ActivityReader` in Kotlin: PostgreSQL's dialect (not CockroachDB's, which has neither
/// `pg_stat_activity`'s columns nor `pg_blocking_pids`) and MySQL and MariaDB themselves (TiDB and PlanetScale speak
/// the protocol but list and kill sessions their own way).
fn has_reader(db_type: &DatabaseType) -> bool {
    match db_type.dialect() {
        Some(SqlDialect::PostgreSQL) => true,
        Some(SqlDialect::MySQL) => {
            *db_type == DatabaseType::MYSQL || *db_type == DatabaseType::MARIADB
        }
        _ => false,
    }
}

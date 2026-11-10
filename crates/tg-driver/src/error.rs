//! Errors (`driver/QueryError.kt` and the exceptions `Database.kt` throws). Messages the user sees keep the Kotlin
//! wording.

use tg_core::ssh::SshError;

/// Why a statement failed, as the engine reported it: the message the user sees plus what a fix needs.
/// `position` is a 0-based UTF-16 offset into the statement, where the engine says (PostgreSQL does).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct QueryError {
    pub message: String,
    pub sql_state: Option<String>,
    pub vendor_code: Option<i32>,
    pub position: Option<usize>,
}

impl QueryError {
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            ..Self::default()
        }
    }
}

impl std::fmt::Display for QueryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DriverError {
    /// The engine refused or failed a statement.
    #[error("{0}")]
    Query(QueryError),
    /// Thrown before a statement reaches the server when the connection is read-only.
    #[error("This connection is read-only: {verb} is not allowed.")]
    ReadOnly { verb: String },
    /// A script statement failed. `completed` statements ran before it; `rolled_back` says whether they were undone.
    #[error("{message}")]
    Script {
        message: String,
        statement: String,
        completed: usize,
        rolled_back: bool,
    },
    /// An insert failed on `row` (as the caller described it); nothing was kept.
    #[error("{message}")]
    Insert { message: String, row: String },
    #[error("Query cancelled")]
    Cancelled,
    #[error("The query timed out")]
    Timeout,
    #[error("{0}")]
    Connect(String),
    #[error(transparent)]
    Tunnel(#[from] SshError),
    /// The engine can't do this, or this build has no driver for it.
    #[error("{0}")]
    Unsupported(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

impl DriverError {
    pub fn read_only(verb: &str) -> Self {
        Self::ReadOnly {
            verb: verb.to_owned(),
        }
    }

    /// The structured error for display: engine errors keep their SQLSTATE and position, others only a message.
    pub fn query_error(&self) -> QueryError {
        match self {
            Self::Query(e) => e.clone(),
            other => QueryError::new(other.to_string()),
        }
    }
}

impl From<QueryError> for DriverError {
    fn from(e: QueryError) -> Self {
        Self::Query(e)
    }
}

pub type Result<T, E = DriverError> = std::result::Result<T, E>;

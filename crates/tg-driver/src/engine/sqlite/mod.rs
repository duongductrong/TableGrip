//! SQLite through rusqlite (bundled). Each connection lives on a worker thread ([`worker`]); the pool holds four, as
//! Hikari did, and an in-memory database is a shared-cache URI kept alive by the pool, as `JdbcUrls` did it.

mod catalog;
mod conn;
mod structure;
pub mod values;
mod worker;

use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::SqliteConn;
use rusqlite::OpenFlags;
use std::path::Path;
use std::sync::{Arc, Mutex};
use tg_core::connection::ConnectionConfig;
use tg_sql::model::ConnectionKind;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
pub use worker::{Target, Worker};

const POOL_SIZE: usize = 4;

/// sqlite-jdbc applies connection properties named after pragmas; these are the ones worth honouring.
const PRAGMAS: &[&str] = &[
    "foreign_keys",
    "journal_mode",
    "synchronous",
    "busy_timeout",
    "cache_size",
    "case_sensitive_like",
    "recursive_triggers",
    "temp_store",
    "secure_delete",
    "locking_mode",
];

pub async fn connect(config: &ConnectionConfig) -> Result<Arc<dyn RelationalConnection>> {
    if config.db_type.connection == ConnectionKind::DataFile {
        return Err(DriverError::Unsupported(format!(
            "{} files open through their working copy, which this build doesn't have yet.",
            config.db_type.display_name
        )));
    }
    let source = SqliteSource::open(target(config)).await?;
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(source),
        None,
    )))
}

/// The file or shared in-memory database a profile names (`JdbcUrls.fileUrl`).
pub fn target(config: &ConnectionConfig) -> Target {
    let raw = config.database.trim();
    let path = match raw.strip_prefix("~/") {
        Some(rest) => tg_core::paths::user_home()
            .join(rest)
            .to_string_lossy()
            .into_owned(),
        None => raw.to_owned(),
    };
    let memory = path.is_empty() || path == ":memory:" || path.starts_with("mem:");
    let mut flags = OpenFlags::SQLITE_OPEN_URI | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    flags |= if config.read_only && !memory {
        OpenFlags::SQLITE_OPEN_READ_ONLY
    } else {
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE
    };
    let path = if memory {
        format!("file:tablegrip-{}?mode=memory&cache=shared", config.id)
    } else {
        path
    };
    let pragmas = config
        .properties
        .iter()
        .filter(|(k, v)| {
            PRAGMAS.contains(&k.as_str())
                && v.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        })
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Target {
        path,
        flags,
        pragmas,
    }
}

/// An engine error as the user sees it, with SQLite's extended result code as the vendor code.
pub(crate) fn error(e: rusqlite::Error) -> DriverError {
    match e {
        rusqlite::Error::SqliteFailure(code, message) => {
            if code.code == rusqlite::ErrorCode::OperationInterrupted {
                return DriverError::Cancelled;
            }
            let text = message.unwrap_or_else(|| code.to_string());
            // sqlite-jdbc's wording: "[SQLITE_ERROR] SQL error or missing database (no such table: x)".
            DriverError::Query(QueryError {
                message: text,
                sql_state: None,
                vendor_code: Some(code.extended_code),
                position: None,
            })
        }
        other => DriverError::Query(QueryError::new(other.to_string())),
    }
}

/// Idle pooled workers.
pub(crate) type Idle = Arc<Mutex<Vec<Worker>>>;

/// The pool: idle workers, a permit per checked-out one, and an anchor that keeps an in-memory database alive.
pub struct SqliteSource {
    target: Target,
    idle: Idle,
    permits: Arc<Semaphore>,
    _anchor: Worker,
}

impl SqliteSource {
    pub async fn open(target: Target) -> Result<Self> {
        // Opening one up front validates the path, like Hikari's initialization check.
        let anchor = Worker::open(&target).await?;
        Ok(Self {
            target,
            idle: Idle::default(),
            permits: Arc::new(Semaphore::new(POOL_SIZE)),
            _anchor: anchor,
        })
    }
}

#[async_trait]
impl ConnSource for SqliteSource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        let permit: OwnedSemaphorePermit = self
            .permits
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| DriverError::Cancelled)?;
        let worker = self.idle.lock().unwrap().pop();
        let worker = match worker {
            Some(w) => w,
            None => Worker::open(&self.target).await?,
        };
        Ok(Box::new(SqliteConn::pooled(
            worker,
            self.idle.clone(),
            permit,
        )))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(SqliteConn::dedicated(
            Worker::open(&self.target).await?,
        )))
    }

    async fn backup_into(&self, target: &Path) -> Result<()> {
        let path = std::path::absolute(target)
            .unwrap_or(target.to_path_buf())
            .to_string_lossy()
            .replace('\'', "''");
        let mut conn = self.pooled().await?;
        conn.execute(&format!("VACUUM INTO '{path}'"), &[])
            .await
            .map(|_| ())
    }
}

//! DuckDB, built in (the `duckdb` crate, bundled, at the engine version the Kotlin build's JDBC driver carried). One
//! database per profile; its connections are clones of a root one that keeps it open (an in-memory database lives
//! as long as the profile, as `JdbcUrls` made it). Each connection runs on a thread of its own, like SQLite's, and a
//! dropped call interrupts the statement.

mod catalog;
mod conn;
mod values;

use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::DuckConn;
use duckdb::{AccessMode, Config, Connection, InterruptHandle};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use tg_core::connection::ConnectionConfig;
use tokio::sync::{Semaphore, oneshot};

const POOL_SIZE: usize = 4;

pub(crate) fn error(e: duckdb::Error) -> DriverError {
    let message = e.to_string();
    if message.contains("INTERRUPT") || message.contains("Interrupted") {
        return DriverError::Cancelled;
    }
    DriverError::Query(QueryError::new(message))
}

type Job = Box<dyn FnOnce(&mut Connection) + Send>;

/// A DuckDB connection on a thread of its own. Calls run in order; dropping a call's future interrupts it.
pub struct Worker {
    jobs: mpsc::Sender<Job>,
    interrupt: Arc<InterruptHandle>,
}

impl Worker {
    fn start(conn: Connection) -> Result<Self> {
        let interrupt = conn.interrupt_handle();
        let (jobs, inbox) = mpsc::channel::<Job>();
        std::thread::Builder::new()
            .name("tg-duckdb".into())
            .spawn(move || {
                let mut conn = conn;
                while let Ok(job) = inbox.recv() {
                    job(&mut conn);
                }
            })
            .map_err(DriverError::Io)?;
        Ok(Self { jobs, interrupt })
    }

    /// Runs `f` on the connection's thread.
    pub async fn call<T, F>(&self, f: F) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(&mut Connection) -> Result<T> + Send + 'static,
    {
        let (tx, rx) = oneshot::channel();
        let cancelled = Arc::new(AtomicBool::new(false));
        let skip = cancelled.clone();
        self.jobs
            .send(Box::new(move |conn| {
                if !skip.load(Ordering::SeqCst) {
                    let _ = tx.send(f(conn));
                }
            }))
            .map_err(|_| DriverError::Connect("DuckDB connection closed".into()))?;
        let mut guard = InterruptOnDrop {
            handle: &self.interrupt,
            cancelled,
            armed: true,
        };
        let result = rx.await;
        guard.armed = false;
        result.map_err(|_| DriverError::Cancelled)?
    }

    /// Queues `f` without waiting for it.
    pub fn post(&self, f: impl FnOnce(&mut Connection) + Send + 'static) {
        let _ = self.jobs.send(Box::new(f));
    }
}

struct InterruptOnDrop<'a> {
    handle: &'a InterruptHandle,
    cancelled: Arc<AtomicBool>,
    armed: bool,
}

impl Drop for InterruptOnDrop<'_> {
    fn drop(&mut self) {
        if self.armed {
            self.cancelled.store(true, Ordering::SeqCst);
            self.handle.interrupt();
        }
    }
}

/// The file a profile names, or None for an in-memory database (`JdbcUrls.fileUrl`).
fn path(config: &ConnectionConfig) -> Option<String> {
    let raw = config.database.trim();
    let path = match raw.strip_prefix("~/") {
        Some(rest) => tg_core::paths::user_home()
            .join(rest)
            .to_string_lossy()
            .into_owned(),
        None => raw.to_owned(),
    };
    let memory = path.is_empty() || path == ":memory:" || path.starts_with("mem:");
    (!memory).then_some(path)
}

pub async fn connect(config: &ConnectionConfig) -> Result<Arc<dyn RelationalConnection>> {
    let file = path(config);
    let read_only = config.read_only;
    let root = tokio::task::spawn_blocking(move || {
        let mut options = Config::default();
        if read_only && file.is_some() {
            options = options.access_mode(AccessMode::ReadOnly).map_err(error)?;
        }
        match &file {
            Some(f) => Connection::open_with_flags(f, options),
            None => Connection::open_in_memory_with_flags(options),
        }
        .map_err(|e| DriverError::Connect(e.to_string()))
    })
    .await
    .map_err(|e| DriverError::Connect(e.to_string()))??;
    let source = DuckSource {
        root: Mutex::new(root),
        idle: Arc::new(Mutex::new(Vec::new())),
        permits: Arc::new(Semaphore::new(POOL_SIZE)),
    };
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(source),
        None,
    )))
}

pub(crate) type Idle = Arc<Mutex<Vec<Worker>>>;

struct DuckSource {
    /// Keeps the database open; connections are its clones.
    root: Mutex<Connection>,
    idle: Idle,
    permits: Arc<Semaphore>,
}

impl DuckSource {
    fn worker(&self) -> Result<Worker> {
        let conn = self
            .root
            .lock()
            .expect("duckdb root")
            .try_clone()
            .map_err(error)?;
        Worker::start(conn)
    }
}

#[async_trait]
impl ConnSource for DuckSource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        let permit = self
            .permits
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| DriverError::Connect("The connection is closed.".into()))?;
        let idle = self.idle.lock().expect("pool lock").pop();
        let worker = match idle {
            Some(w) => w,
            None => self.worker()?,
        };
        Ok(Box::new(DuckConn::pooled(
            worker,
            self.idle.clone(),
            permit,
        )))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(DuckConn::owned(self.worker()?)))
    }

    fn pool_size(&self) -> usize {
        POOL_SIZE
    }

    /// `EXPORT DATABASE` as Parquet files into a directory (`Database.backupInto`).
    async fn backup_into(&self, target: &Path) -> Result<()> {
        let path = std::path::absolute(target)
            .map_err(DriverError::Io)?
            .to_string_lossy()
            .replace('\'', "''");
        let worker = self.worker()?;
        worker
            .call(move |c| {
                c.execute_batch(&format!("EXPORT DATABASE '{path}' (FORMAT PARQUET)"))
                    .map_err(error)
            })
            .await
    }
}

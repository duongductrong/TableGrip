//! A rusqlite connection on a thread of its own. Calls run in order; dropping a call's future interrupts it
//! (`sqlite3_interrupt`), like JDBC's `Statement.cancel()`.

use crate::error::{DriverError, Result};
use rusqlite::{Connection, InterruptHandle, OpenFlags};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use tokio::sync::oneshot;

type Job = Box<dyn FnOnce(&mut Connection) + Send>;

pub struct Worker {
    jobs: mpsc::Sender<Job>,
    interrupt: Arc<InterruptHandle>,
}

/// What to open: a path or `file:` URI, and how.
#[derive(Clone, Debug)]
pub struct Target {
    pub path: String,
    pub flags: OpenFlags,
    /// `PRAGMA name = value` to run on every new connection.
    pub pragmas: Vec<(String, String)>,
}

impl Worker {
    pub async fn open(target: &Target) -> Result<Self> {
        let (jobs, inbox) = mpsc::channel::<Job>();
        let (ready, opened) = oneshot::channel();
        let target = target.clone();
        std::thread::Builder::new()
            .name("tg-sqlite".into())
            .spawn(move || {
                let mut conn = match open(&target) {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = ready.send(Err(e));
                        return;
                    }
                };
                let _ = ready.send(Ok(Arc::new(conn.get_interrupt_handle())));
                while let Ok(job) = inbox.recv() {
                    job(&mut conn);
                }
            })
            .map_err(DriverError::Io)?;
        let interrupt = opened
            .await
            .map_err(|_| DriverError::Connect("SQLite worker stopped".into()))??;
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
        let job: Job = Box::new(move |conn| {
            if !skip.load(Ordering::SeqCst) {
                let _ = tx.send(f(conn));
            }
        });
        self.jobs
            .send(job)
            .map_err(|_| DriverError::Connect("SQLite connection closed".into()))?;
        let mut guard = InterruptOnDrop {
            handle: &self.interrupt,
            cancelled,
            armed: true,
        };
        let result = rx.await;
        guard.armed = false;
        result.map_err(|_| DriverError::Cancelled)?
    }

    /// Queues `f` without waiting for it, e.g. cleaning up before the connection is reused.
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

fn open(target: &Target) -> Result<Connection> {
    let conn = Connection::open_with_flags(&target.path, target.flags).map_err(super::error)?;
    // sqlite-jdbc's default: wait for a lock rather than fail at once.
    conn.busy_timeout(std::time::Duration::from_millis(3_000))
        .map_err(super::error)?;
    for (name, value) in &target.pragmas {
        conn.execute_batch(&format!("PRAGMA {name} = {value}"))
            .map_err(super::error)?;
    }
    Ok(conn)
}

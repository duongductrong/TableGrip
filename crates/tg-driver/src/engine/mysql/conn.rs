//! One MySQL connection as an [`SqlConn`]. Each statement runs in a task that owns the connection and hands it back
//! when done. Dropping the caller's future sends `KILL QUERY` from a side connection; the task then sees the
//! statement end and the connection is reused, with its session (transaction, variables) intact, as after JDBC's
//! `Statement.cancel()`.

use super::types::{self, BinaryAs, Reading};
use super::{Pool, error, open};
use crate::engine::inline::{self, Literals};
use crate::error::{DriverError, Result};
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, RowCollector, SqlConn};
use async_trait::async_trait;
use mysql_async::prelude::Queryable;
use mysql_async::{Conn, Opts};
use std::future::Future;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tg_sql::SqlSyntax;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tokio::sync::{OwnedSemaphorePermit, mpsc, oneshot};
use tokio::task::JoinHandle;

/// A server connection with what the side channel and literals need to know about it.
pub struct Physical {
    conn: Conn,
    /// `CONNECTION_ID()`, for `KILL QUERY` (64-bit on TiDB).
    id: u64,
    /// `NO_BACKSLASH_ESCAPES` is in the session's sql_mode, so `\` is an ordinary character in literals.
    no_backslash_escapes: bool,
}

impl Physical {
    /// Reads the connection's id and sql_mode. A read-only profile's session is made read-only too, where the server
    /// supports it (Hikari's `isReadOnly`; TiDB doesn't); consoles check statements before sending them anyway.
    pub async fn new(mut conn: Conn, read_only: bool) -> Result<Self> {
        if read_only {
            let _ = conn.query_drop("SET SESSION TRANSACTION READ ONLY").await;
        }
        let row: Option<(u64, String)> = conn
            .query_first("SELECT CONNECTION_ID(), @@SESSION.sql_mode")
            .await
            .map_err(error)?;
        let (id, mode) = row.unwrap_or_default();
        Ok(Self {
            conn,
            id,
            no_backslash_escapes: mode.to_ascii_uppercase().contains("NO_BACKSLASH_ESCAPES"),
        })
    }

    /// Whether an idle connection still answers (the server's wait_timeout or a firewall may have closed it).
    pub async fn ping(&mut self) -> bool {
        tokio::time::timeout(Duration::from_secs(5), self.conn.ping())
            .await
            .is_ok_and(|r| r.is_ok())
    }
}

/// MySQL string literals: `'` doubled, and `\` doubled unless the session turned backslash escapes off.
struct MyLiterals {
    no_backslash_escapes: bool,
}

impl Literals for MyLiterals {
    fn text(&self, value: &str) -> Result<String> {
        let doubled = value.replace('\'', "''");
        Ok(if self.no_backslash_escapes {
            format!("'{doubled}'")
        } else {
            format!("'{}'", doubled.replace('\\', "\\\\"))
        })
    }
}

/// Sends `KILL QUERY` for the connection if dropped while armed, unless the statement has ended by then. The kill's
/// task is left in `slot`, and the connection's next statement waits for it, so a late kill never hits that one.
struct KillOnDrop {
    opts: Opts,
    id: u64,
    running: Arc<AtomicBool>,
    slot: Arc<Mutex<Option<JoinHandle<()>>>>,
    armed: bool,
}

impl Drop for KillOnDrop {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let (opts, id, running) = (self.opts.clone(), self.id, self.running.clone());
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            let handle = runtime.spawn(async move {
                if let Ok(mut side) = open(&opts).await {
                    if running.load(Ordering::SeqCst) {
                        let _ = side.query_drop(format!("KILL QUERY {id}")).await;
                    }
                    let _ = side.disconnect().await;
                }
            });
            *self.slot.lock().expect("kill slot") = Some(handle);
        }
    }
}

pub struct MyConn {
    idle: Option<Physical>,
    /// The task running the last statement, which gives the connection back (None if it broke).
    busy: Option<JoinHandle<Option<Physical>>>,
    opts: Opts,
    /// Where a pooled connection goes back to, and the permit it holds.
    pool: Option<(Arc<Pool>, OwnedSemaphorePermit)>,
    discard: bool,
    read_only: bool,
    /// A `KILL QUERY` on its way for a statement whose future was dropped.
    kill: Arc<Mutex<Option<JoinHandle<()>>>>,
}

/// What reading one statement found.
enum Outcome {
    Rows(Rows),
    Update(u64),
}

impl MyConn {
    pub fn owned(conn: Physical, opts: Opts, read_only: bool) -> Self {
        Self {
            read_only,
            idle: Some(conn),
            busy: None,
            opts,
            pool: None,
            discard: false,
            kill: Arc::default(),
        }
    }

    pub fn pooled(conn: Physical, pool: Arc<Pool>, permit: OwnedSemaphorePermit) -> Self {
        Self {
            read_only: pool.read_only,
            idle: Some(conn),
            busy: None,
            opts: pool.opts.clone(),
            pool: Some((pool, permit)),
            discard: false,
            kill: Arc::default(),
        }
    }

    /// The connection, once the previous statement's task has given it back; a new one if that broke it.
    async fn take(&mut self) -> Result<Physical> {
        if let Some(handle) = self.busy.take()
            && let Ok(Ok(Some(conn))) = tokio::time::timeout(Duration::from_secs(30), handle).await
        {
            self.idle = Some(conn);
        }
        let kill = self.kill.lock().expect("kill slot").take();
        if let Some(kill) = kill {
            let _ = tokio::time::timeout(Duration::from_secs(10), kill).await;
        }
        match self.idle.take() {
            Some(c) => Ok(c),
            None => Physical::new(open(&self.opts).await?, self.read_only).await,
        }
    }

    /// Runs `job` on the connection in a task of its own; dropping this future kills the statement.
    async fn with<T, F, Fut>(&mut self, job: F) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(Physical) -> Fut + Send + 'static,
        Fut: Future<Output = (Physical, Result<T>)> + Send + 'static,
    {
        let conn = self.take().await?;
        let running = Arc::new(AtomicBool::new(true));
        let mut guard = KillOnDrop {
            opts: self.opts.clone(),
            id: conn.id,
            running: running.clone(),
            slot: self.kill.clone(),
            armed: true,
        };
        let (tx, rx) = oneshot::channel();
        self.busy = Some(tokio::spawn(async move {
            let (conn, result) = job(conn).await;
            running.store(false, Ordering::SeqCst);
            let _ = tx.send(result);
            (!conn.conn.is_disconnected()).then_some(conn)
        }));
        let result = rx.await;
        guard.armed = false;
        if let Some(handle) = self.busy.take()
            && let Ok(Some(conn)) = handle.await
        {
            self.idle = Some(conn);
        }
        result.unwrap_or_else(|_| Err(DriverError::Connect("The connection was lost.".into())))
    }

    fn literals(&self) -> MyLiterals {
        MyLiterals {
            no_backslash_escapes: self.idle.as_ref().is_some_and(|c| c.no_backslash_escapes),
        }
    }

    fn inline(&mut self, sql: &str, params: &[Param<'_>]) -> Result<String> {
        inline::inline(sql, SqlSyntax::MYSQL, params, &self.literals())
    }

    /// Reads one statement: up to `max_rows` rows (+1 for `truncated`) of its first result set, or what it changed.
    async fn read(
        &mut self,
        sql: String,
        max_rows: Option<usize>,
        binary: BinaryAs,
    ) -> Result<Outcome> {
        self.with(move |mut c| async move {
            let result = read(&mut c, &sql, max_rows, binary).await;
            (c, result)
        })
        .await
    }

    /// Text rows of a catalog query.
    pub(super) async fn text_rows(&mut self, sql: &str) -> Result<Vec<Vec<Cell>>> {
        Ok(
            match self.read(sql.to_owned(), None, BinaryAs::Preview).await? {
                Outcome::Rows(r) => r.rows,
                Outcome::Update(_) => Vec::new(),
            },
        )
    }
}

async fn read(
    c: &mut Physical,
    sql: &str,
    max_rows: Option<usize>,
    binary: BinaryAs,
) -> Result<Outcome> {
    let start = Instant::now();
    let mut result = c.conn.query_iter(sql).await.map_err(error)?;
    let Some(columns) = result.columns().filter(|c| !c.is_empty()) else {
        let affected = result.affected_rows();
        result.drop_result().await.map_err(error)?;
        return Ok(Outcome::Update(affected));
    };
    let (columns, readings) = types::result_columns(&columns);
    let mut collector = RowCollector::new(max_rows);
    while let Some(row) = result.next().await.map_err(error)? {
        if !collector.push(row_cells(row, &readings, binary)) {
            break;
        }
    }
    // Reads past what's wanted, as JDBC's maxRows did; killing instead could hit the connection's next statement.
    result.drop_result().await.map_err(error)?;
    Ok(Outcome::Rows(
        collector.finish(columns, start.elapsed().as_millis() as u64),
    ))
}

fn row_cells(row: mysql_async::Row, readings: &[Reading], binary: BinaryAs) -> Vec<Cell> {
    row.unwrap_raw()
        .into_iter()
        .zip(readings)
        .map(|(v, r)| types::cell(v, *r, binary))
        .collect()
}

impl Drop for MyConn {
    fn drop(&mut self) {
        let Some((pool, permit)) = self.pool.take() else {
            return;
        };
        if self.discard {
            return;
        }
        if let Some(conn) = self.idle.take() {
            pool.give_back(conn);
            drop(permit);
        } else if let Some(handle) = self.busy.take() {
            // Dropped mid-statement: the kill is on its way; the connection goes back once both are done.
            let kill = self.kill.lock().expect("kill slot").take();
            if let Ok(runtime) = tokio::runtime::Handle::try_current() {
                runtime.spawn(async move {
                    let back = tokio::time::timeout(Duration::from_secs(30), handle).await;
                    if let Some(kill) = kill {
                        let _ = tokio::time::timeout(Duration::from_secs(10), kill).await;
                    }
                    if let Ok(Ok(Some(conn))) = back {
                        pool.give_back(conn);
                    }
                    drop(permit);
                });
            }
        }
    }
}

#[async_trait]
impl SqlConn for MyConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let start = Instant::now();
        Ok(
            match self
                .read(sql.to_owned(), Some(options.max_rows), BinaryAs::Preview)
                .await?
            {
                Outcome::Rows(r) => QueryResult::Rows(r),
                Outcome::Update(n) => QueryResult::Update {
                    affected_rows: n,
                    elapsed_ms: start.elapsed().as_millis() as u64,
                },
            },
        )
    }

    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows> {
        let sql = self.inline(sql, params)?;
        match self.read(sql, max_rows, BinaryAs::Preview).await? {
            Outcome::Rows(r) => Ok(r),
            Outcome::Update(_) => Ok(Rows {
                columns: Vec::new(),
                rows: Vec::new(),
                truncated: false,
                elapsed_ms: 0,
            }),
        }
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        let sql = self.inline(sql, params)?;
        match self.read(sql, Some(0), BinaryAs::Preview).await? {
            Outcome::Update(n) => Ok(n),
            Outcome::Rows(_) => Ok(0),
        }
    }

    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        let sql = self.inline(sql, params)?;
        let (columns_tx, columns_rx) = oneshot::channel::<Vec<ResultColumn>>();
        let (rows_tx, mut rows_rx) = mpsc::channel::<Vec<Vec<Cell>>>(4);
        let reading = self.with(move |mut c| async move {
            let result = async {
                let mut result = c.conn.query_iter(sql).await.map_err(error)?;
                let columns = result.columns().unwrap_or_else(|| Arc::from(Vec::new()));
                let (columns, readings) = types::result_columns(&columns);
                let _ = columns_tx.send(columns);
                let mut batch = Vec::with_capacity(1000);
                while let Some(row) = result.next().await.map_err(error)? {
                    batch.push(row_cells(row, &readings, BinaryAs::Placeholder));
                    if batch.len() == 1000
                        && rows_tx.send(std::mem::take(&mut batch)).await.is_err()
                    {
                        break;
                    }
                }
                if !batch.is_empty() {
                    let _ = rows_tx.send(batch).await;
                }
                drop(rows_tx);
                result.drop_result().await.map_err(error)
            }
            .await;
            (c, result)
        });
        let writing = async {
            let Ok(columns) = columns_rx.await else {
                return Ok(0);
            };
            sink.begin(&columns)?;
            let mut count: u64 = 0;
            while let Some(batch) = rows_rx.recv().await {
                for row in batch {
                    sink.write(&row)?;
                    count += 1;
                    if count.is_multiple_of(PROGRESS_EVERY) {
                        progress(count);
                    }
                }
            }
            sink.finish()?;
            progress(count);
            Ok::<u64, DriverError>(count)
        };
        let (read, written) = tokio::join!(reading, writing);
        read?;
        written
    }

    async fn is_valid(&mut self) -> bool {
        tokio::time::timeout(
            Duration::from_secs(2),
            self.with(|mut c| async move {
                let r = c.conn.ping().await.map_err(error);
                (c, r)
            }),
        )
        .await
        .is_ok_and(|r| r.is_ok())
    }

    fn discard(&mut self) {
        self.discard = true;
    }

    async fn namespaces(&mut self) -> Result<Vec<Namespace>> {
        super::catalog::namespaces(self).await
    }

    async fn tables(&mut self, namespace: &Namespace) -> Result<Vec<TableInfo>> {
        super::catalog::tables(self, namespace).await
    }

    async fn details(&mut self, table: &TableRef, keys_and_indexes: bool) -> Result<TableDetails> {
        super::catalog::details(self, table, keys_and_indexes).await
    }

    async fn columns_of(
        &mut self,
        namespace: &Namespace,
    ) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
        super::catalog::columns_of(self, namespace).await
    }

    async fn relations(&mut self, table: &TableRef) -> Result<Vec<TableRelation>> {
        super::catalog::relations(self, table).await
    }

    async fn structure(&mut self, details: TableDetails) -> Result<TableStructure> {
        super::catalog::structure(self, details).await
    }

    async fn server_major_version(&mut self) -> Result<Option<i32>> {
        let rows = self.text_rows("SELECT version()").await?;
        Ok(rows
            .first()
            .and_then(|r| r[0].as_deref())
            .and_then(|v| v.split('.').next())
            .and_then(|v| v.parse().ok()))
    }
}

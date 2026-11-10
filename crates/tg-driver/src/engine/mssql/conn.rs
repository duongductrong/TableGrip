//! One SQL Server connection as an [`SqlConn`]. Each statement runs in a task that owns the client and hands it
//! back when done; dropping the caller's future makes the task stop reading and send a TDS attention, which cancels
//! the request on the server and leaves the session (its transaction, temp tables, settings) as it was.

use super::types::{self, BinaryAs};
use super::{Pool, error, open};
use crate::engine::inline::{self, Literals};
use crate::error::{DriverError, Result};
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, SqlConn};
use async_trait::async_trait;
use futures_util::TryStreamExt;
use futures_util::future::BoxFuture;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tg_sql::{SqlClassifier, SqlSyntax, StatementKind};
use tiberius::{Config, QueryItem};
use tokio::net::TcpStream;
use tokio::sync::{OwnedSemaphorePermit, mpsc, oneshot};
use tokio::task::JoinHandle;
use tokio_util::compat::Compat;
use tokio_util::sync::CancellationToken;

pub type Client = tiberius::Client<Compat<TcpStream>>;

/// T-SQL literals: Unicode strings (`N'…'`, as mssql-jdbc sends parameters by default), booleans as bits.
struct TsqlLiterals;

impl Literals for TsqlLiterals {
    fn text(&self, value: &str) -> Result<String> {
        Ok(format!("N'{}'", value.replace('\'', "''")))
    }

    fn boolean(&self, value: bool) -> String {
        if value { "1" } else { "0" }.to_owned()
    }
}

/// The `SAVEPOINT` statements the shared code uses, in T-SQL.
fn translate(sql: &str) -> Option<String> {
    let words: Vec<&str> = sql.split_whitespace().collect();
    match words.as_slice() {
        ["SAVEPOINT", name] => Some(format!("SAVE TRANSACTION {name}")),
        ["ROLLBACK", "TO", "SAVEPOINT", name] => Some(format!("ROLLBACK TRANSACTION {name}")),
        // T-SQL savepoints end with the transaction.
        ["RELEASE", "SAVEPOINT", _] => Some(String::new()),
        _ => None,
    }
}

pub struct MsConn {
    idle: Option<Client>,
    /// The task running the last statement, which gives the client back (None if it broke).
    busy: Option<JoinHandle<Option<Client>>>,
    tds: Config,
    pool: Option<(Arc<Pool>, OwnedSemaphorePermit)>,
    discard: bool,
}

type Job<T> = Box<dyn for<'c> FnOnce(&'c mut Client) -> BoxFuture<'c, Result<T>> + Send>;

impl MsConn {
    pub fn owned(client: Client, tds: Config) -> Self {
        Self {
            idle: Some(client),
            busy: None,
            tds,
            pool: None,
            discard: false,
        }
    }

    pub fn pooled(client: Client, pool: Arc<Pool>, permit: OwnedSemaphorePermit) -> Self {
        Self {
            idle: Some(client),
            busy: None,
            tds: pool.tds.clone(),
            pool: Some((pool, permit)),
            discard: false,
        }
    }

    async fn take(&mut self) -> Result<Client> {
        if let Some(handle) = self.busy.take()
            && let Ok(Ok(Some(client))) =
                tokio::time::timeout(Duration::from_secs(30), handle).await
        {
            self.idle = Some(client);
        }
        match self.idle.take() {
            Some(c) => Ok(c),
            None => open(&self.tds).await,
        }
    }

    /// Runs `job` on the client in a task of its own; dropping this future cancels the request.
    async fn with<T: Send + 'static>(&mut self, job: Job<T>) -> Result<T> {
        let mut client = self.take().await?;
        let token = CancellationToken::new();
        let guard = token.clone().drop_guard();
        let (tx, rx) = oneshot::channel();
        self.busy = Some(tokio::spawn(async move {
            let outcome = {
                let work = job(&mut client);
                tokio::select! {
                    result = work => Some(result),
                    () = token.cancelled() => None,
                }
            };
            match outcome {
                Some(result) => {
                    let _ = tx.send(result);
                    Some(client)
                }
                None => tokio::time::timeout(Duration::from_secs(10), client.cancel_query())
                    .await
                    .is_ok_and(|r| r.is_ok())
                    .then_some(client),
            }
        }));
        let result = rx.await;
        guard.disarm();
        if let Some(handle) = self.busy.take()
            && let Ok(Some(client)) = handle.await
        {
            self.idle = Some(client);
        }
        result.unwrap_or_else(|_| Err(DriverError::Connect("The connection was lost.".into())))
    }

    fn inline(sql: &str, params: &[Param<'_>]) -> Result<String> {
        inline::inline(sql, SqlSyntax::TSQL, params, &TsqlLiterals)
    }

    /// Rows of a query: up to `max_rows` (+1 for `truncated`) of its first result set.
    async fn read(
        &mut self,
        sql: String,
        max_rows: Option<usize>,
        binary: BinaryAs,
    ) -> Result<Rows> {
        self.with(Box::new(move |client| {
            Box::pin(read(client, sql, max_rows, binary))
        }))
        .await
    }

    /// Rows changed by a statement that returns none.
    async fn update(&mut self, sql: String) -> Result<u64> {
        if sql.is_empty() {
            return Ok(0);
        }
        self.with(Box::new(move |client| {
            Box::pin(async move {
                if !counts_rows(&sql) {
                    // Through sp_executesql (`execute`), a transaction begun or ended inside the call is error 266,
                    // and CREATE VIEW/PROCEDURE/TRIGGER must start a batch: anything but DML runs as a plain batch.
                    let stream = client.simple_query(sql).await.map_err(super::error)?;
                    stream.into_results().await.map_err(super::error)?;
                    return Ok(0);
                }
                let result = client.execute(sql, &[]).await.map_err(super::error)?;
                Ok(result.rows_affected().iter().sum())
            })
        }))
        .await
    }

    /// Text rows of a catalog query.
    pub(super) async fn text_rows(&mut self, sql: &str) -> Result<Vec<Vec<Cell>>> {
        Ok(self
            .read(sql.to_owned(), None, BinaryAs::Preview)
            .await?
            .rows)
    }
}

async fn read(
    client: &mut Client,
    sql: String,
    max_rows: Option<usize>,
    binary: BinaryAs,
) -> Result<Rows> {
    let start = Instant::now();
    let mut stream = client.simple_query(sql).await.map_err(error)?;
    let mut meta: Vec<tiberius::Column> = Vec::new();
    let mut raw: Vec<tiberius::Row> = Vec::new();
    let mut more = false;
    while let Some(item) = stream.try_next().await.map_err(error)? {
        match item {
            QueryItem::Metadata(m) if m.result_index() == 0 => meta = m.columns().to_vec(),
            QueryItem::Metadata(_) => break,
            QueryItem::Row(row) if row.result_index() == 0 => {
                if max_rows.is_some_and(|m| raw.len() > m) {
                    more = true;
                    break;
                }
                raw.push(row);
            }
            QueryItem::Row(_) => break,
        }
    }
    drop(stream);
    if more {
        // Stop the server sending rows nobody reads.
        let _ = client.cancel_query().await;
    }
    let columns = types::result_columns(&meta, raw.first());
    let truncated = max_rows.is_some_and(|m| raw.len() > m);
    if let Some(m) = max_rows {
        raw.truncate(m);
    }
    let rows = raw
        .iter()
        .map(|r| {
            r.cells()
                .zip(&columns)
                .map(|((_, v), c)| types::cell(v, c, binary))
                .collect()
        })
        .collect();
    Ok(Rows {
        columns,
        rows,
        truncated: truncated || more,
        elapsed_ms: start.elapsed().as_millis() as u64,
    })
}

/// DML, whose row counts `execute` reports.
fn counts_rows(sql: &str) -> bool {
    let info = SqlClassifier::new(SqlSyntax::TSQL).classify(sql);
    matches!(info.verb.as_str(), "INSERT" | "UPDATE" | "DELETE" | "MERGE")
}

/// Whether a console statement returns rows (so it's read) rather than a count (so it's executed).
fn returns_rows(sql: &str) -> bool {
    let info = SqlClassifier::new(SqlSyntax::TSQL).classify(sql);
    info.kind == StatementKind::Read || matches!(info.verb.as_str(), "EXEC" | "EXECUTE")
}

impl Drop for MsConn {
    fn drop(&mut self) {
        let Some((pool, permit)) = self.pool.take() else {
            return;
        };
        if self.discard {
            return;
        }
        if let Some(client) = self.idle.take() {
            pool.give_back(client);
            drop(permit);
        } else if let Some(handle) = self.busy.take()
            && let Ok(runtime) = tokio::runtime::Handle::try_current()
        {
            // Dropped mid-statement: the attention is on its way; the client goes back once it's acknowledged.
            runtime.spawn(async move {
                if let Ok(Ok(Some(client))) =
                    tokio::time::timeout(Duration::from_secs(30), handle).await
                {
                    pool.give_back(client);
                }
                drop(permit);
            });
        }
    }
}

#[async_trait]
impl SqlConn for MsConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let start = Instant::now();
        if returns_rows(sql) {
            let rows = self
                .read(sql.to_owned(), Some(options.max_rows), BinaryAs::Preview)
                .await?;
            // A procedure that only changes data returns no result set.
            if !rows.columns.is_empty() {
                return Ok(QueryResult::Rows(rows));
            }
            return Ok(QueryResult::Update {
                affected_rows: 0,
                elapsed_ms: rows.elapsed_ms,
            });
        }
        let affected_rows = self
            .update(translate(sql).unwrap_or_else(|| sql.to_owned()))
            .await?;
        Ok(QueryResult::Update {
            affected_rows,
            elapsed_ms: start.elapsed().as_millis() as u64,
        })
    }

    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows> {
        let sql = Self::inline(sql, params)?;
        self.read(sql, max_rows, BinaryAs::Preview).await
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        let sql = translate(sql).unwrap_or(Self::inline(sql, params)?);
        self.update(sql).await
    }

    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        let sql = Self::inline(sql, params)?;
        let (columns_tx, columns_rx) = oneshot::channel::<Vec<ResultColumn>>();
        let (rows_tx, mut rows_rx) = mpsc::channel::<Vec<Vec<Cell>>>(4);
        let reading = self.with(Box::new(move |client| {
            Box::pin(async move {
                let mut stream = client.simple_query(sql).await.map_err(error)?;
                let mut meta: Vec<tiberius::Column> = Vec::new();
                let mut columns: Option<Vec<ResultColumn>> = None;
                let mut columns_tx = Some(columns_tx);
                let mut batch = Vec::with_capacity(1000);
                while let Some(item) = stream.try_next().await.map_err(error)? {
                    match item {
                        QueryItem::Metadata(m) if m.result_index() == 0 => {
                            meta = m.columns().to_vec()
                        }
                        QueryItem::Row(row) if row.result_index() == 0 => {
                            let cols = columns.get_or_insert_with(|| {
                                let c = types::result_columns(&meta, Some(&row));
                                if let Some(tx) = columns_tx.take() {
                                    let _ = tx.send(c.clone());
                                }
                                c
                            });
                            batch.push(
                                row.cells()
                                    .zip(cols.iter())
                                    .map(|((_, v), c)| types::cell(v, c, BinaryAs::Placeholder))
                                    .collect(),
                            );
                            if batch.len() == 1000
                                && rows_tx.send(std::mem::take(&mut batch)).await.is_err()
                            {
                                break;
                            }
                        }
                        _ => break,
                    }
                }
                if let Some(tx) = columns_tx.take() {
                    let _ = tx.send(types::result_columns(&meta, None));
                }
                if !batch.is_empty() {
                    let _ = rows_tx.send(batch).await;
                }
                Ok(())
            })
        }));
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

    async fn begin(&mut self) -> Result<()> {
        self.update("BEGIN TRANSACTION".into()).await.map(|_| ())
    }

    async fn commit(&mut self) -> Result<()> {
        self.update("IF @@TRANCOUNT > 0 COMMIT TRANSACTION".into())
            .await
            .map(|_| ())
    }

    async fn rollback(&mut self) -> Result<()> {
        self.update("IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION".into())
            .await
            .map(|_| ())
    }

    async fn is_valid(&mut self) -> bool {
        tokio::time::timeout(
            Duration::from_secs(2),
            self.read("SELECT 1".into(), Some(1), BinaryAs::Preview),
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
        let rows = self
            .text_rows("SELECT CAST(SERVERPROPERTY('ProductMajorVersion') AS int)")
            .await?;
        Ok(rows
            .first()
            .and_then(|r| r[0].as_deref())
            .and_then(|v| v.parse().ok()))
    }
}

//! One SQLite connection as an [`SqlConn`].

use super::values::{self, BinaryAs, Storage};
use super::{Idle, Worker, error};
use crate::error::{DriverError, Result};
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, RowCollector, SqlConn};
use async_trait::async_trait;
use rusqlite::types::{Value, ValueRef};
use rusqlite::{Connection, Row, params_from_iter};
use std::time::Instant;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tokio::sync::{OwnedSemaphorePermit, mpsc};

pub struct SqliteConn {
    worker: Option<Worker>,
    pool: Option<(Idle, OwnedSemaphorePermit)>,
    discard: bool,
}

impl SqliteConn {
    pub fn pooled(worker: Worker, idle: Idle, permit: OwnedSemaphorePermit) -> Self {
        Self {
            worker: Some(worker),
            pool: Some((idle, permit)),
            discard: false,
        }
    }

    pub fn dedicated(worker: Worker) -> Self {
        Self {
            worker: Some(worker),
            pool: None,
            discard: false,
        }
    }

    fn worker(&self) -> &Worker {
        self.worker.as_ref().expect("worker present until drop")
    }

    /// Runs `f` on this connection's thread.
    pub(super) async fn call<T, F>(&self, f: F) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(&mut Connection) -> Result<T> + Send + 'static,
    {
        self.worker().call(f).await
    }
}

impl Drop for SqliteConn {
    fn drop(&mut self) {
        let (Some(worker), Some((idle, _permit))) = (self.worker.take(), self.pool.take()) else {
            return;
        };
        if self.discard {
            return;
        }
        // Whatever the borrower left open is rolled back before anyone else gets the connection.
        worker.post(|conn| {
            if !conn.is_autocommit() {
                let _ = conn.execute_batch("ROLLBACK");
            }
        });
        idle.lock().unwrap().push(worker);
    }
}

fn owned(params: &[Param<'_>]) -> Vec<Value> {
    params.iter().map(values::bind).collect()
}

/// Column labels and declared types, known before any row is read.
struct Shape {
    names: Vec<String>,
    decls: Vec<Option<String>>,
}

impl Shape {
    fn of(stmt: &rusqlite::Statement<'_>) -> Self {
        Self {
            names: stmt.column_names().map(str::to_owned).collect(),
            decls: stmt
                .columns()
                .map(|c| c.decl_type().map(str::to_owned))
                .collect(),
        }
    }

    /// The result columns as sqlite-jdbc typed them, from the first row (or none).
    fn columns(&self, first: Option<&Row<'_>>) -> Vec<ResultColumn> {
        (0..self.names.len())
            .map(|i| {
                let value = first.and_then(|r| r.get_ref(i).ok());
                let storage = value.as_ref().map_or(Storage::Null, Storage::of);
                let integer = match value {
                    Some(ValueRef::Integer(v)) => Some(v),
                    _ => None,
                };
                let type_name =
                    values::column_type_name(self.decls[i].as_deref(), &self.names[i], storage);
                let jdbc = values::column_type(&type_name, storage, integer);
                ResultColumn::new(&self.names[i], &type_name, jdbc)
            })
            .collect()
    }
}

fn row_cells(row: &Row<'_>, columns: &[ResultColumn], binary: BinaryAs) -> Vec<Cell> {
    columns
        .iter()
        .enumerate()
        .map(|(i, c)| row.get_ref(i).ok().and_then(|v| values::cell(v, c, binary)))
        .collect()
}

/// Reads up to `max_rows` (+1) rows of a prepared query.
fn read(conn: &Connection, sql: &str, params: Vec<Value>, max_rows: Option<usize>) -> Result<Rows> {
    let start = Instant::now();
    let mut stmt = conn.prepare(sql).map_err(error)?;
    let shape = Shape::of(&stmt);
    let mut rows = stmt.query(params_from_iter(params)).map_err(error)?;
    let mut columns: Option<Vec<ResultColumn>> = None;
    let mut collector = RowCollector::new(max_rows);
    while let Some(row) = rows.next().map_err(error)? {
        let cols = columns.get_or_insert_with(|| shape.columns(Some(row)));
        if !collector.push(row_cells(row, cols, BinaryAs::Preview)) {
            break;
        }
    }
    let columns = columns.unwrap_or_else(|| shape.columns(None));
    Ok(collector.finish(columns, start.elapsed().as_millis() as u64))
}

/// Runs a statement that returns no rows; the rows it changed (0 for DDL, where `changes()` would be stale).
fn exec(conn: &Connection, sql: &str, params: Vec<Value>) -> Result<u64> {
    let before = conn.total_changes();
    let mut stmt = conn.prepare(sql).map_err(error)?;
    if stmt.column_count() > 0 {
        // A query run for its effect (e.g. `PRAGMA x = y` echoing a row): step through it.
        let mut rows = stmt.query(params_from_iter(params)).map_err(error)?;
        while rows.next().map_err(error)?.is_some() {}
        return Ok(0);
    }
    stmt.raw_bind_parameters_from(params)?;
    stmt.raw_execute().map_err(error)?;
    Ok(if conn.total_changes() == before {
        0
    } else {
        conn.changes()
    })
}

trait BindAll {
    fn raw_bind_parameters_from(&mut self, params: Vec<Value>) -> Result<()>;
}

impl BindAll for rusqlite::Statement<'_> {
    fn raw_bind_parameters_from(&mut self, params: Vec<Value>) -> Result<()> {
        if params.len() != self.parameter_count() {
            return Err(DriverError::Query(crate::QueryError::new(format!(
                "Expected {} parameter(s) but {} were given",
                self.parameter_count(),
                params.len()
            ))));
        }
        for (i, p) in params.into_iter().enumerate() {
            self.raw_bind_parameter(i + 1, p).map_err(error)?;
        }
        Ok(())
    }
}

enum Streamed {
    Columns(Vec<ResultColumn>),
    Rows(Vec<Vec<Cell>>),
}

#[async_trait]
impl SqlConn for SqliteConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let sql = sql.to_owned();
        self.call(move |conn| {
            let start = Instant::now();
            let returns_rows = conn.prepare(&sql).map_err(error)?.column_count() > 0;
            if returns_rows {
                read(conn, &sql, Vec::new(), Some(options.max_rows)).map(QueryResult::Rows)
            } else {
                let affected_rows = exec(conn, &sql, Vec::new())?;
                Ok(QueryResult::Update {
                    affected_rows,
                    elapsed_ms: start.elapsed().as_millis() as u64,
                })
            }
        })
        .await
    }

    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows> {
        let (sql, params) = (sql.to_owned(), owned(params));
        self.call(move |conn| read(conn, &sql, params, max_rows))
            .await
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        let (sql, params) = (sql.to_owned(), owned(params));
        self.call(move |conn| exec(conn, &sql, params)).await
    }

    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        const BATCH: usize = 256;
        let (sql, params) = (sql.to_owned(), owned(params));
        let (tx, mut rx) = mpsc::channel::<Streamed>(8);
        let reading = self.call(move |conn| {
            let mut stmt = conn.prepare(&sql).map_err(error)?;
            let shape = Shape::of(&stmt);
            let mut rows = stmt.query(params_from_iter(params)).map_err(error)?;
            let mut columns: Option<Vec<ResultColumn>> = None;
            let mut batch = Vec::with_capacity(BATCH);
            while let Some(row) = rows.next().map_err(error)? {
                if columns.is_none() {
                    let c = shape.columns(Some(row));
                    if tx.blocking_send(Streamed::Columns(c.clone())).is_err() {
                        return Err(DriverError::Cancelled);
                    }
                    columns = Some(c);
                }
                batch.push(row_cells(
                    row,
                    columns.as_ref().unwrap(),
                    BinaryAs::Placeholder,
                ));
                if batch.len() == BATCH
                    && tx
                        .blocking_send(Streamed::Rows(std::mem::take(&mut batch)))
                        .is_err()
                {
                    return Err(DriverError::Cancelled);
                }
            }
            if columns.is_none() {
                let _ = tx.blocking_send(Streamed::Columns(shape.columns(None)));
            }
            if !batch.is_empty() {
                let _ = tx.blocking_send(Streamed::Rows(batch));
            }
            Ok(())
        });
        let writing = async {
            let mut count: u64 = 0;
            while let Some(message) = rx.recv().await {
                match message {
                    Streamed::Columns(c) => sink.begin(&c)?,
                    Streamed::Rows(rows) => {
                        for row in rows {
                            sink.write(&row)?;
                            count += 1;
                            if count.is_multiple_of(PROGRESS_EVERY) {
                                progress(count);
                            }
                        }
                    }
                }
            }
            Ok::<u64, DriverError>(count)
        };
        let (read_result, count) = tokio::join!(reading, writing);
        read_result?;
        let count = count?;
        sink.finish()?;
        progress(count);
        Ok(count)
    }

    async fn is_valid(&mut self) -> bool {
        self.call(|conn| conn.execute_batch("SELECT 1").map_err(error))
            .await
            .is_ok()
    }

    fn discard(&mut self) {
        self.discard = true;
    }

    async fn namespaces(&mut self) -> Result<Vec<Namespace>> {
        Ok(vec![Namespace::new(None, None)])
    }

    async fn tables(&mut self, namespace: &Namespace) -> Result<Vec<TableInfo>> {
        let ns = namespace.clone();
        self.call(move |conn| super::catalog::tables(conn, &ns))
            .await
    }

    async fn details(&mut self, table: &TableRef, keys_and_indexes: bool) -> Result<TableDetails> {
        let table = table.clone();
        self.call(move |conn| super::catalog::details(conn, &table, keys_and_indexes))
            .await
    }

    async fn columns_of(
        &mut self,
        namespace: &Namespace,
    ) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
        let ns = namespace.clone();
        self.call(move |conn| super::catalog::columns_of(conn, &ns))
            .await
    }

    async fn relations(&mut self, table: &TableRef) -> Result<Vec<TableRelation>> {
        let table = table.clone();
        self.call(move |conn| super::catalog::relations(conn, &table))
            .await
    }

    async fn structure(&mut self, details: TableDetails) -> Result<TableStructure> {
        self.call(move |conn| super::catalog::structure(conn, details))
            .await
    }

    async fn server_major_version(&mut self) -> Result<Option<i32>> {
        Ok(Some(rusqlite::version_number() / 1_000_000))
    }
}

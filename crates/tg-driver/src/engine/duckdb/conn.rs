//! One DuckDB connection as an [`SqlConn`], on its worker thread.

use super::values::{self, BinaryAs, Kind};
use super::{Idle, Worker, error};
use crate::engine::inline::{self, Standard};
use crate::error::{DriverError, Result};
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, RowCollector, SqlConn};
use async_trait::async_trait;
use duckdb::Connection;
use std::time::Instant;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tg_sql::{SqlClassifier, SqlSyntax, StatementKind};
use tokio::sync::{OwnedSemaphorePermit, mpsc, oneshot};

pub struct DuckConn {
    worker: Option<Worker>,
    pool: Option<(Idle, OwnedSemaphorePermit)>,
    discard: bool,
}

/// Runs `sql` and reads up to `max_rows` rows (+1 for `truncated`).
pub(super) fn read(
    conn: &Connection,
    sql: &str,
    max_rows: Option<usize>,
    binary: BinaryAs,
) -> Result<Rows> {
    let start = Instant::now();
    let mut stmt = conn.prepare(sql).map_err(error)?;
    let mut rows = stmt.query([]).map_err(error)?;
    let (columns, kinds): (Vec<ResultColumn>, Vec<Kind>) = {
        let s = rows.as_ref().expect("an executed statement");
        (0..s.column_count())
            .map(|i| {
                let name = s
                    .column_name(i)
                    .map_or_else(|_| format!("column{i}"), |n| n.clone());
                values::result_column(&name, &s.column_logical_type(i))
            })
            .unzip()
    };
    let mut collector = RowCollector::new(max_rows);
    while let Some(row) = rows.next().map_err(error)? {
        let cells = (0..columns.len())
            .map(|i| {
                row.get_ref(i)
                    .ok()
                    .and_then(|v| values::cell(v, kinds[i], binary))
            })
            .collect();
        if !collector.push(cells) {
            break;
        }
    }
    Ok(collector.finish(columns, start.elapsed().as_millis() as u64))
}

impl DuckConn {
    pub fn owned(worker: Worker) -> Self {
        Self {
            worker: Some(worker),
            pool: None,
            discard: false,
        }
    }

    pub fn pooled(worker: Worker, idle: Idle, permit: OwnedSemaphorePermit) -> Self {
        Self {
            worker: Some(worker),
            pool: Some((idle, permit)),
            discard: false,
        }
    }

    fn worker(&self) -> &Worker {
        self.worker.as_ref().expect("present until drop")
    }

    fn inline(sql: &str, params: &[Param<'_>]) -> Result<String> {
        inline::inline(sql, SqlSyntax::POSTGRESQL, params, &Standard)
    }

    async fn rows(&self, sql: String, max_rows: Option<usize>) -> Result<Rows> {
        self.worker()
            .call(move |c| read(c, &sql, max_rows, BinaryAs::Preview))
            .await
    }

    pub(super) async fn text_rows(&self, sql: &str) -> Result<Vec<Vec<Cell>>> {
        Ok(self.rows(sql.to_owned(), None).await?.rows)
    }
}

impl Drop for DuckConn {
    fn drop(&mut self) {
        let (Some((idle, permit)), Some(worker)) = (self.pool.take(), self.worker.take()) else {
            return;
        };
        if self.discard {
            return;
        }
        // Whatever the borrower left open is rolled back before the next one gets the connection.
        worker.post(|c| {
            let _ = c.execute_batch("ROLLBACK");
        });
        idle.lock().expect("pool lock").push(worker);
        drop(permit);
    }
}

#[async_trait]
impl SqlConn for DuckConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let start = Instant::now();
        let reads =
            SqlClassifier::new(SqlSyntax::POSTGRESQL).classify(sql).kind == StatementKind::Read;
        if reads {
            return Ok(QueryResult::Rows(
                self.rows(sql.to_owned(), Some(options.max_rows)).await?,
            ));
        }
        let sql = sql.to_owned();
        let affected = self
            .worker()
            .call(move |c| c.execute(&sql, []).map_err(error))
            .await?;
        Ok(QueryResult::Update {
            affected_rows: affected as u64,
            elapsed_ms: start.elapsed().as_millis() as u64,
        })
    }

    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows> {
        self.rows(Self::inline(sql, params)?, max_rows).await
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        // DuckDB has no savepoints. The shared code only rolls back to one to find a failing insert row, and then
        // rolls everything back: restarting the transaction does for that.
        let words: Vec<&str> = sql.split_whitespace().collect();
        match words.as_slice() {
            ["SAVEPOINT", _] | ["RELEASE", "SAVEPOINT", _] => return Ok(0),
            ["ROLLBACK", "TO", "SAVEPOINT", _] => {
                return self
                    .worker()
                    .call(|c| {
                        c.execute_batch("ROLLBACK; BEGIN TRANSACTION")
                            .map_err(error)
                    })
                    .await
                    .map(|()| 0);
            }
            _ => {}
        }
        let sql = Self::inline(sql, params)?;
        let affected = self
            .worker()
            .call(move |c| c.execute(&sql, []).map_err(error))
            .await?;
        Ok(affected as u64)
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
        let reading = self.worker().call(move |c| {
            let mut stmt = c.prepare(&sql).map_err(error)?;
            let mut rows = stmt.query([]).map_err(error)?;
            let (columns, kinds): (Vec<ResultColumn>, Vec<Kind>) = {
                let s = rows.as_ref().expect("an executed statement");
                (0..s.column_count())
                    .map(|i| {
                        let name = s
                            .column_name(i)
                            .map_or_else(|_| format!("column{i}"), |n| n.clone());
                        values::result_column(&name, &s.column_logical_type(i))
                    })
                    .unzip()
            };
            let width = columns.len();
            let _ = columns_tx.send(columns);
            let mut batch = Vec::with_capacity(1000);
            while let Some(row) = rows.next().map_err(error)? {
                batch.push(
                    (0..width)
                        .map(|i| {
                            row.get_ref(i)
                                .ok()
                                .and_then(|v| values::cell(v, kinds[i], BinaryAs::Placeholder))
                        })
                        .collect(),
                );
                if batch.len() == 1000 && rows_tx.blocking_send(std::mem::take(&mut batch)).is_err()
                {
                    return Ok(());
                }
            }
            if !batch.is_empty() {
                let _ = rows_tx.blocking_send(batch);
            }
            Ok(())
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
        self.rows("SELECT 1".into(), Some(1)).await.is_ok()
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
            .map(|v| v.trim_start_matches('v'))
            .and_then(|v| v.split('.').next())
            .and_then(|v| v.parse().ok()))
    }
}

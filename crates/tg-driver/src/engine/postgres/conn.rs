//! One PostgreSQL connection as an [`SqlConn`]: simple-protocol text results (decision B1), column types from a
//! pipelined `prepare`, row limits through a cursor so a console's transaction is never cancelled, and a server-side
//! cancel when a statement's future is dropped.

use super::literal::inline;
use super::types;
use super::{Tls, error};
use crate::error::Result;
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, RowCollector, SqlConn};
use async_trait::async_trait;
use futures_util::{StreamExt, pin_mut};
use std::time::Instant;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tokio_postgres::{CancelToken, Client, SimpleQueryMessage};

pub enum Handle {
    Pooled(Option<deadpool_postgres::Object>),
    Owned(Client),
}

pub struct PgConn {
    handle: Handle,
    tls: Tls,
    discard: bool,
    /// SQL that undoes a multi-step operation a dropped future left half-way (a cursor's transaction).
    cleanup: Option<&'static str>,
}

/// Sends a cancel request for whatever runs on the connection if dropped while armed.
struct CancelOnDrop {
    token: CancelToken,
    tls: Tls,
    armed: bool,
}

impl Drop for CancelOnDrop {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let (token, tls) = (self.token.clone(), self.tls.clone());
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn(async move {
                let _ = token.cancel_query(tls).await;
            });
        }
    }
}

const CURSOR: &str = "tg_cursor";

impl PgConn {
    pub fn new(handle: Handle, tls: Tls) -> Self {
        Self {
            handle,
            tls,
            discard: false,
            cleanup: None,
        }
    }

    pub(super) fn client(&self) -> &Client {
        match &self.handle {
            Handle::Pooled(o) => o.as_ref().expect("present until drop"),
            Handle::Owned(c) => c,
        }
    }

    fn guard(&self) -> CancelOnDrop {
        CancelOnDrop {
            token: self.client().cancel_token(),
            tls: self.tls.clone(),
            armed: true,
        }
    }

    /// Runs leftover cleanup from an operation that was dropped half-way.
    async fn settle(&mut self) {
        if let Some(sql) = self.cleanup.take() {
            let _ = self.client().batch_execute(sql).await;
        }
    }

    /// All messages of one simple query, cancelled on the server if dropped.
    pub(super) async fn simple(&self, sql: &str) -> Result<Vec<SimpleQueryMessage>> {
        let mut guard = self.guard();
        let result = self.client().simple_query(sql).await.map_err(error);
        guard.armed = false;
        result
    }

    /// Text rows of a statement that returns rows; empty for others.
    pub(super) async fn text_rows(&self, sql: &str) -> Result<Vec<Vec<Option<String>>>> {
        Ok(self
            .simple(sql)
            .await?
            .into_iter()
            .filter_map(|m| match m {
                SimpleQueryMessage::Row(r) => {
                    Some((0..r.len()).map(|i| r.get(i).map(str::to_owned)).collect())
                }
                _ => None,
            })
            .collect())
    }

    /// Result columns as pgjdbc typed them, from a prepared statement; None when the statement can't be prepared
    /// (several statements, or a transaction already in error).
    async fn columns(&self, sql: &str) -> Option<Vec<ResultColumn>> {
        let statement = self.client().prepare(sql).await.ok()?;
        Some(types::result_columns(self.client(), statement.columns()).await)
    }

    /// Runs `sql` and reads its rows, typed by a pipelined prepare.
    async fn read(
        &self,
        sql: &str,
        max_rows: Option<usize>,
        binary: types::BinaryAs,
    ) -> Result<(Vec<ResultColumn>, Rows)> {
        let start = Instant::now();
        let (columns, messages) = tokio::join!(self.columns(sql), self.simple(sql));
        let messages = messages?;
        let mut names = Vec::new();
        let mut collector = RowCollector::new(max_rows);
        for m in &messages {
            match m {
                SimpleQueryMessage::RowDescription(d) => {
                    names = d.iter().map(|c| c.name().to_owned()).collect()
                }
                SimpleQueryMessage::Row(r) => {
                    if names.is_empty() {
                        names = r.columns().iter().map(|c| c.name().to_owned()).collect();
                    }
                    let _ =
                        collector.push((0..r.len()).map(|i| r.get(i).map(str::to_owned)).collect());
                }
                _ => {}
            }
        }
        let columns = match columns {
            Some(c) if c.len() == names.len() => c,
            _ => names
                .iter()
                .map(|n| ResultColumn::new(n, "unknown", tg_sql::model::jdbc_types::OTHER))
                .collect(),
        };
        let mut rows = collector.finish(columns.clone(), 0);
        types::render_binary(&columns, &mut rows.rows, binary);
        rows.elapsed_ms = start.elapsed().as_millis() as u64;
        Ok((columns, rows))
    }

    /// A console query read through a cursor, so only `max_rows` + 1 rows cross the wire and nothing is cancelled:
    /// in a transaction under a savepoint, otherwise in a transaction of its own. None when the statement can't be a
    /// cursor (the caller then runs it as is).
    async fn read_limited(&mut self, sql: &str, max_rows: usize) -> Result<Option<Rows>> {
        let start = Instant::now();
        let in_transaction = self.simple("SAVEPOINT tg_limit").await.is_ok();
        let (open, undo, close): (&str, &'static str, &'static str) = if in_transaction {
            (
                "",
                "ROLLBACK TO SAVEPOINT tg_limit; RELEASE SAVEPOINT tg_limit",
                "CLOSE tg_cursor; RELEASE SAVEPOINT tg_limit",
            )
        } else {
            ("BEGIN; ", "ROLLBACK", "CLOSE tg_cursor; COMMIT")
        };
        self.cleanup = Some(undo);
        let declare = format!(
            "{open}DECLARE {CURSOR} NO SCROLL CURSOR FOR {}",
            sql.trim().trim_end_matches(';')
        );
        let (columns, declared) = tokio::join!(self.columns(sql), self.simple(&declare));
        if declared.is_err() {
            self.settle().await;
            return Ok(None);
        }
        let fetched = self
            .simple(&format!("FETCH FORWARD {} FROM {CURSOR}", max_rows + 1))
            .await;
        let messages = match fetched {
            Ok(m) => m,
            Err(e) => {
                // Like pgjdbc: an error inside the user's transaction leaves it failed; our own one is rolled back.
                if in_transaction {
                    self.cleanup = None;
                } else {
                    self.settle().await;
                }
                return Err(e);
            }
        };
        self.simple(close).await?;
        self.cleanup = None;
        let mut names = Vec::new();
        let mut collector = RowCollector::new(Some(max_rows));
        for m in messages {
            match m {
                SimpleQueryMessage::RowDescription(d) => {
                    names = d.iter().map(|c| c.name().to_owned()).collect()
                }
                SimpleQueryMessage::Row(r) => {
                    let _ =
                        collector.push((0..r.len()).map(|i| r.get(i).map(str::to_owned)).collect());
                }
                _ => {}
            }
        }
        let columns = match columns {
            Some(c) if c.len() == names.len() || names.is_empty() => c,
            _ => names
                .iter()
                .map(|n| ResultColumn::new(n, "unknown", tg_sql::model::jdbc_types::OTHER))
                .collect(),
        };
        let mut rows = collector.finish(columns.clone(), 0);
        types::render_binary(&columns, &mut rows.rows, types::BinaryAs::Preview);
        rows.elapsed_ms = start.elapsed().as_millis() as u64;
        Ok(Some(rows))
    }
}

impl Drop for PgConn {
    fn drop(&mut self) {
        // A pooled connection dropped mid-operation (or marked) is closed rather than pooled.
        if let Handle::Pooled(object) = &mut self.handle
            && (self.discard || self.cleanup.is_some())
            && let Some(object) = object.take()
        {
            drop(deadpool_postgres::Object::take(object));
        }
    }
}

/// Whether a console statement is worth reading through a cursor: one that only reads rows.
fn cursorable(sql: &str) -> bool {
    let info = tg_sql::SqlClassifier::new(tg_sql::SqlSyntax::POSTGRESQL).classify(sql);
    info.kind == tg_sql::StatementKind::Read
        && matches!(info.verb.as_str(), "SELECT" | "VALUES" | "TABLE" | "WITH")
}

#[async_trait]
impl SqlConn for PgConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        self.settle().await;
        if cursorable(sql)
            && let Some(rows) = self.read_limited(sql, options.max_rows).await?
        {
            return Ok(QueryResult::Rows(rows));
        }
        let start = Instant::now();
        let (columns, messages) = tokio::join!(self.columns(sql), self.simple(sql));
        let messages = messages?;
        let has_rows = messages.iter().any(|m| {
            matches!(
                m,
                SimpleQueryMessage::RowDescription(_) | SimpleQueryMessage::Row(_)
            )
        });
        if !has_rows {
            let affected_rows = messages
                .iter()
                .rev()
                .find_map(|m| match m {
                    SimpleQueryMessage::CommandComplete(n) => Some(*n),
                    _ => None,
                })
                .unwrap_or(0);
            return Ok(QueryResult::Update {
                affected_rows,
                elapsed_ms: start.elapsed().as_millis() as u64,
            });
        }
        let mut names = Vec::new();
        let mut collector = RowCollector::new(Some(options.max_rows));
        for m in messages {
            match m {
                SimpleQueryMessage::RowDescription(d) => {
                    names = d.iter().map(|c| c.name().to_owned()).collect()
                }
                SimpleQueryMessage::Row(r) => {
                    let _ =
                        collector.push((0..r.len()).map(|i| r.get(i).map(str::to_owned)).collect());
                }
                _ => {}
            }
        }
        let columns = match columns {
            Some(c) if c.len() == names.len() => c,
            _ => names
                .iter()
                .map(|n| ResultColumn::new(n, "unknown", tg_sql::model::jdbc_types::OTHER))
                .collect(),
        };
        let mut rows = collector.finish(columns.clone(), start.elapsed().as_millis() as u64);
        types::render_binary(&columns, &mut rows.rows, types::BinaryAs::Preview);
        Ok(QueryResult::Rows(rows))
    }

    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows> {
        self.settle().await;
        let sql = inline(sql, params)?;
        Ok(self.read(&sql, max_rows, types::BinaryAs::Preview).await?.1)
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        self.settle().await;
        let sql = inline(sql, params)?;
        Ok(self
            .simple(&sql)
            .await?
            .iter()
            .rev()
            .find_map(|m| match m {
                SimpleQueryMessage::CommandComplete(n) => Some(*n),
                _ => None,
            })
            .unwrap_or(0))
    }

    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        self.settle().await;
        let sql = inline(sql, params)?;
        // PostgreSQL only streams through a cursor inside a transaction, as pgjdbc's fetch size did.
        self.cleanup = Some("ROLLBACK");
        let declare = format!("BEGIN; DECLARE {CURSOR} NO SCROLL CURSOR FOR {sql}");
        let (columns, declared) = tokio::join!(self.columns(&sql), self.simple(&declare));
        if let Err(e) = declared {
            self.settle().await;
            return Err(e);
        }
        let columns = columns.unwrap_or_default();
        sink.begin(&columns)?;
        let mut count: u64 = 0;
        loop {
            let mut guard = self.guard();
            let stream = self
                .client()
                .simple_query_raw(&format!("FETCH FORWARD 1000 FROM {CURSOR}"))
                .await
                .map_err(error)?;
            pin_mut!(stream);
            let mut batch: Vec<Vec<Cell>> = Vec::new();
            while let Some(message) = stream.next().await {
                if let SimpleQueryMessage::Row(r) = message.map_err(error)? {
                    batch.push((0..r.len()).map(|i| r.get(i).map(str::to_owned)).collect());
                }
            }
            guard.armed = false;
            let fetched = batch.len();
            types::render_binary(&columns, &mut batch, types::BinaryAs::Placeholder);
            for row in batch {
                sink.write(&row)?;
                count += 1;
                if count.is_multiple_of(PROGRESS_EVERY) {
                    progress(count);
                }
            }
            if fetched < 1000 {
                break;
            }
        }
        self.settle().await;
        sink.finish()?;
        progress(count);
        Ok(count)
    }

    async fn is_valid(&mut self) -> bool {
        self.settle().await;
        tokio::time::timeout(
            std::time::Duration::from_secs(2),
            self.client().simple_query("SELECT 1"),
        )
        .await
        .is_ok_and(|r| r.is_ok())
    }

    fn discard(&mut self) {
        self.discard = true;
    }

    async fn namespaces(&mut self) -> Result<Vec<Namespace>> {
        self.settle().await;
        super::catalog::namespaces(self).await
    }

    async fn tables(&mut self, namespace: &Namespace) -> Result<Vec<TableInfo>> {
        self.settle().await;
        super::catalog::tables(self, namespace).await
    }

    async fn details(&mut self, table: &TableRef, keys_and_indexes: bool) -> Result<TableDetails> {
        self.settle().await;
        super::catalog::details(self, table, keys_and_indexes).await
    }

    async fn columns_of(
        &mut self,
        namespace: &Namespace,
    ) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
        self.settle().await;
        super::catalog::columns_of(self, namespace).await
    }

    async fn relations(&mut self, table: &TableRef) -> Result<Vec<TableRelation>> {
        self.settle().await;
        super::catalog::relations(self, table).await
    }

    async fn structure(&mut self, details: TableDetails) -> Result<TableStructure> {
        self.settle().await;
        super::structure::read(self, details).await
    }

    async fn server_major_version(&mut self) -> Result<Option<i32>> {
        let rows = self.text_rows("SHOW server_version_num").await?;
        Ok(rows
            .first()
            .and_then(|r| r[0].as_deref())
            .and_then(|v| v.parse::<i32>().ok())
            .map(|v| v / 10_000))
    }
}

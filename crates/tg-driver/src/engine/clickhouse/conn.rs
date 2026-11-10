//! One ClickHouse "connection" as an [`SqlConn`]: HTTP requests, in a session for consoles.

use super::{Http, new_id, server_error, types};
use crate::engine::inline::{self, Literals};
use crate::error::{DriverError, Result};
use crate::result::{
    Cell, ExecOptions, PROGRESS_EVERY, Progress, QueryResult, ResultColumn, RowSink, Rows,
};
use crate::sql::{Param, RowCollector, SqlConn};
use async_trait::async_trait;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tg_sql::{SqlClassifier, SqlSyntax, StatementKind};
use tokio::task::JoinHandle;

/// ClickHouse string literals: backslash escapes, as its parser reads them.
struct ChLiterals;

impl Literals for ChLiterals {
    fn text(&self, value: &str) -> Result<String> {
        Ok(format!(
            "'{}'",
            value.replace('\\', "\\\\").replace('\'', "\\'")
        ))
    }
}

type KillSlot = Arc<Mutex<Option<JoinHandle<()>>>>;

/// Sends `KILL QUERY` for the request if dropped while armed; the next request waits for it.
struct KillOnDrop {
    http: Arc<Http>,
    query_id: String,
    slot: KillSlot,
    armed: bool,
}

impl Drop for KillOnDrop {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let (http, id) = (self.http.clone(), std::mem::take(&mut self.query_id));
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            let handle = runtime.spawn(async move { http.kill(&id).await });
            *self.slot.lock().expect("kill slot") = Some(handle);
        }
    }
}

pub struct ChConn {
    http: Arc<Http>,
    session: Option<String>,
    kill: KillSlot,
}

/// Reads a `TabSeparatedWithNamesAndTypes` body line by line.
struct Lines {
    response: reqwest::Response,
    buffer: Vec<u8>,
    done: bool,
}

impl Lines {
    async fn next(&mut self) -> Result<Option<String>> {
        loop {
            if let Some(i) = self.buffer.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = self.buffer.drain(..=i).collect();
                let text = String::from_utf8_lossy(&line[..line.len() - 1]).into_owned();
                // An error after rows were sent arrives in the body.
                if text.starts_with("Code: ") && text.contains("DB::Exception") {
                    return Err(server_error(&text));
                }
                return Ok(Some(text));
            }
            if self.done {
                if self.buffer.is_empty() {
                    return Ok(None);
                }
                let text = String::from_utf8_lossy(&std::mem::take(&mut self.buffer)).into_owned();
                return Ok(Some(text));
            }
            match self
                .response
                .chunk()
                .await
                .map_err(|e| DriverError::Connect(e.to_string()))?
            {
                Some(chunk) => self.buffer.extend_from_slice(&chunk),
                None => self.done = true,
            }
        }
    }
}

/// What one request returned.
enum Outcome {
    Rows(Rows),
    Update(u64),
}

impl ChConn {
    pub fn new(http: Arc<Http>, session: Option<String>) -> Self {
        Self {
            http,
            session,
            kill: KillSlot::default(),
        }
    }

    fn guard(&self, query_id: &str) -> KillOnDrop {
        KillOnDrop {
            http: self.http.clone(),
            query_id: query_id.to_owned(),
            slot: self.kill.clone(),
            armed: true,
        }
    }

    /// Sends a request once any kill of the previous one is through. A session stays locked until its cancelled
    /// query has stopped, so the request is retried for a few seconds while it is.
    async fn post(&self, sql: &str, query_id: &str, wait: bool) -> Result<reqwest::Response> {
        let pending = self.kill.lock().expect("kill slot").take();
        if let Some(kill) = pending {
            let _ = tokio::time::timeout(Duration::from_secs(10), kill).await;
        }
        let mut attempts = 0;
        loop {
            match self
                .http
                .post(sql, query_id, self.session.as_deref(), wait)
                .await
            {
                // 373: SESSION_IS_LOCKED.
                Err(DriverError::Query(e)) if e.vendor_code == Some(373) && attempts < 50 => {
                    attempts += 1;
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
                other => return other,
            }
        }
    }

    fn inline(sql: &str, params: &[Param<'_>]) -> Result<String> {
        inline::inline(sql, SqlSyntax::CLICKHOUSE, params, &ChLiterals)
    }

    /// Sends `sql` and reads up to `max_rows` rows (+1 for `truncated`), or what it wrote.
    async fn read(&mut self, sql: &str, max_rows: Option<usize>, wait: bool) -> Result<Outcome> {
        let start = Instant::now();
        let query_id = new_id("query");
        let mut guard = self.guard(&query_id);
        let response = self.post(sql, &query_id, wait).await?;
        let written = response
            .headers()
            .get("X-ClickHouse-Summary")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| serde_json::from_str::<serde_json::Value>(s).ok())
            .and_then(|v| v["written_rows"].as_str().and_then(|n| n.parse().ok()))
            .unwrap_or(0);
        let mut lines = Lines {
            response,
            buffer: Vec::new(),
            done: false,
        };
        let Some(names) = lines.next().await? else {
            guard.armed = false;
            return Ok(Outcome::Update(written));
        };
        let types_line = lines.next().await?.unwrap_or_default();
        let columns: Vec<ResultColumn> = names
            .split('\t')
            .zip(types_line.split('\t'))
            .map(|(n, t)| {
                let name = types::field(n).unwrap_or_default();
                let ty = types::field(t).unwrap_or_default();
                ResultColumn::new(&name, &ty, types::jdbc_type(&ty))
            })
            .collect();
        let mut collector = RowCollector::new(max_rows);
        let mut complete = true;
        while let Some(line) = lines.next().await? {
            if !collector.push(types::line(&line)) {
                complete = false;
                break;
            }
        }
        // Rows nobody reads are stopped on the server; a finished query needs nothing.
        guard.armed = !complete;
        drop(guard);
        Ok(Outcome::Rows(
            collector.finish(columns, start.elapsed().as_millis() as u64),
        ))
    }

    pub(super) async fn text_rows(&mut self, sql: &str) -> Result<Vec<Vec<Cell>>> {
        Ok(match self.read(sql, None, false).await? {
            Outcome::Rows(r) => r.rows,
            Outcome::Update(_) => Vec::new(),
        })
    }
}

/// Statements the shared code sends for transactions, which ClickHouse doesn't have.
fn transaction_statement(sql: &str) -> bool {
    let first = sql
        .split_whitespace()
        .next()
        .unwrap_or("")
        .to_ascii_uppercase();
    matches!(
        first.as_str(),
        "BEGIN" | "COMMIT" | "ROLLBACK" | "SAVEPOINT" | "RELEASE"
    )
}

#[async_trait]
impl SqlConn for ChConn {
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let start = Instant::now();
        let reads =
            SqlClassifier::new(SqlSyntax::CLICKHOUSE).classify(sql).kind == StatementKind::Read;
        Ok(
            match self.read(sql, Some(options.max_rows), !reads).await? {
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
        let sql = Self::inline(sql, params)?;
        Ok(match self.read(&sql, max_rows, false).await? {
            Outcome::Rows(r) => r,
            Outcome::Update(_) => Rows {
                columns: Vec::new(),
                rows: Vec::new(),
                truncated: false,
                elapsed_ms: 0,
            },
        })
    }

    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64> {
        if transaction_statement(sql) {
            return Ok(0);
        }
        let sql = Self::inline(sql, params)?;
        Ok(match self.read(&sql, Some(0), true).await? {
            Outcome::Update(n) => n,
            Outcome::Rows(_) => 0,
        })
    }

    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        let sql = Self::inline(sql, params)?;
        let query_id = new_id("export");
        let mut guard = self.guard(&query_id);
        let response = self.post(&sql, &query_id, false).await?;
        let mut lines = Lines {
            response,
            buffer: Vec::new(),
            done: false,
        };
        let names = lines.next().await?.unwrap_or_default();
        let types_line = lines.next().await?.unwrap_or_default();
        let columns: Vec<ResultColumn> = names
            .split('\t')
            .zip(types_line.split('\t'))
            .map(|(n, t)| {
                let ty = types::field(t).unwrap_or_default();
                ResultColumn::new(
                    &types::field(n).unwrap_or_default(),
                    &ty,
                    types::jdbc_type(&ty),
                )
            })
            .collect();
        sink.begin(&columns)?;
        let mut count: u64 = 0;
        while let Some(line) = lines.next().await? {
            sink.write(&types::line(&line))?;
            count += 1;
            if count.is_multiple_of(PROGRESS_EVERY) {
                progress(count);
            }
        }
        guard.armed = false;
        sink.finish()?;
        progress(count);
        Ok(count)
    }

    // No transactions: the shared code's BEGIN/COMMIT/ROLLBACK do nothing, as ClickHouse JDBC's did.
    async fn begin(&mut self) -> Result<()> {
        Ok(())
    }

    async fn commit(&mut self) -> Result<()> {
        Ok(())
    }

    async fn rollback(&mut self) -> Result<()> {
        Ok(())
    }

    async fn is_valid(&mut self) -> bool {
        tokio::time::timeout(
            Duration::from_secs(2),
            self.read("SELECT 1", Some(1), false),
        )
        .await
        .is_ok_and(|r| r.is_ok())
    }

    fn discard(&mut self) {}

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

    async fn relations(&mut self, _table: &TableRef) -> Result<Vec<TableRelation>> {
        // ClickHouse has no foreign keys.
        Ok(Vec::new())
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

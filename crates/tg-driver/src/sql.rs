//! What an SQL engine implements: one connection ([`SqlConn`]) and where connections come from ([`ConnSource`]).
//! [`crate::relational::SqlDatabase`] builds the whole [`crate::RelationalConnection`] on top of these, the way
//! `Database.kt` builds it on JDBC.

use crate::activity::ActivityMonitor;
use crate::error::Result;
use crate::result::{Cell, ExecOptions, Progress, QueryResult, ResultColumn, RowSink, Rows};
use async_trait::async_trait;
use std::path::Path;
use std::time::Duration;
use tg_sql::edit::BoundValue;
use tg_sql::filter::SqlParam;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};

/// A value bound to a `?` in the SQL the core generates. Engines that can't bind text (PostgreSQL over the simple
/// protocol, decision B1) inline it as a literal instead.
#[derive(Clone, Copy, Debug)]
pub enum Param<'a> {
    /// A cell value read as text, written back (`dialect.bind(…, value, jdbcType)`).
    Value(&'a BoundValue),
    /// A typed filter value (`dialect.bind(…, SqlParam)`).
    Filter(&'a SqlParam),
    /// Plain text, e.g. a LIKE pattern.
    Text(&'a str),
}

/// One connection, owned by one task at a time. Every method must be safe to drop mid-way: an engine that has a
/// statement running on the server cancels it when its future is dropped. A dedicated connection (a console's) stays
/// usable afterwards, as after a JDBC `Statement.cancel()`; a pooled one is closed instead of going back to the pool.
#[async_trait]
pub trait SqlConn: Send {
    /// Runs one console statement of any kind.
    async fn run(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult>;

    /// Runs a query the core generated and reads up to `max_rows` rows (+1 to tell `truncated`); None reads all.
    /// Binary cells come back as [`crate::binary_text`] previews.
    async fn query(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        max_rows: Option<usize>,
    ) -> Result<Rows>;

    /// Runs a statement and returns the rows it changed.
    async fn execute(&mut self, sql: &str, params: &[Param<'_>]) -> Result<u64>;

    /// Streams every row into `sink` with binary cells as placeholders, without holding the result in memory.
    async fn stream(
        &mut self,
        sql: &str,
        params: &[Param<'_>],
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> Result<u64>;

    async fn begin(&mut self) -> Result<()> {
        self.execute("BEGIN", &[]).await.map(|_| ())
    }

    async fn commit(&mut self) -> Result<()> {
        self.execute("COMMIT", &[]).await.map(|_| ())
    }

    async fn rollback(&mut self) -> Result<()> {
        self.execute("ROLLBACK", &[]).await.map(|_| ())
    }

    async fn is_valid(&mut self) -> bool;

    /// Marks the connection as left in an unknown state (a transaction or setting half-way): it is closed when
    /// dropped instead of going back to the pool.
    fn discard(&mut self);

    // Introspection: what JDBC's DatabaseMetaData answered in Kotlin, as each engine's catalog queries.

    async fn namespaces(&mut self) -> Result<Vec<Namespace>>;

    async fn tables(&mut self, namespace: &Namespace) -> Result<Vec<TableInfo>>;

    /// Columns and primary key, plus indexes and foreign keys when `keys_and_indexes`.
    async fn details(&mut self, table: &TableRef, keys_and_indexes: bool) -> Result<TableDetails>;

    /// Every table's columns, in catalog order.
    async fn columns_of(&mut self, namespace: &Namespace)
    -> Result<Vec<(String, Vec<ColumnInfo>)>>;

    async fn relations(&mut self, table: &TableRef) -> Result<Vec<TableRelation>>;

    /// Constraints, triggers and DDL around `details` (`StructureReader`).
    async fn structure(&mut self, details: TableDetails) -> Result<TableStructure>;

    async fn server_major_version(&mut self) -> Result<Option<i32>> {
        Ok(None)
    }
}

/// Where an engine's connections come from: a small shared pool, and dedicated ones for consoles and monitors.
#[async_trait]
pub trait ConnSource: Send + Sync + 'static {
    /// A pooled connection; dropping it gives it back (or discards it if it was dropped mid-statement).
    async fn pooled(&self) -> Result<Box<dyn SqlConn>>;

    /// A new connection of its own, outside the pool.
    async fn dedicated(&self) -> Result<Box<dyn SqlConn>>;

    /// Connections in the pool; schema snapshots read with one fewer at a time.
    fn pool_size(&self) -> usize {
        4
    }

    async fn backup_into(&self, _target: &Path) -> Result<()> {
        Err(crate::DriverError::Unsupported(
            "can't back itself up".into(),
        ))
    }

    async fn activity_monitor(&self) -> Result<Box<dyn ActivityMonitor>> {
        Err(crate::DriverError::Unsupported(
            "has no activity monitor".into(),
        ))
    }
}

/// Reads rows with a deadline; the read is dropped (and so cancelled on the server) when it passes.
pub async fn with_timeout<T>(
    timeout: Option<Duration>,
    work: impl Future<Output = Result<T>>,
) -> Result<T> {
    match timeout.filter(|t| !t.is_zero()) {
        None => work.await,
        Some(t) => tokio::time::timeout(t, work)
            .await
            .unwrap_or(Err(crate::DriverError::Timeout)),
    }
}

/// Collects rows up to `max_rows` + 1, for engines that read row by row.
pub struct RowCollector {
    pub rows: Vec<Vec<Cell>>,
    max_rows: Option<usize>,
}

impl RowCollector {
    pub fn new(max_rows: Option<usize>) -> Self {
        Self {
            rows: Vec::new(),
            max_rows,
        }
    }

    /// Adds a row; false once one past the limit has been read, so the reader can stop.
    pub fn push(&mut self, row: Vec<Cell>) -> bool {
        self.rows.push(row);
        self.max_rows.is_none_or(|m| self.rows.len() <= m)
    }

    pub fn finish(mut self, columns: Vec<ResultColumn>, elapsed_ms: u64) -> Rows {
        let truncated = self.max_rows.is_some_and(|m| self.rows.len() > m);
        if let Some(m) = self.max_rows {
            self.rows.truncate(m);
        }
        Rows {
            columns,
            rows: self.rows,
            truncated,
            elapsed_ms,
        }
    }
}

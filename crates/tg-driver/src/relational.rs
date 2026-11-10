//! Tables and SQL (`driver/DatabaseConnection.kt`, `QuerySession.kt`, `Database.kt`): the trait the explorer, table
//! viewer, structure view and consoles use, and [`SqlDatabase`], which implements it for every SQL engine on top of the
//! engine's [`SqlConn`] and [`ConnSource`].

use crate::activity::ActivityMonitor;
use crate::error::{DriverError, Result};
use crate::result::{Cell, ExecOptions, PageRequest, Progress, QueryResult, Rows, TableQuery};
use crate::sql::{ConnSource, Param, SqlConn, with_timeout};
use async_trait::async_trait;
use futures_util::{StreamExt, stream};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};
use tg_core::connection::ConnectionConfig;
use tg_sql::ddl::{DdlPhase, DdlScript};
use tg_sql::edit::{BoundValue, ChangeStatement};
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};
use tg_sql::schema::SchemaSnapshot;
use tg_sql::{SqlClassifier, SqlDialect, StatementKind};

/// Namespaces, tables and SQL: what the explorer, table viewer, structure view and consoles work with.
#[async_trait]
pub trait RelationalConnection: Send + Sync {
    fn config(&self) -> &ConnectionConfig;

    fn dialect(&self) -> SqlDialect;

    async fn namespaces(&self) -> Result<Vec<Namespace>>;

    async fn tables(&self, namespace: &Namespace) -> Result<Vec<TableInfo>>;

    async fn table_details(&self, table: &TableRef) -> Result<TableDetails>;

    /// Just the columns and primary key, which is all a table viewer needs.
    async fn table_columns(&self, table: &TableRef) -> Result<TableDetails>;

    /// Columns, keys and indexes plus constraints, triggers and DDL, for the structure view.
    async fn table_structure(&self, table: &TableRef) -> Result<TableStructure>;

    async fn columns_of(&self, namespace: &Namespace) -> Result<Vec<(String, Vec<ColumnInfo>)>>;

    async fn relations(&self, table: &TableRef) -> Result<Vec<TableRelation>>;

    /// Values of `column` starting with `prefix`, for suggestions; bounded in rows scanned and time.
    async fn sample_values(
        &self,
        table: &TableRef,
        column: &str,
        prefix: &str,
        limit: usize,
    ) -> Result<Vec<String>>;

    /// A page of rows; `truncated` says whether another page follows.
    async fn fetch_page(&self, request: &PageRequest<'_>) -> Result<Rows>;

    /// Streams every row (with the viewer's filter and sort) into `sink`; drop the future to stop.
    async fn export_table(
        &self,
        query: &TableQuery<'_>,
        sink: &mut dyn crate::RowSink,
        progress: Progress<'_>,
    ) -> Result<u64>;

    async fn count_rows(&self, query: &TableQuery<'_>, timeout: Option<Duration>) -> Result<u64>;

    /// Applies `statements` in one transaction, rolling back unless each hits the rows it expects.
    async fn apply_changes(&self, statements: &[ChangeStatement]) -> Result<u64>;

    /// Every table of `namespace` with columns, keys and indexes; `progress` gets tables read and the total.
    async fn schema_snapshot(
        &self,
        namespace: &Namespace,
        progress: &(dyn Fn(usize, usize) + Send + Sync),
    ) -> Result<SchemaSnapshot>;

    /// Runs a schema tool's script: in one transaction when the engine can roll DDL back, else statement by
    /// statement, stopping at the first failure ([`DriverError::Script`] says how far it got).
    async fn execute_script(
        &self,
        script: &DdlScript,
        progress: &(dyn Fn(usize) + Send + Sync),
    ) -> Result<usize>;

    /// Inserts rows (text values, one per column) in batches, all in one transaction: on failure or cancellation
    /// nothing is kept. `describe` names a row by its index in error messages. Returns the row count.
    async fn insert_rows(
        &self,
        table: &TableRef,
        columns: &[ColumnInfo],
        rows: &mut (dyn Iterator<Item = Vec<Cell>> + Send),
        describe: &(dyn Fn(u64) -> String + Send + Sync),
        progress: Progress<'_>,
    ) -> Result<u64>;

    /// A dedicated session for a console, with its own transaction state.
    async fn open_session(&self) -> Result<Box<dyn QuerySession>>;

    /// The server's major version, e.g. 17; None if unknown.
    async fn server_major_version(&self) -> Result<Option<i32>> {
        Ok(None)
    }

    /// Backs the database up by its own means (SQLite `VACUUM INTO`). Reads, never writes.
    async fn backup_into(&self, _target: &Path) -> Result<()> {
        Err(DriverError::Unsupported(format!(
            "{} can't back itself up",
            self.config().db_type.display_name
        )))
    }

    /// Watches the server's sessions over a connection of its own.
    async fn open_activity_monitor(&self) -> Result<Box<dyn ActivityMonitor>> {
        Err(DriverError::Unsupported(format!(
            "{} has no activity monitor",
            self.config().db_type.display_name
        )))
    }
}

/// A dedicated connection for one SQL console, so session state (transactions, `SET` variables, temp tables) stays
/// with that console and long queries don't block the explorer. Drop it to roll back and close.
#[async_trait]
pub trait QuerySession: Send {
    fn read_only(&self) -> bool;

    /// False in manual-commit mode, where changes stay pending until commit or rollback.
    fn auto_commit(&self) -> bool;

    /// Statements run since the last commit/rollback in manual mode; > 0 means there may be uncommitted work.
    fn uncommitted_statements(&self) -> usize;

    /// Executes one statement. Drop the future to cancel it on the server.
    async fn execute(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult>;

    /// Switching back to auto-commit commits pending work, as JDBC specifies.
    async fn set_auto_commit(&mut self, enabled: bool) -> Result<()>;

    async fn commit(&mut self) -> Result<()>;

    async fn rollback(&mut self) -> Result<()>;

    async fn is_valid(&mut self) -> bool;
}

/// Rows per insert batch: big enough to amortize round trips, small enough to name a failing row.
pub const INSERT_BATCH_SIZE: usize = 500;

/// Bound values an insert batch may hold, below SQLite's 32766.
const MAX_BATCH_PARAMS: usize = 30_000;

/// A [`RelationalConnection`] over an engine's [`ConnSource`]: everything `Database.kt` does on JDBC.
pub struct SqlDatabase {
    config: ConnectionConfig,
    dialect: SqlDialect,
    source: Arc<dyn ConnSource>,
    /// Kept alive as long as the connection; dropping it closes the tunnel.
    _tunnel: Option<tg_core::ssh::SshTunnel>,
}

impl SqlDatabase {
    pub fn new(
        config: ConnectionConfig,
        source: Arc<dyn ConnSource>,
        tunnel: Option<tg_core::ssh::SshTunnel>,
    ) -> Self {
        let dialect = config.db_type.dialect().unwrap_or(SqlDialect::Generic);
        Self {
            config,
            dialect,
            source,
            _tunnel: tunnel,
        }
    }

    async fn pooled(&self) -> Result<Scoped> {
        Ok(Scoped::new(self.source.pooled().await?))
    }

    fn require_writable(&self, verb: &str) -> Result<()> {
        if self.config.read_only {
            Err(DriverError::read_only(verb))
        } else {
            Ok(())
        }
    }
}

/// A connection used for a multi-step change. If it's dropped before [`Scoped::done`] (the caller was cancelled or a
/// step failed half-way), the engine closes it instead of pooling it, so no half-open transaction or changed setting
/// leaks to the next user.
struct Scoped {
    conn: Box<dyn SqlConn>,
    clean: bool,
}

impl Scoped {
    fn new(conn: Box<dyn SqlConn>) -> Self {
        Self { conn, clean: false }
    }

    fn done(mut self) {
        self.clean = true;
    }
}

impl Drop for Scoped {
    fn drop(&mut self) {
        if !self.clean {
            self.conn.discard();
        }
    }
}

fn filter_params(filter: Option<&tg_sql::filter::SqlFilter>) -> Vec<Param<'_>> {
    filter
        .map(|f| f.parameters.iter().map(Param::Filter).collect())
        .unwrap_or_default()
}

#[async_trait]
impl RelationalConnection for SqlDatabase {
    fn config(&self) -> &ConnectionConfig {
        &self.config
    }

    fn dialect(&self) -> SqlDialect {
        self.dialect
    }

    async fn namespaces(&self) -> Result<Vec<Namespace>> {
        let mut c = self.pooled().await?;
        let out = c.conn.namespaces().await?;
        c.done();
        Ok(out)
    }

    async fn tables(&self, namespace: &Namespace) -> Result<Vec<TableInfo>> {
        let mut c = self.pooled().await?;
        let mut out = c.conn.tables(namespace).await?;
        c.done();
        out.sort_by_key(|t| t.table.name.to_lowercase());
        Ok(out)
    }

    async fn table_details(&self, table: &TableRef) -> Result<TableDetails> {
        let mut c = self.pooled().await?;
        let out = c.conn.details(table, true).await?;
        c.done();
        Ok(out)
    }

    async fn table_columns(&self, table: &TableRef) -> Result<TableDetails> {
        let mut c = self.pooled().await?;
        let out = c.conn.details(table, false).await?;
        c.done();
        Ok(out)
    }

    async fn table_structure(&self, table: &TableRef) -> Result<TableStructure> {
        let mut c = self.pooled().await?;
        let details = c.conn.details(table, true).await?;
        let out = c.conn.structure(details).await?;
        c.done();
        Ok(out)
    }

    async fn columns_of(&self, namespace: &Namespace) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
        let mut c = self.pooled().await?;
        let out = c.conn.columns_of(namespace).await?;
        c.done();
        Ok(out)
    }

    async fn relations(&self, table: &TableRef) -> Result<Vec<TableRelation>> {
        let mut c = self.pooled().await?;
        let out = c.conn.relations(table).await?;
        c.done();
        Ok(out)
    }

    async fn sample_values(
        &self,
        table: &TableRef,
        column: &str,
        prefix: &str,
        limit: usize,
    ) -> Result<Vec<String>> {
        let sql = self
            .dialect
            .sample_values(table, column, limit as i32, 10_000);
        let pattern = self.dialect.sample_pattern(prefix);
        let mut c = self.pooled().await?;
        let rows = with_timeout(
            Some(Duration::from_secs(3)),
            c.conn.query(&sql, &[Param::Text(&pattern)], None),
        )
        .await?;
        c.done();
        Ok(rows
            .rows
            .into_iter()
            .filter_map(|r| r.into_iter().next().flatten())
            .collect())
    }

    async fn fetch_page(&self, r: &PageRequest<'_>) -> Result<Rows> {
        // One extra row tells whether another page exists; it comes back as `truncated`.
        let sql = self.dialect.select_page(
            r.table,
            r.filter.map(|f| f.sql.as_str()),
            r.order_by,
            (r.limit + 1) as i32,
            r.offset as i64,
            r.projection,
        );
        let params = filter_params(r.filter);
        let start = Instant::now();
        let mut c = self.pooled().await?;
        let mut rows = with_timeout(r.timeout, c.conn.query(&sql, &params, Some(r.limit))).await?;
        c.done();
        rows.elapsed_ms = start.elapsed().as_millis() as u64;
        Ok(rows)
    }

    async fn export_table(
        &self,
        q: &TableQuery<'_>,
        sink: &mut dyn crate::RowSink,
        progress: Progress<'_>,
    ) -> Result<u64> {
        let sql = self.dialect.select_all(
            q.table,
            q.filter.map(|f| f.sql.as_str()),
            q.order_by,
            q.projection,
        );
        let params = filter_params(q.filter);
        let mut c = self.pooled().await?;
        let n = c.conn.stream(&sql, &params, sink, progress).await?;
        c.done();
        Ok(n)
    }

    async fn count_rows(&self, q: &TableQuery<'_>, timeout: Option<Duration>) -> Result<u64> {
        let sql = self
            .dialect
            .count_rows(q.table, q.filter.map(|f| f.sql.as_str()), q.projection);
        let params = filter_params(q.filter);
        let mut c = self.pooled().await?;
        let rows = with_timeout(timeout, c.conn.query(&sql, &params, Some(1))).await?;
        c.done();
        let text = rows
            .rows
            .first()
            .and_then(|r| r.first().cloned().flatten())
            .unwrap_or_default();
        text.trim().parse().map_err(|_| {
            DriverError::Query(crate::QueryError::new(format!("Not a row count: {text}")))
        })
    }

    async fn apply_changes(&self, statements: &[ChangeStatement]) -> Result<u64> {
        self.require_writable("editing data")?;
        let mut c = self.pooled().await?;
        c.conn.begin().await?;
        let mut total = 0;
        for change in statements {
            let params: Vec<Param> = change.parameters.iter().map(Param::Value).collect();
            let affected = match c.conn.execute(&change.sql, &params).await {
                Ok(n) => n,
                Err(e) => {
                    c.conn.rollback().await?;
                    c.done();
                    return Err(e);
                }
            };
            if let Some(expected) = change.expected_rows.filter(|&e| e != affected) {
                c.conn.rollback().await?;
                c.done();
                return Err(DriverError::Query(crate::QueryError::new(format!(
                    "Expected {expected} row(s) but {affected} would be affected by: {}. Nothing was saved.",
                    change.preview(self.dialect)
                ))));
            }
            total += affected;
        }
        c.conn.commit().await?;
        c.done();
        Ok(total)
    }

    async fn schema_snapshot(
        &self,
        namespace: &Namespace,
        progress: &(dyn Fn(usize, usize) + Send + Sync),
    ) -> Result<SchemaSnapshot> {
        let (views, tables): (Vec<TableInfo>, Vec<TableInfo>) = self
            .tables(namespace)
            .await?
            .into_iter()
            .partition(|t| t.is_view);
        let total = tables.len();
        progress(0, total);
        let done = AtomicUsize::new(0);
        // A few tables at a time: each is several catalog round trips, but the pool is shared with open table tabs.
        let parallel = self.source.pool_size().saturating_sub(1).max(1);
        let done = &done;
        let details: Vec<Result<TableDetails>> = stream::iter(tables.into_iter().map(|t| t.table))
            .map(|table| async move {
                let d = self.table_details(&table).await;
                progress(done.fetch_add(1, Ordering::SeqCst) + 1, total);
                d
            })
            .buffered(parallel)
            .collect()
            .await;
        Ok(SchemaSnapshot {
            namespace: namespace.clone(),
            tables: details.into_iter().collect::<Result<_>>()?,
            views: views.into_iter().map(|v| v.table.name).collect(),
        })
    }

    async fn execute_script(
        &self,
        script: &DdlScript,
        progress: &(dyn Fn(usize) + Send + Sync),
    ) -> Result<usize> {
        self.require_writable("changing the schema")?;
        let phase = |p: DdlPhase| {
            script
                .statements
                .iter()
                .filter(move |s| s.phase == p)
                .map(|s| s.sql.as_str())
        };
        let prologue: Vec<&str> = phase(DdlPhase::Prologue).collect();
        let epilogue: Vec<&str> = phase(DdlPhase::Epilogue).collect();
        let body: Vec<&str> = script
            .statements
            .iter()
            .filter(|s| s.phase != DdlPhase::Prologue && s.phase != DdlPhase::Epilogue)
            .map(|s| s.sql.as_str())
            .collect();
        let mut c = self.pooled().await?;
        // SQLite rebuilds turn foreign keys off; put back whatever the connection had, not blindly ON.
        let sqlite_foreign_keys = if self.dialect == SqlDialect::SQLite && !prologue.is_empty() {
            let rows = c.conn.query("PRAGMA foreign_keys", &[], Some(1)).await?;
            rows.rows.first().and_then(|r| r.first().cloned().flatten())
        } else {
            None
        };
        let mut done = 0;
        for sql in &prologue {
            c.conn.execute(sql, &[]).await?;
            done += 1;
            progress(done);
        }
        if script.transactional {
            c.conn.begin().await?;
        }
        for (index, sql) in body.iter().enumerate() {
            if let Err(e) = c.conn.execute(sql, &[]).await {
                let rolled_back = script.transactional && c.conn.rollback().await.is_ok();
                let outcome = if rolled_back || index == 0 {
                    "Nothing was changed.".to_owned()
                } else {
                    format!(
                        "The {index} statement(s) before it were applied and stay applied: {} can't roll back DDL.",
                        self.dialect.name()
                    )
                };
                if let Some(fk) = &sqlite_foreign_keys {
                    let _ = c
                        .conn
                        .execute(&format!("PRAGMA foreign_keys = {fk}"), &[])
                        .await;
                }
                c.done();
                return Err(DriverError::Script {
                    message: format!(
                        "Statement {} of {} failed: {}\n{outcome}",
                        index + 1,
                        body.len(),
                        e
                    ),
                    statement: (*sql).to_owned(),
                    completed: index,
                    rolled_back,
                });
            }
            done += 1;
            progress(done);
        }
        if let Some(fk) = &sqlite_foreign_keys {
            let check = c
                .conn
                .query("PRAGMA foreign_key_check", &[], Some(5))
                .await?;
            let mut tables: Vec<String> = check
                .rows
                .into_iter()
                .filter_map(|r| r.into_iter().next().flatten())
                .collect();
            if !tables.is_empty() {
                tables.dedup();
                let _ = c.conn.rollback().await;
                let _ = c
                    .conn
                    .execute(&format!("PRAGMA foreign_keys = {fk}"), &[])
                    .await;
                c.done();
                return Err(DriverError::Script {
                    message: format!(
                        "The change leaves rows whose foreign keys point nowhere (in {}). Nothing was changed.",
                        tables.join(", ")
                    ),
                    statement: "PRAGMA foreign_key_check".into(),
                    completed: body.len(),
                    rolled_back: true,
                });
            }
        }
        if script.transactional {
            c.conn.commit().await?;
        }
        if let Some(fk) = &sqlite_foreign_keys {
            c.conn
                .execute(&format!("PRAGMA foreign_keys = {fk}"), &[])
                .await?;
        } else {
            for sql in &epilogue {
                c.conn.execute(sql, &[]).await?;
                done += 1;
                progress(done);
            }
        }
        c.done();
        Ok(done)
    }

    async fn insert_rows(
        &self,
        table: &TableRef,
        columns: &[ColumnInfo],
        rows: &mut (dyn Iterator<Item = Vec<Cell>> + Send),
        describe: &(dyn Fn(u64) -> String + Send + Sync),
        progress: Progress<'_>,
    ) -> Result<u64> {
        self.require_writable("inserting rows")?;
        if columns.is_empty() {
            return Err(DriverError::Unsupported("No columns to insert into".into()));
        }
        let names: Vec<String> = columns
            .iter()
            .map(|c| self.dialect.quote_identifier(&c.name))
            .collect();
        let head = format!(
            "INSERT INTO {} ({}) VALUES ",
            self.dialect.qualified_name(table),
            names.join(", ")
        );
        let tuple = format!("({})", vec!["?"; columns.len()].join(", "));
        let batch_rows = INSERT_BATCH_SIZE.min((MAX_BATCH_PARAMS / columns.len()).max(1));
        let bound = |row: &[Cell]| -> Vec<BoundValue> {
            columns
                .iter()
                .enumerate()
                .map(|(i, c)| {
                    BoundValue::new(row.get(i).cloned().flatten().as_deref(), c.jdbc_type, false)
                })
                .collect()
        };
        let mut c = self.pooled().await?;
        c.conn.begin().await?;
        let mut count: u64 = 0;
        let mut pending: Vec<Vec<BoundValue>> = Vec::with_capacity(batch_rows);
        loop {
            while pending.len() < batch_rows {
                match rows.next() {
                    Some(row) => pending.push(bound(&row)),
                    None => break,
                }
            }
            if pending.is_empty() {
                break;
            }
            let sql = format!("{head}{}", vec![tuple.as_str(); pending.len()].join(", "));
            let params: Vec<Param> = pending.iter().flatten().map(Param::Value).collect();
            c.conn.execute("SAVEPOINT tg_insert", &[]).await?;
            if let Err(batch_error) = c.conn.execute(&sql, &params).await {
                // Find the row that fails on its own, to name it.
                let _ = c.conn.execute("ROLLBACK TO SAVEPOINT tg_insert", &[]).await;
                let single = format!("{head}{tuple}");
                let mut culprit = None;
                for (i, row) in pending.iter().enumerate() {
                    let params: Vec<Param> = row.iter().map(Param::Value).collect();
                    if let Err(e) = c.conn.execute(&single, &params).await {
                        culprit = Some((count + i as u64, e));
                        break;
                    }
                }
                let _ = c.conn.rollback().await;
                c.done();
                let (place, reason) = match culprit {
                    Some((index, e)) => (describe(index), e.to_string()),
                    None => (
                        format!("rows {}–{}", count + 1, count + pending.len() as u64),
                        batch_error.to_string(),
                    ),
                };
                return Err(DriverError::Insert {
                    message: format!("Couldn't insert {place}: {reason}. Nothing was imported."),
                    row: place,
                });
            }
            c.conn.execute("RELEASE SAVEPOINT tg_insert", &[]).await?;
            count += pending.len() as u64;
            pending.clear();
            progress(count);
        }
        c.conn.commit().await?;
        c.done();
        Ok(count)
    }

    async fn open_session(&self) -> Result<Box<dyn QuerySession>> {
        let conn = self.source.dedicated().await?;
        Ok(Box::new(GuardedSession::new(
            conn,
            self.dialect,
            self.config.read_only,
        )))
    }

    async fn server_major_version(&self) -> Result<Option<i32>> {
        let mut c = self.pooled().await?;
        let out = c.conn.server_major_version().await?;
        c.done();
        Ok(out)
    }

    async fn backup_into(&self, target: &Path) -> Result<()> {
        self.source.backup_into(target).await.map_err(|e| match e {
            DriverError::Unsupported(_) => DriverError::Unsupported(format!(
                "{} can't back itself up",
                self.config.db_type.display_name
            )),
            e => e,
        })
    }

    async fn open_activity_monitor(&self) -> Result<Box<dyn ActivityMonitor>> {
        self.source.activity_monitor().await.map_err(|e| match e {
            DriverError::Unsupported(_) => DriverError::Unsupported(format!(
                "{} has no activity monitor",
                self.config.db_type.display_name
            )),
            e => e,
        })
    }
}

/// The session every SQL engine gets: read-only refusal, manual-commit bookkeeping and the uncommitted counter are
/// here, so an engine only provides the connection.
pub struct GuardedSession {
    conn: Box<dyn SqlConn>,
    classifier: SqlClassifier,
    read_only: bool,
    auto_commit: bool,
    /// A transaction this session opened for manual-commit mode is running.
    in_transaction: bool,
    uncommitted: usize,
}

impl GuardedSession {
    pub fn new(conn: Box<dyn SqlConn>, dialect: SqlDialect, read_only: bool) -> Self {
        Self {
            conn,
            classifier: SqlClassifier::new(dialect.syntax()),
            read_only,
            auto_commit: true,
            in_transaction: false,
            uncommitted: 0,
        }
    }
}

#[async_trait]
impl QuerySession for GuardedSession {
    fn read_only(&self) -> bool {
        self.read_only
    }

    fn auto_commit(&self) -> bool {
        self.auto_commit
    }

    fn uncommitted_statements(&self) -> usize {
        self.uncommitted
    }

    async fn execute(&mut self, sql: &str, options: ExecOptions) -> Result<QueryResult> {
        let info = self.classifier.classify(sql);
        if self.read_only && info.changes_data() {
            return Err(DriverError::read_only(&info.verb));
        }
        let ends = info.kind == StatementKind::Transaction
            && matches!(info.verb.as_str(), "COMMIT" | "END" | "ROLLBACK");
        // JDBC drivers open the next transaction implicitly in manual-commit mode; so does this.
        if !self.auto_commit && !self.in_transaction && !ends {
            self.conn.begin().await?;
            self.in_transaction = true;
        }
        let result = with_timeout(options.timeout, self.conn.run(sql, options)).await?;
        if ends {
            self.uncommitted = 0;
            self.in_transaction = false;
        } else if !self.auto_commit && info.kind != StatementKind::Read {
            self.uncommitted += 1;
        }
        Ok(result)
    }

    async fn set_auto_commit(&mut self, enabled: bool) -> Result<()> {
        if enabled && self.in_transaction {
            self.conn.commit().await?;
        }
        self.in_transaction = false;
        self.auto_commit = enabled;
        self.uncommitted = 0;
        Ok(())
    }

    async fn commit(&mut self) -> Result<()> {
        if self.in_transaction {
            self.conn.commit().await?;
            self.in_transaction = false;
        }
        self.uncommitted = 0;
        Ok(())
    }

    async fn rollback(&mut self) -> Result<()> {
        if self.in_transaction {
            self.conn.rollback().await?;
            self.in_transaction = false;
        }
        self.uncommitted = 0;
        Ok(())
    }

    async fn is_valid(&mut self) -> bool {
        self.conn.is_valid().await
    }
}

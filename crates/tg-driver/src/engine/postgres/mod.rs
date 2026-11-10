//! PostgreSQL (and the engines that speak its protocol: CockroachDB, TimescaleDB, Neon, Supabase) through
//! tokio-postgres, a deadpool pool of four for explorer and tables, dedicated connections for consoles, TLS by rustls.

mod activity;
mod catalog;
mod conn;
mod literal;
mod structure;
mod tls;
mod types;

use crate::engine::{Endpoint, Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::{Handle, PgConn};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tg_sql::model::DatabaseType;
pub use tls::Tls;
use tls::TlsChoice;
use tokio_postgres::config::SslMode;

const POOL_SIZE: usize = 4;

/// A server error as pgjdbc's `PSQLException` worded it (`ERROR: …` plus Detail, Hint, Position and Where lines),
/// with its SQLSTATE and 0-based position.
pub(crate) fn error(e: tokio_postgres::Error) -> DriverError {
    let Some(db) = e.as_db_error() else {
        let text = e.to_string();
        return if text.contains("canceling statement") {
            DriverError::Cancelled
        } else {
            DriverError::Query(QueryError::new(text))
        };
    };
    if db.code() == &tokio_postgres::error::SqlState::QUERY_CANCELED {
        return DriverError::Cancelled;
    }
    let position = match db.position() {
        Some(tokio_postgres::error::ErrorPosition::Original(p)) => Some(*p as usize),
        _ => None,
    };
    let mut message = format!("{}: {}", db.severity(), db.message());
    if let Some(d) = db.detail() {
        message += &format!("\n  Detail: {d}");
    }
    if let Some(h) = db.hint() {
        message += &format!("\n  Hint: {h}");
    }
    if let Some(p) = position {
        message += &format!("\n  Position: {p}");
    }
    if let Some(w) = db.where_() {
        message += &format!("\n  Where: {w}");
    }
    DriverError::Query(QueryError {
        message,
        sql_state: Some(db.code().code().to_owned()),
        vendor_code: None,
        position: position.and_then(|p| p.checked_sub(1)),
    })
}

/// The tokio-postgres configuration for a profile reached at `endpoint` (`JdbcUrls.build` + `properties`).
fn pg_config(
    config: &ConnectionConfig,
    endpoint: &Endpoint,
    password: Option<&str>,
) -> Result<(tokio_postgres::Config, Tls)> {
    let mut properties = config.properties.clone();
    let mut database = config.database.clone();
    let mut user = config.user.clone();
    if let Some(url) = &config.url {
        let parsed =
            tg_core::connection::string::parse(url).map_err(|e| DriverError::Connect(e.0))?;
        database = parsed.database.unwrap_or(database);
        user = parsed.user.unwrap_or(user);
        for (k, v) in parsed.properties {
            properties.entry(k).or_insert(v);
        }
    }
    let mut pg = tokio_postgres::Config::new();
    pg.host(&endpoint.host)
        .port(endpoint.port)
        .connect_timeout(Duration::from_secs(10))
        .keepalives_idle(Duration::from_secs(120));
    if !database.trim().is_empty() {
        pg.dbname(database.trim());
    }
    let user = properties.get("user").cloned().unwrap_or(user);
    if !user.trim().is_empty() {
        pg.user(user.trim());
    }
    if let Some(p) = password
        .filter(|p| !p.is_empty())
        .map(str::to_owned)
        .or_else(|| properties.get("password").cloned())
    {
        pg.password(p);
    }
    // Names TableGrip's sessions in pg_stat_activity, for DBAs and for the activity monitor.
    pg.application_name(
        properties
            .get("ApplicationName")
            .map_or("TableGrip", String::as_str),
    );
    if let Some(options) = properties.get("options") {
        pg.options(options);
    }
    // Hikari's isReadOnly: every transaction of the pool is read-only. Consoles check statements first.
    if config.read_only {
        let options = properties
            .get("options")
            .map_or(String::new(), |o| format!("{o} "));
        pg.options(format!("{options}-c default_transaction_read_only=on"));
    }
    let mode = properties
        .get("sslmode")
        .map_or("prefer", String::as_str)
        .to_ascii_lowercase();
    let (ssl, tls) = match mode.as_str() {
        "disable" => (SslMode::Disable, Tls::none()),
        "allow" | "prefer" => (SslMode::Prefer, Tls::unverified()),
        "require" => (SslMode::Require, Tls::unverified()),
        "verify-ca" | "verify-full" => (SslMode::Require, Tls::verified()?),
        other => {
            return Err(DriverError::Connect(format!(
                "Unknown sslmode \"{other}\"."
            )));
        }
    };
    pg.ssl_mode(ssl);
    Ok((pg, tls))
}

async fn endpoint(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Endpoint> {
    let (host, port) = host_and_port(config)?;
    open_tunnel(config, &host, port, secrets, paths).await
}

/// A connection of its own, with the task that drives it.
async fn open(pg: &tokio_postgres::Config, tls: &Tls) -> Result<tokio_postgres::Client> {
    let (client, connection) = pg.connect(tls.clone()).await.map_err(error)?;
    tokio::spawn(async move {
        let _ = connection.await;
    });
    Ok(client)
}

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn RelationalConnection>> {
    let endpoint = endpoint(config, secrets, paths).await?;
    let (pg, tls) = pg_config(config, &endpoint, secrets.password.as_deref())?;
    let manager = deadpool_postgres::Manager::from_config(
        pg.clone(),
        tls.clone(),
        deadpool_postgres::ManagerConfig {
            recycling_method: deadpool_postgres::RecyclingMethod::Fast,
        },
    );
    let pool = deadpool_postgres::Pool::builder(manager)
        .max_size(POOL_SIZE)
        .runtime(deadpool_postgres::Runtime::Tokio1)
        .wait_timeout(Some(Duration::from_secs(30)))
        .build()
        .map_err(|e| DriverError::Connect(e.to_string()))?;
    // Validate now, so bad credentials fail here rather than in the first tab.
    let first = pool.get().await.map_err(pool_error)?;
    drop(first);
    let source = PgSource {
        pool,
        pg,
        tls,
        read_only: config.read_only,
        db_type: config.db_type,
    };
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(source),
        endpoint.tunnel,
    )))
}

fn pool_error(e: deadpool_postgres::PoolError) -> DriverError {
    match e {
        deadpool_postgres::PoolError::Backend(e) => error(e),
        other => DriverError::Connect(other.to_string()),
    }
}

/// "Test Connection": one unpooled connection; the engine the server turned out to be when that isn't the profile's
/// type (`ServerIdentity.suggest`).
pub async fn test(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Option<DatabaseType>> {
    let endpoint = endpoint(config, secrets, paths).await?;
    let (pg, tls) = pg_config(config, &endpoint, secrets.password.as_deref())?;
    let conn = PgConn::new(Handle::Owned(open(&pg, &tls).await?), tls);
    let identifiable = [
        DatabaseType::POSTGRESQL,
        DatabaseType::COCKROACHDB,
        DatabaseType::TIMESCALEDB,
    ];
    if !identifiable.contains(&config.db_type) {
        return Ok(None);
    }
    Ok(identify(&conn).await.ok().filter(|t| *t != config.db_type))
}

/// `ServerIdentity.identify` for the PostgreSQL family.
async fn identify(conn: &PgConn) -> Result<DatabaseType> {
    let version = conn
        .text_rows("SELECT version()")
        .await?
        .first()
        .and_then(|r| r[0].clone())
        .unwrap_or_default();
    if version.contains("CockroachDB") {
        return Ok(DatabaseType::COCKROACHDB);
    }
    let timescale = !conn
        .text_rows("SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'")
        .await?
        .is_empty();
    Ok(if timescale {
        DatabaseType::TIMESCALEDB
    } else {
        DatabaseType::POSTGRESQL
    })
}

struct PgSource {
    pool: deadpool_postgres::Pool,
    pg: tokio_postgres::Config,
    tls: Tls,
    read_only: bool,
    db_type: DatabaseType,
}

#[async_trait]
impl ConnSource for PgSource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        let object = self.pool.get().await.map_err(pool_error)?;
        Ok(Box::new(PgConn::new(
            Handle::Pooled(Some(object)),
            self.tls.clone(),
        )))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(PgConn::new(
            Handle::Owned(open(&self.pg, &self.tls).await?),
            self.tls.clone(),
        )))
    }

    fn pool_size(&self) -> usize {
        POOL_SIZE
    }

    async fn activity_monitor(&self) -> Result<Box<dyn crate::activity::ActivityMonitor>> {
        // CockroachDB speaks the protocol but has neither pg_stat_activity's columns nor pg_blocking_pids.
        if self.db_type == DatabaseType::COCKROACHDB {
            return Err(DriverError::Unsupported("no activity monitor".into()));
        }
        let conn = self.dedicated().await?;
        Ok(Box::new(crate::activity::SqlActivityMonitor::new(
            conn,
            Box::new(activity::PostgresActivity),
            self.read_only,
        )))
    }
}

//! MySQL, MariaDB and TiDB (and PlanetScale) through mysql_async, read over the text protocol as MariaDB
//! Connector/J did (the family's default driver in Kotlin): parameters are inlined as literals, which is what its
//! client-side prepared statements sent. A small pool of four for explorer and tables, dedicated connections for
//! consoles, `KILL QUERY` from a side connection when a statement's future is dropped, TLS by rustls.

mod activity;
mod catalog;
mod conn;
mod types;

use crate::engine::{Endpoint, Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::{MyConn, Physical};
use mysql_async::{Conn, Opts, OptsBuilder, SslOpts};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tg_sql::model::DatabaseType;
use tokio::sync::Semaphore;

const POOL_SIZE: usize = 4;

/// A server error as MariaDB Connector/J worded it (`(conn=12) message`, minus the connection id), with its SQLSTATE
/// and error code.
pub(crate) fn error(e: mysql_async::Error) -> DriverError {
    match e {
        mysql_async::Error::Server(s) => {
            // 1317: the statement was interrupted (KILL QUERY); 3024: max_execution_time ran out.
            if s.code == 1317 {
                return DriverError::Cancelled;
            }
            DriverError::Query(QueryError {
                message: s.message,
                sql_state: Some(s.state),
                vendor_code: Some(i32::from(s.code)),
                position: None,
            })
        }
        other => DriverError::Query(QueryError::new(other.to_string())),
    }
}

/// The mysql_async options for a profile reached at `endpoint` (`JdbcUrls.build` + `properties`).
fn opts(config: &ConnectionConfig, endpoint: &Endpoint, password: Option<&str>) -> Result<Opts> {
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
    // DatabaseType.defaultProperties: PlanetScale only takes verified TLS.
    if config.db_type == DatabaseType::PLANETSCALE {
        properties
            .entry("sslMode".into())
            .or_insert_with(|| "verify-full".into());
    }
    let user = properties.get("user").cloned().unwrap_or(user);
    let password = password
        .filter(|p| !p.is_empty())
        .map(str::to_owned)
        .or_else(|| properties.get("password").cloned());
    // The MariaDB vocabulary, with MySQL's names accepted too (`JdbcUrls.mysqlToMariaSslMode`).
    let ssl_mode = properties
        .get("sslMode")
        .or_else(|| properties.get("sslmode"))
        .map(|m| match m.to_ascii_uppercase().as_str() {
            "DISABLED" => "disable".to_owned(),
            "PREFERRED" | "REQUIRED" => "trust".to_owned(),
            "VERIFY_CA" => "verify-ca".to_owned(),
            "VERIFY_IDENTITY" => "verify-full".to_owned(),
            _ => m.to_ascii_lowercase(),
        })
        .unwrap_or_else(|| {
            if properties.get("useSSL").is_some_and(|v| v == "true") {
                "trust".into()
            } else {
                "disable".into()
            }
        });
    let ssl = match ssl_mode.as_str() {
        "disable" => None,
        "trust" | "allow" | "prefer" | "require" => Some(
            SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        ),
        "verify-ca" => Some(SslOpts::default().with_danger_skip_domain_validation(true)),
        "verify-full" => Some(SslOpts::default()),
        other => {
            return Err(DriverError::Connect(format!(
                "Unknown sslMode \"{other}\"."
            )));
        }
    };
    let mut attributes = HashMap::new();
    attributes.insert("program_name".to_owned(), "TableGrip".to_owned());
    let init = vec![
        // What MariaDB Connector/J sets on every connection.
        "SET sql_mode = CONCAT(@@sql_mode, ',STRICT_TRANS_TABLES'), NAMES utf8mb4".to_owned(),
    ];
    let builder = OptsBuilder::default()
        .ip_or_hostname(endpoint.host.clone())
        .tcp_port(endpoint.port)
        .user(Some(user.trim().to_owned()).filter(|u| !u.is_empty()))
        .pass(password)
        .db_name(Some(database.trim().to_owned()).filter(|d| !d.is_empty()))
        .prefer_socket(false)
        .stmt_cache_size(0)
        .ssl_opts(ssl)
        .connect_attributes(attributes)
        .init(init);
    Ok(builder.into())
}

async fn endpoint(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Endpoint> {
    let (host, port) = host_and_port(config)?;
    open_tunnel(config, &host, port, secrets, paths).await
}

/// A new connection, failing after 10 s like Connector/J's `connectTimeout`.
async fn open(opts: &Opts) -> Result<Conn> {
    tokio::time::timeout(Duration::from_secs(10), Conn::new(opts.clone()))
        .await
        .map_err(|_| DriverError::Connect("Timed out connecting to the server.".into()))?
        .map_err(|e| match error(e) {
            DriverError::Query(q) => DriverError::Connect(q.message),
            other => other,
        })
}

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn RelationalConnection>> {
    let endpoint = endpoint(config, secrets, paths).await?;
    let opts = opts(config, &endpoint, secrets.password.as_deref())?;
    // Validate now, so bad credentials fail here rather than in the first tab.
    let first = Physical::new(open(&opts).await?, config.read_only).await?;
    let pool = Arc::new(Pool {
        opts: opts.clone(),
        read_only: config.read_only,
        idle: Mutex::new(vec![first]),
        permits: Arc::new(Semaphore::new(POOL_SIZE)),
    });
    let source = MySource {
        pool,
        read_only: config.read_only,
        db_type: config.db_type,
    };
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(source),
        endpoint.tunnel,
    )))
}

/// "Test Connection": one unpooled connection; the engine the server turned out to be when that isn't the profile's
/// type (`ServerIdentity.suggest`).
pub async fn test(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Option<DatabaseType>> {
    let endpoint = endpoint(config, secrets, paths).await?;
    let opts = opts(config, &endpoint, secrets.password.as_deref())?;
    let mut conn = MyConn::owned(Physical::new(open(&opts).await?, false).await?, opts, false);
    let identifiable = [
        DatabaseType::MYSQL,
        DatabaseType::MARIADB,
        DatabaseType::TIDB,
    ];
    if !identifiable.contains(&config.db_type) {
        return Ok(None);
    }
    Ok(identify(&mut conn)
        .await
        .ok()
        .filter(|t| *t != config.db_type))
}

/// `ServerIdentity.identify` for the MySQL family: the version string names TiDB and MariaDB.
async fn identify(conn: &mut MyConn) -> Result<DatabaseType> {
    let version = conn
        .text_rows("SELECT version()")
        .await?
        .first()
        .and_then(|r| r[0].clone())
        .unwrap_or_default();
    Ok(if version.contains("TiDB") {
        DatabaseType::TIDB
    } else if version.contains("MariaDB") {
        DatabaseType::MARIADB
    } else {
        DatabaseType::MYSQL
    })
}

/// Four connections shared by explorer and table viewers. A connection is borrowed with a permit and comes back when
/// its [`MyConn`] drops cleanly; one dropped mid-statement is closed and a new one opened on the next borrow.
pub(crate) struct Pool {
    pub(crate) opts: Opts,
    pub(crate) read_only: bool,
    idle: Mutex<Vec<Physical>>,
    permits: Arc<Semaphore>,
}

impl Pool {
    pub(crate) fn give_back(&self, conn: Physical) {
        self.idle.lock().expect("pool lock").push(conn);
    }
}

struct MySource {
    pool: Arc<Pool>,
    read_only: bool,
    db_type: DatabaseType,
}

#[async_trait]
impl ConnSource for MySource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        let permit = tokio::time::timeout(
            Duration::from_secs(30),
            self.pool.permits.clone().acquire_owned(),
        )
        .await
        .map_err(|_| DriverError::Connect("Timed out waiting for a free connection.".into()))?
        .map_err(|_| DriverError::Connect("The connection is closed.".into()))?;
        let idle = self.pool.idle.lock().expect("pool lock").pop();
        let conn = match idle {
            Some(mut c) => {
                // Idle connections may have been closed by the server (wait_timeout) or a firewall.
                if c.ping().await {
                    c
                } else {
                    Physical::new(open(&self.pool.opts).await?, self.read_only).await?
                }
            }
            None => Physical::new(open(&self.pool.opts).await?, self.read_only).await?,
        };
        Ok(Box::new(MyConn::pooled(conn, self.pool.clone(), permit)))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(MyConn::owned(
            Physical::new(open(&self.pool.opts).await?, self.read_only).await?,
            self.pool.opts.clone(),
            self.read_only,
        )))
    }

    fn pool_size(&self) -> usize {
        POOL_SIZE
    }

    async fn activity_monitor(&self) -> Result<Box<dyn crate::activity::ActivityMonitor>> {
        // TiDB and PlanetScale (Vitess) speak the protocol but list and kill sessions their own way.
        if self.db_type != DatabaseType::MYSQL && self.db_type != DatabaseType::MARIADB {
            return Err(DriverError::Unsupported("no activity monitor".into()));
        }
        let conn = self.dedicated().await?;
        Ok(Box::new(crate::activity::SqlActivityMonitor::new(
            conn,
            Box::new(activity::MySqlActivity),
            self.read_only,
        )))
    }
}

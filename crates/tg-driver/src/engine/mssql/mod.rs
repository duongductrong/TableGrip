//! SQL Server and Azure SQL through tiberius (TDS 7.4) over rustls. Values come typed and are written out as
//! mssql-jdbc's `getString` did; parameters are inlined as literals (`N'…'`), which also keeps large inserts clear of
//! the 2,100-parameter limit. A pool of four for explorer and tables, dedicated connections for consoles, and a TDS
//! attention when a statement's future is dropped, so the session survives as after JDBC's `Statement.cancel()`.

mod catalog;
mod conn;
mod types;

use crate::engine::{Endpoint, Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::{Client, MsConn};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tg_sql::model::DatabaseType;
use tiberius::{AuthMethod, Config, EncryptionLevel};
use tokio::net::TcpStream;
use tokio::sync::Semaphore;
use tokio_util::compat::TokioAsyncWriteCompatExt;

const POOL_SIZE: usize = 4;

/// A server error as mssql-jdbc's `SQLServerException` reported it: the message, with SQLSTATE and error number.
pub(crate) fn error(e: tiberius::error::Error) -> DriverError {
    match e {
        tiberius::error::Error::Server(t) => DriverError::Query(QueryError {
            message: t.message().to_owned(),
            sql_state: Some(sql_state(t.code()).to_owned()),
            vendor_code: Some(t.code() as i32),
            position: None,
        }),
        other => DriverError::Query(QueryError::new(other.to_string())),
    }
}

/// The SQLSTATE mssql-jdbc gives the common error numbers (`SQLServerException.mapFromXopen`), else `S0001`.
fn sql_state(code: u32) -> &'static str {
    match code {
        2627 | 2601 | 547 | 515 => "23000",
        208 => "S0002",
        207 => "S0022",
        102 | 156 | 170 => "S0001",
        1205 => "40001",
        _ => "S0001",
    }
}

/// The tiberius configuration for a profile reached at `endpoint` (`JdbcUrls.build` + `properties`).
fn tds_config(
    config: &ConnectionConfig,
    endpoint: &Endpoint,
    password: Option<&str>,
) -> Result<Config> {
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
    // DatabaseType.defaultProperties: Azure SQL only takes encrypted connections to its own hosts.
    if config.db_type == DatabaseType::AZURESQL {
        properties
            .entry("encrypt".into())
            .or_insert_with(|| "true".into());
        properties
            .entry("hostNameInCertificate".into())
            .or_insert_with(|| "*.database.windows.net".into());
    }
    let property = |k: &str| {
        properties
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(k))
            .map(|(_, v)| v.as_str())
    };
    crate::engine::use_ring();
    let mut tds = Config::new();
    tds.host(&endpoint.host);
    tds.port(endpoint.port);
    if !database.trim().is_empty() {
        tds.database(database.trim());
    }
    tds.application_name(property("applicationName").unwrap_or("TableGrip"));
    let user = property("user").map(str::to_owned).unwrap_or(user);
    let password = password
        .filter(|p| !p.is_empty())
        .map(str::to_owned)
        .or_else(|| property("password").map(str::to_owned))
        .unwrap_or_default();
    tds.authentication(AuthMethod::sql_server(user.trim(), password));
    // mssql-jdbc 10+ encrypts by default and checks the certificate unless told to trust it.
    match property("encrypt").map(str::to_ascii_lowercase).as_deref() {
        Some("false") | Some("no") | Some("optional") => {
            tds.encryption(EncryptionLevel::Off);
        }
        _ => tds.encryption(EncryptionLevel::Required),
    }
    if property("trustServerCertificate").is_some_and(|v| v.eq_ignore_ascii_case("true")) {
        tds.trust_cert();
    }
    if let Some(host) = property("hostNameInCertificate") {
        tds.hostname_in_certificate(host);
    }
    if config.read_only
        || property("applicationIntent").is_some_and(|v| v.eq_ignore_ascii_case("ReadOnly"))
    {
        tds.readonly(true);
    }
    Ok(tds)
}

async fn endpoint(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Endpoint> {
    let (host, port) = host_and_port(config)?;
    // `host\instance` names a named instance, which the SQL Browser would resolve; a port must be given instead.
    let host = host.split('\\').next().unwrap_or(&host).to_owned();
    open_tunnel(config, &host, port, secrets, paths).await
}

/// A new connection, failing after 15 s like mssql-jdbc's `loginTimeout`.
async fn open(tds: &Config) -> Result<Client> {
    let connect = async {
        let tcp = TcpStream::connect(tds.get_addr())
            .await
            .map_err(|e| DriverError::Connect(e.to_string()))?;
        tcp.set_nodelay(true).ok();
        tiberius::Client::connect(tds.clone(), tcp.compat_write())
            .await
            .map_err(|e| match error(e) {
                DriverError::Query(q) => DriverError::Connect(q.message),
                other => other,
            })
    };
    tokio::time::timeout(Duration::from_secs(15), connect)
        .await
        .map_err(|_| DriverError::Connect("Timed out connecting to the server.".into()))?
}

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn RelationalConnection>> {
    let endpoint = endpoint(config, secrets, paths).await?;
    let tds = tds_config(config, &endpoint, secrets.password.as_deref())?;
    // Validate now, so bad credentials fail here rather than in the first tab.
    let first = open(&tds).await?;
    let pool = Arc::new(Pool {
        tds,
        idle: Mutex::new(vec![first]),
        permits: Arc::new(Semaphore::new(POOL_SIZE)),
    });
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(MsSource { pool }),
        endpoint.tunnel,
    )))
}

/// Four connections shared by explorer and table viewers; see `mysql::Pool`.
pub(crate) struct Pool {
    pub(crate) tds: Config,
    idle: Mutex<Vec<Client>>,
    permits: Arc<Semaphore>,
}

impl Pool {
    pub(crate) fn give_back(&self, client: Client) {
        self.idle.lock().expect("pool lock").push(client);
    }
}

struct MsSource {
    pool: Arc<Pool>,
}

#[async_trait]
impl ConnSource for MsSource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        let permit = tokio::time::timeout(
            Duration::from_secs(30),
            self.pool.permits.clone().acquire_owned(),
        )
        .await
        .map_err(|_| DriverError::Connect("Timed out waiting for a free connection.".into()))?
        .map_err(|_| DriverError::Connect("The connection is closed.".into()))?;
        let idle = self.pool.idle.lock().expect("pool lock").pop();
        let client = match idle {
            Some(mut c) => {
                let alive = tokio::time::timeout(Duration::from_secs(5), async {
                    c.simple_query("SELECT 1").await?.into_results().await
                })
                .await
                .is_ok_and(|r| r.is_ok());
                if alive {
                    c
                } else {
                    open(&self.pool.tds).await?
                }
            }
            None => open(&self.pool.tds).await?,
        };
        Ok(Box::new(MsConn::pooled(client, self.pool.clone(), permit)))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(MsConn::owned(
            open(&self.pool.tds).await?,
            self.pool.tds.clone(),
        )))
    }

    fn pool_size(&self) -> usize {
        POOL_SIZE
    }
}

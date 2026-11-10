//! Opening connections: the engine for a profile's family, its SSH tunnel, and "Test Connection".

#[cfg(feature = "clickhouse")]
pub mod clickhouse;
#[cfg(any(feature = "mysql", feature = "mssql", feature = "clickhouse"))]
pub(crate) mod inline;
#[cfg(feature = "mssql")]
pub mod mssql;
#[cfg(feature = "mysql")]
pub mod mysql;
#[cfg(feature = "postgres")]
pub mod postgres;
#[cfg(feature = "sqlite")]
pub mod sqlite;

use crate::error::{DriverError, Result};
use crate::{DocumentConnection, KeyValueConnection, RelationalConnection};
use std::sync::Arc;
use tg_core::connection::{ConnectionConfig, string as connection_string};
use tg_core::paths::TableGripPaths;
use tg_core::ssh::{SshTunnel, SshTunnelSpec};
#[allow(unused_imports)]
use tg_sql::model::{DatabaseType, EngineFamily, Paradigm};

/// A live connection to one profile; the UI chooses its screens by the variant.
#[derive(Clone)]
pub enum Connection {
    Relational(Arc<dyn RelationalConnection>),
    KeyValue(Arc<dyn KeyValueConnection>),
    Document(Arc<dyn DocumentConnection>),
}

/// What the secret store holds for a profile: its password and its SSH password or key passphrase.
#[derive(Clone, Default)]
pub struct Secrets {
    pub password: Option<String>,
    pub ssh: Option<String>,
}

impl std::fmt::Debug for Secrets {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Secrets(…)")
    }
}

/// What "Test Connection" learned.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TestOutcome {
    /// The engine the server turned out to be, when that isn't the profile's type (`ServerIdentity.suggest`).
    pub suggested_type: Option<DatabaseType>,
    /// The server's description, for key-value and document stores.
    pub server: Option<String>,
}

/// Opens the SSH tunnel (if any) and the connection, and validates it, so bad credentials fail here.
pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Connection> {
    match config.db_type.paradigm {
        Paradigm::Relational => connect_relational(config, secrets, paths)
            .await
            .map(Connection::Relational),
        _ => Err(unsupported(&config.db_type)),
    }
}

/// A single connection, closed again, e.g. for "Test Connection".
pub async fn test(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<TestOutcome> {
    match config.db_type.family {
        #[cfg(feature = "postgres")]
        EngineFamily::PostgreSQL => Ok(TestOutcome {
            suggested_type: postgres::test(config, secrets, paths).await?,
            server: None,
        }),
        #[cfg(feature = "mysql")]
        EngineFamily::MySQL => Ok(TestOutcome {
            suggested_type: mysql::test(config, secrets, paths).await?,
            server: None,
        }),
        _ => match connect(config, secrets, paths).await? {
            Connection::Relational(c) => {
                c.namespaces().await?;
                Ok(TestOutcome {
                    suggested_type: None,
                    server: None,
                })
            }
            Connection::KeyValue(c) => Ok(TestOutcome {
                suggested_type: None,
                server: Some(c.server().await?),
            }),
            Connection::Document(c) => Ok(TestOutcome {
                suggested_type: None,
                server: Some(c.server().await?),
            }),
        },
    }
}

/// The relational engine for the profile's family.
pub async fn connect_relational(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn RelationalConnection>> {
    let _ = (secrets, paths);
    match config.db_type.family {
        #[cfg(feature = "postgres")]
        EngineFamily::PostgreSQL => postgres::connect(config, secrets, paths).await,
        #[cfg(feature = "sqlite")]
        EngineFamily::SQLite => sqlite::connect(config).await,
        #[cfg(feature = "mysql")]
        EngineFamily::MySQL => mysql::connect(config, secrets, paths).await,
        #[cfg(feature = "mssql")]
        EngineFamily::SqlServer => mssql::connect(config, secrets, paths).await,
        #[cfg(feature = "clickhouse")]
        EngineFamily::ClickHouse => clickhouse::connect(config, secrets, paths).await,
        _ => Err(unsupported(&config.db_type)),
    }
}

fn unsupported(db_type: &DatabaseType) -> DriverError {
    DriverError::Unsupported(format!(
        "This build of TableGrip has no driver for {}.",
        db_type.display_name
    ))
}

/// Makes ring rustls' process-wide crypto provider, unless one is set already. mysql_async builds its TLS config
/// with the process default, which rustls can't pick by itself once a second provider is compiled in (tiberius
/// brings aws-lc-rs); tiberius honours the default too, so every engine's TLS runs on ring.
#[cfg(any(feature = "mysql", feature = "mssql", feature = "clickhouse"))]
pub(crate) fn use_ring() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

/// Where to reach the server: the profile's own host and port, or the local end of its SSH tunnel.
pub struct Endpoint {
    pub host: String,
    pub port: u16,
    pub tunnel: Option<SshTunnel>,
}

/// Opens the profile's SSH tunnel to its database host (`Database.tunnelTarget`), or returns the host and port as
/// they are. `host`/`port` come from the profile's fields or from its URL.
pub async fn open_tunnel(
    config: &ConnectionConfig,
    host: &str,
    port: Option<u16>,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Endpoint> {
    let default_port = config.db_type.default_port;
    let Some(ssh) = &config.ssh else {
        let port = port.or(default_port).ok_or_else(|| {
            DriverError::Connect(format!(
                "{} doesn't use a network connection.",
                config.db_type.display_name
            ))
        })?;
        return Ok(Endpoint {
            host: host.to_owned(),
            port,
            tunnel: None,
        });
    };
    let port = port.or(default_port).ok_or_else(|| {
        DriverError::Connect(format!(
            "{} doesn't use a network connection.",
            config.db_type.display_name
        ))
    })?;
    if host.contains(',') {
        return Err(DriverError::Connect(
            if config.url.is_some() {
                "SSH tunnels need a single-host URL."
            } else {
                "SSH tunnels need a single host."
            }
            .into(),
        ));
    }
    let spec = SshTunnelSpec::from_config(ssh, secrets.ssh.clone(), paths.known_hosts());
    let target = host.trim_matches(['[', ']']);
    let tunnel = SshTunnel::open(&spec, target, port).await?;
    Ok(Endpoint {
        host: "127.0.0.1".into(),
        port: tunnel.local_port(),
        tunnel: Some(tunnel),
    })
}

/// The host and port a profile names: its fields, or the single host of its raw URL.
pub fn host_and_port(config: &ConnectionConfig) -> Result<(String, Option<u16>)> {
    if let Some(url) = &config.url {
        let parsed = connection_string::parse(url).map_err(|e| DriverError::Connect(e.0))?;
        let host = parsed.host.unwrap_or_else(|| "localhost".into());
        return Ok((host, parsed.port.and_then(|p| u16::try_from(p).ok())));
    }
    let host = config.host.trim();
    let host = if host.is_empty() { "localhost" } else { host };
    Ok((
        host.to_owned(),
        config.port.and_then(|p| u16::try_from(p).ok()),
    ))
}

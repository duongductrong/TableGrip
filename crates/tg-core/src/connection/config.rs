//! A saved connection profile (`connection/ConnectionConfig.kt`).

use crate::connection::ai_access::AiAccess;
use crate::json::{Baseline, omit_defaults};
use indexmap::IndexMap;
use serde::{Deserialize, Serialize};
use tg_sql::model::{DatabaseType, EngineFamily};

/// Where a database lives; drives the connection's colour and how cautious the UI is.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Environment {
    #[default]
    None,
    Local,
    Development,
    Staging,
    Production,
}

impl Environment {
    pub fn label(self) -> &'static str {
        match self {
            Self::None => "None",
            Self::Local => "Local",
            Self::Development => "Development",
            Self::Staging => "Staging",
            Self::Production => "Production",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SshAuth {
    Password,
    PrivateKey,
}

/// An SSH tunnel to reach the database through a bastion host. Its password or key passphrase lives in the secret
/// store, like the database password.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SshConfig {
    pub host: String,
    #[serde(default = "ssh_port")]
    pub port: i32,
    pub user: String,
    #[serde(default = "private_key")]
    pub auth: SshAuth,
    /// None uses the default keys in `~/.ssh`.
    #[serde(default)]
    pub private_key_path: Option<String>,
}

fn ssh_port() -> i32 {
    22
}

fn private_key() -> SshAuth {
    SshAuth::PrivateKey
}

impl SshConfig {
    pub fn new(host: &str, user: &str) -> Self {
        Self {
            host: host.to_string(),
            port: 22,
            user: user.to_string(),
            auth: SshAuth::PrivateKey,
            private_key_path: None,
        }
    }
}

impl Baseline for SshConfig {
    fn baseline(&self) -> Self {
        Self::new("", "")
    }
}
omit_defaults!(SshConfig {
    host: "host" = req,
    port: "port",
    user: "user" = req,
    auth: "auth",
    private_key_path: "privateKeyPath",
});

/// How to read a data file profile. None fields are detected from the file; writing back keeps whatever was detected
/// or chosen.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DataFileOptions {
    /// CSV: the field separator; None detects it.
    pub delimiter: Option<char>,
    /// CSV and Excel: whether the first row names the columns.
    pub has_header: bool,
    /// CSV: the encoding's name, e.g. `windows-1252`; None detects it.
    pub charset: Option<String>,
}

impl Default for DataFileOptions {
    fn default() -> Self {
        Self {
            delimiter: None,
            has_header: true,
            charset: None,
        }
    }
}

impl Baseline for DataFileOptions {
    fn baseline(&self) -> Self {
        Self::default()
    }
}
omit_defaults!(DataFileOptions {
    delimiter: "delimiter",
    has_header: "hasHeader",
    charset: "charset",
});

/// The drivers TableGrip knew in the Kotlin app. Profiles store the name; the Rust port compiles its drivers in, but
/// it keeps reading and writing the field so profiles stay compatible.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum DriverId {
    PostgreSQL,
    MySQL,
    MariaDB,
    SQLite,
    H2,
    HSQLDB,
    Derby,
    SqlServer,
    ClickHouse,
    DuckDB,
    Lettuce,
    MongoDB,
    Etcd,
    Generic,
}

impl DriverId {
    /// The drivers that can serve `db`, the default first (`DatabaseType.drivers` in Kotlin).
    pub fn for_type(db: &DatabaseType) -> &'static [DriverId] {
        use DriverId::*;
        match db.family {
            EngineFamily::PostgreSQL => &[PostgreSQL],
            EngineFamily::MySQL => &[MariaDB, MySQL],
            EngineFamily::SQLite => &[SQLite],
            EngineFamily::SqlServer => &[SqlServer],
            EngineFamily::ClickHouse => &[ClickHouse],
            EngineFamily::DuckDB => &[DuckDB],
            EngineFamily::Redis => &[Lettuce],
            EngineFamily::MongoDB => &[MongoDB],
            EngineFamily::Etcd => &[Etcd],
            EngineFamily::Generic => match db.id {
                "H2" => &[H2],
                "HSQLDB" => &[HSQLDB],
                "Derby" => &[Derby],
                _ => &[Generic],
            },
        }
    }
}

/// A saved connection profile. Passwords are not part of it: they live in a secret store.
#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionConfig {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub db_type: DatabaseType,
    #[serde(default = "localhost")]
    pub host: String,
    #[serde(default)]
    pub port: Option<i32>,
    #[serde(default)]
    pub database: String,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub environment: Environment,
    /// Rejects writes and DDL.
    #[serde(default)]
    pub read_only: bool,
    /// Asks before running statements that change data or schema.
    #[serde(default)]
    pub confirm_writes: bool,
    #[serde(default)]
    pub ssh: Option<SshConfig>,
    /// The driver to use; None (or one that can't serve the type) means the type's default.
    #[serde(default)]
    pub driver: Option<DriverId>,
    /// Extra driver properties, e.g. `sslmode=require`.
    #[serde(default)]
    pub properties: IndexMap<String, String>,
    /// A complete JDBC URL used as-is instead of host/port/database. Never contains the password.
    #[serde(default)]
    pub url: Option<String>,
    /// The driver jars of a generic profile: the user's, kept where they are.
    #[serde(default)]
    pub driver_jars: Vec<String>,
    /// The explorer folder the connection is listed in; blank lists it at the top level.
    #[serde(default)]
    pub group: String,
    /// Favorites are listed first, above the groups.
    #[serde(default)]
    pub favorite: bool,
    /// What may be sent to an AI agent; None follows the app-wide setting.
    #[serde(default)]
    pub ai_access: Option<AiAccess>,
    /// How a data file profile reads its file; None takes the defaults.
    #[serde(default)]
    pub data_file: Option<DataFileOptions>,
}

fn localhost() -> String {
    "localhost".to_string()
}

impl ConnectionConfig {
    /// A profile with only its required fields; everything else at its default.
    pub fn new(id: &str, name: &str, db_type: DatabaseType) -> Self {
        Self {
            id: id.to_string(),
            name: name.to_string(),
            db_type,
            host: localhost(),
            port: None,
            database: String::new(),
            user: String::new(),
            environment: Environment::None,
            read_only: false,
            confirm_writes: false,
            ssh: None,
            driver: None,
            properties: IndexMap::new(),
            url: None,
            driver_jars: Vec::new(),
            group: String::new(),
            favorite: false,
            ai_access: None,
            data_file: None,
        }
    }

    pub fn effective_driver(&self) -> DriverId {
        let drivers = DriverId::for_type(&self.db_type);
        self.driver
            .filter(|d| drivers.contains(d))
            .unwrap_or(drivers[0])
    }
}

impl Baseline for ConnectionConfig {
    fn baseline(&self) -> Self {
        Self::new("", "", self.db_type)
    }
}
omit_defaults!(ConnectionConfig {
    id: "id" = req,
    name: "name" = req,
    db_type: "type" = req,
    host: "host",
    port: "port",
    database: "database",
    user: "user",
    environment: "environment",
    read_only: "readOnly",
    confirm_writes: "confirmWrites",
    ssh: "ssh",
    driver: "driver",
    properties: "properties",
    url: "url",
    driver_jars: "driverJars",
    group: "group",
    favorite: "favorite",
    ai_access: "aiAccess",
    data_file: "dataFile",
});

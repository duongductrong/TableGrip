//! The data model the rest of the port shares: engines ([`DatabaseType`]) and what introspection reports about tables.
//!
//! It lives in tg-sql because the dialects, filters, DDL, schema diffs and completion all need it and tg-sql depends on
//! no other TableGrip crate; tg-core, tg-driver, tg-data and the app take it from here (re-exported as `tg_sql::model`).

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::dialect::SqlDialect;
use crate::kt::eq_ignore_case;

/// Engines that speak the same wire protocol and SQL. A family shares a driver and a dialect, so adding an engine to an
/// existing family (Redshift or CockroachDB to PostgreSQL, TiDB to MySQL) is a new [`DatabaseType`] and nothing else.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum EngineFamily {
    PostgreSQL,
    MySQL,
    SQLite,
    /// Microsoft SQL Server and Azure SQL: T-SQL over TDS.
    SqlServer,
    /// Column store over HTTP; no transactions, and changes are mutations.
    ClickHouse,
    /// In-process analytics on a file, Parquet or CSV.
    DuckDB,
    /// Standard SQL read through JDBC metadata alone. The Rust port has no driver for this family (decision D1); its
    /// engines stay listed so profiles that name them still load.
    Generic,
    /// The Redis protocol: Redis, Valkey, KeyDB, Dragonfly.
    Redis,
    /// The MongoDB wire protocol and query language.
    MongoDB,
    /// etcd's v3 key-value API.
    Etcd,
}

/// How a profile reaches its database, which decides the fields the connection dialog asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ConnectionKind {
    /// Host, port, database.
    Server,
    /// A database file.
    File,
    /// Only a JDBC URL, for drivers whose URL syntax TableGrip doesn't model.
    Url,
    /// A data file (CSV, Excel, JSON…) opened as tables through a SQLite working copy.
    DataFile,
}

/// How an engine's data is shaped, which decides the screens that browse it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Paradigm {
    Relational,
    KeyValue,
    Document,
    WideColumn,
    Search,
    Stream,
    Graph,
}

/// Features the UI offers only where the engine has them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Capability {
    /// Showing a statement's plan.
    Explain,
    /// Manual commit and rollback in consoles.
    Transactions,
    /// Editing rows in the table viewer: updates and deletes by primary key, in one transaction.
    EditRows,
}

impl Capability {
    pub const ALL_RELATIONAL: &[Capability] = &[Self::Explain, Self::Transactions, Self::EditRows];
}

/// A database engine TableGrip connects to: what it's called, how it's reached and what it can do. Code asks these
/// properties rather than which engine it is. The Kotlin descriptor also lists JDBC drivers and their default
/// properties; drivers are compiled in here (decision D2), so tg-driver keeps those per engine.
#[derive(Clone, Copy, Debug)]
pub struct DatabaseType {
    /// Stored in connection profiles: never change an existing one.
    pub id: &'static str,
    pub display_name: &'static str,
    pub family: EngineFamily,
    /// None for engines that open a file rather than a server.
    pub default_port: Option<u16>,
    pub paradigm: Paradigm,
    pub capabilities: &'static [Capability],
    pub connection: ConnectionKind,
    /// Placeholder for the database field, e.g. `/path/to/app.mv.db`.
    pub database_hint: Option<&'static str>,
    dialect: Option<SqlDialect>,
}

impl PartialEq for DatabaseType {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
    }
}

impl Eq for DatabaseType {}

impl std::hash::Hash for DatabaseType {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.id.hash(state);
    }
}

impl std::fmt::Display for DatabaseType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.id)
    }
}

const fn relational(
    id: &'static str,
    name: &'static str,
    family: EngineFamily,
    port: Option<u16>,
) -> DatabaseType {
    DatabaseType {
        id,
        display_name: name,
        family,
        default_port: port,
        paradigm: Paradigm::Relational,
        capabilities: Capability::ALL_RELATIONAL,
        connection: if port.is_none() {
            ConnectionKind::File
        } else {
            ConnectionKind::Server
        },
        database_hint: None,
        dialect: SqlDialect::of(family),
    }
}

const fn key_value(id: &'static str, name: &'static str) -> DatabaseType {
    DatabaseType {
        paradigm: Paradigm::KeyValue,
        capabilities: &[],
        ..relational(id, name, EngineFamily::Redis, Some(6379))
    }
}

const fn data_file(id: &'static str, name: &'static str, hint: &'static str) -> DatabaseType {
    DatabaseType {
        connection: ConnectionKind::DataFile,
        database_hint: Some(hint),
        ..relational(id, name, EngineFamily::SQLite, None)
    }
}

const fn with_hint(t: DatabaseType, hint: &'static str) -> DatabaseType {
    DatabaseType {
        database_hint: Some(hint),
        ..t
    }
}

impl DatabaseType {
    pub const POSTGRESQL: Self = relational(
        "PostgreSQL",
        "PostgreSQL",
        EngineFamily::PostgreSQL,
        Some(5432),
    );
    pub const MYSQL: Self = relational("MySQL", "MySQL", EngineFamily::MySQL, Some(3306));
    pub const MARIADB: Self = relational("MariaDB", "MariaDB", EngineFamily::MySQL, Some(3306));
    pub const COCKROACHDB: Self = Self {
        dialect: Some(SqlDialect::CockroachDB),
        ..relational(
            "CockroachDB",
            "CockroachDB",
            EngineFamily::PostgreSQL,
            Some(26257),
        )
    };
    pub const TIMESCALEDB: Self = relational(
        "TimescaleDB",
        "TimescaleDB",
        EngineFamily::PostgreSQL,
        Some(5432),
    );
    pub const NEON: Self = relational("Neon", "Neon", EngineFamily::PostgreSQL, Some(5432));
    pub const SUPABASE: Self =
        relational("Supabase", "Supabase", EngineFamily::PostgreSQL, Some(5432));
    pub const TIDB: Self = relational("TiDB", "TiDB", EngineFamily::MySQL, Some(4000));
    pub const PLANETSCALE: Self = relational(
        "PlanetScale",
        "PlanetScale",
        EngineFamily::MySQL,
        Some(3306),
    );
    pub const SQLITE: Self = relational("SQLite", "SQLite", EngineFamily::SQLite, None);
    pub const SQLSERVER: Self = relational(
        "SqlServer",
        "SQL Server",
        EngineFamily::SqlServer,
        Some(1433),
    );
    pub const AZURESQL: Self =
        relational("AzureSql", "Azure SQL", EngineFamily::SqlServer, Some(1433));
    pub const CLICKHOUSE: Self = Self {
        capabilities: &[Capability::Explain],
        ..relational(
            "ClickHouse",
            "ClickHouse",
            EngineFamily::ClickHouse,
            Some(8123),
        )
    };
    pub const DUCKDB: Self = with_hint(
        relational("DuckDB", "DuckDB", EngineFamily::DuckDB, None),
        "/path/to/file.duckdb or :memory:",
    );
    pub const REDIS: Self = key_value("Redis", "Redis");
    pub const VALKEY: Self = key_value("Valkey", "Valkey");
    pub const KEYDB: Self = key_value("KeyDB", "KeyDB");
    pub const DRAGONFLY: Self = key_value("Dragonfly", "Dragonfly");
    pub const ETCD: Self = Self {
        paradigm: Paradigm::KeyValue,
        capabilities: &[],
        ..relational("Etcd", "etcd", EngineFamily::Etcd, Some(2379))
    };
    pub const FERRETDB: Self = Self {
        paradigm: Paradigm::Document,
        capabilities: &[],
        ..relational("FerretDB", "FerretDB", EngineFamily::MongoDB, Some(27017))
    };
    pub const MONGODB: Self = Self {
        paradigm: Paradigm::Document,
        capabilities: &[],
        ..relational("MongoDB", "MongoDB", EngineFamily::MongoDB, Some(27017))
    };
    pub const H2: Self = with_hint(
        relational("H2", "H2", EngineFamily::Generic, None),
        "/path/to/data (without .mv.db) or mem:test",
    );
    pub const HSQLDB: Self = with_hint(
        relational("HSQLDB", "HSQLDB", EngineFamily::Generic, None),
        "/path/to/data (without .script) or mem:test",
    );
    pub const DERBY: Self = with_hint(
        relational("Derby", "Apache Derby", EngineFamily::Generic, None),
        "/path/to/database directory",
    );
    pub const CSV_FILE: Self = data_file("CsvFile", "CSV / TSV file", "/path/to/data.csv");
    pub const EXCEL_FILE: Self = data_file("ExcelFile", "Excel workbook", "/path/to/workbook.xlsx");
    pub const JSON_FILE: Self =
        data_file("JsonFile", "JSON / NDJSON file", "/path/to/records.json");
    /// Any JDBC driver: the user brings its jar and the URL.
    pub const OTHER_JDBC: Self = Self {
        connection: ConnectionKind::Url,
        ..relational(
            "OtherJdbc",
            "Other (JDBC driver)",
            EngineFamily::Generic,
            None,
        )
    };

    /// Every engine, in the order the connection dialog lists them.
    pub const ENTRIES: &[DatabaseType] = &[
        Self::POSTGRESQL,
        Self::MYSQL,
        Self::MARIADB,
        Self::SQLITE,
        Self::SQLSERVER,
        Self::CLICKHOUSE,
        Self::DUCKDB,
        Self::MONGODB,
        Self::REDIS,
        Self::VALKEY,
        Self::COCKROACHDB,
        Self::TIMESCALEDB,
        Self::NEON,
        Self::SUPABASE,
        Self::TIDB,
        Self::PLANETSCALE,
        Self::AZURESQL,
        Self::KEYDB,
        Self::DRAGONFLY,
        Self::ETCD,
        Self::FERRETDB,
        Self::H2,
        Self::HSQLDB,
        Self::DERBY,
        Self::OTHER_JDBC,
        Self::CSV_FILE,
        Self::EXCEL_FILE,
        Self::JSON_FILE,
    ];

    /// The engine stored as `id` (any case).
    pub fn by_id(id: &str) -> Option<&'static DatabaseType> {
        Self::ENTRIES.iter().find(|t| eq_ignore_case(t.id, id))
    }

    /// The engine's SQL dialect; None for engines without SQL.
    pub fn dialect(&self) -> Option<SqlDialect> {
        self.dialect
    }

    /// Opens a file rather than a server: a database file or a data file.
    pub fn is_file(&self) -> bool {
        matches!(
            self.connection,
            ConnectionKind::File | ConnectionKind::DataFile
        )
    }

    pub fn is_data_file(&self) -> bool {
        self.connection == ConnectionKind::DataFile
    }

    pub fn is_server(&self) -> bool {
        self.connection == ConnectionKind::Server
    }

    pub fn has(&self, capability: Capability) -> bool {
        self.capabilities.contains(&capability)
    }
}

/// Written as the id, as the Kotlin profiles store it.
impl Serialize for DatabaseType {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.id)
    }
}

impl<'de> Deserialize<'de> for DatabaseType {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let id = String::deserialize(deserializer)?;
        Self::by_id(&id).copied().ok_or_else(|| {
            serde::de::Error::custom(format!(
                "Unknown database type \"{id}\". The profile may come from a newer version of TableGrip."
            ))
        })
    }
}

/// `java.sql.Types` constants, as drivers and the Kotlin core report column types.
pub mod jdbc_types {
    pub const BIT: i32 = -7;
    pub const TINYINT: i32 = -6;
    pub const SMALLINT: i32 = 5;
    pub const INTEGER: i32 = 4;
    pub const BIGINT: i32 = -5;
    pub const FLOAT: i32 = 6;
    pub const REAL: i32 = 7;
    pub const DOUBLE: i32 = 8;
    pub const NUMERIC: i32 = 2;
    pub const DECIMAL: i32 = 3;
    pub const CHAR: i32 = 1;
    pub const VARCHAR: i32 = 12;
    pub const LONGVARCHAR: i32 = -1;
    pub const DATE: i32 = 91;
    pub const TIME: i32 = 92;
    pub const TIMESTAMP: i32 = 93;
    pub const BINARY: i32 = -2;
    pub const VARBINARY: i32 = -3;
    pub const LONGVARBINARY: i32 = -4;
    pub const NULL: i32 = 0;
    pub const OTHER: i32 = 1111;
    pub const JAVA_OBJECT: i32 = 2000;
    pub const DISTINCT: i32 = 2001;
    pub const STRUCT: i32 = 2002;
    pub const ARRAY: i32 = 2003;
    pub const BLOB: i32 = 2004;
    pub const CLOB: i32 = 2005;
    pub const REF: i32 = 2006;
    pub const DATALINK: i32 = 70;
    pub const BOOLEAN: i32 = 16;
    pub const ROWID: i32 = -8;
    pub const NCHAR: i32 = -15;
    pub const NVARCHAR: i32 = -9;
    pub const LONGNVARCHAR: i32 = -16;
    pub const NCLOB: i32 = 2011;
    pub const SQLXML: i32 = 2009;
    pub const REF_CURSOR: i32 = 2012;
    pub const TIME_WITH_TIMEZONE: i32 = 2013;
    pub const TIMESTAMP_WITH_TIMEZONE: i32 = 2014;
}

/// A schema (PostgreSQL), a database (MySQL), or the single `main` database (SQLite).
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Namespace {
    pub catalog: Option<String>,
    pub schema: Option<String>,
}

impl Namespace {
    pub fn new(catalog: Option<&str>, schema: Option<&str>) -> Self {
        Self {
            catalog: catalog.map(str::to_owned),
            schema: schema.map(str::to_owned),
        }
    }

    pub fn display_name(&self) -> &str {
        self.schema
            .as_deref()
            .or(self.catalog.as_deref())
            .unwrap_or("main")
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct TableRef {
    pub namespace: Namespace,
    pub name: String,
}

impl TableRef {
    pub fn new(namespace: Namespace, name: &str) -> Self {
        Self {
            namespace,
            name: name.to_owned(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableInfo {
    pub table: TableRef,
    pub is_view: bool,
    pub remarks: Option<String>,
}

/// A result column (`driver/QueryResult.kt`): its label, the type name the server reports, and a [`jdbc_types`]
/// constant that says how to treat its values. Shared by the drivers, the exporters and the chart profiler.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResultColumn {
    pub name: String,
    pub type_name: String,
    pub jdbc_type: i32,
}

impl ResultColumn {
    pub fn new(name: &str, type_name: &str, jdbc_type: i32) -> Self {
        Self {
            name: name.to_owned(),
            type_name: type_name.to_owned(),
            jdbc_type,
        }
    }

    pub fn is_numeric(&self) -> bool {
        use jdbc_types::*;
        [
            TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, NUMERIC, DECIMAL,
        ]
        .contains(&self.jdbc_type)
    }

    pub fn is_binary(&self) -> bool {
        use jdbc_types::*;
        [BINARY, VARBINARY, LONGVARBINARY, BLOB].contains(&self.jdbc_type)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ColumnInfo {
    pub name: String,
    pub type_name: String,
    pub size: Option<i32>,
    pub nullable: bool,
    pub default_value: Option<String>,
    pub auto_increment: bool,
    pub primary_key: bool,
    /// A [`jdbc_types`] constant, as the driver reports it.
    pub jdbc_type: i32,
    /// Digits after the decimal point for exact numerics (`numeric(10,2)` has 2), else None.
    pub scale: Option<i32>,
}

impl ColumnInfo {
    /// A column with Kotlin's defaults for the optional fields.
    pub fn new(name: &str, type_name: &str) -> Self {
        Self {
            name: name.to_owned(),
            type_name: type_name.to_owned(),
            size: None,
            nullable: true,
            default_value: None,
            auto_increment: false,
            primary_key: false,
            jdbc_type: jdbc_types::OTHER,
            scale: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IndexInfo {
    pub name: String,
    pub columns: Vec<String>,
    pub unique: bool,
}

/// `on_update` and `on_delete` are referential actions such as `CASCADE`, or None for the default (no action).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ForeignKeyInfo {
    pub name: String,
    pub columns: Vec<String>,
    pub referenced_table: TableRef,
    pub referenced_columns: Vec<String>,
    pub on_update: Option<String>,
    pub on_delete: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableDetails {
    pub table: TableRef,
    pub columns: Vec<ColumnInfo>,
    pub indexes: Vec<IndexInfo>,
    pub foreign_keys: Vec<ForeignKeyInfo>,
    /// The primary key constraint's name where the engine names it (`users_pkey`, `PRIMARY`), else None.
    pub primary_key_name: Option<String>,
}

impl TableDetails {
    pub fn primary_key(&self) -> Vec<&str> {
        self.columns
            .iter()
            .filter(|c| c.primary_key)
            .map(|c| c.name.as_str())
            .collect()
    }
}

/// A foreign key: `from_table.from_columns` references `to_table.to_columns`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableRelation {
    pub from_table: TableRef,
    pub from_columns: Vec<String>,
    pub to_table: TableRef,
    pub to_columns: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ConstraintKind {
    PrimaryKey,
    Unique,
    ForeignKey,
    Check,
    Exclusion,
}

impl ConstraintKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::PrimaryKey => "PRIMARY KEY",
            Self::Unique => "UNIQUE",
            Self::ForeignKey => "FOREIGN KEY",
            Self::Check => "CHECK",
            Self::Exclusion => "EXCLUDE",
        }
    }
}

/// A table constraint; `definition` is its SQL as it would follow `CONSTRAINT name`. `name` is empty if unnamed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConstraintInfo {
    pub name: String,
    pub kind: ConstraintKind,
    pub columns: Vec<String>,
    pub definition: String,
}

/// `timing` is e.g. `BEFORE`, `events` e.g. `INSERT OR UPDATE`; `definition` is the full `CREATE TRIGGER` statement.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TriggerInfo {
    pub name: String,
    pub timing: String,
    pub events: String,
    pub enabled: bool,
    pub definition: String,
}

/// Everything the structure view shows about a table or view.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableStructure {
    pub details: TableDetails,
    pub constraints: Vec<ConstraintInfo>,
    pub triggers: Vec<TriggerInfo>,
    pub ddl: String,
}

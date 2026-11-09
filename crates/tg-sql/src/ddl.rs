//! Table designs and the DDL that turns one into another (`ddl/TableDesign.kt`, `ddl/DdlGenerator.kt`).

use regex::Regex;
use std::collections::HashMap;
use std::sync::LazyLock;

use crate::decimal::Decimal;
use crate::dialect::SqlDialect;
use crate::kt;
use crate::model::{
    ColumnInfo, ConstraintInfo, ConstraintKind, ForeignKeyInfo, IndexInfo, TableDetails, TableRef,
    TriggerInfo, jdbc_types as types,
};

/// A column as designed. `type_` is the declared type as it would appear in `CREATE TABLE` (`varchar(255)`,
/// `numeric(10,2)`), `default_value` a SQL expression (`'draft'`, `now()`), and `original_name` ties the column to the
/// one it was read as, so a changed name is a rename rather than a drop and an add. None for a new column.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DesignColumn {
    pub name: String,
    pub type_: String,
    pub nullable: bool,
    pub default_value: Option<String>,
    pub auto_increment: bool,
    pub original_name: Option<String>,
}

impl DesignColumn {
    pub fn new(name: &str, type_: &str) -> Self {
        Self {
            name: name.to_owned(),
            type_: type_.to_owned(),
            nullable: true,
            default_value: None,
            auto_increment: false,
            original_name: None,
        }
    }
}

/// A table as designed: what the table designer edits and schema compare migrates to. Column names in `primary_key`,
/// `indexes` and `foreign_keys` are the designed (current) names. `unique_constraints`, `checks` and `triggers` aren't
/// edited but are kept, so an engine that has to rebuild the table to change it (SQLite) brings them back.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableDesign {
    pub table: TableRef,
    pub columns: Vec<DesignColumn>,
    pub primary_key: Vec<String>,
    pub indexes: Vec<IndexInfo>,
    pub foreign_keys: Vec<ForeignKeyInfo>,
    pub primary_key_name: Option<String>,
    pub unique_constraints: Vec<IndexInfo>,
    /// Full `CHECK (…)` clauses.
    pub checks: Vec<String>,
    /// Full `CREATE TRIGGER` statements.
    pub triggers: Vec<String>,
}

const SQLITE_AUTO_INDEX: &str = "sqlite_autoindex_";

impl TableDesign {
    pub fn new(table: TableRef, columns: Vec<DesignColumn>) -> Self {
        Self {
            table,
            columns,
            primary_key: vec![],
            indexes: vec![],
            foreign_keys: vec![],
            primary_key_name: None,
            unique_constraints: vec![],
            checks: vec![],
            triggers: vec![],
        }
    }

    pub fn column(&self, name: &str) -> Option<&DesignColumn> {
        self.columns.iter().find(|c| c.name == name)
    }

    /// The design of an existing table. With `constraints` (from the structure view), indexes that back a UNIQUE
    /// constraint are kept apart from plain indexes, since engines drop them differently.
    pub fn from(
        details: &TableDetails,
        dialect: SqlDialect,
        constraints: &[ConstraintInfo],
        triggers: &[TriggerInfo],
    ) -> Self {
        let primary_key: Vec<String> = details
            .primary_key()
            .into_iter()
            .map(str::to_owned)
            .collect();
        let unique_names: Vec<&str> = constraints
            .iter()
            .filter(|c| c.kind == ConstraintKind::Unique)
            .map(|c| c.name.as_str())
            .collect();
        let (unique_constraints, indexes): (Vec<IndexInfo>, Vec<IndexInfo>) = details
            .indexes
            .iter()
            .filter(|i| !is_primary_key_index(i, &primary_key, details.primary_key_name.as_deref()))
            .cloned()
            .partition(|i| {
                unique_names.contains(&i.name.as_str()) || i.name.starts_with(SQLITE_AUTO_INDEX)
            });
        Self {
            table: details.table.clone(),
            columns: details
                .columns
                .iter()
                .map(|column| {
                    let (type_, identity) = column_types::declared_type(column, dialect);
                    DesignColumn {
                        name: column.name.clone(),
                        type_,
                        // SQLite reports INTEGER PRIMARY KEY as nullable; a key column never is.
                        nullable: column.nullable && !primary_key.contains(&column.name),
                        default_value: column_types::default_expression(column, dialect),
                        auto_increment: column.auto_increment || identity,
                        original_name: Some(column.name.clone()),
                    }
                })
                .collect(),
            primary_key,
            indexes,
            foreign_keys: details.foreign_keys.clone(),
            primary_key_name: details.primary_key_name.clone(),
            unique_constraints,
            checks: constraints
                .iter()
                .filter(|c| c.kind == ConstraintKind::Check)
                .map(|c| c.definition.clone())
                .collect(),
            triggers: triggers.iter().map(|t| t.definition.clone()).collect(),
        }
    }
}

/// The index the engine made for the primary key: dropping or recreating it is the primary key's business.
fn is_primary_key_index(
    index: &IndexInfo,
    primary_key: &[String],
    primary_key_name: Option<&str>,
) -> bool {
    if primary_key_name == Some(index.name.as_str()) {
        return true;
    }
    if !index.unique || primary_key.is_empty() || index.columns != primary_key {
        return false;
    }
    let name = index.name.as_str();
    name == "PRIMARY"
        || name.ends_with("_pkey")
        || name.starts_with("sqlite_autoindex_")
        || name.starts_with("PK__")
        || name.starts_with("PRIMARY_KEY")
        || name.starts_with("SYS_IDX_")
        || name.starts_with("SQL") && name.encode_utf16().count() > 12
}

/// How a foreign key is matched between two versions of a table: by name, or for unnamed ones (SQLite's) by what it
/// links, so two unnamed keys don't collapse into one.
pub fn foreign_key_identity(fk: &ForeignKeyInfo) -> String {
    if kt::is_blank(&fk.name) {
        format!(
            "{}->{}({})",
            fk.columns.join(","),
            fk.referenced_table.name,
            fk.referenced_columns.join(",")
        )
    } else {
        fk.name.clone()
    }
}

/// One table's change: `original` None creates the table, `target` None drops it, both alters `original` into
/// `target`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableChange {
    pub original: Option<TableDesign>,
    pub target: Option<TableDesign>,
}

impl TableChange {
    pub fn new(original: Option<TableDesign>, target: Option<TableDesign>) -> Self {
        assert!(
            original.is_some() || target.is_some(),
            "A change needs a table before or after"
        );
        Self { original, target }
    }

    pub fn table(&self) -> &TableRef {
        &self
            .target
            .as_ref()
            .or(self.original.as_ref())
            .unwrap()
            .table
    }
}

/// Declared types and default expressions as `CREATE TABLE` would spell them, from what metadata reports.
pub mod column_types {
    use super::*;

    /// Types whose length is part of the declared type; for others (int, date…) the driver's size is noise.
    const LENGTH_TYPES: &[&str] = &[
        "varchar",
        "char",
        "character",
        "character varying",
        "nvarchar",
        "nchar",
        "varbinary",
        "binary",
        "bit",
        "varbit",
        "bit varying",
        "bpchar",
        "varchar2",
        "nvarchar2",
        "raw",
    ];
    const EXACT_NUMERIC_TYPES: &[&str] = &["numeric", "decimal", "number", "dec"];
    /// Above this, a length is the driver's way of saying "unbounded" (PostgreSQL's 2147483647, SQL Server's max).
    const UNBOUNDED_LENGTH: i32 = 10_485_760;
    const SQL_DEFAULT_KEYWORDS: &[&str] = &[
        "CURRENT_DATE",
        "CURRENT_TIME",
        "LOCALTIME",
        "LOCALTIMESTAMP",
        "NOW()",
        "TRUE",
        "FALSE",
        "UUID()",
    ];
    const TEXT_TYPES: &[i32] = &[
        types::CHAR,
        types::VARCHAR,
        types::LONGVARCHAR,
        types::NCHAR,
        types::NVARCHAR,
        types::LONGNVARCHAR,
        types::CLOB,
    ];

    /// The type as declared, and whether it carried an identity marker (`int identity` on SQL Server).
    pub fn declared_type(column: &ColumnInfo, dialect: SqlDialect) -> (String, bool) {
        let mut name = kt::trim(&column.type_name).to_owned();
        let suffix = name.len().checked_sub(9).and_then(|at| name.get(at..));
        let identity = suffix.is_some_and(|s| kt::eq_ignore_case(s, " identity"));
        if identity {
            name = kt::trim(&name[..name.len() - 9]).to_owned();
        }
        let base = name.to_lowercase();
        let declared = match column.size {
            // Already spelled with its arguments, e.g. ClickHouse `Decimal(10, 2)` or `Nullable(String)`.
            _ if name.contains('(') => name.clone(),
            None => name.clone(),
            Some(size) if size <= 0 => name.clone(),
            Some(size) if LENGTH_TYPES.contains(&base.as_str()) => {
                if size <= UNBOUNDED_LENGTH {
                    format!("{name}({size})")
                } else if dialect == SqlDialect::SqlServer {
                    // SQL Server reports `(max)` as a huge length; PostgreSQL's unbounded varchar has no length.
                    format!("{name}(max)")
                } else {
                    name.clone()
                }
            }
            Some(size)
                if EXACT_NUMERIC_TYPES.contains(&base.as_str()) && (1..=1000).contains(&size) =>
            {
                match column.scale.filter(|s| *s > 0) {
                    Some(scale) => format!("{name}({size},{scale})"),
                    None => format!("{name}({size})"),
                }
            }
            _ => name.clone(),
        };
        (declared, identity)
    }

    /// The column default as a SQL expression. MySQL reports text defaults bare (`draft` for `DEFAULT 'draft'`), so
    /// those get their quotes back; everyone else already reports an expression.
    pub fn default_expression(column: &ColumnInfo, dialect: SqlDialect) -> Option<String> {
        let raw = column.default_value.as_deref()?;
        if dialect != SqlDialect::MySQL {
            return Some(kt::trim(raw).to_owned());
        }
        let value = kt::trim(raw);
        let upper = value.to_uppercase();
        let bare_keyword = SQL_DEFAULT_KEYWORDS.contains(&upper.as_str())
            || upper.starts_with("CURRENT_TIMESTAMP");
        if kt::eq_ignore_case(value, "NULL") {
            None
        } else if value.starts_with('\'')
            || value.starts_with('(')
            || bare_keyword
            || value.contains('(') && value.ends_with(')')
            || Decimal::parse(value).is_some() && !TEXT_TYPES.contains(&column.jdbc_type)
            || value.starts_with("b'")
        {
            Some(value.to_owned())
        } else {
            Some(dialect.literal(Some(raw), false))
        }
    }

    static AROUND_PUNCTUATION: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r]*([(),])[ \t\n\x0B\x0C\r]*").unwrap());
    static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r]+").unwrap());
    static CAST: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"::[a-zA-Z_ ]+(\[\])?(\([0-9]+(,[0-9]+)?\))?").unwrap());

    fn alias(base: &str) -> &str {
        match base {
            "int" | "int4" => "integer",
            "int8" => "bigint",
            "int2" => "smallint",
            "serial4" => "serial",
            "serial8" => "bigserial",
            "float8" | "double" => "double precision",
            "float4" => "real",
            "bool" => "boolean",
            "character varying" => "varchar",
            "character" | "bpchar" => "char",
            "decimal" | "dec" => "numeric",
            "timestamp without time zone" => "timestamp",
            "timestamp with time zone" => "timestamptz",
            "time without time zone" => "time",
            "time with time zone" => "timetz",
            other => other,
        }
    }

    /// Comparable form of a declared type: lower case, no spaces around commas, and each engine's aliases folded
    /// together, so `int4` and `integer` or `character varying(20)` and `varchar(20)` aren't reported as differences.
    pub fn normalize(type_: &str) -> String {
        let lower = kt::trim(type_).to_lowercase();
        let lower = AROUND_PUNCTUATION.replace_all(&lower, "$1");
        let lower = SPACES.replace_all(&lower, " ");
        let base = kt::trim(lower.split('(').next().unwrap_or(""));
        let arguments = kt::trim(&lower[base.len()..]);
        format!("{}{arguments}", alias(base))
    }

    /// Comparable form of a default expression: no PostgreSQL casts or wrapping parentheses, case folded outside
    /// quotes.
    pub fn normalize_default(expression: Option<&str>) -> Option<String> {
        let mut value = kt::trim(expression?).to_owned();
        if kt::eq_ignore_case(&value, "NULL") {
            return None;
        }
        while value.len() >= 2
            && value.starts_with('(')
            && value.ends_with(')')
            && balanced(&value[1..value.len() - 1])
        {
            value = kt::trim(&value[1..value.len() - 1]).to_owned();
        }
        let value = CAST.replace_all(&value, "").into_owned();
        Some(if value.starts_with('\'') {
            value
        } else {
            value.to_lowercase()
        })
    }

    fn balanced(text: &str) -> bool {
        let mut depth = 0;
        for c in text.chars() {
            if c == '(' {
                depth += 1;
            } else if c == ')' {
                depth -= 1;
                if depth < 0 {
                    return false;
                }
            }
        }
        depth == 0
    }

    /// Types each engine offers in the designer's type field, most used first.
    pub fn common_types(dialect: SqlDialect) -> &'static [&'static str] {
        match dialect {
            SqlDialect::PostgreSQL | SqlDialect::CockroachDB => &[
                "integer",
                "bigint",
                "smallint",
                "serial",
                "bigserial",
                "numeric(10,2)",
                "real",
                "double precision",
                "boolean",
                "text",
                "varchar(255)",
                "char(1)",
                "uuid",
                "date",
                "timestamp",
                "timestamptz",
                "time",
                "interval",
                "jsonb",
                "json",
                "bytea",
                "inet",
                "text[]",
                "integer[]",
            ],
            SqlDialect::MySQL => &[
                "INT",
                "BIGINT",
                "SMALLINT",
                "TINYINT",
                "DECIMAL(10,2)",
                "DOUBLE",
                "FLOAT",
                "BOOLEAN",
                "VARCHAR(255)",
                "CHAR(36)",
                "TEXT",
                "MEDIUMTEXT",
                "LONGTEXT",
                "DATE",
                "DATETIME",
                "TIMESTAMP",
                "TIME",
                "YEAR",
                "JSON",
                "BLOB",
                "ENUM('a','b')",
            ],
            SqlDialect::SQLite => &[
                "INTEGER",
                "TEXT",
                "REAL",
                "NUMERIC",
                "BLOB",
                "BOOLEAN",
                "DATE",
                "DATETIME",
                "VARCHAR(255)",
            ],
            SqlDialect::SqlServer => &[
                "int",
                "bigint",
                "smallint",
                "tinyint",
                "bit",
                "decimal(10,2)",
                "float",
                "nvarchar(255)",
                "nvarchar(max)",
                "varchar(255)",
                "char(1)",
                "uniqueidentifier",
                "date",
                "datetime2",
                "datetimeoffset",
                "time",
                "varbinary(max)",
            ],
            SqlDialect::ClickHouse => &[
                "Int32",
                "Int64",
                "UInt32",
                "UInt64",
                "Float64",
                "Decimal(18, 2)",
                "String",
                "Bool",
                "UUID",
                "Date",
                "DateTime",
                "DateTime64(3)",
                "LowCardinality(String)",
            ],
            SqlDialect::DuckDB => &[
                "INTEGER",
                "BIGINT",
                "SMALLINT",
                "DECIMAL(10,2)",
                "DOUBLE",
                "BOOLEAN",
                "VARCHAR",
                "UUID",
                "DATE",
                "TIMESTAMP",
                "TIMESTAMPTZ",
                "TIME",
                "JSON",
                "BLOB",
            ],
            SqlDialect::Generic => &[
                "INTEGER",
                "BIGINT",
                "SMALLINT",
                "DECIMAL(10,2)",
                "DOUBLE",
                "BOOLEAN",
                "VARCHAR(255)",
                "CHAR(1)",
                "CLOB",
                "DATE",
                "TIMESTAMP",
                "TIME",
                "BLOB",
            ],
        }
    }
}

/// When a statement runs relative to the others. A script runs phase by phase, so foreign keys go before what they
/// depend on is dropped and come back after what they point at exists, whichever tables the changes touch.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum DdlPhase {
    /// Outside the transaction, before it (SQLite's `PRAGMA foreign_keys`, a no-op inside one).
    Prologue,
    DropForeignKeys,
    DropIndexes,
    Tables,
    CreateIndexes,
    AddForeignKeys,
    DropTables,
    /// Outside the transaction, after it.
    Epilogue,
}

impl DdlPhase {
    pub fn name(self) -> &'static str {
        match self {
            Self::Prologue => "Prologue",
            Self::DropForeignKeys => "DropForeignKeys",
            Self::DropIndexes => "DropIndexes",
            Self::Tables => "Tables",
            Self::CreateIndexes => "CreateIndexes",
            Self::AddForeignKeys => "AddForeignKeys",
            Self::DropTables => "DropTables",
            Self::Epilogue => "Epilogue",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct DdlStatement {
    pub sql: String,
    pub phase: DdlPhase,
}

/// Statements in the order they must run, and what the user should know before running them: changes an engine can't
/// make, or makes by copying the table.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DdlScript {
    pub statements: Vec<DdlStatement>,
    pub warnings: Vec<String>,
    pub transactional: bool,
}

impl DdlScript {
    pub fn is_empty(&self) -> bool {
        self.statements.is_empty()
    }

    /// The script as text, for the preview and consoles.
    pub fn text(&self) -> String {
        self.statements
            .iter()
            .map(|s| {
                let sql = kt::trim_end(&s.sql);
                format!("{};", sql.strip_suffix(';').unwrap_or(sql))
            })
            .collect::<Vec<_>>()
            .join("\n\n")
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Engine {
    PostgreSQL,
    MySQL,
    SQLite,
    SqlServer,
    DuckDB,
    ClickHouse,
    Generic,
}

#[derive(Default)]
struct Output {
    statements: Vec<DdlStatement>,
    warnings: Vec<String>,
}

impl Output {
    fn add(&mut self, phase: DdlPhase, sql: String) {
        self.statements.push(DdlStatement { sql, phase });
    }

    fn warn(&mut self, message: String) {
        self.warnings.push(message);
    }
}

fn distinct<T: PartialEq>(items: Vec<T>) -> Vec<T> {
    let mut out: Vec<T> = Vec::with_capacity(items.len());
    for item in items {
        if !out.contains(&item) {
            out.push(item);
        }
    }
    out
}

/// Writes DDL for table changes in a dialect's SQL. PostgreSQL, MySQL/MariaDB, SQLite and SQL Server get their own
/// syntax; DuckDB and ClickHouse what they support; other engines standard SQL, with a warning to check it.
pub struct DdlGenerator {
    dialect: SqlDialect,
    engine: Engine,
}

impl DdlGenerator {
    pub fn new(dialect: SqlDialect) -> Self {
        let engine = match dialect {
            SqlDialect::PostgreSQL | SqlDialect::CockroachDB => Engine::PostgreSQL,
            SqlDialect::MySQL => Engine::MySQL,
            SqlDialect::SQLite => Engine::SQLite,
            SqlDialect::SqlServer => Engine::SqlServer,
            SqlDialect::DuckDB => Engine::DuckDB,
            SqlDialect::ClickHouse => Engine::ClickHouse,
            SqlDialect::Generic => Engine::Generic,
        };
        Self { dialect, engine }
    }

    /// Whether the engine can roll DDL back: the script then runs in one transaction, all or nothing.
    pub fn transactional_ddl(&self) -> bool {
        matches!(
            self.engine,
            Engine::PostgreSQL | Engine::SQLite | Engine::SqlServer | Engine::DuckDB
        )
    }

    /// Engines whose foreign keys can only be declared in `CREATE TABLE`.
    fn inline_foreign_keys(&self) -> bool {
        matches!(self.engine, Engine::SQLite | Engine::DuckDB)
    }

    pub fn script(&self, changes: &[TableChange]) -> DdlScript {
        let mut out = Output::default();
        for change in changes {
            match (&change.original, &change.target) {
                (None, Some(target)) => self.create_table(target, &mut out, &target.table),
                (Some(original), None) => self.drop_table(original, &mut out),
                (Some(original), Some(target)) => self.alter_table(original, target, &mut out),
                (None, None) => unreachable!("A change needs a table before or after"),
            }
        }
        if self.engine == Engine::Generic && !out.statements.is_empty() {
            out.warn(format!(
                "Written in standard SQL for {}: check it against your database's syntax before running it.",
                self.dialect.name()
            ));
        }
        // A stable sort keeps statements of one phase in the order they were written.
        let mut ordered = out.statements;
        ordered.sort_by_key(|s| s.phase);
        // SQLite rebuilds turn foreign keys off around the transaction; once is enough.
        DdlScript {
            statements: distinct(ordered),
            warnings: distinct(out.warnings),
            transactional: self.transactional_ddl(),
        }
    }

    fn q(&self, name: &str) -> String {
        self.dialect.quote_identifier(name)
    }

    fn columns(&self, names: &[String]) -> String {
        format!(
            "({})",
            names
                .iter()
                .map(|n| self.q(n))
                .collect::<Vec<_>>()
                .join(", ")
        )
    }

    fn qualified(&self, table: &TableRef) -> String {
        self.dialect.qualified_name(table)
    }

    /// Index names live in the table's schema where engines have schemas.
    fn qualified_index(&self, table: &TableRef, name: &str) -> String {
        match (self.engine, &table.namespace.schema) {
            (Engine::PostgreSQL | Engine::Generic | Engine::DuckDB, Some(schema)) => {
                format!("{}.{}", self.q(schema), self.q(name))
            }
            _ => self.q(name),
        }
    }

    fn create_table(&self, design: &TableDesign, out: &mut Output, name: &TableRef) {
        assert!(
            !design.columns.is_empty(),
            "{} has no columns",
            design.table.name
        );
        let engine = self.engine;
        let inline_primary_key = engine == Engine::SQLite
            && design.primary_key.len() == 1
            && design
                .column(&design.primary_key[0])
                .is_some_and(|c| c.auto_increment);
        let mut lines: Vec<String> = design
            .columns
            .iter()
            .map(|c| {
                self.column_definition(c, inline_primary_key && c.name == design.primary_key[0])
            })
            .collect();
        if !design.primary_key.is_empty() && !inline_primary_key && engine != Engine::ClickHouse {
            let constraint = design
                .primary_key_name
                .as_ref()
                .filter(|_| matches!(engine, Engine::PostgreSQL | Engine::SqlServer))
                .map_or_else(String::new, |n| format!("CONSTRAINT {} ", self.q(n)));
            lines.push(format!(
                "{constraint}PRIMARY KEY {}",
                self.columns(&design.primary_key)
            ));
        }
        for unique in &design.unique_constraints {
            let named = if engine == Engine::SQLite || unique.name.starts_with("sqlite_autoindex") {
                String::new()
            } else {
                format!("CONSTRAINT {} ", self.q(&unique.name))
            };
            lines.push(format!("{named}UNIQUE {}", self.columns(&unique.columns)));
        }
        lines.extend(design.checks.iter().cloned());
        if self.inline_foreign_keys() {
            for fk in &design.foreign_keys {
                lines.push(self.foreign_key_clause(name, fk, engine != Engine::SQLite));
            }
        }
        let body = format!("(\n    {}\n)", lines.join(",\n    "));
        let suffix = if engine == Engine::ClickHouse {
            let order = if design.primary_key.is_empty() {
                "tuple()".to_owned()
            } else {
                self.columns(&design.primary_key)
            };
            format!("\nENGINE = MergeTree\nORDER BY {order}")
        } else {
            String::new()
        };
        out.add(
            DdlPhase::Tables,
            format!("CREATE TABLE {} {body}{suffix}", self.qualified(name)),
        );
        for index in &design.indexes {
            out.add(DdlPhase::CreateIndexes, self.create_index(name, index));
        }
        if !self.inline_foreign_keys() {
            for fk in &design.foreign_keys {
                self.add_foreign_key(name, fk, out);
            }
        }
        if engine == Engine::ClickHouse {
            if !design.foreign_keys.is_empty() {
                out.warn(format!(
                    "ClickHouse has no foreign keys: those of {} are left out.",
                    name.name
                ));
            }
            if !design.indexes.is_empty() {
                out.warn(format!(
                    "ClickHouse indexes are data-skipping indexes: those of {} are left out.",
                    name.name
                ));
            }
        }
        if engine == Engine::DuckDB && design.columns.iter().any(|c| c.auto_increment) {
            out.warn(format!(
                "DuckDB has no auto-increment columns: give {} a sequence default instead (DEFAULT nextval('seq')).",
                name.name
            ));
        }
    }

    fn drop_table(&self, design: &TableDesign, out: &mut Output) {
        out.add(
            DdlPhase::DropTables,
            format!("DROP TABLE {}", self.qualified(&design.table)),
        );
    }

    fn alter_table(&self, original: &TableDesign, target: &TableDesign, out: &mut Output) {
        let renames: HashMap<&str, &str> = target
            .columns
            .iter()
            .filter_map(|c| {
                c.original_name
                    .as_deref()
                    .filter(|o| *o != c.name)
                    .map(|o| (o, c.name.as_str()))
            })
            .collect();
        let renamed = |names: &[String]| -> Vec<String> {
            names
                .iter()
                .map(|n| {
                    renames
                        .get(n.as_str())
                        .map_or_else(|| n.clone(), |r| (*r).to_owned())
                })
                .collect()
        };
        let original_by_name: HashMap<&str, &DesignColumn> = original
            .columns
            .iter()
            .map(|c| (c.name.as_str(), c))
            .collect();
        let in_original = |c: &DesignColumn| {
            c.original_name
                .as_deref()
                .is_some_and(|o| original_by_name.contains_key(o))
        };
        let kept: Vec<&DesignColumn> = target.columns.iter().filter(|c| in_original(c)).collect();
        let added: Vec<&DesignColumn> = target.columns.iter().filter(|c| !in_original(c)).collect();
        let dropped: Vec<&DesignColumn> = original
            .columns
            .iter()
            .filter(|c| {
                !target
                    .columns
                    .iter()
                    .any(|t| t.original_name.as_deref() == Some(c.name.as_str()))
            })
            .collect();
        let from_of = |c: &DesignColumn| original_by_name[c.original_name.as_deref().unwrap()];
        let altered = kept.iter().any(|c| column_changed(from_of(c), c));

        let primary_key_changed = renamed(&original.primary_key) != target.primary_key;
        let original_indexes: HashMap<&str, IndexInfo> = original
            .indexes
            .iter()
            .map(|i| {
                (
                    i.name.as_str(),
                    IndexInfo {
                        columns: renamed(&i.columns),
                        ..i.clone()
                    },
                )
            })
            .collect();
        let target_indexes: HashMap<&str, &IndexInfo> = target
            .indexes
            .iter()
            .map(|i| (i.name.as_str(), i))
            .collect();
        let dropped_indexes: Vec<&IndexInfo> = original
            .indexes
            .iter()
            .filter(|i| {
                target_indexes.get(i.name.as_str()).copied()
                    != original_indexes.get(i.name.as_str())
            })
            .collect();
        let created_indexes: Vec<&IndexInfo> = target
            .indexes
            .iter()
            .filter(|i| original_indexes.get(i.name.as_str()) != Some(*i))
            .collect();
        // Unnamed keys match by what they link, so renaming a column they use changes their identity: compare renamed.
        let renamed_fk = |fk: &ForeignKeyInfo| ForeignKeyInfo {
            columns: renamed(&fk.columns),
            ..fk.clone()
        };
        let original_fks: HashMap<String, ForeignKeyInfo> = original
            .foreign_keys
            .iter()
            .map(|fk| {
                let r = renamed_fk(fk);
                (foreign_key_identity(&r), r)
            })
            .collect();
        let target_fks: HashMap<String, &ForeignKeyInfo> = target
            .foreign_keys
            .iter()
            .map(|fk| (foreign_key_identity(fk), fk))
            .collect();
        let dropped_fks: Vec<&ForeignKeyInfo> = original
            .foreign_keys
            .iter()
            .filter(|fk| {
                let id = foreign_key_identity(&renamed_fk(fk));
                !same_foreign_key(target_fks.get(&id).copied(), original_fks.get(&id))
            })
            .collect();
        let added_fks: Vec<&ForeignKeyInfo> = target
            .foreign_keys
            .iter()
            .filter(|fk| !same_foreign_key(original_fks.get(&foreign_key_identity(fk)), Some(fk)))
            .collect();
        let table_renamed = original.table.name != target.table.name;

        if self.engine == Engine::SQLite {
            let uses = |column: &DesignColumn| {
                original.primary_key.contains(&column.name)
                    || original
                        .indexes
                        .iter()
                        .any(|i| i.columns.contains(&column.name))
                    || original
                        .unique_constraints
                        .iter()
                        .any(|i| i.columns.contains(&column.name))
                    || original
                        .foreign_keys
                        .iter()
                        .any(|f| f.columns.contains(&column.name))
            };
            let needs_rebuild = altered
                || primary_key_changed
                || !dropped_fks.is_empty()
                || !added_fks.is_empty()
                || dropped.iter().any(|c| uses(c))
                || added.iter().any(|c| !sqlite_can_add(c, target));
            if needs_rebuild {
                return self.rebuild_sqlite_table(original, target, out);
            }
        }

        let table = &target.table;
        let before = &original.table;
        for fk in dropped_fks {
            self.drop_foreign_key(before, fk, out);
        }
        for index in dropped_indexes {
            for sql in self.drop_index(before, index) {
                out.add(DdlPhase::DropIndexes, sql);
            }
        }
        if primary_key_changed && !original.primary_key.is_empty() {
            self.drop_primary_key(original, out);
        }
        if table_renamed {
            out.add(DdlPhase::Tables, self.rename_table(before, &table.name));
        }
        for c in dropped {
            out.add(
                DdlPhase::Tables,
                format!(
                    "ALTER TABLE {} DROP COLUMN {}",
                    self.qualified(table),
                    self.q(&c.name)
                ),
            );
        }
        for column in &kept {
            self.alter_column(table, from_of(column), column, out);
        }
        for column in added {
            self.add_column(table, column, out);
        }
        if primary_key_changed && !target.primary_key.is_empty() {
            self.add_primary_key(target, out);
        }
        for index in created_indexes {
            out.add(DdlPhase::CreateIndexes, self.create_index(table, index));
        }
        for fk in added_fks {
            self.add_foreign_key(table, fk, out);
        }
    }

    fn alter_column(
        &self,
        table: &TableRef,
        from: &DesignColumn,
        to: &DesignColumn,
        out: &mut Output,
    ) {
        let name = self.q(&to.name);
        let target = self.qualified(table);
        let renamed = from.name != to.name;
        let type_changed =
            column_types::normalize(&from.type_) != column_types::normalize(&to.type_);
        let null_changed = from.nullable != to.nullable;
        let default_changed = column_types::normalize_default(from.default_value.as_deref())
            != column_types::normalize_default(to.default_value.as_deref());
        if from.auto_increment != to.auto_increment && self.engine != Engine::MySQL {
            out.warn(format!(
                "{}.{}: auto-increment can't be switched on an existing column here; recreate the column instead.",
                table.name, to.name
            ));
        }
        let set_default = |to: &DesignColumn| match &to.default_value {
            Some(d) => format!("SET DEFAULT {d}"),
            None => "DROP DEFAULT".to_owned(),
        };
        let null_word = if to.nullable { "DROP" } else { "SET" };
        match self.engine {
            Engine::MySQL => {
                // MySQL restates the whole column: CHANGE renames too, MODIFY keeps the name.
                if renamed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} CHANGE COLUMN {} {}",
                            self.q(&from.name),
                            self.column_definition(to, false)
                        ),
                    );
                } else if type_changed
                    || null_changed
                    || default_changed
                    || from.auto_increment != to.auto_increment
                {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} MODIFY COLUMN {}",
                            self.column_definition(to, false)
                        ),
                    );
                }
            }
            Engine::ClickHouse => {
                if renamed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} RENAME COLUMN {} TO {name}",
                            self.q(&from.name)
                        ),
                    );
                }
                if type_changed || null_changed || default_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} MODIFY COLUMN {}",
                            self.column_definition(to, false)
                        ),
                    );
                }
            }
            Engine::SqlServer => {
                if renamed {
                    let path = [
                        table.namespace.schema.as_deref(),
                        Some(&table.name),
                        Some(&from.name),
                    ]
                    .into_iter()
                    .flatten()
                    .collect::<Vec<_>>()
                    .join(".");
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "EXEC sp_rename {}, {}, 'COLUMN'",
                            self.dialect.literal(Some(&path), false),
                            self.dialect.literal(Some(&to.name), false)
                        ),
                    );
                }
                if type_changed || null_changed {
                    let nullable = if to.nullable { "NULL" } else { "NOT NULL" };
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {name} {} {nullable}",
                            to.type_
                        ),
                    );
                }
                if default_changed {
                    if from.default_value.is_some() {
                        out.warn(format!(
                            "{}.{}: SQL Server keeps defaults as named constraints; drop the old default constraint before this runs.",
                            table.name, to.name
                        ));
                    }
                    if let Some(d) = &to.default_value {
                        out.add(
                            DdlPhase::Tables,
                            format!("ALTER TABLE {target} ADD DEFAULT {d} FOR {name}"),
                        );
                    }
                }
            }
            Engine::SQLite => {
                // Anything but a rename rebuilds the table, decided before we get here.
                if renamed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} RENAME COLUMN {} TO {name}",
                            self.q(&from.name)
                        ),
                    );
                }
            }
            Engine::PostgreSQL | Engine::DuckDB => {
                if renamed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} RENAME COLUMN {} TO {name}",
                            self.q(&from.name)
                        ),
                    );
                }
                if type_changed {
                    // USING converts existing values the way a cast would, e.g. text holding numbers to integer.
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {name} TYPE {} USING {name}::{}",
                            to.type_, to.type_
                        ),
                    );
                }
                if null_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!("ALTER TABLE {target} ALTER COLUMN {name} {null_word} NOT NULL"),
                    );
                }
                if default_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {name} {}",
                            set_default(to)
                        ),
                    );
                }
            }
            Engine::Generic => {
                if renamed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {} RENAME TO {name}",
                            self.q(&from.name)
                        ),
                    );
                }
                if type_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {name} SET DATA TYPE {}",
                            to.type_
                        ),
                    );
                }
                if null_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!("ALTER TABLE {target} ALTER COLUMN {name} {null_word} NOT NULL"),
                    );
                }
                if default_changed {
                    out.add(
                        DdlPhase::Tables,
                        format!(
                            "ALTER TABLE {target} ALTER COLUMN {name} {}",
                            set_default(to)
                        ),
                    );
                }
            }
        }
        if null_changed && !to.nullable && to.default_value.is_none() {
            out.warn(format!(
                "{}.{} becomes NOT NULL: this fails if any row has NULL there.",
                table.name, to.name
            ));
        }
    }

    fn add_column(&self, table: &TableRef, column: &DesignColumn, out: &mut Output) {
        let keyword = if self.engine == Engine::SqlServer {
            "ADD"
        } else {
            "ADD COLUMN"
        };
        out.add(
            DdlPhase::Tables,
            format!(
                "ALTER TABLE {} {keyword} {}",
                self.qualified(table),
                self.column_definition(column, false)
            ),
        );
        if !column.nullable && column.default_value.is_none() && !column.auto_increment {
            out.warn(format!(
                "New column {}.{} is NOT NULL without a default: adding it fails if the table has rows.",
                table.name, column.name
            ));
        }
    }

    fn rename_table(&self, table: &TableRef, new_name: &str) -> String {
        let renamed = TableRef {
            name: new_name.to_owned(),
            ..table.clone()
        };
        match self.engine {
            Engine::MySQL | Engine::ClickHouse => {
                format!(
                    "RENAME TABLE {} TO {}",
                    self.qualified(table),
                    self.qualified(&renamed)
                )
            }
            Engine::SqlServer => {
                let path = [table.namespace.schema.as_deref(), Some(table.name.as_str())]
                    .into_iter()
                    .flatten()
                    .collect::<Vec<_>>()
                    .join(".");
                format!(
                    "EXEC sp_rename {}, {}",
                    self.dialect.literal(Some(&path), false),
                    self.dialect.literal(Some(new_name), false)
                )
            }
            // PostgreSQL and SQLite keep the schema; naming it again is an error.
            _ => format!(
                "ALTER TABLE {} RENAME TO {}",
                self.qualified(table),
                self.q(new_name)
            ),
        }
    }

    fn drop_primary_key(&self, original: &TableDesign, out: &mut Output) {
        let table = self.qualified(&original.table);
        match self.engine {
            Engine::MySQL | Engine::Generic => {
                out.add(DdlPhase::DropIndexes, format!("ALTER TABLE {table} DROP PRIMARY KEY"));
            }
            Engine::PostgreSQL => {
                let name = original
                    .primary_key_name
                    .clone()
                    .unwrap_or_else(|| format!("{}_pkey", original.table.name));
                out.add(DdlPhase::DropIndexes, format!("ALTER TABLE {table} DROP CONSTRAINT {}", self.q(&name)));
            }
            Engine::SqlServer => match &original.primary_key_name {
                Some(name) => {
                    out.add(DdlPhase::DropIndexes, format!("ALTER TABLE {table} DROP CONSTRAINT {}", self.q(name)));
                }
                None => out.warn(format!(
                    "{}: the primary key's constraint name is unknown; drop it by name before this runs.",
                    original.table.name
                )),
            },
            Engine::DuckDB | Engine::ClickHouse => out.warn(format!(
                "{} can't change the primary key of {} in place; recreate the table instead.",
                self.dialect.name(),
                original.table.name
            )),
            Engine::SQLite => {}
        }
    }

    fn add_primary_key(&self, target: &TableDesign, out: &mut Output) {
        if matches!(self.engine, Engine::DuckDB | Engine::ClickHouse) {
            return;
        }
        let name = target
            .primary_key_name
            .clone()
            .filter(|_| matches!(self.engine, Engine::SqlServer | Engine::PostgreSQL))
            .or_else(|| {
                (self.engine == Engine::SqlServer).then(|| format!("PK_{}", target.table.name))
            });
        let constraint = name.map_or_else(String::new, |n| format!("CONSTRAINT {} ", self.q(&n)));
        out.add(
            DdlPhase::Tables,
            format!(
                "ALTER TABLE {} ADD {constraint}PRIMARY KEY {}",
                self.qualified(&target.table),
                self.columns(&target.primary_key)
            ),
        );
    }

    fn create_index(&self, table: &TableRef, index: &IndexInfo) -> String {
        format!(
            "CREATE {}INDEX {} ON {} {}",
            if index.unique { "UNIQUE " } else { "" },
            self.q(&index.name),
            self.qualified(table),
            self.columns(&index.columns)
        )
    }

    fn drop_index(&self, table: &TableRef, index: &IndexInfo) -> Vec<String> {
        match self.engine {
            Engine::MySQL | Engine::SqlServer => {
                vec![format!(
                    "DROP INDEX {} ON {}",
                    self.q(&index.name),
                    self.qualified(table)
                )]
            }
            // A unique index can be a UNIQUE constraint's, which DROP INDEX refuses; dropping the constraint first
            // takes it with it, and IF EXISTS makes whichever statement doesn't apply a no-op.
            Engine::PostgreSQL => vec![
                format!(
                    "ALTER TABLE {} DROP CONSTRAINT IF EXISTS {}",
                    self.qualified(table),
                    self.q(&index.name)
                ),
                format!(
                    "DROP INDEX IF EXISTS {}",
                    self.qualified_index(table, &index.name)
                ),
            ],
            _ => vec![format!(
                "DROP INDEX {}",
                self.qualified_index(table, &index.name)
            )],
        }
    }

    fn add_foreign_key(&self, table: &TableRef, fk: &ForeignKeyInfo, out: &mut Output) {
        match self.engine {
            Engine::ClickHouse => out.warn(format!(
                "ClickHouse has no foreign keys: {} is left out.",
                fk.name
            )),
            Engine::DuckDB => out.warn(format!(
                "DuckDB can't add a foreign key to an existing table: {} is left out.",
                fk.name
            )),
            _ => out.add(
                DdlPhase::AddForeignKeys,
                format!(
                    "ALTER TABLE {} ADD {}",
                    self.qualified(table),
                    self.foreign_key_clause(table, fk, true)
                ),
            ),
        }
    }

    fn drop_foreign_key(&self, table: &TableRef, fk: &ForeignKeyInfo, out: &mut Output) {
        match self.engine {
            Engine::MySQL => out.add(
                DdlPhase::DropForeignKeys,
                format!(
                    "ALTER TABLE {} DROP FOREIGN KEY {}",
                    self.qualified(table),
                    self.q(&fk.name)
                ),
            ),
            Engine::ClickHouse => {}
            Engine::DuckDB => out.warn(format!(
                "DuckDB can't drop a foreign key from an existing table: {} stays.",
                fk.name
            )),
            _ => out.add(
                DdlPhase::DropForeignKeys,
                format!(
                    "ALTER TABLE {} DROP CONSTRAINT {}",
                    self.qualified(table),
                    self.q(&fk.name)
                ),
            ),
        }
    }

    /// SQLite's ALTER TABLE only adds, renames and drops plain columns. Anything else follows its documented recipe:
    /// create the new table under a temporary name, copy the rows, drop the old one, rename, and bring back indexes
    /// and triggers — with foreign keys off, or dropping a referenced table would cascade.
    fn rebuild_sqlite_table(&self, original: &TableDesign, target: &TableDesign, out: &mut Output) {
        let temporary = TableRef {
            name: format!("_{}_new", target.table.name),
            ..target.table.clone()
        };
        out.add(DdlPhase::Prologue, "PRAGMA foreign_keys = OFF".to_owned());
        let without_indexes = TableDesign {
            indexes: vec![],
            ..target.clone()
        };
        self.create_table(&without_indexes, out, &temporary);
        let copied: Vec<&DesignColumn> = target
            .columns
            .iter()
            .filter(|c| {
                c.original_name
                    .as_ref()
                    .is_some_and(|o| original.columns.iter().any(|oc| &oc.name == o))
            })
            .collect();
        if !copied.is_empty() {
            let into = copied
                .iter()
                .map(|c| self.q(&c.name))
                .collect::<Vec<_>>()
                .join(", ");
            let from = copied
                .iter()
                .map(|c| self.q(c.original_name.as_ref().unwrap()))
                .collect::<Vec<_>>()
                .join(", ");
            out.add(
                DdlPhase::Tables,
                format!(
                    "INSERT INTO {} ({into}) SELECT {from} FROM {}",
                    self.qualified(&temporary),
                    self.qualified(&original.table)
                ),
            );
        }
        out.add(
            DdlPhase::Tables,
            format!("DROP TABLE {}", self.qualified(&original.table)),
        );
        out.add(
            DdlPhase::Tables,
            format!(
                "ALTER TABLE {} RENAME TO {}",
                self.qualified(&temporary),
                self.q(&target.table.name)
            ),
        );
        for index in &target.indexes {
            out.add(
                DdlPhase::CreateIndexes,
                self.create_index(&target.table, index),
            );
        }
        for trigger in &target.triggers {
            out.add(DdlPhase::CreateIndexes, trigger.clone());
        }
        out.add(DdlPhase::Epilogue, "PRAGMA foreign_keys = ON".to_owned());
        out.warn(format!(
            "SQLite can't make these changes to {} in place: the table is rebuilt and its rows copied. Views that use it may need recreating.",
            target.table.name
        ));
        for c in target.columns.iter().filter(|c| {
            c.original_name.is_none()
                && !c.nullable
                && c.default_value.is_none()
                && !c.auto_increment
        }) {
            out.warn(format!(
                "New column {}.{} is NOT NULL without a default: copying existing rows fails if there are any.",
                target.table.name, c.name
            ));
        }
    }

    fn column_definition(&self, column: &DesignColumn, inline_primary_key: bool) -> String {
        let engine = self.engine;
        let mut out = format!("{} ", self.q(&column.name));
        let type_ = kt::trim(&column.type_);
        if engine == Engine::ClickHouse && column.nullable && !type_.starts_with("Nullable(") {
            out.push_str(&format!("Nullable({type_})"));
        } else {
            out.push_str(type_);
        }
        if inline_primary_key {
            out.push_str(" PRIMARY KEY AUTOINCREMENT");
        }
        if column.auto_increment && !inline_primary_key {
            match engine {
                Engine::PostgreSQL if !type_.to_lowercase().contains("serial") => {
                    out.push_str(" GENERATED BY DEFAULT AS IDENTITY");
                }
                Engine::SqlServer => out.push_str(" IDENTITY(1,1)"),
                Engine::Generic => out.push_str(" GENERATED BY DEFAULT AS IDENTITY"),
                _ => {}
            }
        }
        if let Some(default) = &column.default_value
            && !(column.auto_increment && engine == Engine::PostgreSQL)
        {
            out.push_str(" DEFAULT ");
            out.push_str(kt::trim(default));
        }
        if engine != Engine::ClickHouse {
            if !column.nullable {
                out.push_str(" NOT NULL");
            } else if matches!(engine, Engine::MySQL | Engine::SqlServer) {
                out.push_str(" NULL");
            }
        }
        if column.auto_increment && engine == Engine::MySQL {
            out.push_str(" AUTO_INCREMENT");
        }
        out
    }

    fn foreign_key_clause(&self, table: &TableRef, fk: &ForeignKeyInfo, named: bool) -> String {
        let mut out = String::new();
        if named && !kt::is_blank(&fk.name) {
            out.push_str(&format!("CONSTRAINT {} ", self.q(&fk.name)));
        }
        let target = &fk.referenced_table;
        let same_namespace = target.namespace == table.namespace
            || target.namespace.catalog.as_deref().unwrap_or("").is_empty()
                && target.namespace.schema.as_deref().unwrap_or("").is_empty();
        out.push_str(&format!(
            "FOREIGN KEY {} REFERENCES ",
            self.columns(&fk.columns)
        ));
        if same_namespace {
            out.push_str(&self.qualified(&TableRef {
                namespace: table.namespace.clone(),
                name: target.name.clone(),
            }));
        } else {
            out.push_str(&self.qualified(target));
        }
        out.push(' ');
        out.push_str(&self.columns(&fk.referenced_columns));
        if let Some(on_update) = &fk.on_update {
            out.push_str(&format!(" ON UPDATE {on_update}"));
        }
        if let Some(on_delete) = &fk.on_delete {
            out.push_str(&format!(" ON DELETE {on_delete}"));
        }
        out
    }
}

fn column_changed(from: &DesignColumn, to: &DesignColumn) -> bool {
    column_types::normalize(&from.type_) != column_types::normalize(&to.type_)
        || from.nullable != to.nullable
        || column_types::normalize_default(from.default_value.as_deref())
            != column_types::normalize_default(to.default_value.as_deref())
        || from.auto_increment != to.auto_increment
}

fn same_foreign_key(a: Option<&ForeignKeyInfo>, b: Option<&ForeignKeyInfo>) -> bool {
    let (Some(a), Some(b)) = (a, b) else {
        return false;
    };
    let action = |x: &Option<String>| x.clone().unwrap_or_else(|| "NO ACTION".to_owned());
    a.columns == b.columns
        && a.referenced_table.name == b.referenced_table.name
        && a.referenced_columns == b.referenced_columns
        && action(&a.on_update) == action(&b.on_update)
        && action(&a.on_delete) == action(&b.on_delete)
}

/// What `ALTER TABLE ADD COLUMN` takes in SQLite; anything else needs the table rebuilt.
fn sqlite_can_add(column: &DesignColumn, target: &TableDesign) -> bool {
    if target.primary_key.contains(&column.name) || column.auto_increment {
        return false;
    }
    if target
        .unique_constraints
        .iter()
        .any(|u| u.columns.contains(&column.name))
    {
        return false;
    }
    if target
        .foreign_keys
        .iter()
        .any(|f| f.columns.contains(&column.name))
    {
        return false;
    }
    let default = column.default_value.as_deref().map(kt::trim);
    // Expressions in parentheses and CURRENT_* aren't constant, which ADD COLUMN requires.
    if let Some(d) = default
        && (d.starts_with('(') || d.to_uppercase().starts_with("CURRENT_"))
    {
        return false;
    }
    column.nullable || default.is_some()
}

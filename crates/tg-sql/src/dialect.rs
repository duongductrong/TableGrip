//! Everything that differs between database engines: SQL syntax, quoting and the SQL the app generates
//! (`dialect/SqlDialect.kt`). Binding values to statements and reading catalogs belong to the drivers (tg-driver);
//! [`SqlDialect::list_namespaces`] keeps the part of the latter that is pure: which namespaces to show.

use regex::Regex;
use std::sync::LazyLock;

use crate::decimal::Decimal;
use crate::filter::values::{self as filter_values, ParamValue, SqlParam};
use crate::filter::{SelectFunction, SqlProjection};
use crate::kt::eq_ignore_case;
use crate::model::{EngineFamily, Namespace, TableRef};
use crate::syntax::SqlSyntax;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SortDirection {
    Asc,
    Desc,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SortColumn {
    pub column: String,
    pub direction: SortDirection,
}

impl SortColumn {
    pub fn new(column: &str, direction: SortDirection) -> Self {
        Self {
            column: column.to_owned(),
            direction,
        }
    }
}

/// A unit dates are rounded down to, for charts grouped on the server and the select list's truncate.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TimeBucket {
    Hour,
    Day,
    Week,
    Month,
    Quarter,
    Year,
}

impl TimeBucket {
    pub const ALL: &[TimeBucket] = &[
        Self::Hour,
        Self::Day,
        Self::Week,
        Self::Month,
        Self::Quarter,
        Self::Year,
    ];

    /// The Kotlin enum name, which is also its label.
    pub fn name(self) -> &'static str {
        match self {
            Self::Hour => "Hour",
            Self::Day => "Day",
            Self::Week => "Week",
            Self::Month => "Month",
            Self::Quarter => "Quarter",
            Self::Year => "Year",
        }
    }

    pub fn label(self) -> &'static str {
        self.name()
    }

    /// The bucket named `text`, any case.
    pub fn of(text: &str) -> Option<Self> {
        Self::ALL
            .iter()
            .copied()
            .find(|b| eq_ignore_case(b.name(), text))
    }
}

/// The SQL dialect of an [`EngineFamily`]; an engine that strays from its family's SQL has a variant of its own.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SqlDialect {
    PostgreSQL,
    /// CockroachDB speaks PostgreSQL's SQL and catalog, mostly; what differs is in its structure reader.
    CockroachDB,
    MySQL,
    SQLite,
    /// Standard SQL: H2, HSQLDB, Derby, and drivers the user brings (no driver in the Rust port, decision D1).
    Generic,
    /// T-SQL: bracket quoting, OFFSET/FETCH paging (which needs an ORDER BY), schemas inside one database.
    SqlServer,
    /// ClickHouse: databases as namespaces, ILIKE and RE2 regexes, no transactions.
    ClickHouse,
    /// DuckDB: PostgreSQL-like SQL in an embedded engine; namespaces are schemas, `main` by default.
    DuckDB,
}

static PLAIN: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z_][A-Za-z0-9_$]*$").unwrap());
static PLAIN_LOWER: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[a-z_][a-z0-9_$]*$").unwrap());

impl SqlDialect {
    pub const ALL: &[SqlDialect] = &[
        Self::PostgreSQL,
        Self::CockroachDB,
        Self::MySQL,
        Self::SQLite,
        Self::Generic,
        Self::SqlServer,
        Self::ClickHouse,
        Self::DuckDB,
    ];

    pub const fn of(family: EngineFamily) -> Option<Self> {
        Some(match family {
            EngineFamily::PostgreSQL => Self::PostgreSQL,
            EngineFamily::MySQL => Self::MySQL,
            EngineFamily::SQLite => Self::SQLite,
            EngineFamily::Generic => Self::Generic,
            EngineFamily::SqlServer => Self::SqlServer,
            EngineFamily::ClickHouse => Self::ClickHouse,
            EngineFamily::DuckDB => Self::DuckDB,
            EngineFamily::Redis | EngineFamily::MongoDB | EngineFamily::Etcd => return None,
        })
    }

    /// Kotlin's `toString()`.
    pub fn name(self) -> &'static str {
        match self {
            Self::PostgreSQL => "PostgreSQL",
            Self::CockroachDB => "CockroachDB",
            Self::MySQL => "MySQL",
            Self::SQLite => "SQLite",
            Self::Generic => "Generic",
            Self::SqlServer => "SQL Server",
            Self::ClickHouse => "ClickHouse",
            Self::DuckDB => "DuckDB",
        }
    }

    pub fn syntax(self) -> SqlSyntax {
        match self {
            Self::PostgreSQL | Self::CockroachDB | Self::DuckDB => SqlSyntax::POSTGRESQL,
            Self::MySQL => SqlSyntax::MYSQL,
            Self::SQLite => SqlSyntax::SQLITE,
            Self::Generic => SqlSyntax::STANDARD,
            Self::SqlServer => SqlSyntax::TSQL,
            Self::ClickHouse => SqlSyntax::CLICKHOUSE,
        }
    }

    fn is_postgres(self) -> bool {
        matches!(self, Self::PostgreSQL | Self::CockroachDB)
    }

    /// Table types shown in the explorer (as JDBC names them), with whether they are views.
    pub fn table_types(self) -> &'static [(&'static str, bool)] {
        if self.is_postgres() {
            &[
                ("TABLE", false),
                ("PARTITIONED TABLE", false),
                ("VIEW", true),
                ("MATERIALIZED VIEW", true),
            ]
        } else {
            &[("TABLE", false), ("VIEW", true)]
        }
    }

    pub fn quote_identifier(self, name: &str) -> String {
        match self {
            Self::MySQL => format!("`{}`", name.replace('`', "``")),
            Self::SqlServer => format!("[{}]", name.replace(']', "]]")),
            Self::ClickHouse => format!("`{}`", name.replace('\\', "\\\\").replace('`', "\\`")),
            _ => format!("\"{}\"", name.replace('"', "\"\"")),
        }
    }

    fn escape_string(self, value: &str) -> String {
        match self {
            Self::MySQL => value.replace('\\', "\\\\").replace('\'', "''"),
            Self::ClickHouse => value.replace('\\', "\\\\").replace('\'', "\\'"),
            _ => value.replace('\'', "''"),
        }
    }

    /// A SQL literal for display or copy-as-SQL; execution always binds parameters instead.
    pub fn literal(self, value: Option<&str>, numeric: bool) -> String {
        match value {
            None => "NULL".to_owned(),
            Some(v) if numeric && Decimal::parse(v).is_some() => v.to_owned(),
            Some(v) => format!("'{}'", self.escape_string(v)),
        }
    }

    /// A typed filter value as a literal, for previews.
    pub fn literal_param(self, param: &SqlParam) -> String {
        match &param.value {
            ParamValue::Long(_) | ParamValue::Decimal(_) => filter_values::format(&param.value),
            ParamValue::Bool(b) if self == Self::SqlServer => {
                (if *b { "1" } else { "0" }).to_owned()
            }
            ParamValue::Bool(b) => (if *b { "TRUE" } else { "FALSE" }).to_owned(),
            other => self.literal(Some(&filter_values::format(other)), false),
        }
    }

    /// The SQL around a bound LIKE pattern (`!` escapes wildcards) that `expression` must match, as the text before
    /// and after the `?`. Case-insensitive unless `case_sensitive`.
    pub fn text_match(
        self,
        expression: &str,
        case_sensitive: bool,
        negated: bool,
    ) -> (String, String) {
        let not = if negated { "NOT " } else { "" };
        let ilike = if case_sensitive { "LIKE" } else { "ILIKE" };
        match self {
            Self::PostgreSQL | Self::CockroachDB | Self::DuckDB => (
                format!("{expression} {not}{ilike} "),
                " ESCAPE '!'".to_owned(),
            ),
            Self::ClickHouse => (format!("{expression} {not}{ilike} "), String::new()),
            Self::MySQL if case_sensitive => (
                format!("CAST({expression} AS BINARY) {not}LIKE CAST("),
                " AS BINARY) ESCAPE '!'".to_owned(),
            ),
            Self::SqlServer if case_sensitive => (
                format!("{expression} COLLATE Latin1_General_CS_AS {not}LIKE "),
                " ESCAPE '!'".to_owned(),
            ),
            Self::SQLite if case_sensitive => (format!("{expression} {not}GLOB "), String::new()),
            Self::SQLite => (format!("{expression} {not}LIKE "), " ESCAPE '!'".to_owned()),
            _ if case_sensitive => (format!("{expression} {not}LIKE "), " ESCAPE '!'".to_owned()),
            _ => (
                format!("LOWER({expression}) {not}LIKE LOWER("),
                ") ESCAPE '!'".to_owned(),
            ),
        }
    }

    /// The value to bind for [`Self::text_match`], from a LIKE pattern.
    pub fn text_pattern(self, like_pattern: &str, case_sensitive: bool) -> String {
        match self {
            Self::SQLite if case_sensitive => {
                // GLOB: `*` and `?` are the wildcards, and `[c]` matches a character literally.
                let chars: Vec<char> = like_pattern.chars().collect();
                let mut glob = String::new();
                let literal = |glob: &mut String, c: char| {
                    if "*?[".contains(c) {
                        glob.push('[');
                        glob.push(c);
                        glob.push(']');
                    } else {
                        glob.push(c);
                    }
                };
                let mut i = 0;
                while i < chars.len() {
                    match chars[i] {
                        '!' => {
                            if let Some(&next) = chars.get(i + 1) {
                                literal(&mut glob, next);
                                i += 1;
                            }
                        }
                        '%' => glob.push('*'),
                        '_' => glob.push('?'),
                        c => literal(&mut glob, c),
                    }
                    i += 1;
                }
                glob
            }
            Self::ClickHouse => {
                // ClickHouse's LIKE has no ESCAPE clause: wildcards are escaped with a backslash.
                let chars: Vec<char> = like_pattern.chars().collect();
                let mut out = String::new();
                let mut i = 0;
                while i < chars.len() {
                    let c = chars[i];
                    if c == '!' && i + 1 < chars.len() {
                        out.push('\\');
                        out.push(chars[i + 1]);
                        i += 2;
                    } else {
                        if c == '\\' {
                            out.push('\\');
                        }
                        out.push(c);
                        i += 1;
                    }
                }
                out
            }
            _ => like_pattern.to_owned(),
        }
    }

    /// Whether filters can match regular expressions.
    pub fn supports_regex(self) -> bool {
        !matches!(self, Self::SQLite | Self::Generic | Self::SqlServer)
    }

    /// Whether [`Self::regex_match`] honours case sensitivity; otherwise the column's collation decides.
    pub fn regex_honours_case(self) -> bool {
        matches!(
            self,
            Self::PostgreSQL | Self::CockroachDB | Self::ClickHouse | Self::DuckDB
        )
    }

    /// Like [`Self::text_match`] for a regular expression, or None if the engine has none.
    pub fn regex_match(
        self,
        expression: &str,
        case_sensitive: bool,
        negated: bool,
    ) -> Option<(String, String)> {
        let not = if negated { "NOT " } else { "" };
        Some(match self {
            Self::SQLite | Self::Generic | Self::SqlServer => return None,
            Self::PostgreSQL | Self::CockroachDB => (
                format!(
                    "{expression} {}~{} ",
                    if negated { "!" } else { "" },
                    if case_sensitive { "" } else { "*" }
                ),
                String::new(),
            ),
            Self::ClickHouse => (
                format!(
                    "{not}match({expression}, {}",
                    if case_sensitive {
                        ""
                    } else {
                        "concat('(?i)', "
                    }
                ),
                (if case_sensitive { ")" } else { "))" }).to_owned(),
            ),
            Self::DuckDB => (
                format!("{not}regexp_matches({expression}, "),
                (if case_sensitive { ")" } else { ", 'i')" }).to_owned(),
            ),
            Self::MySQL => (format!("{expression} {not}REGEXP "), String::new()),
        })
    }

    /// The namespaces to show, from the schema and catalog names the driver read (None where reading them failed):
    /// system ones left out, sorted. None when the dialect needs names that couldn't be read; only the generic dialect
    /// tolerates failed reads.
    pub fn list_namespaces(
        self,
        schemas: Option<Vec<String>>,
        catalogs: Option<Vec<String>>,
    ) -> Option<Vec<Namespace>> {
        let sorted = |mut names: Vec<String>, as_schema: bool| -> Vec<Namespace> {
            names.sort();
            names
                .into_iter()
                .map(|n| {
                    if as_schema {
                        Namespace {
                            catalog: None,
                            schema: Some(n),
                        }
                    } else {
                        Namespace {
                            catalog: Some(n),
                            schema: None,
                        }
                    }
                })
                .collect()
        };
        let keep = |names: Option<Vec<String>>, drop: &dyn Fn(&str) -> bool| -> Vec<String> {
            names
                .unwrap_or_default()
                .into_iter()
                .filter(|n| !drop(n))
                .collect()
        };
        let needed = match self {
            Self::PostgreSQL | Self::CockroachDB | Self::SqlServer | Self::DuckDB => {
                schemas.is_some()
            }
            Self::MySQL => catalogs.is_some(),
            Self::ClickHouse => {
                schemas.as_ref().is_some_and(|s| !s.is_empty())
                    || schemas.is_some() && catalogs.is_some()
            }
            Self::SQLite | Self::Generic => true,
        };
        if !needed {
            return None;
        }
        Some(match self {
            Self::PostgreSQL | Self::CockroachDB => sorted(
                keep(schemas, &|n| {
                    n == "pg_catalog"
                        || n == "information_schema"
                        || n.starts_with("pg_toast")
                        || n.starts_with("pg_temp")
                }),
                true,
            ),
            Self::MySQL => sorted(
                keep(catalogs, &|n| {
                    ["information_schema", "performance_schema", "mysql", "sys"].contains(&n)
                }),
                false,
            ),
            Self::SQLite => vec![Namespace::default()],
            Self::SqlServer => sorted(
                keep(schemas, &|n| {
                    ["sys", "INFORMATION_SCHEMA", "guest"].contains(&n) || n.starts_with("db_")
                }),
                true,
            ),
            Self::DuckDB => {
                let mut names = keep(schemas, &|n| n == "information_schema" || n == "pg_catalog");
                names.sort();
                names.dedup();
                sorted(names, true)
            }
            Self::ClickHouse | Self::Generic => {
                let system: &dyn Fn(&str) -> bool = if self == Self::ClickHouse {
                    &|n| ["system", "INFORMATION_SCHEMA", "information_schema"].contains(&n)
                } else {
                    &|n| {
                        eq_ignore_case(n, "INFORMATION_SCHEMA")
                            || ["NULLID", "SQLJ", "SYSTEM_LOBS"]
                                .contains(&n.to_uppercase().as_str())
                            || n.get(..3).is_some_and(|p| eq_ignore_case(p, "SYS"))
                    }
                };
                let schemas = keep(schemas, system);
                if !schemas.is_empty() {
                    return Some(sorted(schemas, true));
                }
                let catalogs = keep(catalogs, system);
                if !catalogs.is_empty() || self == Self::ClickHouse {
                    return Some(sorted(catalogs, false));
                }
                vec![Namespace::default()]
            }
        })
    }

    /// The namespace unqualified names resolve to, among `names`, for a connection to `database`.
    pub fn default_namespace(self, names: &[String], database: &str) -> Option<String> {
        let named = |n: &str| names.iter().any(|x| x == n).then(|| n.to_owned());
        let first = || names.first().cloned();
        match self {
            Self::PostgreSQL | Self::CockroachDB => named("public").or_else(first),
            Self::MySQL => named(database).or_else(first),
            Self::SqlServer => named("dbo").or_else(first),
            Self::DuckDB => named("main").or_else(first),
            Self::ClickHouse => named(database).or_else(|| named("default")).or_else(first),
            Self::Generic => names
                .iter()
                .find(|n| eq_ignore_case(n, "PUBLIC"))
                .or_else(|| names.iter().find(|n| eq_ignore_case(n, "APP")))
                .cloned()
                .or_else(first),
            Self::SQLite => first(),
        }
    }

    /// Statements that show `sql`'s plan instead of running it.
    pub fn explain(self, sql: &str) -> Vec<String> {
        match self {
            // Plain EXPLAIN shows bytecode; this is the readable plan.
            Self::SQLite => vec![format!("EXPLAIN QUERY PLAN {sql}")],
            // Plans come back as rows while SHOWPLAN is on, which has to be a batch of its own.
            Self::SqlServer => vec![
                "SET SHOWPLAN_TEXT ON".to_owned(),
                sql.to_owned(),
                "SET SHOWPLAN_TEXT OFF".to_owned(),
            ],
            _ => vec![format!("EXPLAIN {sql}")],
        }
    }

    /// Functions completion offers besides the standard ones.
    pub fn functions(self) -> &'static [&'static str] {
        match self {
            Self::PostgreSQL => &[
                "now",
                "date_trunc",
                "to_char",
                "string_agg",
                "array_agg",
                "jsonb_build_object",
                "generate_series",
                "gen_random_uuid",
            ],
            Self::CockroachDB => &[
                "now",
                "date_trunc",
                "to_char",
                "string_agg",
                "array_agg",
                "jsonb_build_object",
                "generate_series",
                "gen_random_uuid",
                "unique_rowid",
                "gen_random_ulid",
                "crdb_internal.cluster_id",
            ],
            Self::MySQL => &[
                "now",
                "date_format",
                "group_concat",
                "ifnull",
                "json_object",
                "json_extract",
                "uuid",
            ],
            Self::SQLite => &[
                "datetime",
                "strftime",
                "group_concat",
                "ifnull",
                "json_extract",
                "random",
            ],
            Self::SqlServer => &[
                "getdate",
                "sysdatetime",
                "dateadd",
                "datediff",
                "format",
                "isnull",
                "string_agg",
                "newid",
                "convert",
                "iif",
            ],
            Self::ClickHouse => &[
                "now",
                "today",
                "toDate",
                "toStartOfDay",
                "formatDateTime",
                "uniq",
                "groupArray",
                "arrayJoin",
                "JSONExtractString",
                "countIf",
            ],
            Self::DuckDB => &[
                "now",
                "date_trunc",
                "strftime",
                "string_agg",
                "list",
                "unnest",
                "read_parquet",
                "read_csv_auto",
                "regexp_matches",
            ],
            Self::Generic => &[],
        }
    }

    /// Names that need no quoting, reserved words aside (PostgreSQL folds unquoted names to lower case, so anything
    /// else needs quotes there). Anchored at both ends.
    pub fn plain_identifier(self) -> &'static Regex {
        if self.is_postgres() {
            &PLAIN_LOWER
        } else {
            &PLAIN
        }
    }

    pub fn is_plain_identifier(self, name: &str) -> bool {
        self.plain_identifier().is_match(name)
    }

    pub fn qualified_name(self, table: &TableRef) -> String {
        [
            table.namespace.catalog.as_deref(),
            table.namespace.schema.as_deref(),
            Some(table.name.as_str()),
        ]
        .into_iter()
        .flatten()
        .map(|part| self.quote_identifier(part))
        .collect::<Vec<_>>()
        .join(".")
    }

    pub fn select_page(
        self,
        table: &TableRef,
        filter: Option<&str>,
        order_by: &[SortColumn],
        limit: i32,
        offset: i64,
        projection: Option<&SqlProjection>,
    ) -> String {
        let all = self.select_all(table, filter, order_by, projection);
        match self {
            // SQL:2008 paging, which every engine in this family speaks (LIMIT is a common but non-standard extension).
            Self::Generic => format!("{all} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY"),
            Self::SqlServer => {
                // With DISTINCT, ORDER BY may only name what's selected, so the stand-in order is the first column.
                let order = if !order_by.is_empty() {
                    ""
                } else if projection.is_some_and(|p| p.distinct) {
                    " ORDER BY 1"
                } else {
                    " ORDER BY (SELECT NULL)"
                };
                format!("{all}{order} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY")
            }
            _ => format!("{all} LIMIT {limit} OFFSET {offset}"),
        }
    }

    /// The table's rows, as `projection` picks their columns (all of them when None).
    pub fn select_all(
        self,
        table: &TableRef,
        filter: Option<&str>,
        order_by: &[SortColumn],
        projection: Option<&SqlProjection>,
    ) -> String {
        let mut sql = format!(
            "SELECT {} FROM {}",
            projection.map_or_else(|| "*".to_owned(), SqlProjection::clause),
            self.qualified_name(table)
        );
        if let Some(filter) = filter.filter(|f| !crate::kt::is_blank(f)) {
            sql.push_str(" WHERE ");
            sql.push_str(filter);
        }
        if !order_by.is_empty() {
            sql.push_str(" ORDER BY ");
            let order: Vec<String> = order_by
                .iter()
                .map(|s| {
                    let dir = match s.direction {
                        SortDirection::Asc => "ASC",
                        SortDirection::Desc => "DESC",
                    };
                    format!("{} {dir}", self.quote_identifier(&s.column))
                })
                .collect();
            sql.push_str(&order.join(", "));
        }
        sql
    }

    /// `expression` as text, for prefix matching any column type.
    pub fn cast_to_text(self, expression: &str) -> String {
        match self {
            Self::MySQL => format!("CAST({expression} AS CHAR)"),
            Self::Generic => format!("CAST({expression} AS VARCHAR(4000))"),
            Self::SqlServer => format!("CAST({expression} AS NVARCHAR(4000))"),
            Self::ClickHouse => format!("toString({expression})"),
            Self::DuckDB => format!("CAST({expression} AS VARCHAR)"),
            _ => format!("CAST({expression} AS TEXT)"),
        }
    }

    /// The value bound to [`Self::sample_values`]' `?` to match values starting with `prefix` literally.
    pub fn sample_pattern(self, prefix: &str) -> String {
        if self == Self::ClickHouse {
            return prefix
                .replace('\\', "\\\\")
                .replace('%', "\\%")
                .replace('_', "\\_")
                + "%";
        }
        // '!' escapes LIKE wildcards: unlike '\\' it means the same in every dialect's string literals.
        prefix
            .replace('!', "!!")
            .replace('%', "!%")
            .replace('_', "!_")
            + "%"
    }

    /// Up to `limit` distinct non-null values of `column` starting with a bound `?` prefix, scanning at most `scan`
    /// rows so suggestions stay cheap on large tables.
    pub fn sample_values(self, table: &TableRef, column: &str, limit: i32, scan: i32) -> String {
        let col = self.quote_identifier(column);
        let table = self.qualified_name(table);
        let text = self.cast_to_text(&col);
        match self {
            Self::Generic => format!(
                "SELECT DISTINCT v FROM (SELECT {col} AS v FROM {table} WHERE {col} IS NOT NULL AND {text} LIKE ? ESCAPE '!' FETCH FIRST {scan} ROWS ONLY) s ORDER BY v FETCH FIRST {limit} ROWS ONLY"
            ),
            Self::SqlServer => format!(
                "SELECT DISTINCT TOP {limit} v FROM (SELECT TOP {scan} {col} AS v FROM {table} WHERE {col} IS NOT NULL AND {text} LIKE ? ESCAPE '!') s ORDER BY v"
            ),
            Self::ClickHouse => format!(
                "SELECT DISTINCT v FROM (SELECT {col} AS v FROM {table} WHERE {col} IS NOT NULL AND {text} LIKE ? LIMIT {scan}) s ORDER BY v LIMIT {limit}"
            ),
            _ => format!(
                "SELECT DISTINCT v FROM (SELECT {col} AS v FROM {table} WHERE {col} IS NOT NULL AND {text} LIKE ? ESCAPE '!' LIMIT {scan}) s ORDER BY v LIMIT {limit}"
            ),
        }
    }

    /// Rows [`Self::select_all`] returns; with DISTINCT, the distinct ones.
    pub fn count_rows(
        self,
        table: &TableRef,
        filter: Option<&str>,
        projection: Option<&SqlProjection>,
    ) -> String {
        let filter = filter.filter(|f| !crate::kt::is_blank(f));
        let where_ = filter.map_or_else(String::new, |f| format!(" WHERE {f}"));
        let table = self.qualified_name(table);
        match projection {
            Some(p) if p.distinct => {
                format!(
                    "SELECT COUNT(*) FROM (SELECT {} FROM {table}{where_}) d",
                    p.clause()
                )
            }
            _ => format!("SELECT COUNT(*) FROM {table}{where_}"),
        }
    }

    /// `function` applied to `expression` in a select list, with its `arguments` already SQL (a [`TimeBucket`] name
    /// for truncate); None when the engine has no such function.
    pub fn select_function(
        self,
        function: SelectFunction,
        expression: &str,
        arguments: &[String],
    ) -> Option<String> {
        use SelectFunction as F;
        let args = arguments.join(", ");
        let single = arguments.first().map_or("", String::as_str);
        let e = expression;
        let own = match (self, function) {
            (Self::PostgreSQL | Self::CockroachDB, F::Round) => {
                Some(format!("ROUND(CAST({e} AS NUMERIC), {single})"))
            }
            (Self::MySQL, F::Length) => Some(format!("CHAR_LENGTH({e})")),
            (Self::SQLite, F::Substring) => Some(format!("substr({e}, {args})")),
            (Self::SqlServer, F::Length) => Some(format!("LEN({e})")),
            (Self::SqlServer, F::Trim) => Some(format!("LTRIM(RTRIM({e}))")),
            (Self::ClickHouse, F::Upper) => Some(format!("upperUTF8({e})")),
            (Self::ClickHouse, F::Lower) => Some(format!("lowerUTF8({e})")),
            (Self::ClickHouse, F::Trim) => Some(format!("trimBoth({e})")),
            (Self::ClickHouse, F::Length) => Some(format!("lengthUTF8({e})")),
            (Self::ClickHouse, F::Substring) => Some(format!("substringUTF8({e}, {args})")),
            (Self::ClickHouse, F::Ceil) => Some(format!("ceil({e})")),
            _ => None,
        };
        if own.is_some() {
            return own;
        }
        Some(match function {
            F::None => e.to_owned(),
            F::Upper => format!("UPPER({e})"),
            F::Lower => format!("LOWER({e})"),
            F::Trim => format!("TRIM({e})"),
            F::Length => format!("LENGTH({e})"),
            F::Substring => format!("SUBSTRING({e}, {args})"),
            F::Round => format!("ROUND({e}, {single})"),
            F::Abs => format!("ABS({e})"),
            F::Floor => format!("FLOOR({e})"),
            F::Ceil => format!("CEILING({e})"),
            F::Truncate => return TimeBucket::of(single).and_then(|b| self.time_bucket(e, b)),
            F::Coalesce => format!("COALESCE({e}, {single})"),
            F::AsText => self.cast_to_text(e),
        })
    }

    /// `expression`, a date or timestamp, rounded down to the start of its `bucket` (weeks start on Monday), for
    /// charts grouped on the server; None when the engine's date functions aren't known.
    pub fn time_bucket(self, expression: &str, bucket: TimeBucket) -> Option<String> {
        use TimeBucket as B;
        let e = expression;
        Some(match self {
            Self::PostgreSQL | Self::CockroachDB | Self::DuckDB => {
                format!("date_trunc('{}', {e})", bucket.name().to_lowercase())
            }
            Self::MySQL => match bucket {
                B::Hour => format!(
                    "STR_TO_DATE(DATE_FORMAT({e}, '%Y-%m-%d %H:00:00'), '%Y-%m-%d %H:%i:%s')"
                ),
                B::Day => format!("DATE({e})"),
                B::Week => format!("DATE(DATE_SUB({e}, INTERVAL WEEKDAY({e}) DAY))"),
                B::Month => format!("DATE(DATE_FORMAT({e}, '%Y-%m-01'))"),
                B::Quarter => {
                    format!("MAKEDATE(YEAR({e}), 1) + INTERVAL (QUARTER({e}) - 1) QUARTER")
                }
                B::Year => format!("MAKEDATE(YEAR({e}), 1)"),
            },
            // SQLite has no date type: dates are text, and these return text in the same ISO form.
            Self::SQLite => match bucket {
                B::Hour => format!("strftime('%Y-%m-%d %H:00:00', {e})"),
                B::Day => format!("date({e})"),
                // To the next Sunday (or the same day), then back to that week's Monday.
                B::Week => format!("date({e}, 'weekday 0', '-6 days')"),
                B::Month => format!("strftime('%Y-%m-01', {e})"),
                B::Quarter => format!(
                    "printf('%s-%02d-01', strftime('%Y', {e}), (CAST(strftime('%m', {e}) AS INTEGER) - 1) / 3 * 3 + 1)"
                ),
                B::Year => format!("strftime('%Y-01-01', {e})"),
            },
            // Without DATETRUNC, which needs SQL Server 2022.
            Self::SqlServer => match bucket {
                B::Hour => format!("DATEADD(hour, DATEDIFF(hour, 0, {e}), 0)"),
                B::Day => format!("CAST({e} AS date)"),
                // Independent of SET DATEFIRST: days since a known Monday (1900-01-01), modulo 7.
                B::Week => format!("DATEADD(day, -(DATEDIFF(day, 0, {e}) % 7), CAST({e} AS date))"),
                B::Month => format!("DATEFROMPARTS(YEAR({e}), MONTH({e}), 1)"),
                B::Quarter => {
                    format!("DATEFROMPARTS(YEAR({e}), (DATEPART(quarter, {e}) - 1) * 3 + 1, 1)")
                }
                B::Year => format!("DATEFROMPARTS(YEAR({e}), 1, 1)"),
            },
            Self::ClickHouse => match bucket {
                B::Hour => format!("toStartOfHour({e})"),
                B::Day => format!("toDate({e})"),
                B::Week => format!("toMonday({e})"),
                B::Month => format!("toStartOfMonth({e})"),
                B::Quarter => format!("toStartOfQuarter({e})"),
                B::Year => format!("toStartOfYear({e})"),
            },
            Self::Generic => return None,
        })
    }
}

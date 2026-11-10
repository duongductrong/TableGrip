//! Connection strings as users paste them (`connection/ConnectionString.kt`).
//!
//! Parses connection strings the way their drivers and tools define them:
//! - PostgreSQL: libpq URIs (`postgresql://`, `postgres://`) and keyword/value strings, with libpq parameter names
//!   translated to pgjdbc properties; pgjdbc URLs (`jdbc:postgresql://host/db`, `jdbc:postgresql:db`).
//! - MySQL/MariaDB: `mysql://`, `mariadb://`, `jdbc:mysql://`, `jdbc:mariadb://`; advanced Connector/J and MariaDB
//!   Connector/J forms are kept as raw URLs.
//! - SQLite: `jdbc:sqlite:`, `sqlite://`, `file:` URIs and plain file paths.
//! - Redis and Valkey: `redis://`, `rediss://` (TLS), `valkey://`.
//! - Anything else starting with `jdbc:` is kept as a raw URL for the user's own driver.

use crate::connection::config::DriverId;
use crate::connection::files;
use indexmap::IndexMap;
use regex::Regex;
use std::path::Path;
use std::sync::LazyLock;
use tg_sql::model::DatabaseType;

/// What a pasted connection string describes. `host` may list several hosts (`h1:5432,h2:5433`), in which case `port`
/// is None and each entry carries its own. When `url` is set the string uses a form TableGrip can't break into fields:
/// connect with it as-is. It never contains the password.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ParsedConnection {
    pub db_type: DatabaseType,
    pub driver: Option<DriverId>,
    pub host: Option<String>,
    pub port: Option<i32>,
    pub database: Option<String>,
    pub user: Option<String>,
    pub password: Option<String>,
    pub properties: IndexMap<String, String>,
    pub url: Option<String>,
    pub warnings: Vec<String>,
}

impl ParsedConnection {
    fn new(db_type: DatabaseType) -> Self {
        Self {
            db_type,
            driver: None,
            host: None,
            port: None,
            database: None,
            user: None,
            password: None,
            properties: IndexMap::new(),
            url: None,
            warnings: Vec::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConnectionStringError(pub String);

impl std::fmt::Display for ConnectionStringError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ConnectionStringError {}

type Result<T> = std::result::Result<T, ConnectionStringError>;

fn error<T>(message: impl Into<String>) -> Result<T> {
    Err(ConnectionStringError(message.into()))
}

// Java's `\s` is ASCII-only, unlike the regex crate's.
static PASSWORD_ATTRIBUTE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i);[ \t\n\x0B\f\r]*password[ \t\n\x0B\f\r]*=[ \t\n\x0B\f\r]*([^;]*)").unwrap()
});
static USER_INFO: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(//)([^/@?]*?):([^/@?]*)@").unwrap());
static KEYWORD_VALUE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^[ \t\n\x0B\f\r]*(host|hostaddr|port|dbname|user|password|sslmode|application_name|options|connect_timeout)[ \t\n\x0B\f\r]*=",
    )
    .unwrap()
});
static DRIVE_PATH: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z]:[\\/]").unwrap());

pub fn parse(input: &str) -> Result<ParsedConnection> {
    let text = input.trim();
    if text.is_empty() {
        return error("The connection string is empty.");
    }
    let lower = text.to_lowercase();
    let starts = |prefix: &str| lower.starts_with(prefix);
    if starts("postgresql://") || starts("postgres://") {
        libpq_uri(text)
    } else if starts("jdbc:postgresql:") {
        pgjdbc(text)
    // MariaDB Connector/J takes jdbc:mysql: URLs too, and unlike Connector/J TableGrip can download it.
    } else if starts("jdbc:mysql") {
        mysql_family(text, DatabaseType::MYSQL, None, "jdbc:mysql")
    } else if starts("jdbc:mariadb") {
        mysql_family(
            text,
            DatabaseType::MARIADB,
            Some(DriverId::MariaDB),
            "jdbc:mariadb",
        )
    } else if starts("mysqlx") {
        error("X Protocol (mysqlx://) isn't supported; use a mysql:// URL.")
    } else if starts("mysql://") {
        mysql_family(text, DatabaseType::MYSQL, None, "mysql")
    } else if starts("mariadb://") {
        mysql_family(text, DatabaseType::MARIADB, None, "mariadb")
    } else if starts("jdbc:sqlite:") {
        Ok(sqlite(&text["jdbc:sqlite:".len()..]))
    } else if starts("sqlite:") || starts("sqlite3:") {
        Ok(sqlite_uri(substring_after(text, ':', text)))
    } else if starts("file:") {
        Ok(sqlite(text))
    } else if looks_like_keyword_value(text) {
        libpq_keyword_value(text)
    } else if looks_like_sqlite_path(text) {
        Ok(sqlite(&expand_home(text)))
    } else if starts("mongodb://") || starts("mongodb+srv://") {
        let (url, password) = strip_secrets(text);
        Ok(ParsedConnection {
            driver: Some(DriverId::MongoDB),
            url: Some(url),
            password,
            ..ParsedConnection::new(DatabaseType::MONGODB)
        })
    } else if starts("redis://")
        || starts("rediss://")
        || starts("valkey://")
        || starts("valkeys://")
    {
        redis_uri(text)
    // IDE data sources write Redis connections as JDBC URLs.
    } else if starts("jdbc:redis:") {
        redis_uri(substring_after(text, ':', text))
    } else if starts("jdbc:sqlserver:") {
        Ok(raw_jdbc(text, DatabaseType::SQLSERVER, DriverId::SqlServer))
    } else if starts("jdbc:clickhouse:") || starts("jdbc:ch:") {
        Ok(raw_jdbc(
            text,
            DatabaseType::CLICKHOUSE,
            DriverId::ClickHouse,
        ))
    } else if starts("jdbc:duckdb:") {
        Ok(raw_jdbc(text, DatabaseType::DUCKDB, DriverId::DuckDB))
    } else if starts("jdbc:h2:") {
        Ok(raw_jdbc(text, DatabaseType::H2, DriverId::H2))
    } else if starts("jdbc:hsqldb:") {
        Ok(raw_jdbc(text, DatabaseType::HSQLDB, DriverId::HSQLDB))
    } else if starts("jdbc:derby:") {
        Ok(raw_jdbc(text, DatabaseType::DERBY, DriverId::Derby))
    // Any other driver works too, from the user's own jar.
    } else if starts("jdbc:") {
        Ok(raw_jdbc(text, DatabaseType::OTHER_JDBC, DriverId::Generic))
    } else {
        error("Unrecognized connection string. Try postgres://, mysql://, jdbc:… or a file path.")
    }
}

/// A JDBC URL kept as it is, minus its password.
fn raw_jdbc(text: &str, db_type: DatabaseType, driver: DriverId) -> ParsedConnection {
    let (url, password) = strip_secrets(text);
    ParsedConnection {
        driver: Some(driver),
        url: Some(url),
        password,
        ..ParsedConnection::new(db_type)
    }
}

/// Removes the password from a URL's user info, its query, and `;password=` attributes (H2, HSQLDB, Derby, SQL
/// Server), returning the cleaned URL and the password.
pub fn strip_secrets(url: &str) -> (String, Option<String>) {
    let mut password = None;
    let mut result = url.to_string();
    if let Some(m) = PASSWORD_ATTRIBUTE.captures(&result) {
        password = Some(m[1].to_string());
        let range = m.get(0).unwrap().range();
        result.replace_range(range, "");
    }
    if let Some(m) = USER_INFO.captures(&result) {
        password = Some(decode(&m[3]));
        let replacement = format!("{}{}@", &m[1], &m[2]);
        let range = m.get(0).unwrap().range();
        result.replace_range(range, &replacement);
    }
    if let Some(query_start) = result.find('?') {
        let kept: Vec<&str> = result[query_start + 1..]
            .split('&')
            .filter(|part| {
                let key = substring_before(part, '=');
                if key.eq_ignore_ascii_case("password") {
                    password = Some(decode(substring_after(part, '=', "")));
                    false
                } else {
                    true
                }
            })
            .collect();
        let head = result[..query_start].to_string();
        result = if kept.is_empty() {
            head
        } else {
            format!("{head}?{}", kept.join("&"))
        };
    }
    (result, password)
}

/// `redis://[[user]:password@]host[:port][/db]`, `rediss://` for TLS; Valkey's `valkey://` forms are the same.
fn redis_uri(text: &str) -> Result<ParsedConnection> {
    let scheme = substring_before(text, "://").to_lowercase();
    let uri = split_uri(substring_after(text, "://", text))?;
    let host = single(parse_hosts(&uri.hosts)?);
    let mut warnings = Vec::new();
    if let Some(path) = uri.path.as_deref().filter(|p| *p != "0") {
        warnings.push(format!(
            "Database {path}: pick it in the key browser; connections start on database 0."
        ));
    }
    let tls = scheme == "rediss" || scheme == "valkeys";
    Ok(ParsedConnection {
        driver: Some(DriverId::Lettuce),
        host: Some(
            host.as_ref()
                .map(|h| h.0.clone())
                .filter(|h| !h.is_empty())
                .unwrap_or_else(|| "localhost".to_string()),
        ),
        port: host.and_then(|h| h.1),
        user: uri.user,
        password: uri.password,
        properties: if tls {
            IndexMap::from([("ssl".to_string(), "true".to_string())])
        } else {
            IndexMap::new()
        },
        warnings,
        ..ParsedConnection::new(if scheme.starts_with("valkey") {
            DatabaseType::VALKEY
        } else {
            DatabaseType::REDIS
        })
    })
}

// region PostgreSQL

/// libpq URI: `postgresql://[user[:password]@][host[:port][,...]][/dbname][?param=value&...]`.
fn libpq_uri(text: &str) -> Result<ParsedConnection> {
    let uri = split_uri(substring_after(text, "://", text))?;
    let mut base = PgBuilder::new(false);
    base.user = uri.user.or(base.user);
    base.password = uri.password.or(base.password);
    base.hosts = parse_hosts(&uri.hosts)?;
    base.database = uri.path.or(base.database);
    for (k, v) in &uri.query {
        base.libpq_param(k, v);
    }
    Ok(base.build())
}

/// libpq keyword/value: `host=localhost port=5432 dbname='my db'`, with `\'` and `\\` escapes.
fn libpq_keyword_value(text: &str) -> Result<ParsedConnection> {
    let chars: Vec<char> = text.chars().collect();
    let mut base = PgBuilder::new(false);
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() {
            break;
        }
        let key_start = i;
        while i < chars.len() && chars[i] != '=' && !chars[i].is_whitespace() {
            i += 1;
        }
        let key: String = chars[key_start..i].iter().collect();
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() || chars[i] != '=' {
            return error(format!("Missing \"=\" after \"{key}\"."));
        }
        i += 1;
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        let mut value = String::new();
        if i < chars.len() && chars[i] == '\'' {
            i += 1;
            while i < chars.len() && chars[i] != '\'' {
                if chars[i] == '\\' && i + 1 < chars.len() {
                    i += 1;
                }
                value.push(chars[i]);
                i += 1;
            }
            if i >= chars.len() {
                return error(format!("Unterminated quoted value for \"{key}\"."));
            }
            i += 1;
        } else {
            while i < chars.len() && !chars[i].is_whitespace() {
                if chars[i] == '\\' && i + 1 < chars.len() {
                    i += 1;
                }
                value.push(chars[i]);
                i += 1;
            }
        }
        base.libpq_param(&key, &value);
    }
    Ok(base.build())
}

/// pgjdbc: `jdbc:postgresql:database`, `jdbc:postgresql:/`, `jdbc:postgresql://host[:port][,...]/database?props`.
fn pgjdbc(text: &str) -> Result<ParsedConnection> {
    let rest = &text["jdbc:postgresql:".len()..];
    let mut base = PgBuilder::new(true);
    if let Some(authority) = rest.strip_prefix("//") {
        let uri = split_uri(authority)?;
        base.user = uri.user.or(base.user);
        base.password = uri.password.or(base.password);
        base.hosts = parse_hosts(&uri.hosts)?;
        base.database = uri.path.or(base.database);
        for (k, v) in &uri.query {
            base.jdbc_param(k, v);
        }
    } else {
        let name = substring_before(rest, '?');
        let name = name.strip_prefix('/').unwrap_or(name);
        base.database = Some(decode(name)).filter(|d| !d.is_empty());
        for (k, v) in &parse_query(substring_after(rest, '?', "")) {
            base.jdbc_param(k, v);
        }
    }
    Ok(base.build())
}

struct PgBuilder {
    jdbc: bool,
    hosts: Vec<(String, Option<i32>)>,
    ports: Vec<i32>,
    database: Option<String>,
    user: Option<String>,
    password: Option<String>,
    properties: IndexMap<String, String>,
    warnings: Vec<String>,
}

impl PgBuilder {
    fn new(jdbc: bool) -> Self {
        Self {
            jdbc,
            hosts: Vec::new(),
            ports: Vec::new(),
            database: None,
            user: None,
            password: None,
            properties: IndexMap::new(),
            warnings: Vec::new(),
        }
    }

    /// pgjdbc URL query: properties already use pgjdbc's names.
    fn jdbc_param(&mut self, key: &str, value: &str) {
        match key {
            "user" => self.user = Some(value.to_string()),
            "password" => self.password = Some(value.to_string()),
            _ => {
                self.properties.insert(key.to_string(), value.to_string());
            }
        }
    }

    /// libpq parameter names, translated to their pgjdbc equivalents.
    fn libpq_param(&mut self, key: &str, value: &str) {
        let set = |p: &mut IndexMap<String, String>, k: &str, v: &str| {
            p.insert(k.to_string(), v.to_string());
        };
        match key {
            "host" => {
                self.hosts = value
                    .split(',')
                    .map(|h| (h.trim().to_string(), None))
                    .collect()
            }
            "hostaddr" => {
                if self.hosts.is_empty() {
                    self.hosts = value
                        .split(',')
                        .map(|h| (h.trim().to_string(), None))
                        .collect();
                    self.warnings
                        .push("hostaddr is used as the host name.".to_string());
                }
            }
            "port" => {
                self.ports = value
                    .split(',')
                    .filter_map(|p| p.trim().parse::<i32>().ok())
                    .collect();
            }
            "dbname" => self.database = Some(value.to_string()),
            "user" => self.user = Some(value.to_string()),
            "password" => self.password = Some(value.to_string()),
            "ssl" => {
                if value == "true" || value.is_empty() {
                    set(&mut self.properties, "sslmode", "require");
                }
            }
            "sslmode" | "sslrootcert" | "sslcert" | "sslkey" | "sslpassword" | "options" => {
                set(&mut self.properties, key, value);
            }
            "application_name" => set(&mut self.properties, "ApplicationName", value),
            "connect_timeout" => set(&mut self.properties, "connectTimeout", value),
            "target_session_attrs" => {
                let mapped = match value {
                    "any" => Some("any"),
                    "primary" => Some("primary"),
                    "standby" => Some("secondary"),
                    "prefer-standby" => Some("preferSecondary"),
                    "read-write" => {
                        self.warnings.push(
                            "target_session_attrs=read-write is approximated by targetServerType=primary."
                                .to_string(),
                        );
                        Some("primary")
                    }
                    "read-only" => {
                        self.warnings.push(
                            "target_session_attrs=read-only is approximated by targetServerType=secondary."
                                .to_string(),
                        );
                        Some("secondary")
                    }
                    _ => {
                        self.warnings
                            .push(format!("Unknown target_session_attrs \"{value}\" ignored."));
                        None
                    }
                };
                if let Some(mapped) = mapped {
                    set(&mut self.properties, "targetServerType", mapped);
                }
            }
            _ => {
                set(&mut self.properties, key, value);
                self.warnings.push(format!(
                    "\"{key}\" isn't a pgjdbc property; it's passed to the driver as-is."
                ));
            }
        }
    }

    fn build(mut self) -> ParsedConnection {
        // libpq: a single port applies to every host; otherwise ports pair up with hosts.
        let ports = &self.ports;
        let mut resolved: Vec<(String, Option<i32>)> = self
            .hosts
            .iter()
            .enumerate()
            .map(|(i, (host, port))| {
                let port = port.or_else(|| ports.get(i).copied()).or_else(|| {
                    if ports.len() == 1 {
                        Some(ports[0])
                    } else {
                        None
                    }
                });
                (host.clone(), port)
            })
            .collect();
        let is_socket = |h: &str| h.starts_with('/') || h.starts_with('@');
        if resolved.iter().any(|(h, _)| is_socket(h)) {
            self.warnings.push(
                "Unix-domain sockets aren't supported by the JDBC driver; connecting to localhost instead."
                    .to_string(),
            );
            for (host, _) in &mut resolved {
                if is_socket(host) {
                    *host = "localhost".to_string();
                }
            }
        }
        if resolved.is_empty() && !self.ports.is_empty() {
            resolved = vec![("localhost".to_string(), Some(self.ports[0]))];
        }
        let single = if resolved.len() == 1 {
            resolved.first()
        } else {
            None
        };
        let host = if resolved.is_empty() {
            None
        } else if let Some((h, _)) = single {
            Some(if h.is_empty() {
                "localhost".to_string()
            } else {
                h.clone()
            })
        } else {
            Some(
                resolved
                    .iter()
                    .map(|(h, p)| format_host(if h.is_empty() { "localhost" } else { h }, *p))
                    .collect::<Vec<_>>()
                    .join(","),
            )
        };
        ParsedConnection {
            driver: if self.jdbc {
                Some(DriverId::PostgreSQL)
            } else {
                None
            },
            host,
            port: single.and_then(|(_, p)| *p),
            database: self.database,
            user: self.user,
            password: self.password,
            properties: self.properties,
            warnings: self.warnings,
            ..ParsedConnection::new(DatabaseType::POSTGRESQL)
        }
    }
}

// endregion

// region MySQL / MariaDB

const ADVANCED_MYSQL_MODES: [&str; 5] = [
    ":loadbalance:",
    ":replication:",
    ":sequential:",
    ":load-balance-read:",
    "+srv:",
];

fn mysql_family(
    text: &str,
    db_type: DatabaseType,
    driver: Option<DriverId>,
    prefix: &str,
) -> Result<ParsedConnection> {
    let after_scheme = &text[prefix.len()..];
    // Replication, load-balancing, SRV and key-value host forms only make sense as a whole URL.
    let advanced = ADVANCED_MYSQL_MODES
        .iter()
        .any(|m| after_scheme.starts_with(m))
        || !after_scheme.starts_with("://");
    let uri_part = substring_after(text, "//", "");
    let host_part =
        substring_after_last(substring_before(substring_before(uri_part, '/'), '?'), '@');
    if advanced
        || host_part.contains('(')
        || host_part.starts_with("address=")
        || (host_part.starts_with('[') && host_part.contains(','))
    {
        if !text.starts_with("jdbc:") {
            return error("This form needs a jdbc: URL.");
        }
        let (url, password) = strip_secrets(text);
        let uri = split_uri(uri_part)?;
        return Ok(ParsedConnection {
            driver,
            user: uri.user.or_else(|| uri.query.get("user").cloned()),
            password,
            database: uri.path,
            url: Some(url),
            warnings: vec![
                "This URL form is kept as-is; edit it under Connect by → JDBC URL.".to_string(),
            ],
            ..ParsedConnection::new(db_type)
        });
    }

    let uri = split_uri(uri_part)?;
    let hosts = parse_hosts(&uri.hosts)?;
    let mut properties = IndexMap::new();
    let mut warnings = Vec::new();
    let mut user = uri.user.clone();
    let mut password = uri.password.clone();
    for (key, value) in &uri.query {
        match key.as_str() {
            "user" => user = Some(value.clone()),
            "password" => password = Some(value.clone()),
            // mysql client style, common in DATABASE_URL strings.
            "ssl-mode" => {
                properties.insert("sslMode".to_string(), value.to_uppercase());
            }
            "ssl" if !text.starts_with("jdbc:") && (value == "true" || value == "1") => {
                properties.insert("sslMode".to_string(), "REQUIRED".to_string());
                warnings.push("ssl=true is translated to sslMode=REQUIRED.".to_string());
            }
            _ => {
                properties.insert(key.clone(), value.clone());
            }
        }
    }
    let single = if hosts.len() == 1 {
        hosts.first()
    } else {
        None
    };
    let host = if hosts.is_empty() {
        None
    } else if let Some((h, _)) = single {
        Some(h.clone())
    } else {
        Some(
            hosts
                .iter()
                .map(|(h, p)| format_host(h, *p))
                .collect::<Vec<_>>()
                .join(","),
        )
    };
    Ok(ParsedConnection {
        driver,
        host,
        port: single.and_then(|(_, p)| *p),
        database: uri.path,
        user,
        password,
        properties,
        warnings,
        ..ParsedConnection::new(db_type)
    })
}

// endregion

// region SQLite

/// `sqlite:` URLs: `sqlite://` alone is in-memory, `sqlite:///path` and `sqlite:////path` are absolute paths.
fn sqlite_uri(rest: &str) -> ParsedConnection {
    let Some(path) = rest.strip_prefix("//") else {
        return sqlite(rest);
    };
    if path.is_empty() || path == ":memory:" {
        sqlite(":memory:")
    } else if path.starts_with('/') {
        sqlite(&format!("/{}", decode(path.trim_start_matches('/'))))
    } else {
        sqlite(&decode(path))
    }
}

fn sqlite(path: &str) -> ParsedConnection {
    let database = if path.trim().is_empty() {
        ":memory:".to_string()
    } else if path.starts_with("file:") && !path.contains('?') {
        let rest = &path["file:".len()..];
        decode(rest.strip_prefix("//").unwrap_or(rest))
    } else {
        path.to_string()
    };
    let expanded = expand_home(&database);
    let warnings = file_or_none(&expanded)
        .and_then(|f| files::mismatch(f, DatabaseType::SQLITE))
        .into_iter()
        .collect();
    ParsedConnection {
        driver: Some(DriverId::SQLite),
        database: Some(expanded),
        warnings,
        ..ParsedConnection::new(DatabaseType::SQLITE)
    }
}

fn file_or_none(path: &str) -> Option<&Path> {
    if path == ":memory:" || path.starts_with("file:") {
        None
    } else {
        Some(Path::new(path))
    }
}

fn looks_like_sqlite_path(text: &str) -> bool {
    let lower = text.to_lowercase();
    [".db", ".sqlite", ".sqlite3", ".db3"].iter().any(|e| lower.ends_with(e))
        || text.starts_with('/')
        || text.starts_with("~/")
        || text.starts_with("./")
        || DRIVE_PATH.is_match(text)
        // A relative name with no telling extension, e.g. `chinook`: its content decides.
        || file_or_none(&expand_home(text)).is_some_and(|f| files::detect(f) == Some(DatabaseType::SQLITE))
}

// endregion

// region URI helpers

struct UriParts {
    user: Option<String>,
    password: Option<String>,
    hosts: String,
    path: Option<String>,
    query: IndexMap<String, String>,
}

/// Splits `[user[:password]@]hosts[/path][?query]` (the part after `//`), decoding user info and path.
fn split_uri(text: &str) -> Result<UriParts> {
    let (before_query, query) = match text.find('?') {
        Some(q) => (&text[..q], parse_query(&text[q + 1..])),
        None => (text, IndexMap::new()),
    };
    // The last '@' ends the user info, which tolerates passwords with unencoded '@' or '/' (common in pasted
    // strings even though the specs require encoding them).
    let at = before_query.rfind('@');
    let user_info = at.map(|a| &before_query[..a]);
    let host_and_path = match at {
        Some(a) => &before_query[a + 1..],
        None => before_query,
    };
    let (hosts, path) = match host_and_path.find('/') {
        Some(s) => (
            &host_and_path[..s],
            Some(decode(&host_and_path[s + 1..])).filter(|p| !p.is_empty()),
        ),
        None => (host_and_path, None),
    };
    Ok(UriParts {
        user: user_info
            .map(|u| decode(substring_before(u, ':')))
            .filter(|u| !u.is_empty()),
        password: user_info
            .filter(|u| u.contains(':'))
            .map(|u| decode(substring_after(u, ':', ""))),
        hosts: hosts.to_string(),
        path,
        query,
    })
}

/// `name=value&...`; a bare `name` means `name=` (pgjdbc treats `?ssl` as enabling SSL).
fn parse_query(query: &str) -> IndexMap<String, String> {
    query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|part| {
            (
                decode(substring_before(part, '=')),
                decode(substring_after(part, '=', "")),
            )
        })
        .collect()
}

/// `host[:port][,host[:port]...]`, with IPv6 hosts in brackets and percent-encoded socket paths.
fn parse_hosts(spec: &str) -> Result<Vec<(String, Option<i32>)>> {
    spec.split(',')
        .filter(|e| !e.trim().is_empty())
        .map(|entry| {
            if let Some(inner) = entry.strip_prefix('[') {
                let Some(close) = inner.find(']') else {
                    return error(format!("Unclosed [ in host \"{entry}\"."));
                };
                // Kotlin: entry.indexOf(']') must be > 0, and it is: the bracket was found after position 0.
                let host = inner[..close].to_string();
                Ok((host, to_port(substring_after(entry, "]:", ""), entry)?))
            } else if entry.matches(':').count() == 1 {
                Ok((
                    decode(substring_before(entry, ':')),
                    to_port(substring_after(entry, ':', ""), entry)?,
                ))
            } else {
                Ok((decode(entry), None))
            }
        })
        .collect()
}

fn to_port(text: &str, entry: &str) -> Result<Option<i32>> {
    if text.is_empty() {
        return Ok(None);
    }
    match text.parse::<i32>() {
        Ok(port) if (1..=65535).contains(&port) => Ok(Some(port)),
        _ => error(format!("Invalid port in \"{entry}\".")),
    }
}

/// Percent-decoding without URLDecoder's form semantics: '+' stays a plus.
fn decode(value: &str) -> String {
    if !value.contains('%') {
        return value.to_string();
    }
    let chars: Vec<char> = value.chars().collect();
    let mut bytes: Vec<u8> = Vec::with_capacity(value.len());
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '%' && i + 2 < chars.len() {
            let hex: String = chars[i + 1..i + 3].iter().collect();
            // Kotlin's toIntOrNull(16) takes a sign too, and `write` keeps the low byte.
            if let Ok(n) = i32::from_str_radix(&hex, 16) {
                bytes.push((n & 0xFF) as u8);
                i += 3;
                continue;
            }
        }
        let mut buf = [0u8; 4];
        bytes.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
        i += 1;
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn looks_like_keyword_value(text: &str) -> bool {
    !text.contains("://") && KEYWORD_VALUE.is_match(text)
}

fn expand_home(path: &str) -> String {
    match path.strip_prefix('~') {
        Some(rest) if path.starts_with("~/") => {
            format!("{}{rest}", crate::paths::user_home().to_string_lossy())
        }
        _ => path.to_string(),
    }
}

// endregion

/// `host` or `[ipv6]`, with `:port` when there is one.
pub fn format_host(host: &str, port: Option<i32>) -> String {
    let bracketed = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host.to_string()
    };
    match port {
        None => bracketed,
        Some(p) => format!("{bracketed}:{p}"),
    }
}

fn single<T>(mut items: Vec<T>) -> Option<T> {
    if items.len() == 1 { items.pop() } else { None }
}

// Kotlin's substringBefore/After take a char or a string delimiter and a fallback.
trait Delimiter {
    fn find_in(&self, text: &str) -> Option<(usize, usize)>;
    fn rfind_in(&self, text: &str) -> Option<(usize, usize)>;
}

impl Delimiter for char {
    fn find_in(&self, text: &str) -> Option<(usize, usize)> {
        text.find(*self).map(|i| (i, i + self.len_utf8()))
    }
    fn rfind_in(&self, text: &str) -> Option<(usize, usize)> {
        text.rfind(*self).map(|i| (i, i + self.len_utf8()))
    }
}

impl Delimiter for &str {
    fn find_in(&self, text: &str) -> Option<(usize, usize)> {
        text.find(*self).map(|i| (i, i + self.len()))
    }
    fn rfind_in(&self, text: &str) -> Option<(usize, usize)> {
        text.rfind(*self).map(|i| (i, i + self.len()))
    }
}

fn substring_before<D: Delimiter>(text: &str, delimiter: D) -> &str {
    delimiter
        .find_in(text)
        .map_or(text, |(start, _)| &text[..start])
}

fn substring_after<'a, D: Delimiter>(text: &'a str, delimiter: D, missing: &'a str) -> &'a str {
    delimiter
        .find_in(text)
        .map_or(missing, |(_, end)| &text[end..])
}

fn substring_after_last<D: Delimiter>(text: &str, delimiter: D) -> &str {
    delimiter
        .rfind_in(text)
        .map_or(text, |(_, end)| &text[end..])
}

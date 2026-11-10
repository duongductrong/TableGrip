//! Connections found in other tools (`imports/ConnectionImport.kt`): a `Connections.plist`, DBeaver, IDE data sources
//! (`.idea/dataSources.xml`), libpq's `.pgpass` and `pg_service.conf`, and `.env` files, so moving to TableGrip
//! doesn't mean typing them in again. Nothing is written: the caller saves the candidates the user picks. Also
//! `~/.ssh/config` hosts for the SSH tunnel dialog.

pub mod dbeaver;
pub mod dotenv;
pub mod ide;
pub mod pgpass;
pub mod plist;
pub mod ssh_hosts;
mod xml;

pub use ssh_hosts::{SshHost, read as ssh_hosts};

use regex::Regex;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use tg_core::connection::string::{self as connection_string, ParsedConnection};
use tg_core::connection::{ConnectionConfig, DriverId, Environment, SshConfig};
use tg_sql::model::{DatabaseType, EngineFamily};

/// The tools and files TableGrip reads connections from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ImportFormat {
    MacPlist,
    DBeaver,
    IdeDataSources,
    PgPass,
    PgService,
    DotEnv,
}

impl ImportFormat {
    pub fn label(self) -> &'static str {
        match self {
            Self::MacPlist => "Connections.plist (macOS)",
            Self::DBeaver => "DBeaver",
            Self::IdeDataSources => "IDE data sources (.idea/dataSources.xml)",
            Self::PgPass => ".pgpass",
            Self::PgService => "pg_service.conf",
            Self::DotEnv => ".env files",
        }
    }

    pub fn short_label(self) -> &'static str {
        match self {
            Self::MacPlist => "Connections.plist",
            Self::IdeDataSources => "IDE data sources",
            Self::DotEnv => ".env",
            other => other.label(),
        }
    }
}

/// A place connections can be read from: one file, or a folder for [`ImportFormat::DotEnv`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportSource {
    pub format: ImportFormat,
    pub path: PathBuf,
}

/// A connection found in another tool, ready to save. `config` has a fresh id; `password` and `ssh_secret` are what
/// the tool had stored, if TableGrip could read them. `origin` says where it came from, for the user.
#[derive(Clone, Debug, PartialEq)]
pub struct ImportCandidate {
    pub config: ConnectionConfig,
    pub password: Option<String>,
    pub ssh_secret: Option<String>,
    pub origin: String,
    pub notes: Vec<String>,
}

impl ImportCandidate {
    pub fn new(config: ConnectionConfig, origin: impl Into<String>) -> Self {
        Self {
            config,
            password: None,
            ssh_secret: None,
            origin: origin.into(),
            notes: Vec::new(),
        }
    }

    /// What identifies the database regardless of name: two candidates with the same key point at the same place.
    pub fn key(&self) -> String {
        key_of(&self.config)
    }
}

/// What reading a source produced: connections, plus those it had to leave out and why.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ImportScan {
    pub candidates: Vec<ImportCandidate>,
    pub skipped: Vec<String>,
}

impl ImportScan {
    pub fn plus(mut self, other: ImportScan) -> Self {
        self.candidates.extend(other.candidates);
        self.skipped.extend(other.skipped);
        self
    }
}

/// Sources present on this machine, in the order they're listed.
pub fn discover(home: &Path, env: &HashMap<String, String>) -> Vec<ImportSource> {
    let mut out = Vec::new();
    for p in plist::locations(home).into_iter().filter(|p| p.is_file()) {
        out.push(ImportSource {
            format: ImportFormat::MacPlist,
            path: p,
        });
    }
    for p in dbeaver::locations(home, env) {
        out.push(ImportSource {
            format: ImportFormat::DBeaver,
            path: p,
        });
    }
    for p in ide::locations(home) {
        out.push(ImportSource {
            format: ImportFormat::IdeDataSources,
            path: p,
        });
    }
    let pass = pgpass::pass_file(home, env);
    if pass.is_file() {
        out.push(ImportSource {
            format: ImportFormat::PgPass,
            path: pass,
        });
    }
    for p in pgpass::service_files(home, env)
        .into_iter()
        .filter(|p| p.is_file())
    {
        out.push(ImportSource {
            format: ImportFormat::PgService,
            path: p,
        });
    }
    out
}

/// Reads one source; a file that can't be read becomes a skipped entry rather than an error.
pub fn read(source: &ImportSource, home: &Path) -> ImportScan {
    let result = match source.format {
        ImportFormat::MacPlist => plist::read(&source.path),
        ImportFormat::DBeaver => dbeaver::read(&source.path),
        ImportFormat::IdeDataSources => ide::read(&source.path, home),
        ImportFormat::PgPass => pgpass::read_pass_file(&source.path),
        ImportFormat::PgService => pgpass::read_service_file(&source.path),
        ImportFormat::DotEnv if source.path.is_dir() => Ok(dotenv::scan(&source.path, 4)),
        ImportFormat::DotEnv => dotenv::read(&source.path, None),
    };
    result.unwrap_or_else(|e| ImportScan {
        candidates: Vec::new(),
        skipped: vec![format!("{}: {e}", source.path.display())],
    })
}

const MAX_SNIFF_BYTES: u64 = 4 * 1024 * 1024;

/// Works out what a file the user picked is, from its name and then its content; None if it's none of them.
pub fn detect(path: &Path) -> Option<ImportSource> {
    if path.is_dir() {
        // An IDE project, or a folder to look for .env files in.
        let idea = path.join(".idea").join("dataSources.xml");
        return Some(if idea.exists() {
            ImportSource {
                format: ImportFormat::IdeDataSources,
                path: idea,
            }
        } else {
            ImportSource {
                format: ImportFormat::DotEnv,
                path: path.to_owned(),
            }
        });
    }
    let name = path.file_name()?.to_string_lossy().to_lowercase();
    let format = if name.ends_with(".plist") {
        Some(ImportFormat::MacPlist)
    } else if name.starts_with("data-sources") && name.ends_with(".json") {
        Some(ImportFormat::DBeaver)
    } else if name.starts_with("datasources") && name.ends_with(".xml") {
        Some(ImportFormat::IdeDataSources)
    } else if name == ".pgpass" || name == "pgpass.conf" {
        Some(ImportFormat::PgPass)
    } else if name.ends_with("pg_service.conf") {
        Some(ImportFormat::PgService)
    } else if name == ".env" || name.starts_with(".env.") || name.ends_with(".env") {
        Some(ImportFormat::DotEnv)
    } else {
        sniff(path)
    }?;
    Some(ImportSource {
        format,
        path: path.to_owned(),
    })
}

fn sniff(path: &Path) -> Option<ImportFormat> {
    static SERVICE: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?m)^\s*\[[^\]]+]\s*$").unwrap());
    static PASS: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?m)^[^#\s][^:\n]*:[^:\n]*:[^:\n]*:[^:\n]*:.*$").unwrap());
    static ENV: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?m)^\s*(export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=").unwrap());
    if std::fs::metadata(path).ok()?.len() > MAX_SNIFF_BYTES {
        return None;
    }
    let text = std::fs::read_to_string(path).ok()?;
    if text.contains("<data-source") {
        Some(ImportFormat::IdeDataSources)
    } else if text.contains("\"connections\"") && text.contains("\"provider\"") {
        Some(ImportFormat::DBeaver)
    } else if text.contains("ConnectionName") {
        Some(ImportFormat::MacPlist)
    } else if SERVICE.is_match(&text) && text.contains("dbname") {
        Some(ImportFormat::PgService)
    } else if PASS.is_match(&text) {
        Some(ImportFormat::PgPass)
    } else if ENV.is_match(&text) {
        Some(ImportFormat::DotEnv)
    } else {
        None
    }
}

/// Engine, address, database and user, normalised so that `localhost:5432/app` written with or without its port is
/// the same database. Profiles connecting by URL compare by URL.
pub fn key_of(config: &ConnectionConfig) -> String {
    let family = format!("{:?}", config.db_type.family);
    if let Some(url) = &config.url {
        return format!(
            "{family}|url|{}|{}",
            url.trim().to_lowercase(),
            config.user.trim()
        );
    }
    if config.db_type.is_file() {
        return format!("{family}|file|{}", config.database.trim());
    }
    let host = config.host.trim().to_lowercase();
    let host = if matches!(host.as_str(), "127.0.0.1" | "::1" | "[::1]") {
        "localhost".to_owned()
    } else {
        host
    };
    let port = config.port.or(config.db_type.default_port.map(i32::from));
    let port = port.map_or("null".to_owned(), |p| p.to_string());
    format!(
        "{family}|{host}|{port}|{}|{}",
        config.database.trim(),
        config.user.trim()
    )
}

/// libpq's password file on this machine, which may hold passwords for connections other tools import without.
pub fn pg_pass_file(home: &Path, env: &HashMap<String, String>) -> PathBuf {
    pgpass::pass_file(home, env)
}

/// Fills in the passwords of PostgreSQL connections that came without one from `pass_file` entries, the way libpq
/// itself would look them up.
pub fn fill_passwords_from_pg_pass(
    candidates: Vec<ImportCandidate>,
    pass_file: &Path,
) -> Vec<ImportCandidate> {
    if !pass_file.is_file() {
        return candidates;
    }
    let entries = std::fs::read_to_string(pass_file)
        .map(|t| pgpass::entries(&t))
        .unwrap_or_default();
    if entries.is_empty() {
        return candidates;
    }
    candidates
        .into_iter()
        .map(|mut candidate| {
            let config = &candidate.config;
            if candidate.password.is_some()
                || config.db_type.family != EngineFamily::PostgreSQL
                || config.url.is_some()
            {
                return candidate;
            }
            let database = if config.database.is_empty() {
                &config.user
            } else {
                &config.database
            };
            if let Some(p) = pgpass::lookup(
                &entries,
                &config.host,
                config.port.unwrap_or(5432),
                database,
                &config.user,
            ) {
                candidate.password = Some(p);
                candidate.notes.push("Password from ~/.pgpass".into());
            }
            candidate
        })
        .collect()
}

pub(crate) fn new_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut parts = [0u64; 2];
    for (i, p) in parts.iter_mut().enumerate() {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_nanos()),
        );
        h.write_usize(i);
        *p = h.finish();
    }
    let hex = format!("{:016x}{:016x}", parts[0], parts[1]);
    // A version 4 UUID's layout.
    format!(
        "{}-{}-4{}-{}{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[13..16],
        ["8", "9", "a", "b"][(parts[1] & 3) as usize],
        &hex[17..20],
        &hex[20..32]
    )
}

/// What `fromParsed` takes beyond the parsed string.
#[derive(Default)]
pub(crate) struct Profile<'a> {
    pub name: &'a str,
    pub group: &'a str,
    pub environment: Environment,
    pub read_only: bool,
    pub ssh: Option<SshConfig>,
    pub db_type: Option<DatabaseType>,
}

/// A profile for a parsed connection string, named `name` (`ConnectionImport.fromParsed(parsed, name)`).
pub fn profile_from(parsed: &ParsedConnection, name: &str) -> ConnectionConfig {
    from_parsed(
        parsed,
        Profile {
            name,
            ..Profile::default()
        },
    )
}

/// Turns a parsed connection string into a profile, the way the connection dialog does when one is pasted.
pub(crate) fn from_parsed(parsed: &ParsedConnection, p: Profile<'_>) -> ConnectionConfig {
    let db_type = p.db_type.unwrap_or(parsed.db_type);
    let driver = parsed
        .driver
        .filter(|d| DriverId::for_type(&db_type).contains(d));
    let name = p.name.trim();
    let name = if name.is_empty() {
        fallback_name(parsed)
    } else {
        name.to_owned()
    };
    let mut config = ConnectionConfig::new(&new_id(), &name, db_type);
    config.host = if !db_type.is_server() {
        String::new()
    } else {
        parsed.host.clone().unwrap_or_else(|| {
            if parsed.url.is_none() {
                "localhost".into()
            } else {
                String::new()
            }
        })
    };
    config.port = parsed.port.filter(|_| db_type.is_server());
    config.database = parsed.database.clone().unwrap_or_default();
    config.user = parsed.user.clone().unwrap_or_default();
    config.driver = driver;
    config.properties = parsed.properties.clone();
    config.url = parsed.url.clone();
    config.group = p.group.trim().to_owned();
    config.environment = p.environment;
    config.read_only = p.read_only;
    config.confirm_writes = p.environment == Environment::Production;
    config.ssh = p.ssh.filter(|_| db_type.is_server());
    config
}

/// Parses `text` as a connection string, also accepting `redis://` and the `jdbc:` forms of URLs IDE data sources hold.
pub(crate) fn parse_or_none(text: &str) -> Option<ParsedConnection> {
    connection_string::parse(text).ok()
}

fn fallback_name(parsed: &ParsedConnection) -> String {
    if let Some(url) = &parsed.url {
        let rest = url.split_once("//").map_or(url.as_str(), |(_, r)| r);
        let rest = rest.split('?').next().unwrap_or("");
        let rest = rest.rsplit('@').next().unwrap_or("");
        return if rest.is_empty() {
            parsed.db_type.display_name.to_owned()
        } else {
            rest.to_owned()
        };
    }
    if parsed.db_type.is_server() {
        let mut parts = vec![parsed.host.clone().unwrap_or_else(|| "localhost".into())];
        parts.extend(parsed.database.clone().filter(|d| !d.is_empty()));
        return parts.join("/");
    }
    let file = parsed.database.clone().unwrap_or_default();
    let name = file.rsplit('/').next().unwrap_or("").to_owned();
    if name.is_empty() {
        parsed.db_type.display_name.to_owned()
    } else {
        name
    }
}

/// Maps the environment names other tools use (plist tags, DBeaver's connection types) to TableGrip's.
pub(crate) fn environment_of(name: Option<&str>) -> Environment {
    match name.map(|n| n.trim().to_lowercase()).as_deref() {
        Some("local") => Environment::Local,
        Some("dev" | "development") => Environment::Development,
        Some("test" | "testing" | "qa" | "staging" | "stage" | "uat") => Environment::Staging,
        Some("prod" | "production" | "live") => Environment::Production,
        _ => Environment::None,
    }
}

pub(crate) fn expand_home(path: &str, home: &Path) -> String {
    if path == "~" || path.starts_with("~/") {
        format!("{}{}", home.display(), &path[1..])
    } else {
        path.to_owned()
    }
}

/// The user's account name, the default SSH user.
pub(crate) fn user_name() -> String {
    std::env::var("USER")
        .or_else(|_| std::env::var("USERNAME"))
        .unwrap_or_default()
}

/// An empty parsed connection of `db_type`, for importers that read fields rather than a string.
pub(crate) fn parsed(db_type: DatabaseType) -> ParsedConnection {
    ParsedConnection {
        db_type,
        driver: None,
        host: None,
        port: None,
        database: None,
        user: None,
        password: None,
        properties: Default::default(),
        url: None,
        warnings: Vec::new(),
    }
}

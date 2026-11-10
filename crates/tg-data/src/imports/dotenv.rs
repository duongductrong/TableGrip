//! Connections in projects' `.env` files (`imports/DotEnvImporter.kt`): any variable holding a connection URL
//! (`DATABASE_URL`, `REDIS_URL`, `MONGO_URI`...), and the split forms frameworks and Docker images use — Laravel's
//! `DB_CONNECTION`/`DB_HOST`/..., and `POSTGRES_*` / `MYSQL_*` / `PG*` variables.

use super::{ImportCandidate, ImportScan, Profile, from_parsed, parse_or_none, parsed};
use indexmap::IndexMap;
use regex::Regex;
use std::path::Path;
use std::sync::LazyLock;
use tg_core::connection::string::ParsedConnection;
use tg_sql::model::DatabaseType;

const SKIPPED_FOLDERS: &[&str] = &[
    "node_modules",
    ".git",
    "vendor",
    "build",
    "dist",
    "target",
    ".gradle",
    ".venv",
    "venv",
    ".next",
    ".idea",
];
const MAX_FILES: usize = 200;

/// Looks for `.env` files under `folder`, skipping dependency and build folders.
pub fn scan(folder: &Path, max_depth: usize) -> ImportScan {
    let mut files = Vec::new();
    walk(folder, 0, max_depth, &mut files);
    files.sort();
    files.truncate(MAX_FILES);
    files.iter().fold(ImportScan::default(), |scan, file| {
        scan.plus(read(file, Some(folder)).unwrap_or_else(|e| ImportScan {
            candidates: vec![],
            skipped: vec![e],
        }))
    })
}

fn walk(dir: &Path, depth: usize, max_depth: usize, out: &mut Vec<std::path::PathBuf>) {
    if depth >= max_depth {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        if path.is_dir() {
            if !SKIPPED_FOLDERS.contains(&name.as_str()) {
                walk(&path, depth + 1, max_depth, out);
            }
        } else if path.is_file() && is_env_file(&name) {
            out.push(path);
        }
    }
}

/// `.env`, `.env.local`, `.env.production`, `app.env`... but not editor backups.
pub fn is_env_file(name: &str) -> bool {
    (name == ".env" || name.starts_with(".env.") || name.ends_with(".env"))
        && !name.ends_with('~')
        && !name.ends_with(".swp")
}

/// Templates hold placeholders, not real credentials.
fn is_template(name: &str) -> bool {
    static RE: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"\.(example|sample|template|dist|defaults?)$").unwrap());
    RE.is_match(name)
}

/// Reads one file; `root` names connections by their folder relative to it.
pub fn read(file: &Path, root: Option<&Path>) -> Result<ImportScan, String> {
    let vars = parse(&std::fs::read_to_string(file).map_err(|e| e.to_string())?);
    let parent = file.parent();
    let project = match (root, parent) {
        (Some(r), Some(p)) if p == r => r
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default(),
        (Some(r), Some(p)) => p
            .strip_prefix(r)
            .map(|x| x.display().to_string())
            .unwrap_or_default(),
        (None, Some(p)) => p
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default(),
        _ => String::new(),
    };
    let file_name = file
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let template = is_template(&file_name);
    let label = [project.as_str(), file_name.as_str()]
        .iter()
        .filter(|s| !s.is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join(" · ");
    let notes: Vec<String> = if template {
        vec!["From a template file: the values may be placeholders".into()]
    } else {
        vec![]
    };
    let mut scan = ImportScan::default();
    let mut add = |p: &ParsedConnection, variable: &str| {
        let name = format!("{project} · {variable}");
        let name = name.strip_prefix(" · ").unwrap_or(&name).to_owned();
        let config = from_parsed(
            p,
            Profile {
                name: &name,
                group: &project,
                ..Profile::default()
            },
        );
        let mut c = ImportCandidate::new(config, format!("{} ({variable})", file.display()));
        c.password = p.password.clone().filter(|p| !p.is_empty());
        c.notes = notes
            .iter()
            .cloned()
            .chain(p.warnings.iter().cloned())
            .collect();
        scan.candidates.push(c);
    };
    let mut skipped = Vec::new();
    for (key, value) in &vars {
        if !looks_like_url(value) {
            continue;
        }
        match parse_or_none(value) {
            Some(p) => add(&p, key),
            None => {
                if looks_like_database_key(key) {
                    skipped.push(format!(
                        "{label}: {key} isn't a connection string TableGrip understands."
                    ));
                }
            }
        }
    }
    if let Some((p, variable)) = split(&vars) {
        add(&p, variable);
    }
    scan.skipped.extend(skipped);
    Ok(scan)
}

fn looks_like_url(value: &str) -> bool {
    static RE: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"(?i)^(postgres(ql)?|mysql|mariadb|mongodb(\+srv)?|rediss?|valkeys?|sqlite3?|jdbc:[a-z0-9]+)(:|://)").unwrap()
    });
    RE.is_match(value)
}

fn looks_like_database_key(key: &str) -> bool {
    static RE: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"(?i)(DATABASE|DB|POSTGRES|PG|MYSQL|MONGO|REDIS).*(URL|URI|DSN)").unwrap()
    });
    RE.is_match(key)
}

fn port_type(port: Option<&String>) -> Option<DatabaseType> {
    match port?.parse::<i32>().ok()? {
        5432 => Some(DatabaseType::POSTGRESQL),
        3306 => Some(DatabaseType::MYSQL),
        1433 => Some(DatabaseType::SQLSERVER),
        _ => None,
    }
}

/// A connection spelled out over several variables, with the variable that names it.
fn split(vars: &IndexMap<String, String>) -> Option<(ParsedConnection, &'static str)> {
    let get = |k: &str| vars.get(k).cloned();
    let port = |k: &str| vars.get(k).and_then(|p| p.parse::<i32>().ok());
    // Laravel and many PHP/Node frameworks.
    if let Some(host) = vars.get("DB_HOST") {
        let kind = get("DB_CONNECTION")
            .or_else(|| get("DB_DRIVER"))
            .or_else(|| get("DB_DIALECT"))
            .map(|k| k.to_lowercase());
        let db_type = match kind.as_deref() {
            Some("pgsql" | "postgres" | "postgresql") => DatabaseType::POSTGRESQL,
            Some("mysql") => DatabaseType::MYSQL,
            Some("mariadb") => DatabaseType::MARIADB,
            Some("sqlsrv" | "mssql") => DatabaseType::SQLSERVER,
            None => port_type(vars.get("DB_PORT"))?,
            _ => return None,
        };
        let mut p = parsed(db_type);
        p.host = Some(if host.is_empty() {
            "localhost".into()
        } else {
            host.clone()
        });
        p.port = port("DB_PORT");
        p.database = get("DB_DATABASE").or_else(|| get("DB_NAME"));
        p.user = get("DB_USERNAME").or_else(|| get("DB_USER"));
        p.password = get("DB_PASSWORD").or_else(|| get("DB_PASS"));
        return Some((p, "DB_HOST"));
    }
    // The official Docker images' variables, and libpq's own environment.
    if vars.keys().any(|k| k.starts_with("POSTGRES_")) {
        let mut p = parsed(DatabaseType::POSTGRESQL);
        p.host = Some(get("POSTGRES_HOST").unwrap_or_else(|| "localhost".into()));
        p.port = port("POSTGRES_PORT");
        p.database = get("POSTGRES_DB")
            .or_else(|| get("POSTGRES_DATABASE"))
            .or_else(|| get("POSTGRES_USER"));
        p.user = Some(get("POSTGRES_USER").unwrap_or_else(|| "postgres".into()));
        p.password = get("POSTGRES_PASSWORD");
        return Some((p, "POSTGRES_*"));
    }
    if vars.contains_key("PGHOST") || vars.contains_key("PGDATABASE") {
        let mut p = parsed(DatabaseType::POSTGRESQL);
        p.host = Some(get("PGHOST").unwrap_or_else(|| "localhost".into()));
        p.port = port("PGPORT");
        p.database = get("PGDATABASE");
        p.user = get("PGUSER");
        p.password = get("PGPASSWORD");
        return Some((p, "PG*"));
    }
    if vars
        .keys()
        .any(|k| k.starts_with("MYSQL_") && k != "MYSQL_ALLOW_EMPTY_PASSWORD")
    {
        let user = get("MYSQL_USER").unwrap_or_else(|| "root".into());
        let mut p = parsed(DatabaseType::MYSQL);
        p.host = Some(get("MYSQL_HOST").unwrap_or_else(|| "localhost".into()));
        p.port = port("MYSQL_PORT").or_else(|| port("MYSQL_TCP_PORT"));
        p.database = get("MYSQL_DATABASE");
        p.password = if user == "root" {
            get("MYSQL_ROOT_PASSWORD").or_else(|| get("MYSQL_PASSWORD"))
        } else {
            get("MYSQL_PASSWORD")
        };
        p.user = Some(user);
        return Some((p, "MYSQL_*"));
    }
    None
}

/// `KEY=value` lines as dotenv reads them: `export` prefixes, single quotes taken literally, double quotes with
/// escapes, `#` comments after unquoted values, and `${VAR}` / `$VAR` references to earlier variables.
pub fn parse(text: &str) -> IndexMap<String, String> {
    static KEY: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"^[A-Za-z_][A-Za-z0-9_.]*$").unwrap());
    static COMMENT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+#.*$").unwrap());
    let mut vars: IndexMap<String, String> = IndexMap::new();
    let lines: Vec<&str> = text.split('\n').collect();
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i].trim();
        i += 1;
        let line = line.strip_prefix("export ").unwrap_or(line).trim_start();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some(eq) = line.find('=').filter(|e| *e > 0) else {
            continue;
        };
        let key = line[..eq].trim();
        if !KEY.is_match(key) {
            continue;
        }
        let mut raw = line[eq + 1..].trim_start().to_owned();
        let value = if let Some(rest) = raw.strip_prefix('\'') {
            rest.split('\'').next().unwrap_or("").to_owned()
        } else if raw.starts_with('"') {
            // A double-quoted value may continue on the next lines.
            while !closes_quote(&raw) && i < lines.len() {
                raw.push('\n');
                raw.push_str(lines[i]);
                i += 1;
            }
            let body = &raw[1..];
            let end = closing_quote(body);
            interpolate(&unescape(&body[..end]), &vars)
        } else {
            interpolate(COMMENT.replace(&raw, "").trim(), &vars)
        };
        vars.insert(key.to_owned(), value);
    }
    vars
}

/// Byte index of the closing quote in `body`, or its length.
fn closing_quote(body: &str) -> usize {
    let bytes = body.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\\' => i += 1,
            b'"' => return i,
            _ => {}
        }
        i += 1;
    }
    body.len()
}

fn closes_quote(raw: &str) -> bool {
    closing_quote(&raw[1..]) < raw.len() - 1
}

fn unescape(value: &str) -> String {
    let mut out = String::new();
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some(other) => out.push(other),
                None => out.push('\\'),
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn interpolate(value: &str, vars: &IndexMap<String, String>) -> String {
    static RE: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?\}|\$([A-Za-z_][A-Za-z0-9_]*)")
            .unwrap()
    });
    RE.replace_all(value, |c: &regex::Captures| {
        let name = c.get(1).or_else(|| c.get(4)).map_or("", |m| m.as_str());
        vars.get(name)
            .filter(|v| !v.is_empty())
            .cloned()
            .unwrap_or_else(|| c.get(3).map_or("", |m| m.as_str()).to_owned())
    })
    .into_owned()
}

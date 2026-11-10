//! libpq's password file (`host:port:database:user:password`, `*` wildcards, `\:` escapes) and connection service
//! file (`[name]` sections of `host=`, `port=`, `dbname=`...), as documented in the PostgreSQL manual
//! (`imports/PgPassImporter.kt`).

use super::{ImportCandidate, ImportScan, Profile, from_parsed, parsed};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tg_sql::model::DatabaseType;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub host: String,
    pub port: String,
    pub database: String,
    pub user: String,
    pub password: String,
}

/// `$PGPASSFILE`, else `~/.pgpass` (`%APPDATA%\postgresql\pgpass.conf` on Windows).
pub fn pass_file(home: &Path, env: &HashMap<String, String>) -> PathBuf {
    if let Some(p) = env.get("PGPASSFILE") {
        return PathBuf::from(p);
    }
    if cfg!(windows)
        && let Some(a) = env.get("APPDATA")
    {
        return Path::new(a).join("postgresql").join("pgpass.conf");
    }
    home.join(".pgpass")
}

/// `$PGSERVICEFILE` or `~/.pg_service.conf`, then the system-wide file in `$PGSYSCONFDIR`.
pub fn service_files(home: &Path, env: &HashMap<String, String>) -> Vec<PathBuf> {
    let mut out = vec![
        env.get("PGSERVICEFILE")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".pg_service.conf")),
    ];
    if let Some(dir) = env.get("PGSYSCONFDIR") {
        let p = Path::new(dir).join("pg_service.conf");
        if !out.contains(&p) {
            out.push(p);
        }
    }
    out
}

pub fn entries(text: &str) -> Vec<Entry> {
    text.lines()
        .map(|l| l.trim_end_matches('\r'))
        .filter(|l| !l.trim().is_empty() && !l.trim_start().starts_with('#'))
        .filter_map(|line| {
            let f = split_fields(line);
            (f.len() >= 5).then(|| Entry {
                host: f[0].clone(),
                port: f[1].clone(),
                database: f[2].clone(),
                user: f[3].clone(),
                password: f[4..].join(":"),
            })
        })
        .collect()
}

/// Fields separated by unescaped colons; `\:` and `\\` are a literal colon and backslash.
fn split_fields(line: &str) -> Vec<String> {
    let chars: Vec<char> = line.chars().collect();
    let mut fields = Vec::new();
    let mut current = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '\\' && i + 1 < chars.len() {
            i += 1;
            current.push(chars[i]);
        } else if c == ':' && fields.len() < 4 {
            fields.push(std::mem::take(&mut current));
        } else {
            current.push(c);
        }
        i += 1;
    }
    fields.push(current);
    fields
}

/// The first matching entry's password, as libpq picks it.
pub fn lookup(
    entries: &[Entry],
    host: &str,
    port: i32,
    database: &str,
    user: &str,
) -> Option<String> {
    let hosts: Vec<&str> = if host == "127.0.0.1" || host == "::1" {
        vec![host, "localhost"]
    } else {
        vec![host]
    };
    let matches = |pattern: &str, value: &str| pattern == "*" || pattern == value;
    entries
        .iter()
        .find(|e| {
            (e.host == "*" || hosts.contains(&e.host.as_str()))
                && matches(&e.port, &port.to_string())
                && matches(&e.database, database)
                && matches(&e.user, user)
        })
        .map(|e| e.password.clone())
}

pub fn read_pass_file(path: &Path) -> Result<ImportScan, String> {
    let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    let mut scan = ImportScan::default();
    for entry in entries(&text) {
        // A wildcard host or user is a password for many servers, not a server to connect to.
        if entry.host == "*" || entry.user == "*" {
            scan.skipped.push(format!(
                ".pgpass: “{}:{}:{}:{}” uses a wildcard host or user.",
                entry.host, entry.port, entry.database, entry.user
            ));
            continue;
        }
        let database = if entry.database == "*" {
            String::new()
        } else {
            entry.database.clone()
        };
        let mut p = parsed(DatabaseType::POSTGRESQL);
        p.host = Some(
            entry
                .host
                .trim_start_matches('[')
                .trim_end_matches(']')
                .to_owned(),
        );
        p.port = (entry.port != "*")
            .then(|| entry.port.parse().ok())
            .flatten();
        p.database = Some(database.clone());
        p.user = Some(entry.user.clone());
        let name = format!(
            "{}@{}{}",
            entry.user,
            entry.host,
            if database.is_empty() {
                String::new()
            } else {
                format!("/{database}")
            }
        );
        let config = from_parsed(
            &p,
            Profile {
                name: &name,
                ..Profile::default()
            },
        );
        let mut candidate = ImportCandidate::new(config, path.display().to_string());
        candidate.password = Some(entry.password);
        scan.candidates.push(candidate);
    }
    Ok(scan)
}

pub fn read_service_file(path: &Path) -> Result<ImportScan, String> {
    let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    let mut scan = ImportScan::default();
    let mut name: Option<String> = None;
    let mut params: HashMap<String, String> = HashMap::new();
    let mut flush = |name: &Option<String>, params: &HashMap<String, String>| {
        let Some(service) = name else { return };
        let mut p = parsed(DatabaseType::POSTGRESQL);
        for (from, to) in [
            ("sslmode", "sslmode"),
            ("application_name", "ApplicationName"),
            ("connect_timeout", "connectTimeout"),
        ] {
            if let Some(v) = params.get(from) {
                p.properties.insert(to.into(), v.clone());
            }
        }
        p.host = Some(
            params
                .get("host")
                .map(|h| h.split(',').next().unwrap_or("").to_owned())
                .filter(|h| !h.starts_with('/'))
                .unwrap_or_else(|| "localhost".into()),
        );
        p.port = params
            .get("port")
            .and_then(|v| v.split(',').next()?.parse().ok());
        p.database = Some(params.get("dbname").cloned().unwrap_or_default());
        p.user = Some(params.get("user").cloned().unwrap_or_default());
        let config = from_parsed(
            &p,
            Profile {
                name: service,
                ..Profile::default()
            },
        );
        let mut candidate = ImportCandidate::new(config, path.display().to_string());
        candidate.password = params.get("password").cloned();
        scan.candidates.push(candidate);
    };
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            flush(&name, &params);
            name = Some(line[1..line.len() - 1].trim().to_owned());
            params = HashMap::new();
        } else if let Some((k, v)) = line.split_once('=') {
            params.insert(k.trim().to_lowercase(), v.trim().to_owned());
        }
    }
    flush(&name, &params);
    Ok(scan)
}

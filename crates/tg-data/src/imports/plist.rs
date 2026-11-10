//! A macOS SQL client's `Connections.plist`, with folder names from `ConnectionGroups.plist` next to it
//! (`imports/PlistConnectionsImporter.kt`). Passwords stay in that client's Keychain items, which other apps can't read.

use super::xml::{self, Element};
use super::{ImportCandidate, ImportScan, Profile, environment_of, from_parsed, parsed, user_name};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use tg_core::connection::{SshAuth, SshConfig};
use tg_sql::model::{DatabaseType, EngineFamily};

/// A property list value.
#[derive(Clone, Debug, PartialEq)]
pub enum PlistValue {
    Dict(BTreeMap<String, PlistValue>),
    Array(Vec<PlistValue>),
    Bool(bool),
    Integer(i64),
    Real(f64),
    String(String),
}

impl PlistValue {
    /// The value as text, as Kotlin's `toString()` prints it.
    fn text(&self) -> String {
        match self {
            Self::String(s) => s.clone(),
            Self::Integer(i) => i.to_string(),
            Self::Real(r) => r.to_string(),
            Self::Bool(b) => b.to_string(),
            other => format!("{other:?}"),
        }
    }
}

/// Where the client keeps it: the App Store / direct download build, then the Setapp one.
pub fn locations(home: &Path) -> Vec<PathBuf> {
    ["com.tinyapp.TablePlus", "com.tinyapp.TablePlus-setapp"]
        .iter()
        .map(|id| {
            home.join(format!(
                "Library/Application Support/{id}/Data/Connections.plist"
            ))
        })
        .collect()
}

fn string(d: &BTreeMap<String, PlistValue>, key: &str) -> Option<String> {
    d.get(key).map(PlistValue::text)
}

fn boolean(d: &BTreeMap<String, PlistValue>, key: &str) -> bool {
    match d.get(key) {
        Some(PlistValue::Bool(b)) => *b,
        Some(v) => {
            let t = v.text();
            t == "1" || t.eq_ignore_ascii_case("true")
        }
        None => false,
    }
}

pub fn read(file: &Path) -> Result<ImportScan, String> {
    let Some(PlistValue::Array(connections)) = read_plist(file)? else {
        return Ok(ImportScan {
            candidates: vec![],
            skipped: vec![format!("{} isn't a connection list.", file.display())],
        });
    };
    let groups: BTreeMap<String, String> =
        match read_plist(&file.with_file_name("ConnectionGroups.plist"))
            .ok()
            .flatten()
        {
            Some(PlistValue::Array(items)) => items
                .iter()
                .filter_map(|g| match g {
                    PlistValue::Dict(d) => {
                        Some((string(d, "ID")?, string(d, "Name").unwrap_or_default()))
                    }
                    _ => None,
                })
                .collect(),
            _ => BTreeMap::new(),
        };
    let mut scan = ImportScan::default();
    for entry in &connections {
        let PlistValue::Dict(entry) = entry else {
            continue;
        };
        let name = string(entry, "ConnectionName").unwrap_or_default();
        let driver = string(entry, "Driver").unwrap_or_default();
        let Some(db_type) = type_of(&driver) else {
            scan.skipped.push(format!(
                "Connections.plist: “{name}” uses {driver}, which TableGrip doesn't support yet."
            ));
            continue;
        };
        let tls = string(entry, "tLSMode")
            .and_then(|m| m.parse::<i32>().ok())
            .unwrap_or(0)
            > 0;
        let mut p = parsed(db_type);
        p.host = string(entry, "DatabaseHost")
            .filter(|h| !h.is_empty())
            .filter(|_| db_type.is_server());
        p.port = string(entry, "DatabasePort")
            .and_then(|v| v.parse().ok())
            .filter(|_| db_type.is_server());
        p.database = if db_type.is_file() {
            string(entry, "DatabasePath")
        } else {
            string(entry, "DatabaseName")
        };
        p.user = string(entry, "DatabaseUser");
        if tls {
            match db_type.family {
                EngineFamily::PostgreSQL => {
                    p.properties.insert("sslmode".into(), "require".into());
                }
                EngineFamily::Redis => {
                    p.properties.insert("ssl".into(), "true".into());
                }
                _ => {}
            }
        }
        let group = string(entry, "GroupID")
            .and_then(|g| groups.get(&g).cloned())
            .unwrap_or_default();
        let config = from_parsed(
            &p,
            Profile {
                name: &name,
                group: &group,
                environment: environment_of(
                    string(entry, "Enviroment")
                        .or_else(|| string(entry, "Environment"))
                        .as_deref(),
                ),
                ssh: ssh(entry),
                ..Profile::default()
            },
        );
        let mut notes = Vec::new();
        if driver.eq_ignore_ascii_case("Redshift") {
            notes.push("Redshift is imported as PostgreSQL".into());
        }
        if tls && p.properties.is_empty() {
            notes.push(
                "TLS was on in the source: turn it on under Options › Driver properties".into(),
            );
        }
        let mut candidate = ImportCandidate::new(config, file.display().to_string());
        candidate.notes = notes;
        scan.candidates.push(candidate);
    }
    Ok(scan)
}

fn ssh(entry: &BTreeMap<String, PlistValue>) -> Option<SshConfig> {
    if !boolean(entry, "isOverSSH") {
        return None;
    }
    let host = string(entry, "ServerAddress").filter(|h| !h.is_empty())?;
    let use_key = boolean(entry, "isUsePrivateKey");
    let key_path = ["PrivateKeyPath", "ServerPrivateKeyPath", "PrivateKey"]
        .iter()
        .find_map(|k| string(entry, k).filter(|v| v.starts_with('/') || v.starts_with('~')));
    let user = string(entry, "ServerUser")
        .filter(|u| !u.is_empty())
        .unwrap_or_else(user_name);
    let mut config = SshConfig::new(&host, &user);
    config.port = string(entry, "ServerPort")
        .and_then(|p| p.parse().ok())
        .unwrap_or(22);
    config.auth = if use_key {
        SshAuth::PrivateKey
    } else {
        SshAuth::Password
    };
    config.private_key_path = key_path.filter(|_| use_key);
    Some(config)
}

fn type_of(driver: &str) -> Option<DatabaseType> {
    Some(match driver.to_lowercase().as_str() {
        "postgresql" | "postgres" | "redshift" => DatabaseType::POSTGRESQL,
        "mysql" => DatabaseType::MYSQL,
        "mariadb" => DatabaseType::MARIADB,
        "sqlite" => DatabaseType::SQLITE,
        "sqlserver" | "microsoft sql server" | "mssql" => DatabaseType::SQLSERVER,
        "cockroachdb" | "cockroach" => DatabaseType::COCKROACHDB,
        "clickhouse" => DatabaseType::CLICKHOUSE,
        "duckdb" => DatabaseType::DUCKDB,
        "redis" => DatabaseType::REDIS,
        "mongodb" | "mongo" => DatabaseType::MONGODB,
        _ => return None,
    })
}

/// Reads an XML property list, or a binary one through macOS' `plutil`.
pub fn read_plist(file: &Path) -> Result<Option<PlistValue>, String> {
    let bytes = std::fs::read(file).map_err(|e| e.to_string())?;
    let text = if bytes.starts_with(b"bplist") {
        let out = std::process::Command::new("plutil")
            .args(["-convert", "xml1", "-o", "-"])
            .arg(file)
            .output()
            .map_err(|e| e.to_string())?;
        if !out.status.success() {
            return Err(format!("plutil couldn't read {}", file.display()));
        }
        String::from_utf8_lossy(&out.stdout).into_owned()
    } else {
        String::from_utf8_lossy(&bytes).into_owned()
    };
    parse_xml_plist(&text)
}

pub fn parse_xml_plist(text: &str) -> Result<Option<PlistValue>, String> {
    let root = xml::parse(text)?;
    Ok(root.elements().next().map(value))
}

fn value(element: &Element) -> PlistValue {
    match element.name.as_str() {
        "dict" => {
            let children: Vec<&Element> = element.elements().collect();
            PlistValue::Dict(
                children
                    .chunks(2)
                    .filter(|c| c.len() == 2 && c[0].name == "key")
                    .map(|c| (c[0].text(), value(c[1])))
                    .collect(),
            )
        }
        "array" => PlistValue::Array(element.elements().map(value).collect()),
        "true" => PlistValue::Bool(true),
        "false" => PlistValue::Bool(false),
        "integer" => element
            .text()
            .trim()
            .parse()
            .map_or(PlistValue::String(element.text()), PlistValue::Integer),
        "real" => element
            .text()
            .trim()
            .parse()
            .map_or(PlistValue::String(element.text()), PlistValue::Real),
        _ => PlistValue::String(element.text()),
    }
}

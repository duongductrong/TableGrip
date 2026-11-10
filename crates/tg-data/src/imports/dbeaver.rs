//! DBeaver's `data-sources*.json`, one set per project, with the logins it keeps in the project's
//! `credentials-config.json` (`imports/DBeaverImporter.kt`). That file is encrypted with a key built into DBeaver
//! (the same on every install), so it can be read unless the user protected it with a master password.

use super::{
    ImportCandidate, ImportScan, Profile, environment_of, from_parsed, parse_or_none, parsed,
    user_name,
};
use aes::cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit, block_padding::Pkcs7};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tg_core::connection::string::ParsedConnection;
use tg_core::connection::{SshAuth, SshConfig};
use tg_sql::model::DatabaseType;

/// DBeaver's built-in key for `credentials-config.json`: AES-128-CBC, the IV in the first 16 bytes.
const KEY: [u8; 16] = [
    0xba, 0xbb, 0x4a, 0x9f, 0x77, 0x4a, 0xb8, 0x53, 0xc9, 0x6c, 0x2d, 0x65, 0x3d, 0xfe, 0x54, 0x4a,
];

/// Every project's data source files in the workspace of this OS.
pub fn locations(home: &Path, env: &HashMap<String, String>) -> Vec<PathBuf> {
    let mut roots = vec![
        home.join("Library/DBeaverData/workspace6"),
        home.join(".local/share/DBeaverData/workspace6"),
    ];
    if let Some(a) = env.get("APPDATA") {
        roots.push(Path::new(a).join("DBeaverData").join("workspace6"));
    }
    let mut out = Vec::new();
    for root in roots.into_iter().filter(|r| r.is_dir()) {
        let mut projects: Vec<PathBuf> = std::fs::read_dir(&root)
            .into_iter()
            .flatten()
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.is_dir()
                    && !p
                        .file_name()
                        .is_some_and(|n| n.to_string_lossy().starts_with('.'))
            })
            .collect();
        projects.sort();
        for project in projects {
            let config = project.join(".dbeaver");
            let mut files: Vec<PathBuf> = std::fs::read_dir(&config)
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| e.path())
                .filter(|p| {
                    let name = p
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    p.is_file() && name.starts_with("data-sources") && name.ends_with(".json")
                })
                .collect();
            files.sort();
            out.extend(files);
        }
    }
    out
}

fn string(o: &Map<String, Value>, key: &str) -> Option<String> {
    match o.get(key)? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn boolean(o: &Map<String, Value>, key: &str) -> Option<bool> {
    match o.get(key)? {
        Value::Bool(b) => Some(*b),
        Value::String(s) => s.parse().ok(),
        _ => None,
    }
}

fn object<'a>(o: &'a Map<String, Value>, key: &str) -> Option<&'a Map<String, Value>> {
    o.get(key)?.as_object()
}

/// User and password for the database and for the SSH tunnel.
#[derive(Clone, Debug, Default)]
pub struct Login {
    pub connection: Option<(Option<String>, Option<String>)>,
    pub ssh: Option<(Option<String>, Option<String>)>,
}

pub fn read(file: &Path) -> Result<ImportScan, String> {
    let root: Value =
        serde_json::from_str(&std::fs::read_to_string(file).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
    let Some(connections) = root.get("connections").and_then(Value::as_object) else {
        return Ok(ImportScan::default());
    };
    let credentials = file
        .parent()
        .map(|p| p.join("credentials-config.json"))
        .filter(|p| p.is_file())
        .and_then(|p| std::fs::read(p).ok())
        .and_then(|b| decrypt_credentials(&b).ok());
    // The workspace's default project is "General"; others become part of the group.
    let project = file
        .parent()
        .and_then(Path::parent)
        .and_then(Path::file_name)
        .map(|n| n.to_string_lossy().into_owned())
        .filter(|n| n != "General");
    let empty = Map::new();
    let mut scan = ImportScan::default();
    for (id, element) in connections {
        let Some(connection) = element.as_object() else {
            continue;
        };
        let name = string(connection, "name").unwrap_or_else(|| id.clone());
        let configuration = object(connection, "configuration").unwrap_or(&empty);
        let db_type = type_of(
            string(connection, "provider").as_deref(),
            string(connection, "driver").as_deref(),
        );
        let url = string(configuration, "url");
        let parsed = match db_type {
            None if url.as_deref().is_some_and(|u| u.starts_with("jdbc:")) => {
                url.as_deref().and_then(parse_or_none)
            }
            None => None,
            Some(t)
                if string(configuration, "configurationType").as_deref() == Some("URL")
                    || (string(configuration, "host").is_none_or(|h| h.is_empty())
                        && t.is_server()) =>
            {
                url.as_deref().and_then(parse_or_none).map(|mut p| {
                    if p.db_type.family != t.family {
                        p.db_type = t;
                    }
                    p
                })
            }
            Some(t) => Some(fields(t, configuration)),
        };
        let Some(mut parsed) = parsed else {
            scan.skipped.push(format!(
                "DBeaver: “{name}” ({}) isn't supported.",
                string(connection, "provider").unwrap_or_else(|| "unknown".into())
            ));
            continue;
        };
        let login = credentials.as_ref().and_then(|c| c.get(id)).cloned();
        let ssh = ssh(configuration, login.as_ref());
        let folder = string(connection, "folder").unwrap_or_default();
        parsed.user = login
            .as_ref()
            .and_then(|l| l.connection.as_ref().and_then(|c| c.0.clone()))
            .or_else(|| string(configuration, "user"))
            .or(parsed.user);
        let group: Vec<String> = project
            .iter()
            .cloned()
            .chain((!folder.is_empty()).then_some(folder))
            .collect();
        let group = group.join("/");
        let config = from_parsed(
            &parsed,
            Profile {
                name: &name,
                group: &group,
                environment: environment_of(string(configuration, "type").as_deref()),
                read_only: boolean(connection, "read-only")
                    .or_else(|| boolean(configuration, "read-only"))
                    .unwrap_or(false),
                ssh,
                db_type: None,
            },
        );
        let password = login
            .as_ref()
            .and_then(|l| l.connection.as_ref().and_then(|c| c.1.clone()))
            .or_else(|| string(configuration, "password"))
            .or(parsed.password.clone());
        let mut notes = parsed.warnings.clone();
        if password.is_none()
            && boolean(connection, "save-password") == Some(true)
            && credentials.is_none()
        {
            notes.push("DBeaver's saved password couldn't be read".into());
        }
        if parsed.db_type.id == DatabaseType::OTHER_JDBC.id {
            notes.push("Choose the driver's jar when connecting".into());
        }
        let mut candidate = ImportCandidate::new(config, file.display().to_string());
        candidate.password = password.filter(|p| !p.is_empty());
        candidate.ssh_secret = login.and_then(|l| l.ssh.and_then(|s| s.1));
        candidate.notes = notes;
        scan.candidates.push(candidate);
    }
    Ok(scan)
}

/// DBeaver keeps host, port and database as separate fields (the port as a string).
fn fields(db_type: DatabaseType, configuration: &Map<String, Value>) -> ParsedConnection {
    let mut p = parsed(db_type);
    if let Some(props) = object(configuration, "properties") {
        for (k, v) in props {
            if let Some(s) = match v {
                Value::String(s) => Some(s.clone()),
                Value::Number(n) => Some(n.to_string()),
                Value::Bool(b) => Some(b.to_string()),
                _ => None,
            } {
                p.properties.insert(k.clone(), s);
            }
        }
    }
    p.host = string(configuration, "host").filter(|_| db_type.is_server());
    p.port = string(configuration, "port")
        .and_then(|v| v.parse().ok())
        .filter(|_| db_type.is_server());
    p.database = string(configuration, "database").or_else(|| {
        string(configuration, "url")
            .filter(|_| db_type.is_file())
            .map(|u| {
                let after = u.split_once(':').map_or(u.as_str(), |(_, r)| r);
                after.split_once(':').map_or(after, |(_, r)| r).to_owned()
            })
    });
    p.user = string(configuration, "user");
    p
}

fn ssh(configuration: &Map<String, Value>, login: Option<&Login>) -> Option<SshConfig> {
    let handler = object(configuration, "handlers").and_then(|h| object(h, "ssh_tunnel"))?;
    if boolean(handler, "enabled") != Some(true) {
        return None;
    }
    let empty = Map::new();
    let properties = object(handler, "properties").unwrap_or(&empty);
    let host = string(properties, "host")?;
    let auth = string(properties, "authType");
    let mut config = SshConfig::new(
        &host,
        &login
            .and_then(|l| l.ssh.as_ref().and_then(|s| s.0.clone()))
            .or_else(|| string(handler, "user"))
            .or_else(|| string(properties, "user"))
            .unwrap_or_else(user_name),
    );
    config.port = string(properties, "port")
        .and_then(|p| p.parse().ok())
        .unwrap_or(22);
    config.auth = if auth.as_deref() == Some("PASSWORD") {
        SshAuth::Password
    } else {
        SshAuth::PrivateKey
    };
    config.private_key_path = string(properties, "keyPath")
        .filter(|k| auth.as_deref() == Some("PUBLIC_KEY") && !k.trim().is_empty());
    Some(config)
}

/// DBeaver's provider and driver ids; Redis and MongoDB come from its commercial editions.
fn type_of(provider: Option<&str>, driver: Option<&str>) -> Option<DatabaseType> {
    let d = driver.unwrap_or("").to_lowercase();
    Some(if d.contains("cockroach") {
        DatabaseType::COCKROACHDB
    } else if d.contains("timescale") {
        DatabaseType::TIMESCALEDB
    } else if d.contains("mariadb") {
        DatabaseType::MARIADB
    } else if d.contains("tidb") {
        DatabaseType::TIDB
    } else if d.contains("duckdb") {
        DatabaseType::DUCKDB
    } else if d.contains("clickhouse") {
        DatabaseType::CLICKHOUSE
    } else if d.contains("sqlite") {
        DatabaseType::SQLITE
    } else if d.starts_with("h2") {
        DatabaseType::H2
    } else if d.contains("hsql") {
        DatabaseType::HSQLDB
    } else if d.contains("derby") {
        DatabaseType::DERBY
    } else {
        match provider.map(str::to_lowercase).as_deref() {
            Some("postgresql") => DatabaseType::POSTGRESQL,
            Some("mysql") => DatabaseType::MYSQL,
            Some("sqlite") => DatabaseType::SQLITE,
            Some("sqlserver" | "mssql") => DatabaseType::SQLSERVER,
            Some("clickhouse") => DatabaseType::CLICKHOUSE,
            Some("duckdb") => DatabaseType::DUCKDB,
            Some("redis") => DatabaseType::REDIS,
            Some("mongodb") => DatabaseType::MONGODB,
            _ => return None,
        }
    })
}

type Decryptor = cbc::Decryptor<aes::Aes128>;
type Encryptor = cbc::Encryptor<aes::Aes128>;

pub fn decrypt_credentials(bytes: &[u8]) -> Result<HashMap<String, Login>, String> {
    if bytes.len() < 32 {
        return Err("too short".into());
    }
    let mut data = bytes[16..].to_vec();
    let plain = Decryptor::new(&KEY.into(), bytes[..16].into())
        .decrypt_padded_mut::<Pkcs7>(&mut data)
        .map_err(|e| e.to_string())?;
    let value: Value = serde_json::from_slice(plain).map_err(|e| e.to_string())?;
    let empty = Map::new();
    Ok(value
        .as_object()
        .unwrap_or(&empty)
        .iter()
        .map(|(id, v)| {
            let pair = |key: &str| {
                v.get(key)
                    .and_then(Value::as_object)
                    .map(|o| (string(o, "user"), string(o, "password")))
            };
            (
                id.clone(),
                Login {
                    connection: pair("#connection"),
                    ssh: pair("network/ssh_tunnel"),
                },
            )
        })
        .collect())
}

/// For tests: the inverse of [`decrypt_credentials`].
pub fn encrypt_credentials(text: &str, iv: [u8; 16]) -> Vec<u8> {
    let mut buffer = text.as_bytes().to_vec();
    let len = buffer.len();
    buffer.resize(len + 16, 0);
    let cipher = Encryptor::new(&KEY.into(), &iv.into())
        .encrypt_padded_mut::<Pkcs7>(&mut buffer, len)
        .map(<[u8]>::to_vec)
        .unwrap_or_default();
    iv.iter().copied().chain(cipher).collect()
}

//! Data sources of IDE database tools (`imports/IdeDataSourcesImporter.kt`): each project's `.idea/dataSources.xml`
//! (the shared part: name, driver, JDBC URL) and `dataSources.local.xml` (user name, SSH, read-only), plus the
//! IDE-wide SSH configurations they refer to. Projects are found through each IDE's recent projects list. Passwords
//! stay in the IDE's own credential store.

use super::xml::{self, Element};
use super::{ImportCandidate, ImportScan, Profile, from_parsed, parse_or_none, user_name};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tg_core::connection::{SshAuth, SshConfig};
use tg_sql::model::DatabaseType;

/// The IDEs' configuration folders, newest version of each product last so it wins when they disagree.
fn config_dirs(home: &Path) -> Vec<PathBuf> {
    let mut bases = vec![
        home.join("Library/Application Support/JetBrains"),
        home.join(".config/JetBrains"),
    ];
    if let Ok(a) = std::env::var("APPDATA") {
        bases.push(Path::new(&a).join("JetBrains"));
    }
    let mut out = Vec::new();
    for base in bases.into_iter().filter(|b| b.is_dir()) {
        let mut dirs: Vec<PathBuf> = std::fs::read_dir(&base)
            .into_iter()
            .flatten()
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.is_dir() && p.join("options").is_dir())
            .collect();
        dirs.sort();
        out.extend(dirs);
    }
    out
}

fn parse(file: &Path) -> Result<Element, String> {
    xml::parse(&std::fs::read_to_string(file).map_err(|e| e.to_string())?)
}

/// `dataSources.xml` of every recently opened project, and the IDEs' global data sources.
pub fn locations(home: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = Vec::new();
    let mut add = |p: PathBuf| {
        if !files.contains(&p) {
            files.push(p);
        }
    };
    for dir in config_dirs(home) {
        let global = dir.join("options/dataSources.xml");
        if global.is_file() {
            add(global);
        }
        let recent = dir.join("options/recentProjects.xml");
        let Ok(document) = parse(&recent) else {
            continue;
        };
        for entry in document.all("entry") {
            let key = entry.attribute("key");
            if key.is_empty() {
                continue;
            }
            let project = PathBuf::from(key.replace("$USER_HOME$", &home.display().to_string()));
            let file = project.join(".idea/dataSources.xml");
            if file.is_file() {
                add(file);
            }
        }
    }
    files
}

pub fn read(file: &Path, home: &Path) -> Result<ImportScan, String> {
    let shared = parse(file)?;
    let local_path = file.with_file_name(
        file.file_name()
            .map(|n| {
                n.to_string_lossy()
                    .replace("dataSources", "dataSources.local")
            })
            .unwrap_or_default(),
    );
    let local = local_path
        .is_file()
        .then(|| parse(&local_path).ok())
        .flatten();
    let local_by_id: HashMap<String, &Element> = local
        .as_ref()
        .map(|l| {
            l.all("data-source")
                .into_iter()
                .map(|e| (e.attribute("uuid"), e))
                .collect()
        })
        .unwrap_or_default();
    let ssh_configs = ssh_configs(home);
    // `$PROJECT_DIR$` points at the folder holding `.idea`; global data sources have none.
    let project_dir = file
        .parent()
        .filter(|p| p.file_name().is_some_and(|n| n == ".idea"))
        .and_then(Path::parent);
    let mut scan = ImportScan::default();
    for source in shared.all("data-source") {
        let name = source.attribute("name");
        let details = local_by_id.get(&source.attribute("uuid")).copied();
        let Some(raw) = source.child("jdbc-url") else {
            scan.skipped
                .push(format!("IDE data sources: “{name}” has no JDBC URL."));
            continue;
        };
        let url = expand_macros(&raw, project_dir, home);
        let Some(mut parsed) = parse_or_none(&url) else {
            scan.skipped.push(format!(
                "IDE data sources: “{name}” isn't supported ({url})."
            ));
            continue;
        };
        let dbms =
            details.and_then(|d| d.all("database-info").first().map(|e| e.attribute("dbms")));
        let db_type = refine(
            parsed.db_type,
            source.child("driver-ref"),
            dbms.filter(|d| !d.is_empty()),
        );
        let ssh = details
            .and_then(|d| d.all("ssh-properties").first().copied())
            .filter(|e| e.child("enabled").as_deref() == Some("true"))
            .and_then(|e| e.child("ssh-config-id"))
            .and_then(|id| ssh_configs.get(&id).cloned());
        if let Some(user) = details.and_then(|d| d.child("user-name")) {
            parsed.user = Some(user);
        }
        let group = Some(source.attribute("group"))
            .filter(|g| !g.is_empty())
            .unwrap_or_else(|| {
                project_dir
                    .and_then(Path::file_name)
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default()
            });
        let config = from_parsed(
            &parsed,
            Profile {
                name: &name,
                group: &group,
                read_only: details.and_then(|d| d.child("read-only")).as_deref() == Some("true")
                    || source.child("read-only").as_deref() == Some("true"),
                ssh,
                db_type: Some(db_type),
                ..Profile::default()
            },
        );
        let mut notes = parsed.warnings.clone();
        if db_type.id == DatabaseType::OTHER_JDBC.id {
            notes.push("Choose the driver's jar when connecting".into());
        }
        let mut candidate = ImportCandidate::new(config, file.display().to_string());
        candidate.password = parsed.password.clone();
        candidate.notes = notes;
        scan.candidates.push(candidate);
    }
    Ok(scan)
}

/// The JDBC URL says the protocol; the IDE's driver and the server it saw say which engine of the family it is.
fn refine(db_type: DatabaseType, driver_ref: Option<String>, dbms: Option<String>) -> DatabaseType {
    let key = dbms
        .map(|d| d.to_uppercase())
        .or_else(|| driver_ref.map(|r| r.split('.').next().unwrap_or("").to_uppercase()));
    let hint = match key.as_deref() {
        Some("MARIADB") => Some(DatabaseType::MARIADB),
        Some("COCKROACH" | "COCKROACHDB") => Some(DatabaseType::COCKROACHDB),
        Some("TIDB") => Some(DatabaseType::TIDB),
        Some("AZURE" | "AZURESQL") => Some(DatabaseType::AZURESQL),
        Some("VALKEY") => Some(DatabaseType::VALKEY),
        _ => None,
    };
    match hint {
        Some(h) if h.family == db_type.family => h,
        _ => db_type,
    }
}

fn expand_macros(url: &str, project_dir: Option<&Path>, home: &Path) -> String {
    let mut out = url.replace("$USER_HOME$", &home.display().to_string());
    if let Some(p) = project_dir {
        let p = p.display().to_string();
        out = out
            .replace("$PROJECT_DIR$", &p)
            .replace("$ProjectFileDir$", &p);
    }
    out
}

/// The IDE-wide SSH configurations, by id.
fn ssh_configs(home: &Path) -> HashMap<String, SshConfig> {
    let mut out = HashMap::new();
    for dir in config_dirs(home) {
        let Ok(document) = parse(&dir.join("options/sshConfigs.xml")) else {
            continue;
        };
        for e in document.all("sshConfig") {
            let (id, host) = (e.attribute("id"), e.attribute("host"));
            if id.is_empty() || host.is_empty() {
                continue;
            }
            let user = Some(e.attribute("username"))
                .filter(|u| !u.is_empty())
                .unwrap_or_else(user_name);
            let mut config = SshConfig::new(&host, &user);
            config.port = e.attribute("port").parse().unwrap_or(22);
            config.auth = if e.attribute("authType") == "PASSWORD" {
                SshAuth::Password
            } else {
                SshAuth::PrivateKey
            };
            config.private_key_path = Some(e.attribute("keyPath"))
                .filter(|k| !k.is_empty())
                .map(|k| k.replace("$USER_HOME$", &home.display().to_string()));
            out.insert(id, config);
        }
    }
    out
}

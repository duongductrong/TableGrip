//! Saved connection profiles and the form that edits them (`connections/ConnectionsViewModel.kt`,
//! `connections/ConnectionDialog.kt`'s `ConnectionForm`).
//!
//! [`Connections`] holds the list the explorer shows and decides what group and favourite changes mean; it returns the
//! profiles to write and the app writes them through [`tg_core::connection::ConnectionStore`] off the UI thread, one
//! write at a time. [`ConnectionForm`] is the dialog's state as plain strings, validated and turned into a profile
//! exactly as the Kotlin form does.

use std::path::Path;

use indexmap::IndexMap;
use tg_core::connection::string::{self as connection_string, ParsedConnection};
use tg_core::connection::{
    AiAccess, ConnectionConfig, DataFileOptions, DriverId, Environment, SshAuth, SshConfig,
    files as database_files,
};
use tg_data::datafile::DataFileStore;
use tg_sql::model::{ConnectionKind, DatabaseType, EngineFamily, Paradigm};

/// The saved profiles, in their saved order.
#[derive(Clone, Debug, Default)]
pub struct Connections {
    pub profiles: Vec<ConnectionConfig>,
}

impl Connections {
    pub fn new(profiles: Vec<ConnectionConfig>) -> Self {
        Self { profiles }
    }

    pub fn by_id(&self, id: &str) -> Option<&ConnectionConfig> {
        self.profiles.iter().find(|c| c.id == id)
    }

    /// Groups in use, for "Move to Group" to offer: by name, ignoring case.
    pub fn groups(&self) -> Vec<String> {
        let mut groups: Vec<String> = Vec::new();
        for c in &self.profiles {
            let g = c.group.trim();
            if !g.is_empty() && !groups.iter().any(|x| x == g) {
                groups.push(g.to_owned());
            }
        }
        groups.sort_by_key(|g| g.to_lowercase());
        groups
    }

    /// Shows `changed` at once (a saved list replaces it later); returns them, to be saved.
    fn change(&mut self, changed: Vec<ConnectionConfig>) -> Vec<ConnectionConfig> {
        for c in &changed {
            if let Some(slot) = self.profiles.iter_mut().find(|p| p.id == c.id) {
                *slot = c.clone();
            }
        }
        changed
    }

    /// Puts `ids` in `group`, or at the top level when it's blank; they leave Favorites too, so they show up where
    /// they were put.
    pub fn move_to_group(&mut self, ids: &[String], group: &str) -> Vec<ConnectionConfig> {
        let name = group.trim();
        let moved: Vec<ConnectionConfig> = self
            .profiles
            .iter()
            .filter(|c| ids.contains(&c.id) && (c.group.trim() != name || c.favorite))
            .map(|c| ConnectionConfig {
                group: name.to_owned(),
                favorite: false,
                ..c.clone()
            })
            .collect();
        self.change(moved)
    }

    pub fn toggle_favorite(&mut self, id: &str) -> Vec<ConnectionConfig> {
        let changed = self
            .by_id(id)
            .map(|c| ConnectionConfig {
                favorite: !c.favorite,
                ..c.clone()
            })
            .into_iter()
            .collect();
        self.change(changed)
    }

    /// Renames a group, merging it into another of that name if there is one.
    pub fn rename_group(&mut self, from: &str, to: &str) -> Vec<ConnectionConfig> {
        let name = to.trim();
        if name.is_empty() || name == from {
            return Vec::new();
        }
        let changed = self
            .profiles
            .iter()
            .filter(|c| c.group.trim() == from)
            .map(|c| ConnectionConfig {
                group: name.to_owned(),
                ..c.clone()
            })
            .collect();
        self.change(changed)
    }

    /// Removes a group, moving its connections to the top level.
    pub fn ungroup(&mut self, name: &str) -> Vec<ConnectionConfig> {
        let changed = self
            .profiles
            .iter()
            .filter(|c| c.group.trim() == name)
            .map(|c| ConnectionConfig {
                group: String::new(),
                ..c.clone()
            })
            .collect();
        self.change(changed)
    }

    /// The profile of a data file at `path`: the one already pointing at it, or a new one (not saved yet: the
    /// second value says so). None when no format claims the file.
    pub fn data_file_profile(&self, path: &Path, new_id: &str) -> Option<(ConnectionConfig, bool)> {
        let path = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
        if let Some(existing) = self
            .profiles
            .iter()
            .find(|c| c.db_type.is_data_file() && DataFileStore::path_of(&c.database) == path)
        {
            return Some((existing.clone(), false));
        }
        DataFileStore::profile_for(&path, new_id, "").map(|c| (c, true))
    }
}

/// The engines a new connection can be: everything the Rust drivers open. H2, HSQLDB, Derby and Generic JDBC had only
/// Java drivers (D1); a profile of theirs still edits, with its own type listed.
pub fn connectable_types() -> Vec<&'static DatabaseType> {
    DatabaseType::ENTRIES
        .iter()
        .filter(|t| !dropped(t))
        .collect()
}

/// H2, HSQLDB, Derby and Generic JDBC: kept so their profiles load, but no Rust driver opens them (D1).
pub fn dropped(t: &DatabaseType) -> bool {
    matches!(t.family, EngineFamily::Generic)
}

/// The dialog's tabs; SSH only for server engines.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FormTab {
    General,
    Ssh,
    Options,
}

/// The connection dialog's state (`ConnectionForm`). Text fields are plain strings; the app copies its text inputs
/// in before validating.
#[derive(Clone, Debug, PartialEq)]
pub struct ConnectionForm {
    pub id: String,
    pub db_type: DatabaseType,
    pub name: String,
    pub host: String,
    pub port: String,
    pub database: String,
    pub user: String,
    pub password: String,
    pub driver: DriverId,
    /// Connect with a raw URL instead of host/port/database.
    pub use_url: bool,
    pub url: String,
    /// `key=value` per line.
    pub properties: String,
    pub connection_string: String,
    pub environment: Environment,
    pub group: String,
    favorite: bool,
    pub read_only: bool,
    pub confirm_writes: bool,
    /// None follows the app-wide setting.
    pub ai_access: Option<AiAccess>,
    pub delimiter: Option<char>,
    pub charset: Option<String>,
    pub has_header: bool,
    pub use_ssh: bool,
    pub ssh_host: String,
    pub ssh_port: String,
    pub ssh_user: String,
    pub ssh_auth: SshAuth,
    pub ssh_key_path: String,
    /// SSH password, or the private key's passphrase.
    pub ssh_secret: String,
    /// Profile fields the form doesn't show, kept as they were.
    driver_jars: Vec<String>,
}

impl ConnectionForm {
    /// A form for `existing`, or a new PostgreSQL connection in `group`. `new_id` names a new profile; `user` is the
    /// default SSH user (the OS account).
    pub fn new(existing: Option<&ConnectionConfig>, group: &str, new_id: &str, user: &str) -> Self {
        let db_type = existing.map_or(DatabaseType::POSTGRESQL, |c| c.db_type);
        let ssh = existing.and_then(|c| c.ssh.as_ref());
        Self {
            id: existing.map_or_else(|| new_id.to_owned(), |c| c.id.clone()),
            name: existing.map(|c| c.name.clone()).unwrap_or_default(),
            host: existing.map_or_else(|| "localhost".to_owned(), |c| c.host.clone()),
            port: existing
                .and_then(|c| c.port)
                .map(|p| p.to_string())
                .unwrap_or_default(),
            database: existing.map(|c| c.database.clone()).unwrap_or_default(),
            user: existing.map(|c| c.user.clone()).unwrap_or_default(),
            password: String::new(),
            driver: existing.map_or_else(
                || DriverId::for_type(&db_type)[0],
                ConnectionConfig::effective_driver,
            ),
            use_url: existing
                .is_some_and(|c| c.url.is_some() || c.db_type.connection == ConnectionKind::Url),
            url: existing.and_then(|c| c.url.clone()).unwrap_or_default(),
            properties: existing
                .map(|c| {
                    c.properties
                        .iter()
                        .map(|(k, v)| format!("{k}={v}"))
                        .collect::<Vec<_>>()
                        .join("\n")
                })
                .unwrap_or_default(),
            connection_string: String::new(),
            environment: existing.map_or(Environment::None, |c| c.environment),
            group: existing.map_or_else(|| group.to_owned(), |c| c.group.clone()),
            favorite: existing.is_some_and(|c| c.favorite),
            read_only: existing.is_some_and(|c| c.read_only),
            confirm_writes: existing.is_some_and(|c| c.confirm_writes),
            ai_access: existing.and_then(|c| c.ai_access),
            delimiter: existing
                .and_then(|c| c.data_file.as_ref())
                .and_then(|d| d.delimiter),
            charset: existing
                .and_then(|c| c.data_file.as_ref())
                .and_then(|d| d.charset.clone()),
            has_header: existing
                .and_then(|c| c.data_file.as_ref())
                .is_none_or(|d| d.has_header),
            use_ssh: ssh.is_some(),
            ssh_host: ssh.map(|s| s.host.clone()).unwrap_or_default(),
            ssh_port: ssh.map_or(22, |s| s.port).to_string(),
            ssh_user: ssh.map_or_else(|| user.to_owned(), |s| s.user.clone()),
            ssh_auth: ssh.map_or(SshAuth::PrivateKey, |s| s.auth),
            ssh_key_path: ssh
                .and_then(|s| s.private_key_path.clone())
                .unwrap_or_default(),
            ssh_secret: String::new(),
            driver_jars: existing.map(|c| c.driver_jars.clone()).unwrap_or_default(),
            db_type,
        }
    }

    pub fn is_server(&self) -> bool {
        self.db_type.is_server()
    }

    /// Reached as a relational database: a database field, a URL form.
    pub fn relational(&self) -> bool {
        self.db_type.paradigm == Paradigm::Relational && !self.data_file()
    }

    /// MongoDB and the like: a database field and a connection string.
    pub fn document(&self) -> bool {
        self.db_type.paradigm == Paradigm::Document
    }

    /// The engine has no host/file form: only a URL.
    pub fn url_only(&self) -> bool {
        self.db_type.connection == ConnectionKind::Url
    }

    /// A CSV, Excel or JSON file opened as tables.
    pub fn data_file(&self) -> bool {
        self.db_type.is_data_file()
    }

    fn ssh(&self) -> bool {
        self.use_ssh && self.is_server()
    }

    /// The tabs the dialog shows.
    pub fn tabs(&self) -> Vec<FormTab> {
        let mut tabs = vec![FormTab::General];
        if self.is_server() {
            tabs.push(FormTab::Ssh);
        }
        tabs.push(FormTab::Options);
        tabs
    }

    pub fn change_type(&mut self, db_type: &DatabaseType) {
        self.db_type = *db_type;
        let drivers = DriverId::for_type(db_type);
        if !drivers.contains(&self.driver) {
            self.driver = drivers[0];
        }
        if self.url_only() {
            self.use_url = true;
        }
    }

    /// Takes a chosen or pasted data file, switching to the type its extension names.
    pub fn use_data_file(&mut self, path: &str) {
        if let Some(profile) = DataFileStore::profile_for(Path::new(path), &self.id, "") {
            self.change_type(&profile.db_type);
        }
        self.database = path.to_owned();
    }

    /// Fills the form from a pasted connection string; returns warnings to show.
    pub fn apply(&mut self, parsed: &ParsedConnection) -> Vec<String> {
        self.change_type(&parsed.db_type);
        if let Some(d) = parsed
            .driver
            .filter(|d| DriverId::for_type(&parsed.db_type).contains(d))
        {
            self.driver = d;
        }
        self.use_url = parsed.url.is_some() || self.url_only();
        if let Some(url) = &parsed.url {
            self.url = url.clone();
        }
        if let Some(host) = &parsed.host {
            self.host = host.clone();
        }
        self.port = parsed.port.map(|p| p.to_string()).unwrap_or_default();
        if let Some(d) = &parsed.database {
            self.database = d.clone();
        }
        if let Some(u) = &parsed.user {
            self.user = u.clone();
        }
        if let Some(p) = &parsed.password {
            self.password = p.clone();
        }
        if !parsed.properties.is_empty() {
            let mut merged = self.parse_properties().unwrap_or_default();
            for (k, v) in &parsed.properties {
                merged.insert(k.clone(), v.clone());
            }
            self.properties = merged
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join("\n");
        }
        parsed.warnings.clone()
    }

    /// Parses [`Self::connection_string`] into the form ("Parse"); Err with the reason when it can't.
    pub fn parse_connection_string(&mut self) -> Result<Vec<String>, String> {
        let raw = self.connection_string.trim().to_owned();
        if raw.is_empty() {
            return Err("Paste a connection string first.".to_owned());
        }
        if DataFileStore::profile_for(Path::new(&raw), &self.id, "").is_some() {
            self.use_data_file(&raw);
            return Ok(Vec::new());
        }
        let parsed = connection_string::parse(&raw).map_err(|e| e.0)?;
        Ok(self.apply(&parsed))
    }

    /// Moves a password typed into the URL to the password field, so it ends up in the keychain, not the profile.
    pub fn move_url_password(&mut self) {
        if !self.use_url {
            return;
        }
        let (clean, secret) = connection_string::strip_secrets(self.url.trim());
        self.url = clean;
        if let Some(secret) = secret {
            self.password = secret;
        }
    }

    /// `key=value` per line; blank lines and `#` comments are ignored.
    pub fn parse_properties(&self) -> Result<IndexMap<String, String>, String> {
        let mut out = IndexMap::new();
        for line in self.properties.lines().map(str::trim) {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else {
                return Err(format!(
                    "Driver property \"{line}\" must look like key=value."
                ));
            };
            out.insert(k.trim().to_owned(), v.trim().to_owned());
        }
        Ok(out)
    }

    /// What's wrong with the form, in the words the Kotlin dialog uses; None when it can be saved or tested.
    pub fn validate(&self) -> Option<String> {
        if self.data_file() {
            return self.validate_data_file();
        }
        if let Err(e) = self.parse_properties() {
            return Some(e);
        }
        let port_ok = |p: &str| p.parse::<i32>().is_ok_and(|p| (1..=65535).contains(&p));
        if self.use_url && self.document() {
            let raw = self.url.trim();
            return if raw.is_empty() {
                Some("Connection string is required.".into())
            } else if !raw.starts_with("mongodb://") && !raw.starts_with("mongodb+srv://") {
                Some("A connection string starts with mongodb:// or mongodb+srv://.".into())
            } else if self.ssh() {
                Some("SSH tunnels need host and port, not a connection string.".into())
            } else {
                None
            };
        }
        if self.use_url {
            let raw = self.url.trim();
            return if raw.is_empty() {
                Some("JDBC URL is required.".into())
            } else if !raw.starts_with("jdbc:") {
                Some(
                    "A JDBC URL starts with jdbc: — paste other formats into Connection string."
                        .into(),
                )
            } else if self.ssh() && raw.contains(',') {
                Some("SSH tunnels need a single-host URL.".into())
            } else {
                None
            };
        }
        if self.relational() && self.database.trim().is_empty() {
            return Some(if self.is_server() {
                "Database is required.".into()
            } else {
                "Database file is required.".into()
            });
        }
        if let Some(m) = self.file_mismatch() {
            return Some(m);
        }
        if self.is_server() && self.host.trim().is_empty() {
            return Some("Host is required.".into());
        }
        if self.ssh() && self.host.contains(',') {
            return Some("SSH tunnels need a single host.".into());
        }
        if self.is_server() && !self.port.is_empty() && !port_ok(&self.port) {
            return Some("Port must be between 1 and 65535.".into());
        }
        if self.ssh() && self.ssh_host.trim().is_empty() {
            return Some("SSH host is required.".into());
        }
        if self.ssh() && self.ssh_user.trim().is_empty() {
            return Some("SSH user is required.".into());
        }
        if self.ssh() && !port_ok(&self.ssh_port) {
            return Some("SSH port must be between 1 and 65535.".into());
        }
        None
    }

    fn validate_data_file(&self) -> Option<String> {
        let raw = self.database.trim();
        if raw.is_empty() {
            return Some("File is required.".into());
        }
        let path = DataFileStore::path_of(raw);
        if !path.is_file() {
            return Some(format!("{} doesn't exist or isn't a file.", path.display()));
        }
        if let Some(found) = DataFileStore::profile_for(&path, &self.id, "")
            && found.db_type != self.db_type
        {
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            return Some(format!(
                "{name} looks like a {}; choose that type, or rename the file.",
                found.db_type.display_name
            ));
        }
        if let Some(charset) = &self.charset
            && encoding_known(charset).is_none()
        {
            return Some(format!("Unknown encoding \"{charset}\"."));
        }
        None
    }

    /// Why the chosen file isn't a database of this type (checked by content), or None.
    fn file_mismatch(&self) -> Option<String> {
        if self.is_server() || self.data_file() {
            return None;
        }
        database_files::mismatch(Path::new(self.database.trim()), self.db_type)
    }

    /// The profile the form describes.
    pub fn to_config(&self) -> ConnectionConfig {
        let database = self.database.trim().to_owned();
        let fallback = if self.use_url {
            let after = self
                .url
                .split_once("//")
                .map_or(self.url.as_str(), |(_, r)| r);
            let before_query = after.split('?').next().unwrap_or("");
            let host = before_query.rsplit('@').next().unwrap_or("");
            if host.is_empty() {
                self.db_type.display_name.to_owned()
            } else {
                host.to_owned()
            }
        } else if self.is_server() {
            format!("{}/{}", self.host.trim(), database)
        } else {
            database.rsplit('/').next().unwrap_or("").to_owned()
        };
        let name = self.name.trim();
        let mut config = ConnectionConfig::new(
            &self.id,
            if name.is_empty() { &fallback } else { name },
            self.db_type,
        );
        let server = self.is_server();
        config.host = if server {
            self.host.trim().to_owned()
        } else {
            String::new()
        };
        config.port = if server { self.port.parse().ok() } else { None };
        config.database = database;
        config.user = if server {
            self.user.trim().to_owned()
        } else {
            String::new()
        };
        config.driver = Some(self.driver);
        config.driver_jars = if self.driver == DriverId::Generic {
            self.driver_jars.clone()
        } else {
            Vec::new()
        };
        config.properties = if self.data_file() {
            IndexMap::new()
        } else {
            self.parse_properties().unwrap_or_default()
        };
        let url = self.url.trim();
        config.url = (self.use_url && !url.is_empty() && !self.data_file()).then(|| url.to_owned());
        config.data_file = self.data_file().then(|| DataFileOptions {
            delimiter: self.delimiter,
            has_header: self.has_header,
            charset: self.charset.clone(),
        });
        config.environment = self.environment;
        config.group = self.group.trim().to_owned();
        config.favorite = self.favorite;
        config.read_only = self.read_only;
        config.confirm_writes = self.confirm_writes;
        config.ai_access = self.ai_access;
        config.ssh = self.ssh().then(|| {
            let key = self.ssh_key_path.trim();
            SshConfig {
                host: self.ssh_host.trim().to_owned(),
                port: self.ssh_port.parse().unwrap_or(22),
                user: self.ssh_user.trim().to_owned(),
                auth: self.ssh_auth,
                private_key_path: (self.ssh_auth == SshAuth::PrivateKey && !key.is_empty())
                    .then(|| key.to_owned()),
            }
        });
        config
    }

    /// The SSH secret to store: a blank one removes it, as does turning SSH off.
    pub fn ssh_secret_to_store(&self) -> &str {
        if self.ssh() { &self.ssh_secret } else { "" }
    }
}

/// The encoding a name stands for, as the data file reader takes it.
fn encoding_known(name: &str) -> Option<()> {
    tg_data::importer::charset::for_name(name).map(|_| ())
}

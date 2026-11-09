//! Connection profiles as JSON in a file, and their passwords in a secret store (`connection/ConnectionStore.kt`).

use crate::connection::config::ConnectionConfig;
use crate::connection::secret::{KeyringSecretStore, SecretError, SecretStore};
use crate::json::to_pretty;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Debug)]
pub enum StoreError {
    Io(std::io::Error),
    /// The file isn't a JSON array.
    Parse(String),
    Secret(SecretError),
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(e) => e.fmt(f),
            Self::Parse(e) => f.write_str(e),
            Self::Secret(e) => e.fmt(f),
        }
    }
}

impl std::error::Error for StoreError {}

impl From<std::io::Error> for StoreError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<SecretError> for StoreError {
    fn from(e: SecretError) -> Self {
        Self::Secret(e)
    }
}

/// Persists connection profiles as JSON in `file`, and their passwords in `secrets`. Not thread-safe: call it from one
/// thread (the UI one).
pub struct ConnectionStore {
    file: PathBuf,
    /// Also holds other secrets that stay out of the profile files, such as the agent provider's API key.
    pub secrets: Box<dyn SecretStore>,
}

impl ConnectionStore {
    pub fn new(file: impl Into<PathBuf>, secrets: Box<dyn SecretStore>) -> Self {
        Self {
            file: file.into(),
            secrets,
        }
    }

    /// A store over the system keychain.
    pub fn with_keyring(file: impl Into<PathBuf>) -> Self {
        Self::new(file, Box::new(KeyringSecretStore::default()))
    }

    pub fn file(&self) -> &Path {
        &self.file
    }

    /// The profiles this version understands. Others (an engine added by a newer TableGrip) are skipped, not lost:
    /// writes keep them in the file.
    pub fn load(&self) -> Result<Vec<ConnectionConfig>, StoreError> {
        Ok(self
            .read_all()?
            .into_iter()
            .filter_map(|(_, c)| c)
            .collect())
    }

    fn read_all(&self) -> Result<Vec<(Value, Option<ConnectionConfig>)>, StoreError> {
        if !self.file.exists() {
            return Ok(Vec::new());
        }
        let text = std::fs::read_to_string(&self.file)?;
        let value: Value =
            serde_json::from_str(&text).map_err(|e| StoreError::Parse(e.to_string()))?;
        let Value::Array(elements) = value else {
            return Err(StoreError::Parse(
                "Element class kotlinx.serialization.json.JsonObject is not a JsonArray"
                    .to_string(),
            ));
        };
        Ok(elements
            .into_iter()
            .map(|element| {
                let config = serde_json::from_value::<ConnectionConfig>(element.clone()).ok();
                (element, config)
            })
            .collect())
    }

    /// Inserts or replaces `config`. For `password` and `ssh_secret` (SSH password or key passphrase), None keeps the
    /// stored value and an empty string removes it.
    pub fn save(
        &self,
        config: &ConnectionConfig,
        password: Option<&str>,
        ssh_secret: Option<&str>,
    ) -> Result<Vec<ConnectionConfig>, StoreError> {
        let current = self.load()?;
        let updated: Vec<ConnectionConfig> = if current.iter().any(|c| c.id == config.id) {
            current
                .into_iter()
                .map(|c| if c.id == config.id { config.clone() } else { c })
                .collect()
        } else {
            current.into_iter().chain([config.clone()]).collect()
        };
        self.write(&updated)?;
        self.store_secret(&config.id, password)?;
        self.store_secret(&ssh_key(&config.id), ssh_secret)?;
        Ok(updated)
    }

    fn store_secret(&self, key: &str, value: Option<&str>) -> Result<(), StoreError> {
        match value {
            None => {}
            Some("") => self.secrets.delete(key),
            Some(secret) => self.secrets.set(key, secret)?,
        }
        Ok(())
    }

    pub fn delete(&self, id: &str) -> Result<Vec<ConnectionConfig>, StoreError> {
        let updated: Vec<ConnectionConfig> =
            self.load()?.into_iter().filter(|c| c.id != id).collect();
        self.write(&updated)?;
        self.secrets.delete(id);
        self.secrets.delete(&ssh_key(id));
        Ok(updated)
    }

    pub fn password(&self, id: &str) -> Option<String> {
        self.secrets.get(id)
    }

    pub fn ssh_secret(&self, id: &str) -> Option<String> {
        self.secrets.get(&ssh_key(id))
    }

    fn write(&self, configs: &[ConnectionConfig]) -> Result<(), StoreError> {
        let parent = self.file.parent().unwrap_or(Path::new(""));
        std::fs::create_dir_all(parent)?;
        let unknown: Vec<Value> = self
            .read_all()?
            .into_iter()
            .filter(|(_, c)| c.is_none())
            .map(|(element, _)| element)
            .collect();
        let mut elements: Vec<Value> = Vec::with_capacity(configs.len() + unknown.len());
        for config in configs {
            elements
                .push(serde_json::to_value(config).map_err(|e| StoreError::Parse(e.to_string()))?);
        }
        elements.extend(unknown);
        let text = to_pretty(&Value::Array(elements), "    ")
            .map_err(|e| StoreError::Parse(e.to_string()))?;
        // Write to a temp file first so a crash never leaves a half-written profile list behind.
        let tmp = temp_sibling(&self.file);
        std::fs::write(&tmp, text)?;
        if let Err(e) = std::fs::rename(&tmp, &self.file) {
            let _ = std::fs::remove_file(&tmp);
            return Err(e.into());
        }
        Ok(())
    }
}

fn ssh_key(id: &str) -> String {
    format!("{id}:ssh")
}

/// A file next to `file` for a write in progress: `<name><unique>.tmp`, like `Files.createTempFile(dir, name, ".tmp")`.
pub(crate) fn temp_sibling(file: &Path) -> PathBuf {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let unique = format!(
        "{}{}{}",
        std::process::id(),
        nanos,
        COUNTER.fetch_add(1, Ordering::Relaxed)
    );
    let mut name = file.file_name().unwrap_or_default().to_os_string();
    name.push(unique);
    name.push(".tmp");
    file.with_file_name(name)
}

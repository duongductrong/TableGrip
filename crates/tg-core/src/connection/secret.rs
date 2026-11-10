//! Passwords outside of the profile file (`connection/SecretStore.kt`).
//!
//! [`KeyringSecretStore`] writes the very items java-keyring wrote for the Kotlin app, so existing passwords keep
//! working:
//!
//! | OS      | what java-keyring 1.0.4 stores                                                                   |
//! |---------|--------------------------------------------------------------------------------------------------|
//! | macOS   | a generic password (`SecKeychainAddGenericPassword`): service `TableGrip`, account = the key, UTF-8 |
//! | Windows | a generic credential with target `TableGrip|<key>`, user name = the key, UTF-16LE blob              |
//! | Linux   | a Secret Service item with attributes `service` and `account`, label `TableGrip|<key>`             |
//!
//! The `keyring` crate reads and writes the first two as they are. Its Linux backends use other attribute names
//! (`service` and `username`), so the Linux store is not wired up yet; see the decision in the S2 log entry.

use std::collections::HashMap;
use std::sync::Mutex;

/// The service name every item is stored under.
pub const SERVICE: &str = "TableGrip";

#[derive(Debug)]
pub struct SecretError(pub String);

impl std::fmt::Display for SecretError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for SecretError {}

/// Stores connection passwords outside of the profile file. Keys are connection ids, or `<id>:ssh` for an SSH
/// password or key passphrase.
pub trait SecretStore: Send + Sync {
    /// None when nothing is stored, and also when the keychain can't be read.
    fn get(&self, key: &str) -> Option<String>;

    fn set(&self, key: &str, secret: &str) -> Result<(), SecretError>;

    /// Nothing stored is not an error.
    fn delete(&self, key: &str);
}

#[derive(Debug, Default)]
pub struct InMemorySecretStore {
    secrets: Mutex<HashMap<String, String>>,
}

impl InMemorySecretStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SecretStore for InMemorySecretStore {
    fn get(&self, key: &str) -> Option<String> {
        self.secrets.lock().expect("lock").get(key).cloned()
    }

    fn set(&self, key: &str, secret: &str) -> Result<(), SecretError> {
        self.secrets
            .lock()
            .expect("lock")
            .insert(key.to_string(), secret.to_string());
        Ok(())
    }

    fn delete(&self, key: &str) {
        self.secrets.lock().expect("lock").remove(key);
    }
}

/// Backed by the OS credential store: macOS Keychain or Windows Credential Manager.
#[derive(Debug)]
pub struct KeyringSecretStore {
    service: String,
}

impl Default for KeyringSecretStore {
    fn default() -> Self {
        Self::new(SERVICE)
    }
}

impl KeyringSecretStore {
    pub fn new(service: &str) -> Self {
        Self {
            service: service.to_string(),
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl KeyringSecretStore {
    fn entry(&self, key: &str) -> keyring::Result<keyring::Entry> {
        #[cfg(target_os = "windows")]
        {
            // java-keyring's target name; the crate's own default is `<user>.<service>`.
            keyring::Entry::new_with_target(&format!("{}|{key}", self.service), &self.service, key)
        }
        #[cfg(not(target_os = "windows"))]
        {
            keyring::Entry::new(&self.service, key)
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl SecretStore for KeyringSecretStore {
    fn get(&self, key: &str) -> Option<String> {
        self.entry(key).ok()?.get_password().ok()
    }

    fn set(&self, key: &str, secret: &str) -> Result<(), SecretError> {
        self.entry(key)
            .and_then(|e| e.set_password(secret))
            .map_err(|e| SecretError(e.to_string()))
    }

    fn delete(&self, key: &str) {
        if let Ok(entry) = self.entry(key) {
            let _ = entry.delete_credential();
        }
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
impl SecretStore for KeyringSecretStore {
    fn get(&self, _key: &str) -> Option<String> {
        None
    }

    fn set(&self, _key: &str, _secret: &str) -> Result<(), SecretError> {
        let _ = &self.service;
        Err(SecretError(
            "The Secret Service keychain isn't supported yet on this platform.".to_string(),
        ))
    }

    fn delete(&self, _key: &str) {}
}

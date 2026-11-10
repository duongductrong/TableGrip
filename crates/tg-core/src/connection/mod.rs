//! Connection profiles, their storage and the strings that describe them (`core/.../connection`).

pub mod ai_access;
pub mod config;
pub mod files;
pub mod secret;
pub mod store;
pub mod string;

pub use ai_access::AiAccess;
pub use config::{ConnectionConfig, DataFileOptions, DriverId, Environment, SshAuth, SshConfig};
pub use secret::{InMemorySecretStore, KeyringSecretStore, SecretError, SecretStore};
pub use store::{ConnectionStore, StoreError};
pub use string::{ConnectionStringError, ParsedConnection};

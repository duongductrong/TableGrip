//! Storage and platform services shared by the whole app: `~/.tablegrip` paths, settings, the connection
//! store and connection strings, secrets in the OS keychain, SSH tunnels, query history, saved queries, sessions,
//! activity, backups and theme preferences.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/{connection,settings,history,queries,session,activity,backup,theme}`
//! (milestone M2 of docs/plans/rust-migration.md). Files the Kotlin app wrote must read back unchanged.

pub mod backup;
pub mod json;
pub mod paths;
pub mod settings;
pub mod theme;

pub use paths::TableGripPaths;

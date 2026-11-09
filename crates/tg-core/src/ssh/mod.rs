//! SSH tunnels: a local port on 127.0.0.1 forwarded to `host:port` through an SSH server, so a database driver can
//! connect to `127.0.0.1:<local_port>` as if the database were local.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/connection/SshTunnel.kt` (SSHJ) onto russh with the pure-Rust
//! `ring` crypto backend, so building needs no system OpenSSL and no aws-lc C/CMake/NASM step.
//!
//! # Using it
//!
//! ```no_run
//! # async fn demo() -> Result<(), tg_core::ssh::SshError> {
//! use tg_core::ssh::{SshAuth, SshTunnel, SshTunnelSpec};
//!
//! let spec = SshTunnelSpec::new(
//!     "bastion.example.com", 22, "deploy",
//!     SshAuth::Agent { socket: None },
//!     "/home/me/.tablegrip/known_hosts".into(),
//! );
//! let tunnel = SshTunnel::open(&spec, "db.internal", 5432).await?;
//! // Connect the driver to ("127.0.0.1", tunnel.local_port()).
//! tunnel.close().await; // or just drop it
//! # Ok(()) }
//! ```
//!
//! * **Async callers** (the future `tg-driver`) call [`SshTunnel::open`] from inside a tokio runtime. The tunnel's
//!   accept loop and per-connection copy tasks are spawned on that runtime, so it must outlive the tunnel.
//! * **Sync callers** use [`SshTunnel::open_blocking`] with a runtime [`Handle`](tokio::runtime::Handle) they own.
//!   It must not be called from a thread that is itself driving that runtime. The returned tunnel is `Send + Sync`
//!   and keeps running on the runtime in the background; dropping it from any thread stops it.
//! * Dropping a tunnel stops the accept loop, aborts every open forwarded connection and disconnects from the server
//!   (the disconnect happens on the runtime in the background). [`SshTunnel::close`] does the same and waits for it.
//!
//! # Behaviour mirrored from Kotlin
//!
//! * Host keys are trusted on first use and pinned in the `known_hosts` file the caller names (Kotlin used
//!   `~/.tablegrip/known_hosts`; its directory is created up front, the file with the first pin). A changed key fails the
//!   connection and the file is never rewritten. Entries are written in OpenSSH syntax (`host` for port 22,
//!   `[host]:port` otherwise), and hashed (`|1|`) entries are understood, so a system file can be reused.
//! * Password auth sends the secret as given (an absent secret is the empty password).
//! * Key auth with a path loads exactly that file (`~/` expanded) with the optional passphrase; with no path it tries
//!   the default `~/.ssh/id_*` keys, unencrypted only.
//! * New relative to Kotlin: [`SshAuth::Agent`] (ssh-agent via `SSH_AUTH_SOCK`, or the OpenSSH named pipe / Pageant
//!   on Windows) and [`HostKeyPolicy::Strict`], which refuses unknown hosts instead of pinning them.

mod auth;
mod host_keys;
mod tunnel;

use std::path::PathBuf;
use std::time::Duration;

pub use tunnel::SshTunnel;

/// How to authenticate to the SSH server.
#[derive(Clone, PartialEq, Eq)]
pub enum SshAuth {
    /// Password authentication.
    Password(String),
    /// A private key file (OpenSSH, PKCS#8 or PEM, optionally passphrase-protected). `path: None` tries the default
    /// keys in `~/.ssh` (without a passphrase), like Kotlin's `authPublickey(user)`.
    Key {
        path: Option<String>,
        passphrase: Option<String>,
    },
    /// Whatever identities an ssh-agent offers. `socket: None` uses `SSH_AUTH_SOCK` (on Windows: that pipe, else
    /// `\\.\pipe\openssh-ssh-agent`, else Pageant); `Some(path)` names a Unix socket / pipe explicitly.
    Agent { socket: Option<String> },
}

impl std::fmt::Debug for SshAuth {
    // Never print secrets.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Password(_) => f.write_str("Password(..)"),
            Self::Key { path, passphrase } => f
                .debug_struct("Key")
                .field("path", path)
                .field("passphrase", &passphrase.as_ref().map(|_| ".."))
                .finish(),
            Self::Agent { socket } => f.debug_struct("Agent").field("socket", socket).finish(),
        }
    }
}

/// What to do with the server's host key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostKeyPolicy {
    /// Pin unknown hosts in this file on first connect; reject a changed key. This is the Kotlin behaviour.
    TrustOnFirstUse(PathBuf),
    /// Only connect to hosts already in this file.
    Strict(PathBuf),
}

/// Everything needed to open a tunnel. Map `ConnectionConfig`'s `SshConfig` plus its keychain secret onto this.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SshTunnelSpec {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub auth: SshAuth,
    pub host_keys: HostKeyPolicy,
    /// TCP connect plus SSH handshake and authentication. Kotlin used 10 seconds.
    pub connect_timeout: Duration,
}

impl SshTunnelSpec {
    /// The spec for a stored profile's tunnel. `secret` is what the secret store holds under `<id>:ssh`: the
    /// password, or the key's passphrase. Like Kotlin, a missing password is sent as an empty one. A port outside
    /// `1..=65535` (Kotlin would fail to connect) falls back to 22.
    pub fn from_config(
        config: &crate::connection::SshConfig,
        secret: Option<String>,
        known_hosts: PathBuf,
    ) -> Self {
        use crate::connection::SshAuth as Stored;
        let auth = match config.auth {
            Stored::Password => SshAuth::Password(secret.unwrap_or_default()),
            Stored::PrivateKey => SshAuth::Key {
                path: config.private_key_path.clone(),
                passphrase: secret,
            },
        };
        let port = u16::try_from(config.port)
            .ok()
            .filter(|p| *p != 0)
            .unwrap_or(22);
        Self::new(
            config.host.clone(),
            port,
            config.user.clone(),
            auth,
            known_hosts,
        )
    }

    /// A spec with trust-on-first-use pinning in `known_hosts` and the 10 second connect timeout.
    pub fn new(
        host: impl Into<String>,
        port: u16,
        user: impl Into<String>,
        auth: SshAuth,
        known_hosts: PathBuf,
    ) -> Self {
        Self {
            host: host.into(),
            port,
            user: user.into(),
            auth,
            host_keys: HostKeyPolicy::TrustOnFirstUse(known_hosts),
            connect_timeout: Duration::from_secs(10),
        }
    }
}

/// Why a tunnel could not be opened.
#[derive(Debug, thiserror::Error)]
pub enum SshError {
    #[error("could not connect to {host}:{port}: {source}")]
    Connect {
        host: String,
        port: u16,
        source: std::io::Error,
    },
    #[error("timed out connecting to {host}:{port}")]
    Timeout { host: String, port: u16 },
    /// The server's key differs from the pinned one: possibly a man in the middle.
    #[error("host key for {host}:{port} changed (known_hosts line {line}); refusing to connect")]
    HostKeyChanged {
        host: String,
        port: u16,
        line: usize,
    },
    /// Only with [`HostKeyPolicy::Strict`].
    #[error("host key for {host}:{port} is not in known_hosts")]
    HostKeyUnknown { host: String, port: u16 },
    #[error("known_hosts: {0}")]
    KnownHosts(String),
    #[error("ssh authentication failed for {user}@{host}")]
    AuthFailed { user: String, host: String },
    #[error("could not load private key {path}: {message}")]
    Key { path: String, message: String },
    #[error("ssh-agent: {0}")]
    Agent(String),
    #[error("ssh: {0}")]
    Protocol(#[from] russh::Error),
    #[error("{0}")]
    Io(#[from] std::io::Error),
}

#[cfg(test)]
mod tests;

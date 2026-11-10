//! Host key verification against a `known_hosts` file (trust on first use or strict).

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use russh::client::Handler;
use russh::keys::{self, PublicKey};

use super::{HostKeyPolicy, SshError};

/// Why the verifier said no, so `open` can report it instead of russh's generic "unknown key".
#[derive(Debug, Clone)]
pub(super) enum Rejection {
    Changed { line: usize },
    Unknown,
    Other(String),
}

pub(super) type RejectionSlot = Arc<Mutex<Option<Rejection>>>;

pub(super) struct HostKeyHandler {
    host: String,
    port: u16,
    path: PathBuf,
    trust_on_first_use: bool,
    rejection: RejectionSlot,
}

impl HostKeyHandler {
    pub(super) fn new(host: &str, port: u16, policy: &HostKeyPolicy) -> (Self, RejectionSlot) {
        let (path, trust_on_first_use) = match policy {
            HostKeyPolicy::TrustOnFirstUse(path) => (path.clone(), true),
            HostKeyPolicy::Strict(path) => (path.clone(), false),
        };
        let rejection = RejectionSlot::default();
        (
            Self {
                host: host.to_owned(),
                port,
                path,
                trust_on_first_use,
                rejection: rejection.clone(),
            },
            rejection,
        )
    }

    fn reject(&self, why: Rejection) -> bool {
        *self.rejection.lock().unwrap() = Some(why);
        false
    }

    fn verify(&self, key: &PublicKey) -> bool {
        match keys::known_hosts::check_known_hosts_path(&self.host, self.port, key, &self.path) {
            Ok(true) => true,
            // Unknown host, or only known under another key algorithm: pin it (or refuse, when strict).
            Ok(false) if self.trust_on_first_use => {
                match pin(&self.host, self.port, key, &self.path) {
                    Ok(()) => true,
                    Err(e) => self.reject(Rejection::Other(e.to_string())),
                }
            }
            Ok(false) => self.reject(Rejection::Unknown),
            Err(keys::Error::KeyChanged { line }) => self.reject(Rejection::Changed { line }),
            Err(e) => self.reject(Rejection::Other(e.to_string())),
        }
    }
}

impl Handler for HostKeyHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &keys::PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        match key {
            keys::PublicKeyOrCertificate::PublicKey { key, .. } => Ok(self.verify(key)),
            keys::PublicKeyOrCertificate::Certificate(_) => Ok(self.reject(Rejection::Other(
                "host certificates are not supported".into(),
            ))),
        }
    }
}

/// Append `host key` (OpenSSH syntax: `[host]:port` unless the port is 22). Unlike russh's `learn_known_hosts_path`
/// this adds no blank first line to a new file.
fn pin(host: &str, port: u16, key: &PublicKey, path: &std::path::Path) -> std::io::Result<()> {
    use std::io::{Read, Seek, SeekFrom, Write};
    let mut file = std::fs::OpenOptions::new()
        .read(true)
        .append(true)
        .create(true)
        .open(path)?;
    let mut last = [0u8; 1];
    let needs_newline = file.seek(SeekFrom::End(-1)).is_ok()
        && file.read_exact(&mut last).is_ok()
        && last[0] != b'\n';
    let name = if port == 22 {
        host.to_owned()
    } else {
        format!("[{host}]:{port}")
    };
    let openssh = key.to_openssh().map_err(std::io::Error::other)?;
    let line = format!(
        "{}{name} {openssh}\n",
        if needs_newline { "\n" } else { "" }
    );
    file.write_all(line.as_bytes())
}

/// Create the file's directory if missing (Kotlin also created the empty file; here it appears with the first pin,
/// which avoids a blank first line).
pub(super) fn ensure_dir(policy: &HostKeyPolicy) -> Result<(), SshError> {
    let path = match policy {
        HostKeyPolicy::TrustOnFirstUse(p) | HostKeyPolicy::Strict(p) => p,
    };
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    Ok(())
}

pub(super) fn into_error(rejection: Option<Rejection>, host: &str, port: u16) -> Option<SshError> {
    let (host, port) = (host.to_owned(), port);
    Some(match rejection? {
        Rejection::Changed { line } => SshError::HostKeyChanged { host, port, line },
        Rejection::Unknown => SshError::HostKeyUnknown { host, port },
        Rejection::Other(message) => SshError::KnownHosts(message),
    })
}

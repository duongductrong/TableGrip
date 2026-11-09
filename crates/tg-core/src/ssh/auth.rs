//! The three ways to authenticate: password, private key, ssh-agent.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use russh::client::{AuthResult, Handle, Handler};
use russh::keys::agent::client::{AgentClient, AgentStream};
use russh::keys::{HashAlg, PrivateKey, PrivateKeyWithHashAlg};

use super::{SshAuth, SshError, SshTunnelSpec};

/// Authenticate `handle`, or fail with [`SshError::AuthFailed`] when the server accepts none of what we offer.
pub(super) async fn authenticate<H: Handler>(
    handle: &mut Handle<H>,
    spec: &SshTunnelSpec,
) -> Result<(), SshError> {
    let failed = || SshError::AuthFailed {
        user: spec.user.clone(),
        host: spec.host.clone(),
    };
    let ok = match &spec.auth {
        SshAuth::Password(password) => handle
            .authenticate_password(&spec.user, password)
            .await?
            .success(),
        SshAuth::Key {
            path: Some(path),
            passphrase,
        } if !path.trim().is_empty() => {
            let key = load_key(&expand_home(path), passphrase.as_deref())?;
            try_key(handle, &spec.user, key).await?
        }
        // Blank or absent path: the default keys, none of which can have a passphrase here.
        SshAuth::Key { .. } => {
            let mut ok = false;
            for path in default_key_paths() {
                if let Ok(key) = load_key(&path, None)
                    && try_key(handle, &spec.user, key).await?
                {
                    ok = true;
                    break;
                }
            }
            ok
        }
        SshAuth::Agent { socket } => try_agent(handle, &spec.user, socket.as_deref()).await?,
    };
    if ok { Ok(()) } else { Err(failed()) }
}

async fn try_key<H: Handler>(
    handle: &mut Handle<H>,
    user: &str,
    key: PrivateKey,
) -> Result<bool, SshError> {
    let hash = best_hash(handle).await?;
    let key = PrivateKeyWithHashAlg::new(Arc::new(key), hash);
    Ok(handle.authenticate_publickey(user, key).await?.success())
}

async fn best_hash<H: Handler>(handle: &Handle<H>) -> Result<Option<HashAlg>, SshError> {
    // For RSA keys; ignored for every other algorithm.
    Ok(handle.best_supported_rsa_hash().await?.flatten())
}

async fn try_agent<H: Handler>(
    handle: &mut Handle<H>,
    user: &str,
    socket: Option<&str>,
) -> Result<bool, SshError> {
    let agent_err = |e: russh::keys::Error| SshError::Agent(e.to_string());
    let mut agent = connect_agent(socket).await.map_err(agent_err)?;
    let identities = agent.request_identities().await.map_err(agent_err)?;
    if identities.is_empty() {
        return Err(SshError::Agent("the agent holds no identities".into()));
    }
    let hash = best_hash(handle).await?;
    for identity in identities {
        // Certificates are not offered; plain keys only.
        let russh::keys::agent::AgentIdentity::PublicKey { key, .. } = identity else {
            continue;
        };
        match handle
            .authenticate_publickey_with(user, key, hash, &mut agent)
            .await
        {
            Ok(result) if result.success() => return Ok(true),
            // This identity was refused or the agent declined to sign: try the next one.
            Ok(AuthResult::Failure { .. }) | Err(_) => {}
            Ok(_) => {}
        }
    }
    Ok(false)
}

type Agent = AgentClient<Box<dyn AgentStream + Send + Unpin + 'static>>;

#[cfg(unix)]
async fn connect_agent(socket: Option<&str>) -> Result<Agent, russh::keys::Error> {
    Ok(match socket {
        Some(path) => AgentClient::connect_uds(path).await?.dynamic(),
        None => AgentClient::connect_env().await?.dynamic(),
    })
}

#[cfg(windows)]
async fn connect_agent(socket: Option<&str>) -> Result<Agent, russh::keys::Error> {
    // OpenSSH for Windows first (SSH_AUTH_SOCK may name a pipe), then Pageant.
    let pipe = socket
        .map(str::to_owned)
        .or_else(|| std::env::var("SSH_AUTH_SOCK").ok())
        .unwrap_or_else(|| r"\\.\pipe\openssh-ssh-agent".into());
    match AgentClient::connect_named_pipe(&pipe).await {
        Ok(client) => Ok(client.dynamic()),
        Err(_) => Ok(AgentClient::connect_pageant().await?.dynamic()),
    }
}

/// Load a private key in any of OpenSSH, PKCS#8 or PEM (PKCS#1 / SEC1) form.
pub(super) fn load_key(path: &Path, passphrase: Option<&str>) -> Result<PrivateKey, SshError> {
    let passphrase = passphrase.filter(|p| !p.is_empty());
    russh::keys::load_secret_key(path, passphrase).map_err(|e| SshError::Key {
        path: path.display().to_string(),
        message: e.to_string(),
    })
}

fn expand_home(path: &str) -> PathBuf {
    match path.strip_prefix("~/") {
        Some(rest) => home_dir().map_or_else(|| PathBuf::from(path), |home| home.join(rest)),
        None => PathBuf::from(path),
    }
}

fn home_dir() -> Option<PathBuf> {
    std::env::home_dir()
}

/// SSHJ's default identities, in its order.
fn default_key_paths() -> Vec<PathBuf> {
    let Some(ssh) = home_dir().map(|h| h.join(".ssh")) else {
        return Vec::new();
    };
    ["id_rsa", "id_ed25519", "id_ecdsa"]
        .into_iter()
        .map(|n| ssh.join(n))
        .filter(|p| p.is_file())
        .collect()
}

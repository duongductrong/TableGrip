//! Port of `core/src/test/kotlin/dev/tablegrip/core/SshTunnelTest.kt` plus the auth methods Kotlin's test never
//! covered (keys, passphrases, agent). The SSH server is an in-process russh server that forwards `direct-tcpip`
//! channels to real TCP targets; the target is an in-process echo server.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::Arc;

use russh::keys::ssh_key::LineEnding;
use russh::keys::{Algorithm, PrivateKey, PublicKey};
use russh::server::{Auth, Msg, Server as _, Session, VerifiedPublicKey};
use russh::{Channel, ChannelOpenFailure};
use tokio::io::{AsyncReadExt, AsyncWriteExt, copy_bidirectional};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

use super::{HostKeyPolicy, SshAuth, SshError, SshTunnel, SshTunnelSpec};

const FIXTURES: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/ssh");
const PASSPHRASE: &str = "test-passphrase";

fn fixture(name: &str) -> String {
    format!("{FIXTURES}/{name}")
}

fn fixture_public(name: &str) -> PublicKey {
    russh::keys::load_public_key(fixture(&format!("{name}.pub"))).unwrap()
}

fn random_key() -> PrivateKey {
    PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap()
}

// ---- test servers -------------------------------------------------------------------------------------------

/// A TCP echo server: the tunnel's target.
struct Echo {
    port: u16,
    task: JoinHandle<()>,
}

impl Echo {
    async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let task = tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let (mut r, mut w) = socket.split();
                    let _ = tokio::io::copy(&mut r, &mut w).await;
                });
            }
        });
        Self { port, task }
    }
}

impl Drop for Echo {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// What the test SSH server accepts.
#[derive(Clone, Default)]
struct Accepts {
    /// `(user, password)`.
    password: Option<(String, String)>,
    /// Public keys that may log in as `user`.
    keys: Vec<PublicKey>,
    user: String,
    /// Accept any credentials (the Kotlin impostor server does).
    anyone: bool,
}

#[derive(Clone)]
struct SshServer {
    accepts: Arc<Accepts>,
}

impl russh::server::Server for SshServer {
    type Handler = Self;
    fn new_client(&mut self, _: Option<SocketAddr>) -> Self {
        self.clone()
    }
}

impl russh::server::Handler for SshServer {
    type Error = russh::Error;

    async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
        let ok = self.accepts.anyone
            || self
                .accepts
                .password
                .as_ref()
                .is_some_and(|(u, p)| u == user && p == password);
        Ok(if ok { Auth::Accept } else { Auth::reject() })
    }

    async fn auth_publickey_offered(
        &mut self,
        user: &str,
        key: russh::server::PublicKeyOffer<'_>,
    ) -> Result<Auth, Self::Error> {
        let ok = self.accepts.anyone
            || (user == self.accepts.user
                && self
                    .accepts
                    .keys
                    .iter()
                    .any(|k| k.key_data() == key.public_key.key_data()));
        Ok(if ok { Auth::Accept } else { Auth::reject() })
    }

    async fn auth_publickey(
        &mut self,
        user: &str,
        key: VerifiedPublicKey<'_>,
    ) -> Result<Auth, Self::Error> {
        let ok = self.accepts.anyone
            || (user == self.accepts.user
                && self
                    .accepts
                    .keys
                    .iter()
                    .any(|k| k.key_data() == key.public_key.key_data()));
        Ok(if ok { Auth::Accept } else { Auth::reject() })
    }

    async fn channel_open_direct_tcpip(
        &mut self,
        channel: Channel<Msg>,
        host: &str,
        port: u32,
        _originator: &str,
        _originator_port: u32,
        reply: russh::server::ChannelOpenHandle,
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        match TcpStream::connect((host, port as u16)).await {
            Ok(mut target) => {
                reply.accept().await;
                tokio::spawn(async move {
                    let mut stream = channel.into_stream();
                    let _ = copy_bidirectional(&mut target, &mut stream).await;
                });
            }
            Err(_) => reply.reject(ChannelOpenFailure::ConnectFailed).await,
        }
        Ok(())
    }
}

struct TestServer {
    port: u16,
    task: JoinHandle<()>,
}

impl TestServer {
    async fn start(host_key: PrivateKey, accepts: Accepts) -> Self {
        Self::start_on(0, host_key, accepts).await
    }

    async fn start_on(port: u16, host_key: PrivateKey, accepts: Accepts) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", port)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let config = Arc::new(russh::server::Config {
            keys: vec![host_key],
            auth_rejection_time: std::time::Duration::from_millis(1),
            auth_rejection_time_initial: Some(std::time::Duration::ZERO),
            ..Default::default()
        });
        let mut server = SshServer {
            accepts: Arc::new(accepts),
        };
        let task = tokio::spawn(async move {
            let _ = server.run_on_socket(config, &listener).await;
        });
        Self { port, task }
    }

    /// Stop listening and drop every session, like `SshServer.stop(true)`.
    async fn stop(mut self) {
        self.task.abort();
        let _ = (&mut self.task).await; // the listener is gone once the task is
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

fn password_accepts() -> Accepts {
    Accepts {
        password: Some(("tunnel".into(), "secret".into())),
        user: "tunnel".into(),
        ..Default::default()
    }
}

fn key_accepts(keys: Vec<PublicKey>) -> Accepts {
    Accepts {
        keys,
        user: "tunnel".into(),
        ..Default::default()
    }
}

fn spec(port: u16, auth: SshAuth, known_hosts: &Path) -> SshTunnelSpec {
    let mut spec = SshTunnelSpec::new("127.0.0.1", port, "tunnel", auth, known_hosts.to_path_buf());
    spec.connect_timeout = std::time::Duration::from_secs(20);
    spec
}

fn password(secret: &str) -> SshAuth {
    SshAuth::Password(secret.into())
}

fn key_auth(path: impl Into<String>, passphrase: Option<&str>) -> SshAuth {
    SshAuth::Key {
        path: Some(path.into()),
        passphrase: passphrase.map(str::to_owned),
    }
}

/// Send `text`, half-close, and read everything back.
async fn round_trip(port: u16, text: &str) -> String {
    let mut socket = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    socket.write_all(text.as_bytes()).await.unwrap();
    socket.shutdown().await.unwrap();
    let mut out = String::new();
    socket.read_to_string(&mut out).await.unwrap();
    out
}

async fn assert_forwards(spec: &SshTunnelSpec, echo: &Echo) {
    let tunnel = SshTunnel::open(spec, "127.0.0.1", echo.port).await.unwrap();
    assert_eq!(
        round_trip(tunnel.local_port(), "hello through ssh").await,
        "hello through ssh"
    );
    tunnel.close().await;
}

// ---- ported from SshTunnelTest.kt ---------------------------------------------------------------------------

/// `forwards traffic through the ssh server and pins its host key`
#[tokio::test]
async fn forwards_traffic_through_the_ssh_server_and_pins_its_host_key() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let known_hosts = dir.path().join("known_hosts");
    let spec = spec(server.port, password("secret"), &known_hosts);

    let tunnel = SshTunnel::open(&spec, "127.0.0.1", echo.port)
        .await
        .unwrap();
    assert_eq!(
        round_trip(tunnel.local_port(), "hello through ssh").await,
        "hello through ssh"
    );
    tunnel.close().await;

    let pinned = std::fs::read_to_string(&known_hosts).unwrap();
    assert!(
        pinned.contains("127.0.0.1"),
        "host key is remembered: {pinned:?}"
    );
    // Reconnecting with the same host key works.
    SshTunnel::open(&spec, "127.0.0.1", echo.port)
        .await
        .unwrap()
        .close()
        .await;
    assert_eq!(
        std::fs::read_to_string(&known_hosts).unwrap(),
        pinned,
        "a known key is not appended again"
    );
}

/// `rejects wrong passwords and changed host keys`
#[tokio::test]
async fn rejects_wrong_passwords_and_changed_host_keys() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let known_hosts = dir.path().join("known_hosts");
    let first = TestServer::start(random_key(), password_accepts()).await;
    let port = first.port;

    let wrong = SshTunnel::open(
        &spec(port, password("wrong"), &known_hosts),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(
        matches!(wrong, Err(SshError::AuthFailed { .. })),
        "{wrong:?}"
    );

    SshTunnel::open(
        &spec(port, password("secret"), &known_hosts),
        "127.0.0.1",
        echo.port,
    )
    .await
    .unwrap()
    .close()
    .await;
    first.stop().await;
    // Same host and port, different key: looks like a man in the middle.
    let impostor = TestServer::start_on(
        port,
        random_key(),
        Accepts {
            anyone: true,
            ..Default::default()
        },
    )
    .await;
    let before = std::fs::read_to_string(&known_hosts).unwrap();
    let changed = SshTunnel::open(
        &spec(impostor.port, password("secret"), &known_hosts),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(
        matches!(changed, Err(SshError::HostKeyChanged { .. })),
        "{changed:?}"
    );
    assert_eq!(
        std::fs::read_to_string(&known_hosts).unwrap(),
        before,
        "a changed key never rewrites known_hosts"
    );
}

// ---- beyond the Kotlin test: key auth -----------------------------------------------------------------------

#[tokio::test]
async fn authenticates_with_an_openssh_private_key() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let key = random_key();
    let path = dir.path().join("id_ed25519");
    std::fs::write(&path, key.to_openssh(LineEnding::LF).unwrap()).unwrap();
    let server = TestServer::start(random_key(), key_accepts(vec![key.public_key().clone()])).await;

    assert_forwards(
        &spec(
            server.port,
            key_auth(path.to_str().unwrap(), None),
            &dir.path().join("kh"),
        ),
        &echo,
    )
    .await;
}

#[tokio::test]
async fn authenticates_with_a_passphrase_protected_key() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let key = random_key();
    let encrypted = key.encrypt(&mut rand::rng(), PASSPHRASE).unwrap();
    let path = dir.path().join("id_ed25519");
    std::fs::write(&path, encrypted.to_openssh(LineEnding::LF).unwrap()).unwrap();
    let server = TestServer::start(random_key(), key_accepts(vec![key.public_key().clone()])).await;
    let known_hosts = dir.path().join("kh");

    assert_forwards(
        &spec(
            server.port,
            key_auth(path.to_str().unwrap(), Some(PASSPHRASE)),
            &known_hosts,
        ),
        &echo,
    )
    .await;
    for bad in [None, Some("wrong")] {
        let result = SshTunnel::open(
            &spec(
                server.port,
                key_auth(path.to_str().unwrap(), bad),
                &known_hosts,
            ),
            "127.0.0.1",
            echo.port,
        )
        .await;
        assert!(
            matches!(result, Err(SshError::Key { .. })),
            "{bad:?}: {result:?}"
        );
    }
}

#[tokio::test]
async fn a_key_the_server_does_not_know_is_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(
        random_key(),
        key_accepts(vec![random_key().public_key().clone()]),
    )
    .await;
    let path = dir.path().join("id");
    std::fs::write(&path, random_key().to_openssh(LineEnding::LF).unwrap()).unwrap();
    let result = SshTunnel::open(
        &spec(
            server.port,
            key_auth(path.to_str().unwrap(), None),
            &dir.path().join("kh"),
        ),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(
        matches!(result, Err(SshError::AuthFailed { .. })),
        "{result:?}"
    );
}

/// Every key format SSHJ reads must load, with its passphrase where needed, and authenticate (RSA exercises the
/// rsa-sha2 signature path).
#[tokio::test]
async fn authenticates_with_every_supported_key_format() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let cases = [
        ("rsa_pem_plain", None),
        ("rsa_pem_encrypted", Some(PASSPHRASE)),
        ("rsa_openssh", None),
        ("rsa_pkcs8_encrypted", Some(PASSPHRASE)),
        ("ecdsa_sec1_pem", None),
        ("ed25519_openssh_encrypted", Some(PASSPHRASE)),
    ];
    for (name, passphrase) in cases {
        let server = TestServer::start(random_key(), key_accepts(vec![fixture_public(name)])).await;
        let spec = spec(
            server.port,
            key_auth(fixture(name), passphrase),
            &dir.path().join("kh"),
        );
        let tunnel = SshTunnel::open(&spec, "127.0.0.1", echo.port)
            .await
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(round_trip(tunnel.local_port(), name).await, name);
    }
}

#[tokio::test]
async fn missing_key_file_is_a_key_error() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let result = SshTunnel::open(
        &spec(
            server.port,
            key_auth("/nonexistent/id", None),
            &dir.path().join("kh"),
        ),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(matches!(result, Err(SshError::Key { .. })), "{result:?}");
}

// ---- ssh-agent ----------------------------------------------------------------------------------------------

/// A real `ssh-agent -D` on a private socket, killed on drop.
#[cfg(unix)]
struct Agent {
    child: std::process::Child,
    socket: std::path::PathBuf,
    _dir: tempfile::TempDir,
}

#[cfg(unix)]
impl Agent {
    /// None when `ssh-agent` is not installed (the test is then skipped).
    async fn spawn() -> Option<Self> {
        // Unix socket paths are short; the default temp dir on macOS is already near the limit.
        let dir = tempfile::Builder::new()
            .prefix("tg")
            .tempdir_in("/tmp")
            .ok()?;
        let socket = dir.path().join("agent.sock");
        let child = std::process::Command::new("ssh-agent")
            .args(["-D", "-a"])
            .arg(&socket)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .ok()?;
        let agent = Self {
            child,
            socket,
            _dir: dir,
        };
        for _ in 0..100 {
            if agent.socket.exists() {
                return Some(agent);
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        None
    }

    async fn add(&self, key: &PrivateKey) {
        let mut client = russh::keys::agent::client::AgentClient::connect_uds(&self.socket)
            .await
            .unwrap();
        client.add_identity(key, &[]).await.unwrap();
    }

    fn auth(&self) -> SshAuth {
        SshAuth::Agent {
            socket: Some(self.socket.display().to_string()),
        }
    }
}

#[cfg(unix)]
impl Drop for Agent {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(unix)]
#[tokio::test]
async fn authenticates_through_ssh_agent() {
    let Some(agent) = Agent::spawn().await else {
        eprintln!("skipped: ssh-agent is not available");
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let wanted = random_key();
    // An unrelated identity first: the tunnel must move on to the one the server accepts.
    agent.add(&random_key()).await;
    agent.add(&wanted).await;
    let server =
        TestServer::start(random_key(), key_accepts(vec![wanted.public_key().clone()])).await;
    assert_forwards(
        &spec(server.port, agent.auth(), &dir.path().join("kh")),
        &echo,
    )
    .await;
}

#[cfg(unix)]
#[tokio::test]
async fn agent_without_a_matching_identity_is_rejected() {
    let Some(agent) = Agent::spawn().await else {
        eprintln!("skipped: ssh-agent is not available");
        return;
    };
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(
        random_key(),
        key_accepts(vec![random_key().public_key().clone()]),
    )
    .await;

    let empty = SshTunnel::open(
        &spec(server.port, agent.auth(), &dir.path().join("kh")),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(matches!(empty, Err(SshError::Agent(_))), "{empty:?}");
    agent.add(&random_key()).await;
    let stranger = SshTunnel::open(
        &spec(server.port, agent.auth(), &dir.path().join("kh")),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(
        matches!(stranger, Err(SshError::AuthFailed { .. })),
        "{stranger:?}"
    );
}

#[cfg(unix)]
#[tokio::test]
async fn missing_agent_socket_is_an_agent_error() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let auth = SshAuth::Agent {
        socket: Some(dir.path().join("none.sock").display().to_string()),
    };
    let result = SshTunnel::open(
        &spec(server.port, auth, &dir.path().join("kh")),
        "127.0.0.1",
        echo.port,
    )
    .await;
    assert!(matches!(result, Err(SshError::Agent(_))), "{result:?}");
}

// ---- known_hosts and lifecycle ------------------------------------------------------------------------------

#[tokio::test]
async fn strict_policy_refuses_unknown_hosts_but_accepts_pinned_ones() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let known_hosts = dir.path().join("sub/known_hosts");
    let mut strict = spec(server.port, password("secret"), &known_hosts);
    strict.host_keys = HostKeyPolicy::Strict(known_hosts.clone());

    let unknown = SshTunnel::open(&strict, "127.0.0.1", echo.port).await;
    assert!(
        matches!(unknown, Err(SshError::HostKeyUnknown { .. })),
        "{unknown:?}"
    );
    assert!(!known_hosts.exists(), "nothing is pinned in strict mode");

    // Pin it by trusting on first use, then strict mode lets it through.
    let mut tofu = strict.clone();
    tofu.host_keys = HostKeyPolicy::TrustOnFirstUse(known_hosts);
    SshTunnel::open(&tofu, "127.0.0.1", echo.port)
        .await
        .unwrap()
        .close()
        .await;
    assert_forwards(&strict, &echo).await;
}

#[tokio::test]
async fn known_hosts_entries_use_openssh_syntax_for_non_default_ports() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let known_hosts = dir.path().join("known_hosts");
    SshTunnel::open(
        &spec(server.port, password("secret"), &known_hosts),
        "127.0.0.1",
        echo.port,
    )
    .await
    .unwrap()
    .close()
    .await;
    let line = std::fs::read_to_string(&known_hosts).unwrap();
    assert!(
        line.starts_with(&format!("[127.0.0.1]:{} ssh-ed25519 ", server.port)),
        "{line:?}"
    );
}

#[tokio::test]
async fn closing_or_dropping_stops_the_local_port() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let spec = spec(server.port, password("secret"), &dir.path().join("kh"));

    let closed = SshTunnel::open(&spec, "127.0.0.1", echo.port)
        .await
        .unwrap();
    let port = closed.local_port();
    closed.close().await;
    assert!(
        TcpStream::connect(("127.0.0.1", port)).await.is_err(),
        "port stays open after close"
    );

    let dropped = SshTunnel::open(&spec, "127.0.0.1", echo.port)
        .await
        .unwrap();
    let port = dropped.local_port();
    drop(dropped);
    let mut refused = false;
    for _ in 0..100 {
        if TcpStream::connect(("127.0.0.1", port)).await.is_err() {
            refused = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert!(refused, "port stays open after drop");
}

#[tokio::test]
async fn closing_the_tunnel_ends_connections_already_forwarded() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let tunnel = SshTunnel::open(
        &spec(server.port, password("secret"), &dir.path().join("kh")),
        "127.0.0.1",
        echo.port,
    )
    .await
    .unwrap();
    let mut socket = TcpStream::connect(("127.0.0.1", tunnel.local_port()))
        .await
        .unwrap();
    socket.write_all(b"ping").await.unwrap();
    let mut buf = [0u8; 4];
    socket.read_exact(&mut buf).await.unwrap();
    tunnel.close().await;
    let n = tokio::time::timeout(std::time::Duration::from_secs(5), socket.read(&mut buf))
        .await
        .unwrap();
    assert!(
        matches!(n, Ok(0) | Err(_)),
        "forwarded connection must end: {n:?}"
    );
}

#[tokio::test]
async fn serves_several_connections_at_once() {
    let dir = tempfile::tempdir().unwrap();
    let echo = Echo::start().await;
    let server = TestServer::start(random_key(), password_accepts()).await;
    let tunnel = SshTunnel::open(
        &spec(server.port, password("secret"), &dir.path().join("kh")),
        "127.0.0.1",
        echo.port,
    )
    .await
    .unwrap();
    let port = tunnel.local_port();
    let jobs: Vec<_> = (0..8)
        .map(|i| tokio::spawn(async move { round_trip(port, &format!("client {i}")).await }))
        .collect();
    for (i, job) in jobs.into_iter().enumerate() {
        assert_eq!(job.await.unwrap(), format!("client {i}"));
    }
}

#[tokio::test]
async fn a_refused_target_closes_the_local_connection() {
    let dir = tempfile::tempdir().unwrap();
    // A port nothing listens on.
    let dead = TcpListener::bind("127.0.0.1:0")
        .await
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let server = TestServer::start(random_key(), password_accepts()).await;
    let tunnel = SshTunnel::open(
        &spec(server.port, password("secret"), &dir.path().join("kh")),
        "127.0.0.1",
        dead,
    )
    .await
    .unwrap();
    let mut socket = TcpStream::connect(("127.0.0.1", tunnel.local_port()))
        .await
        .unwrap();
    let _ = socket.write_all(b"anyone there?").await;
    let mut buf = Vec::new();
    // Either a clean EOF or a reset, but never data and never a hang.
    let read = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        socket.read_to_end(&mut buf),
    )
    .await
    .unwrap();
    assert!(buf.is_empty(), "{read:?}");
}

#[tokio::test]
async fn unreachable_server_is_a_connect_error() {
    let dir = tempfile::tempdir().unwrap();
    let dead = TcpListener::bind("127.0.0.1:0")
        .await
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let result = SshTunnel::open(
        &spec(dead, password("secret"), &dir.path().join("kh")),
        "127.0.0.1",
        1,
    )
    .await;
    assert!(
        matches!(result, Err(SshError::Connect { .. })),
        "{result:?}"
    );
}

#[test]
fn open_blocking_serves_sync_callers() {
    let dir = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    let echo = runtime.block_on(Echo::start());
    let server = runtime.block_on(TestServer::start(random_key(), password_accepts()));
    let spec = spec(server.port, password("secret"), &dir.path().join("kh"));
    let tunnel = SshTunnel::open_blocking(runtime.handle(), &spec, "127.0.0.1", echo.port).unwrap();
    assert_eq!(
        runtime.block_on(round_trip(tunnel.local_port(), "sync")),
        "sync"
    );
    drop(tunnel);
}

#[test]
fn secrets_are_not_printed() {
    let shown = format!(
        "{:?} {:?}",
        password("hunter2"),
        key_auth("k", Some("hunter3"))
    );
    assert!(!shown.contains("hunter"), "{shown}");
}

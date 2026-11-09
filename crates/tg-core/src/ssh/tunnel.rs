//! The tunnel itself: connect, authenticate, then accept local connections and pipe each through a `direct-tcpip`
//! channel to the target.

use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::copy_bidirectional;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tokio::task::JoinSet;

use super::host_keys::{self, HostKeyHandler};
use super::{SshError, SshTunnelSpec, auth};

/// An open tunnel. Dropping it (or calling [`close`](Self::close)) tears everything down.
#[derive(Debug)]
pub struct SshTunnel {
    local_port: u16,
    /// Dropping this tells the run task to stop.
    shutdown: Option<oneshot::Sender<()>>,
    done: Option<tokio::task::JoinHandle<()>>,
}

impl SshTunnel {
    /// Connect to the SSH server, authenticate and start forwarding `127.0.0.1:<local_port>` to
    /// `target_host:target_port` as seen from the server. The local port is chosen by the OS.
    pub async fn open(
        spec: &SshTunnelSpec,
        target_host: &str,
        target_port: u16,
    ) -> Result<Self, SshError> {
        host_keys::ensure_dir(&spec.host_keys)?;
        let (handler, rejection) = HostKeyHandler::new(&spec.host, spec.port, &spec.host_keys);
        let config = Arc::new(russh::client::Config {
            keepalive_interval: Some(Duration::from_secs(30)),
            keepalive_max: 3,
            nodelay: true,
            ..Default::default()
        });

        let setup = async {
            let tcp = TcpStream::connect((spec.host.as_str(), spec.port))
                .await
                .map_err(|source| SshError::Connect {
                    host: spec.host.clone(),
                    port: spec.port,
                    source,
                })?;
            let mut handle = match russh::client::connect_stream(config, tcp, handler).await {
                Ok(handle) => handle,
                Err(e) => {
                    let why = rejection.lock().unwrap().take();
                    return Err(host_keys::into_error(why, &spec.host, spec.port)
                        .unwrap_or(SshError::Protocol(e)));
                }
            };
            auth::authenticate(&mut handle, spec).await?;
            Ok::<_, SshError>(handle)
        };
        let handle = tokio::time::timeout(spec.connect_timeout, setup)
            .await
            .map_err(|_| SshError::Timeout {
                host: spec.host.clone(),
                port: spec.port,
            })??;

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let local_port = listener.local_addr()?.port();
        let (shutdown, stop) = oneshot::channel();
        let target = (target_host.to_owned(), target_port);
        let done = tokio::spawn(run(handle, listener, target, stop));
        Ok(Self {
            local_port,
            shutdown: Some(shutdown),
            done: Some(done),
        })
    }

    /// [`open`](Self::open) for callers outside async code: runs it to completion on `runtime`, which then also hosts
    /// the tunnel's background tasks. Panics if called from within that runtime.
    pub fn open_blocking(
        runtime: &tokio::runtime::Handle,
        spec: &SshTunnelSpec,
        target_host: &str,
        target_port: u16,
    ) -> Result<Self, SshError> {
        runtime.block_on(Self::open(spec, target_host, target_port))
    }

    /// The local port on 127.0.0.1 that reaches the target.
    pub fn local_port(&self) -> u16 {
        self.local_port
    }

    /// Stop listening, drop open forwarded connections and disconnect, waiting until that is done.
    pub async fn close(mut self) {
        drop(self.shutdown.take());
        if let Some(done) = self.done.take() {
            let _ = done.await;
        }
    }
}

// Drop needs no code: dropping `shutdown` wakes the run task, which cleans up on the runtime. The JoinHandle is
// detached, not aborted, so the SSH disconnect message still goes out.

async fn run(
    handle: russh::client::Handle<HostKeyHandler>,
    listener: TcpListener,
    target: (String, u16),
    mut stop: oneshot::Receiver<()>,
) {
    let handle = Arc::new(handle);
    // Dropping the set aborts every in-flight connection task.
    let mut connections = JoinSet::new();
    loop {
        tokio::select! {
            _ = &mut stop => break,
            Some(_) = connections.join_next(), if !connections.is_empty() => {}
            accepted = listener.accept() => {
                let Ok((mut local, peer)) = accepted else { break };
                let handle = handle.clone();
                let (host, port) = target.clone();
                connections.spawn(async move {
                    let origin = (peer.ip().to_string(), u32::from(peer.port()));
                    // If the server refuses the target (or the session is gone), dropping `local` tells the client.
                    if let Ok(channel) = handle.channel_open_direct_tcpip(host, u32::from(port), origin.0, origin.1).await {
                        let mut remote = channel.into_stream();
                        let _ = copy_bidirectional(&mut local, &mut remote).await;
                    }
                });
            }
        }
    }
    drop(listener);
    drop(connections);
    let _ = handle
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
}

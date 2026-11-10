//! The user's own PATH, which is where their CLI agents live (`agent/cli/ShellEnvironment.kt`). An app launched from
//! Finder inherits launchd's PATH, and installers append their PATH lines to `.zshrc`, which only interactive shells
//! source; so the PATH is read once from an interactive login shell and cached.

use std::path::PathBuf;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::sync::Mutex;

/// Brackets the value, so whatever the rc files print around it is discarded.
const MARKER: &str = "__TABLEGRIP_PATH__";
/// An rc file that blocks on the network or a prompt must not hang discovery.
const TIMEOUT: Duration = Duration::from_secs(5);

static CACHED: Mutex<Option<String>> = Mutex::const_new(None);

pub async fn path(rescan: bool) -> String {
    let mut cached = CACHED.lock().await;
    if rescan {
        *cached = None;
    }
    if let Some(p) = cached.as_ref() {
        return p.clone();
    }
    let fresh = read().await;
    *cached = Some(fresh.clone());
    fresh
}

/// Where `binary` lives on the user's PATH, or None when it isn't installed.
pub async fn locate(binary: &str, rescan: bool) -> Option<PathBuf> {
    let names: Vec<String> = if cfg!(windows) {
        vec![
            format!("{binary}.exe"),
            format!("{binary}.cmd"),
            format!("{binary}.bat"),
            binary.to_owned(),
        ]
    } else {
        vec![binary.to_owned()]
    };
    let path = path(rescan).await;
    std::env::split_paths(&path)
        .filter(|d| !d.as_os_str().is_empty())
        .flat_map(|d| names.iter().map(move |n| d.join(n)))
        .find(|p| p.is_file() && executable(p))
}

#[cfg(unix)]
fn executable(path: &std::path::Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).is_ok_and(|m| m.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn executable(_: &std::path::Path) -> bool {
    true
}

async fn read() -> String {
    let inherited = std::env::var("PATH").unwrap_or_default();
    if cfg!(windows) {
        return inherited;
    }
    let shell = std::env::var("SHELL")
        .ok()
        .filter(|s| executable(std::path::Path::new(s)))
        .unwrap_or_else(|| "/bin/zsh".into());
    let from_shell = async {
        let mut child = tokio::process::Command::new(&shell)
            .args([
                "-i",
                "-l",
                "-c",
                &format!("printf '%s%s' '{MARKER}' \"$PATH\""),
            ])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .ok()?;
        let mut out = String::new();
        let mut stdout = child.stdout.take()?;
        let read = tokio::time::timeout(TIMEOUT, stdout.read_to_string(&mut out)).await;
        let _ = child.kill().await;
        read.ok()?.ok()?;
        out.rsplit_once(MARKER).map(|(_, p)| p.trim().to_owned())
    }
    .await
    .unwrap_or_default();
    [from_shell, inherited]
        .into_iter()
        .filter(|p| !p.trim().is_empty())
        .collect::<Vec<_>>()
        .join(if cfg!(windows) { ";" } else { ":" })
}

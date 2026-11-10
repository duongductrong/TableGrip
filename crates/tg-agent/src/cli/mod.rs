//! CLI agents run headless (`agent/cli/*.kt`): one process per turn, the prompt as one argument, stdout decoded
//! into events.

pub mod decoder;
pub mod shell;

pub use decoder::{CliFailure, CliOutputDecoder, CliOutputFormat};

use crate::{
    AgentAdapter, AgentDescriptor, AgentError, AgentEvent, AgentModel, AgentRequest, AgentSource,
    AgentTransport, EventStream,
};
use async_trait::async_trait;
use regex::Regex;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::sync::Mutex;

const PROMPT: &str = "{prompt}";
const MODEL: &str = "{model}";
const STDERR_CAP: usize = 64 * 1024;
/// Codex's catalog carries each model's whole system prompt.
const MODELS_CAP: usize = 8 * 1024 * 1024;
/// Some list over the network; a slow one just leaves the model to be typed.
const MODELS_TIMEOUT: Duration = Duration::from_secs(30);

static ANSI: LazyLock<Regex> = LazyLock::new(|| Regex::new("\u{1b}\\[[0-9;]*[A-Za-z]").unwrap());

/// How a preset's CLI says which models it offers: the arguments that print them, and how to read what it prints.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CliModelList {
    /// No way to ask: the model is typed by hand.
    None,
    /// Claude Code can't list models, but these aliases always resolve to the latest of each.
    ClaudeAliases,
    /// `codex debug models`: the model catalog as JSON; `visibility: "list"` marks the ones its own picker shows.
    Codex,
    /// `cursor-agent models`: `id - Name` lines under a heading.
    Cursor,
    /// `grok models`: `* id (default)` and `- id` lines.
    Grok,
    /// `opencode models`: one `provider/model` per line.
    OpenCode,
    /// `agy models`: `id<TAB>Name` lines after a progress line.
    Antigravity,
}

impl CliModelList {
    pub fn arguments(self) -> &'static [&'static str] {
        match self {
            Self::None | Self::ClaudeAliases => &[],
            Self::Codex => &["debug", "models"],
            _ => &["models"],
        }
    }

    /// Whether the list comes from running the CLI rather than being known up front.
    pub fn runs(self) -> bool {
        !self.arguments().is_empty()
    }

    pub fn parse(self, output: &str) -> Vec<AgentModel> {
        static CURSOR: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(\S+) - (.+)$").unwrap());
        static GROK: LazyLock<Regex> =
            LazyLock::new(|| Regex::new(r"^\s*[*-]\s+(\S+)(?:\s+\(.*\))?\s*$").unwrap());
        static OPENCODE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[^\s/]+/\S+$").unwrap());
        static MARKER: LazyLock<Regex> =
            LazyLock::new(|| Regex::new(r"(?i)\s*\((default|current)\)\s*$").unwrap());
        let clean = ANSI.replace_all(output, "");
        let lines: Vec<&str> = clean
            .split('\n')
            .map(|l| l.strip_suffix('\r').unwrap_or(l))
            .collect();
        let models: Vec<AgentModel> = match self {
            Self::None => Vec::new(),
            Self::ClaudeAliases => ["fable", "opus", "sonnet", "haiku"]
                .iter()
                .map(|m| AgentModel::new(m, None))
                .collect(),
            Self::Codex => codex(output),
            Self::Cursor => lines
                .iter()
                .filter_map(|l| {
                    CURSOR
                        .captures(l.trim())
                        .map(|c| AgentModel::new(&c[1], Some(&MARKER.replace(&c[2], ""))))
                })
                .collect(),
            Self::Grok => lines
                .iter()
                .filter_map(|l| GROK.captures(l).map(|c| AgentModel::new(&c[1], None)))
                .collect(),
            Self::OpenCode => lines
                .iter()
                .map(|l| l.trim())
                .filter(|l| OPENCODE.is_match(l))
                .map(|l| AgentModel::new(l, None))
                .collect(),
            Self::Antigravity => lines
                .iter()
                .filter_map(|l| {
                    let parts: Vec<&str> = l.trim().split('\t').collect();
                    (parts.len() >= 2 && !parts[0].trim().is_empty() && !parts[0].contains(' '))
                        .then(|| AgentModel::new(parts[0], Some(parts[1].trim())))
                })
                .collect(),
        };
        let mut seen = std::collections::HashSet::new();
        models
            .into_iter()
            .filter(|m| seen.insert(m.id.clone()))
            .collect()
    }
}

fn codex(output: &str) -> Vec<AgentModel> {
    let Some(start) = output.find('{') else {
        return Vec::new();
    };
    let Ok(Value::Object(catalog)) = serde_json::from_str::<Value>(&output[start..]) else {
        return Vec::new();
    };
    let Some(models) = catalog.get("models").and_then(Value::as_array) else {
        return Vec::new();
    };
    models
        .iter()
        .filter_map(|m| {
            let m = m.as_object()?;
            let text = |k: &str| {
                m.get(k)
                    .and_then(Value::as_str)
                    .filter(|s| !s.trim().is_empty())
            };
            let slug = text("slug")?;
            if text("visibility").is_some_and(|v| v != "list") {
                return None;
            }
            Some(AgentModel::new(slug, text("display_name")))
        })
        .collect()
}

/// The CLI agents TableGrip knows how to run headless. Each is configured to *answer*, not to act: built-in tools
/// are switched off where the CLI allows it, and elsewhere it runs in its read-only, ask or plan mode.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CliAgentPreset {
    Claude,
    Codex,
    Gemini,
    Cursor,
    Grok,
    OpenCode,
    Antigravity,
    Pi,
    Hermes,
}

impl CliAgentPreset {
    pub const ALL: [Self; 9] = [
        Self::Claude,
        Self::Codex,
        Self::Gemini,
        Self::Cursor,
        Self::Grok,
        Self::OpenCode,
        Self::Antigravity,
        Self::Pi,
        Self::Hermes,
    ];

    pub fn title(self) -> &'static str {
        match self {
            Self::Claude => "Claude Code",
            Self::Codex => "Codex",
            Self::Gemini => "Gemini CLI",
            Self::Cursor => "Cursor Agent",
            Self::Grok => "Grok",
            Self::OpenCode => "OpenCode",
            Self::Antigravity => "Antigravity",
            Self::Pi => "Pi",
            Self::Hermes => "Hermes",
        }
    }

    pub fn binary(self) -> &'static str {
        match self {
            Self::Claude => "claude",
            Self::Codex => "codex",
            Self::Gemini => "gemini",
            Self::Cursor => "cursor-agent",
            Self::Grok => "grok",
            Self::OpenCode => "opencode",
            Self::Antigravity => "agy",
            Self::Pi => "pi",
            Self::Hermes => "hermes",
        }
    }

    fn template(self) -> &'static [&'static str] {
        match self {
            // `--tools ""` removes every built-in tool and `--strict-mcp-config` (with no config given) every MCP server.
            Self::Claude => &[
                "--safe-mode",
                "--no-session-persistence",
                "--tools",
                "",
                "--strict-mcp-config",
                "--permission-mode",
                "dontAsk",
                "-p",
                "--verbose",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                MODEL,
                PROMPT,
            ],
            // Codex can't drop its shell, but the read-only sandbox keeps commands from writing or reaching the network.
            Self::Codex => &[
                "exec",
                "--skip-git-repo-check",
                "--ephemeral",
                "--sandbox",
                "read-only",
                "--ignore-rules",
                "--json",
                MODEL,
                PROMPT,
            ],
            Self::Gemini => &[
                "--approval-mode",
                "default",
                "--output-format",
                "stream-json",
                MODEL,
                PROMPT,
            ],
            Self::Cursor => &[
                "--mode",
                "ask",
                "--trust",
                MODEL,
                "-p",
                "--output-format",
                "stream-json",
                "--stream-partial-output",
                PROMPT,
            ],
            Self::Grok => &[
                "--permission-mode",
                "dontAsk",
                "--no-plan",
                MODEL,
                "--output-format",
                "streaming-messages-json",
                "--include-partial-messages",
                "-p",
                PROMPT,
            ],
            Self::OpenCode => &[
                "run",
                "--agent",
                "plan",
                "--format",
                "json",
                "--thinking",
                MODEL,
                PROMPT,
            ],
            Self::Antigravity => &[
                "--mode",
                "plan",
                MODEL,
                "--output-format",
                "stream-json",
                "-p",
                PROMPT,
            ],
            Self::Pi => &["--no-session", "-p", PROMPT],
            Self::Hermes => &["-z", PROMPT],
        }
    }

    pub fn output(self) -> CliOutputFormat {
        match self {
            Self::Claude | Self::Cursor | Self::Grok => CliOutputFormat::ClaudeStreamJson,
            Self::Codex => CliOutputFormat::CodexJson,
            Self::Gemini => CliOutputFormat::GeminiStreamJson,
            Self::OpenCode => CliOutputFormat::OpenCodeJson,
            Self::Antigravity => CliOutputFormat::AntigravityStreamJson,
            Self::Pi | Self::Hermes => CliOutputFormat::PlainText,
        }
    }

    pub fn timeout(self) -> Duration {
        // Antigravity starts slowly (~10s of process startup before the model is asked).
        Duration::from_secs(if self == Self::Antigravity { 300 } else { 180 })
    }

    /// The flag that picks a model; None when the CLI can't be told one.
    pub fn model_flag(self) -> Option<&'static str> {
        match self {
            Self::Pi | Self::Hermes => None,
            _ => Some("--model"),
        }
    }

    pub fn models(self) -> CliModelList {
        match self {
            Self::Claude => CliModelList::ClaudeAliases,
            Self::Codex => CliModelList::Codex,
            Self::Cursor => CliModelList::Cursor,
            Self::Grok => CliModelList::Grok,
            Self::OpenCode => CliModelList::OpenCode,
            Self::Antigravity => CliModelList::Antigravity,
            _ => CliModelList::None,
        }
    }

    /// The argv after the binary, with the prompt passed as one argument: no shell, so nothing in it needs quoting.
    /// A blank `model` leaves the flag out.
    pub fn arguments(self, prompt: &str, model: Option<&str>) -> Vec<String> {
        let chosen = model.map(str::trim).unwrap_or("");
        let mut out = Vec::new();
        for a in self.template() {
            match *a {
                PROMPT => out.push(prompt.to_owned()),
                MODEL => {
                    if let (Some(flag), false) = (self.model_flag(), chosen.is_empty()) {
                        out.push(flag.to_owned());
                        out.push(chosen.to_owned());
                    }
                }
                other => out.push(other.to_owned()),
            }
        }
        out
    }

    pub fn chooses_model(self) -> bool {
        self.model_flag().is_some()
    }

    pub fn id(self) -> String {
        format!("cli:{}", self.binary())
    }
}

/// The CLI agents installed on this machine.
pub struct CliAgentSource {
    presets: Vec<CliAgentPreset>,
}

impl CliAgentSource {
    pub fn all() -> Self {
        Self {
            presets: CliAgentPreset::ALL.to_vec(),
        }
    }
}

#[async_trait]
impl AgentSource for CliAgentSource {
    async fn discover(&self, rescan: bool) -> Vec<Arc<dyn AgentAdapter>> {
        shell::path(rescan).await;
        let mut out: Vec<Arc<dyn AgentAdapter>> = Vec::new();
        for preset in &self.presets {
            if let Some(executable) = shell::locate(preset.binary(), false).await {
                out.push(Arc::new(CliAgentAdapter::new(*preset, executable)));
            }
        }
        out
    }
}

/// Runs a CLI agent headless, one process per turn, and streams its stdout as the answer. The process runs the
/// binary directly with the user's PATH, stdin closed and the variables that turn a terminal tool into a quiet one.
/// Dropping the stream kills it.
pub struct CliAgentAdapter {
    preset: CliAgentPreset,
    executable: PathBuf,
    descriptor: AgentDescriptor,
    listed: Mutex<Option<Vec<AgentModel>>>,
}

impl CliAgentAdapter {
    pub fn new(preset: CliAgentPreset, executable: PathBuf) -> Self {
        let descriptor = AgentDescriptor {
            id: preset.id(),
            name: preset.title().to_owned(),
            transport: AgentTransport::Cli,
            detail: Some(executable.display().to_string()),
        };
        Self {
            preset,
            executable,
            descriptor,
            listed: Mutex::new(None),
        }
    }

    async fn list_models(&self) -> Vec<AgentModel> {
        let Ok(mut child) = command(&self.executable, self.preset.models().arguments(), None)
            .await
            .spawn()
        else {
            return Vec::new();
        };
        let mut stdout = child.stdout.take();
        let mut stderr = child.stderr.take();
        let drain = tokio::spawn(async move {
            if let Some(e) = stderr.as_mut() {
                let _ = read_bounded(e, STDERR_CAP).await;
            }
        });
        let output = tokio::time::timeout(MODELS_TIMEOUT, async {
            match stdout.as_mut() {
                Some(o) => read_bounded(o, MODELS_CAP).await,
                None => String::new(),
            }
        })
        .await;
        drain.abort();
        let _ = child.kill().await;
        output
            .map(|o| self.preset.models().parse(&o))
            .unwrap_or_default()
    }
}

/// Reads all of `reader`, keeping at most `cap` bytes: a child blocked on a full pipe never exits.
async fn read_bounded(reader: &mut (impl AsyncReadExt + Unpin), cap: usize) -> String {
    let mut out = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        match reader.read(&mut buffer).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let room = cap.saturating_sub(out.len());
                out.extend_from_slice(&buffer[..n.min(room)]);
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// The binary with `arguments`, stdin closed and the variables that turn a terminal tool into a quiet one.
async fn command(
    executable: &Path,
    arguments: &[impl AsRef<std::ffi::OsStr>],
    directory: Option<&Path>,
) -> tokio::process::Command {
    let mut c = tokio::process::Command::new(executable);
    c.args(arguments)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .env("PATH", shell::path(false).await)
        .env("CI", "1")
        .env("TERM", "dumb")
        .env("NO_COLOR", "1")
        .env("FORCE_COLOR", "0")
        .env("PAGER", "cat")
        .env("GIT_PAGER", "cat");
    if let Some(d) = directory {
        c.current_dir(d);
    }
    c
}

/// One useful line out of a CLI's stderr: a usage dump is named for what it is, and otherwise the last line naming a
/// problem wins.
pub fn diagnostic(text: &str) -> String {
    // Some CLIs colour their errors even with NO_COLOR set.
    let clean = ANSI.replace_all(text, "");
    let lines: Vec<&str> = clean
        .lines()
        .map(str::trim)
        .filter(|l| {
            !l.is_empty()
                && l.chars().filter(|c| c.is_alphanumeric()).count() * 2 >= l.chars().count()
        })
        .collect();
    let Some(last) = lines.last() else {
        return String::new();
    };
    if lines
        .iter()
        .any(|l| l.to_lowercase().starts_with("usage:") || *l == "Commands:" || *l == "Options:")
    {
        return "The agent rejected its command line — its CLI may be out of date.".into();
    }
    const MARKERS: &[&str] = &[
        "error",
        "unknown",
        "invalid",
        "not found",
        "no such",
        "failed",
        "missing",
        "denied",
        "unauthorized",
        "forbidden",
        "expired",
        "login",
    ];
    let named = lines.iter().rev().find(|l| {
        let lower = l.to_lowercase();
        MARKERS.iter().any(|m| lower.contains(m))
    });
    named.unwrap_or(last).chars().take(240).collect()
}

#[async_trait]
impl AgentAdapter for CliAgentAdapter {
    fn descriptor(&self) -> &AgentDescriptor {
        &self.descriptor
    }

    fn chooses_model(&self) -> bool {
        self.preset.chooses_model()
    }

    /// Asked once per adapter; a list that fails or comes back empty isn't kept, so the next look tries again.
    async fn models(&self) -> Vec<AgentModel> {
        if !self.preset.chooses_model() {
            return Vec::new();
        }
        if !self.preset.models().runs() {
            return self.preset.models().parse("");
        }
        let mut listed = self.listed.lock().await;
        if let Some(l) = listed.as_ref() {
            return l.clone();
        }
        let fresh = self.list_models().await;
        if !fresh.is_empty() {
            *listed = Some(fresh.clone());
        }
        fresh
    }

    fn send(&self, request: AgentRequest) -> EventStream {
        let preset = self.preset;
        let executable = self.executable.clone();
        EventStream::spawn(move |events| async move {
            events.send(AgentEvent::Thinking).await;
            let directory = request.working_directory.clone();
            if let Some(d) = &directory {
                let _ = std::fs::create_dir_all(d);
            }
            let arguments = preset.arguments(&request.to_prompt(), request.model.as_deref());
            let mut child = command(&executable, &arguments, directory.as_deref())
                .await
                .spawn()
                .map_err(|_| {
                    AgentError(format!(
                        "Couldn't start {} ({}).",
                        preset.title(),
                        preset.binary()
                    ))
                })?;
            let mut stdout = child
                .stdout
                .take()
                .ok_or_else(|| AgentError("No output".into()))?;
            let mut stderr_pipe = child.stderr.take();
            let stderr = tokio::spawn(async move {
                match stderr_pipe.as_mut() {
                    Some(e) => read_bounded(e, STDERR_CAP).await,
                    None => String::new(),
                }
            });
            let mut decoder = preset.output().decoder(preset.title());
            let reading = async {
                let mut buffer = [0u8; 4096];
                let mut pending: Vec<u8> = Vec::new();
                loop {
                    let n = stdout.read(&mut buffer).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    pending.extend_from_slice(&buffer[..n]);
                    // A read may end inside a multi-byte character; keep its start for the next one.
                    let valid = match std::str::from_utf8(&pending) {
                        Ok(_) => pending.len(),
                        Err(e) if e.error_len().is_none() => e.valid_up_to(),
                        Err(_) => pending.len(),
                    };
                    let chunk = String::from_utf8_lossy(&pending[..valid]).into_owned();
                    pending.drain(..valid);
                    for event in decoder.consume(&chunk) {
                        if !events.send(event).await {
                            return false;
                        }
                    }
                }
                true
            };
            let finished = tokio::time::timeout(preset.timeout(), reading).await;
            let timed_out = finished.is_err();
            if timed_out {
                let _ = child.kill().await;
                return Err(AgentError(format!(
                    "No answer from {} in {}s.",
                    preset.title(),
                    preset.timeout().as_secs()
                )));
            }
            if finished == Ok(false) {
                let _ = child.kill().await;
                return Ok(());
            }
            // Stdout closing comes a moment before the exit; let it finish rather than kill it on its way out.
            let status = match tokio::time::timeout(Duration::from_secs(5), child.wait()).await {
                Ok(Ok(s)) => s.code().unwrap_or(-1),
                _ => {
                    let _ = child.kill().await;
                    -1
                }
            };
            let errors = stderr.await.unwrap_or_default();
            let failed = || {
                let d = diagnostic(&errors);
                AgentError(if d.is_empty() {
                    format!("{} exited with code {status}.", preset.title())
                } else {
                    d
                })
            };
            let text = match decoder.finish(status, &errors) {
                Ok(t) => t,
                Err(CliFailure::Stderr) => return Err(failed()),
                Err(CliFailure::Agent(e)) => return Err(e),
            };
            // Several of these agents exit non-zero on a warning they also print: only an empty run is a failure.
            if text.is_empty() {
                return Err(if status != 0 {
                    failed()
                } else {
                    AgentError(format!("{} returned nothing.", preset.title()))
                });
            }
            events.send(AgentEvent::Completed(text)).await;
            Ok(())
        })
    }
}

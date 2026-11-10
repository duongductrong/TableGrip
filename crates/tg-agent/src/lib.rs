//! The agent chat and assists (`core/.../agent`, `core/.../assist`): agents reached through a CLI installed on this
//! machine or an HTTP provider, all reduced to one stream of [`AgentEvent`]s; chats kept between launches; mentions;
//! and one-shot assists (SQL fix, chart) that come back as checked data.

pub mod assist;
pub mod chat;
pub mod cli;
pub mod mention;
pub mod provider;

use async_trait::async_trait;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

/// How TableGrip reaches an agent. Every transport reduces to the same [`AgentAdapter::send`] stream.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AgentTransport {
    Cli,
    Acp,
    Http,
}

/// What the agent picker shows. `id` is stable across launches, so the last-used agent can be remembered.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentDescriptor {
    pub id: String,
    pub name: String,
    pub transport: AgentTransport,
    pub detail: Option<String>,
}

/// A model an agent can be asked for. `id` is what the agent takes; `label` is its display name, when it gives one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentModel {
    pub id: String,
    pub label: Option<String>,
}

impl AgentModel {
    pub fn new(id: &str, label: Option<&str>) -> Self {
        Self {
            id: id.to_owned(),
            label: label.map(str::to_owned),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum Role {
    User,
    Assistant,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentMessage {
    pub role: Role,
    pub text: String,
}

impl AgentMessage {
    pub fn new(role: Role, text: impl Into<String>) -> Self {
        Self {
            role,
            text: text.into(),
        }
    }
}

/// One turn: the whole conversation so far, ending with the user's new message.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AgentRequest {
    pub messages: Vec<AgentMessage>,
    /// Framing for the turn; transports without a system channel put it at the top of the prompt.
    pub system: Option<String>,
    /// Where a local agent runs; transports that can't touch this machine ignore it.
    pub working_directory: Option<PathBuf>,
    /// Rules restated right before the latest message, where agents weigh them most.
    pub reminder: Option<String>,
    /// The model to answer with; None or blank leaves it to the agent.
    pub model: Option<String>,
}

impl AgentRequest {
    /// Flattens the conversation into one prompt, for transports that take a single string and keep no session.
    pub fn to_prompt(&self) -> String {
        let latest = self.messages.last().map_or("", |m| m.text.as_str());
        let earlier = &self.messages[..self.messages.len().saturating_sub(1)];
        let mut out = String::new();
        if let Some(system) = self.system.as_deref().filter(|s| !s.trim().is_empty()) {
            out.push_str(system.trim());
            out.push_str("\n\n");
        }
        if !earlier.is_empty() {
            out.push_str("Conversation so far:\n");
            for message in earlier {
                out.push_str(if message.role == Role::User {
                    "User: "
                } else {
                    "Assistant: "
                });
                out.push_str(message.text.trim());
                out.push_str("\n\n");
            }
            out.push_str("User: ");
        }
        if let Some(reminder) = self.reminder.as_deref().filter(|r| !r.trim().is_empty()) {
            out.push_str("<system-reminder>\n");
            out.push_str(reminder.trim());
            out.push_str("\n</system-reminder>\n\n");
        }
        out.push_str(latest.trim());
        out
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum ActivityState {
    Running,
    Done,
    Failed,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AgentEvent {
    /// The agent started working; a progress signal with nothing to show yet.
    Thinking,
    TextDelta(String),
    /// The agent's reasoning, for showing while it works. Not part of the answer, and never sent back to it.
    ReasoningDelta(String),
    /// Something the agent does besides writing: a tool call, a command, a search. Updates share the `id`.
    Activity {
        id: String,
        label: String,
        state: ActivityState,
    },
    /// The whole answer. Always the last event of a successful turn.
    Completed(String),
}

/// A failed turn, with a message short enough to show in the chat as the reason.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentError(pub String);

impl std::fmt::Display for AgentError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for AgentError {}

pub type Result<T> = std::result::Result<T, AgentError>;

/// Where an agent's events go while it runs; `send` is false once the reader dropped the stream.
#[derive(Clone)]
pub struct Events(mpsc::Sender<Result<AgentEvent>>);

impl Events {
    pub async fn send(&self, event: AgentEvent) -> bool {
        self.0.send(Ok(event)).await.is_ok()
    }
}

/// One turn's events. Dropping it stops the agent (the task running it is aborted, and with it the process or the
/// HTTP exchange).
pub struct EventStream {
    rx: mpsc::Receiver<Result<AgentEvent>>,
    task: Option<JoinHandle<()>>,
}

impl EventStream {
    /// Runs `body` on the tokio runtime, streaming what it sends; an error it returns ends the stream.
    pub fn spawn<F>(body: impl FnOnce(Events) -> F) -> Self
    where
        F: std::future::Future<Output = Result<()>> + Send + 'static,
    {
        let (tx, rx) = mpsc::channel(256);
        let events = Events(tx.clone());
        let future = body(events);
        let task = tokio::spawn(async move {
            if let Err(e) = future.await {
                let _ = tx.send(Err(e)).await;
            }
        });
        Self {
            rx,
            task: Some(task),
        }
    }

    pub async fn next(&mut self) -> Option<Result<AgentEvent>> {
        self.rx.recv().await
    }

    /// Every event until the end, or the error that ended the turn.
    pub async fn collect(mut self) -> Result<Vec<AgentEvent>> {
        let mut out = Vec::new();
        while let Some(event) = self.next().await {
            out.push(event?);
        }
        Ok(out)
    }
}

impl Drop for EventStream {
    fn drop(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

/// One agent the chat can talk to. Implementations are stateless between turns: the request carries the
/// conversation.
#[async_trait]
pub trait AgentAdapter: Send + Sync {
    fn descriptor(&self) -> &AgentDescriptor;

    /// Streams one turn; failures end the stream with an [`AgentError`].
    fn send(&self, request: AgentRequest) -> EventStream;

    /// Whether [`AgentRequest::model`] is honoured; when false the agent always answers with its own model.
    fn chooses_model(&self) -> bool {
        false
    }

    /// The models the agent says it offers, best first. Empty when it can't tell. Never fails.
    async fn models(&self) -> Vec<AgentModel> {
        Vec::new()
    }
}

/// This agent, answering every turn with `model`; itself when it can't choose one or `model` is blank.
pub fn with_model(agent: Arc<dyn AgentAdapter>, model: Option<&str>) -> Arc<dyn AgentAdapter> {
    let chosen = model.map(str::trim).unwrap_or("");
    if !agent.chooses_model() || chosen.is_empty() {
        return agent;
    }
    Arc::new(WithModel {
        agent,
        model: chosen.to_owned(),
    })
}

struct WithModel {
    agent: Arc<dyn AgentAdapter>,
    model: String,
}

#[async_trait]
impl AgentAdapter for WithModel {
    fn descriptor(&self) -> &AgentDescriptor {
        self.agent.descriptor()
    }

    fn send(&self, mut request: AgentRequest) -> EventStream {
        request.model = Some(self.model.clone());
        self.agent.send(request)
    }

    fn chooses_model(&self) -> bool {
        true
    }

    async fn models(&self) -> Vec<AgentModel> {
        self.agent.models().await
    }
}

/// Somewhere agents come from: the CLIs on this machine, providers configured in settings.
#[async_trait]
pub trait AgentSource: Send + Sync {
    /// The agents this source can offer right now. `rescan` drops anything cached.
    async fn discover(&self, rescan: bool) -> Vec<Arc<dyn AgentAdapter>>;
}

/// Every agent TableGrip can offer, gathered from its sources.
pub struct AgentRegistry {
    sources: Vec<Box<dyn AgentSource>>,
}

impl AgentRegistry {
    pub fn new(sources: Vec<Box<dyn AgentSource>>) -> Self {
        Self { sources }
    }

    /// The CLIs on this machine or the provider in settings, whichever `mode` picks at discovery time.
    pub fn standard(
        mode: Arc<dyn Fn() -> provider::AgentMode + Send + Sync>,
        config: Arc<dyn Fn() -> provider::ProviderConfig + Send + Sync>,
        api_key: Arc<dyn Fn(provider::ProviderProtocol) -> Option<String> + Send + Sync>,
    ) -> Self {
        Self::new(vec![
            Box::new(ModeSource {
                active: provider::AgentMode::Cli,
                mode: mode.clone(),
                source: Box::new(cli::CliAgentSource::all()),
            }),
            Box::new(ModeSource {
                active: provider::AgentMode::Provider,
                mode,
                source: Box::new(provider::ProviderAgentSource::new(config, api_key)),
            }),
        ])
    }

    /// Asks every source at once; duplicates by id are dropped.
    pub async fn discover(&self, rescan: bool) -> Vec<Arc<dyn AgentAdapter>> {
        let all =
            futures_util::future::join_all(self.sources.iter().map(|s| s.discover(rescan))).await;
        let mut seen = std::collections::HashSet::new();
        all.into_iter()
            .flatten()
            .filter(|a| seen.insert(a.descriptor().id.clone()))
            .collect()
    }
}

/// `source` while `mode` is `active`, nothing otherwise; the switch takes effect on the next discovery.
struct ModeSource {
    active: provider::AgentMode,
    mode: Arc<dyn Fn() -> provider::AgentMode + Send + Sync>,
    source: Box<dyn AgentSource>,
}

#[async_trait]
impl AgentSource for ModeSource {
    async fn discover(&self, rescan: bool) -> Vec<Arc<dyn AgentAdapter>> {
        if (self.mode)() == self.active {
            self.source.discover(rescan).await
        } else {
            Vec::new()
        }
    }
}

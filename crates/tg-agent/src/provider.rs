//! An HTTP provider as one agent (`agent/provider/*.kt`): the Anthropic Messages API or the OpenAI Chat Completions
//! shape (which most gateways and local servers also serve), streamed as server-sent events. No tools are offered, so
//! a query the agent wants run comes back as text for the user to allow, as with the CLI agents.

use crate::{
    AgentAdapter, AgentDescriptor, AgentError, AgentEvent, AgentRequest, AgentSource,
    AgentTransport, EventStream, Role,
};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::Duration;

const ANTHROPIC_VERSION: &str = "2026-10-21";
/// A cap on one answer, not a spend: only what the model writes is billed.
const MAX_TOKENS: u32 = 16_000;
const ERROR_CAP: usize = 16 * 1024;

/// Where the chat panel's agents come from: the CLIs on this machine (the default), or an HTTP API.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AgentMode {
    Cli,
    Provider,
}

impl AgentMode {
    pub fn label(self) -> &'static str {
        match self {
            Self::Cli => "Command-line agents",
            Self::Provider => "API provider",
        }
    }

    /// Unknown or blank names fall back to Cli: a bad edit must not leave the panel with no agent.
    pub fn of(name: &str) -> Self {
        if name == "Provider" {
            Self::Provider
        } else {
            Self::Cli
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ProviderProtocol {
    Anthropic,
    OpenAi,
}

impl ProviderProtocol {
    pub fn name(self) -> &'static str {
        match self {
            Self::Anthropic => "Anthropic",
            Self::OpenAi => "OpenAi",
        }
    }

    pub fn title(self) -> &'static str {
        match self {
            Self::Anthropic => "Anthropic",
            Self::OpenAi => "OpenAI-compatible",
        }
    }

    pub fn default_base_url(self) -> &'static str {
        match self {
            Self::Anthropic => "https://api.anthropic.com",
            Self::OpenAi => "https://api.openai.com/v1",
        }
    }

    pub fn default_model(self) -> &'static str {
        match self {
            Self::Anthropic => "claude-opus-5-5",
            Self::OpenAi => "gpt-5",
        }
    }

    /// Read when no key is saved in the keychain, for users who already export one.
    pub fn key_variable(self) -> &'static str {
        match self {
            Self::Anthropic => "ANTHROPIC_API_KEY",
            Self::OpenAi => "OPENAI_API_KEY",
        }
    }

    /// The keychain entry holding this protocol's API key.
    pub fn secret_key(self) -> String {
        format!("agent-provider:{}", self.name().to_lowercase())
    }

    pub fn of(name: &str) -> Self {
        if name == "OpenAi" {
            Self::OpenAi
        } else {
            Self::Anthropic
        }
    }
}

/// A provider as settings describe it, with blanks already resolved to the protocol's defaults.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProviderConfig {
    pub protocol: ProviderProtocol,
    pub base_url: String,
    pub model: String,
}

impl ProviderConfig {
    pub fn of(protocol: ProviderProtocol, base_url: &str, model: &str) -> Self {
        let base = base_url.trim();
        let base = if base.is_empty() {
            protocol.default_base_url()
        } else {
            base
        };
        let model = model.trim();
        Self {
            protocol,
            base_url: base.trim_end_matches('/').to_owned(),
            model: if model.is_empty() {
                protocol.default_model()
            } else {
                model
            }
            .to_owned(),
        }
    }
}

/// The readable part of an API error body, `{"error": {"message": …}}` in both protocols.
pub fn error_message(body: &str) -> Option<String> {
    serde_json::from_str::<Value>(body)
        .ok()?
        .get("error")?
        .get("message")?
        .as_str()
        .filter(|m| !m.trim().is_empty())
        .map(str::to_owned)
}

/// Turns a provider's server-sent events back into events; fed the payload of each `data:` line.
pub struct StreamDecoder {
    protocol: ProviderProtocol,
    title: String,
    text: String,
    /// The provider's safety layer stopped the answer; said so rather than "returned nothing".
    refused: bool,
}

impl StreamDecoder {
    pub fn new(protocol: ProviderProtocol, title: &str) -> Self {
        Self {
            protocol,
            title: title.to_owned(),
            text: String::new(),
            refused: false,
        }
    }

    fn delta(&mut self, chunk: Option<&str>) -> Vec<AgentEvent> {
        match chunk.filter(|c| !c.is_empty()) {
            Some(c) => {
                self.text.push_str(c);
                vec![AgentEvent::TextDelta(c.to_owned())]
            }
            None => Vec::new(),
        }
    }

    fn reasoning(chunk: Option<&str>) -> Vec<AgentEvent> {
        chunk
            .filter(|c| !c.is_empty())
            .map(|c| vec![AgentEvent::ReasoningDelta(c.to_owned())])
            .unwrap_or_default()
    }

    pub fn data(&mut self, payload: &str) -> Result<Vec<AgentEvent>, AgentError> {
        if self.protocol == ProviderProtocol::OpenAi && payload == "[DONE]" {
            return Ok(Vec::new());
        }
        let Ok(json) = serde_json::from_str::<Value>(payload) else {
            return Ok(Vec::new());
        };
        let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).map(str::to_owned);
        Ok(match self.protocol {
            // Messages API events: `content_block_delta` carries the text, `message_delta` the stop reason, `error` a
            // failure mid-stream.
            ProviderProtocol::Anthropic => match s(&json, "type").as_deref() {
                Some("content_block_delta") => {
                    let delta = json.get("delta").cloned().unwrap_or(Value::Null);
                    match s(&delta, "type").as_deref() {
                        Some("text_delta") => self.delta(s(&delta, "text").as_deref()),
                        Some("thinking_delta") => Self::reasoning(s(&delta, "thinking").as_deref()),
                        _ => Vec::new(),
                    }
                }
                Some("message_delta") => {
                    if json
                        .get("delta")
                        .and_then(|d| d.get("stop_reason"))
                        .and_then(Value::as_str)
                        == Some("refusal")
                    {
                        self.refused = true;
                    }
                    Vec::new()
                }
                Some("error") => {
                    return Err(AgentError(
                        json.get("error")
                            .and_then(|e| s(e, "message"))
                            .unwrap_or_else(|| format!("{} failed.", self.title)),
                    ));
                }
                _ => Vec::new(),
            },
            // Chat Completions chunks: text in `choices[0].delta.content`; reasoning in `reasoning_content` or
            // `reasoning`.
            ProviderProtocol::OpenAi => {
                if let Some(e) = json.get("error").filter(|e| e.is_object()) {
                    return Err(AgentError(
                        s(e, "message").unwrap_or_else(|| format!("{} failed.", self.title)),
                    ));
                }
                let Some(choice) = json
                    .get("choices")
                    .and_then(|c| c.get(0))
                    .filter(|c| c.is_object())
                else {
                    return Ok(Vec::new());
                };
                if s(choice, "finish_reason").as_deref() == Some("content_filter") {
                    self.refused = true;
                }
                let delta = choice.get("delta").cloned().unwrap_or(Value::Null);
                let mut out = Self::reasoning(
                    s(&delta, "reasoning_content")
                        .or_else(|| s(&delta, "reasoning"))
                        .as_deref(),
                );
                out.extend(self.delta(s(&delta, "content").as_deref()));
                out
            }
        })
    }

    /// The whole answer once the stream has ended, or the reason there is none.
    pub fn finish(&self) -> Result<String, AgentError> {
        let answer = self.text.trim();
        if !answer.is_empty() {
            Ok(answer.to_owned())
        } else if self.refused {
            Err(AgentError(format!("{} declined to answer.", self.title)))
        } else {
            Err(AgentError(format!("{} returned nothing.", self.title)))
        }
    }
}

type ConfigFn = Arc<dyn Fn() -> ProviderConfig + Send + Sync>;
type KeyFn = Arc<dyn Fn(ProviderProtocol) -> Option<String> + Send + Sync>;

/// The provider configured in settings, as one agent. Discovery only reads settings: nothing is sent to the
/// provider, and the API key is looked up when a turn starts.
pub struct ProviderAgentSource {
    config: ConfigFn,
    api_key: KeyFn,
}

impl ProviderAgentSource {
    pub fn new(config: ConfigFn, api_key: KeyFn) -> Self {
        Self { config, api_key }
    }
}

#[async_trait]
impl AgentSource for ProviderAgentSource {
    async fn discover(&self, _rescan: bool) -> Vec<Arc<dyn AgentAdapter>> {
        vec![Arc::new(ProviderAgentAdapter::new(
            (self.config)(),
            self.api_key.clone(),
        ))]
    }
}

/// Streams one turn from an HTTP provider. Stateless like every adapter: the whole conversation goes with each
/// request.
pub struct ProviderAgentAdapter {
    config: ProviderConfig,
    api_key: KeyFn,
    descriptor: AgentDescriptor,
}

impl ProviderAgentAdapter {
    pub fn new(config: ProviderConfig, api_key: KeyFn) -> Self {
        let descriptor = AgentDescriptor {
            id: format!("provider:{}", config.protocol.name().to_lowercase()),
            name: config.model.clone(),
            transport: AgentTransport::Http,
            detail: Some(config.base_url.clone()),
        };
        Self {
            config,
            api_key,
            descriptor,
        }
    }

    /// The conversation as alternating turns. A failed reply is left out of the history, which can leave two user
    /// turns in a row; they are joined. The reminder goes on the last turn.
    fn messages(request: &AgentRequest) -> Vec<Value> {
        let mut merged: Vec<(Role, String)> = Vec::new();
        for message in request
            .messages
            .iter()
            .filter(|m| !m.text.trim().is_empty())
        {
            match merged.last_mut() {
                Some((role, text)) if *role == message.role => {
                    text.push_str("\n\n");
                    text.push_str(message.text.trim());
                }
                _ => merged.push((message.role, message.text.trim().to_owned())),
            }
        }
        if let Some(reminder) = request.reminder.as_deref().filter(|r| !r.trim().is_empty())
            && let Some((Role::User, text)) = merged.last_mut()
        {
            *text = format!(
                "<system-reminder>\n{}\n</system-reminder>\n\n{text}",
                reminder.trim()
            );
        }
        merged
            .into_iter()
            .map(|(role, text)| json!({"role": if role == Role::User { "user" } else { "assistant" }, "content": text}))
            .collect()
    }

    fn body(&self, request: &AgentRequest) -> (String, Value) {
        let system = request
            .system
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .map(str::trim);
        match self.config.protocol {
            ProviderProtocol::Anthropic => {
                let mut body =
                    json!({"model": self.config.model, "max_tokens": MAX_TOKENS, "stream": true});
                if let Some(s) = system {
                    body["system"] = json!(s);
                }
                body["messages"] = Value::Array(Self::messages(request));
                (format!("{}/v1/messages", self.config.base_url), body)
            }
            ProviderProtocol::OpenAi => {
                let mut messages: Vec<Value> = system
                    .map(|s| json!({"role": "system", "content": s}))
                    .into_iter()
                    .collect();
                messages.extend(Self::messages(request));
                (
                    format!("{}/chat/completions", self.config.base_url),
                    json!({"model": self.config.model, "stream": true, "messages": messages}),
                )
            }
        }
    }
}

#[async_trait]
impl AgentAdapter for ProviderAgentAdapter {
    fn descriptor(&self) -> &AgentDescriptor {
        &self.descriptor
    }

    fn send(&self, request: AgentRequest) -> EventStream {
        let config = self.config.clone();
        let api_key = self.api_key.clone();
        let (url, body) = self.body(&request);
        EventStream::spawn(move |events| async move {
            events.send(AgentEvent::Thinking).await;
            let title = config.protocol.title();
            let key = api_key(config.protocol).filter(|k| !k.trim().is_empty());
            if key.is_none() && config.protocol == ProviderProtocol::Anthropic {
                return Err(AgentError(format!(
                    "No API key for {title}. Add one in Settings › Agent, or set {}.",
                    config.protocol.key_variable()
                )));
            }
            let _ = rustls::crypto::ring::default_provider().install_default();
            let client = reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(15))
                .build()
                .map_err(|e| AgentError(e.to_string()))?;
            let mut builder = client
                .post(&url)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .timeout(Duration::from_secs(300))
                .body(body.to_string());
            match config.protocol {
                ProviderProtocol::Anthropic => {
                    builder = builder.header("anthropic-version", ANTHROPIC_VERSION);
                    if let Some(k) = &key {
                        builder = builder.header("x-api-key", k);
                    }
                }
                // Local servers usually take no key; they get no Authorization header rather than an empty one.
                ProviderProtocol::OpenAi => {
                    if let Some(k) = &key {
                        builder = builder.header("authorization", format!("Bearer {k}"));
                    }
                }
            }
            let unreachable =
                |_| AgentError(format!("Couldn't reach {title} at {}.", config.base_url));
            let mut response = builder.send().await.map_err(unreachable)?;
            let status = response.status().as_u16();
            if !(200..300).contains(&status) {
                let bytes = response.bytes().await.unwrap_or_default();
                let text =
                    String::from_utf8_lossy(&bytes[..bytes.len().min(ERROR_CAP)]).into_owned();
                return Err(AgentError(format!(
                    "{title}: {}",
                    error_message(&text).unwrap_or_else(|| format!("HTTP {status}"))
                )));
            }
            let mut decoder = StreamDecoder::new(config.protocol, title);
            let mut pending: Vec<u8> = Vec::new();
            loop {
                let chunk = response.chunk().await.map_err(unreachable)?;
                let done = chunk.is_none();
                if let Some(c) = chunk {
                    pending.extend_from_slice(&c);
                } else if !pending.is_empty() {
                    pending.push(b'\n');
                }
                while let Some(i) = pending.iter().position(|b| *b == b'\n') {
                    let line: Vec<u8> = pending.drain(..=i).collect();
                    let line = String::from_utf8_lossy(&line);
                    let line = line.trim_end_matches(['\n', '\r']);
                    if let Some(payload) = line.strip_prefix("data:") {
                        for event in decoder.data(payload.trim())? {
                            if !events.send(event).await {
                                return Ok(());
                            }
                        }
                    }
                }
                if done {
                    break;
                }
            }
            events.send(AgentEvent::Completed(decoder.finish()?)).await;
            Ok(())
        })
    }
}

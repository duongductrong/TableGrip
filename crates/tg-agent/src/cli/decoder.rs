//! What a preset prints on stdout, and how to turn it back into events (`agent/cli/CliOutputDecoder.kt`). Plain text
//! streams by itself; several CLIs only stream when asked for NDJSON, which also says how the run ended.

use crate::{ActivityState, AgentError, AgentEvent};
use serde_json::{Map, Value};
use std::collections::HashMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CliOutputFormat {
    PlainText,
    /// Claude Code's `stream-json`, which Cursor Agent and Grok also speak.
    ClaudeStreamJson,
    GeminiStreamJson,
    /// `codex exec --json`: whole messages, no token deltas.
    CodexJson,
    AntigravityStreamJson,
    /// `opencode run --format json`: each text part whole once it is finished.
    OpenCodeJson,
}

impl CliOutputFormat {
    pub fn decoder(self, title: &str) -> Box<dyn CliOutputDecoder> {
        let base = || Ndjson::new(title);
        match self {
            Self::PlainText => Box::new(PlainText::default()),
            Self::ClaudeStreamJson => Box::new(Claude {
                base: base(),
                result: None,
                error: None,
                whole: String::new(),
            }),
            Self::GeminiStreamJson => Box::new(Gemini {
                base: base(),
                error: None,
            }),
            Self::CodexJson => Box::new(Codex {
                base: base(),
                error: None,
                reasoned: false,
            }),
            Self::AntigravityStreamJson => Box::new(Antigravity {
                base: base(),
                status: None,
                response: String::new(),
                error: None,
            }),
            Self::OpenCodeJson => Box::new(OpenCode {
                base: base(),
                error: None,
                reasoned: false,
            }),
        }
    }
}

/// Why a run has no answer: the CLI failed before streaming anything (the story is in stderr, which the adapter boils
/// down to one line), or it said why itself.
#[derive(Debug, PartialEq, Eq)]
pub enum CliFailure {
    Stderr,
    Agent(AgentError),
}

/// Fed stdout as it arrives; owned by one run.
pub trait CliOutputDecoder: Send {
    /// The events completed by `chunk`. Chunks are pipe reads, not lines.
    fn consume(&mut self, chunk: &str) -> Vec<AgentEvent>;

    /// The answer once the process has exited, or the reason there is none.
    fn finish(&mut self, exit_status: i32, stderr: &str) -> Result<String, CliFailure>;
}

#[derive(Default)]
struct PlainText {
    text: String,
}

impl CliOutputDecoder for PlainText {
    fn consume(&mut self, chunk: &str) -> Vec<AgentEvent> {
        self.text.push_str(chunk);
        vec![AgentEvent::TextDelta(chunk.to_owned())]
    }

    // Several agents exit non-zero on a warning they also print: only an empty run is a failure, and the adapter says why.
    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        Ok(self.text.trim().to_owned())
    }
}

fn agent_error(message: impl Into<String>) -> CliFailure {
    CliFailure::Agent(AgentError(message.into()))
}

type Obj = Map<String, Value>;

fn string(o: &Obj, key: &str) -> Option<String> {
    match o.get(key)? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn obj<'a>(o: &'a Obj, key: &str) -> Option<&'a Obj> {
    o.get(key)?.as_object()
}

fn array<'a>(o: &'a Obj, key: &str) -> Vec<&'a Obj> {
    o.get(key)
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_object).collect())
        .unwrap_or_default()
}

fn boolean(o: &Obj, key: &str) -> Option<bool> {
    o.get(key)?.as_bool()
}

/// An activity's label fits one row of the chat.
fn one_line(text: &str) -> String {
    let first = text.lines().next().unwrap_or("").trim();
    if first.chars().count() > 80 {
        format!("{}…", first.chars().take(79).collect::<String>())
    } else {
        first.to_owned()
    }
}

/// What a tool call was about, from the argument that usually says it: a path, a command, a query.
fn tool_label(tool: &str, input: Option<&Obj>) -> String {
    let detail = [
        "file_path",
        "path",
        "command",
        "pattern",
        "query",
        "url",
        "description",
    ]
    .iter()
    .find_map(|k| input.and_then(|i| string(i, k)));
    match detail.filter(|d| !d.trim().is_empty()) {
        Some(d) => format!("{tool} · {d}"),
        None => tool.to_owned(),
    }
}

/// Splits chunks into lines and tolerates the stray non-JSON line (update notices, warnings) these tools print.
struct Ndjson {
    title: String,
    partial: String,
    streamed: String,
    /// Anything parsed at all: a stream of garbage fails differently from one that ran and said nothing.
    parsed_anything: bool,
    /// Labels by id, so an update that only says how an activity ended still names it.
    labels: HashMap<String, String>,
}

impl Ndjson {
    fn new(title: &str) -> Self {
        Self {
            title: title.to_owned(),
            partial: String::new(),
            streamed: String::new(),
            parsed_anything: false,
            labels: HashMap::new(),
        }
    }

    /// The complete JSON lines in `chunk`.
    fn lines(&mut self, chunk: &str) -> Vec<Obj> {
        self.partial.push_str(chunk);
        let mut out = Vec::new();
        while let Some(i) = self.partial.find('\n') {
            let line: String = self.partial.drain(..=i).collect();
            out.extend(self.parse(&line));
        }
        out
    }

    /// Flushes the last line, which need not end in a newline.
    fn flush(&mut self) -> Vec<Obj> {
        let rest = std::mem::take(&mut self.partial);
        self.parse(&rest).into_iter().collect()
    }

    fn parse(&mut self, raw: &str) -> Option<Obj> {
        let line = raw.trim();
        if !line.starts_with('{') {
            return None;
        }
        let Ok(Value::Object(o)) = serde_json::from_str::<Value>(line) else {
            return None;
        };
        self.parsed_anything = true;
        Some(o)
    }

    fn delta(&mut self, text: &str) -> Vec<AgentEvent> {
        if text.is_empty() {
            return Vec::new();
        }
        self.streamed.push_str(text);
        vec![AgentEvent::TextDelta(text.to_owned())]
    }

    fn reasoning(text: &str) -> Vec<AgentEvent> {
        if text.is_empty() {
            Vec::new()
        } else {
            vec![AgentEvent::ReasoningDelta(text.to_owned())]
        }
    }

    fn activity(&mut self, id: &str, label: Option<&str>, state: ActivityState) -> Vec<AgentEvent> {
        let name = match label.filter(|l| !l.trim().is_empty()).map(one_line) {
            Some(n) => {
                self.labels.insert(id.to_owned(), n.clone());
                n
            }
            None => match self.labels.get(id) {
                Some(n) => n.clone(),
                None => return Vec::new(),
            },
        };
        vec![AgentEvent::Activity {
            id: id.to_owned(),
            label: name,
            state,
        }]
    }

    /// Nothing parsed means the CLI failed before it streamed anything; that story is in stderr.
    fn no_outcome(&self) -> Result<String, CliFailure> {
        let partial = self.streamed.trim();
        if !partial.is_empty() {
            return Ok(partial.to_owned());
        }
        if !self.parsed_anything {
            return Err(CliFailure::Stderr);
        }
        Err(agent_error(format!("{} returned nothing.", self.title)))
    }
}

macro_rules! ndjson_consume {
    () => {
        fn consume(&mut self, chunk: &str) -> Vec<AgentEvent> {
            let lines = self.base.lines(chunk);
            lines.iter().flat_map(|l| self.event(l)).collect()
        }
    };
}

/// `--output-format stream-json`. Claude Code sends token deltas as `stream_event`s and whole `assistant` messages
/// besides. Cursor Agent sends its deltas as `assistant` messages carrying `timestamp_ms`, then the whole message
/// again without one. Both end with a `result`.
struct Claude {
    base: Ndjson,
    result: Option<String>,
    error: Option<String>,
    whole: String,
}

fn text_of(message: &Obj) -> String {
    array(message, "content")
        .iter()
        .filter(|c| string(c, "type").as_deref() == Some("text"))
        .filter_map(|c| string(c, "text"))
        .collect()
}

impl Claude {
    fn event(&mut self, json: &Obj) -> Vec<AgentEvent> {
        match string(json, "type").as_deref() {
            Some("stream_event") => {
                let event = obj(json, "event");
                let delta = event.and_then(|e| obj(e, "delta"));
                if event.and_then(|e| string(e, "type")).as_deref() != Some("content_block_delta") {
                    return Vec::new();
                }
                match delta.and_then(|d| string(d, "type")).as_deref() {
                    Some("text_delta") => self
                        .base
                        .delta(&delta.and_then(|d| string(d, "text")).unwrap_or_default()),
                    Some("thinking_delta") => Ndjson::reasoning(
                        &delta
                            .and_then(|d| string(d, "thinking"))
                            .unwrap_or_default(),
                    ),
                    _ => Vec::new(),
                }
            }
            Some("assistant") => {
                let message = obj(json, "message");
                let text = message.map(text_of).unwrap_or_default();
                let mut out = Vec::new();
                for tool in message
                    .map(|m| array(m, "content"))
                    .unwrap_or_default()
                    .iter()
                    .filter(|c| string(c, "type").as_deref() == Some("tool_use"))
                {
                    let label = tool_label(
                        &string(tool, "name").unwrap_or_default(),
                        obj(tool, "input"),
                    );
                    out.extend(self.base.activity(
                        &string(tool, "id").unwrap_or_default(),
                        Some(&label),
                        ActivityState::Running,
                    ));
                }
                if json.contains_key("timestamp_ms") {
                    out.extend(self.base.delta(&text));
                } else {
                    self.whole.push_str(&text);
                }
                out
            }
            // Tool results come back as the user's turn.
            Some("user") => {
                let mut out = Vec::new();
                let content = obj(json, "message")
                    .map(|m| array(m, "content"))
                    .unwrap_or_default();
                for result in content
                    .iter()
                    .filter(|c| string(c, "type").as_deref() == Some("tool_result"))
                {
                    let state = if boolean(result, "is_error") == Some(true) {
                        ActivityState::Failed
                    } else {
                        ActivityState::Done
                    };
                    out.extend(self.base.activity(
                        &string(result, "tool_use_id").unwrap_or_default(),
                        None,
                        state,
                    ));
                }
                out
            }
            // Cursor Agent's own events: reasoning deltas, and tool calls named by their key, e.g. `readToolCall`.
            Some("thinking") => {
                if string(json, "subtype").as_deref() == Some("delta") {
                    Ndjson::reasoning(&string(json, "text").unwrap_or_default())
                } else {
                    Vec::new()
                }
            }
            Some("tool_call") => {
                let call = obj(json, "tool_call").and_then(|t| t.iter().next());
                let body = call.and_then(|(_, v)| v.as_object());
                let name = call.map(|(k, _)| k.strip_suffix("ToolCall").unwrap_or(k).to_owned());
                let result = body.and_then(|b| obj(b, "result"));
                let state = if string(json, "subtype").as_deref() != Some("completed") {
                    ActivityState::Running
                } else if result
                    .is_some_and(|r| r.contains_key("error") || r.contains_key("rejected"))
                {
                    ActivityState::Failed
                } else {
                    ActivityState::Done
                };
                let label = name.map(|n| tool_label(&n, body.and_then(|b| obj(b, "args"))));
                self.base.activity(
                    &string(json, "call_id").unwrap_or_default(),
                    label.as_deref(),
                    state,
                )
            }
            Some("result") => {
                let text = string(json, "result").unwrap_or_default();
                if boolean(json, "is_error") == Some(true)
                    || string(json, "subtype").is_some_and(|s| s.starts_with("error"))
                {
                    self.error = Some(if text.trim().is_empty() {
                        string(json, "subtype").unwrap_or_default()
                    } else {
                        text
                    });
                } else {
                    self.result = Some(text);
                }
                Vec::new()
            }
            _ => Vec::new(),
        }
    }
}

impl CliOutputDecoder for Claude {
    ndjson_consume!();

    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        for line in self.base.flush() {
            self.event(&line);
        }
        if let Some(e) = &self.error {
            return Err(agent_error(e.clone()));
        }
        let answer = match &self.result {
            Some(r) => r.clone(),
            None if !self.base.streamed.trim().is_empty() => self.base.streamed.clone(),
            None => self.whole.clone(),
        };
        let answer = answer.trim();
        if answer.is_empty() {
            self.base.no_outcome()
        } else {
            Ok(answer.to_owned())
        }
    }
}

/// `gemini -o stream-json`: `message` events with `delta: true` while it streams, then a `result` with a status.
struct Gemini {
    base: Ndjson,
    error: Option<String>,
}

impl Gemini {
    fn event(&mut self, json: &Obj) -> Vec<AgentEvent> {
        match string(json, "type").as_deref() {
            Some("message") if string(json, "role").as_deref() == Some("assistant") => self
                .base
                .delta(&string(json, "content").unwrap_or_default()),
            Some("tool_use") => {
                let label = tool_label(
                    &string(json, "tool_name").unwrap_or_default(),
                    obj(json, "parameters"),
                );
                self.base.activity(
                    &string(json, "tool_id").unwrap_or_default(),
                    Some(&label),
                    ActivityState::Running,
                )
            }
            Some("tool_result") => {
                let state = if string(json, "status").as_deref() == Some("error") {
                    ActivityState::Failed
                } else {
                    ActivityState::Done
                };
                self.base
                    .activity(&string(json, "tool_id").unwrap_or_default(), None, state)
            }
            Some("error") => {
                if let Some(m) = string(json, "message") {
                    self.error = Some(m);
                }
                Vec::new()
            }
            Some("result") => {
                if string(json, "status").as_deref() == Some("error") {
                    self.error = Some(
                        obj(json, "error")
                            .and_then(|e| string(e, "message"))
                            .or_else(|| string(json, "error"))
                            .unwrap_or_else(|| format!("{} failed.", self.base.title)),
                    );
                }
                Vec::new()
            }
            _ => Vec::new(),
        }
    }
}

impl CliOutputDecoder for Gemini {
    ndjson_consume!();

    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        for line in self.base.flush() {
            self.event(&line);
        }
        if self.base.streamed.trim().is_empty()
            && let Some(e) = &self.error
        {
            return Err(agent_error(e.clone()));
        }
        self.base.no_outcome()
    }
}

/// `codex exec --json`. The answer arrives as whole `agent_message` items. Items of type `error` are warnings and the
/// turn carries on; `turn.failed` is the real failure.
struct Codex {
    base: Ndjson,
    error: Option<String>,
    reasoned: bool,
}

impl Codex {
    fn event(&mut self, json: &Obj) -> Vec<AgentEvent> {
        match string(json, "type").as_deref() {
            Some("item.completed") => {
                let item = obj(json, "item");
                match item.and_then(|i| string(i, "type")).as_deref() {
                    Some("agent_message") => {
                        let text = item.and_then(|i| string(i, "text")).unwrap_or_default();
                        let text = if self.base.streamed.is_empty() {
                            text
                        } else {
                            format!("\n\n{text}")
                        };
                        self.base.delta(&text)
                    }
                    Some("reasoning") => {
                        let text = item
                            .and_then(|i| string(i, "text"))
                            .unwrap_or_default()
                            .trim()
                            .to_owned();
                        let out = Ndjson::reasoning(&if self.reasoned {
                            format!("\n\n{text}")
                        } else {
                            text.clone()
                        });
                        if !text.is_empty() {
                            self.reasoned = true;
                        }
                        out
                    }
                    _ => self.work(item, true),
                }
            }
            Some("item.started" | "item.updated") => self.work(obj(json, "item"), false),
            Some("turn.failed") => {
                self.error = Some(
                    obj(json, "error")
                        .and_then(|e| string(e, "message"))
                        .unwrap_or_else(|| format!("{} failed.", self.base.title)),
                );
                Vec::new()
            }
            Some("error") => {
                if self.error.is_none() {
                    self.error = string(json, "message");
                }
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// Commands, tool calls, searches and edits, as activities.
    fn work(&mut self, item: Option<&Obj>, done: bool) -> Vec<AgentEvent> {
        let Some(item) = item else { return Vec::new() };
        let label = match string(item, "type").as_deref() {
            Some("command_execution") => string(item, "command").map(|c| format!("Ran {c}")),
            Some("mcp_tool_call") => {
                let parts: Vec<String> = [string(item, "server"), string(item, "tool")]
                    .into_iter()
                    .flatten()
                    .collect();
                (!parts.is_empty()).then(|| parts.join("."))
            }
            Some("web_search") => string(item, "query").map(|q| format!("Searched {q}")),
            Some("file_change") => {
                let paths: Vec<String> = array(item, "changes")
                    .iter()
                    .filter_map(|c| string(c, "path"))
                    .collect();
                (!paths.is_empty()).then(|| format!("Edited {}", paths.join(", ")))
            }
            _ => return Vec::new(),
        };
        let status = string(item, "status");
        let exit = item
            .get("exit_code")
            .and_then(|v| v.as_i64().or_else(|| v.as_str()?.parse().ok()));
        let state = if matches!(status.as_deref(), Some("failed" | "declined"))
            || exit.is_some_and(|e| e != 0)
        {
            ActivityState::Failed
        } else if done || status.as_deref() == Some("completed") {
            ActivityState::Done
        } else {
            ActivityState::Running
        };
        self.base.activity(
            &string(item, "id").unwrap_or_default(),
            label.as_deref(),
            state,
        )
    }
}

/// Codex passes the API's error body through as a string of JSON; its `message` is the readable part.
fn unwrap_api_error(message: &str) -> String {
    serde_json::from_str::<Value>(message)
        .ok()
        .and_then(|v| v.get("error")?.get("message")?.as_str().map(str::to_owned))
        .unwrap_or_else(|| message.to_owned())
}

impl CliOutputDecoder for Codex {
    ndjson_consume!();

    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        for line in self.base.flush() {
            self.event(&line);
        }
        if self.base.streamed.trim().is_empty()
            && let Some(e) = &self.error
        {
            return Err(agent_error(unwrap_api_error(e)));
        }
        self.base.no_outcome()
    }
}

/// Antigravity's `stream-json`: `step_update` events carrying `text_delta`, then a `result` with its own status.
struct Antigravity {
    base: Ndjson,
    status: Option<String>,
    response: String,
    error: Option<String>,
}

impl Antigravity {
    fn event(&mut self, json: &Obj) -> Vec<AgentEvent> {
        match string(json, "event").as_deref() {
            Some("step_update") => self.step(obj(json, "step_update")),
            Some("result") => {
                let result = obj(json, "result");
                self.status = Some(
                    result
                        .and_then(|r| string(r, "status"))
                        .unwrap_or_else(|| "INVALID".into()),
                );
                self.response = result
                    .and_then(|r| string(r, "response"))
                    .unwrap_or_default();
                self.error = result
                    .and_then(|r| string(r, "error"))
                    .filter(|e| !e.trim().is_empty());
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// The answer streams in `agent_response` steps; any other step but the user's own is work worth showing.
    fn step(&mut self, update: Option<&Obj>) -> Vec<AgentEvent> {
        let Some(update) = update else {
            return Vec::new();
        };
        let kind = string(update, "step_type").unwrap_or_default();
        let text = string(update, "text_delta").unwrap_or_default();
        if kind.contains("thinking") {
            return Ndjson::reasoning(&text);
        }
        if kind.is_empty() || kind == "agent_response" || kind == "user_input" {
            return self.base.delta(&text);
        }
        let state = match string(update, "state").as_deref() {
            Some("DONE") => ActivityState::Done,
            Some("ERROR" | "FAILED" | "CANCELED" | "CANCELLED") => ActivityState::Failed,
            _ => ActivityState::Running,
        };
        let spaced = kind.replace('_', " ");
        let mut chars = spaced.chars();
        let label: String = chars
            .next()
            .map(|f| f.to_uppercase().chain(chars).collect())
            .unwrap_or_default();
        let index = string(update, "step_index").unwrap_or_else(|| "null".into());
        let mut out = self
            .base
            .activity(&format!("step-{index}"), Some(&label), state);
        out.extend(self.base.delta(&text));
        out
    }
}

impl CliOutputDecoder for Antigravity {
    ndjson_consume!();

    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        for line in self.base.flush() {
            self.event(&line);
        }
        // Antigravity exits 0 with the refusal where the answer should be, so its own status decides.
        let answer = if self.response.is_empty() {
            self.base.streamed.clone()
        } else {
            self.response.clone()
        };
        let answer = answer.trim().to_owned();
        match &self.status {
            None => self.base.no_outcome(),
            Some(s) if s == "SUCCESS" && !answer.is_empty() => Ok(answer),
            _ if !answer.is_empty() && self.error.is_none() => Ok(answer),
            Some(s) => {
                Err(agent_error(self.error.clone().unwrap_or_else(|| {
                    format!("{} ended in state {s}.", self.base.title)
                })))
            }
        }
    }
}

/// `opencode run --format json`: `text` and `reasoning` parts arrive whole once finished, tool calls as `tool_use`
/// parts with their state, and a failure as an `error` event while the process still exits 0.
struct OpenCode {
    base: Ndjson,
    error: Option<String>,
    reasoned: bool,
}

impl OpenCode {
    fn event(&mut self, json: &Obj) -> Vec<AgentEvent> {
        let part = obj(json, "part");
        match string(json, "type").as_deref() {
            Some("text") => {
                let text = part.and_then(|p| string(p, "text")).unwrap_or_default();
                let text = if self.base.streamed.is_empty() || text.is_empty() {
                    text
                } else {
                    format!("\n\n{text}")
                };
                self.base.delta(&text)
            }
            Some("reasoning") => {
                let text = part
                    .and_then(|p| string(p, "text"))
                    .unwrap_or_default()
                    .trim()
                    .to_owned();
                let out = Ndjson::reasoning(&if self.reasoned {
                    format!("\n\n{text}")
                } else {
                    text.clone()
                });
                if !text.is_empty() {
                    self.reasoned = true;
                }
                out
            }
            Some("tool_use") => {
                let state = part.and_then(|p| obj(p, "state"));
                let status = match state.and_then(|s| string(s, "status")).as_deref() {
                    Some("completed") => ActivityState::Done,
                    Some("error") => ActivityState::Failed,
                    _ => ActivityState::Running,
                };
                let tool = part.and_then(|p| string(p, "tool"));
                let label = match state
                    .and_then(|s| string(s, "title"))
                    .filter(|t| !t.trim().is_empty())
                {
                    Some(t) => format!("{} · {t}", tool.clone().unwrap_or_else(|| "null".into())),
                    None => tool_label(
                        &tool.unwrap_or_default(),
                        state.and_then(|s| obj(s, "input")),
                    ),
                };
                let id = part
                    .and_then(|p| string(p, "callID").or_else(|| string(p, "id")))
                    .unwrap_or_default();
                self.base.activity(&id, Some(&label), status)
            }
            Some("error") => {
                let body = obj(json, "error");
                self.error = Some(
                    body.and_then(|b| {
                        string(b, "message")
                            .or_else(|| obj(b, "data").and_then(|d| string(d, "message")))
                    })
                    .unwrap_or_else(|| format!("{} failed.", self.base.title)),
                );
                Vec::new()
            }
            _ => Vec::new(),
        }
    }
}

impl CliOutputDecoder for OpenCode {
    ndjson_consume!();

    fn finish(&mut self, _: i32, _: &str) -> Result<String, CliFailure> {
        for line in self.base.flush() {
            self.event(&line);
        }
        if self.base.streamed.trim().is_empty()
            && let Some(e) = &self.error
        {
            return Err(agent_error(e.clone()));
        }
        self.base.no_outcome()
    }
}

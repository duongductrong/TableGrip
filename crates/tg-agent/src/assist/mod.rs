//! One request to an agent that must come back as data, not chat (`assist/Assist.kt`): a fix for a failed statement,
//! a chart. The agent answers with a JSON object, which the task checks against what TableGrip knows before anything
//! is shown. An assist only proposes: whatever it returns is applied by the user, through the usual checks.

mod chart;
mod digest;
mod sql_fix;

pub use chart::{ChartProposal, ChartSpecTask};
pub use digest::SchemaDigest;
pub use sql_fix::{
    MissingKind, MissingName, SqlErrors, SqlFix, SqlFixTask, SqlNameFixer, review_fix,
};

use crate::{AgentAdapter, AgentEvent, AgentMessage, AgentRequest, Role};
use regex::Regex;
use serde_json::{Map, Value};
use std::path::PathBuf;
use std::sync::LazyLock;
use std::time::{Duration, Instant};

pub type JsonObject = Map<String, Value>;

/// An answer that can't be used, with reasons specific enough for the agent to correct it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssistInvalid(pub Vec<String>);

impl AssistInvalid {
    pub fn one(reason: impl Into<String>) -> Self {
        Self(vec![reason.into()])
    }
}

pub trait AssistTask {
    type Output;

    /// For logs, e.g. `fix-sql`.
    fn name(&self) -> &str;

    /// The task's role and rules.
    fn system(&self) -> String;

    /// The context and the request, ending with the JSON shape expected back.
    fn prompt(&self) -> String;

    /// Reads the agent's answer; the reasons it's invalid go back to the agent once.
    fn parse(&self, json: &JsonObject) -> Result<Self::Output, AssistInvalid>;
}

#[derive(Clone, Debug, PartialEq)]
pub enum AssistResult<O> {
    Ok {
        value: O,
        agent: String,
        elapsed_millis: u128,
    },
    /// The agent answered, twice, with something that didn't parse.
    Invalid(Vec<String>),
    /// The agent couldn't be reached, failed, or took too long.
    Failed(String),
}

const ATTEMPTS: usize = 2;
/// Restated last, where CLI agents weigh it most: they are tools that like to act.
pub const REMINDER: &str = "Don't run tools, commands or queries. Reply with exactly one JSON object in a ```json block, nothing else.";

/// Runs tasks on an agent: one turn, plus one more to correct an answer that didn't parse. Dropping the future stops
/// the agent.
pub struct AssistRunner {
    pub timeout: Duration,
    pub working_directory: Option<PathBuf>,
}

impl Default for AssistRunner {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(180),
            working_directory: None,
        }
    }
}

impl AssistRunner {
    pub async fn run<T: AssistTask>(
        &self,
        agent: &dyn AgentAdapter,
        task: &T,
    ) -> AssistResult<T::Output> {
        let started = Instant::now();
        let mut messages = vec![AgentMessage::new(Role::User, task.prompt())];
        let mut reasons = Vec::new();
        let name = agent.descriptor().name.clone();
        for _ in 0..ATTEMPTS {
            let request = AgentRequest {
                messages: messages.clone(),
                system: Some(task.system()),
                working_directory: self.working_directory.clone(),
                reminder: Some(REMINDER.into()),
                model: None,
            };
            let answer = match tokio::time::timeout(self.timeout, answer(agent, request)).await {
                Err(_) => {
                    return AssistResult::Failed(format!(
                        "{name} didn't answer within {} s.",
                        self.timeout.as_secs()
                    ));
                }
                Ok(Err(e)) => return AssistResult::Failed(e.0),
                Ok(Ok(a)) => a,
            };
            let parsed = match extract(&answer) {
                None => Err(AssistInvalid::one("The answer contained no JSON object.")),
                Some(json) => task.parse(&json),
            };
            match parsed {
                Ok(value) => {
                    return AssistResult::Ok {
                        value,
                        agent: name,
                        elapsed_millis: started.elapsed().as_millis(),
                    };
                }
                Err(AssistInvalid(r)) => reasons = r,
            }
            messages.push(AgentMessage::new(Role::Assistant, answer));
            let list: Vec<String> = reasons.iter().map(|r| format!("- {r}")).collect();
            messages.push(AgentMessage::new(
                Role::User,
                format!("That answer can't be used:\n{}\nReply again with only the corrected JSON object.", list.join("\n")),
            ));
        }
        AssistResult::Invalid(reasons)
    }
}

async fn answer(agent: &dyn AgentAdapter, request: AgentRequest) -> crate::Result<String> {
    let mut stream = agent.send(request);
    let mut streamed = String::new();
    let mut completed = None;
    while let Some(event) = stream.next().await {
        match event? {
            AgentEvent::TextDelta(t) => streamed.push_str(&t),
            AgentEvent::Completed(t) => completed = Some(t),
            _ => {}
        }
    }
    Ok(completed.unwrap_or(streamed))
}

/// The JSON object in an agent's answer, which may come with prose around it: the last fenced JSON object, else the
/// last balanced `{…}` that parses; None if there's none.
pub fn extract(text: &str) -> Option<JsonObject> {
    static FENCE: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r"(?s)```(?:json)?[ \t]*\r?\n(.*?)```").unwrap());
    let fenced: Vec<String> = FENCE.captures_iter(text).map(|c| c[1].to_owned()).collect();
    for candidate in fenced.iter().rev() {
        if let Some(o) = parse(candidate) {
            return Some(o);
        }
    }
    objects(text).iter().rev().find_map(|o| parse(o))
}

fn parse(candidate: &str) -> Option<JsonObject> {
    match serde_json::from_str::<Value>(candidate.trim()).ok()? {
        Value::Object(o) => Some(o),
        _ => None,
    }
}

/// Top-level `{…}` spans, skipping braces inside strings.
fn objects(text: &str) -> Vec<String> {
    let mut spans = Vec::new();
    let mut depth = 0;
    let mut start = 0;
    let mut in_string = false;
    let mut escaped = false;
    for (i, c) in text.char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                in_string = false;
            }
            continue;
        }
        match c {
            '"' if depth > 0 => in_string = true,
            '{' => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            '}' if depth > 0 => {
                depth -= 1;
                if depth == 0 {
                    spans.push(text[start..=i].to_owned());
                }
            }
            _ => {}
        }
    }
    spans
}

/// A string field of an assist answer; blank counts as missing.
pub fn text(json: &JsonObject, key: &str) -> Option<String> {
    json.get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(str::to_owned)
}

/// A required string field, or the reason naming it.
pub fn require_text(json: &JsonObject, key: &str) -> Result<String, AssistInvalid> {
    text(json, key)
        .ok_or_else(|| AssistInvalid::one(format!("\"{key}\" must be a non-empty string.")))
}

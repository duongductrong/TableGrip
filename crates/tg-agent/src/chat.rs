//! Agent chats kept between launches (`agent/ChatStore.kt`): one JSON file each, so a broken file costs one chat and
//! the list is the directory. Files are written the way kotlinx wrote them (compact, defaults left out), so chats
//! saved by the Kotlin build open here and the other way round.

use crate::{ActivityState, Role};
use indexmap::IndexMap;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

fn is_false(b: &bool) -> bool {
    !*b
}

fn is_empty_str(s: &str) -> bool {
    s.is_empty()
}

/// An agent chat as it is kept between launches: what the chat showed, and what the agent was sent.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedChat {
    pub id: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
    /// The agent last answering in it, by descriptor id; only shown.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub messages: Vec<SavedChatMessage>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedChatMessage {
    pub role: Role,
    pub text: String,
    /// A one-line note sent on the user's behalf rather than a message they typed.
    #[serde(default, skip_serializing_if = "is_false")]
    pub note: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default, skip_serializing_if = "is_empty_str")]
    pub reasoning: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thought_seconds: Option<i32>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub activities: Vec<SavedActivity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Mentions by their stand-in character in `text`.
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub mentions: IndexMap<String, SavedMention>,
    /// Answered run requests, by the index of their code block.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub runs: Vec<SavedRun>,
    /// For a note: the index of the message holding the run request it answers, and that request's block.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub origin: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub origin_block: Option<i32>,
}

impl SavedChatMessage {
    pub fn new(role: Role, text: impl Into<String>) -> Self {
        Self {
            role,
            text: text.into(),
            note: false,
            label: None,
            reasoning: String::new(),
            thought_seconds: None,
            activities: Vec::new(),
            error: None,
            mentions: IndexMap::new(),
            runs: Vec::new(),
            origin: None,
            origin_block: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedActivity {
    pub id: String,
    pub label: String,
    pub state: ActivityState,
}

/// A mention as it was sent: what it read as, and what the agent read for it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedMention {
    pub label: String,
    pub context: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum RunState {
    Done,
    Declined,
    Refused,
    NotShared,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedRun {
    pub block: i32,
    pub state: RunState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
}

/// What the chat list shows of a chat, without its messages.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatSummary {
    pub id: String,
    pub title: String,
    pub updated_at: i64,
    #[serde(default)]
    pub agent: Option<String>,
}

/// A chat id that isn't safe as a file name.
#[derive(Debug, PartialEq, Eq)]
pub struct BadChatId(pub String);

impl std::fmt::Display for BadChatId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Bad chat id: {}", self.0)
    }
}

impl std::error::Error for BadChatId {}

/// Agent chats, one JSON file each. A missing or unreadable file is skipped, never an error.
pub struct ChatStore {
    directory: PathBuf,
}

impl ChatStore {
    pub fn new(directory: impl Into<PathBuf>) -> Self {
        Self {
            directory: directory.into(),
        }
    }

    pub fn standard(paths: &tg_core::paths::TableGripPaths) -> Self {
        Self::new(paths.chats())
    }

    /// Most recently updated first.
    pub fn list(&self) -> Vec<ChatSummary> {
        let Ok(entries) = std::fs::read_dir(&self.directory) else {
            return Vec::new();
        };
        let mut out: Vec<ChatSummary> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|x| x == "json"))
            .filter_map(|p| serde_json::from_str(&std::fs::read_to_string(p).ok()?).ok())
            .collect();
        out.sort_by_key(|c| std::cmp::Reverse(c.updated_at));
        out
    }

    pub fn load(&self, id: &str) -> Option<SavedChat> {
        let file = self.file(id).ok()?;
        serde_json::from_str(&std::fs::read_to_string(file).ok()?).ok()
    }

    /// Writes to a temporary file first, so a crash mid-write leaves the previous version intact.
    pub fn save(&self, chat: &SavedChat) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let file = self.file(&chat.id)?;
        std::fs::create_dir_all(&self.directory)?;
        let mut temp = file.as_os_str().to_owned();
        temp.push(".tmp");
        let temp = PathBuf::from(temp);
        std::fs::write(&temp, serde_json::to_string(chat)?)?;
        std::fs::rename(&temp, &file)?;
        Ok(())
    }

    pub fn rename(&self, id: &str, title: &str) {
        if let Some(mut chat) = self.load(id) {
            chat.title = title.to_owned();
            let _ = self.save(&chat);
        }
    }

    pub fn delete(&self, id: &str) {
        if let Ok(file) = self.file(id) {
            let _ = std::fs::remove_file(file);
        }
    }

    /// Ids are generated by the app, but a file name is never taken from one unchecked.
    fn file(&self, id: &str) -> Result<PathBuf, BadChatId> {
        if id.is_empty() || !id.chars().all(|c| c.is_alphanumeric() || c == '-') {
            return Err(BadChatId(id.to_owned()));
        }
        Ok(self.directory.join(format!("{id}.json")))
    }

    pub fn directory(&self) -> &Path {
        &self.directory
    }
}

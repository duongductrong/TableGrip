//! The workspace as the user left it (`session/SessionStore.kt`).

use crate::json::{Baseline, omit_defaults, to_pretty};
use serde::ser::SerializeMap;
use serde::{Deserialize, Serialize, Serializer};
use std::path::PathBuf;

/// An editor tab as it is reopened on the next start.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum SessionTab {
    /// A SQL console: its text even if unsaved, the file it edits, and the caret.
    #[serde(rename = "console", rename_all = "camelCase")]
    Console {
        connection_id: String,
        text: String,
        #[serde(default)]
        file: Option<String>,
        #[serde(default)]
        caret: i32,
        /// The file's content when the console last saved or opened it, to tell whether `text` has unsaved edits.
        #[serde(default)]
        saved_text: Option<String>,
    },
    /// A table viewer: which view was showing, and its filter as a WHERE clause.
    #[serde(rename = "table", rename_all = "camelCase")]
    Table {
        connection_id: String,
        #[serde(default)]
        catalog: Option<String>,
        #[serde(default)]
        schema: Option<String>,
        name: String,
        #[serde(default)]
        structure: bool,
        #[serde(default)]
        filter: Option<String>,
    },
}

impl SessionTab {
    pub fn connection_id(&self) -> &str {
        match self {
            Self::Console { connection_id, .. } | Self::Table { connection_id, .. } => {
                connection_id
            }
        }
    }
}

/// Written as kotlinx writes a sealed class: the `type` first, then the fields that aren't at their defaults.
impl Serialize for SessionTab {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(None)?;
        match self {
            Self::Console {
                connection_id,
                text,
                file,
                caret,
                saved_text,
            } => {
                map.serialize_entry("type", "console")?;
                map.serialize_entry("connectionId", connection_id)?;
                map.serialize_entry("text", text)?;
                if file.is_some() {
                    map.serialize_entry("file", file)?;
                }
                if *caret != 0 {
                    map.serialize_entry("caret", caret)?;
                }
                if saved_text.is_some() {
                    map.serialize_entry("savedText", saved_text)?;
                }
            }
            Self::Table {
                connection_id,
                catalog,
                schema,
                name,
                structure,
                filter,
            } => {
                map.serialize_entry("type", "table")?;
                map.serialize_entry("connectionId", connection_id)?;
                if catalog.is_some() {
                    map.serialize_entry("catalog", catalog)?;
                }
                if schema.is_some() {
                    map.serialize_entry("schema", schema)?;
                }
                map.serialize_entry("name", name)?;
                if *structure {
                    map.serialize_entry("structure", structure)?;
                }
                if filter.is_some() {
                    map.serialize_entry("filter", filter)?;
                }
            }
        }
        map.end()
    }
}

/// The main window's place and size in dp, or just `maximized`.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct WindowBounds {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    #[serde(default)]
    pub maximized: bool,
}

impl Baseline for WindowBounds {
    fn baseline(&self) -> Self {
        Self {
            maximized: false,
            ..self.clone()
        }
    }
}
omit_defaults!(WindowBounds {
    x: "x" = req,
    y: "y" = req,
    width: "width" = req,
    height: "height" = req,
    maximized: "maximized",
});

#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Session {
    pub tabs: Vec<SessionTab>,
    /// Index into `tabs` of the tab in front.
    pub selected: i32,
    /// The left tool window's name, or None when it was hidden.
    pub left_tool: Option<String>,
    pub connection_id: Option<String>,
    pub agent_open: bool,
    /// The agent chat that was open, reopened from the chat history.
    pub agent_chat: Option<String>,
    /// Where the main window was, in dp; None until it has been saved once.
    pub window: Option<WindowBounds>,
    /// Divider positions as fractions of their split: the left tool window's and the agent chat's.
    pub sidebar_split: Option<f32>,
    pub agent_split: Option<f32>,
}

impl Default for Session {
    fn default() -> Self {
        Self {
            tabs: Vec::new(),
            selected: -1,
            left_tool: Some("Explorer".to_string()),
            connection_id: None,
            agent_open: false,
            agent_chat: None,
            window: None,
            sidebar_split: None,
            agent_split: None,
        }
    }
}

impl Baseline for Session {
    fn baseline(&self) -> Self {
        Self::default()
    }
}
omit_defaults!(Session {
    tabs: "tabs",
    selected: "selected",
    left_tool: "leftTool",
    connection_id: "connectionId",
    agent_open: "agentOpen",
    agent_chat: "agentChat",
    window: "window",
    sidebar_split: "sidebarSplit",
    agent_split: "agentSplit",
});

/// Reads and writes [`Session`]s; a missing or unreadable file is an empty session, never an error at startup.
#[derive(Clone, Debug)]
pub struct SessionStore {
    file: PathBuf,
}

impl SessionStore {
    pub fn new(file: impl Into<PathBuf>) -> Self {
        Self { file: file.into() }
    }

    pub fn file(&self) -> &std::path::Path {
        &self.file
    }

    pub fn load(&self) -> Session {
        std::fs::read_to_string(&self.file)
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    /// Writes to a temporary file first, so a crash mid-write leaves the previous session intact.
    pub fn save(&self, session: &Session) -> std::io::Result<()> {
        if let Some(parent) = self.file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut name = self.file.file_name().unwrap_or_default().to_os_string();
        name.push(".tmp");
        let temp = self.file.with_file_name(name);
        std::fs::write(
            &temp,
            to_pretty(session, "    ").map_err(std::io::Error::other)?,
        )?;
        std::fs::rename(&temp, &self.file)
    }
}

//! The root of the UI state (`AppState.kt`): the models of each part, what the shell shows around them, and what the
//! window's commands and the explorer's menus do. Everything here is synchronous; work that needs I/O comes back as
//! [`Effect`]s for the app to run (on tokio) and report back.

use tg_core::connection::ConnectionConfig;
use tg_core::settings::Settings;
use tg_sql::model::{Paradigm, TableRef};

use crate::connections::{ConnectionForm, Connections, FormTab};
use crate::explorer::{Explorer, LoadJob, Node, NodeKind, connection_key, connection_of};
use crate::imports::ImportState;
use crate::registry::Status;
use crate::workspace::{TabSpec, Workspace};

/// The tool window in the left island.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LeftTool {
    Explorer,
    History,
    Queries,
}

impl LeftTool {
    pub const ALL: [LeftTool; 3] = [LeftTool::Explorer, LeftTool::History, LeftTool::Queries];

    /// The name the session stores.
    pub fn name(self) -> &'static str {
        match self {
            Self::Explorer => "Explorer",
            Self::History => "History",
            Self::Queries => "Queries",
        }
    }

    pub fn of(name: &str) -> Option<LeftTool> {
        Self::ALL.into_iter().find(|t| t.name() == name)
    }
}

/// A line under the connection dialog's fields: progress, an error, or what Test Connection learned, with an
/// optional action (switch to the engine the server turned out to be).
#[derive(Clone, Debug, PartialEq)]
pub struct Message {
    pub text: String,
    pub error: bool,
    pub use_type: Option<tg_sql::model::DatabaseType>,
}

impl Message {
    pub fn info(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            error: false,
            use_type: None,
        }
    }

    pub fn error(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            error: true,
            use_type: None,
        }
    }
}

/// The connection dialog: the form, which tab shows, and the line under it.
#[derive(Clone, Debug, PartialEq)]
pub struct ConnectionDialog {
    pub form: ConnectionForm,
    pub editing: bool,
    pub tab: FormTab,
    pub message: Option<Message>,
    pub busy: bool,
}

/// Asks for a group name: one for `ids` to move into, or a new name for the group `renaming`.
#[derive(Clone, Debug, PartialEq)]
pub struct GroupRequest {
    pub ids: Vec<String>,
    pub renaming: Option<String>,
}

/// The dialog in front of the window, if any; one at a time, as the Kotlin app shows them.
#[derive(Clone, Debug)]
pub enum Dialog {
    Connection(Box<ConnectionDialog>),
    DeleteConnection(String),
    Group(GroupRequest),
    Import(ImportState),
    Shortcuts,
}

/// Work the app runs off the UI thread, then reports back to the model.
#[derive(Clone, Debug, PartialEq)]
pub enum Effect {
    /// Load an explorer node's children (connecting first if need be), then call [`Explorer::loaded`].
    Load(LoadJob),
    /// Write these profiles (no secrets changed).
    SaveProfiles(Vec<ConnectionConfig>),
    /// Delete the profile and its secrets.
    DeleteProfile(String),
    /// Close the live connection.
    Disconnect(String),
    /// Change the UI zoom to this percent and save it.
    Zoom(i32),
    /// Put text on the clipboard.
    Copy(String),
    /// Read the table's DDL and copy it.
    CopyDdl {
        connection_id: String,
        table: TableRef,
    },
}

/// What an explorer menu entry does.
#[derive(Clone, Debug, PartialEq)]
pub enum MenuAction {
    NewConsole,
    Refresh,
    Disconnect,
    ToggleFavorite,
    MoveToGroup,
    RemoveFromGroup,
    Edit,
    Delete,
    NewConnection { group: String },
    RenameGroup,
    Ungroup,
    OpenData,
    OpenStructure,
    CopyDdl,
    NewConsoleWithSelect,
    CopyName(String),
    BrowseKeys,
    OpenDocuments,
    NewConnectionPlain,
    ImportConnections,
    CollapseAll,
    DisconnectAll,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MenuEntry {
    pub label: String,
    pub action: MenuAction,
}

fn entry(label: impl Into<String>, action: MenuAction) -> MenuEntry {
    MenuEntry {
        label: label.into(),
        action,
    }
}

/// The application's state.
pub struct AppModel {
    pub settings: Settings,
    pub connections: Connections,
    pub explorer: Explorer,
    pub workspace: Workspace,
    pub left_tool: Option<LeftTool>,
    /// The tool [`Self::toggle_sidebar`] brings back.
    hidden_tool: LeftTool,
    /// Shows the settings screen in place of the tool window and editor tabs.
    pub settings_open: bool,
    pub agent_open: bool,
    /// The agent chat that was open; kept as the session had it until the agent panel is ported (S10).
    pub agent_chat: Option<String>,
    pub dialog: Option<Dialog>,
    pub status: String,
    /// The connection the title bar shows and new consoles use; follows the selected tab.
    pub current_connection_id: Option<String>,
    /// Divider positions, as fractions of their split.
    pub sidebar_split: f32,
    pub agent_split: f32,
}

impl AppModel {
    pub fn new(settings: Settings, profiles: Vec<ConnectionConfig>) -> Self {
        Self {
            settings,
            connections: Connections::new(profiles),
            explorer: Explorer::default(),
            workspace: Workspace::default(),
            left_tool: Some(LeftTool::Explorer),
            hidden_tool: LeftTool::Explorer,
            settings_open: false,
            agent_open: false,
            agent_chat: None,
            dialog: None,
            status: "Ready".to_owned(),
            current_connection_id: None,
            sidebar_split: 0.24,
            agent_split: 0.68,
        }
    }

    pub fn profiles(&self) -> &[ConnectionConfig] {
        &self.connections.profiles
    }

    /// The current connection, or the first one.
    pub fn current_connection(&self) -> Option<&ConnectionConfig> {
        self.current_connection_id
            .as_deref()
            .and_then(|id| self.connections.by_id(id))
            .or_else(|| self.connections.profiles.first())
    }

    /// Selects a tab: its connection becomes current, and the tabs come back if settings were showing.
    pub fn select_tab(&mut self, key: u64) {
        if self.workspace.select(key) {
            self.settings_open = false;
            self.follow_selected();
        }
    }

    fn follow_selected(&mut self) {
        if let Some(tab) = self.workspace.selected() {
            self.current_connection_id = Some(tab.spec.connection_id().to_owned());
        }
    }

    pub fn close_tab(&mut self, key: u64) {
        self.workspace.close(key);
        self.follow_selected();
    }

    /// Makes `id` current: reveals it in the explorer (connecting it) without changing open tabs.
    pub fn pick_connection(&mut self, id: &str) -> Vec<Effect> {
        self.current_connection_id = Some(id.to_owned());
        self.settings_open = false;
        if let Some(key) = self
            .workspace
            .tabs
            .iter()
            .rev()
            .find(|t| t.spec.connection_id() == id)
            .map(|t| t.key)
        {
            self.workspace.select(key);
        }
        self.left_tool = Some(LeftTool::Explorer);
        let profiles = self.connections.profiles.clone();
        self.explorer
            .reveal(id, &profiles)
            .map(Effect::Load)
            .into_iter()
            .collect()
    }

    /// From the settings screen, a tool button goes back to the workspace with that tool shown.
    pub fn toggle_tool(&mut self, tool: LeftTool) {
        self.left_tool = if self.left_tool == Some(tool) && !self.settings_open {
            None
        } else {
            Some(tool)
        };
        self.settings_open = false;
    }

    /// Hides the left tool window, or shows the one last hidden this way.
    pub fn toggle_sidebar(&mut self) {
        match self.left_tool {
            Some(tool) if !self.settings_open => {
                self.hidden_tool = tool;
                self.left_tool = None;
            }
            tool => self.left_tool = Some(tool.unwrap_or(self.hidden_tool)),
        }
        self.settings_open = false;
    }

    /// Opens a console on the current connection.
    pub fn new_console(&mut self) -> bool {
        let Some(config) = self.current_connection().cloned() else {
            return false;
        };
        let key = self.workspace.open_console(&config, "", 0, None);
        self.select_tab(key);
        true
    }

    /// Runs the keymap action `id` at window level (`Main.kt`'s `handleTabSwitch` and `handleWindowShortcut`). None
    /// when it isn't the window's to handle (the focused tab may).
    pub fn window_command(&mut self, id: &str) -> Option<Vec<Effect>> {
        let done = Some(Vec::new());
        match id {
            "NextTab" | "NextTabAlt" => {
                if let Some(key) = self.workspace.select_relative(1) {
                    self.select_tab(key);
                }
                done
            }
            "PreviousTab" | "PreviousTabAlt" => {
                if let Some(key) = self.workspace.select_relative(-1) {
                    self.select_tab(key);
                }
                done
            }
            // Esc and ⌘W leave the settings screen rather than acting on the hidden tabs.
            "CloseTab" if self.settings_open => {
                self.settings_open = false;
                done
            }
            "CloseTab" => {
                let key = self.workspace.selected()?.key;
                self.close_tab(key);
                done
            }
            "NewConsole" => self.new_console().then(Vec::new),
            "Explorer" => {
                self.toggle_tool(LeftTool::Explorer);
                done
            }
            "History" => {
                self.toggle_tool(LeftTool::History);
                done
            }
            "Queries" => {
                self.toggle_tool(LeftTool::Queries);
                done
            }
            "Agent" => {
                self.agent_open = !self.agent_open;
                done
            }
            "ToggleSidebar" => {
                self.toggle_sidebar();
                done
            }
            "Settings" => {
                self.settings_open = true;
                done
            }
            "ShowShortcuts" => {
                self.dialog = Some(Dialog::Shortcuts);
                done
            }
            "ZoomIn" | "ZoomInAlt" => Some(vec![Effect::Zoom(tg_theme::zoom::step(
                self.settings.zoom,
                1,
            ))]),
            "ZoomOut" => Some(vec![Effect::Zoom(tg_theme::zoom::step(
                self.settings.zoom,
                -1,
            ))]),
            "ResetZoom" => Some(vec![Effect::Zoom(100)]),
            _ => None,
        }
    }

    /// Escape at window level: leaves settings, or closes the dialog in front.
    pub fn escape(&mut self) -> bool {
        if self.dialog.take().is_some() {
            return true;
        }
        if self.settings_open {
            self.settings_open = false;
            return true;
        }
        false
    }

    // Connections.

    pub fn open_new_connection(&mut self, group: &str, new_id: &str, user: &str) {
        self.dialog = Some(Dialog::Connection(Box::new(ConnectionDialog {
            form: ConnectionForm::new(None, group, new_id, user),
            editing: false,
            tab: FormTab::General,
            message: None,
            busy: false,
        })));
    }

    pub fn open_edit_connection(&mut self, id: &str, user: &str) {
        if let Some(config) = self.connections.by_id(id) {
            self.dialog = Some(Dialog::Connection(Box::new(ConnectionDialog {
                form: ConnectionForm::new(Some(config), "", id, user),
                editing: true,
                tab: FormTab::General,
                message: None,
                busy: false,
            })));
        }
    }

    pub fn connection_dialog(&mut self) -> Option<&mut ConnectionDialog> {
        match &mut self.dialog {
            Some(Dialog::Connection(d)) => Some(d),
            _ => None,
        }
    }

    /// A profile was saved from the dialog: the saved list replaces ours; an edited profile reconnects on next use.
    pub fn saved_connection(
        &mut self,
        saved: Vec<ConnectionConfig>,
        id: &str,
        edited: bool,
    ) -> Vec<Effect> {
        self.connections.profiles = saved;
        self.dialog = None;
        if edited {
            self.explorer.forget(id);
            vec![Effect::Disconnect(id.to_owned())]
        } else {
            self.status = format!(
                "Added {}",
                self.connections
                    .by_id(id)
                    .map_or("connection", |c| c.name.as_str())
            );
            Vec::new()
        }
    }

    /// The user confirmed deleting `id`: its tabs close, it disconnects, and the profile goes.
    pub fn delete_connection(&mut self, id: &str) -> Vec<Effect> {
        self.dialog = None;
        self.workspace.close_connection(id);
        self.follow_selected();
        self.explorer.forget(id);
        if self.current_connection_id.as_deref() == Some(id) {
            self.current_connection_id = None;
        }
        let name = self
            .connections
            .by_id(id)
            .map(|c| c.name.clone())
            .unwrap_or_default();
        self.connections.profiles.retain(|c| c.id != id);
        self.status = format!("Deleted {name}");
        vec![
            Effect::Disconnect(id.to_owned()),
            Effect::DeleteProfile(id.to_owned()),
        ]
    }

    /// The group dialog's answer.
    pub fn submit_group(&mut self, request: &GroupRequest, name: &str) -> Vec<Effect> {
        self.dialog = None;
        let changed = match &request.renaming {
            Some(from) => self.connections.rename_group(from, name),
            None => {
                let changed = self.connections.move_to_group(&request.ids, name);
                if !changed.is_empty() {
                    let who = if changed.len() == 1 {
                        changed[0].name.clone()
                    } else {
                        format!("{} connections", changed.len())
                    };
                    self.status = if name.trim().is_empty() {
                        format!("{who} moved out of its group")
                    } else {
                        format!("{who} moved to {}", name.trim())
                    };
                }
                changed
            }
        };
        save(changed)
    }

    // The explorer.

    /// What the tree's context menu offers for `node` (`ExplorerPanel.contextMenu`). Entries for screens later
    /// sessions port (ER diagram, backup, import, test data, activity) are left out until they exist.
    pub fn menu(&self, node: &Node, connected: bool) -> Vec<MenuEntry> {
        let config = connection_of(&node.key).and_then(|id| self.connections.by_id(id));
        match (&node.kind, config) {
            (NodeKind::Connection { .. }, Some(config)) => {
                let mut out = vec![
                    entry("New Console", MenuAction::NewConsole),
                    entry("Refresh", MenuAction::Refresh),
                ];
                if connected {
                    out.push(entry("Disconnect", MenuAction::Disconnect));
                }
                out.push(entry(
                    if config.favorite {
                        "Remove from Favorites"
                    } else {
                        "Add to Favorites"
                    },
                    MenuAction::ToggleFavorite,
                ));
                out.push(entry("Move to Group…", MenuAction::MoveToGroup));
                if !config.group.trim().is_empty() && !config.favorite {
                    out.push(entry("Remove from Group", MenuAction::RemoveFromGroup));
                }
                out.push(entry("Edit…", MenuAction::Edit));
                out.push(entry("Delete…", MenuAction::Delete));
                out
            }
            (
                NodeKind::Group {
                    name,
                    favorites: false,
                },
                _,
            ) => vec![
                entry(
                    format!("New Connection in {name}…"),
                    MenuAction::NewConnection {
                        group: name.clone(),
                    },
                ),
                entry("Rename Group…", MenuAction::RenameGroup),
                entry("Ungroup", MenuAction::Ungroup),
            ],
            (NodeKind::Schema { .. }, Some(_)) => vec![entry("Refresh", MenuAction::Refresh)],
            (NodeKind::DocumentDatabase { name, .. }, Some(_)) => vec![
                entry("New Console", MenuAction::NewConsole),
                entry("Copy Name", MenuAction::CopyName(name.clone())),
                entry("Refresh", MenuAction::Refresh),
            ],
            (NodeKind::Collection { info, .. }, Some(_)) => vec![
                entry("Open Documents", MenuAction::OpenDocuments),
                entry("Copy Name", MenuAction::CopyName(info.name.clone())),
            ],
            (NodeKind::Keyspace { .. }, Some(_)) => vec![
                entry("Browse Keys", MenuAction::BrowseKeys),
                entry("New Console", MenuAction::NewConsole),
            ],
            (NodeKind::Table { info, .. }, Some(_)) => vec![
                entry("Open Data", MenuAction::OpenData),
                entry("Open Structure", MenuAction::OpenStructure),
                entry("Copy DDL", MenuAction::CopyDdl),
                entry("New Console with SELECT", MenuAction::NewConsoleWithSelect),
                entry("Copy Name", MenuAction::CopyName(info.table.name.clone())),
                entry("Refresh", MenuAction::Refresh),
            ],
            (NodeKind::Column { column, .. }, _) => vec![entry(
                "Copy Name",
                MenuAction::CopyName(column.name.clone()),
            )],
            _ => Vec::new(),
        }
    }

    /// The menu of the explorer's empty space and header.
    pub fn sidebar_menu(&self, any_open: bool, any_connected: bool) -> Vec<MenuEntry> {
        let mut out = Vec::new();
        if any_open {
            out.push(entry("Collapse All", MenuAction::CollapseAll));
        }
        if any_connected {
            out.push(entry("Disconnect All", MenuAction::DisconnectAll));
        }
        out.push(entry("New Connection…", MenuAction::NewConnectionPlain));
        out.push(entry(
            "Import from Other Tools…",
            MenuAction::ImportConnections,
        ));
        out
    }

    /// Runs a menu entry chosen on `node`. `new_id` and `user` are for a new connection's form.
    pub fn menu_action(
        &mut self,
        node: Option<&Node>,
        action: &MenuAction,
        new_id: &str,
        user: &str,
    ) -> Vec<Effect> {
        let profiles = self.connections.profiles.clone();
        let config = node
            .and_then(|n| connection_of(&n.key))
            .and_then(|id| self.connections.by_id(id))
            .cloned();
        match action {
            MenuAction::NewConsole => {
                if let (Some(config), Some(node)) = (config, node) {
                    let (keyspace, database) = match &node.kind {
                        NodeKind::Keyspace { keyspace, .. } => (keyspace.index, None),
                        NodeKind::DocumentDatabase { name, .. } => (0, Some(name.as_str())),
                        _ => (0, None),
                    };
                    let key = self.workspace.open_console(&config, "", keyspace, database);
                    self.select_tab(key);
                }
                Vec::new()
            }
            MenuAction::Refresh => node
                .and_then(|n| self.explorer.refresh(&n.key, &profiles))
                .map(Effect::Load)
                .into_iter()
                .collect(),
            MenuAction::Disconnect => match config {
                Some(c) => {
                    self.explorer.forget(&c.id);
                    vec![Effect::Disconnect(c.id)]
                }
                None => Vec::new(),
            },
            MenuAction::ToggleFavorite => config
                .map(|c| save(self.connections.toggle_favorite(&c.id)))
                .unwrap_or_default(),
            MenuAction::MoveToGroup => {
                if let Some(c) = config {
                    self.dialog = Some(Dialog::Group(GroupRequest {
                        ids: vec![c.id],
                        renaming: None,
                    }));
                }
                Vec::new()
            }
            MenuAction::RemoveFromGroup => config
                .map(|c| save(self.connections.move_to_group(&[c.id], "")))
                .unwrap_or_default(),
            MenuAction::Edit => {
                if let Some(c) = config {
                    self.open_edit_connection(&c.id, user);
                }
                Vec::new()
            }
            MenuAction::Delete => {
                if let Some(c) = config {
                    self.dialog = Some(Dialog::DeleteConnection(c.id));
                }
                Vec::new()
            }
            MenuAction::NewConnection { group } => {
                self.open_new_connection(group, new_id, user);
                Vec::new()
            }
            MenuAction::NewConnectionPlain => {
                self.open_new_connection("", new_id, user);
                Vec::new()
            }
            MenuAction::RenameGroup => {
                if let Some(NodeKind::Group { name, .. }) = node.map(|n| &n.kind) {
                    self.dialog = Some(Dialog::Group(GroupRequest {
                        ids: Vec::new(),
                        renaming: Some(name.clone()),
                    }));
                }
                Vec::new()
            }
            MenuAction::Ungroup => match node.map(|n| &n.kind) {
                Some(NodeKind::Group { name, .. }) => {
                    self.status = format!("Ungrouped {name}");
                    save(self.connections.ungroup(&name.clone()))
                }
                _ => Vec::new(),
            },
            MenuAction::OpenData | MenuAction::OpenStructure => {
                if let (Some(config), Some(NodeKind::Table { info, .. })) =
                    (config, node.map(|n| &n.kind))
                {
                    let structure = matches!(action, MenuAction::OpenStructure);
                    let key = self
                        .workspace
                        .open_table(&config, &info.table, Some(structure));
                    self.select_tab(key);
                }
                Vec::new()
            }
            MenuAction::CopyDdl => match (config, node.map(|n| &n.kind)) {
                (Some(config), Some(NodeKind::Table { info, .. })) => {
                    vec![Effect::CopyDdl {
                        connection_id: config.id,
                        table: info.table.clone(),
                    }]
                }
                _ => Vec::new(),
            },
            MenuAction::NewConsoleWithSelect => {
                if let (Some(config), Some(NodeKind::Table { info, .. })) =
                    (config, node.map(|n| &n.kind))
                {
                    let sql = select_sql(&config, &info.table);
                    let key = self.workspace.open_console(&config, &sql, 0, None);
                    self.select_tab(key);
                }
                Vec::new()
            }
            MenuAction::CopyName(name) => vec![Effect::Copy(name.clone())],
            MenuAction::BrowseKeys => {
                if let (Some(config), Some(NodeKind::Keyspace { keyspace, .. })) =
                    (config, node.map(|n| &n.kind))
                {
                    let key = self.workspace.open_keys(&config, keyspace.index);
                    self.select_tab(key);
                }
                Vec::new()
            }
            MenuAction::OpenDocuments => {
                if let (Some(config), Some(NodeKind::Collection { database, info, .. })) =
                    (config, node.map(|n| &n.kind))
                {
                    let key = self
                        .workspace
                        .open_collection(&config, database, &info.name);
                    self.select_tab(key);
                }
                Vec::new()
            }
            MenuAction::ImportConnections => {
                self.dialog = Some(Dialog::Import(ImportState::new()));
                Vec::new()
            }
            MenuAction::CollapseAll => {
                // The view closes every node in the tree; the explorer forgets the open set with it.
                let open: Vec<String> = self.explorer.open_keys().cloned().collect();
                for key in open {
                    self.explorer.closed(&key);
                }
                Vec::new()
            }
            MenuAction::DisconnectAll => {
                let ids: Vec<String> = self
                    .connections
                    .profiles
                    .iter()
                    .map(|c| c.id.clone())
                    .collect();
                ids.into_iter()
                    .map(|id| {
                        self.explorer.forget(&id);
                        Effect::Disconnect(id)
                    })
                    .collect()
            }
        }
    }

    /// A double click on a row (`ExplorerPanel`'s activation): opens a table's data, browses a keyspace, opens a
    /// collection's documents.
    pub fn activate(&mut self, node: &Node) -> Vec<Effect> {
        let action = match &node.kind {
            NodeKind::Table { .. } => MenuAction::OpenData,
            NodeKind::Keyspace { .. } => MenuAction::BrowseKeys,
            NodeKind::Collection { .. } => MenuAction::OpenDocuments,
            _ => return Vec::new(),
        };
        self.menu_action(Some(node), &action, "", "")
    }

    /// The status dot's colour class for a connection row.
    pub fn status_of(status: &Status) -> Option<bool> {
        match status {
            Status::Connected => Some(true),
            Status::Failed(_) => Some(false),
            _ => None,
        }
    }

    /// Breadcrumbs for the status bar: the selected tab's connection, namespace and table.
    pub fn breadcrumbs(&self) -> Vec<String> {
        let Some(tab) = self.workspace.selected() else {
            return self
                .current_connection()
                .map(|c| vec![c.name.clone()])
                .unwrap_or_default();
        };
        let mut out = Vec::new();
        if let Some(c) = self.connections.by_id(tab.spec.connection_id()) {
            out.push(c.name.clone());
        }
        match &tab.spec {
            TabSpec::Table { table, .. } => {
                if table.namespace.catalog.is_some() || table.namespace.schema.is_some() {
                    out.push(table.namespace.display_name().to_owned());
                }
                out.push(table.name.clone());
            }
            TabSpec::Collection {
                database,
                collection,
                ..
            } => {
                out.push(database.clone());
                out.push(collection.clone());
            }
            _ => {}
        }
        out
    }

    /// Whether the connection's tree node is the explorer's (for the empty state and the menus).
    pub fn connection_node_key(id: &str) -> String {
        connection_key(id)
    }

    pub fn paradigm_of(&self, id: &str) -> Option<Paradigm> {
        self.connections.by_id(id).map(|c| c.db_type.paradigm)
    }
}

fn save(changed: Vec<ConnectionConfig>) -> Vec<Effect> {
    if changed.is_empty() {
        Vec::new()
    } else {
        vec![Effect::SaveProfiles(changed)]
    }
}

/// `SELECT * FROM <table>;` with the name as the dialect quotes it.
pub fn select_sql(config: &ConnectionConfig, table: &TableRef) -> String {
    let name = config
        .db_type
        .dialect()
        .map_or_else(|| table.name.clone(), |d| d.qualified_name(table));
    format!("SELECT * FROM {name};")
}

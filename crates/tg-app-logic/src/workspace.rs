//! The editor tabs of the main area (`workspace/WorkspaceViewModel.kt`).
//!
//! A tab is a [`TabSpec`]: what it shows, as data. The shell draws each kind with the view registered for it (S6 ships
//! placeholders; S7 registers the table view, S8 the console). A view keeps its tab's spec current (a console's text
//! and caret, a table's filter), so the session saves what is on screen. Keys are stable while a tab is open.

use tg_core::connection::ConnectionConfig;
use tg_core::session::SessionTab;
use tg_sql::model::{Namespace, Paradigm, TableRef};

#[derive(Clone, Debug, PartialEq)]
pub enum TabSpec {
    /// A SQL console, or a command console for engines without SQL (a key-value store's `keyspace`, a document
    /// store's `database`).
    Console {
        connection_id: String,
        text: String,
        file: Option<String>,
        caret: i32,
        saved_text: Option<String>,
        keyspace: i32,
        database: Option<String>,
    },
    /// A table's data, or its structure.
    Table {
        connection_id: String,
        table: TableRef,
        structure: bool,
        /// The filter as a WHERE clause.
        filter: Option<String>,
    },
    /// A key-value store's key browser.
    Keys {
        connection_id: String,
        keyspace: i32,
    },
    /// A document store's collection.
    Collection {
        connection_id: String,
        database: String,
        collection: String,
    },
}

impl TabSpec {
    pub fn connection_id(&self) -> &str {
        match self {
            Self::Console { connection_id, .. }
            | Self::Table { connection_id, .. }
            | Self::Keys { connection_id, .. }
            | Self::Collection { connection_id, .. } => connection_id,
        }
    }

    /// The name views are registered under.
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Console { .. } => "console",
            Self::Table { .. } => "table",
            Self::Keys { .. } => "keys",
            Self::Collection { .. } => "collection",
        }
    }

    /// How the tab reopens on the next start; None for tabs that don't (as in Kotlin, only consoles and tables).
    pub fn to_session(&self) -> Option<SessionTab> {
        match self {
            Self::Console {
                connection_id,
                text,
                file,
                caret,
                saved_text,
                ..
            } => Some(SessionTab::Console {
                connection_id: connection_id.clone(),
                text: text.clone(),
                file: file.clone(),
                caret: *caret,
                saved_text: saved_text.clone(),
            }),
            Self::Table {
                connection_id,
                table,
                structure,
                filter,
            } => Some(SessionTab::Table {
                connection_id: connection_id.clone(),
                catalog: table.namespace.catalog.clone(),
                schema: table.namespace.schema.clone(),
                name: table.name.clone(),
                structure: *structure,
                filter: filter.clone(),
            }),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Tab {
    pub key: u64,
    pub title: String,
    pub spec: TabSpec,
}

#[derive(Clone, Debug, Default)]
pub struct Workspace {
    pub tabs: Vec<Tab>,
    selected: Option<u64>,
    console_count: u32,
    next_key: u64,
}

impl Workspace {
    pub fn selected(&self) -> Option<&Tab> {
        self.selected
            .and_then(|k| self.tabs.iter().find(|t| t.key == k))
    }

    pub fn selected_index(&self) -> Option<usize> {
        self.selected
            .and_then(|k| self.tabs.iter().position(|t| t.key == k))
    }

    pub fn tab(&self, key: u64) -> Option<&Tab> {
        self.tabs.iter().find(|t| t.key == key)
    }

    pub fn tab_mut(&mut self, key: u64) -> Option<&mut Tab> {
        self.tabs.iter_mut().find(|t| t.key == key)
    }

    /// Selects `key`; the caller makes its connection current.
    pub fn select(&mut self, key: u64) -> bool {
        if self.tabs.iter().any(|t| t.key == key) {
            self.selected = Some(key);
            true
        } else {
            false
        }
    }

    fn console_name(&mut self, config: &ConnectionConfig) -> String {
        self.console_count += 1;
        if self.console_count == 1 {
            config.name.clone()
        } else {
            format!("{} ({})", config.name, self.console_count)
        }
    }

    fn open(&mut self, title: String, spec: TabSpec) -> u64 {
        let key = self.next_key;
        self.next_key += 1;
        self.tabs.push(Tab { key, title, spec });
        self.selected = Some(key);
        key
    }

    /// A SQL console, or a command console for engines without SQL; `initial` is the first text to show.
    pub fn open_console(
        &mut self,
        config: &ConnectionConfig,
        initial: &str,
        keyspace: i32,
        database: Option<&str>,
    ) -> u64 {
        let name = self.console_name(config);
        let database = match config.db_type.paradigm {
            Paradigm::Document => Some(database.map_or_else(
                || {
                    if config.database.trim().is_empty() {
                        "test".to_owned()
                    } else {
                        config.database.clone()
                    }
                },
                str::to_owned,
            )),
            _ => None,
        };
        self.open(
            name,
            TabSpec::Console {
                connection_id: config.id.clone(),
                text: initial.to_owned(),
                file: None,
                caret: 0,
                saved_text: None,
                keyspace,
                database,
            },
        )
    }

    /// Focuses the table's tab if it is already open; `structure` switches it to the structure view, or (None) keeps
    /// the view an open tab shows and opens a new one on its data.
    pub fn open_table(
        &mut self,
        config: &ConnectionConfig,
        table: &TableRef,
        structure: Option<bool>,
    ) -> u64 {
        if let Some(tab) = self.tabs.iter_mut().find(|t| {
            matches!(&t.spec, TabSpec::Table { connection_id, table: t2, .. } if connection_id == &config.id && t2 == table)
        }) {
            if let (Some(s), TabSpec::Table { structure: shown, .. }) = (structure, &mut tab.spec) {
                *shown = s;
            }
            let key = tab.key;
            self.selected = Some(key);
            return key;
        }
        self.open(
            table.name.clone(),
            TabSpec::Table {
                connection_id: config.id.clone(),
                table: table.clone(),
                structure: structure.unwrap_or(false),
                filter: None,
            },
        )
    }

    /// Focuses the key browser of `keyspace` if it is already open.
    pub fn open_keys(&mut self, config: &ConnectionConfig, keyspace: i32) -> u64 {
        if let Some(t) = self.tabs.iter().find(|t| {
            matches!(&t.spec, TabSpec::Keys { connection_id, keyspace: k } if connection_id == &config.id && *k == keyspace)
        }) {
            let key = t.key;
            self.selected = Some(key);
            return key;
        }
        self.open(
            format!("{} db{keyspace}", config.name),
            TabSpec::Keys {
                connection_id: config.id.clone(),
                keyspace,
            },
        )
    }

    /// Focuses the collection's document browser if it is already open.
    pub fn open_collection(
        &mut self,
        config: &ConnectionConfig,
        database: &str,
        collection: &str,
    ) -> u64 {
        if let Some(t) = self.tabs.iter().find(|t| {
            matches!(&t.spec, TabSpec::Collection { connection_id, database: d, collection: c }
                if connection_id == &config.id && d == database && c == collection)
        }) {
            let key = t.key;
            self.selected = Some(key);
            return key;
        }
        self.open(
            collection.to_owned(),
            TabSpec::Collection {
                connection_id: config.id.clone(),
                database: database.to_owned(),
                collection: collection.to_owned(),
            },
        )
    }

    /// Closes `key`; the tab after it (or else the last) becomes selected if it was.
    pub fn close(&mut self, key: u64) -> Option<Tab> {
        let index = self.tabs.iter().position(|t| t.key == key)?;
        let tab = self.tabs.remove(index);
        if self.selected == Some(key) {
            self.selected = self
                .tabs
                .get(index)
                .or_else(|| self.tabs.last())
                .map(|t| t.key);
        }
        Some(tab)
    }

    /// Closes every tab of a connection (e.g. it was deleted).
    pub fn close_connection(&mut self, id: &str) {
        let keys: Vec<u64> = self
            .tabs
            .iter()
            .filter(|t| t.spec.connection_id() == id)
            .map(|t| t.key)
            .collect();
        for k in keys {
            self.close(k);
        }
    }

    /// Moves the tab at `from` to `to`, shifting the ones in between.
    pub fn move_tab(&mut self, from: usize, to: usize) {
        if from == to || from >= self.tabs.len() || to >= self.tabs.len() {
            return;
        }
        let tab = self.tabs.remove(from);
        self.tabs.insert(to, tab);
    }

    /// Selects the tab `offset` places from the current one, wrapping around.
    pub fn select_relative(&mut self, offset: i32) -> Option<u64> {
        if self.tabs.is_empty() {
            return None;
        }
        let index = self.selected_index().unwrap_or(0) as i32;
        let n = self.tabs.len() as i32;
        let key = self.tabs[(index + offset).rem_euclid(n) as usize].key;
        self.selected = Some(key);
        Some(key)
    }

    /// Reopens a tab from the last session: a console with its text, file and caret, a table with its view and filter.
    pub fn restore(&mut self, config: &ConnectionConfig, saved: &SessionTab) -> u64 {
        match saved {
            SessionTab::Console {
                text,
                file,
                caret,
                saved_text,
                ..
            } => {
                let name = self.console_name(config);
                self.open(
                    name,
                    TabSpec::Console {
                        connection_id: config.id.clone(),
                        text: text.clone(),
                        file: file.clone(),
                        caret: *caret,
                        saved_text: saved_text.clone(),
                        keyspace: 0,
                        database: None,
                    },
                )
            }
            SessionTab::Table {
                catalog,
                schema,
                name,
                structure,
                filter,
                ..
            } => {
                let table =
                    TableRef::new(Namespace::new(catalog.as_deref(), schema.as_deref()), name);
                self.open(
                    name.clone(),
                    TabSpec::Table {
                        connection_id: config.id.clone(),
                        table,
                        structure: *structure,
                        filter: filter.clone(),
                    },
                )
            }
        }
    }
}

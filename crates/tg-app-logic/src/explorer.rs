//! The database explorer's tree (`explorer/ExplorerViewModel.kt`): connections in their groups, then namespaces,
//! tables and views, and the columns, indexes and foreign keys of a table. Children of a connection, a namespace, a
//! table and a document database load the first time their node opens and are cached until refreshed.
//!
//! Node keys are strings, so the tree component can keep its open and selection state by them. A connection's key is
//! `c:<id>`; every node below it extends its parent's key with [`SEP`], so the connection a node belongs to, and the
//! nodes to forget when it disconnects, are read off the key. [`Explorer::build`] turns the cache into the tree's
//! items plus a map from key to [`Node`] for drawing labels and menus.

use std::collections::{HashMap, HashSet};

use tg_core::connection::ConnectionConfig;
use tg_driver::document::CollectionInfo;
use tg_driver::keyvalue::Keyspace;
use tg_sql::model::{
    ColumnInfo, ForeignKeyInfo, IndexInfo, Namespace, Paradigm, TableInfo, TableRef,
};
use tg_ui_logic::Item;

use crate::registry::Live;

/// Joins a child's segment to its parent's key.
pub const SEP: char = '\u{1f}';

#[derive(Clone, Debug, PartialEq)]
pub enum NodeKind {
    /// A folder of connections: one of the user's groups, or the favourites listed above them.
    Group {
        name: String,
        favorites: bool,
    },
    Connection {
        id: String,
    },
    Schema {
        connection: String,
        namespace: Namespace,
    },
    /// "tables", "views", "columns", "indexes", "foreign keys".
    Folder {
        label: String,
    },
    Table {
        connection: String,
        info: TableInfo,
    },
    Column {
        table: TableRef,
        column: ColumnInfo,
    },
    Index {
        index: IndexInfo,
    },
    ForeignKey {
        foreign_key: ForeignKeyInfo,
    },
    /// One database of a key-value store; opening it browses its keys.
    Keyspace {
        connection: String,
        keyspace: Keyspace,
    },
    /// A database of a document store; its children are collections.
    DocumentDatabase {
        connection: String,
        name: String,
    },
    Collection {
        connection: String,
        database: String,
        info: CollectionInfo,
    },
    /// Stands in for a node's children while they load, connecting first if need be.
    Loading,
    /// Stands in for a node's children when loading them failed.
    Failure {
        message: String,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Node {
    pub key: String,
    pub kind: NodeKind,
    /// Children known without loading (groups and folders).
    pub children: Option<Vec<Node>>,
}

impl Node {
    fn leaf(key: String, kind: NodeKind) -> Node {
        Node {
            key,
            kind,
            children: None,
        }
    }

    /// The node's own name, the one search matches and the row shows.
    pub fn label(&self, connections: &[ConnectionConfig]) -> String {
        match &self.kind {
            NodeKind::Group { name, .. } => name.clone(),
            NodeKind::Connection { id } => connections
                .iter()
                .find(|c| &c.id == id)
                .map_or_else(|| id.clone(), |c| c.name.clone()),
            NodeKind::Schema { namespace, .. } => namespace.display_name().to_owned(),
            NodeKind::Folder { label } => label.clone(),
            NodeKind::Table { info, .. } => info.table.name.clone(),
            NodeKind::Column { column, .. } => column.name.clone(),
            NodeKind::Index { index } => index.name.clone(),
            NodeKind::ForeignKey { foreign_key } => foreign_key.name.clone(),
            NodeKind::Keyspace { keyspace, .. } => format!("db{}", keyspace.index),
            NodeKind::DocumentDatabase { name, .. } => name.clone(),
            NodeKind::Collection { info, .. } => info.name.clone(),
            NodeKind::Loading => "Loading…".to_owned(),
            NodeKind::Failure { message } => tg_core::json::first_line(message),
        }
    }

    /// Grey text after the label: a column's type, an index's columns, a key count.
    pub fn detail(&self) -> Option<String> {
        match &self.kind {
            NodeKind::Column { column, .. } => {
                let mut s = column.type_name.to_lowercase();
                if column.primary_key {
                    s.push_str(" · PK");
                }
                if !column.nullable {
                    s.push_str(" · not null");
                }
                Some(s)
            }
            NodeKind::Index { index } => Some(format!(
                "{}{}",
                if index.unique { "unique · " } else { "" },
                index.columns.join(", ")
            )),
            NodeKind::ForeignKey { foreign_key } => Some(format!(
                "→ {}({})",
                foreign_key.referenced_table.name,
                foreign_key.referenced_columns.join(", ")
            )),
            NodeKind::Keyspace { keyspace, .. } => keyspace.keys.map(|k| format!("{k} keys")),
            NodeKind::Collection { info, .. } => info.documents.map(|d| format!("{d} documents")),
            _ => None,
        }
    }

    /// The icon key (`tg_ui::icon`) the row draws.
    pub fn icon(&self) -> &'static str {
        match &self.kind {
            NodeKind::Group {
                favorites: true, ..
            } => "Nodes.Favorite",
            NodeKind::Group { .. } | NodeKind::Folder { .. } => "Nodes.Folder",
            NodeKind::Connection { .. } => "Nodes.DataSchema",
            NodeKind::Schema { .. }
            | NodeKind::DocumentDatabase { .. }
            | NodeKind::Keyspace { .. } => "Nodes.DataSchema",
            NodeKind::Table { .. } | NodeKind::Collection { .. } => "Nodes.DataTables",
            NodeKind::Column { column, .. } if column.primary_key => "Nodes.FieldPK",
            NodeKind::Column { .. } => "Nodes.DataColumn",
            NodeKind::Index { .. } => "ObjectBrowser.Sorted",
            NodeKind::ForeignKey { .. } => "Diff.ArrowLeftRight",
            NodeKind::Loading => "Actions.Refresh",
            NodeKind::Failure { .. } => "General.Error",
        }
    }

    /// Whether the node has children to load when it opens.
    pub fn lazy(&self) -> bool {
        matches!(
            self.kind,
            NodeKind::Connection { .. }
                | NodeKind::Schema { .. }
                | NodeKind::Table { .. }
                | NodeKind::DocumentDatabase { .. }
        )
    }
}

/// The connection a node key belongs to: the id in its leading `c:<id>` segment.
pub fn connection_of(key: &str) -> Option<&str> {
    let head = key.split(SEP).next()?;
    head.strip_prefix("c:")
}

pub fn connection_key(id: &str) -> String {
    format!("c:{id}")
}

fn child_key(parent: &str, segment: &str) -> String {
    format!("{parent}{SEP}{segment}")
}

fn namespace_segment(ns: &Namespace) -> String {
    format!(
        "s:{}.{}",
        ns.catalog.as_deref().unwrap_or(""),
        ns.schema.as_deref().unwrap_or("")
    )
}

/// What opening a lazy node asks the app to load.
#[derive(Clone, Debug, PartialEq)]
pub struct LoadJob {
    pub key: String,
    pub connection_id: String,
    pub what: LoadWhat,
}

#[derive(Clone, Debug, PartialEq)]
pub enum LoadWhat {
    Connection,
    Schema(Namespace),
    Table(TableRef),
    DocumentDatabase(String),
}

#[derive(Clone, Debug, PartialEq)]
enum Children {
    Loading,
    Loaded(Vec<Node>),
    Failed(String),
}

/// The tree as items for the tree component, and every node by key.
#[derive(Clone, Debug, Default)]
pub struct Built {
    pub items: Vec<Item<String>>,
    pub nodes: HashMap<String, Node>,
}

/// Explorer state: loaded children, the open nodes (as the tree reports them), and the search text.
#[derive(Clone, Debug, Default)]
pub struct Explorer {
    children: HashMap<String, Children>,
    open: HashSet<String>,
    /// Groups already shown once: a group opens the first time it appears, as the Kotlin panel does, and stays as
    /// the user leaves it after that.
    seen_groups: HashSet<String>,
    /// Narrows the tree to connections, and tables and columns already loaded, whose name matches.
    pub search: String,
    /// Nodes for the tree to open (reveal, a lone namespace) or close (a disconnect) the next time it draws.
    pending_open: Vec<String>,
    pending_close: Vec<String>,
    /// Bumped on every change, so the view rebuilds its items only when something changed.
    revision: u64,
}

impl Explorer {
    pub fn revision(&self) -> u64 {
        self.revision
    }

    fn changed(&mut self) {
        self.revision += 1;
    }

    pub fn set_search(&mut self, text: &str) {
        if self.search != text {
            self.search = text.to_owned();
            self.changed();
        }
    }

    pub fn is_open(&self, key: &str) -> bool {
        self.open.contains(key)
    }

    /// Keys the tree should open and close now; taking them clears the queue.
    pub fn take_pending(&mut self) -> (Vec<String>, Vec<String>) {
        (
            std::mem::take(&mut self.pending_open),
            std::mem::take(&mut self.pending_close),
        )
    }

    /// The tree opened `key`: its children load unless they're cached.
    pub fn opened(&mut self, key: &str, profiles: &[ConnectionConfig]) -> Option<LoadJob> {
        self.open.insert(key.to_owned());
        self.ensure_loaded(key, profiles)
    }

    /// The tree closed `key`, which also forgets which of its descendants were open.
    pub fn closed(&mut self, key: &str) {
        let prefix = format!("{key}{SEP}");
        self.open.retain(|k| k != key && !k.starts_with(&prefix));
    }

    /// Opens already loaded nodes so search matches show (`searchMatchAncestors`); nothing loads.
    pub fn reveal_keys(&mut self, keys: Vec<String>) {
        for key in keys {
            // The tree may not have it open even when we do (`opened` was told first), so always ask it.
            self.open.insert(key.clone());
            self.pending_open.push(key);
        }
    }

    /// Opens groups that weren't there before (on the first draw, every group).
    pub fn open_new_groups(&mut self, profiles: &[ConnectionConfig]) {
        let fresh: Vec<String> = Self::top_level(profiles)
            .into_iter()
            .filter(|n| matches!(n.kind, NodeKind::Group { .. }))
            .map(|n| n.key)
            .filter(|k| self.seen_groups.insert(k.clone()))
            .collect();
        if !fresh.is_empty() {
            self.reveal_keys(fresh);
            self.changed();
        }
    }

    /// The key of the group a profile is listed in, if any.
    fn group_key(config: &ConnectionConfig) -> Option<String> {
        if config.favorite {
            Some("g:1:Favorites".to_owned())
        } else if !config.group.trim().is_empty() {
            Some(format!("g:0:{}", config.group.trim()))
        } else {
            None
        }
    }

    /// Opens a connection's node (which connects and loads its namespaces), and its group so it shows.
    pub fn reveal(&mut self, id: &str, profiles: &[ConnectionConfig]) -> Option<LoadJob> {
        if let Some(group) = profiles
            .iter()
            .find(|c| c.id == id)
            .and_then(Self::group_key)
        {
            self.reveal_keys(vec![group]);
        }
        let key = connection_key(id);
        self.pending_open.push(key.clone());
        self.opened(&key, profiles)
    }

    fn ensure_loaded(&mut self, key: &str, profiles: &[ConnectionConfig]) -> Option<LoadJob> {
        if matches!(
            self.children.get(key),
            Some(Children::Loaded(_) | Children::Loading)
        ) {
            return None;
        }
        let id = connection_of(key)?;
        let config = profiles.iter().find(|c| c.id == id)?;
        let what = self.load_what(key, config)?;
        self.children.insert(key.to_owned(), Children::Loading);
        self.changed();
        Some(LoadJob {
            key: key.to_owned(),
            connection_id: id.to_owned(),
            what,
        })
    }

    /// What the node at `key` loads, read off the cached node itself.
    fn load_what(&self, key: &str, config: &ConnectionConfig) -> Option<LoadWhat> {
        if key == connection_key(&config.id) {
            return Some(LoadWhat::Connection);
        }
        let node = self.find(key)?;
        match &node.kind {
            NodeKind::Schema { namespace, .. } => Some(LoadWhat::Schema(namespace.clone())),
            NodeKind::Table { info, .. } => Some(LoadWhat::Table(info.table.clone())),
            NodeKind::DocumentDatabase { name, .. } => {
                Some(LoadWhat::DocumentDatabase(name.clone()))
            }
            _ => None,
        }
    }

    /// A loaded node by key, searching the cache.
    pub fn find(&self, key: &str) -> Option<&Node> {
        fn search<'a>(nodes: &'a [Node], key: &str) -> Option<&'a Node> {
            nodes.iter().find_map(|n| {
                if n.key == key {
                    Some(n)
                } else {
                    n.children.as_deref().and_then(|c| search(c, key))
                }
            })
        }
        self.children.values().find_map(|c| match c {
            Children::Loaded(nodes) => search(nodes, key),
            _ => None,
        })
    }

    /// Children arrived (or failed). Returns what else to load: a lone namespace opens right away, as it adds
    /// nothing to look at.
    pub fn loaded(
        &mut self,
        job: &LoadJob,
        result: Result<Vec<Node>, String>,
        profiles: &[ConnectionConfig],
    ) -> Vec<LoadJob> {
        // Refreshed or disconnected since: drop the stale answer.
        if self.children.get(&job.key) != Some(&Children::Loading) {
            return Vec::new();
        }
        let mut next = Vec::new();
        match result {
            Ok(nodes) => {
                if job.what == LoadWhat::Connection
                    && nodes.len() == 1
                    && matches!(nodes[0].kind, NodeKind::Schema { .. })
                {
                    let key = nodes[0].key.clone();
                    self.children
                        .insert(job.key.clone(), Children::Loaded(nodes));
                    self.pending_open.push(key.clone());
                    next.extend(self.opened(&key, profiles));
                } else {
                    self.children
                        .insert(job.key.clone(), Children::Loaded(nodes));
                }
            }
            Err(message) => {
                self.children
                    .insert(job.key.clone(), Children::Failed(message));
            }
        }
        self.changed();
        next
    }

    /// Drops cached children of `key` and loads them again if the node is open.
    pub fn refresh(&mut self, key: &str, profiles: &[ConnectionConfig]) -> Option<LoadJob> {
        let prefix = format!("{key}{SEP}");
        self.children
            .retain(|k, _| k != key && !k.starts_with(&prefix));
        self.changed();
        if self.open.contains(key) {
            self.ensure_loaded(key, profiles)
        } else {
            None
        }
    }

    /// Clears everything cached for a connection and collapses it.
    pub fn forget(&mut self, id: &str) {
        let key = connection_key(id);
        let prefix = format!("{key}{SEP}");
        self.children
            .retain(|k, _| *k != key && !k.starts_with(&prefix));
        if self.open.contains(&key) {
            self.pending_close.push(key.clone());
        }
        self.closed(&key);
        self.changed();
    }

    /// Every open node; the tree keeps its own copy, this one decides what loads.
    pub fn open_keys(&self) -> impl Iterator<Item = &String> {
        self.open.iter()
    }

    /// Favourites first, then each group in name order (ignoring case), then connections without a group in their
    /// saved order.
    fn top_level(profiles: &[ConnectionConfig]) -> Vec<Node> {
        let connection = |c: &ConnectionConfig| {
            Node::leaf(
                connection_key(&c.id),
                NodeKind::Connection { id: c.id.clone() },
            )
        };
        let mut out = Vec::new();
        let favorites: Vec<Node> = profiles
            .iter()
            .filter(|c| c.favorite)
            .map(connection)
            .collect();
        if !favorites.is_empty() {
            out.push(Node {
                key: "g:1:Favorites".to_owned(),
                kind: NodeKind::Group {
                    name: "Favorites".to_owned(),
                    favorites: true,
                },
                children: Some(favorites),
            });
        }
        let mut groups: Vec<(String, Vec<Node>)> = Vec::new();
        for c in profiles
            .iter()
            .filter(|c| !c.favorite && !c.group.trim().is_empty())
        {
            let name = c.group.trim();
            match groups.iter_mut().find(|(g, _)| g == name) {
                Some((_, members)) => members.push(connection(c)),
                None => groups.push((name.to_owned(), vec![connection(c)])),
            }
        }
        groups.sort_by_key(|(g, _)| g.to_lowercase());
        for (name, members) in groups {
            out.push(Node {
                key: format!("g:0:{name}"),
                kind: NodeKind::Group {
                    name,
                    favorites: false,
                },
                children: Some(members),
            });
        }
        out.extend(
            profiles
                .iter()
                .filter(|c| !c.favorite && c.group.trim().is_empty())
                .map(connection),
        );
        out
    }

    fn matches(&self, node: &Node, query: &str, profiles: &[ConnectionConfig]) -> bool {
        let q = query.to_lowercase();
        let named = match &node.kind {
            NodeKind::Folder { .. }
            | NodeKind::Index { .. }
            | NodeKind::ForeignKey { .. }
            | NodeKind::Keyspace { .. }
            | NodeKind::Loading
            | NodeKind::Failure { .. } => false,
            _ => node.label(profiles).to_lowercase().contains(&q),
        };
        named
            || match &node.kind {
                NodeKind::Connection { id } => {
                    profiles.iter().find(|c| &c.id == id).is_some_and(|c| {
                        [c.host.as_str(), c.database.as_str(), c.db_type.display_name]
                            .iter()
                            .any(|s| s.to_lowercase().contains(&q))
                    })
                }
                _ => false,
            }
    }

    /// The node's children as the tree shows them: loaded ones, or a Loading or Failure row in their place.
    fn shown_children(&self, node: &Node) -> Option<Vec<Node>> {
        if let Some(children) = &node.children {
            return Some(children.clone());
        }
        if !node.lazy() {
            return None;
        }
        Some(match self.children.get(&node.key) {
            Some(Children::Loaded(nodes)) => nodes.clone(),
            Some(Children::Failed(message)) => vec![Node::leaf(
                child_key(&node.key, "failure"),
                NodeKind::Failure {
                    message: message.clone(),
                },
            )],
            Some(Children::Loading) | None => {
                vec![Node::leaf(
                    child_key(&node.key, "loading"),
                    NodeKind::Loading,
                )]
            }
        })
    }

    /// Loaded children only, for search: searching never connects anything.
    fn loaded_children(&self, node: &Node) -> Vec<Node> {
        match (&node.children, self.children.get(&node.key)) {
            (Some(children), _) => children.clone(),
            (None, Some(Children::Loaded(nodes))) => nodes.clone(),
            _ => Vec::new(),
        }
    }

    /// `node` if it or something loaded under it matches `query`; a match keeps everything under it, a group or
    /// folder only its matching children.
    fn filtered(&self, node: &Node, query: &str, profiles: &[ConnectionConfig]) -> Option<Node> {
        let is_group = matches!(node.kind, NodeKind::Group { .. });
        if query.is_empty() || (self.matches(node, query, profiles) && !is_group) {
            return Some(node.clone());
        }
        let kept: Vec<Node> = self
            .loaded_children(node)
            .iter()
            .filter_map(|c| self.filtered(c, query, profiles))
            .collect();
        match node.kind {
            NodeKind::Group { .. } if self.matches(node, query, profiles) => Some(node.clone()),
            NodeKind::Group { .. } | NodeKind::Folder { .. } => (!kept.is_empty()).then(|| Node {
                children: Some(kept),
                ..node.clone()
            }),
            _ if !kept.is_empty() => Some(node.clone()),
            _ => None,
        }
    }

    /// Nodes to open so every search match shows; only nodes already loaded.
    pub fn search_match_ancestors(&self, profiles: &[ConnectionConfig]) -> Vec<String> {
        let query = self.search.trim();
        if query.is_empty() {
            return Vec::new();
        }
        let mut keys = Vec::new();
        fn visit(
            e: &Explorer,
            node: &Node,
            path: &mut Vec<String>,
            query: &str,
            profiles: &[ConnectionConfig],
            keys: &mut Vec<String>,
        ) -> bool {
            path.push(node.key.clone());
            let mut child_matched = false;
            for c in e.loaded_children(node) {
                child_matched |= visit(e, &c, path, query, profiles, keys);
            }
            if child_matched {
                for k in path.iter() {
                    if !keys.contains(k) {
                        keys.push(k.clone());
                    }
                }
            }
            path.pop();
            child_matched || e.matches(node, query, profiles)
        }
        for node in Self::top_level(profiles) {
            visit(self, &node, &mut Vec::new(), query, profiles, &mut keys);
        }
        keys
    }

    /// The tree's items and nodes for `profiles`, narrowed by the search.
    pub fn build(&self, profiles: &[ConnectionConfig]) -> Built {
        let query = self.search.trim().to_owned();
        let mut built = Built::default();
        for node in Self::top_level(profiles) {
            if let Some(node) = self.filtered(&node, &query, profiles) {
                let item = self.item(&node, &query, profiles, &mut built.nodes);
                built.items.push(item);
            }
        }
        built
    }

    fn item(
        &self,
        node: &Node,
        searched: &str,
        profiles: &[ConnectionConfig],
        nodes: &mut HashMap<String, Node>,
    ) -> Item<String> {
        // Below a match everything shows; elsewhere only the paths to matches do.
        let query = if !searched.is_empty() && self.matches(node, searched, profiles) {
            ""
        } else {
            searched
        };
        nodes.insert(node.key.clone(), node.clone());
        match self.shown_children(node) {
            None => Item::leaf(node.key.clone()),
            Some(children) => {
                let children = children
                    .iter()
                    .filter_map(|c| {
                        if query.is_empty() {
                            Some(c.clone())
                        } else {
                            self.filtered(c, query, profiles)
                        }
                    })
                    .map(|c| self.item(&c, query, profiles, nodes))
                    .collect();
                Item::node(node.key.clone(), children)
            }
        }
    }
}

/// Loads the children `job` asks for from the live connection (`ExplorerViewModel.load`).
pub async fn load(live: &Live, job: &LoadJob) -> Result<Vec<Node>, String> {
    let id = &job.connection_id;
    let key = job.key.as_str();
    if let Some(docs) = live.documents() {
        return Ok(match &job.what {
            LoadWhat::Connection => docs
                .databases()
                .await
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|name| {
                    Node::leaf(
                        child_key(key, &format!("d:{name}")),
                        NodeKind::DocumentDatabase {
                            connection: id.clone(),
                            name,
                        },
                    )
                })
                .collect(),
            LoadWhat::DocumentDatabase(database) => docs
                .collections(database)
                .await
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|info| {
                    Node::leaf(
                        child_key(key, &format!("o:{}", info.name)),
                        NodeKind::Collection {
                            connection: id.clone(),
                            database: database.clone(),
                            info,
                        },
                    )
                })
                .collect(),
            _ => Vec::new(),
        });
    }
    if let Some(kv) = live.key_value() {
        // Empty databases past the first add nothing but noise; the console's SELECT still reaches them.
        let keyspaces = kv.keyspaces().await.map_err(|e| e.to_string())?;
        return Ok(keyspaces
            .into_iter()
            .filter(|k| k.index == 0 || k.keys.unwrap_or(0) > 0)
            .map(|keyspace| {
                Node::leaf(
                    child_key(key, &format!("k:{}", keyspace.index)),
                    NodeKind::Keyspace {
                        connection: id.clone(),
                        keyspace,
                    },
                )
            })
            .collect());
    }
    let db = live
        .relational()
        .ok_or_else(|| "This connection has no tables".to_owned())?;
    match &job.what {
        LoadWhat::Connection => Ok(db
            .namespaces()
            .await
            .map_err(|e| e.to_string())?
            .into_iter()
            .map(|namespace| {
                Node::leaf(
                    child_key(key, &namespace_segment(&namespace)),
                    NodeKind::Schema {
                        connection: id.clone(),
                        namespace,
                    },
                )
            })
            .collect()),
        LoadWhat::Schema(namespace) => {
            let (views, tables): (Vec<TableInfo>, Vec<TableInfo>) = db
                .tables(namespace)
                .await
                .map_err(|e| e.to_string())?
                .into_iter()
                .partition(|t| t.is_view);
            let folder = |label: &str, infos: Vec<TableInfo>| {
                let folder_key = child_key(key, label);
                let children = infos
                    .into_iter()
                    .map(|info| {
                        Node::leaf(
                            child_key(&folder_key, &format!("t:{}", info.table.name)),
                            NodeKind::Table {
                                connection: id.clone(),
                                info,
                            },
                        )
                    })
                    .collect();
                Node {
                    key: folder_key,
                    kind: NodeKind::Folder {
                        label: label.to_owned(),
                    },
                    children: Some(children),
                }
            };
            let mut out = Vec::new();
            if !tables.is_empty() {
                out.push(folder("tables", tables));
            }
            if !views.is_empty() {
                out.push(folder("views", views));
            }
            Ok(out)
        }
        LoadWhat::Table(table) => {
            let details = db.table_details(table).await.map_err(|e| e.to_string())?;
            let folder = |label: &str, children: Vec<Node>| Node {
                key: child_key(key, label),
                kind: NodeKind::Folder {
                    label: label.to_owned(),
                },
                children: Some(children),
            };
            let columns_key = child_key(key, "columns");
            let mut out = vec![folder(
                "columns",
                details
                    .columns
                    .into_iter()
                    .map(|column| {
                        Node::leaf(
                            child_key(&columns_key, &column.name),
                            NodeKind::Column {
                                table: table.clone(),
                                column,
                            },
                        )
                    })
                    .collect(),
            )];
            if !details.indexes.is_empty() {
                let k = child_key(key, "indexes");
                out.push(folder(
                    "indexes",
                    details
                        .indexes
                        .into_iter()
                        .map(|index| {
                            Node::leaf(child_key(&k, &index.name), NodeKind::Index { index })
                        })
                        .collect(),
                ));
            }
            if !details.foreign_keys.is_empty() {
                let k = child_key(key, "foreign keys");
                out.push(folder(
                    "foreign keys",
                    details
                        .foreign_keys
                        .into_iter()
                        .map(|foreign_key| {
                            Node::leaf(
                                child_key(&k, &foreign_key.name),
                                NodeKind::ForeignKey { foreign_key },
                            )
                        })
                        .collect(),
                ));
            }
            Ok(out)
        }
        LoadWhat::DocumentDatabase(_) => Ok(Vec::new()),
    }
}

/// The paradigm decides which loading message a connection's placeholder row shows.
pub fn loading_label(node_kind: &NodeKind, paradigm: Option<Paradigm>) -> &'static str {
    match node_kind {
        NodeKind::Connection { .. } => match paradigm {
            Some(Paradigm::Document) => "Loading databases…",
            Some(Paradigm::KeyValue) => "Loading keys…",
            _ => "Loading schemas…",
        },
        NodeKind::Schema { .. } => "Loading tables…",
        NodeKind::DocumentDatabase { .. } => "Loading collections…",
        NodeKind::Table { .. } => "Loading columns…",
        _ => "Loading…",
    }
}

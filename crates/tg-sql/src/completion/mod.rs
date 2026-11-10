//! Completion for the SQL editor (`SqlCompleter.kt`) and the document browser's filters (`DocumentCompleter.kt`).
//! Offsets are byte offsets into the text being completed.

pub mod document;
pub mod sql;

pub use document::{DocumentField, DocumentQuery, complete as complete_document};
pub use sql::SqlCompleter;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CompletionKind {
    Keyword,
    Schema,
    Table,
    View,
    Column,
    Alias,
    Function,
    Join,
    Value,
    Operator,
}

impl CompletionKind {
    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Keyword => "Keyword",
            Self::Schema => "Schema",
            Self::Table => "Table",
            Self::View => "View",
            Self::Column => "Column",
            Self::Alias => "Alias",
            Self::Function => "Function",
            Self::Join => "Join",
            Self::Value => "Value",
            Self::Operator => "Operator",
        }
    }
}

/// One suggestion. `insert_text` replaces the typed prefix; the caret ends `caret_offset` bytes into it (inside the
/// parentheses of a function, for example).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompletionItem {
    pub label: String,
    pub insert_text: String,
    pub kind: CompletionKind,
    pub detail: Option<String>,
    pub caret_offset: usize,
}

impl CompletionItem {
    pub fn new(
        label: impl Into<String>,
        insert_text: impl Into<String>,
        kind: CompletionKind,
        detail: Option<String>,
    ) -> Self {
        let insert_text = insert_text.into();
        Self {
            label: label.into(),
            caret_offset: insert_text.len(),
            insert_text,
            kind,
            detail,
        }
    }

    pub fn with_caret(mut self, caret_offset: usize) -> Self {
        self.caret_offset = caret_offset;
        self
    }
}

/// A spot where a literal value is expected, e.g. `status = 'pa|`, so the host can suggest values from the data.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ValueContext {
    pub namespace: Option<String>,
    pub table: String,
    pub column: String,
    pub prefix: String,
    pub quoted: bool,
}

/// Suggestions for the caret. Insert over `replace_start..replace_end`. `pending_tables` lists tables whose columns
/// were needed but aren't loaded yet; load them and complete again.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompletionResult {
    pub replace_start: usize,
    pub replace_end: usize,
    pub prefix: String,
    pub items: Vec<CompletionItem>,
    pub value: Option<ValueContext>,
    pub pending_tables: Vec<(Option<String>, String)>,
}

impl CompletionResult {
    pub fn empty(caret: usize) -> Self {
        Self {
            replace_start: caret,
            replace_end: caret,
            prefix: String::new(),
            items: vec![],
            value: None,
            pending_tables: vec![],
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableEntry {
    pub name: String,
    pub is_view: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ColumnEntry {
    pub name: String,
    pub type_: String,
    pub primary_key: bool,
}

/// A foreign key: `from_table.from_columns` references `to_table.to_columns`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Relation {
    pub from_table: String,
    pub from_columns: Vec<String>,
    pub to_table: String,
    pub to_columns: Vec<String>,
}

/// Read-only view of a database's schema for completion; None means "not loaded yet".
pub trait SchemaProvider {
    /// The namespace unqualified names resolve to (PostgreSQL's `public`, the MySQL database, SQLite's `main`).
    fn default_namespace(&self) -> Option<String>;

    fn namespaces(&self) -> Vec<String>;

    fn tables(&self, namespace: Option<&str>) -> Option<Vec<TableEntry>>;

    fn columns(&self, namespace: Option<&str>, table: &str) -> Option<Vec<ColumnEntry>>;

    /// Foreign keys from and to `table`.
    fn relations(&self, namespace: Option<&str>, table: &str) -> Option<Vec<Relation>>;
}

/// No schema: no tables, and columns and relations never loaded.
pub struct EmptySchema;

impl SchemaProvider for EmptySchema {
    fn default_namespace(&self) -> Option<String> {
        None
    }

    fn namespaces(&self) -> Vec<String> {
        vec![]
    }

    fn tables(&self, _namespace: Option<&str>) -> Option<Vec<TableEntry>> {
        Some(vec![])
    }

    fn columns(&self, _namespace: Option<&str>, _table: &str) -> Option<Vec<ColumnEntry>> {
        None
    }

    fn relations(&self, _namespace: Option<&str>, _table: &str) -> Option<Vec<Relation>> {
        None
    }
}

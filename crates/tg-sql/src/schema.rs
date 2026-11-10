//! Schema snapshots, comparison and migration (`schema/SchemaDiff.kt`), and the ER diagram's model and layout
//! (`schema/ErModel.kt`).

use regex::Regex;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::LazyLock;

use crate::ddl::{
    DdlGenerator, DdlScript, DesignColumn, TableChange, TableDesign, column_types,
    foreign_key_identity,
};
use crate::dialect::SqlDialect;
use crate::kt;
use crate::model::{ForeignKeyInfo, IndexInfo, Namespace, TableDetails, TableRef};

/// Every table of a namespace with its columns, keys and indexes; views are only named.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SchemaSnapshot {
    pub namespace: Namespace,
    pub tables: Vec<TableDetails>,
    pub views: Vec<String>,
}

/// One difference inside a table that exists on both sides.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DiffItem {
    ColumnAdded(DesignColumn),
    ColumnRemoved(DesignColumn),
    /// The changes say what differs, e.g. `type varchar(50) → varchar(100)`.
    ColumnChanged {
        from: DesignColumn,
        to: DesignColumn,
        changes: Vec<String>,
    },
    IndexAdded(IndexInfo),
    IndexRemoved(IndexInfo),
    IndexChanged {
        from: IndexInfo,
        to: IndexInfo,
    },
    ForeignKeyAdded(ForeignKeyInfo),
    ForeignKeyRemoved(ForeignKeyInfo),
    ForeignKeyChanged {
        from: ForeignKeyInfo,
        to: ForeignKeyInfo,
    },
    PrimaryKeyChanged {
        from: Vec<String>,
        to: Vec<String>,
    },
}

fn or_columns(name: &str, columns: &[String]) -> String {
    if kt::is_blank(name) {
        columns.join(", ")
    } else {
        name.to_owned()
    }
}

impl DiffItem {
    /// Stable, for remembering what the user selected.
    pub fn id(&self) -> String {
        match self {
            Self::ColumnAdded(c) | Self::ColumnRemoved(c) | Self::ColumnChanged { from: c, .. } => {
                format!("column:{}", c.name)
            }
            Self::IndexAdded(i) | Self::IndexRemoved(i) | Self::IndexChanged { from: i, .. } => {
                format!("index:{}", i.name)
            }
            Self::ForeignKeyAdded(f)
            | Self::ForeignKeyRemoved(f)
            | Self::ForeignKeyChanged { from: f, .. } => {
                format!("fk:{}", foreign_key_identity(f))
            }
            Self::PrimaryKeyChanged { .. } => "pk".to_owned(),
        }
    }

    pub fn label(&self) -> String {
        match self {
            Self::ColumnAdded(c) => format!("Add column {} {}", c.name, c.type_),
            Self::ColumnRemoved(c) => format!("Drop column {}", c.name),
            Self::ColumnChanged { from, changes, .. } => {
                format!("Change column {}: {}", from.name, changes.join(", "))
            }
            Self::IndexAdded(i) => format!(
                "Create {}index {} ({})",
                if i.unique { "unique " } else { "" },
                i.name,
                i.columns.join(", ")
            ),
            Self::IndexRemoved(i) => format!("Drop index {}", i.name),
            Self::IndexChanged { to, .. } => format!(
                "Recreate index {} ({}){}",
                to.name,
                to.columns.join(", "),
                if to.unique { " unique" } else { "" }
            ),
            Self::ForeignKeyAdded(f) => format!(
                "Add foreign key {} → {}({})",
                or_columns(&f.name, &f.columns),
                f.referenced_table.name,
                f.referenced_columns.join(", ")
            ),
            Self::ForeignKeyRemoved(f) => {
                let what = if kt::is_blank(&f.name) {
                    format!("{} → {}", f.columns.join(", "), f.referenced_table.name)
                } else {
                    f.name.clone()
                };
                format!("Drop foreign key {what}")
            }
            Self::ForeignKeyChanged { to, .. } => format!(
                "Recreate foreign key {} → {}({})",
                or_columns(&to.name, &to.columns),
                to.referenced_table.name,
                to.referenced_columns.join(", ")
            ),
            Self::PrimaryKeyChanged { from, to } => {
                if to.is_empty() {
                    format!("Drop primary key ({})", from.join(", "))
                } else if from.is_empty() {
                    format!("Add primary key ({})", to.join(", "))
                } else {
                    format!(
                        "Change primary key ({}) → ({})",
                        from.join(", "),
                        to.join(", ")
                    )
                }
            }
        }
    }

    /// Drops lose data or structure, so they start unselected.
    pub fn destructive(&self) -> bool {
        matches!(
            self,
            Self::ColumnRemoved(_) | Self::IndexRemoved(_) | Self::ForeignKeyRemoved(_)
        )
    }

    /// The Kotlin class name, for the golden files.
    pub fn kind_name(&self) -> &'static str {
        match self {
            Self::ColumnAdded(_) => "ColumnAdded",
            Self::ColumnRemoved(_) => "ColumnRemoved",
            Self::ColumnChanged { .. } => "ColumnChanged",
            Self::IndexAdded(_) => "IndexAdded",
            Self::IndexRemoved(_) => "IndexRemoved",
            Self::IndexChanged { .. } => "IndexChanged",
            Self::ForeignKeyAdded(_) => "ForeignKeyAdded",
            Self::ForeignKeyRemoved(_) => "ForeignKeyRemoved",
            Self::ForeignKeyChanged { .. } => "ForeignKeyChanged",
            Self::PrimaryKeyChanged { .. } => "PrimaryKeyChanged",
        }
    }
}

/// In the order differences are listed: new tables first, drops last.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum TableDiffKind {
    Added,
    Changed,
    Removed,
}

impl TableDiffKind {
    pub fn name(self) -> &'static str {
        match self {
            Self::Added => "Added",
            Self::Changed => "Changed",
            Self::Removed => "Removed",
        }
    }
}

/// A table that differs: only in the source (added to the target), only in the target (removed), or in both with
/// `items` differing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableDiff {
    pub name: String,
    pub kind: TableDiffKind,
    pub source: Option<TableDesign>,
    pub target: Option<TableDesign>,
    pub items: Vec<DiffItem>,
}

impl TableDiff {
    pub fn id(&self) -> String {
        format!("table:{}", self.name)
    }

    pub fn item_key(&self, item: &DiffItem) -> String {
        format!("{}/{}", self.name, item.id())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SchemaComparison {
    pub diffs: Vec<TableDiff>,
    pub identical: usize,
    pub target_namespace: Namespace,
}

impl SchemaComparison {
    pub fn is_empty(&self) -> bool {
        self.diffs.is_empty()
    }

    /// What starts selected: everything except drops.
    pub fn default_selection(&self) -> BTreeSet<String> {
        let mut selection = BTreeSet::new();
        for diff in &self.diffs {
            match diff.kind {
                TableDiffKind::Added => {
                    selection.insert(diff.id());
                }
                TableDiffKind::Removed => {}
                TableDiffKind::Changed => {
                    for item in diff.items.iter().filter(|i| !i.destructive()) {
                        selection.insert(diff.item_key(item));
                    }
                }
            }
        }
        selection
    }
}

/// Exact names first, then case-insensitively: engines fold unquoted names differently. Later items win, as with
/// Kotlin's `associateBy`.
struct NameLookup<'a, T> {
    exact: HashMap<String, &'a T>,
    folded: HashMap<String, &'a T>,
}

impl<'a, T> NameLookup<'a, T> {
    fn new(items: &'a [T], name: impl Fn(&T) -> String) -> Self {
        let mut exact = HashMap::new();
        let mut folded = HashMap::new();
        for item in items {
            let n = name(item);
            folded.insert(n.to_lowercase(), item);
            exact.insert(n, item);
        }
        Self { exact, folded }
    }

    fn find(&self, name: &str) -> Option<&'a T> {
        self.exact
            .get(name)
            .or_else(|| self.folded.get(&name.to_lowercase()))
            .copied()
    }
}

fn same_names(a: &[String], b: &[String]) -> bool {
    a.len() == b.len() && a.iter().zip(b).all(|(x, y)| kt::eq_ignore_case(x, y))
}

fn same_foreign_key(a: &ForeignKeyInfo, b: &ForeignKeyInfo) -> bool {
    let action = |x: &Option<String>| x.clone().unwrap_or_else(|| "NO ACTION".to_owned());
    same_names(&a.columns, &b.columns)
        && kt::eq_ignore_case(&a.referenced_table.name, &b.referenced_table.name)
        && same_names(&a.referenced_columns, &b.referenced_columns)
        && action(&a.on_update) == action(&b.on_update)
        && action(&a.on_delete) == action(&b.on_delete)
}

/// Compares two schemas and writes the script that makes the target look like the source. The script is in the
/// target's dialect; types are carried over as the source spells them.
pub fn compare(
    source: &[TableDesign],
    target: &[TableDesign],
    target_namespace: &Namespace,
) -> SchemaComparison {
    let target_by_name = NameLookup::new(target, |t| t.table.name.clone());
    let source_by_name = NameLookup::new(source, |t| t.table.name.clone());
    let mut diffs = Vec::new();
    let mut identical = 0;
    for table in source {
        let Some(other) = target_by_name.find(&table.table.name) else {
            diffs.push(TableDiff {
                name: table.table.name.clone(),
                kind: TableDiffKind::Added,
                source: Some(table.clone()),
                target: None,
                items: vec![],
            });
            continue;
        };
        let items = compare_table(table, other);
        if items.is_empty() {
            identical += 1;
        } else {
            diffs.push(TableDiff {
                name: other.table.name.clone(),
                kind: TableDiffKind::Changed,
                source: Some(table.clone()),
                target: Some(other.clone()),
                items,
            });
        }
    }
    for table in target {
        if source_by_name.find(&table.table.name).is_none() {
            diffs.push(TableDiff {
                name: table.table.name.clone(),
                kind: TableDiffKind::Removed,
                source: None,
                target: Some(table.clone()),
                items: vec![],
            });
        }
    }
    diffs.sort_by(|a, b| {
        a.kind
            .cmp(&b.kind)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    SchemaComparison {
        diffs,
        identical,
        target_namespace: target_namespace.clone(),
    }
}

fn compare_table(source: &TableDesign, target: &TableDesign) -> Vec<DiffItem> {
    let mut items = Vec::new();
    let target_columns = NameLookup::new(&target.columns, |c| c.name.clone());
    let source_columns = NameLookup::new(&source.columns, |c| c.name.clone());
    for column in &source.columns {
        let Some(other) = target_columns.find(&column.name) else {
            items.push(DiffItem::ColumnAdded(column.clone()));
            continue;
        };
        let changes = column_changes(column, other);
        if !changes.is_empty() {
            let to = DesignColumn {
                name: other.name.clone(),
                original_name: Some(other.name.clone()),
                ..column.clone()
            };
            items.push(DiffItem::ColumnChanged {
                from: other.clone(),
                to,
                changes,
            });
        }
    }
    for c in target
        .columns
        .iter()
        .filter(|c| source_columns.find(&c.name).is_none())
    {
        items.push(DiffItem::ColumnRemoved(c.clone()));
    }

    if !same_names(&source.primary_key, &target.primary_key) {
        items.push(DiffItem::PrimaryKeyChanged {
            from: target.primary_key.clone(),
            to: source.primary_key.clone(),
        });
    }

    let target_indexes = NameLookup::new(&target.indexes, |i| i.name.clone());
    let source_indexes = NameLookup::new(&source.indexes, |i| i.name.clone());
    for index in &source.indexes {
        match target_indexes.find(&index.name) {
            None => items.push(DiffItem::IndexAdded(index.clone())),
            Some(other)
                if !same_names(&index.columns, &other.columns) || index.unique != other.unique =>
            {
                items.push(DiffItem::IndexChanged {
                    from: other.clone(),
                    to: IndexInfo {
                        name: other.name.clone(),
                        ..index.clone()
                    },
                });
            }
            Some(_) => {}
        }
    }
    for i in target
        .indexes
        .iter()
        .filter(|i| source_indexes.find(&i.name).is_none())
    {
        items.push(DiffItem::IndexRemoved(i.clone()));
    }

    let target_fks = NameLookup::new(&target.foreign_keys, foreign_key_identity);
    let source_fks = NameLookup::new(&source.foreign_keys, foreign_key_identity);
    for fk in &source.foreign_keys {
        match target_fks.find(&foreign_key_identity(fk)) {
            None => items.push(DiffItem::ForeignKeyAdded(fk.clone())),
            Some(other) if !same_foreign_key(fk, other) => {
                items.push(DiffItem::ForeignKeyChanged {
                    from: other.clone(),
                    to: ForeignKeyInfo {
                        name: other.name.clone(),
                        ..fk.clone()
                    },
                })
            }
            Some(_) => {}
        }
    }
    for fk in target
        .foreign_keys
        .iter()
        .filter(|f| source_fks.find(&foreign_key_identity(f)).is_none())
    {
        items.push(DiffItem::ForeignKeyRemoved(fk.clone()));
    }
    items
}

fn column_changes(source: &DesignColumn, target: &DesignColumn) -> Vec<String> {
    let mut changes = Vec::new();
    if column_types::normalize(&source.type_) != column_types::normalize(&target.type_) {
        changes.push(format!("type {} → {}", target.type_, source.type_));
    }
    if source.nullable != target.nullable {
        changes.push(
            if source.nullable {
                "allow NULL"
            } else {
                "NOT NULL"
            }
            .to_owned(),
        );
    }
    let source_default = column_types::normalize_default(source.default_value.as_deref());
    let target_default = column_types::normalize_default(target.default_value.as_deref());
    // Sequence defaults name each database's own sequence; they're the auto-increment, not a real difference.
    let sequences = source_default
        .as_deref()
        .is_some_and(|d| d.starts_with("nextval("))
        && target_default
            .as_deref()
            .is_some_and(|d| d.starts_with("nextval("));
    if source_default != target_default && !sequences {
        changes.push(format!(
            "default {} → {}",
            target.default_value.as_deref().unwrap_or("none"),
            source.default_value.as_deref().unwrap_or("none")
        ));
    }
    changes
}

/// The script for the `selected` differences (keys from [`SchemaComparison::default_selection`], [`TableDiff::id`]
/// and [`TableDiff::item_key`]), written by `generator` for the target.
pub fn migration(
    comparison: &SchemaComparison,
    selected: &HashSet<String>,
    generator: &DdlGenerator,
) -> DdlScript {
    let namespace = &comparison.target_namespace;
    let changes: Vec<TableChange> = comparison
        .diffs
        .iter()
        .filter_map(|diff| match diff.kind {
            TableDiffKind::Added => selected.contains(&diff.id()).then(|| {
                TableChange::new(
                    None,
                    Some(retarget(diff.source.as_ref().unwrap(), namespace)),
                )
            }),
            TableDiffKind::Removed => selected
                .contains(&diff.id())
                .then(|| TableChange::new(diff.target.clone(), None)),
            TableDiffKind::Changed => {
                let chosen: Vec<&DiffItem> = diff
                    .items
                    .iter()
                    .filter(|i| selected.contains(&diff.item_key(i)))
                    .collect();
                let target = diff.target.as_ref().unwrap();
                (!chosen.is_empty()).then(|| {
                    let applied = apply(target, &chosen, diff.source.as_ref().unwrap(), namespace);
                    TableChange::new(Some(target.clone()), Some(applied))
                })
            }
        })
        .collect();
    generator.script(&changes)
}

/// `target` with `items` applied; new columns go where the source has them, as far as the target allows.
fn apply(
    target: &TableDesign,
    items: &[&DiffItem],
    source: &TableDesign,
    namespace: &Namespace,
) -> TableDesign {
    let mut columns = target.columns.clone();
    let mut primary_key = target.primary_key.clone();
    let mut indexes = target.indexes.clone();
    let mut foreign_keys = target.foreign_keys.clone();
    for item in items {
        match item {
            DiffItem::ColumnAdded(column) => {
                // Kotlin's indexOf: -1 when absent, which `coerceAtMost` leaves and `add` rejects; source columns are
                // always found.
                let position = source
                    .columns
                    .iter()
                    .position(|c| c == column)
                    .unwrap_or(0)
                    .min(columns.len());
                columns.insert(
                    position,
                    DesignColumn {
                        original_name: None,
                        ..column.clone()
                    },
                );
            }
            DiffItem::ColumnRemoved(column) => columns.retain(|c| c.name != column.name),
            DiffItem::ColumnChanged { from, to, .. } => {
                for c in &mut columns {
                    if c.name == from.name {
                        *c = to.clone();
                    }
                }
            }
            DiffItem::PrimaryKeyChanged { to, .. } => primary_key = to.clone(),
            DiffItem::IndexAdded(index) => indexes.push(index.clone()),
            DiffItem::IndexRemoved(index) => indexes.retain(|i| i.name != index.name),
            DiffItem::IndexChanged { from, to } => {
                for i in &mut indexes {
                    if i.name == from.name {
                        *i = to.clone();
                    }
                }
            }
            DiffItem::ForeignKeyAdded(fk) => foreign_keys.push(retarget_fk(fk, source, namespace)),
            DiffItem::ForeignKeyRemoved(fk) => {
                let id = foreign_key_identity(fk);
                foreign_keys.retain(|f| foreign_key_identity(f) != id);
            }
            DiffItem::ForeignKeyChanged { from, to } => {
                let id = foreign_key_identity(from);
                for f in &mut foreign_keys {
                    if foreign_key_identity(f) == id {
                        *f = retarget_fk(to, source, namespace);
                    }
                }
            }
        }
    }
    TableDesign {
        columns,
        primary_key,
        indexes,
        foreign_keys,
        ..target.clone()
    }
}

/// A source table as it will be created in the target namespace, its foreign keys pointing there too.
fn retarget(design: &TableDesign, namespace: &Namespace) -> TableDesign {
    TableDesign {
        table: TableRef {
            namespace: namespace.clone(),
            name: design.table.name.clone(),
        },
        columns: design
            .columns
            .iter()
            .map(|c| DesignColumn {
                original_name: None,
                ..c.clone()
            })
            .collect(),
        foreign_keys: design
            .foreign_keys
            .iter()
            .map(|fk| retarget_fk(fk, design, namespace))
            .collect(),
        // Engine-specific: the target names its own primary key and doesn't get the source's triggers.
        primary_key_name: None,
        triggers: vec![],
        ..design.clone()
    }
}

fn retarget_fk(fk: &ForeignKeyInfo, source: &TableDesign, namespace: &Namespace) -> ForeignKeyInfo {
    if fk.referenced_table.namespace == source.table.namespace
        || fk.referenced_table.namespace.schema.is_none()
    {
        ForeignKeyInfo {
            referenced_table: TableRef {
                namespace: namespace.clone(),
                name: fk.referenced_table.name.clone(),
            },
            ..fk.clone()
        }
    } else {
        fk.clone()
    }
}

/// A box of the ER diagram: where it is and how big, in diagram units (1 unit = 1 dp at 100%).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ErBox {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl ErBox {
    pub fn right(&self) -> f32 {
        self.x + self.width
    }

    pub fn bottom(&self) -> f32 {
        self.y + self.height
    }

    pub fn contains(&self, px: f32, py: f32) -> bool {
        (self.x..=self.right()).contains(&px) && (self.y..=self.bottom()).contains(&py)
    }
}

/// A foreign key drawn as a line from `from` (the referencing table) to `to`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ErEdge {
    pub from: TableRef,
    pub from_columns: Vec<String>,
    pub to: TableRef,
    pub to_columns: Vec<String>,
    pub optional: bool,
}

/// Places tables in layers by their foreign keys: tables nothing else depends on at the left, the tables that
/// reference them to their right, so most lines run one way. Tables without any foreign key in or out are packed in a
/// grid below.
pub mod er_layout {
    use super::*;

    pub const HEADER_HEIGHT: f32 = 36.0;
    pub const ROW_HEIGHT: f32 = 22.0;
    /// Space between the header and the first row, and after the last.
    pub const ROW_PADDING: f32 = 4.0;
    /// Columns shown per table; the rest fold into a "… N more" row.
    pub const MAX_ROWS: usize = 18;

    const LAYER_GAP: f32 = 90.0;
    const BOX_GAP: f32 = 28.0;
    const MAX_COLUMN_HEIGHT: f32 = 1400.0;

    pub fn edges(tables: &[TableDetails]) -> Vec<ErEdge> {
        let mut known: HashMap<String, &TableDetails> = HashMap::new();
        for t in tables {
            known.insert(t.table.name.to_lowercase(), t);
        }
        let mut edges = Vec::new();
        for table in tables {
            for fk in &table.foreign_keys {
                let Some(target) = known.get(&fk.referenced_table.name.to_lowercase()) else {
                    continue;
                };
                let optional = fk.columns.iter().any(|name| {
                    table
                        .columns
                        .iter()
                        .find(|c| &c.name == name)
                        .is_none_or(|c| c.nullable)
                });
                edges.push(ErEdge {
                    from: table.table.clone(),
                    from_columns: fk.columns.clone(),
                    to: target.table.clone(),
                    to_columns: fk.referenced_columns.clone(),
                    optional,
                });
            }
        }
        edges
    }

    pub fn row_count(table: &TableDetails) -> usize {
        if table.columns.len() > MAX_ROWS {
            MAX_ROWS + 1
        } else {
            table.columns.len()
        }
    }

    pub fn height(table: &TableDetails) -> f32 {
        HEADER_HEIGHT + ROW_PADDING * 2.0 + row_count(table) as f32 * ROW_HEIGHT
    }

    /// Kotlin's `sumOf { (height(it) + BoxGap).toDouble() }.toFloat() - BoxGap`.
    fn column_height(column: &[&TableDetails]) -> f32 {
        column
            .iter()
            .map(|t| f64::from(height(t) + BOX_GAP))
            .sum::<f64>() as f32
            - BOX_GAP
    }

    /// Box positions for `tables`; `width` gives each table's box width (it depends on text measurement).
    pub fn layout(
        tables: &[TableDetails],
        width: impl Fn(&TableDetails) -> f32,
    ) -> HashMap<TableRef, ErBox> {
        let mut boxes = HashMap::new();
        if tables.is_empty() {
            return boxes;
        }
        let edges: Vec<ErEdge> = edges(tables)
            .into_iter()
            .filter(|e| e.from != e.to)
            .collect();
        let connected: HashSet<&TableRef> = edges
            .iter()
            .map(|e| &e.from)
            .chain(edges.iter().map(|e| &e.to))
            .collect();
        let mut parents: HashMap<&TableRef, Vec<&TableRef>> = HashMap::new();
        let mut children: HashMap<&TableRef, Vec<&TableRef>> = HashMap::new();
        for e in &edges {
            parents.entry(&e.from).or_default().push(&e.to);
            children.entry(&e.to).or_default().push(&e.from);
        }

        // Layer = length of the longest chain of references below a table; cycles are cut where they're found.
        fn depth<'a>(
            r: &'a TableRef,
            parents: &HashMap<&'a TableRef, Vec<&'a TableRef>>,
            layer: &mut HashMap<&'a TableRef, i32>,
            visiting: &mut HashSet<&'a TableRef>,
        ) -> i32 {
            if let Some(&l) = layer.get(r) {
                return l;
            }
            if !visiting.insert(r) {
                return 0;
            }
            let mut value: Option<i32> = None;
            for p in parents.get(r).map(Vec::as_slice).unwrap_or_default() {
                if *p != r {
                    let d = depth(p, parents, layer, visiting) + 1;
                    value = Some(value.map_or(d, |v| v.max(d)));
                }
            }
            let value = value.unwrap_or(0);
            visiting.remove(r);
            layer.insert(r, value);
            value
        }
        let mut layer: HashMap<&TableRef, i32> = HashMap::new();
        let mut visiting = HashSet::new();
        let graph_tables: Vec<&TableDetails> = tables
            .iter()
            .filter(|t| connected.contains(&t.table))
            .collect();
        for t in &graph_tables {
            depth(&t.table, &parents, &mut layer, &mut visiting);
        }
        // Pull referenced tables right, next to the closest table that references them, so their lines stay short
        // instead of crossing the layers in between. Children first, so each one is where it ends up.
        let mut by_layer: Vec<&TableRef> = graph_tables.iter().map(|t| &t.table).collect();
        by_layer.sort_by_key(|r| std::cmp::Reverse(layer[r]));
        for r in by_layer {
            let closest = children
                .get(r)
                .into_iter()
                .flatten()
                .filter(|c| **c != r)
                .map(|c| layer[c])
                .min();
            if let Some(closest) = closest
                && closest - 1 > layer[r]
            {
                layer.insert(r, closest - 1);
            }
        }

        let mut layers: BTreeMap<i32, Vec<&TableDetails>> = BTreeMap::new();
        for t in &graph_tables {
            layers.entry(layer[&t.table]).or_default().push(t);
        }
        // Order within a layer by the average position of what each table references, a few sweeps (barycenters).
        let mut order: HashMap<&TableRef, f32> = HashMap::new();
        for members in layers.values() {
            let mut sorted = members.clone();
            sorted.sort_by_key(|t| t.table.name.to_lowercase());
            for (i, t) in sorted.iter().enumerate() {
                order.insert(&t.table, i as f32);
            }
        }
        for _ in 0..3 {
            for members in layers.values() {
                let key = |table: &TableDetails| -> f64 {
                    let mut positions: Vec<f32> = Vec::new();
                    for n in parents.get(&table.table).into_iter().flatten() {
                        if let Some(&o) = order.get(n) {
                            positions.push(o);
                        }
                    }
                    for e in edges.iter().filter(|e| e.to == table.table) {
                        if let Some(&o) = order.get(&e.from) {
                            positions.push(o);
                        }
                    }
                    if positions.is_empty() {
                        f64::from(order[&table.table])
                    } else {
                        positions.iter().map(|&p| f64::from(p)).sum::<f64>()
                            / positions.len() as f64
                    }
                };
                let mut ranked: Vec<(f64, String, &TableDetails)> = members
                    .iter()
                    .map(|t| (key(t), t.table.name.to_lowercase(), *t))
                    .collect();
                ranked.sort_by(|a, b| a.0.total_cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
                for (i, (_, _, t)) in ranked.iter().enumerate() {
                    order.insert(&t.table, i as f32);
                }
            }
        }

        let mut x = 0f32;
        let mut graph_bottom = 0f32;
        let mut columns: Vec<Vec<&TableDetails>> = Vec::new();
        for members in layers.values() {
            // A tall layer wraps into several columns so the diagram stays roughly as wide as it is high.
            let mut sorted = members.clone();
            sorted.sort_by(|a, b| order[&a.table].total_cmp(&order[&b.table]));
            let mut current: Vec<&TableDetails> = Vec::new();
            let mut column_height = 0f32;
            for table in sorted {
                let h = height(table);
                if !current.is_empty() && column_height + h > MAX_COLUMN_HEIGHT {
                    columns.push(std::mem::take(&mut current));
                    column_height = 0.0;
                }
                current.push(table);
                column_height += h + BOX_GAP;
            }
            if !current.is_empty() {
                columns.push(current);
            }
        }
        let tallest = columns
            .iter()
            .map(|c| column_height(c))
            .fold(None, |m: Option<f32>, h| Some(m.map_or(h, |m| m.max(h))));
        let tallest = tallest.unwrap_or(0.0);
        for column in &columns {
            let column_width = column
                .iter()
                .map(|t| width(t))
                .fold(f32::NEG_INFINITY, f32::max);
            let total = column_height(column);
            let mut y = (tallest - total) / 2.0;
            for table in column {
                boxes.insert(
                    table.table.clone(),
                    ErBox {
                        x,
                        y,
                        width: column_width,
                        height: height(table),
                    },
                );
                y += height(table) + BOX_GAP;
            }
            graph_bottom = graph_bottom.max(y);
            x += column_width + LAYER_GAP;
        }

        // Unrelated tables: a grid under the graph (or on its own), shortest first so the rows line up.
        let mut loose: Vec<&TableDetails> = tables
            .iter()
            .filter(|t| !connected.contains(&t.table))
            .collect();
        loose.sort_by_key(|t| t.table.name.to_lowercase());
        if !loose.is_empty() {
            let mut per_row = ((loose.len() as f64 * 1.6).sqrt().ceil() as i32).max(1);
            if !boxes.is_empty() {
                per_row = per_row.max((x / 260.0) as i32);
            }
            let mut top = if boxes.is_empty() {
                0.0
            } else {
                graph_bottom + LAYER_GAP
            };
            for row in loose.chunks(per_row as usize) {
                let mut left = 0f32;
                for table in row {
                    let w = width(table);
                    boxes.insert(
                        table.table.clone(),
                        ErBox {
                            x: left,
                            y: top,
                            width: w,
                            height: height(table),
                        },
                    );
                    left += w + BOX_GAP * 1.5;
                }
                top += row
                    .iter()
                    .map(|t| height(t))
                    .fold(f32::NEG_INFINITY, f32::max)
                    + BOX_GAP * 1.5;
            }
        }
        boxes
    }

    /// The tables within one foreign key of `focus`, for a diagram around one table.
    pub fn neighbourhood<'a>(
        tables: &'a [TableDetails],
        focus: &TableRef,
    ) -> Vec<&'a TableDetails> {
        let edges = edges(tables);
        let mut near: HashSet<TableRef> = HashSet::new();
        for e in edges.iter().filter(|e| &e.from == focus || &e.to == focus) {
            near.insert(e.from.clone());
            near.insert(e.to.clone());
        }
        near.insert(focus.clone());
        tables.iter().filter(|t| near.contains(&t.table)).collect()
    }
}

/// Writes a Mermaid `erDiagram` of tables, for docs and READMEs.
pub mod mermaid {
    use super::*;

    static PLAIN_NAME: LazyLock<Regex> =
        LazyLock::new(|| Regex::new("^[A-Za-z_][A-Za-z0-9_-]*$").unwrap());
    static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r]+").unwrap());

    pub fn er_diagram(tables: &[TableDetails], dialect: SqlDialect) -> String {
        let mut out = String::from("erDiagram\n");
        let mut sorted: Vec<&TableDetails> = tables.iter().collect();
        sorted.sort_by_key(|t| t.table.name.to_lowercase());
        for table in sorted {
            let fk_columns: HashSet<&String> =
                table.foreign_keys.iter().flat_map(|f| &f.columns).collect();
            let unique_columns: HashSet<&String> = table
                .indexes
                .iter()
                .filter(|i| i.unique && i.columns.len() == 1)
                .map(|i| &i.columns[0])
                .collect();
            out.push_str(&format!("    {} {{\n", entity(&table.table.name)));
            for column in &table.columns {
                let keys: Vec<&str> = [
                    column.primary_key.then_some("PK"),
                    fk_columns.contains(&column.name).then_some("FK"),
                    (!column.primary_key && unique_columns.contains(&column.name)).then_some("UK"),
                ]
                .into_iter()
                .flatten()
                .collect();
                let type_ = column_types::declared_type(column, dialect).0;
                out.push_str(&format!(
                    "        {} {}",
                    token(&type_),
                    token(&column.name)
                ));
                if !keys.is_empty() {
                    out.push(' ');
                    out.push_str(&keys.join(", "));
                }
                if !column.nullable && !column.primary_key {
                    out.push_str(" \"not null\"");
                }
                out.push('\n');
            }
            out.push_str("    }\n");
        }
        for edge in er_layout::edges(tables) {
            // Parent side: exactly one, or zero-or-one when the key is nullable; child side: zero or more.
            let parent = if edge.optional { "|o" } else { "||" };
            out.push_str(&format!(
                "    {} {parent}--o{{ {} : \"{}\"\n",
                entity(&edge.to.name),
                entity(&edge.from.name),
                edge.from_columns.join(", ").replace('"', "'")
            ));
        }
        format!("{}\n", kt::trim_end(&out))
    }

    /// Entity names outside Mermaid's plain-word syntax are quoted.
    fn entity(name: &str) -> String {
        if PLAIN_NAME.is_match(name) {
            name.to_owned()
        } else {
            format!("\"{}\"", name.replace('"', "'"))
        }
    }

    /// Attribute types and names are single tokens: spaces and commas would end them early.
    fn token(text: &str) -> String {
        let t = SPACES
            .replace_all(kt::trim(text), "_")
            .replace(',', "-")
            .replace('"', "");
        if t.is_empty() {
            "unknown".to_owned()
        } else {
            t
        }
    }
}

//! The engine-independent half of `metadata/Introspector.kt`: how rows that JDBC `DatabaseMetaData` returned become
//! [`TableDetails`]. Each engine's catalog queries produce these rows; the grouping and ordering live here.

use tg_sql::model::{ColumnInfo, ForeignKeyInfo, IndexInfo, TableDetails, TableRef};

/// One row of `getIndexInfo`: index name (None for table statistics), key position, column (None for expressions on
/// some drivers), uniqueness.
#[derive(Clone, Debug)]
pub struct IndexRow {
    pub name: Option<String>,
    pub position: i32,
    pub column: Option<String>,
    pub unique: bool,
}

/// One row of `getImportedKeys`.
#[derive(Clone, Debug)]
pub struct ForeignKeyRow {
    pub name: String,
    pub sequence: i32,
    pub column: String,
    pub target: TableRef,
    pub target_column: String,
    pub on_update: Option<String>,
    pub on_delete: Option<String>,
}

/// JDBC's `importedKeyCascade`… codes as the referential action they name. `NO ACTION` and `RESTRICT` are what every
/// engine does by default, so they map to None.
pub fn referential_action(code: Option<i32>) -> Option<String> {
    match code? {
        0 => Some("CASCADE".into()),
        2 => Some("SET NULL".into()),
        4 => Some("SET DEFAULT".into()),
        _ => None,
    }
}

/// Indexes grouped by name in the order first seen, each with its columns by key position.
pub fn group_indexes(rows: Vec<IndexRow>) -> Vec<IndexInfo> {
    type Group = (String, Vec<(i32, Option<String>)>, bool);
    let mut groups: Vec<Group> = Vec::new();
    for row in rows {
        let Some(name) = row.name else { continue };
        match groups.iter_mut().find(|g| g.0 == name) {
            Some(g) => g.1.push((row.position, row.column)),
            None => groups.push((name, vec![(row.position, row.column)], row.unique)),
        }
    }
    groups
        .into_iter()
        .map(|(name, mut columns, unique)| {
            columns.sort_by_key(|c| c.0);
            IndexInfo {
                name,
                columns: columns.into_iter().filter_map(|c| c.1).collect(),
                unique,
            }
        })
        .collect()
}

/// Foreign keys grouped by name and target in the order first seen, columns by key sequence. SQLite names no foreign
/// key: grouping by name alone would merge a table's keys to different tables.
pub fn group_foreign_keys(rows: Vec<ForeignKeyRow>) -> Vec<ForeignKeyInfo> {
    let mut groups: Vec<Vec<ForeignKeyRow>> = Vec::new();
    for row in rows {
        match groups
            .iter_mut()
            .find(|g| g[0].name == row.name && g[0].target == row.target)
        {
            Some(g) => g.push(row),
            None => groups.push(vec![row]),
        }
    }
    groups
        .into_iter()
        .map(|mut rows| {
            rows.sort_by_key(|r| r.sequence);
            let first = rows[0].clone();
            ForeignKeyInfo {
                name: first.name,
                columns: rows.iter().map(|r| r.column.clone()).collect(),
                referenced_table: first.target,
                referenced_columns: rows.iter().map(|r| r.target_column.clone()).collect(),
                on_update: first.on_update,
                on_delete: first.on_delete,
            }
        })
        .collect()
}

/// Assembles details from columns (with their ordinal positions) and the key rows.
pub fn details(
    table: &TableRef,
    mut columns: Vec<(i32, ColumnInfo)>,
    primary_key: &[(String, Option<String>)],
    indexes: Vec<IndexRow>,
    foreign_keys: Vec<ForeignKeyRow>,
) -> TableDetails {
    columns.sort_by_key(|c| c.0);
    let columns = columns
        .into_iter()
        .map(|(_, mut c)| {
            c.primary_key = primary_key.iter().any(|(name, _)| *name == c.name);
            c
        })
        .collect();
    TableDetails {
        table: table.clone(),
        columns,
        indexes: group_indexes(indexes),
        foreign_keys: group_foreign_keys(foreign_keys),
        primary_key_name: primary_key.iter().find_map(|(_, name)| name.clone()),
    }
}

/// One row of `getImportedKeys`/`getExportedKeys` as `relations` reads it.
#[derive(Clone, Debug)]
pub struct RelationRow {
    pub name: String,
    pub sequence: i32,
    pub from: TableRef,
    pub from_column: String,
    pub to: TableRef,
    pub to_column: String,
}

/// Relations grouped by (from, name, to), columns by key sequence, duplicates removed (a self-reference shows up as
/// both imported and exported).
pub fn group_relations(rows: Vec<RelationRow>) -> Vec<tg_sql::model::TableRelation> {
    let mut groups: Vec<Vec<RelationRow>> = Vec::new();
    for row in rows {
        match groups
            .iter_mut()
            .find(|g| g[0].from == row.from && g[0].name == row.name && g[0].to == row.to)
        {
            Some(g) => g.push(row),
            None => groups.push(vec![row]),
        }
    }
    let mut out: Vec<tg_sql::model::TableRelation> = Vec::new();
    for mut rows in groups {
        rows.sort_by_key(|r| r.sequence);
        let relation = tg_sql::model::TableRelation {
            from_table: rows[0].from.clone(),
            from_columns: rows.iter().map(|r| r.from_column.clone()).collect(),
            to_table: rows[0].to.clone(),
            to_columns: rows.iter().map(|r| r.to_column.clone()).collect(),
        };
        if !out.contains(&relation) {
            out.push(relation);
        }
    }
    out
}

/// `columnsOf`: columns grouped by table in the order tables first appear, each table's by ordinal position.
pub fn group_columns(rows: Vec<(String, i32, ColumnInfo)>) -> Vec<(String, Vec<ColumnInfo>)> {
    let mut groups: Vec<(String, Vec<(i32, ColumnInfo)>)> = Vec::new();
    for (table, position, column) in rows {
        match groups.iter_mut().find(|g| g.0 == table) {
            Some(g) => g.1.push((position, column)),
            None => groups.push((table, vec![(position, column)])),
        }
    }
    groups
        .into_iter()
        .map(|(t, mut c)| {
            c.sort_by_key(|x| x.0);
            (t, c.into_iter().map(|x| x.1).collect())
        })
        .collect()
}

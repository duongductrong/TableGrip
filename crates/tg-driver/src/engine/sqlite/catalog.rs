//! SQLite's catalog as sqlite-jdbc 3.50's `DatabaseMetaData` reported it to `Introspector.kt`: the same pragmas, the
//! same type mapping, and the same regex-based primary and foreign key names (quirks included, since the golden files
//! hold them). See the S3 log for the behaviours this keeps on purpose.

use super::error;
use crate::error::{DriverError, QueryError, Result};
use crate::introspect::{self, ForeignKeyRow, IndexRow, RelationRow};
use regex::{Regex, RegexBuilder};
use rusqlite::Connection;
use rusqlite::types::Value;
use std::sync::LazyLock;
use tg_sql::model::jdbc_types as types;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
};

fn root() -> Namespace {
    Namespace::new(None, None)
}

/// Rows of a query as dynamically typed values.
fn rows(conn: &Connection, sql: &str, params: &[&str]) -> Result<Vec<Vec<Value>>> {
    let mut stmt = conn.prepare(sql).map_err(error)?;
    let n = stmt.column_count();
    let mapped = stmt
        .query_map(rusqlite::params_from_iter(params.iter()), |r| {
            (0..n).map(|i| r.get::<_, Value>(i)).collect()
        })
        .map_err(error)?;
    mapped.collect::<std::result::Result<_, _>>().map_err(error)
}

fn text(v: &Value) -> Option<String> {
    match v {
        Value::Null => None,
        Value::Integer(i) => Some(i.to_string()),
        Value::Real(r) => Some(super::values::real_text(*r)),
        Value::Text(t) => Some(t.clone()),
        Value::Blob(b) => Some(String::from_utf8_lossy(b).into_owned()),
    }
}

fn int(v: &Value) -> i64 {
    match v {
        Value::Integer(i) => *i,
        other => text(other).and_then(|t| t.trim().parse().ok()).unwrap_or(0),
    }
}

/// The literal sqlite-jdbc builds for a pragma argument: `'` doubled.
fn lit(name: &str) -> String {
    format!("'{}'", name.replace('\'', "''"))
}

/// Objects `getTables` lists, with their `TABLE_TYPE`, before any pattern or type filter.
fn all_objects(conn: &Connection) -> Result<Vec<(String, String)>> {
    let sql = "SELECT name, type FROM (\
          SELECT 'sqlite_schema' AS name, 'SYSTEM TABLE' AS type \
          UNION ALL SELECT name, UPPER(type) FROM sqlite_schema \
            WHERE name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(type) IN ('TABLE', 'VIEW') \
          UNION ALL SELECT name, 'GLOBAL TEMPORARY' FROM sqlite_temp_master \
          UNION ALL SELECT name, 'SYSTEM TABLE' FROM sqlite_schema WHERE name LIKE 'sqlite\\_%' ESCAPE '\\') \
        ORDER BY type, name";
    Ok(rows(conn, sql, &[])?
        .iter()
        .map(|r| {
            (
                text(&r[0]).unwrap_or_default(),
                text(&r[1]).unwrap_or_default(),
            )
        })
        .collect())
}

pub fn tables(conn: &Connection, ns: &Namespace) -> Result<Vec<TableInfo>> {
    Ok(all_objects(conn)?
        .into_iter()
        .filter(|(_, t)| t == "TABLE" || t == "VIEW")
        .map(|(name, t)| TableInfo {
            table: TableRef::new(ns.clone(), &name),
            is_view: t == "VIEW",
            remarks: None,
        })
        .collect())
}

/// SQLite's ASCII-only case folding, as `LIKE` and pragma lookups use it.
fn ascii_eq(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

/// `getColumns` rows for the objects whose name matches `table` (all of them when None): table name, ordinal,
/// column.
fn column_rows(conn: &Connection, table: Option<&str>) -> Result<Vec<(String, i32, ColumnInfo)>> {
    let mut out = Vec::new();
    for (name, _) in all_objects(conn)?
        .into_iter()
        .filter(|(n, _)| table.is_none_or(|t| ascii_eq(n, t)))
    {
        if name.contains('\'') {
            // sqlite-jdbc splices the name into SQL unescaped, so the whole call failed.
            return Err(DriverError::Query(QueryError::new(format!(
                "[SQLITE_ERROR] SQL error or missing database (near \"{name}\": syntax error)"
            ))));
        }
        let auto = rows(
            conn,
            "SELECT LIKE('%autoincrement%', LOWER(sql)) FROM sqlite_schema WHERE LOWER(name) = LOWER(?) AND type IN ('table', 'view')",
            &[&name],
        )?
        .first()
        .is_some_and(|r| int(&r[0]) == 1);
        let info = rows(conn, &format!("PRAGMA table_xinfo({})", lit(&name)), &[])?;
        for (i, r) in info.iter().enumerate() {
            let column_name = text(&r[1]).unwrap_or_default();
            let declared = text(&r[2]).map_or_else(|| "TEXT".to_owned(), |t| t.to_uppercase());
            let not_null = text(&r[3]);
            let default_value = text(&r[4]);
            let pk = text(&r[5]);
            let (jdbc_type, type_name, size, digits) = column_type(&declared);
            out.push((
                name.clone(),
                i as i32 + 1,
                ColumnInfo {
                    name: column_name,
                    type_name,
                    size: Some(size),
                    // NULLABLE is 1 for notnull "0", 2 for none (never), else 0; Kotlin reads `!= columnNoNulls`.
                    nullable: not_null.as_deref().is_none_or(|n| n == "0"),
                    default_value,
                    auto_increment: auto && pk.as_deref() == Some("1"),
                    primary_key: false,
                    jdbc_type,
                    scale: Some(digits),
                },
            ));
        }
    }
    // ORDER BY TABLE_SCHEM, TABLE_NAME, ORDINAL_POSITION (BINARY).
    out.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));
    Ok(out)
}

static TYPE_INTEGER: LazyLock<Regex> = LazyLock::new(|| Regex::new("INT|BOOL").unwrap());
static TYPE_VARCHAR: LazyLock<Regex> = LazyLock::new(|| Regex::new("CHAR|CLOB|TEXT|BLOB").unwrap());
static TYPE_FLOAT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("REAL|FLOA|DOUB|DEC|NUM").unwrap());

/// DATA_TYPE, TYPE_NAME, COLUMN_SIZE and DECIMAL_DIGITS from an upper-cased declared type.
fn column_type(t: &str) -> (i32, String, i32, i32) {
    let mut size: i32 = 2_000_000_000;
    let mut digits: i32 = 10;
    let jdbc = if TYPE_INTEGER.is_match(t) {
        digits = 0;
        types::INTEGER
    } else if TYPE_VARCHAR.is_match(t) {
        digits = 0;
        types::VARCHAR
    } else if TYPE_FLOAT.is_match(t) {
        types::FLOAT
    } else {
        types::VARCHAR
    };
    let mut name = t.to_owned();
    if let Some(p) = t.find('(').filter(|&p| p > 0) {
        if let Some(e) = t[p..].find(')').map(|e| e + p) {
            let (whole, fraction) = match t[p..].find(',').map(|k| k + p).filter(|&k| k < e) {
                Some(k) => (&t[p + 1..k], Some(&t[k + 1..e])),
                None => (&t[p + 1..e], None),
            };
            let parse = |s: &str| {
                s.trim()
                    .trim_start_matches('+')
                    .parse::<u32>()
                    .ok()
                    .map(|v| v as i32)
            };
            if let Some(w) = parse(whole) {
                match fraction.map(parse) {
                    Some(Some(f)) => {
                        digits = f;
                        size = w.wrapping_add(f);
                    }
                    Some(None) => {}
                    None => {
                        digits = 0;
                        size = w;
                    }
                }
            }
        }
        name = t[..p].trim().to_owned();
    }
    (jdbc, name, size, digits)
}

/// `unquoteIdentifier`: trimmed, then one wrapping pair of `` ` ``, `"` or `[]` removed.
fn unquote_identifier(s: &str) -> String {
    let t = s.trim_matches(|c: char| c <= ' ');
    let wrapped = t.len() > 2
        && ((t.starts_with('`') && t.ends_with('`'))
            || (t.starts_with('"') && t.ends_with('"'))
            || (t.starts_with('[') && t.ends_with(']')));
    if wrapped {
        t[1..t.len() - 1].to_owned()
    } else {
        t.to_owned()
    }
}

fn pattern(p: &str) -> Regex {
    RegexBuilder::new(p)
        .case_insensitive(true)
        .dot_matches_new_line(true)
        .build()
        .unwrap()
}

static PK_NAMED: LazyLock<Regex> =
    LazyLock::new(|| pattern(r".*CONSTRAINT\s*(.*?)\s*PRIMARY\s+KEY\s*\((.*?)\).*"));
static PK_UNNAMED: LazyLock<Regex> = LazyLock::new(|| pattern(r".*PRIMARY\s+KEY\s*\((.*?)\).*"));
static FK_NAMED: LazyLock<Regex> = LazyLock::new(|| {
    RegexBuilder::new(r#"CONSTRAINT\s*"?([A-Za-z_][A-Za-z\d_]*)?"?\s*FOREIGN\s+KEY\s*\((.*?)\)"#)
        .case_insensitive(true)
        .build()
        .unwrap()
});

/// `PrimaryKeyFinder`: the primary key's name and columns (in declaration order), or None without one.
struct PrimaryKey {
    name: Option<String>,
    columns: Vec<String>,
}

fn primary_key(conn: &Connection, table: &str) -> Result<Option<PrimaryKey>> {
    if table == "sqlite_schema" || table == "sqlite_master" {
        return Ok(None);
    }
    let sql = rows(
        conn,
        "SELECT sql FROM sqlite_schema WHERE LOWER(name) = LOWER(?) AND type IN ('table', 'view')",
        &[table],
    )?;
    let Some(create) = sql.first().map(|r| text(&r[0]).unwrap_or_default()) else {
        return Err(DriverError::Query(QueryError::new(format!(
            "Table not found: '{table}'"
        ))));
    };
    let (name, columns) = if let Some(c) = PK_NAMED.captures(&create) {
        (
            Some(unquote_identifier(&c[1])),
            c[2].split(',').map(str::to_owned).collect::<Vec<_>>(),
        )
    } else if let Some(c) = PK_UNNAMED.captures(&create) {
        (None, c[1].split(',').map(str::to_owned).collect())
    } else {
        let info = rows(conn, &format!("PRAGMA table_info({})", lit(table)), &[])?;
        let last = info
            .iter()
            .rfind(|r| int(&r[5]) != 0)
            .and_then(|r| text(&r[1]));
        match last {
            Some(c) => (None, vec![c]),
            None => return Ok(None),
        }
    };
    Ok(Some(PrimaryKey {
        name,
        columns: columns.iter().map(|c| unquote_identifier(c)).collect(),
    }))
}

/// `ImportedKeyFinder`: each foreign key's pragma id and name ('' when it has none).
fn foreign_key_names(conn: &Connection, table: &str) -> Result<Vec<(i64, Option<String>)>> {
    let create = rows(
        conn,
        "SELECT sql FROM sqlite_schema WHERE LOWER(name) = LOWER(?)",
        &[table],
    )?
    .first()
    .and_then(|r| text(&r[0]))
    .unwrap_or_default();
    let mut names: Vec<Option<String>> = FK_NAMED
        .captures_iter(&create)
        .map(|c| c.get(1).map(|m| m.as_str().to_owned()))
        .collect();
    names.reverse();
    let list = rows(
        conn,
        &format!("PRAGMA foreign_key_list({})", lit(&table.to_lowercase())),
        &[],
    )?;
    let mut ids: Vec<i64> = Vec::new();
    for r in &list {
        let id = int(&r[0]);
        if ids.last() != Some(&id) {
            ids.push(id);
        }
    }
    Ok(ids
        .into_iter()
        .enumerate()
        .map(|(count, id)| (id, names.get(count).cloned().flatten()))
        .collect())
}

fn rule(v: &Value) -> Option<i32> {
    match text(v)?.as_str() {
        "NO ACTION" => Some(3),
        "CASCADE" => Some(0),
        "RESTRICT" => Some(1),
        "SET NULL" => Some(2),
        "SET DEFAULT" => Some(4),
        _ => None,
    }
}

/// One `getImportedKeys` row.
struct Imported {
    parent: String,
    parent_column: Option<String>,
    column: String,
    seq: i32,
    on_update: Option<i32>,
    on_delete: Option<i32>,
    name: String,
}

fn imported_keys(conn: &Connection, table: &str) -> Result<Vec<Imported>> {
    if table.contains('\'') {
        return Err(DriverError::Query(QueryError::new(
            "[SQLITE_ERROR] SQL error or missing database (syntax error)",
        )));
    }
    let list = rows(
        conn,
        &format!("PRAGMA foreign_key_list({})", lit(table)),
        &[],
    )?;
    if list.is_empty() {
        return Ok(Vec::new());
    }
    let names = foreign_key_names(conn, table)?;
    let mut out = Vec::new();
    for r in &list {
        let id = int(&r[0]);
        let parent = text(&r[2]).unwrap_or_default();
        let parent_column = match text(&r[4]) {
            Some(c) => Some(c),
            None => {
                let pk = primary_key(conn, &parent).ok().flatten();
                Some(
                    pk.and_then(|p| p.columns.into_iter().next())
                        .ok_or_else(|| {
                            DriverError::Query(QueryError::new(format!(
                                "No primary key on {parent} for a foreign key of {table}"
                            )))
                        })?,
                )
            }
        };
        out.push(Imported {
            parent,
            parent_column,
            column: text(&r[3]).unwrap_or_default(),
            seq: int(&r[1]) as i32 + 1,
            on_update: rule(&r[5]),
            on_delete: rule(&r[6]),
            name: usize::try_from(id)
                .ok()
                .and_then(|i| names.get(i))
                .and_then(|n| n.1.clone())
                .unwrap_or_default(),
        });
    }
    // ORDER BY PKTABLE_NAME, KEY_SEQ; ties keep pragma order.
    out.sort_by(|a, b| a.parent.cmp(&b.parent).then(a.seq.cmp(&b.seq)));
    Ok(out)
}

pub fn details(
    conn: &Connection,
    table: &TableRef,
    keys_and_indexes: bool,
) -> Result<TableDetails> {
    let mut pk_rows: Vec<(String, Option<String>)> = match primary_key(conn, &table.name)? {
        Some(pk) => pk
            .columns
            .into_iter()
            .map(|c| (c, pk.name.clone()))
            .collect(),
        None => Vec::new(),
    };
    pk_rows.sort_by(|a, b| a.0.cmp(&b.0));
    let columns: Vec<(i32, ColumnInfo)> = column_rows(conn, Some(&table.name))?
        .into_iter()
        .map(|(_, position, c)| (position, c))
        .collect();
    if !keys_and_indexes {
        return Ok(introspect::details(
            table,
            columns,
            &pk_rows,
            Vec::new(),
            Vec::new(),
        ));
    }
    let mut indexes = Vec::new();
    for index in rows(
        conn,
        &format!("PRAGMA index_list({})", lit(&table.name)),
        &[],
    )? {
        let name = text(&index[1]).unwrap_or_default();
        let unique = int(&index[2]) == 1;
        for column in rows(conn, &format!("PRAGMA index_info({})", lit(&name)), &[])? {
            indexes.push(IndexRow {
                name: Some(name.clone()),
                position: int(&column[0]) as i32 + 1,
                column: text(&column[2]),
                unique,
            });
        }
    }
    let foreign_keys = imported_keys(conn, &table.name)?
        .into_iter()
        .map(|k| ForeignKeyRow {
            name: k.name,
            sequence: k.seq,
            column: k.column,
            target: TableRef::new(root(), &k.parent),
            target_column: k.parent_column.unwrap_or_default(),
            on_update: introspect::referential_action(k.on_update),
            on_delete: introspect::referential_action(k.on_delete),
        })
        .collect();
    Ok(introspect::details(
        table,
        columns,
        &pk_rows,
        indexes,
        foreign_keys,
    ))
}

pub fn columns_of(conn: &Connection, _ns: &Namespace) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    Ok(introspect::group_columns(column_rows(conn, None)?))
}

pub fn relations(conn: &Connection, table: &TableRef) -> Result<Vec<TableRelation>> {
    let rows_out: Vec<RelationRow> = imported_keys(conn, &table.name)?
        .into_iter()
        .map(|k| RelationRow {
            name: k.name,
            sequence: k.seq,
            from: TableRef::new(root(), &table.name),
            from_column: k.column,
            to: TableRef::new(root(), &k.parent),
            to_column: k.parent_column.unwrap_or_default(),
        })
        .collect();
    // Kotlin groups each call's rows on their own, then drops duplicates (a self-reference is in both).
    let mut out = introspect::group_relations(rows_out);
    for r in introspect::group_relations(exported_keys(conn, &table.name)?) {
        if !out.contains(&r) {
            out.push(r);
        }
    }
    Ok(out)
}

/// `getExportedKeys`: foreign keys of every table that point at `table`.
fn exported_keys(conn: &Connection, table: &str) -> Result<Vec<RelationRow>> {
    if primary_key(conn, table)?.is_none() {
        return Ok(Vec::new());
    }
    let all: Vec<String> = rows(
        conn,
        "SELECT name FROM sqlite_schema WHERE type = 'table'",
        &[],
    )?
    .iter()
    .filter_map(|r| text(&r[0]))
    .collect();
    let target = all
        .iter()
        .rfind(|n| n.to_lowercase() == table.to_lowercase())
        .cloned();
    let Some(target) = target else {
        return Ok(Vec::new());
    };
    let mut out: Vec<RelationRow> = Vec::new();
    for tbl in &all {
        let names = foreign_key_names(conn, tbl)?;
        let list = rows(
            conn,
            &format!("PRAGMA foreign_key_list({})", lit(&tbl.to_lowercase())),
            &[],
        )?;
        for (id, name) in &names {
            let keys: Vec<&Vec<Value>> = list.iter().filter(|r| int(&r[0]) == *id).collect();
            let Some(first) = keys.first() else { continue };
            if text(&first[2]).unwrap_or_default().to_lowercase() != target.to_lowercase() {
                continue;
            }
            for (j, k) in keys.iter().enumerate() {
                out.push(RelationRow {
                    name: name.clone().unwrap_or_default(),
                    sequence: j as i32 + 1,
                    from: TableRef::new(root(), tbl),
                    from_column: text(&k[3]).unwrap_or_default(),
                    to: TableRef::new(root(), &target),
                    to_column: text(&k[4]).unwrap_or_default(),
                });
            }
        }
    }
    // ORDER BY FKTABLE_NAME, KEY_SEQ; ties keep input order.
    out.sort_by(|a, b| {
        a.from
            .name
            .cmp(&b.from.name)
            .then(a.sequence.cmp(&b.sequence))
    });
    Ok(out)
}

pub fn structure(conn: &Connection, details: TableDetails) -> Result<TableStructure> {
    super::structure::read(conn, details)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn column_types_like_sqlite_jdbc() {
        assert_eq!(
            column_type("INTEGER"),
            (4, "INTEGER".into(), 2_000_000_000, 0)
        );
        assert_eq!(column_type("VARCHAR ( 10 )"), (12, "VARCHAR".into(), 10, 0));
        assert_eq!(column_type("DECIMAL(10, 2)"), (6, "DECIMAL".into(), 12, 2));
        assert_eq!(
            column_type("DOUBLE PRECISION"),
            (6, "DOUBLE PRECISION".into(), 2_000_000_000, 10)
        );
        assert_eq!(column_type(""), (12, "".into(), 2_000_000_000, 10));
        assert_eq!(
            column_type("FLOATING POINT"),
            (4, "FLOATING POINT".into(), 2_000_000_000, 0)
        );
        assert_eq!(
            column_type("VARCHAR(ABC)"),
            (12, "VARCHAR".into(), 2_000_000_000, 0)
        );
    }

    #[test]
    fn unquotes_like_sqlite_jdbc() {
        assert_eq!(unquote_identifier(" \"x y\" "), "x y");
        assert_eq!(unquote_identifier("[pk]"), "pk");
        assert_eq!(unquote_identifier("\"a\"\"b\""), "a\"\"b");
        assert_eq!(unquote_identifier("'pk'"), "'pk'");
        assert_eq!(unquote_identifier("\"\""), "\"\"");
    }
}

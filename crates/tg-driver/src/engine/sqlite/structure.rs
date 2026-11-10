//! `SQLiteStructure` from `StructureReader.kt`: constraints, triggers and the DDL SQLite keeps in `sqlite_master`.

use super::error;
use crate::error::Result;
use crate::structure::{column_list, foreign_key_definition, statements};
use regex::Regex;
use rusqlite::Connection;
use std::sync::LazyLock;
use tg_sql::model::{
    ConstraintInfo, ConstraintKind, TableDetails, TableRef, TableStructure, TriggerInfo,
};
use tg_sql::{SqlDialect, SqlLexer, TokenType};

const D: SqlDialect = SqlDialect::SQLite;

pub fn read(conn: &Connection, details: TableDetails) -> Result<TableStructure> {
    let constraints = constraints(conn, &details)?;
    let triggers = triggers(conn, &details.table)?;
    let ddl = ddl(conn, &details.table)?;
    Ok(TableStructure {
        details,
        constraints,
        triggers,
        ddl,
    })
}

fn strings(conn: &Connection, sql: &str, param: &str) -> Result<Vec<Option<String>>> {
    let mut stmt = conn.prepare(sql).map_err(error)?;
    let rows = stmt
        .query_map([param], |r| r.get::<_, Option<String>>(0))
        .map_err(error)?;
    rows.collect::<std::result::Result<_, _>>().map_err(error)
}

fn constraints(conn: &Connection, details: &TableDetails) -> Result<Vec<ConstraintInfo>> {
    let table = &details.table;
    let mut out = Vec::new();
    let primary_key: Vec<String> = details
        .primary_key()
        .into_iter()
        .map(str::to_owned)
        .collect();
    if !primary_key.is_empty() {
        out.push(ConstraintInfo {
            name: String::new(),
            kind: ConstraintKind::PrimaryKey,
            definition: format!("PRIMARY KEY {}", column_list(D, &primary_key)),
            columns: primary_key,
        });
    }
    // Only indexes created by a UNIQUE constraint (origin "u"), not CREATE UNIQUE INDEX.
    let mut stmt = conn
        .prepare(&format!(
            "PRAGMA index_list({})",
            D.quote_identifier(&table.name)
        ))
        .map_err(error)?;
    let unique_indexes: Vec<String> = stmt
        .query_map([], |r| {
            Ok((r.get::<_, String>("name")?, r.get::<_, String>("origin")?))
        })
        .map_err(error)?
        .filter_map(|r| r.ok())
        .filter(|(_, origin)| origin == "u")
        .map(|(name, _)| name)
        .collect();
    for index in details
        .indexes
        .iter()
        .filter(|i| unique_indexes.contains(&i.name))
    {
        out.push(ConstraintInfo {
            name: String::new(),
            kind: ConstraintKind::Unique,
            columns: index.columns.clone(),
            definition: format!("UNIQUE {}", column_list(D, &index.columns)),
        });
    }
    for fk in &details.foreign_keys {
        // The driver invents names for unnamed foreign keys; SQLite itself has none.
        out.push(ConstraintInfo {
            name: String::new(),
            kind: ConstraintKind::ForeignKey,
            columns: fk.columns.clone(),
            definition: foreign_key_definition(D, table, fk),
        });
    }
    let create = strings(
        conn,
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
        &table.name,
    )?;
    if let Some(Some(sql)) = create.into_iter().next() {
        out.extend(check_constraints(&sql));
    }
    Ok(out)
}

static TIMING: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\b(BEFORE|AFTER|INSTEAD\s+OF)\b").unwrap());
static EVENT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\b(INSERT|UPDATE|DELETE)\b").unwrap());
static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

fn triggers(conn: &Connection, table: &TableRef) -> Result<Vec<TriggerInfo>> {
    let mut stmt = conn
        .prepare("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ? ORDER BY name")
        .map_err(error)?;
    let rows: Vec<(String, String)> = stmt
        .query_map([&table.name], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(error)?
        .collect::<std::result::Result<_, _>>()
        .map_err(error)?;
    Ok(rows
        .into_iter()
        .map(|(name, sql)| {
            let header = sql.split(" BEGIN").next().unwrap_or("");
            let header = header.split("\nBEGIN").next().unwrap_or("");
            let timing = TIMING
                .find(header)
                .map_or("BEFORE".to_owned(), |m| m.as_str().to_uppercase());
            let events = EVENT
                .find(header)
                .map_or(String::new(), |m| m.as_str().to_uppercase());
            TriggerInfo {
                name,
                timing: SPACES.replace_all(&timing, " ").into_owned(),
                events,
                enabled: true,
                definition: sql,
            }
        })
        .collect())
}

fn ddl(conn: &Connection, table: &TableRef) -> Result<String> {
    let objects: Vec<String> = strings(
        conn,
        "SELECT sql FROM sqlite_master WHERE tbl_name = ? AND sql IS NOT NULL \
         ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 0 WHEN 'index' THEN 1 ELSE 2 END, name",
        &table.name,
    )?
    .into_iter()
    .flatten()
    .collect();
    Ok(statements(&[&objects]))
}

/// SQLite keeps CHECK constraints only in the CREATE TABLE text, so they are read back from it.
pub fn check_constraints(create_sql: &str) -> Vec<ConstraintInfo> {
    let tokens: Vec<_> = SqlLexer::new(D.syntax())
        .tokenize(create_sql)
        .into_iter()
        .filter(|t| t.kind != TokenType::Whitespace && t.kind != TokenType::Comment)
        .collect();
    let text = |i: isize| -> Option<&str> {
        usize::try_from(i)
            .ok()
            .and_then(|i| tokens.get(i))
            .map(|t| t.text(create_sql))
    };
    let mut checks = Vec::new();
    for i in 0..tokens.len() as isize {
        if !text(i).is_some_and(|t| t.eq_ignore_ascii_case("CHECK")) || text(i + 1) != Some("(") {
            continue;
        }
        let mut depth = 0;
        let mut end = i + 1;
        while (end as usize) < tokens.len() {
            match text(end) {
                Some("(") => depth += 1,
                Some(")") => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
                _ => {}
            }
            end += 1;
        }
        if end as usize >= tokens.len() {
            continue;
        }
        let name = if text(i - 2).is_some_and(|t| t.eq_ignore_ascii_case("CONSTRAINT")) {
            text(i - 1).map(unquote).unwrap_or_default()
        } else {
            String::new()
        };
        let body = &create_sql[tokens[(i + 1) as usize].start..tokens[end as usize].end];
        checks.push(ConstraintInfo {
            name,
            kind: ConstraintKind::Check,
            columns: Vec::new(),
            definition: format!("CHECK {body}"),
        });
    }
    checks
}

fn unquote(identifier: &str) -> String {
    let mut chars = identifier.chars();
    match chars.next() {
        Some('"' | '`' | '[') if identifier.chars().count() >= 2 => {
            let inner: String = identifier.chars().skip(1).collect();
            inner[..inner.len() - inner.chars().last().map_or(0, char::len_utf8)].to_owned()
        }
        _ => identifier.to_owned(),
    }
}

//! ClickHouse's catalog: databases as namespaces (ClickHouse JDBC v2 reports them as schemas), tables and columns
//! from `system.tables` and `system.columns`, the primary key from `system.tables.primary_key` (as the driver's
//! `getPrimaryKeys` reads it), no indexes or foreign keys, and `SHOW CREATE TABLE` as the DDL (`ClickHouseStructure`).

use super::conn::ChConn;
use super::types;
use crate::error::Result;
use crate::introspect;
use tg_sql::SqlDialect;
use tg_sql::model::{ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableStructure};

type Row = Vec<Option<String>>;

const D: SqlDialect = SqlDialect::ClickHouse;

fn s(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn q(value: &str) -> String {
    format!("'{}'", value.replace('\\', "\\\\").replace('\'', "\\'"))
}

/// The database a namespace names, or the request's own.
fn database(ns: &Namespace) -> String {
    ns.schema
        .as_deref()
        .or(ns.catalog.as_deref())
        .map_or_else(|| "currentDatabase()".to_owned(), q)
}

pub async fn namespaces(conn: &mut ChConn) -> Result<Vec<Namespace>> {
    let rows = conn
        .text_rows("SELECT name FROM system.databases ORDER BY name")
        .await?;
    let names = rows.iter().map(|r| s(r, 0)).collect();
    Ok(D.list_namespaces(Some(names), None).unwrap_or_default())
}

pub async fn tables(conn: &mut ChConn, ns: &Namespace) -> Result<Vec<TableInfo>> {
    let sql = format!(
        "SELECT name, engine, comment FROM system.tables WHERE database = {} AND NOT is_temporary",
        database(ns)
    );
    let mut tables: Vec<TableInfo> = conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TableInfo {
            table: TableRef::new(ns.clone(), &s(r, 0)),
            is_view: matches!(
                s(r, 1).as_str(),
                "View" | "MaterializedView" | "LiveView" | "WindowView"
            ),
            remarks: Some(s(r, 2)).filter(|c| !c.trim().is_empty()),
        })
        .collect();
    tables.sort_by_key(|t| t.table.name.to_lowercase());
    Ok(tables)
}

const COLUMNS: &str = "SELECT table, name, type, default_kind, default_expression, position, \
    numeric_precision, numeric_scale, character_octet_length FROM system.columns";

fn column(r: &Row) -> (String, i32, ColumnInfo) {
    let ty = s(r, 2);
    let int = |i: usize| {
        r.get(i)
            .cloned()
            .flatten()
            .and_then(|v| v.parse::<i32>().ok())
    };
    let info = ColumnInfo {
        name: s(r, 1),
        nullable: ty.starts_with("Nullable(") || ty.starts_with("LowCardinality(Nullable("),
        jdbc_type: types::jdbc_type(&ty),
        scale: int(7).or_else(|| types::scale(&ty)),
        size: int(6).or_else(|| int(8)),
        default_value: (s(r, 3) == "DEFAULT").then(|| s(r, 4)),
        auto_increment: false,
        primary_key: false,
        type_name: ty,
    };
    (s(r, 0), int(5).unwrap_or(0), info)
}

pub async fn details(
    conn: &mut ChConn,
    table: &TableRef,
    _keys_and_indexes: bool,
) -> Result<TableDetails> {
    let (db, name) = (database(&table.namespace), q(&table.name));
    let primary_key: Vec<(String, Option<String>)> = conn
        .text_rows(&format!(
            "SELECT primary_key FROM system.tables WHERE database = {db} AND name = {name}"
        ))
        .await?
        .first()
        .map(|r| s(r, 0))
        .unwrap_or_default()
        .split(',')
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(|c| (c.to_owned(), Some("PRIMARY".to_owned())))
        .collect();
    let columns = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE database = {db} AND table = {name} ORDER BY position"
        ))
        .await?
        .iter()
        .map(|r| {
            let (_, position, column) = column(r);
            (position, column)
        })
        .collect();
    Ok(introspect::details(
        table,
        columns,
        &primary_key,
        Vec::new(),
        Vec::new(),
    ))
}

pub async fn columns_of(
    conn: &mut ChConn,
    ns: &Namespace,
) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    let rows = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE database = {} ORDER BY table, position",
            database(ns)
        ))
        .await?;
    Ok(introspect::group_columns(rows.iter().map(column).collect()))
}

pub async fn structure(conn: &mut ChConn, details: TableDetails) -> Result<TableStructure> {
    let create = conn
        .text_rows(&format!(
            "SHOW CREATE TABLE {}",
            D.qualified_name(&details.table)
        ))
        .await?
        .first()
        .map(|r| s(r, 0))
        .unwrap_or_default();
    let ddl = format!("{};", create.trim().trim_end_matches(';'));
    Ok(TableStructure {
        details,
        constraints: Vec::new(),
        triggers: Vec::new(),
        ddl,
    })
}

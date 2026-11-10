//! DuckDB's catalog as DuckDB JDBC 1.5's `DatabaseMetaData` reported it to `Introspector.kt` (its SQL is in the
//! driver: `duckdb_tables()`/`duckdb_views()`, `duckdb_columns()`, `duckdb_constraints`, `duckdb_indexes()` and
//! information_schema for foreign keys), plus `DuckDBStructure` from `StructureReader.kt`. Like the driver, index
//! info names unique indexes only, without their columns.

use super::conn::DuckConn;
use crate::error::Result;
use crate::introspect::{self, ForeignKeyRow, IndexRow, RelationRow};
use crate::structure;
use tg_sql::SqlDialect;
use tg_sql::model::{
    ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation, TableStructure,
    jdbc_types as t,
};

type Row = Vec<Option<String>>;

const D: SqlDialect = SqlDialect::DuckDB;

fn s(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn opt(row: &Row, i: usize) -> Option<String> {
    row.get(i).cloned().flatten()
}

fn int(row: &Row, i: usize) -> Option<i64> {
    opt(row, i).and_then(|v| v.parse().ok())
}

fn q(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

fn schema(ns: &Namespace) -> String {
    ns.schema
        .as_deref()
        .map_or_else(|| "current_schema()".into(), q)
}

/// The catalog a namespace names, or the connection's database (the driver didn't filter: tables of other
/// attached databases with the same schema name would show twice).
fn catalog(ns: &Namespace) -> String {
    ns.catalog
        .as_deref()
        .map_or_else(|| "current_database()".into(), q)
}

pub async fn namespaces(conn: &mut DuckConn) -> Result<Vec<Namespace>> {
    let rows = conn
        .text_rows("SELECT schema_name FROM information_schema.schemata ORDER BY catalog_name, schema_name")
        .await?;
    let schemas = rows.iter().map(|r| s(r, 0)).collect();
    Ok(D.list_namespaces(Some(schemas), None).unwrap_or_default())
}

pub async fn tables(conn: &mut DuckConn, ns: &Namespace) -> Result<Vec<TableInfo>> {
    let sql = format!(
        "SELECT table_name, table_type, TABLE_COMMENT FROM (\
           SELECT database_name AS table_catalog, schema_name AS table_schema, table_name, \
             CASE WHEN (\"temporary\") THEN ('LOCAL TEMPORARY') WHEN (\"internal\") THEN 'SYSTEM TABLE' ELSE 'TABLE' END AS table_type, \
             comment AS TABLE_COMMENT FROM duckdb_tables() x \
           UNION ALL SELECT database_name, schema_name, view_name, \
             CASE WHEN (\"internal\") THEN 'SYSTEM VIEW' ELSE 'VIEW' END, comment FROM duckdb_views() x) x \
         WHERE table_catalog = {} AND table_schema = {} AND table_type IN ('TABLE', 'VIEW') \
         ORDER BY table_type, table_catalog, table_schema, table_name",
        catalog(ns),
        schema(ns)
    );
    let mut tables: Vec<TableInfo> = conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TableInfo {
            table: TableRef::new(ns.clone(), &s(r, 0)),
            is_view: s(r, 1) == "VIEW",
            remarks: opt(r, 2).filter(|c| !c.trim().is_empty()),
        })
        .collect();
    tables.sort_by_key(|t| t.table.name.to_lowercase());
    Ok(tables)
}

/// `DuckDBResultSetMetaData.type_to_int` for a column's type name without its parameters.
fn jdbc_type(data_type: &str) -> i32 {
    let base = data_type.split('(').next().unwrap_or(data_type).trim();
    if base.ends_with(']') {
        return t::ARRAY;
    }
    match base {
        "BOOLEAN" => t::BOOLEAN,
        "TINYINT" => t::TINYINT,
        "SMALLINT" => t::SMALLINT,
        "INTEGER" => t::INTEGER,
        "BIGINT" => t::BIGINT,
        "FLOAT" => t::FLOAT,
        "DOUBLE" => t::DOUBLE,
        "DECIMAL" => t::DECIMAL,
        "VARCHAR" => t::VARCHAR,
        "TIME" => t::TIME,
        "DATE" => t::DATE,
        "TIMESTAMP" | "TIMESTAMP_S" | "TIMESTAMP_MS" | "TIMESTAMP_NS" => t::TIMESTAMP,
        "TIMESTAMP WITH TIME ZONE" => t::TIMESTAMP_WITH_TIMEZONE,
        "TIME WITH TIME ZONE" => t::TIME_WITH_TIMEZONE,
        "STRUCT" => t::STRUCT,
        "BIT" => t::BIT,
        "BLOB" => t::BLOB,
        _ => t::JAVA_OBJECT,
    }
}

const COLUMNS: &str = "SELECT table_name, column_name, data_type, numeric_precision, numeric_scale, \
    is_nullable, column_default, column_index FROM duckdb_columns()";

fn column(r: &Row) -> (String, i32, ColumnInfo) {
    let data_type = s(r, 2);
    let info = ColumnInfo {
        name: s(r, 1),
        jdbc_type: jdbc_type(&data_type),
        type_name: data_type,
        size: int(r, 3).map(|v| v as i32),
        nullable: s(r, 5) == "true",
        default_value: opt(r, 6),
        // The driver reports IS_AUTOINCREMENT as ''.
        auto_increment: false,
        primary_key: false,
        scale: int(r, 4).map(|v| v as i32),
    };
    (s(r, 0), int(r, 7).unwrap_or(0) as i32, info)
}

pub async fn details(
    conn: &mut DuckConn,
    table: &TableRef,
    keys_and_indexes: bool,
) -> Result<TableDetails> {
    let (db, sc, name) = (
        catalog(&table.namespace),
        schema(&table.namespace),
        q(&table.name),
    );
    let primary_key: Vec<(String, Option<String>)> = conn
        .text_rows(&format!(
            "SELECT unnest(constraint_column_names) FROM duckdb_constraints() \
             WHERE constraint_type = 'PRIMARY KEY' AND database_name = {db} AND schema_name = {sc} AND table_name = {name}"
        ))
        .await?
        .iter()
        .map(|r| (s(r, 0), None))
        .collect();
    let columns = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE database_name = {db} AND schema_name = {sc} AND table_name = {name} ORDER BY column_index"
        ))
        .await?
        .iter()
        .map(|r| {
            let (_, position, column) = column(r);
            (position, column)
        })
        .collect();
    if !keys_and_indexes {
        return Ok(introspect::details(
            table,
            columns,
            &primary_key,
            Vec::new(),
            Vec::new(),
        ));
    }
    let indexes = conn
        .text_rows(&format!(
            "SELECT index_name FROM duckdb_indexes() WHERE database_name = {db} AND schema_name = {sc} \
             AND table_name = {name} AND is_unique = TRUE ORDER BY index_name"
        ))
        .await?
        .iter()
        .map(|r| IndexRow {
            name: opt(r, 0),
            position: 0,
            column: None,
            unique: true,
        })
        .collect();
    let foreign_keys = keys(
        conn,
        &format!("fk.table_schema = {sc} AND fk.table_name = {name}"),
    )
    .await?
    .into_iter()
    .map(|k| ForeignKeyRow {
        name: k.name,
        sequence: k.sequence,
        column: k.from_column,
        target: k.to,
        target_column: k.to_column,
        on_update: k.on_update,
        on_delete: k.on_delete,
    })
    .collect();
    Ok(introspect::details(
        table,
        columns,
        &primary_key,
        indexes,
        foreign_keys,
    ))
}

struct Key {
    name: String,
    sequence: i32,
    from: TableRef,
    from_column: String,
    to: TableRef,
    to_column: String,
    on_update: Option<String>,
    on_delete: Option<String>,
}

/// `getCrossReference`'s information_schema join, filtered by `filter` on `fk` (referencing) and `pk` (referenced).
async fn keys(conn: &mut DuckConn, filter: &str) -> Result<Vec<Key>> {
    let rule = |c: &str| {
        format!(
            "CASE {c} WHEN 'CASCADE' THEN 0 WHEN 'RESTRICT' THEN 1 WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 ELSE 3 END"
        )
    };
    let sql = format!(
        "SELECT rc.constraint_name, fk.ordinal_position, fk.table_schema, fk.table_name, fk.column_name, \
           pk.table_schema, pk.table_name, pk.column_name, {}, {} \
         FROM information_schema.referential_constraints rc \
         JOIN information_schema.key_column_usage fk ON fk.constraint_catalog = rc.constraint_catalog \
           AND fk.constraint_schema = rc.constraint_schema AND fk.constraint_name = rc.constraint_name \
         JOIN information_schema.key_column_usage pk ON pk.constraint_catalog = rc.unique_constraint_catalog \
           AND pk.constraint_schema = rc.unique_constraint_schema AND pk.constraint_name = rc.unique_constraint_name \
           AND pk.ordinal_position = fk.position_in_unique_constraint \
         WHERE {filter} ORDER BY pk.table_schema, pk.table_name, fk.ordinal_position",
        rule("rc.update_rule"),
        rule("rc.delete_rule")
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| Key {
            name: s(r, 0),
            sequence: int(r, 1).unwrap_or(0) as i32,
            from: TableRef::new(Namespace::new(None, opt(r, 2).as_deref()), &s(r, 3)),
            from_column: s(r, 4),
            to: TableRef::new(Namespace::new(None, opt(r, 5).as_deref()), &s(r, 6)),
            to_column: s(r, 7),
            on_update: introspect::referential_action(int(r, 8).map(|v| v as i32)),
            on_delete: introspect::referential_action(int(r, 9).map(|v| v as i32)),
        })
        .collect())
}

pub async fn columns_of(
    conn: &mut DuckConn,
    ns: &Namespace,
) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    let rows = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE database_name = {} AND schema_name = {} ORDER BY table_name, column_index",
            catalog(ns),
            schema(ns)
        ))
        .await?;
    Ok(introspect::group_columns(rows.iter().map(column).collect()))
}

pub async fn relations(conn: &mut DuckConn, table: &TableRef) -> Result<Vec<TableRelation>> {
    let (sc, name) = (schema(&table.namespace), q(&table.name));
    let imported = keys(
        conn,
        &format!("fk.table_schema = {sc} AND fk.table_name = {name}"),
    )
    .await?;
    let exported = keys(
        conn,
        &format!("pk.table_schema = {sc} AND pk.table_name = {name}"),
    )
    .await?;
    let rows = |keys: Vec<Key>| -> Vec<RelationRow> {
        keys.into_iter()
            .map(|k| RelationRow {
                name: k.name,
                sequence: k.sequence,
                from: k.from,
                from_column: k.from_column,
                to: k.to,
                to_column: k.to_column,
            })
            .collect()
    };
    let mut out = introspect::group_relations(rows(imported));
    for r in introspect::group_relations(rows(exported)) {
        if !out.contains(&r) {
            out.push(r);
        }
    }
    Ok(out)
}

/// `DuckDBStructure`: metadata's keys, and the SQL DuckDB keeps for the table or view and its indexes (the rebuilt
/// DDL only when it has none).
pub async fn structure(conn: &mut DuckConn, details: TableDetails) -> Result<TableStructure> {
    let sc = q(details.table.namespace.schema.as_deref().unwrap_or("main"));
    let name = q(&details.table.name);
    let constraints = structure::generic_constraints(D, &details);
    let objects: Vec<String> = conn
        .text_rows(&format!(
            "SELECT sql FROM duckdb_tables() WHERE schema_name = {sc} AND table_name = {name} \
             UNION ALL SELECT sql FROM duckdb_views() WHERE schema_name = {sc} AND view_name = {name} \
             UNION ALL SELECT sql FROM duckdb_indexes() WHERE schema_name = {sc} AND table_name = {name} AND sql IS NOT NULL"
        ))
        .await?
        .iter()
        .map(|r| s(r, 0))
        .collect();
    let ddl = if objects.is_empty() {
        structure::rebuilt_ddl(D, &details, &constraints, &[], structure::REBUILT_NOTE)
    } else {
        structure::statements(&[&objects])
    };
    Ok(TableStructure {
        details,
        constraints,
        triggers: Vec::new(),
        ddl,
    })
}

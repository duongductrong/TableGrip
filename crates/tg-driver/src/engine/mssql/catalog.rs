//! SQL Server's catalog as mssql-jdbc's `DatabaseMetaData` reported it to `Introspector.kt`: the same system
//! procedures (`sp_columns_100`, `sp_pkeys`, `sp_statistics_100`, `sp_fkeys`) with the driver's remapping of ODBC
//! type codes to JDBC ones, plus `SqlServerStructure` from `StructureReader.kt`.

use super::conn::MsConn;
use crate::error::Result;
use crate::introspect::{self, ForeignKeyRow, IndexRow, RelationRow};
use crate::structure;
use tg_sql::SqlDialect;
use tg_sql::model::{
    ColumnInfo, ConstraintInfo, ConstraintKind, Namespace, TableDetails, TableInfo, TableRef,
    TableRelation, TableStructure, TriggerInfo,
};

type Row = Vec<Option<String>>;

const D: SqlDialect = SqlDialect::SqlServer;

fn s(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn opt(row: &Row, i: usize) -> Option<String> {
    row.get(i).cloned().flatten()
}

fn int(row: &Row, i: usize) -> Option<i64> {
    opt(row, i).and_then(|v| v.parse().ok())
}

fn n(value: &str) -> String {
    format!("N'{}'", value.replace('\'', "''"))
}

fn owner(ns: &Namespace) -> String {
    ns.schema.as_deref().map_or_else(|| "NULL".to_owned(), n)
}

pub async fn namespaces(conn: &mut MsConn) -> Result<Vec<Namespace>> {
    let rows = conn.text_rows("SELECT name FROM sys.schemas").await?;
    let schemas = rows.iter().map(|r| s(r, 0)).collect();
    Ok(D.list_namespaces(Some(schemas), None).unwrap_or_default())
}

pub async fn tables(conn: &mut MsConn, ns: &Namespace) -> Result<Vec<TableInfo>> {
    let sql = format!(
        "SELECT o.name, o.type FROM sys.objects o WHERE o.schema_id = SCHEMA_ID({}) \
         AND o.type IN ('U', 'V') AND o.is_ms_shipped = 0",
        owner(ns)
    );
    let mut tables: Vec<TableInfo> = conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TableInfo {
            table: TableRef::new(ns.clone(), &s(r, 0)),
            is_view: s(r, 1).trim() == "V",
            remarks: None,
        })
        .collect();
    tables.sort_by_key(|t| t.table.name.to_lowercase());
    Ok(tables)
}

/// mssql-jdbc's remapping of `sp_columns_100`'s ODBC codes to `java.sql.Types`.
fn jdbc_type(odbc: i64) -> i32 {
    match odbc {
        -8 => -15,   // nchar → NCHAR
        -10 => -16,  // ntext → LONGNVARCHAR
        -11 => 1,    // uniqueidentifier → CHAR
        -152 => -16, // xml → LONGNVARCHAR
        -154 => 92,  // time → TIME
        -151 => -3,  // CLR types → VARBINARY
        other => other as i32,
    }
}

/// One `sp_columns_100` row (with `@ODBCVer = 3`): table, ordinal position, column.
fn column(r: &Row) -> (String, i32, ColumnInfo) {
    let info = ColumnInfo {
        name: s(r, 3),
        type_name: s(r, 5),
        size: int(r, 6).map(|v| v as i32),
        nullable: int(r, 10) != Some(0),
        default_value: opt(r, 12),
        // SS_IS_IDENTITY
        auto_increment: int(r, 22) == Some(1),
        primary_key: false,
        jdbc_type: jdbc_type(int(r, 4).unwrap_or(1111)),
        scale: int(r, 8).map(|v| v as i32),
    };
    (s(r, 2), int(r, 16).unwrap_or(0) as i32, info)
}

fn columns_call(table: &str, ns: &Namespace, pattern: bool) -> String {
    format!(
        "EXEC sp_columns_100 @table_name = {}, @table_owner = {}, @column_name = N'%', @ODBCVer = 3, @fUsePattern = {}",
        n(table),
        owner(ns),
        u8::from(pattern)
    )
}

pub async fn details(
    conn: &mut MsConn,
    table: &TableRef,
    keys_and_indexes: bool,
) -> Result<TableDetails> {
    let (name, schema) = (n(&table.name), owner(&table.namespace));
    let primary_key: Vec<(String, Option<String>)> = conn
        .text_rows(&format!(
            "EXEC sp_pkeys @table_name = {name}, @table_owner = {schema}"
        ))
        .await?
        .iter()
        .map(|r| (s(r, 3), opt(r, 5)))
        .collect();
    let columns = conn
        .text_rows(&columns_call(&table.name, &table.namespace, false))
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
            "EXEC sp_statistics_100 @table_name = {name}, @table_owner = {schema}, @is_unique = 'N', @accuracy = 'Q'"
        ))
        .await?
        .iter()
        .map(|r| IndexRow {
            name: opt(r, 5),
            position: int(r, 7).unwrap_or(0) as i32,
            column: opt(r, 8),
            unique: int(r, 3) == Some(0),
        })
        .collect();
    let foreign_keys = fkeys(
        conn,
        &format!("@fktable_name = {name}, @fktable_owner = {schema}"),
    )
    .await?
    .into_iter()
    .map(|r| ForeignKeyRow {
        name: r.name,
        sequence: r.sequence,
        column: r.from_column,
        target: r.to,
        target_column: r.to_column,
        on_update: r.on_update,
        on_delete: r.on_delete,
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

/// `sp_fkeys` rows; a foreign key without a name is named as `Introspector` did.
async fn fkeys(conn: &mut MsConn, arguments: &str) -> Result<Vec<Key>> {
    Ok(conn
        .text_rows(&format!("EXEC sp_fkeys {arguments}"))
        .await?
        .iter()
        .map(|r| Key {
            name: opt(r, 11).unwrap_or_else(|| format!("fk_{}_{}", s(r, 6), s(r, 2))),
            sequence: int(r, 8).unwrap_or(0) as i32,
            from: TableRef::new(Namespace::new(None, opt(r, 5).as_deref()), &s(r, 6)),
            from_column: s(r, 7),
            to: TableRef::new(Namespace::new(None, opt(r, 1).as_deref()), &s(r, 2)),
            to_column: s(r, 3),
            on_update: introspect::referential_action(int(r, 9).map(|v| v as i32)),
            on_delete: introspect::referential_action(int(r, 10).map(|v| v as i32)),
        })
        .collect())
}

pub async fn columns_of(
    conn: &mut MsConn,
    ns: &Namespace,
) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    let rows = conn.text_rows(&columns_call("%", ns, true)).await?;
    Ok(introspect::group_columns(rows.iter().map(column).collect()))
}

pub async fn relations(conn: &mut MsConn, table: &TableRef) -> Result<Vec<TableRelation>> {
    let (name, schema) = (n(&table.name), owner(&table.namespace));
    let imported = fkeys(
        conn,
        &format!("@fktable_name = {name}, @fktable_owner = {schema}"),
    )
    .await?;
    let exported = fkeys(
        conn,
        &format!("@pktable_name = {name}, @pktable_owner = {schema}"),
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

/// `SqlServerStructure`: metadata's keys plus CHECK constraints from `sys`, triggers, and a rebuilt CREATE TABLE.
pub async fn structure(conn: &mut MsConn, details: TableDetails) -> Result<TableStructure> {
    let object = n(&D.qualified_name(&details.table));
    let mut constraints = structure::generic_constraints(D, &details);
    constraints.extend(
        conn.text_rows(&format!(
            "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID({object}) ORDER BY name"
        ))
        .await?
        .iter()
        .map(|r| ConstraintInfo {
            name: s(r, 0),
            kind: ConstraintKind::Check,
            columns: Vec::new(),
            definition: format!("CHECK {}", s(r, 1)),
        }),
    );
    let triggers: Vec<TriggerInfo> = conn
        .text_rows(&format!(
            "SELECT t.name, t.is_instead_of_trigger, t.is_disabled, OBJECT_DEFINITION(t.object_id), \
               STUFF((SELECT ' OR ' + e.type_desc FROM sys.trigger_events e WHERE e.object_id = t.object_id FOR XML PATH('')), 1, 4, '') \
             FROM sys.triggers t WHERE t.parent_id = OBJECT_ID({object}) ORDER BY t.name"
        ))
        .await?
        .iter()
        .map(|r| TriggerInfo {
            name: s(r, 0),
            timing: if s(r, 1) == "1" { "INSTEAD OF" } else { "AFTER" }.into(),
            events: s(r, 4),
            enabled: s(r, 2) != "1",
            definition: s(r, 3),
        })
        .collect();
    let ddl = structure::rebuilt_ddl(
        D,
        &details,
        &constraints,
        &triggers,
        "-- Rebuilt from the catalog; storage options (filegroups, compression) aren't included.\n",
    );
    Ok(TableStructure {
        details,
        constraints,
        triggers,
        ddl,
    })
}

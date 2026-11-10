//! The MySQL family's catalog as MariaDB Connector/J 3.5's `DatabaseMetaData` reported it to `Introspector.kt`: the
//! same information_schema queries (captured from the server's general log), plus `MySQLStructure` from
//! `StructureReader.kt`.

use super::conn::MyConn;
use crate::error::Result;
use crate::introspect::{self, ForeignKeyRow, IndexRow, RelationRow};
use crate::structure;
use tg_sql::SqlDialect;
use tg_sql::model::{
    ColumnInfo, ConstraintInfo, ConstraintKind, Namespace, TableDetails, TableInfo, TableRef,
    TableRelation, TableStructure, TriggerInfo,
};

type Row = Vec<Option<String>>;

const D: SqlDialect = SqlDialect::MySQL;

fn s(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn opt(row: &Row, i: usize) -> Option<String> {
    row.get(i).cloned().flatten()
}

fn int(row: &Row, i: usize) -> Option<i64> {
    opt(row, i).and_then(|v| v.parse().ok())
}

/// A string literal for catalog queries (names never hold backslashes worth keeping literal… but quote them anyway).
fn q(value: &str) -> String {
    format!("'{}'", value.replace('\\', "\\\\").replace('\'', "''"))
}

/// The database a namespace names, or the connection's own.
fn schema(ns: &Namespace) -> String {
    ns.catalog
        .as_deref()
        .map_or_else(|| "DATABASE()".to_owned(), q)
}

pub async fn namespaces(conn: &mut MyConn) -> Result<Vec<Namespace>> {
    let rows = conn
        .text_rows(
            "SELECT SCHEMA_NAME TABLE_CAT FROM INFORMATION_SCHEMA.SCHEMATA  ORDER BY SCHEMA_NAME",
        )
        .await?;
    let catalogs = rows.iter().map(|r| s(r, 0)).collect();
    Ok(D.list_namespaces(None, Some(catalogs)).unwrap_or_default())
}

pub async fn tables(conn: &mut MyConn, ns: &Namespace) -> Result<Vec<TableInfo>> {
    let sql = format!(
        "SELECT TABLE_NAME, IF(TABLE_SCHEMA IN ('mysql', 'performance_schema', 'sys'), \
           IF(TABLE_TYPE='BASE TABLE', 'SYSTEM TABLE', IF(TABLE_TYPE='VIEW', 'SYSTEM VIEW', TABLE_TYPE)), \
           IF(TABLE_TYPE='BASE TABLE' or TABLE_TYPE='SYSTEM VERSIONED', 'TABLE', IF(TABLE_TYPE='TEMPORARY', 'LOCAL TEMPORARY', TABLE_TYPE))) as TABLE_TYPE, \
           TABLE_COMMENT REMARKS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA={} AND \
           TABLE_TYPE IN ('BASE TABLE','SYSTEM VERSIONED','VIEW') ORDER BY TABLE_TYPE,TABLE_SCHEMA,TABLE_NAME",
        schema(ns)
    );
    let types = D.table_types();
    let mut tables: Vec<TableInfo> = conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TableInfo {
            table: TableRef::new(ns.clone(), &s(r, 0)),
            is_view: types.iter().any(|(t, view)| *view && *t == s(r, 1)),
            remarks: opt(r, 2).filter(|d| !d.trim().is_empty()),
        })
        .collect();
    tables.sort_by_key(|t| t.table.name.to_lowercase());
    Ok(tables)
}

/// `getColumns`' select list, as the driver builds it with its default options.
const COLUMNS: &str = "SELECT TABLE_NAME, COLUMN_NAME, CASE data_type WHEN 'bit' THEN -7 WHEN 'tinyblob' THEN -3 \
    WHEN 'mediumblob' THEN -4 WHEN 'longblob' THEN -4 WHEN 'blob' THEN -4 WHEN 'tinytext' THEN 12 WHEN 'mediumtext' THEN -1 \
    WHEN 'longtext' THEN -1 WHEN 'text' THEN -1 WHEN 'date' THEN 91 WHEN 'datetime' THEN 93 WHEN 'decimal' THEN 3 \
    WHEN 'double' THEN 8 WHEN 'enum' THEN 12 WHEN 'float' THEN 7 WHEN 'int' THEN IF( COLUMN_TYPE like '%unsigned%', 4,4) \
    WHEN 'bigint' THEN -5 WHEN 'mediumint' THEN 4 WHEN 'null' THEN 0 WHEN 'set' THEN 12 \
    WHEN 'smallint' THEN IF( COLUMN_TYPE like '%unsigned%', 5,5) WHEN 'varchar' THEN 12 WHEN 'varbinary' THEN -3 \
    WHEN 'char' THEN 1 WHEN 'binary' THEN -2 WHEN 'time' THEN 92 WHEN 'timestamp' THEN 93 \
    WHEN 'tinyint' THEN IF(COLUMN_TYPE like 'tinyint(1)%',16,-6)  WHEN 'year' THEN 91 ELSE 1111 END  DATA_TYPE, \
    IF(COLUMN_TYPE like 'tinyint(1)%', 'BOOLEAN',  UCASE(IF( COLUMN_TYPE LIKE '%(%)%', \
      CONCAT(SUBSTRING( COLUMN_TYPE,1, LOCATE('(',COLUMN_TYPE) - 1 ), SUBSTRING(COLUMN_TYPE ,1+locate(')', COLUMN_TYPE))), \
      COLUMN_TYPE))) TYPE_NAME, \
    CASE DATA_TYPE WHEN 'date' THEN 10  WHEN 'time' THEN IF(DATETIME_PRECISION = 0, 10, CAST(11 + DATETIME_PRECISION as signed integer)) \
      WHEN 'datetime' THEN IF(DATETIME_PRECISION = 0, 19, CAST(20 + DATETIME_PRECISION as signed integer)) \
      WHEN 'timestamp' THEN IF(DATETIME_PRECISION = 0, 19, CAST(20 + DATETIME_PRECISION as signed integer)) \
      ELSE IF(NUMERIC_PRECISION IS NULL, LEAST(CHARACTER_MAXIMUM_LENGTH,2147483647), NUMERIC_PRECISION) END COLUMN_SIZE, \
    CONVERT (CASE DATA_TYPE WHEN 'year' THEN NUMERIC_SCALE WHEN 'tinyint' THEN 0 ELSE NUMERIC_SCALE END, UNSIGNED INTEGER) DECIMAL_DIGITS, \
    IF(IS_NULLABLE = 'yes',1,0) NULLABLE, COLUMN_DEFAULT COLUMN_DEF, ORDINAL_POSITION, \
    IF(EXTRA = 'auto_increment','YES','NO') IS_AUTOINCREMENT \
    FROM INFORMATION_SCHEMA.COLUMNS";

/// One `getColumns` row: table, ordinal position, column.
fn column(r: &Row) -> (String, i32, ColumnInfo) {
    let info = ColumnInfo {
        name: s(r, 1),
        type_name: s(r, 3),
        size: int(r, 4).map(|v| v as i32),
        nullable: int(r, 6) != Some(0),
        default_value: opt(r, 7),
        auto_increment: s(r, 9) == "YES",
        primary_key: false,
        jdbc_type: int(r, 2).unwrap_or(1111) as i32,
        scale: int(r, 5).map(|v| v as i32),
    };
    (s(r, 0), int(r, 8).unwrap_or(0) as i32, info)
}

pub async fn details(
    conn: &mut MyConn,
    table: &TableRef,
    keys_and_indexes: bool,
) -> Result<TableDetails> {
    let db = schema(&table.namespace);
    let name = q(&table.name);
    let primary_key: Vec<(String, Option<String>)> = conn
        .text_rows(&format!(
            "SELECT COLUMN_NAME, INDEX_NAME PK_NAME FROM INFORMATION_SCHEMA.STATISTICS \
             WHERE INDEX_NAME='PRIMARY' AND TABLE_SCHEMA={db} AND TABLE_NAME = {name} ORDER BY COLUMN_NAME"
        ))
        .await?
        .iter()
        .map(|r| (s(r, 0), opt(r, 1)))
        .collect();
    let columns = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE TABLE_SCHEMA={db} AND TABLE_NAME={name} ORDER BY TABLE_NAME, ORDINAL_POSITION"
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
            "SELECT INDEX_NAME, SEQ_IN_INDEX ORDINAL_POSITION, COLUMN_NAME, NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS \
             WHERE TABLE_SCHEMA={db} AND TABLE_NAME = {name} ORDER BY NON_UNIQUE, INDEX_NAME, ORDINAL_POSITION"
        ))
        .await?
        .iter()
        .map(|r| IndexRow {
            name: opt(r, 0),
            position: int(r, 1).unwrap_or(0) as i32,
            column: opt(r, 2),
            unique: int(r, 3) == Some(0),
        })
        .collect();
    let foreign_keys = keys(
        conn,
        &format!("KCU.TABLE_SCHEMA={db} AND KCU.TABLE_NAME = {name}"),
        "PKTABLE_CAT, PKTABLE_NAME, KEY_SEQ",
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

/// `getImportedKeys`/`getExportedKeys` rows matching `filter`.
async fn keys(conn: &mut MyConn, filter: &str, order: &str) -> Result<Vec<Key>> {
    let rule = |c: &str| {
        format!(
            "CASE {c} WHEN 'RESTRICT' THEN 1 WHEN 'NO ACTION' THEN 3 WHEN 'CASCADE' THEN 0 \
             WHEN 'SET NULL' THEN 2 WHEN 'SET DEFAULT' THEN 4 END"
        )
    };
    let sql = format!(
        "SELECT KCU.REFERENCED_TABLE_SCHEMA PKTABLE_CAT, KCU.REFERENCED_TABLE_NAME PKTABLE_NAME, \
           KCU.REFERENCED_COLUMN_NAME, KCU.TABLE_SCHEMA FKTABLE_CAT, KCU.TABLE_NAME FKTABLE_NAME, KCU.COLUMN_NAME, \
           KCU.POSITION_IN_UNIQUE_CONSTRAINT KEY_SEQ, {}, {}, RC.CONSTRAINT_NAME FK_NAME \
         FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU INNER JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC \
           ON KCU.CONSTRAINT_CATALOG = RC.CONSTRAINT_CATALOG AND KCU.CONSTRAINT_SCHEMA = RC.CONSTRAINT_SCHEMA \
           AND KCU.CONSTRAINT_NAME = RC.CONSTRAINT_NAME AND KCU.TABLE_NAME = RC.TABLE_NAME \
         WHERE {filter} ORDER BY {order}",
        rule("RC.UPDATE_RULE"),
        rule("RC.DELETE_RULE")
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| Key {
            name: s(r, 9),
            sequence: int(r, 6).unwrap_or(0) as i32,
            from: TableRef::new(Namespace::new(opt(r, 3).as_deref(), None), &s(r, 4)),
            from_column: s(r, 5),
            to: TableRef::new(Namespace::new(opt(r, 0).as_deref(), None), &s(r, 1)),
            to_column: s(r, 2),
            on_update: introspect::referential_action(int(r, 7).map(|v| v as i32)),
            on_delete: introspect::referential_action(int(r, 8).map(|v| v as i32)),
        })
        .collect())
}

pub async fn columns_of(
    conn: &mut MyConn,
    ns: &Namespace,
) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    let rows = conn
        .text_rows(&format!(
            "{COLUMNS} WHERE TABLE_SCHEMA={} ORDER BY TABLE_NAME, ORDINAL_POSITION",
            schema(ns)
        ))
        .await?;
    Ok(introspect::group_columns(rows.iter().map(column).collect()))
}

pub async fn relations(conn: &mut MyConn, table: &TableRef) -> Result<Vec<TableRelation>> {
    let db = schema(&table.namespace);
    let name = q(&table.name);
    let imported = keys(
        conn,
        &format!("KCU.TABLE_SCHEMA={db} AND KCU.TABLE_NAME = {name}"),
        "PKTABLE_CAT, PKTABLE_NAME, KEY_SEQ",
    )
    .await?;
    let exported = keys(
        conn,
        &format!("KCU.REFERENCED_TABLE_SCHEMA={db} AND KCU.REFERENCED_TABLE_NAME = {name}"),
        "FKTABLE_CAT, FKTABLE_NAME, FK_NAME, KEY_SEQ",
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
    // Kotlin groups each side on its own, then drops duplicates.
    let mut out = introspect::group_relations(rows(imported));
    for r in introspect::group_relations(rows(exported)) {
        if !out.contains(&r) {
            out.push(r);
        }
    }
    Ok(out)
}

/// `MySQLStructure`: constraints from information_schema, triggers, and `SHOW CREATE TABLE` as the DDL.
pub async fn structure(conn: &mut MyConn, details: TableDetails) -> Result<TableStructure> {
    let table = details.table.clone();
    let db = schema(&table.namespace);
    let name = q(&table.name);
    let mut columns: Vec<(String, Vec<String>)> = Vec::new();
    for r in conn
        .text_rows(&format!(
            "SELECT CONSTRAINT_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE \
             WHERE TABLE_SCHEMA = {db} AND TABLE_NAME = {name} ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION"
        ))
        .await?
    {
        match columns.iter_mut().find(|c| c.0 == s(&r, 0)) {
            Some(c) => c.1.push(s(&r, 1)),
            None => columns.push((s(&r, 0), vec![s(&r, 1)])),
        }
    }
    // CHECK_CONSTRAINTS exists from MySQL 8.0.16 and MariaDB 10.2; only MariaDB scopes it by table.
    let checks = match conn
        .text_rows(&format!(
            "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS \
             WHERE CONSTRAINT_SCHEMA = {db} AND TABLE_NAME = {name}"
        ))
        .await
    {
        Ok(rows) => rows,
        Err(_) => conn
            .text_rows(&format!(
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = {db}"
            ))
            .await
            .unwrap_or_default(),
    };
    let check = |n: &str| {
        checks
            .iter()
            .rev()
            .find(|r| s(r, 0) == n)
            .map(|r| s(r, 1))
            .unwrap_or_default()
    };
    let order = [
        ConstraintKind::PrimaryKey,
        ConstraintKind::Unique,
        ConstraintKind::ForeignKey,
        ConstraintKind::Check,
    ];
    let mut constraints: Vec<ConstraintInfo> = conn
        .text_rows(&format!(
            "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS \
             WHERE TABLE_SCHEMA = {db} AND TABLE_NAME = {name}"
        ))
        .await?
        .iter()
        .filter_map(|r| {
            let name = s(r, 0);
            let cols = columns
                .iter()
                .find(|c| c.0 == name)
                .map(|c| c.1.clone())
                .unwrap_or_default();
            let list = structure::column_list(D, &cols);
            let (kind, definition) = match s(r, 1).as_str() {
                "PRIMARY KEY" => (ConstraintKind::PrimaryKey, format!("PRIMARY KEY {list}")),
                "UNIQUE" => (ConstraintKind::Unique, format!("UNIQUE {list}")),
                "FOREIGN KEY" => (
                    ConstraintKind::ForeignKey,
                    details
                        .foreign_keys
                        .iter()
                        .find(|f| f.name == name)
                        .map(|f| structure::foreign_key_definition(D, &table, f))
                        .unwrap_or_else(|| format!("FOREIGN KEY {list}")),
                ),
                "CHECK" => {
                    // MySQL stores the clause with backslash-escaped quotes.
                    let clause = check(&name).replace("\\'", "'");
                    let clause = if clause.starts_with('(') {
                        clause
                    } else {
                        format!("({clause})")
                    };
                    (ConstraintKind::Check, format!("CHECK {clause}"))
                }
                _ => return None,
            };
            Some(ConstraintInfo {
                name,
                kind,
                columns: cols,
                definition,
            })
        })
        .collect();
    constraints.sort_by(|a, b| {
        let rank = |k| order.iter().position(|o| *o == k);
        rank(a.kind)
            .cmp(&rank(b.kind))
            .then_with(|| a.name.cmp(&b.name))
    });
    let triggers: Vec<TriggerInfo> = conn
        .text_rows(&format!(
            "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, ACTION_STATEMENT FROM information_schema.TRIGGERS \
             WHERE EVENT_OBJECT_SCHEMA = {db} AND EVENT_OBJECT_TABLE = {name} \
             ORDER BY ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORDER"
        ))
        .await?
        .iter()
        .map(|r| {
            let (name, timing, event) = (s(r, 0), s(r, 1), s(r, 2));
            let definition = format!(
                "CREATE TRIGGER {} {timing} {event} ON {} FOR EACH ROW {}",
                D.quote_identifier(&name),
                D.quote_identifier(&table.name),
                s(r, 3)
            );
            TriggerInfo {
                name,
                timing,
                events: event,
                enabled: true,
                definition,
            }
        })
        .collect();
    // SHOW CREATE TABLE also answers for views, with the CREATE VIEW statement.
    let create = conn
        .text_rows(&format!("SHOW CREATE TABLE {}", D.qualified_name(&table)))
        .await?
        .first()
        .map(|r| s(r, 1))
        .unwrap_or_default();
    let definitions: Vec<String> = triggers.iter().map(|t| t.definition.clone()).collect();
    let ddl = structure::statements(&[&[create], &definitions]);
    Ok(TableStructure {
        details,
        constraints,
        triggers,
        ddl,
    })
}

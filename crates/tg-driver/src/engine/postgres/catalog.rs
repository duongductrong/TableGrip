//! PostgreSQL's catalog as pgjdbc 42.7.8's `DatabaseMetaData` reported it to `Introspector.kt`: the same catalog
//! queries, and `TypeInfoCache`'s type names, JDBC types, sizes and scales.

use super::conn::PgConn;
use super::literal::quote;
use crate::error::Result;
use crate::introspect::{self, ForeignKeyRow, IndexRow, RelationRow};
use tg_sql::SqlDialect;
use tg_sql::model::{ColumnInfo, Namespace, TableDetails, TableInfo, TableRef, TableRelation};

type Row = Vec<Option<String>>;

fn s(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn n(row: &Row, i: usize) -> i64 {
    row.get(i)
        .cloned()
        .flatten()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0)
}

fn opt(row: &Row, i: usize) -> Option<String> {
    row.get(i).cloned().flatten()
}

fn yes(row: &Row, i: usize) -> bool {
    s(row, i) == "t"
}

pub async fn namespaces(conn: &PgConn) -> Result<Vec<Namespace>> {
    let sql = "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname <> 'pg_toast' \
        AND (nspname !~ '^pg_temp_' OR nspname = (pg_catalog.current_schemas(true))[1]) \
        AND (nspname !~ '^pg_toast_temp_' OR nspname = replace((pg_catalog.current_schemas(true))[1], 'pg_temp_', 'pg_toast_temp_')) \
        ORDER BY nspname";
    let schemas: Vec<String> = conn.text_rows(sql).await?.iter().map(|r| s(r, 0)).collect();
    Ok(SqlDialect::PostgreSQL
        .list_namespaces(Some(schemas), None)
        .unwrap_or_default())
}

fn schema(ns: &Namespace) -> &str {
    ns.schema.as_deref().unwrap_or("public")
}

pub async fn tables(conn: &PgConn, ns: &Namespace) -> Result<Vec<TableInfo>> {
    let sql = format!(
        "SELECT c.relname, c.relkind::text, d.description FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c \
         LEFT JOIN pg_catalog.pg_description d ON (c.oid = d.objoid AND d.objsubid = 0 AND d.classoid = 'pg_class'::regclass) \
         WHERE c.relnamespace = n.oid AND n.nspname = {} AND (false \
           OR (c.relkind IN ('r', 'p') AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema') \
           OR (c.relkind = 'v' AND n.nspname <> 'pg_catalog' AND n.nspname <> 'information_schema') \
           OR c.relkind = 'm')",
        quote(schema(ns))?
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TableInfo {
            table: TableRef::new(ns.clone(), &s(r, 0)),
            is_view: matches!(s(r, 1).as_str(), "v" | "m"),
            remarks: opt(r, 2).filter(|d| !d.trim().is_empty()),
        })
        .collect())
}

/// pgjdbc's built-in types: oid, name, `java.sql.Types`, array oid.
const CORE: &[(u32, &str, i32, u32)] = &[
    (21, "int2", 5, 1005),
    (23, "int4", 4, 1007),
    (26, "oid", -5, 1028),
    (20, "int8", -5, 1016),
    (790, "money", 8, 791),
    (1700, "numeric", 2, 1231),
    (700, "float4", 7, 1021),
    (701, "float8", 8, 1022),
    (18, "char", 1, 1002),
    (1042, "bpchar", 1, 1014),
    (1043, "varchar", 12, 1015),
    (1562, "varbit", 1111, 1563),
    (25, "text", 12, 1009),
    (19, "name", 12, 1003),
    (17, "bytea", -2, 1001),
    (16, "bool", -7, 1000),
    (1560, "bit", -7, 1561),
    (1082, "date", 91, 1182),
    (1083, "time", 92, 1183),
    (1266, "timetz", 92, 1270),
    (1114, "timestamp", 93, 1115),
    (1184, "timestamptz", 93, 1185),
    (1790, "refcursor", 2012, 2201),
    (114, "json", 1111, 199),
    (600, "point", 1111, 1017),
    (603, "box", 1111, 1020),
    (2950, "uuid", 1111, 2951),
    (142, "xml", 2009, 143),
];

/// `unknownLength`'s default.
const UNKNOWN: i64 = i32::MAX as i64;

/// A core array's element oid, else the oid itself (`convertArrayToBaseOid`).
fn base_oid(oid: u32) -> u32 {
    CORE.iter().find(|c| c.3 == oid).map_or(oid, |c| c.0)
}

fn seconds(typmod: i64) -> i64 {
    match typmod {
        -1 => 7,
        0 => 0,
        1 => 3,
        t => t + 1,
    }
}

fn display_size(oid: u32, typmod: i64) -> i64 {
    match base_oid(oid) {
        21 => 6,
        23 => 11,
        26 => 10,
        20 => 20,
        700 => 15,
        701 => 25,
        16 | 18 => 1,
        1082 => 13,
        1083 => 8 + seconds(typmod),
        1266 => 8 + seconds(typmod) + 6,
        1114 => 22 + seconds(typmod),
        1184 => 22 + seconds(typmod) + 6,
        1186 => 49,
        1042 | 1043 => {
            if typmod == -1 {
                UNKNOWN
            } else {
                typmod - 4
            }
        }
        1700 => {
            if typmod == -1 {
                131_089
            } else {
                let p = ((typmod - 4) >> 16) & 0xFFFF;
                let s = (typmod - 4) & 0xFFFF;
                1 + p + i64::from(s != 0)
            }
        }
        1560 => typmod,
        1562 => {
            if typmod == -1 {
                UNKNOWN
            } else {
                typmod
            }
        }
        _ => UNKNOWN,
    }
}

fn precision(oid: u32, typmod: i64) -> i64 {
    match base_oid(oid) {
        21 => 5,
        23 | 26 => 10,
        20 => 19,
        700 => 8,
        701 => 17,
        1700 => {
            if typmod == -1 {
                0
            } else {
                ((typmod - 4) & 0xFFFF_0000) >> 16
            }
        }
        16 | 18 => 1,
        1042 | 1043 => {
            if typmod == -1 {
                UNKNOWN
            } else {
                typmod - 4
            }
        }
        b @ (1082 | 1083 | 1114 | 1184 | 1186 | 1266) => display_size(b, typmod),
        1560 => typmod,
        1562 => {
            if typmod == -1 {
                UNKNOWN
            } else {
                typmod
            }
        }
        _ => UNKNOWN,
    }
}

fn scale(oid: u32, typmod: i64) -> i64 {
    match base_oid(oid) {
        700 => 8,
        701 => 17,
        1700 => {
            if typmod == -1 {
                0
            } else {
                (typmod - 4) & 0xFFFF
            }
        }
        1083 | 1114 | 1184 | 1266 => {
            if typmod == -1 {
                6
            } else {
                typmod
            }
        }
        1186 => {
            if typmod == -1 {
                6
            } else {
                typmod & 0xFFFF
            }
        }
        _ => 0,
    }
}

/// `getColumns` for a table (or every table of the schema): table name, ordinal, column.
async fn column_rows(
    conn: &PgConn,
    ns: &Namespace,
    table: Option<&str>,
) -> Result<Vec<(String, i32, ColumnInfo)>> {
    let table_filter = match table {
        Some(t) => format!("AND c.relname = {}", quote(t)?),
        None => String::new(),
    };
    let sql = format!(
        "SELECT c.relname, a.attname, a.atttypid::int8, a.attnotnull OR (t.typtype = 'd' AND t.typnotnull), a.atttypmod, \
                t.typtypmod, row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum), nullif(a.attidentity, ''), \
                pg_catalog.pg_get_expr(def.adbin, def.adrelid), t.typbasetype::int8, t.typtype::text, \
                t.typname, tn.nspname = ANY(current_schemas(true)), tn.nspname, t.typinput = 'pg_catalog.array_in'::regproc \
         FROM pg_catalog.pg_namespace n \
           JOIN pg_catalog.pg_class c ON (c.relnamespace = n.oid) \
           JOIN pg_catalog.pg_attribute a ON (a.attrelid = c.oid) \
           JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid) \
           JOIN pg_catalog.pg_namespace tn ON (t.typnamespace = tn.oid) \
           LEFT JOIN pg_catalog.pg_attrdef def ON (a.attrelid = def.adrelid AND a.attnum = def.adnum) \
         WHERE c.relkind IN ('r', 'p', 'v', 'f', 'm') AND a.attnum > 0 AND NOT a.attisdropped \
           AND n.nspname = {} {table_filter} \
         ORDER BY n.nspname, c.relname, a.attnum",
        quote(schema(ns))?
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| {
            let oid = n(r, 2) as u32;
            let typmod = n(r, 4);
            let typtype = s(r, 10);
            let default = opt(r, 8);
            let core = CORE.iter().find(|c| c.0 == oid);
            let core_array = CORE.iter().find(|c| c.3 == oid);
            let sql_type = match typtype.as_str() {
                "c" => 2002,
                "d" => 2001,
                "e" => 12,
                _ => match (core, core_array) {
                    (Some(c), _) => c.2,
                    (None, Some(_)) => 2003,
                    _ if yes(r, 14) => 2003,
                    _ => 1111,
                },
            };
            let mut type_name = match (core, core_array) {
                (Some(c), _) => c.1.to_owned(),
                (None, Some(c)) => format!("_{}", c.1),
                _ if yes(r, 12) => s(r, 11),
                _ => format!("\"{}\".\"{}\"", s(r, 13), s(r, 11)),
            };
            let nextval = default.as_deref().is_some_and(|d| d.contains("nextval("));
            if nextval {
                type_name = match type_name.as_str() {
                    "int4" => "serial".into(),
                    "int8" => "bigserial".into(),
                    "int2" => "smallserial".into(),
                    other => other.to_owned(),
                };
            }
            let (size, digits) = if sql_type == 2001 {
                let typtypmod = n(r, 5);
                let base = n(r, 9) as u32;
                if typtypmod == -1 {
                    (precision(base, typmod), scale(base, typmod))
                } else if base == 1700 {
                    (precision(1700, typtypmod), scale(1700, typtypmod))
                } else {
                    (typtypmod, scale(base, typmod))
                }
            } else {
                let mut size = precision(oid, typmod);
                if sql_type != 2 && size == 0 {
                    size = display_size(oid, typmod);
                }
                (size, scale(oid, typmod))
            };
            let scale = if (sql_type == 2 || sql_type == 3) && typmod == -1 {
                None
            } else {
                Some(digits as i32)
            };
            (
                s(r, 0),
                n(r, 6) as i32,
                ColumnInfo {
                    name: s(r, 1),
                    type_name,
                    size: Some(size as i32),
                    nullable: !yes(r, 3),
                    default_value: default,
                    auto_increment: nextval || opt(r, 7).is_some(),
                    primary_key: false,
                    jdbc_type: sql_type,
                    scale,
                },
            )
        })
        .collect())
}

pub async fn columns_of(conn: &PgConn, ns: &Namespace) -> Result<Vec<(String, Vec<ColumnInfo>)>> {
    Ok(introspect::group_columns(
        column_rows(conn, ns, None).await?,
    ))
}

/// Imported (`exported` false: keys of `table`) or exported (keys pointing at `table`) foreign key rows, in pgjdbc's
/// order.
async fn keys(conn: &PgConn, table: &TableRef, exported: bool) -> Result<Vec<Row>> {
    let (schema_column, table_column, order) = if exported {
        (
            "pkn.nspname",
            "pkc.relname",
            "fkn.nspname, fkc.relname, con.conname, pos.n",
        )
    } else {
        (
            "fkn.nspname",
            "fkc.relname",
            "pkn.nspname, pkc.relname, con.conname, pos.n",
        )
    };
    let sql = format!(
        "SELECT pkn.nspname, pkc.relname, pka.attname, fkn.nspname, fkc.relname, fka.attname, pos.n, \
                CASE con.confupdtype WHEN 'c' THEN 0 WHEN 'n' THEN 2 WHEN 'd' THEN 4 WHEN 'r' THEN 1 WHEN 'p' THEN 1 WHEN 'a' THEN 3 ELSE NULL END, \
                CASE con.confdeltype WHEN 'c' THEN 0 WHEN 'n' THEN 2 WHEN 'd' THEN 4 WHEN 'r' THEN 1 WHEN 'p' THEN 1 WHEN 'a' THEN 3 ELSE NULL END, \
                con.conname \
         FROM pg_catalog.pg_namespace pkn, pg_catalog.pg_class pkc, pg_catalog.pg_attribute pka, \
              pg_catalog.pg_namespace fkn, pg_catalog.pg_class fkc, pg_catalog.pg_attribute fka, \
              pg_catalog.pg_constraint con, pg_catalog.generate_series(1, \
                (SELECT setting::int FROM pg_catalog.pg_settings WHERE name = 'max_index_keys')) pos(n), pg_catalog.pg_class pkic \
         WHERE pkn.oid = pkc.relnamespace AND pkc.oid = pka.attrelid AND pka.attnum = con.confkey[pos.n] AND con.confrelid = pkc.oid \
           AND fkn.oid = fkc.relnamespace AND fkc.oid = fka.attrelid AND fka.attnum = con.conkey[pos.n] AND con.conrelid = fkc.oid \
           AND con.contype = 'f' AND (pkic.relkind = 'i' OR pkic.relkind = 'I') AND pkic.oid = con.conindid \
           AND {schema_column} = {} AND {table_column} = {} \
         ORDER BY {order}",
        quote(schema(&table.namespace))?,
        quote(&table.name)?
    );
    conn.text_rows(&sql).await
}

fn rule(row: &Row, i: usize) -> Option<i32> {
    opt(row, i).and_then(|v| v.parse().ok())
}

pub async fn details(
    conn: &PgConn,
    table: &TableRef,
    keys_and_indexes: bool,
) -> Result<TableDetails> {
    let (schema_q, table_q) = (quote(schema(&table.namespace))?, quote(&table.name)?);
    let pk_sql = format!(
        "SELECT result.column_name, result.pk_name FROM ( \
           SELECT a.attname AS column_name, (information_schema._pg_expandarray(i.indkey)).n AS key_seq, ci.relname AS pk_name, \
                  information_schema._pg_expandarray(i.indkey) AS keys, a.attnum AS a_attnum, i.indnkeyatts AS key_count, ct.relname AS table_name \
           FROM pg_catalog.pg_class ct \
             JOIN pg_catalog.pg_attribute a ON (ct.oid = a.attrelid) \
             JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) \
             JOIN pg_catalog.pg_index i ON (a.attrelid = i.indrelid) \
             JOIN pg_catalog.pg_class ci ON (ci.oid = i.indexrelid) \
           WHERE n.nspname = {schema_q} AND ct.relname = {table_q} AND i.indisprimary \
         ) result WHERE result.a_attnum = (result.keys).x AND result.key_seq <= result.key_count \
         ORDER BY result.table_name, result.pk_name, result.key_seq"
    );
    let primary_key: Vec<(String, Option<String>)> = conn
        .text_rows(&pk_sql)
        .await?
        .iter()
        .map(|r| (s(r, 0), opt(r, 1)))
        .collect();
    let columns: Vec<(i32, ColumnInfo)> = column_rows(conn, &table.namespace, Some(&table.name))
        .await?
        .into_iter()
        .map(|(_, p, c)| (p, c))
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
    let index_sql = format!(
        "SELECT ci.relname, tmp.n, trim(both '\"' from pg_catalog.pg_get_indexdef(ci.oid, tmp.n, false)), NOT i.indisunique \
         FROM pg_catalog.pg_class ct \
           JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) \
           JOIN pg_catalog.pg_index i ON (ct.oid = i.indrelid) \
           JOIN pg_catalog.pg_class ci ON (ci.oid = i.indexrelid) \
           JOIN pg_catalog.pg_am am ON (ci.relam = am.oid) \
           CROSS JOIN LATERAL (SELECT (information_schema._pg_expandarray(i.indkey)).n) tmp(n) \
         WHERE n.nspname = {schema_q} AND ct.relname = {table_q} \
         ORDER BY NOT i.indisunique, CASE i.indisclustered WHEN true THEN 1 ELSE CASE am.amname WHEN 'hash' THEN 2 ELSE 3 END END, \
                  ci.relname, tmp.n"
    );
    let indexes = conn
        .text_rows(&index_sql)
        .await?
        .iter()
        .map(|r| IndexRow {
            name: opt(r, 0),
            position: n(r, 1) as i32,
            column: opt(r, 2),
            unique: !yes(r, 3),
        })
        .collect();
    let foreign_keys = keys(conn, table, false)
        .await?
        .iter()
        .map(|r| ForeignKeyRow {
            name: opt(r, 9).unwrap_or_else(|| format!("fk_{}_{}", table.name, s(r, 1))),
            sequence: n(r, 6) as i32,
            column: s(r, 5),
            target: TableRef::new(Namespace::new(None, opt(r, 0).as_deref()), &s(r, 1)),
            target_column: s(r, 2),
            on_update: introspect::referential_action(rule(r, 7)),
            on_delete: introspect::referential_action(rule(r, 8)),
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

pub async fn relations(conn: &PgConn, table: &TableRef) -> Result<Vec<TableRelation>> {
    let rows = |rows: Vec<Row>| -> Vec<RelationRow> {
        rows.iter()
            .map(|r| RelationRow {
                name: opt(r, 9).unwrap_or_else(|| format!("{}->{}", s(r, 4), s(r, 1))),
                sequence: n(r, 6) as i32,
                from: TableRef::new(Namespace::new(None, opt(r, 3).as_deref()), &s(r, 4)),
                from_column: s(r, 5),
                to: TableRef::new(Namespace::new(None, opt(r, 0).as_deref()), &s(r, 1)),
                to_column: s(r, 2),
            })
            .collect()
    };
    // Kotlin groups each call's rows on their own, then drops duplicates (a self-reference is in both).
    let mut out = introspect::group_relations(rows(keys(conn, table, false).await?));
    for r in introspect::group_relations(rows(keys(conn, table, true).await?)) {
        if !out.contains(&r) {
            out.push(r);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_like_pgjdbc() {
        let numeric = |p: i64, s: i64| ((p << 16) | s) + 4;
        assert_eq!(
            (precision(1700, numeric(10, 2)), scale(1700, numeric(10, 2))),
            (10, 2)
        );
        assert_eq!(precision(1043, 124), 120);
        assert_eq!(precision(1114, -1), 29);
        assert_eq!(precision(1114, 1), 25);
        assert_eq!(precision(1184, -1), 35);
        assert_eq!(display_size(1231, -1), 131_089);
        assert_eq!(precision(1007, -1), 10);
    }
}

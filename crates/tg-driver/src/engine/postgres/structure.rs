//! `PostgreSQLCatalog` and `CockroachDBStructure` from `StructureReader.kt`: constraints, triggers and DDL from the
//! catalog, the relation addressed as `'"schema"."table"'::regclass`.

use super::conn::PgConn;
use super::literal::quote;
use crate::error::Result;
use crate::structure::statements;
use tg_sql::SqlDialect;
use tg_sql::model::{ConstraintInfo, ConstraintKind, TableDetails, TableStructure, TriggerInfo};

const D: SqlDialect = SqlDialect::PostgreSQL;

type Row = Vec<Option<String>>;

fn get(row: &Row, i: usize) -> String {
    row.get(i).cloned().flatten().unwrap_or_default()
}

fn flag(row: &Row, i: usize) -> bool {
    get(row, i) == "t"
}

pub async fn read(conn: &mut PgConn, details: TableDetails) -> Result<TableStructure> {
    let name = D.qualified_name(&details.table);
    let regclass = format!("{}::regclass", quote(&name)?);
    let cockroach = conn
        .text_rows("SELECT version()")
        .await?
        .first()
        .is_some_and(|r| get(r, 0).contains("CockroachDB"));
    let constraints = constraints(conn, &regclass).await?;
    let triggers = if cockroach {
        Vec::new()
    } else {
        triggers(conn, &regclass).await?
    };
    let ddl = if cockroach {
        let rows = conn.text_rows(&format!("SHOW CREATE TABLE {name}")).await?;
        let create = rows
            .first()
            .map(|r| get(r, r.len() - 1))
            .unwrap_or_default();
        format!("{};", create.trim().trim_end_matches(';'))
    } else {
        ddl(conn, &name, &regclass, &constraints, &triggers).await?
    };
    Ok(TableStructure {
        details,
        constraints,
        triggers,
        ddl,
    })
}

async fn constraints(conn: &PgConn, regclass: &str) -> Result<Vec<ConstraintInfo>> {
    let sql = format!(
        "SELECT c.conname, c.contype::text, pg_get_constraintdef(c.oid, true), \
                array_to_json(ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num, ord) \
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.num ORDER BY k.ord))::text \
         FROM pg_constraint c \
         WHERE c.conrelid = {regclass} AND c.contype IN ('p', 'u', 'f', 'c', 'x') \
         ORDER BY position(c.contype::text IN 'pufcx'), c.conname"
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| ConstraintInfo {
            name: get(r, 0),
            kind: match get(r, 1).as_str() {
                "p" => ConstraintKind::PrimaryKey,
                "u" => ConstraintKind::Unique,
                "f" => ConstraintKind::ForeignKey,
                "c" => ConstraintKind::Check,
                _ => ConstraintKind::Exclusion,
            },
            columns: serde_json::from_str(&get(r, 3)).unwrap_or_default(),
            definition: get(r, 2),
        })
        .collect())
}

async fn triggers(conn: &PgConn, regclass: &str) -> Result<Vec<TriggerInfo>> {
    let sql = format!(
        "SELECT t.tgname, \
                CASE WHEN t.tgtype::int & 2 <> 0 THEN 'BEFORE' WHEN t.tgtype::int & 64 <> 0 THEN 'INSTEAD OF' ELSE 'AFTER' END, \
                concat_ws(' OR ', CASE WHEN t.tgtype::int & 4 <> 0 THEN 'INSERT' END, CASE WHEN t.tgtype::int & 16 <> 0 THEN 'UPDATE' END, \
                          CASE WHEN t.tgtype::int & 8 <> 0 THEN 'DELETE' END, CASE WHEN t.tgtype::int & 32 <> 0 THEN 'TRUNCATE' END), \
                t.tgenabled <> 'D', pg_get_triggerdef(t.oid, true) \
         FROM pg_trigger t WHERE t.tgrelid = {regclass} AND NOT t.tgisinternal ORDER BY t.tgname"
    );
    Ok(conn
        .text_rows(&sql)
        .await?
        .iter()
        .map(|r| TriggerInfo {
            name: get(r, 0),
            timing: get(r, 1),
            events: get(r, 2),
            enabled: flag(r, 3),
            definition: get(r, 4),
        })
        .collect())
}

async fn ddl(
    conn: &PgConn,
    name: &str,
    regclass: &str,
    constraints: &[ConstraintInfo],
    triggers: &[TriggerInfo],
) -> Result<String> {
    let kind_rows = conn
        .text_rows(&format!(
            "SELECT c.relkind::text, CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END FROM pg_class c WHERE c.oid = {regclass}"
        ))
        .await?;
    let kind = kind_rows.first().map(|r| get(r, 0)).unwrap_or_default();
    let partition_key = kind_rows.first().and_then(|r| r.get(1).cloned().flatten());
    let view = |object: &str| format!("SELECT pg_get_viewdef({regclass}, true) -- {object}");
    let create = match kind.as_str() {
        "v" => format!(
            "CREATE OR REPLACE VIEW {name} AS\n{}",
            get(&conn.text_rows(&view("v")).await?[0], 0).trim_end()
        ),
        "m" => format!(
            "CREATE MATERIALIZED VIEW {name} AS\n{}",
            get(&conn.text_rows(&view("m")).await?[0], 0).trim_end()
        ),
        _ => create_table(conn, name, regclass, constraints, partition_key.as_deref()).await?,
    };
    // Indexes behind PRIMARY KEY / UNIQUE / EXCLUDE constraints are already part of CREATE TABLE.
    let indexes: Vec<String> = conn
        .text_rows(&format!(
            "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i WHERE i.indrelid = {regclass} \
             AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.conrelid = i.indrelid AND c.contype IN ('p', 'u', 'x')) \
             ORDER BY i.indexrelid::regclass::text"
        ))
        .await?
        .iter()
        .map(|r| get(r, 0))
        .collect();
    let object_type = match kind.as_str() {
        "v" => "VIEW",
        "m" => "MATERIALIZED VIEW",
        _ => "TABLE",
    };
    let comments: Vec<String> = conn
        .text_rows(&format!(
            "SELECT NULL, obj_description({regclass}, 'pg_class') UNION ALL \
             SELECT a.attname, col_description(a.attrelid, a.attnum) FROM pg_attribute a \
             WHERE a.attrelid = {regclass} AND a.attnum > 0 AND NOT a.attisdropped"
        ))
        .await?
        .into_iter()
        .filter_map(|r| {
            let comment = r.get(1).cloned().flatten()?;
            let target = match r.first().cloned().flatten() {
                None => format!("{object_type} {name}"),
                Some(column) => format!("COLUMN {name}.{}", D.quote_identifier(&column)),
            };
            Some(format!(
                "COMMENT ON {target} IS {}",
                D.literal(Some(&comment), false)
            ))
        })
        .collect();
    let definitions: Vec<String> = triggers.iter().map(|t| t.definition.clone()).collect();
    let disabled: Vec<String> = triggers
        .iter()
        .filter(|t| !t.enabled)
        .map(|t| {
            format!(
                "ALTER TABLE {name} DISABLE TRIGGER {}",
                D.quote_identifier(&t.name)
            )
        })
        .collect();
    Ok(statements(&[
        &[create],
        &indexes,
        &definitions,
        &disabled,
        &comments,
    ]))
}

async fn create_table(
    conn: &PgConn,
    name: &str,
    regclass: &str,
    constraints: &[ConstraintInfo],
    partition_key: Option<&str>,
) -> Result<String> {
    let columns: Vec<String> = conn
        .text_rows(&format!(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid), \
                    a.attidentity::text, a.attgenerated::text \
             FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum \
             WHERE a.attrelid = {regclass} AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum"
        ))
        .await?
        .iter()
        .map(|r| {
            let mut s = format!("{} {}", D.quote_identifier(&get(r, 0)), get(r, 1));
            let default = r.get(3).cloned().flatten();
            match (get(r, 4).as_str(), get(r, 5).as_str(), default) {
                ("a", _, _) => s += " GENERATED ALWAYS AS IDENTITY",
                ("d", _, _) => s += " GENERATED BY DEFAULT AS IDENTITY",
                (_, "s", d) => s += &format!(" GENERATED ALWAYS AS ({}) STORED", d.unwrap_or_else(|| "null".into())),
                (_, _, Some(d)) => s += &format!(" DEFAULT {d}"),
                _ => {}
            }
            if flag(r, 2) {
                s += " NOT NULL";
            }
            s
        })
        .collect();
    let body: Vec<String> = columns
        .into_iter()
        .chain(constraints.iter().map(|c| {
            format!(
                "CONSTRAINT {} {}",
                D.quote_identifier(&c.name),
                c.definition
            )
        }))
        .map(|line| format!("    {line}"))
        .collect();
    let partition = partition_key.map_or(String::new(), |k| format!(" PARTITION BY {k}"));
    Ok(format!(
        "CREATE TABLE {name} (\n{}\n){partition}",
        body.join(",\n")
    ))
}

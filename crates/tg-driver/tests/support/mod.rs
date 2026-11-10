//! Shared by the driver tests: connecting, seeding, and dumping what a connection reports as the JSON shape
//! `IntrospectionGoldenTest` (Kotlin) records.
#![allow(dead_code)]

use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;
use tg_driver::{Connection, ExecOptions, QueryResult, RelationalConnection, Secrets};
use tg_sql::SqlSplitter;
use tg_sql::model::{ColumnInfo, Namespace, TableDetails, TableRef, TableStructure};

pub fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .unwrap()
}

pub fn paths() -> TableGripPaths {
    TableGripPaths::new(std::env::temp_dir().join("tg-driver-tests-home"))
}

pub async fn connect(
    config: &ConnectionConfig,
    password: Option<&str>,
) -> Arc<dyn RelationalConnection> {
    let secrets = Secrets {
        password: password.map(str::to_owned),
        ssh: None,
    };
    match tg_driver::connect(config, &secrets, &paths())
        .await
        .expect("connect")
    {
        Connection::Relational(c) => c,
        _ => panic!("not relational"),
    }
}

/// Runs every statement of `script` in one console session.
pub async fn seed(db: &dyn RelationalConnection, script: &str) {
    let mut session = db.open_session().await.unwrap();
    for statement in SqlSplitter::new(db.dialect().syntax()).split(script) {
        session
            .execute(&statement.text, ExecOptions::default())
            .await
            .unwrap_or_else(|e| panic!("{}: {e}", statement.text));
    }
}

/// `host:port` from an environment variable, or None to skip.
pub fn server(var: &str) -> Option<(String, i32)> {
    let value = std::env::var(var).ok()?;
    let (host, port) = value.rsplit_once(':')?;
    Some((host.to_owned(), port.parse().ok()?))
}

fn namespace(ns: &Namespace) -> Value {
    json!({"catalog": ns.catalog, "schema": ns.schema})
}

fn table_ref(t: &TableRef) -> Value {
    json!({"namespace": namespace(&t.namespace), "name": t.name})
}

fn column(c: &ColumnInfo) -> Value {
    json!({
        "name": c.name, "typeName": c.type_name, "size": c.size, "nullable": c.nullable, "defaultValue": c.default_value,
        "autoIncrement": c.auto_increment, "primaryKey": c.primary_key, "jdbcType": c.jdbc_type, "scale": c.scale,
    })
}

fn details(d: &TableDetails) -> Value {
    json!({
        "table": table_ref(&d.table),
        "columns": d.columns.iter().map(column).collect::<Vec<_>>(),
        "indexes": d.indexes.iter().map(|i| json!({"name": i.name, "columns": i.columns, "unique": i.unique})).collect::<Vec<_>>(),
        "foreignKeys": d.foreign_keys.iter().map(|f| json!({
            "name": f.name, "columns": f.columns, "referencedTable": table_ref(&f.referenced_table),
            "referencedColumns": f.referenced_columns, "onUpdate": f.on_update, "onDelete": f.on_delete,
        })).collect::<Vec<_>>(),
        "primaryKeyName": d.primary_key_name,
    })
}

fn structure(s: &TableStructure) -> Value {
    json!({
        "details": details(&s.details),
        "constraints": s.constraints.iter().map(|c| json!({
            "name": c.name, "kind": format!("{:?}", c.kind), "columns": c.columns, "definition": c.definition,
        })).collect::<Vec<_>>(),
        "triggers": s.triggers.iter().map(|t| json!({
            "name": t.name, "timing": t.timing, "events": t.events, "enabled": t.enabled, "definition": t.definition,
        })).collect::<Vec<_>>(),
        "ddl": s.ddl,
    })
}

/// The same dump as the Kotlin test, for the namespaces `keep` picks.
pub async fn dump(
    db: &dyn RelationalConnection,
    queries: &[&str],
    keep: impl Fn(&Namespace) -> bool,
) -> Value {
    let all = db.namespaces().await.unwrap();
    let mut objects = Vec::new();
    for ns in all.iter().filter(|n| keep(n)) {
        let tables = db.tables(ns).await.unwrap();
        let mut columns_of = serde_json::Map::new();
        for (table, columns) in db.columns_of(ns).await.unwrap() {
            columns_of.insert(table, columns.iter().map(column).collect());
        }
        let (mut d, mut c, mut s, mut r) = (vec![], vec![], vec![], vec![]);
        for t in &tables {
            d.push(details(&db.table_details(&t.table).await.unwrap()));
            c.push(details(&db.table_columns(&t.table).await.unwrap()));
            s.push(structure(&db.table_structure(&t.table).await.unwrap()));
            let relations: Vec<Value> = db
                .relations(&t.table)
                .await
                .unwrap()
                .iter()
                .map(|r| json!({"from": table_ref(&r.from_table), "fromColumns": r.from_columns, "to": table_ref(&r.to_table), "toColumns": r.to_columns}))
                .collect();
            r.push(Value::from(relations));
        }
        objects.push(json!({
            "namespace": namespace(ns),
            "tables": tables.iter().map(|t| json!({"ref": table_ref(&t.table), "isView": t.is_view, "remarks": t.remarks})).collect::<Vec<_>>(),
            "columnsOf": columns_of, "details": d, "columns": c, "structures": s, "relations": r,
        }));
    }
    let mut session = db.open_session().await.unwrap();
    let mut results = Vec::new();
    for sql in queries {
        let QueryResult::Rows(rows) = session.execute(sql, ExecOptions::default()).await.unwrap()
        else {
            panic!("{sql} returned no rows")
        };
        results.push(json!({
            "sql": sql,
            "columns": rows.columns.iter().map(|c| json!({"name": c.name, "typeName": c.type_name, "jdbcType": c.jdbc_type})).collect::<Vec<_>>(),
            "rows": rows.rows,
        }));
    }
    json!({"namespaces": all.iter().map(namespace).collect::<Vec<_>>(), "objects": objects, "queries": results})
}

/// Fails with the first differences between `expected` and `actual`, by JSON path.
pub fn assert_json_eq(expected: &Value, actual: &Value) {
    let mut diffs = Vec::new();
    diff("$", expected, actual, &mut diffs);
    assert!(
        diffs.is_empty(),
        "{} difference(s):\n{}",
        diffs.len(),
        diffs
            .iter()
            .take(40)
            .cloned()
            .collect::<Vec<_>>()
            .join("\n")
    );
}

fn diff(path: &str, e: &Value, a: &Value, out: &mut Vec<String>) {
    match (e, a) {
        (Value::Object(x), Value::Object(y)) => {
            for (k, v) in x {
                diff(
                    &format!("{path}.{k}"),
                    v,
                    y.get(k).unwrap_or(&Value::Null),
                    out,
                );
            }
            for k in y.keys().filter(|k| !x.contains_key(*k)) {
                out.push(format!("{path}.{k}: unexpected"));
            }
        }
        (Value::Array(x), Value::Array(y)) if x.len() == y.len() => {
            for (i, (v, w)) in x.iter().zip(y).enumerate() {
                diff(&format!("{path}[{i}]"), v, w, out);
            }
        }
        _ if e != a => out.push(format!("{path}: expected {e}, got {a}")),
        _ => {}
    }
}

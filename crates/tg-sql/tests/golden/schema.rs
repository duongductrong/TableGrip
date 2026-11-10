//! Runners for `ddl/` and `schema/`.

use super::*;
use std::collections::{HashMap, HashSet};
use tg_sql::ddl::*;
use tg_sql::model::*;
use tg_sql::schema::{self, er_layout, mermaid};

const DDL_ENGINES: &[&str] = &[
    "PostgreSQL",
    "CockroachDB",
    "MySQL",
    "SQLite",
    "SqlServer",
    "ClickHouse",
    "DuckDB",
    "H2",
];

fn per_dialect(case: &Value, run: impl Fn(SqlDialect) -> Value) -> Value {
    let engines: Vec<String> = match case.get("dialects") {
        Some(d) => strings(d),
        None => DDL_ENGINES.iter().map(|s| (*s).to_owned()).collect(),
    };
    engines
        .iter()
        .map(|id| {
            (
                id.clone(),
                run(DatabaseType::by_id(id).unwrap().dialect().unwrap()),
            )
        })
        .collect::<serde_json::Map<_, _>>()
        .into()
}

fn namespace(v: &Value) -> Namespace {
    Namespace::new(opt_str(v, "catalog"), opt_str(v, "schema"))
}

pub fn table_ref(v: &Value) -> TableRef {
    TableRef::new(namespace(v), str(v, "name"))
}

fn ref_json(r: &TableRef) -> Value {
    json!({ "catalog": r.namespace.catalog, "schema": r.namespace.schema, "name": r.name })
}

fn int(v: &Value, key: &str) -> Option<i32> {
    v[key].as_i64().map(|n| n as i32)
}

fn bool_or(v: &Value, key: &str, default: bool) -> bool {
    v[key].as_bool().unwrap_or(default)
}

fn opt_string(v: &Value, key: &str) -> Option<String> {
    opt_str(v, key).map(str::to_owned)
}

pub fn column_info(v: &Value) -> ColumnInfo {
    ColumnInfo {
        name: str(v, "name").to_owned(),
        type_name: opt_str(v, "typeName").unwrap_or("integer").to_owned(),
        size: int(v, "size"),
        nullable: bool_or(v, "nullable", true),
        default_value: opt_string(v, "defaultValue"),
        auto_increment: flag(v, "autoIncrement"),
        primary_key: flag(v, "primaryKey"),
        jdbc_type: int(v, "jdbcType").unwrap_or(jdbc_types::OTHER),
        scale: int(v, "scale"),
    }
}

fn list<T>(v: &Value, f: impl Fn(&Value) -> T) -> Vec<T> {
    v.as_array()
        .map(|a| a.iter().map(f).collect())
        .unwrap_or_default()
}

fn index(v: &Value) -> IndexInfo {
    IndexInfo {
        name: str(v, "name").to_owned(),
        columns: strings(&v["columns"]),
        unique: flag(v, "unique"),
    }
}

fn index_json(i: &IndexInfo) -> Value {
    json!({ "name": i.name, "columns": i.columns, "unique": i.unique })
}

fn foreign_key(v: &Value) -> ForeignKeyInfo {
    ForeignKeyInfo {
        name: opt_str(v, "name").unwrap_or("").to_owned(),
        columns: strings(&v["columns"]),
        referenced_table: table_ref(&v["referencedTable"]),
        referenced_columns: strings(&v["referencedColumns"]),
        on_update: opt_string(v, "onUpdate"),
        on_delete: opt_string(v, "onDelete"),
    }
}

fn foreign_key_json(f: &ForeignKeyInfo) -> Value {
    json!({
        "name": f.name,
        "columns": f.columns,
        "referencedTable": ref_json(&f.referenced_table),
        "referencedColumns": f.referenced_columns,
        "onUpdate": f.on_update,
        "onDelete": f.on_delete,
    })
}

pub fn details(v: &Value) -> TableDetails {
    TableDetails {
        table: table_ref(&v["table"]),
        columns: list(&v["columns"], column_info),
        indexes: list(&v["indexes"], index),
        foreign_keys: list(&v["foreignKeys"], foreign_key),
        primary_key_name: opt_string(v, "primaryKeyName"),
    }
}

fn design_column(v: &Value) -> DesignColumn {
    DesignColumn {
        name: str(v, "name").to_owned(),
        type_: str(v, "type").to_owned(),
        nullable: bool_or(v, "nullable", true),
        default_value: opt_string(v, "defaultValue"),
        auto_increment: flag(v, "autoIncrement"),
        original_name: opt_string(v, "originalName"),
    }
}

fn design_column_json(c: &DesignColumn) -> Value {
    json!({
        "name": c.name,
        "type": c.type_,
        "nullable": c.nullable,
        "defaultValue": c.default_value,
        "autoIncrement": c.auto_increment,
        "originalName": c.original_name,
    })
}

fn design(v: &Value) -> TableDesign {
    TableDesign {
        table: table_ref(&v["table"]),
        columns: list(&v["columns"], design_column),
        primary_key: strings(&v["primaryKey"]),
        indexes: list(&v["indexes"], index),
        foreign_keys: list(&v["foreignKeys"], foreign_key),
        primary_key_name: opt_string(v, "primaryKeyName"),
        unique_constraints: list(&v["uniqueConstraints"], index),
        checks: strings(&v["checks"]),
        triggers: strings(&v["triggers"]),
    }
}

fn design_json(d: &TableDesign) -> Value {
    json!({
        "table": ref_json(&d.table),
        "columns": d.columns.iter().map(design_column_json).collect::<Vec<_>>(),
        "primaryKey": d.primary_key,
        "indexes": d.indexes.iter().map(index_json).collect::<Vec<_>>(),
        "foreignKeys": d.foreign_keys.iter().map(foreign_key_json).collect::<Vec<_>>(),
        "primaryKeyName": d.primary_key_name,
        "uniqueConstraints": d.unique_constraints.iter().map(index_json).collect::<Vec<_>>(),
        "checks": d.checks,
        "triggers": d.triggers,
    })
}

fn script_json(s: &DdlScript) -> Value {
    json!({
        "statements": s.statements.iter().map(|st| json!({ "sql": st.sql, "phase": st.phase.name() })).collect::<Vec<_>>(),
        "warnings": s.warnings,
        "transactional": s.transactional,
        "text": s.text(),
    })
}

fn change(v: &Value) -> TableChange {
    let side = |k: &str| v.get(k).filter(|x| x.is_object()).map(design);
    TableChange::new(side("original"), side("target"))
}

pub fn script(case: &Value) -> Value {
    let changes = list(&case["changes"], change);
    per_dialect(case, |d| {
        script_json(&DdlGenerator::new(d).script(&changes))
    })
}

fn constraint(v: &Value) -> ConstraintInfo {
    let kind = match str(v, "kind") {
        "PrimaryKey" => ConstraintKind::PrimaryKey,
        "Unique" => ConstraintKind::Unique,
        "ForeignKey" => ConstraintKind::ForeignKey,
        "Check" => ConstraintKind::Check,
        _ => ConstraintKind::Exclusion,
    };
    ConstraintInfo {
        name: opt_str(v, "name").unwrap_or("").to_owned(),
        kind,
        columns: strings(&v["columns"]),
        definition: opt_str(v, "definition").unwrap_or("").to_owned(),
    }
}

fn trigger(v: &Value) -> TriggerInfo {
    TriggerInfo {
        name: str(v, "name").to_owned(),
        timing: opt_str(v, "timing").unwrap_or("AFTER").to_owned(),
        events: opt_str(v, "events").unwrap_or("INSERT").to_owned(),
        enabled: bool_or(v, "enabled", true),
        definition: str(v, "definition").to_owned(),
    }
}

pub fn design_from(case: &Value) -> Value {
    let details = details(&case["details"]);
    let constraints = list(&case["constraints"], constraint);
    let triggers = list(&case["triggers"], trigger);
    per_dialect(case, |d| {
        design_json(&TableDesign::from(&details, d, &constraints, &triggers))
    })
}

pub fn column_types(case: &Value) -> Value {
    match str(case, "op") {
        "declaredType" => {
            let column = column_info(&case["column"]);
            per_dialect(case, |d| {
                let (declared, identity) = column_types::declared_type(&column, d);
                json!([declared, identity])
            })
        }
        "defaultExpression" => {
            let column = column_info(&case["column"]);
            per_dialect(case, |d| {
                json!(column_types::default_expression(&column, d))
            })
        }
        "normalize" => strings(&case["values"])
            .iter()
            .map(|v| json!(column_types::normalize(v)))
            .collect(),
        "normalizeDefault" => case["values"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| json!(column_types::normalize_default(v.as_str())))
            .collect(),
        "commonTypes" => per_dialect(case, |d| json!(column_types::common_types(d))),
        other => panic!("unknown column-types op {other}"),
    }
}

pub fn compare(case: &Value) -> Value {
    let comparison = schema::compare(
        &list(&case["source"], design),
        &list(&case["target"], design),
        &namespace(&case["targetNamespace"]),
    );
    let selection: HashSet<String> = match case.get("selection") {
        Some(s) => strings(s).into_iter().collect(),
        None => comparison.default_selection().into_iter().collect(),
    };
    let diffs: Vec<Value> = comparison
        .diffs
        .iter()
        .map(|diff| {
            json!({
                "name": diff.name,
                "kind": diff.kind.name(),
                "id": diff.id(),
                "items": diff.items.iter().map(|item| json!({
                    "type": item.kind_name(),
                    "id": item.id(),
                    "key": diff.item_key(item),
                    "label": item.label(),
                    "destructive": item.destructive(),
                })).collect::<Vec<_>>(),
            })
        })
        .collect();
    json!({
        "diffs": diffs,
        "identical": comparison.identical,
        "defaultSelection": comparison.default_selection(),
        "migration": per_dialect(case, |d| script_json(&schema::migration(&comparison, &selection, &DdlGenerator::new(d)))),
    })
}

pub fn er(case: &Value) -> Value {
    let tables = list(&case["tables"], details);
    let widths: HashMap<String, f32> = case["widths"]
        .as_object()
        .map(|m| {
            m.iter()
                .map(|(k, v)| (k.clone(), v.as_f64().unwrap() as f32))
                .collect()
        })
        .unwrap_or_default();
    let boxes = er_layout::layout(&tables, |t| {
        widths.get(&t.table.name).copied().unwrap_or(200.0)
    });
    let edges: Vec<Value> = er_layout::edges(&tables)
        .iter()
        .map(|e| {
            json!({
                "from": e.from.name,
                "fromColumns": e.from_columns,
                "to": e.to.name,
                "toColumns": e.to_columns,
                "optional": e.optional,
            })
        })
        .collect();
    let boxes: serde_json::Map<String, Value> = boxes
        .iter()
        .map(|(r, b)| {
            let f = |x: f32| json!(x.to_bits() as i32);
            (
                r.name.clone(),
                json!([f(b.x), f(b.y), f(b.width), f(b.height)]),
            )
        })
        .collect();
    let mut out = json!({ "edges": edges, "boxes": boxes });
    if let Some(focus) = opt_str(case, "focus") {
        let r = &tables.iter().find(|t| t.table.name == focus).unwrap().table;
        out["neighbourhood"] = er_layout::neighbourhood(&tables, r)
            .iter()
            .map(|t| json!(t.table.name))
            .collect();
    }
    out
}

pub fn mermaid(case: &Value) -> Value {
    let tables = list(&case["tables"], details);
    per_dialect(case, |d| json!(mermaid::er_diagram(&tables, d)))
}

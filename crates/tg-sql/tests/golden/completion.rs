//! Runners for `completion/`: the caret is the `|` in a case's text.

use super::*;
use tg_sql::completion::*;

const DEFAULT_SCHEMA: &str = r#"{
  "defaultNamespace": "public",
  "namespaces": ["public", "audit"],
  "tables": {
    "public": [{"name": "users"}, {"name": "orders"}, {"name": "teams"}, {"name": "order_items"}, {"name": "active_users", "view": true}],
    "audit": [{"name": "events"}]
  },
  "columns": {
    "users": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "name", "type": "text"}, {"name": "email", "type": "text"}, {"name": "team_id", "type": "int4"}],
    "orders": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "user_id", "type": "int4"}, {"name": "total", "type": "numeric"}, {"name": "status", "type": "text"}],
    "teams": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "name", "type": "text"}],
    "order_items": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "order_id", "type": "int4"}],
    "events": [{"name": "id", "type": "int8", "primaryKey": true}, {"name": "payload", "type": "jsonb"}]
  },
  "relations": [
    {"fromTable": "orders", "fromColumns": ["user_id"], "toTable": "users", "toColumns": ["id"]},
    {"fromTable": "users", "fromColumns": ["team_id"], "toTable": "teams", "toColumns": ["id"]},
    {"fromTable": "order_items", "fromColumns": ["order_id"], "toTable": "orders", "toColumns": ["id"]}
  ]
}"#;

/// The marked text without its `|`, and the caret's byte offset.
fn caret_text(marked: &str) -> (String, usize) {
    let caret = marked.find('|').unwrap();
    (
        format!("{}{}", &marked[..caret], &marked[caret + 1..]),
        caret,
    )
}

struct JsonSchema(Value);

impl SchemaProvider for JsonSchema {
    fn default_namespace(&self) -> Option<String> {
        opt_str(&self.0, "defaultNamespace").map(str::to_owned)
    }

    fn namespaces(&self) -> Vec<String> {
        strings(&self.0["namespaces"])
    }

    fn tables(&self, namespace: Option<&str>) -> Option<Vec<TableEntry>> {
        let key = namespace
            .map(str::to_owned)
            .or_else(|| self.default_namespace());
        let entries = key.and_then(|k| self.0["tables"].get(&k).cloned());
        Some(
            entries
                .and_then(|e| e.as_array().cloned())
                .unwrap_or_default()
                .iter()
                .map(|t| TableEntry {
                    name: str(t, "name").to_owned(),
                    is_view: flag(t, "view"),
                })
                .collect(),
        )
    }

    fn columns(&self, _namespace: Option<&str>, table: &str) -> Option<Vec<ColumnEntry>> {
        let columns = self.0["columns"].get(table)?.as_array()?;
        Some(
            columns
                .iter()
                .map(|c| ColumnEntry {
                    name: str(c, "name").to_owned(),
                    type_: str(c, "type").to_owned(),
                    primary_key: flag(c, "primaryKey"),
                })
                .collect(),
        )
    }

    fn relations(&self, _namespace: Option<&str>, table: &str) -> Option<Vec<Relation>> {
        let relations = self.0.get("relations")?.as_array()?;
        Some(
            relations
                .iter()
                .map(|r| Relation {
                    from_table: str(r, "fromTable").to_owned(),
                    from_columns: strings(&r["fromColumns"]),
                    to_table: str(r, "toTable").to_owned(),
                    to_columns: strings(&r["toColumns"]),
                })
                .filter(|r| r.from_table == table || r.to_table == table)
                .collect(),
        )
    }
}

fn result_json(text: &str, result: &CompletionResult) -> Value {
    let items: Vec<Value> = result
        .items
        .iter()
        .map(|i| {
            json!({
                "label": i.label,
                "insertText": i.insert_text,
                "kind": i.kind.name(),
                "detail": i.detail,
                "caretOffset": u16(&i.insert_text, i.caret_offset),
            })
        })
        .collect();
    let value = result.value.as_ref().map_or(Value::Null, |v| {
        json!({
            "namespace": v.namespace,
            "table": v.table,
            "column": v.column,
            "prefix": v.prefix,
            "quoted": v.quoted,
        })
    });
    let mut pending = result.pending_tables.clone();
    let key = |(ns, name): &(Option<String>, String)| {
        format!("{}.{name}", ns.as_deref().unwrap_or("null"))
    };
    pending.sort_by_key(key);
    json!({
        "replaceStart": u16(text, result.replace_start),
        "replaceEnd": u16(text, result.replace_end),
        "prefix": result.prefix,
        "items": items,
        "value": value,
        "pendingTables": pending.iter().map(|(ns, name)| json!([ns, name])).collect::<Vec<_>>(),
    })
}

pub fn sql(case: &Value) -> Value {
    let completer = SqlCompleter::new(super::filter::dialect_named(case));
    match opt_str(case, "op").unwrap_or("complete") {
        "complete" => {
            let (text, caret) = caret_text(str(case, "text"));
            let schema = JsonSchema(match case.get("schema") {
                Some(s) => s.clone(),
                None => serde_json::from_str(DEFAULT_SCHEMA).unwrap(),
            });
            let max = case["maxItems"].as_u64().map_or(60, |m| m as usize);
            result_json(&text, &completer.complete(&text, caret, &schema, max))
        }
        "tablesIn" => completer
            .tables_in(str(case, "text"))
            .iter()
            .map(|(ns, name)| json!([ns, name]))
            .collect(),
        "quote" => strings(&case["names"])
            .iter()
            .map(|n| json!(completer.quote(n)))
            .collect(),
        other => panic!("unknown sql-completion op {other}"),
    }
}

fn document_fields() -> Vec<DocumentField> {
    let f = |path: &str, types: &[&str], values: &[&str]| DocumentField {
        path: path.to_owned(),
        types: types.iter().map(|s| (*s).to_owned()).collect(),
        values: values.iter().map(|s| (*s).to_owned()).collect(),
    };
    vec![
        f(
            "_id",
            &["objectId"],
            &["ObjectId('65f0c0ffee0000000000abcd')"],
        ),
        f("status", &["string"], &["'A'", "'B'", "'archived'"]),
        f("total", &["number"], &["30", "10"]),
        f("tags", &["array"], &["'pen'", "'ink'"]),
        f("items", &["array"], &[]),
        f("items.sku", &["string"], &["'X-1'"]),
        f("items.qty", &["number"], &[]),
        f("address", &["object"], &[]),
        f("address.city", &["string"], &["'London'"]),
        f("first name", &["string"], &[]),
    ]
}

pub fn document(case: &Value) -> Value {
    let (text, caret) = caret_text(str(case, "text"));
    let fields = match case["fields"].as_array() {
        Some(fields) => fields
            .iter()
            .map(|f| DocumentField {
                path: str(f, "path").to_owned(),
                types: strings(&f["types"]),
                values: strings(&f["values"]),
            })
            .collect(),
        None => document_fields(),
    };
    let query = match opt_str(case, "query").unwrap_or("Filter") {
        "Sort" => DocumentQuery::Sort,
        "Projection" => DocumentQuery::Projection,
        _ => DocumentQuery::Filter,
    };
    result_json(&text, &complete_document(&text, caret, query, &fields))
}

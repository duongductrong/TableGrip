//! Holds tg-data to the Kotlin core's golden files (subjects `data-*`); see `tg-sql/tests/support/harness.rs`.

#[path = "../../tg-sql/tests/support/harness.rs"]
mod harness;

use harness::str;
use serde_json::{Value, json};
use tg_data::{ClipboardTable, CopyFormat, ResultColumn, ResultFormats, TypeInference};
use tg_sql::model::{DatabaseType, jdbc_types as types};

fn jdbc(name: &str) -> i32 {
    match name {
        "TINYINT" => types::TINYINT,
        "SMALLINT" => types::SMALLINT,
        "INTEGER" => types::INTEGER,
        "BIGINT" => types::BIGINT,
        "FLOAT" => types::FLOAT,
        "REAL" => types::REAL,
        "DOUBLE" => types::DOUBLE,
        "NUMERIC" => types::NUMERIC,
        "DECIMAL" => types::DECIMAL,
        "VARCHAR" => types::VARCHAR,
        "DATE" => types::DATE,
        "TIMESTAMP" => types::TIMESTAMP,
        "BOOLEAN" => types::BOOLEAN,
        "BIT" => types::BIT,
        other => panic!("unknown JDBC type {other}"),
    }
}

/// `{i}` and `{i%k}` replaced by the row number and its remainder.
fn template(text: &str, i: usize) -> String {
    let mut out = String::new();
    let mut rest = text;
    while let Some(open) = rest.find("{i") {
        out.push_str(&rest[..open]);
        let tail = &rest[open + 2..];
        let close = tail.find('}').expect("unclosed template");
        match tail[..close].strip_prefix('%') {
            Some(k) => out.push_str(&(i % k.parse::<usize>().unwrap()).to_string()),
            None => out.push_str(&i.to_string()),
        }
        rest = &tail[close + 1..];
    }
    out.push_str(rest);
    out
}

fn texts(list: &Value) -> Vec<Option<String>> {
    list.as_array()
        .map(|a| a.iter().map(|v| v.as_str().map(str::to_owned)).collect())
        .unwrap_or_default()
}

fn rows(case: &Value) -> Vec<Vec<Option<String>>> {
    case.get("rows")
        .and_then(Value::as_array)
        .map(|rows| rows.iter().map(texts).collect())
        .unwrap_or_default()
}

fn inference(case: &Value) -> Value {
    let values: Vec<Option<String>> = match case.get("gen") {
        Some(gen_) => {
            let cell = gen_["cells"][0].as_str().unwrap();
            (0..gen_["count"].as_u64().unwrap() as usize)
                .map(|i| Some(template(cell, i)))
                .collect()
        }
        None => texts(&case["values"]),
    };
    let inferred = TypeInference::infer(values.iter().map(Option::as_deref));
    json!({
        "kind": inferred.kind.name(),
        "maxLength": inferred.max_length,
        "nullable": inferred.nullable,
    })
}

fn rows_json(rows: Vec<Vec<String>>) -> Value {
    rows.into_iter().map(|r| json!(r)).collect()
}

fn clipboard(case: &Value) -> Value {
    match str(case, "op") {
        "parse" => rows_json(ClipboardTable::parse(str(case, "text"))),
        "html" => {
            let header: Option<Vec<String>> = case
                .get("header")
                .filter(|h| !h.is_null())
                .map(|h| texts(h).into_iter().map(Option::unwrap).collect());
            json!(ClipboardTable::html(header.as_deref(), &rows(case)))
        }
        "roundtrip" => {
            let rows = rows(case);
            let width = rows.iter().map(Vec::len).max().unwrap();
            let columns: Vec<ResultColumn> = (0..width)
                .map(|i| ResultColumn::new(&format!("c{i}"), "text", types::VARCHAR))
                .collect();
            let tsv =
                ResultFormats::format(CopyFormat::Tsv, &columns, &rows, false, None, None).unwrap();
            json!({ "tsv": tsv, "parsed": rows_json(ClipboardTable::parse(&tsv)) })
        }
        other => panic!("unknown clipboard op {other}"),
    }
}

fn formats(case: &Value) -> Value {
    let format = *CopyFormat::ALL
        .iter()
        .find(|f| f.name() == str(case, "format"))
        .unwrap();
    let columns: Vec<ResultColumn> = case["columns"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| {
            let (name, ty) = c.as_str().unwrap().split_once(':').unwrap();
            ResultColumn::new(name, "", jdbc(ty))
        })
        .collect();
    let rows = rows(case);
    let header = case
        .get("includeHeader")
        .and_then(Value::as_bool)
        .unwrap_or(true);
    let table = case.get("tableName").and_then(Value::as_str);
    let run = |dialect| {
        ResultFormats::format(format, &columns, &rows, header, dialect, table)
            .map_or_else(|_| json!("error"), |text| json!(text))
    };
    if matches!(format, CopyFormat::SqlInsert | CopyFormat::SqlInList) {
        DatabaseType::ENTRIES
            .iter()
            .filter_map(|t| Some((t.id.to_owned(), run(Some(t.dialect()?)))))
            .collect::<serde_json::Map<_, _>>()
            .into()
    } else {
        run(None)
    }
}

fn run(subject: &str, case: &Value) -> Option<Value> {
    Some(match subject {
        "data-inference" => inference(case),
        "data-clipboard" => clipboard(case),
        "data-formats" => formats(case),
        _ => return None,
    })
}

#[test]
fn golden() {
    harness::check("tg-data", run);
}

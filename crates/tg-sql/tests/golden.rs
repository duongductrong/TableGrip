//! Holds tg-sql to the Kotlin core's golden files; see `support/harness.rs`.

#[path = "support/harness.rs"]
mod harness;

use harness::{opt_str, str, u16};
use serde_json::{Value, json};
use std::collections::HashMap;
use tg_sql::*;

fn syntax(case: &Value) -> SqlSyntax {
    match opt_str(case, "syntax").unwrap_or("Standard") {
        "PostgreSQL" => SqlSyntax::POSTGRESQL,
        "MySQL" => SqlSyntax::MYSQL,
        "SQLite" => SqlSyntax::SQLITE,
        "TSql" => SqlSyntax::TSQL,
        "ClickHouse" => SqlSyntax::CLICKHOUSE,
        "Standard" => SqlSyntax::STANDARD,
        other => panic!("unknown syntax {other}"),
    }
}

fn lexer(case: &Value) -> Value {
    let sql = str(case, "sql");
    SqlLexer::new(syntax(case))
        .tokenize(sql)
        .iter()
        .map(|t| {
            json!({
                "type": t.kind.name(),
                "start": u16(sql, t.start),
                "end": u16(sql, t.end),
                "text": t.text(sql),
            })
        })
        .collect()
}

fn splitter(case: &Value) -> Value {
    let sql = str(case, "sql");
    let splitter = SqlSplitter::new(syntax(case));
    let statements: Vec<Value> = splitter
        .split(sql)
        .iter()
        .map(|s| {
            json!({
                "text": s.text,
                "start": u16(sql, s.start),
                "end": u16(sql, s.end),
                "delimiterEnd": u16(sql, s.delimiter_end),
            })
        })
        .collect();
    let mut out = json!({ "statements": statements });
    if let Some(at) = case.get("at") {
        out["statementAt"] = at
            .as_array()
            .unwrap()
            .iter()
            .map(|o| {
                let offset = harness::byte(sql, o.as_u64().unwrap() as usize);
                splitter.statement_at(sql, offset).map_or(
                    Value::Null,
                    |s| json!({ "start": u16(sql, s.start), "end": u16(sql, s.end) }),
                )
            })
            .collect();
    }
    out
}

fn classifier(case: &Value) -> Value {
    let info = SqlClassifier::new(syntax(case)).classify(str(case, "sql"));
    json!({
        "kind": info.kind.name(),
        "verb": info.verb,
        "danger": info.danger,
        "changesData": info.changes_data(),
    })
}

fn formatter(case: &Value) -> Value {
    let indent = opt_str(case, "indent").unwrap_or("    ");
    let keyword_case = KeywordCase::of(opt_str(case, "keywordCase").unwrap_or("Upper"));
    Value::from(
        SqlFormatter::with_options(syntax(case), indent, keyword_case).format(str(case, "sql")),
    )
}

fn parameters(case: &Value) -> Value {
    let sql = str(case, "sql");
    let params = SqlParameters::new(syntax(case));
    let mut out = json!({ "find": params.find(sql) });
    if let Some(values) = case.get("values") {
        let values: HashMap<String, String> = serde_json::from_value(values.clone()).unwrap();
        out["substitute"] = params.substitute(sql, &values).into();
    }
    out
}

fn keywords(_case: &Value) -> Value {
    let mut all: Vec<&str> = tg_sql::keywords::all().iter().copied().collect();
    all.sort_unstable();
    json!(all)
}

fn run(subject: &str, case: &Value) -> Option<Value> {
    Some(match subject {
        "sql-lexer" => lexer(case),
        "sql-splitter" => splitter(case),
        "sql-classifier" => classifier(case),
        "sql-formatter" => formatter(case),
        "sql-parameters" => parameters(case),
        "sql-keywords" => keywords(case),
        _ => return None,
    })
}

#[test]
fn golden() {
    harness::check("tg-sql", run);
}

#[test]
fn utf16_offsets_round_trip() {
    let s = "a😀ồb";
    for b in [0, 1, 5, 8, 9] {
        assert_eq!(harness::byte(s, u16(s, b)), b);
    }
}

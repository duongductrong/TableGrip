//! Holds tg-sql to the Kotlin core's golden files; see `support/harness.rs`.

#[path = "golden/completion.rs"]
mod completion;
#[path = "golden/filter.rs"]
mod filter;
#[path = "support/harness.rs"]
mod harness;
#[path = "golden/schema.rs"]
mod schema;

use harness::{opt_str, str, u16};
use serde_json::{Value, json};
use std::collections::HashMap;
use tg_sql::filter::{ParamValue, SelectFunction, SqlParam, SqlProjection, ValueKind};
use tg_sql::model::{Namespace, TableRef};
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

fn table(v: &Value) -> TableRef {
    TableRef::new(
        Namespace::new(opt_str(v, "catalog"), opt_str(v, "schema")),
        str(v, "name"),
    )
}

fn projection(case: &Value) -> Option<SqlProjection> {
    let p = case.get("projection").filter(|p| p.is_object())?;
    Some(SqlProjection::new(
        str(p, "columns"),
        p["distinct"].as_bool().unwrap_or(false),
    ))
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .map(|a| a.iter().map(|s| s.as_str().unwrap().to_owned()).collect())
        .unwrap_or_default()
}

fn flag(case: &Value, key: &str) -> bool {
    case[key].as_bool().unwrap_or(false)
}

fn param(v: &Value) -> SqlParam {
    use chrono::{NaiveDate, NaiveDateTime, NaiveTime};
    let text = str(v, "value");
    let value = match str(v, "type") {
        "Text" => ParamValue::Text(text.to_owned()),
        "Long" => ParamValue::Long(text.parse().unwrap()),
        "Decimal" => ParamValue::Decimal(Decimal::parse(text).unwrap()),
        "Bool" => ParamValue::Bool(text == "true"),
        "Uuid" => ParamValue::Uuid(text.to_lowercase()),
        "Date" => ParamValue::Date(NaiveDate::parse_from_str(text, "%Y-%m-%d").unwrap()),
        "DateTime" => ParamValue::DateTime(
            NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M:%S%.f")
                .or_else(|_| NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M"))
                .unwrap(),
        ),
        "Time" => ParamValue::Time(
            NaiveTime::parse_from_str(text, "%H:%M:%S%.f")
                .or_else(|_| NaiveTime::parse_from_str(text, "%H:%M"))
                .unwrap(),
        ),
        other => panic!("unknown param type {other}"),
    };
    SqlParam::new(
        value,
        ValueKind::by_name(opt_str(v, "kind").unwrap_or("Text")).unwrap(),
    )
}

fn syntax_json(s: SqlSyntax) -> Value {
    json!({
        "backtickIdentifiers": s.backtick_identifiers,
        "doubleQuotedStrings": s.double_quoted_strings,
        "hashComments": s.hash_comments,
        "dollarQuotedStrings": s.dollar_quoted_strings,
        "backslashEscapes": s.backslash_escapes,
        "delimiterCommand": s.delimiter_command,
        "bracketIdentifiers": s.bracket_identifiers,
        "batchSeparator": s.batch_separator,
    })
}

fn names_opt(v: &Value) -> Option<Vec<String>> {
    v.as_array().map(|a| {
        a.iter()
            .filter_map(|s| s.as_str().map(str::to_owned))
            .collect()
    })
}

fn dialect_op(d: SqlDialect, case: &Value) -> Value {
    let pair = |p: Option<(String, String)>| p.map_or(Value::Null, |(a, b)| json!([a, b]));
    let order_by = || -> Vec<SortColumn> {
        case["orderBy"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|o| {
                        let dir = if o[1] == "Asc" {
                            SortDirection::Asc
                        } else {
                            SortDirection::Desc
                        };
                        SortColumn::new(o[0].as_str().unwrap(), dir)
                    })
                    .collect()
            })
            .unwrap_or_default()
    };
    let filter = opt_str(case, "filter");
    let int = |k: &str| case[k].as_i64().unwrap();
    match str(case, "op") {
        "properties" => json!({
            "name": d.name(),
            "syntax": syntax_json(d.syntax()),
            "tableTypes": d.table_types().iter().map(|(k, v)| (k.to_string(), json!(v))).collect::<serde_json::Map<_, _>>(),
            "supportsRegex": d.supports_regex(),
            "regexHonoursCase": d.regex_honours_case(),
            "functions": d.functions(),
        }),
        "quoteIdentifier" => d.quote_identifier(str(case, "value")).into(),
        "literal" => d
            .literal(opt_str(case, "value"), flag(case, "numeric"))
            .into(),
        "literalParam" => d.literal_param(&param(&case["param"])).into(),
        "textMatch" => pair(Some(d.text_match(
            str(case, "expression"),
            flag(case, "caseSensitive"),
            flag(case, "negated"),
        ))),
        "regexMatch" => pair(d.regex_match(
            str(case, "expression"),
            flag(case, "caseSensitive"),
            flag(case, "negated"),
        )),
        "textPattern" => d
            .text_pattern(str(case, "value"), flag(case, "caseSensitive"))
            .into(),
        "defaultNamespace" => d
            .default_namespace(&strings(&case["names"]), str(case, "database"))
            .into(),
        "explain" => json!(d.explain(str(case, "sql"))),
        "plainIdentifier" => strings(&case["names"])
            .iter()
            .map(|n| d.is_plain_identifier(n))
            .collect(),
        "qualifiedName" => d.qualified_name(&table(&case["table"])).into(),
        "selectPage" => d
            .select_page(
                &table(&case["table"]),
                filter,
                &order_by(),
                int("limit") as i32,
                int("offset"),
                projection(case).as_ref(),
            )
            .into(),
        "selectAll" => d
            .select_all(
                &table(&case["table"]),
                filter,
                &order_by(),
                projection(case).as_ref(),
            )
            .into(),
        "countRows" => d
            .count_rows(&table(&case["table"]), filter, projection(case).as_ref())
            .into(),
        "castToText" => d.cast_to_text(str(case, "expression")).into(),
        "samplePattern" => d.sample_pattern(str(case, "value")).into(),
        "sampleValues" => d
            .sample_values(
                &table(&case["table"]),
                str(case, "column"),
                int("limit") as i32,
                int("scan") as i32,
            )
            .into(),
        "selectFunction" => SelectFunction::ALL
            .iter()
            .map(|f| {
                let args = strings(&case["arguments"][f.name()]);
                (
                    f.name().to_owned(),
                    json!(d.select_function(*f, str(case, "expression"), &args)),
                )
            })
            .collect::<serde_json::Map<_, _>>()
            .into(),
        "timeBucket" => TimeBucket::ALL
            .iter()
            .map(|b| {
                (
                    b.name().to_owned(),
                    json!(d.time_bucket(str(case, "expression"), *b)),
                )
            })
            .collect::<serde_json::Map<_, _>>()
            .into(),
        "listNamespaces" => {
            match d.list_namespaces(names_opt(&case["schemas"]), names_opt(&case["catalogs"])) {
                None => "error".into(),
                Some(namespaces) => namespaces
                    .iter()
                    .map(|n| json!({ "catalog": n.catalog, "schema": n.schema }))
                    .collect(),
            }
        }
        other => panic!("unknown dialect op {other}"),
    }
}

fn dialect(case: &Value) -> Value {
    DatabaseType::ENTRIES
        .iter()
        .filter_map(|t| Some((t.id.to_owned(), dialect_op(t.dialect()?, case))))
        .collect::<serde_json::Map<_, _>>()
        .into()
}

fn run(subject: &str, case: &Value) -> Option<Value> {
    Some(match subject {
        "sql-lexer" => lexer(case),
        "sql-splitter" => splitter(case),
        "sql-classifier" => classifier(case),
        "sql-formatter" => formatter(case),
        "sql-parameters" => parameters(case),
        "sql-keywords" => keywords(case),
        "sql-dialect" => dialect(case),
        "filter-values" => filter::values(case),
        "filter-operators" => filter::operators(case),
        "filter-compiler" => filter::compiler(case),
        "projection" => filter::projection(case),
        "ddl-script" => schema::script(case),
        "ddl-design" => schema::design_from(case),
        "ddl-column-types" => schema::column_types(case),
        "schema-compare" => schema::compare(case),
        "er-layout" => schema::er(case),
        "mermaid" => schema::mermaid(case),
        "sql-completion" => completion::sql(case),
        "document-completion" => completion::document(case),
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

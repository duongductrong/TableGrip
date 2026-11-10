//! Runners for `filter/`: values, operators, the filter compiler and the projection compiler.

use super::*;
use chrono::{FixedOffset, NaiveDateTime, Weekday};
use tg_sql::filter::values as fv;
use tg_sql::filter::*;
use tg_sql::model::jdbc_types;

fn error(e: FilterValueError) -> Value {
    json!({ "error": e.0 })
}

fn zone(case: &Value) -> FixedOffset {
    let z = opt_str(case, "zone").unwrap_or("Z");
    if z == "Z" {
        return FixedOffset::east_opt(0).unwrap();
    }
    let sign = if z.starts_with('-') { -1 } else { 1 };
    let (h, m) = z[1..].split_once(':').unwrap();
    FixedOffset::east_opt(
        sign * (h.parse::<i32>().unwrap() * 3600 + m.parse::<i32>().unwrap() * 60),
    )
    .unwrap()
}

fn date_time(text: &str) -> NaiveDateTime {
    NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M:%S")
        .or_else(|_| NaiveDateTime::parse_from_str(text, "%Y-%m-%dT%H:%M"))
        .unwrap()
}

pub fn values(case: &Value) -> Value {
    let text = || str(case, "text");
    let result: Result<Value, FilterValueError> = (|| {
        Ok(match str(case, "op") {
            "valueKind" => ValueKind::of(
                str(case, "typeName"),
                case["jdbcType"]
                    .as_i64()
                    .map_or(jdbc_types::OTHER, |j| j as i32),
                case["size"].as_i64().map(|s| s as i32),
            )
            .name()
            .into(),
            "temporal" => {
                let now = date_time(opt_str(case, "now").unwrap_or("2026-10-28T13:45"));
                let (kind, value) = match fv::temporal(text(), now, &zone(case))? {
                    TemporalValue::Day(d) => ("Day", fv::format_date(d)),
                    TemporalValue::Moment(dt) => ("Moment", fv::format_date_time(dt)),
                    TemporalValue::TimeOfDay(t) => ("TimeOfDay", fv::format_time(t)),
                };
                json!({ "type": kind, "value": value })
            }
            "parseList" => json!(fv::parse_list(text())),
            "number" => fv::number(text())?.to_plain_string().into(),
            "uuid" => fv::uuid(text())?.into(),
            "boolean" => json!(fv::boolean(text())),
            "format" => fv::format(&param(&case["param"]).value).into(),
            other => panic!("unknown filter-values op {other}"),
        })
    })();
    result.unwrap_or_else(error)
}

fn sections<T>(sections: Vec<Vec<T>>, name: impl Fn(&T) -> &'static str) -> Value {
    sections
        .iter()
        .map(|s| s.iter().map(&name).collect::<Vec<_>>())
        .collect::<Vec<_>>()
        .into()
}

pub fn operators(_case: &Value) -> Value {
    let mut out = serde_json::Map::new();
    for &kind in ValueKind::ALL {
        let labels: serde_json::Map<_, _> = FilterOperator::ALL
            .iter()
            .map(|op| (op.name().to_owned(), json!(op.label(kind))))
            .collect();
        out.insert(
            kind.name().to_owned(),
            json!({
                "label": kind.label(),
                "isTemporal": kind.is_temporal(),
                "default": FilterOperator::default_for(kind).name(),
                "forKind regex=true": sections(FilterOperator::for_kind(kind, true), |o| o.name()),
                "forKind regex=false": sections(FilterOperator::for_kind(kind, false), |o| o.name()),
                "labels": labels,
            }),
        );
    }
    let operators: serde_json::Map<_, _> = FilterOperator::ALL
        .iter()
        .map(|op| {
            let arity = match op.arity() {
                Arity::None => "None",
                Arity::One => "One",
                Arity::Two => "Two",
                Arity::List => "List",
            };
            (
                op.name().to_owned(),
                json!({ "arity": arity, "matchesText": op.matches_text() }),
            )
        })
        .collect();
    out.insert("operators".to_owned(), operators.into());
    out.into()
}

pub fn dialect_named(case: &Value) -> SqlDialect {
    DatabaseType::by_id(opt_str(case, "dialect").unwrap_or("PostgreSQL"))
        .unwrap()
        .dialect()
        .unwrap()
}

fn columns(case: &Value, default: &[(&str, &str, ValueKind)]) -> Vec<FilterColumn> {
    match case["columns"].as_array() {
        Some(cols) => cols
            .iter()
            .map(|c| {
                FilterColumn::new(
                    str(c, "name"),
                    str(c, "typeName"),
                    ValueKind::by_name(str(c, "kind")).unwrap(),
                )
            })
            .collect(),
        None => default
            .iter()
            .map(|(n, t, k)| FilterColumn::new(n, t, *k))
            .collect(),
    }
}

fn id(v: &Value) -> i64 {
    v["id"].as_i64().unwrap_or(0)
}

fn enabled(v: &Value) -> bool {
    v["enabled"].as_bool().unwrap_or(true)
}

fn text_or_empty(v: &Value, key: &str) -> String {
    opt_str(v, key).unwrap_or("").to_owned()
}

pub fn node(v: &Value) -> FilterNode {
    match str(v, "type") {
        "cond" => FilterNode::Condition(FilterCondition {
            id: id(v),
            column: str(v, "column").to_owned(),
            operator: FilterOperator::by_name(str(v, "op")).unwrap(),
            value: text_or_empty(v, "value"),
            value2: text_or_empty(v, "value2"),
            case_sensitive: flag(v, "caseSensitive"),
            enabled: enabled(v),
        }),
        "group" => FilterNode::Group(group(v)),
        "raw" => FilterNode::Raw(RawCondition {
            id: id(v),
            sql: str(v, "sql").to_owned(),
            enabled: enabled(v),
        }),
        other => panic!("unknown node type {other}"),
    }
}

pub fn group(v: &Value) -> FilterGroup {
    FilterGroup {
        id: id(v),
        combinator: if opt_str(v, "combinator") == Some("Any") {
            Combinator::Any
        } else {
            Combinator::All
        },
        children: v["children"]
            .as_array()
            .map(|c| c.iter().map(node).collect())
            .unwrap_or_default(),
        negated: flag(v, "negated"),
        enabled: enabled(v),
    }
}

pub fn param_json(p: &SqlParam) -> Value {
    let kind = match &p.value {
        ParamValue::Text(_) => "Text",
        ParamValue::Long(_) => "Long",
        ParamValue::Decimal(_) => "Decimal",
        ParamValue::Bool(_) => "Bool",
        ParamValue::Uuid(_) => "Uuid",
        ParamValue::Date(_) => "Date",
        ParamValue::DateTime(_) => "DateTime",
        ParamValue::Time(_) => "Time",
    };
    json!({ "type": kind, "value": fv::format(&p.value), "kind": p.kind.name() })
}

fn issues(issues: &std::collections::BTreeMap<i64, String>) -> Value {
    issues
        .iter()
        .map(|(id, m)| (id.to_string(), json!(m)))
        .collect::<serde_json::Map<_, _>>()
        .into()
}

const FILTER_COLUMNS: &[(&str, &str, ValueKind)] = &[
    ("name", "text", ValueKind::Text),
    ("total", "numeric", ValueKind::Number),
    ("qty", "int4", ValueKind::Number),
    ("active", "bool", ValueKind::Boolean),
    ("born", "date", ValueKind::Date),
    ("created_at", "timestamptz", ValueKind::DateTime),
    ("opens", "time", ValueKind::Time),
    ("token", "uuid", ValueKind::Uuid),
    ("payload", "jsonb", ValueKind::Json),
    ("mood", "mood", ValueKind::Other),
    ("photo", "bytea", ValueKind::Binary),
];

pub fn compiler(case: &Value) -> Value {
    let now = date_time(opt_str(case, "now").unwrap_or("2026-10-28T13:45:30"));
    let week_start = if opt_str(case, "weekLocale") == Some("en-US") {
        Weekday::Sun
    } else {
        Weekday::Mon
    };
    let compiled = FilterCompiler::new(
        dialect_named(case),
        &columns(case, FILTER_COLUMNS),
        now,
        zone(case),
    )
    .with_week_start(week_start)
    .compile(&group(&case["root"]));
    let filter = compiled.filter.as_ref().map_or(Value::Null, |f| {
        json!({
            "sql": f.sql,
            "preview": f.preview,
            "parameters": f.parameters.iter().map(param_json).collect::<Vec<_>>(),
        })
    });
    json!({ "filter": filter, "issues": issues(&compiled.issues) })
}

const PROJECTION_COLUMNS: &[(&str, &str, ValueKind)] = &[
    ("id", "int4", ValueKind::Number),
    ("name", "text", ValueKind::Text),
    ("total", "float8", ValueKind::Number),
    ("created_at", "timestamptz", ValueKind::DateTime),
    ("photo", "bytea", ValueKind::Binary),
];

fn item(v: &Value) -> SelectItem {
    match str(v, "type") {
        "field" => SelectItem::Field(SelectField {
            id: id(v),
            column: str(v, "column").to_owned(),
            function: SelectFunction::by_name(opt_str(v, "function").unwrap_or("None")).unwrap(),
            argument: text_or_empty(v, "argument"),
            alias: text_or_empty(v, "alias"),
            enabled: enabled(v),
        }),
        "expr" => SelectItem::Expression(SelectExpression {
            id: id(v),
            sql: str(v, "sql").to_owned(),
            alias: text_or_empty(v, "alias"),
            enabled: enabled(v),
        }),
        other => panic!("unknown item type {other}"),
    }
}

pub fn projection(case: &Value) -> Value {
    let dialect = dialect_named(case);
    match opt_str(case, "op").unwrap_or("compile") {
        "forKind" => ValueKind::ALL
            .iter()
            .map(|k| {
                (
                    k.name().to_owned(),
                    sections(SelectFunction::for_kind(*k, dialect), |f| f.name()),
                )
            })
            .collect::<serde_json::Map<_, _>>()
            .into(),
        "compile" => {
            let compiler = ProjectionCompiler::new(dialect, &columns(case, PROJECTION_COLUMNS));
            let items: Vec<SelectItem> =
                case["items"].as_array().unwrap().iter().map(item).collect();
            let compiled = compiler.compile(&Projection {
                items: items.clone(),
                distinct: flag(case, "distinct"),
            });
            json!({
                "clause": compiled.projection.as_ref().map(SqlProjection::clause),
                "editable": compiled.editable,
                "issues": issues(&compiled.issues),
                "outputNames": items.iter().map(|i| compiler.output_name(i)).collect::<Vec<_>>(),
            })
        }
        other => panic!("unknown projection op {other}"),
    }
}

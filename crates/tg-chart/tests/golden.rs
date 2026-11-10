//! Holds tg-chart to the Kotlin core's golden files (subjects `chart-*`); see `tg-sql/tests/support/harness.rs`.

#[path = "../../tg-sql/tests/support/harness.rs"]
mod harness;

use harness::str;
use serde_json::{Value, json};
use tg_chart::values;
use tg_chart::{
    Aggregate, ChartColumn, ChartData, ChartPoint, ChartSpec, ChartType, ChartX, ColumnKind,
    ColumnProfile, ColumnProfiler, Measure, TimeBucket, aggregate_query, suggest,
};
use tg_sql::model::{DatabaseType, jdbc_types as types};

fn jdbc(name: &str) -> i32 {
    match name {
        "BIT" => types::BIT,
        "TINYINT" => types::TINYINT,
        "SMALLINT" => types::SMALLINT,
        "INTEGER" => types::INTEGER,
        "BIGINT" => types::BIGINT,
        "FLOAT" => types::FLOAT,
        "REAL" => types::REAL,
        "DOUBLE" => types::DOUBLE,
        "NUMERIC" => types::NUMERIC,
        "DECIMAL" => types::DECIMAL,
        "CHAR" => types::CHAR,
        "VARCHAR" => types::VARCHAR,
        "LONGVARCHAR" => types::LONGVARCHAR,
        "DATE" => types::DATE,
        "TIME" => types::TIME,
        "TIMESTAMP" => types::TIMESTAMP,
        "BINARY" => types::BINARY,
        "VARBINARY" => types::VARBINARY,
        "LONGVARBINARY" => types::LONGVARBINARY,
        "NULL" => types::NULL,
        "OTHER" => types::OTHER,
        "JAVA_OBJECT" => types::JAVA_OBJECT,
        "STRUCT" => types::STRUCT,
        "ARRAY" => types::ARRAY,
        "BLOB" => types::BLOB,
        "CLOB" => types::CLOB,
        "BOOLEAN" => types::BOOLEAN,
        "NVARCHAR" => types::NVARCHAR,
        "NCLOB" => types::NCLOB,
        "SQLXML" => types::SQLXML,
        "TIME_WITH_TIMEZONE" => types::TIME_WITH_TIMEZONE,
        "TIMESTAMP_WITH_TIMEZONE" => types::TIMESTAMP_WITH_TIMEZONE,
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
        let modulo = &tail[..close];
        match modulo.strip_prefix('%') {
            Some(k) => out.push_str(&(i % k.parse::<usize>().unwrap()).to_string()),
            None => {
                assert!(modulo.is_empty(), "bad template {text}");
                out.push_str(&i.to_string());
            }
        }
        rest = &tail[close + 1..];
    }
    out.push_str(rest);
    out
}

type Rows = Vec<Vec<Option<String>>>;

fn chart_rows(case: &Value) -> (Vec<ChartColumn>, Rows) {
    let columns = case["columns"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| {
            let (name, ty) = c.as_str().unwrap().split_once(':').unwrap();
            ChartColumn::new(name, "", jdbc(ty))
        })
        .collect();
    let rows = if let Some(gen_) = case.get("gen") {
        let cells: Vec<&str> = gen_["cells"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c.as_str().unwrap())
            .collect();
        (0..gen_["count"].as_u64().unwrap() as usize)
            .map(|i| cells.iter().map(|c| Some(template(c, i))).collect())
            .collect()
    } else {
        case["rows"]
            .as_array()
            .map(|rows| {
                rows.iter()
                    .map(|r| {
                        r.as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().map(str::to_owned))
                            .collect()
                    })
                    .collect()
            })
            .unwrap_or_default()
    };
    (columns, rows)
}

fn kind(name: &str) -> ColumnKind {
    match name {
        "Numeric" => ColumnKind::Numeric,
        "Temporal" => ColumnKind::Temporal,
        "Category" => ColumnKind::Category,
        "Other" => ColumnKind::Other,
        other => panic!("unknown kind {other}"),
    }
}

fn profiles_of(json: &Value) -> Vec<ColumnProfile> {
    json.as_array()
        .unwrap()
        .iter()
        .enumerate()
        .map(|(index, p)| ColumnProfile {
            index,
            name: p["name"].as_str().unwrap().to_owned(),
            type_name: String::new(),
            kind: kind(p["kind"].as_str().unwrap()),
            distinct: p["distinct"].as_u64().unwrap() as usize,
            nulls: p["nulls"].as_u64().unwrap_or(0) as usize,
            rows: p["rows"].as_u64().unwrap() as usize,
            min: p.get("min").and_then(Value::as_f64),
            max: p.get("max").and_then(Value::as_f64),
        })
        .collect()
}

fn chart_profiles(case: &Value) -> Vec<ColumnProfile> {
    match case.get("profiles") {
        Some(p) => profiles_of(p),
        None => {
            let (columns, rows) = chart_rows(case);
            ColumnProfiler::profile(&columns, &rows)
        }
    }
}

fn aggregate(name: &str) -> Aggregate {
    Aggregate::ALL
        .iter()
        .copied()
        .find(|a| a.name() == name)
        .unwrap_or_else(|| panic!("unknown aggregate {name}"))
}

fn spec_of(json: &Value) -> ChartSpec {
    ChartSpec {
        chart_type: ChartType::ALL
            .iter()
            .copied()
            .find(|t| t.name() == json["type"].as_str().unwrap())
            .unwrap(),
        x: json["x"].as_str().unwrap().to_owned(),
        y: json["y"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| {
                Measure::new(
                    m["column"].as_str().unwrap(),
                    m.get("aggregate")
                        .and_then(Value::as_str)
                        .map_or(Aggregate::None, aggregate),
                )
            })
            .collect(),
        series: json
            .get("series")
            .and_then(Value::as_str)
            .map(str::to_owned),
        bucket: json
            .get("bucket")
            .and_then(Value::as_str)
            .map(|b| TimeBucket::of(b).unwrap()),
        horizontal: json
            .get("horizontal")
            .and_then(Value::as_bool)
            .unwrap_or(false),
    }
}

fn spec_json(spec: &ChartSpec) -> Value {
    json!({
        "type": spec.chart_type.name(),
        "x": spec.x,
        "y": spec.y.iter().map(|m| json!({"column": m.column, "aggregate": m.aggregate.name()})).collect::<Vec<_>>(),
        "series": spec.series,
        "bucket": spec.bucket.map(TimeBucket::name),
        "horizontal": spec.horizontal,
    })
}

fn point_json(p: &ChartPoint) -> Value {
    let x = match &p.x {
        ChartX::Number(n) => json!(n),
        ChartX::Category(c) => json!(c),
    };
    json!({ "x": x, "group": p.group, "y": p.y })
}

fn opt_f64(v: Option<f64>) -> Value {
    v.map_or(Value::Null, |n| json!(n))
}

fn values_subject(case: &Value) -> Value {
    let text = case["text"].as_str();
    let mut out = json!({
        "number": opt_f64(values::number(text)),
        "epochMillis": opt_f64(values::epoch_millis(text)),
    });
    if let Some(bucket) = case.get("bucket").and_then(Value::as_str) {
        let bucket = TimeBucket::of(bucket).unwrap();
        out["truncated"] = opt_f64(
            values::date_time(text)
                .map(|t| values::epoch_millis_of(ChartData::truncate(t, bucket))),
        );
    }
    out
}

fn profile(case: &Value) -> Value {
    let (columns, rows) = chart_rows(case);
    ColumnProfiler::profile(&columns, &rows)
        .iter()
        .map(|p| {
            json!({
                "index": p.index,
                "name": p.name,
                "typeName": p.type_name,
                "kind": p.kind.name(),
                "distinct": p.distinct,
                "nulls": p.nulls,
                "rows": p.rows,
                "min": opt_f64(p.min),
                "max": opt_f64(p.max),
                "unique": p.unique(),
            })
        })
        .collect()
}

fn suggestions(case: &Value) -> Value {
    let profiles = chart_profiles(case);
    let mut out = json!({
        "specs": suggest::suggest(&profiles).iter().map(spec_json).collect::<Vec<_>>(),
    });
    if let Some(time) = profiles.iter().find(|p| p.kind == ColumnKind::Temporal) {
        out["bucket"] = json!(suggest::bucket_for(time).name());
    }
    out
}

fn names<T>(
    words: &Value,
    key: &str,
    of: impl Fn(&str) -> Option<T>,
    name: impl Fn(T) -> &'static str,
) -> Value {
    words[key]
        .as_array()
        .map(|list| {
            list.iter()
                .map(|w| of(w.as_str().unwrap()).map_or(Value::Null, |v| json!(name(v))))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default()
        .into()
}

fn spec_subject(case: &Value) -> Value {
    let mut out = serde_json::Map::new();
    if let Some(spec) = case.get("spec") {
        let profiles = chart_profiles(case);
        let spec = spec_of(spec);
        out.insert("validate".into(), json!(spec.validate(&profiles)));
        out.insert("adjusted".into(), spec_json(&spec.adjusted(&profiles)));
        out.insert("aggregates".into(), json!(spec.aggregates()));
        out.insert("columns".into(), json!(spec.columns()));
        out.insert(
            "labels".into(),
            spec.y.iter().map(Measure::label).collect::<Vec<_>>().into(),
        );
    }
    if let Some(words) = case.get("parse") {
        out.insert(
            "type".into(),
            names(words, "type", ChartType::of, ChartType::name),
        );
        out.insert(
            "aggregate".into(),
            names(words, "aggregate", Aggregate::of, Aggregate::name),
        );
        out.insert(
            "bucket".into(),
            names(words, "bucket", TimeBucket::of, TimeBucket::name),
        );
    }
    out.into()
}

fn data(case: &Value) -> Value {
    let (columns, rows) = chart_rows(case);
    let profiles = match case.get("profiles") {
        Some(p) => profiles_of(p),
        None => ColumnProfiler::profile(&columns, &rows),
    };
    let spec = spec_of(&case["spec"]);
    let frame = ChartData::build(&rows, &profiles, &spec);
    let points = if case.get("summary").and_then(Value::as_bool) == Some(true) {
        json!({
            "count": frame.points.len(),
            "first": frame.points.first().map_or(Value::Null, point_json),
            "last": frame.points.last().map_or(Value::Null, point_json),
            "sumY": frame.points.iter().fold(0.0, |sum, p| sum + p.y),
        })
    } else {
        frame.points.iter().map(point_json).collect()
    };
    json!({
        "xKind": frame.x_kind.name(),
        "points": points,
        "groups": frame.groups,
        "categories": frame.categories,
        "skippedRows": frame.skipped_rows,
        "omittedCategories": frame.omitted_categories,
        "mergedSeries": frame.merged_series,
        "downsampled": frame.downsampled,
        "xLabel": frame.x_label(),
        "yLabel": frame.y_label(),
    })
}

fn lttb(case: &Value) -> Value {
    let peak = case.get("peakAt").and_then(Value::as_u64);
    let points: Vec<ChartPoint> = (0..case["count"].as_u64().unwrap())
        .map(|i| {
            let y = match peak {
                Some(p) => {
                    if i == p {
                        1000.0
                    } else {
                        0.0
                    }
                }
                None => ((i * 7919) % 101) as f64,
            };
            ChartPoint {
                x: ChartX::Number(i as f64),
                group: "g".into(),
                y,
            }
        })
        .collect();
    ChartData::lttb(&points, case["threshold"].as_u64().unwrap() as usize)
        .iter()
        .map(|p| match p.x {
            ChartX::Number(n) => json!(n),
            ChartX::Category(_) => unreachable!(),
        })
        .collect()
}

fn query(case: &Value) -> Value {
    let spec = spec_of(&case["spec"]);
    DatabaseType::ENTRIES
        .iter()
        .filter_map(|t| {
            let dialect = t.dialect()?;
            let out = aggregate_query(dialect, str(case, "sql"), &spec).map_or(
                Value::Null,
                |a| json!({ "sql": a.sql, "spec": spec_json(&a.spec) }),
            );
            Some((t.id.to_owned(), out))
        })
        .collect::<serde_json::Map<_, _>>()
        .into()
}

fn run(subject: &str, case: &Value) -> Option<Value> {
    Some(match subject {
        "chart-values" => values_subject(case),
        "chart-profile" => profile(case),
        "chart-suggest" => suggestions(case),
        "chart-spec" => spec_subject(case),
        "chart-data" => data(case),
        "chart-lttb" => lttb(case),
        "chart-query" => query(case),
        _ => return None,
    })
}

#[test]
fn golden() {
    harness::check("tg-chart", run);
}

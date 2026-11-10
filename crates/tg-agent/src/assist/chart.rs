//! Asks an agent for a chart of a result, described in words (`assist/ChartAssist.kt`). It gets the query and the
//! result's columns with their kinds and value counts; values only in `samples`, which the caller fills only when the
//! connection shares data. The answer is a [`ChartSpec`], checked against the columns like one the user edits.

use super::{AssistInvalid, AssistTask, JsonObject, text};
use serde_json::Value;
use std::collections::HashMap;
use tg_chart::{
    Aggregate, ChartSpec, ChartType, ColumnKind, ColumnProfile, ColumnProfiler, Measure, TimeBucket,
};

/// A chart an agent proposes, with what it assumed so the user can check.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChartProposal {
    pub spec: ChartSpec,
    pub explanation: String,
    pub assumptions: Vec<String>,
}

pub struct ChartSpecTask {
    pub request: String,
    pub engine: String,
    pub sql: String,
    pub profiles: Vec<ColumnProfile>,
    /// A few values per column name; empty unless the connection shares data.
    pub samples: HashMap<String, Vec<String>>,
    /// Whether the rows are the first ones only, so grouping them would miss the rest.
    pub truncated: bool,
}

fn kind_name(kind: ColumnKind) -> &'static str {
    match kind {
        ColumnKind::Numeric => "numeric",
        ColumnKind::Temporal => "temporal",
        ColumnKind::Category => "category",
        ColumnKind::Other => "other",
    }
}

/// A string field where null, blank and "null" (which agents write for "none") all mean absent.
fn optional(json: &JsonObject, key: &str) -> Option<String> {
    if matches!(json.get(key), Some(Value::Null)) {
        return None;
    }
    text(json, key).filter(|t| !t.eq_ignore_ascii_case("null") && !t.eq_ignore_ascii_case("none"))
}

impl AssistTask for ChartSpecTask {
    type Output = ChartProposal;

    fn name(&self) -> &str {
        "chart-spec"
    }

    fn system(&self) -> String {
        "You choose charts for query results in TableGrip, a desktop database client.\n\n# Rules\n\
         - Use only the columns listed, by their exact names. Never invent a column or compute a new one.\n\
         - Pick the chart type that answers the request: line or area for trends over dates or numbers, bar for comparing categories, pie for shares of a whole (at most 8 slices, one measure), scatter for the relation between two numbers.\n\
         - y measures must be numeric columns, except with \"count\", which counts any column's non-null values.\n\
         - \"bucket\" groups dates (hour, day, week, month, quarter, year) and only applies when x is a date column; then every measure needs an aggregate (sum, avg, min, max, count).\n\
         - \"series\" splits one measure by a category column with few values.\n\
         - Say in \"assumptions\" what you inferred that the user didn't say, e.g. which column you took for \"revenue\".\n\
         - If the columns can't answer the request, still return the closest chart and say what's missing in \"explanation\"."
            .to_owned()
    }

    fn prompt(&self) -> String {
        let mut out = format!("Request: {}\n\n", self.request.trim());
        out.push_str(&format!(
            "The result comes from this {} query:\n```sql\n{}\n```\n\n",
            self.engine,
            self.sql.trim()
        ));
        out.push_str("# Columns\n");
        for c in &self.profiles {
            let type_name = if c.type_name.trim().is_empty() {
                "unknown type"
            } else {
                c.type_name.as_str()
            };
            out.push_str(&format!(
                "- {}: {} ({type_name}), ",
                c.name,
                kind_name(c.kind)
            ));
            if c.distinct >= ColumnProfiler::DISTINCT_CAP {
                out.push_str("many distinct values");
            } else {
                out.push_str(&format!("{} distinct values", c.distinct));
            }
            if c.nulls > 0 {
                out.push_str(&format!(", {} nulls", c.nulls));
            }
            if let Some(values) = self.samples.get(&c.name).filter(|v| !v.is_empty()) {
                let quoted: Vec<String> = values.iter().map(|v| format!("\"{v}\"")).collect();
                out.push_str(&format!(", e.g. {}", quoted.join(", ")));
            }
            out.push('\n');
        }
        out.push_str(&format!(
            "{} rows",
            self.profiles.first().map_or(0, |p| p.rows)
        ));
        if self.truncated {
            out.push_str(", the first ones only: the query returns more");
        }
        out.push_str(".\n\nReply with only this JSON object:\n");
        out.push_str("```json\n{\"type\": \"line|area|bar|scatter|pie\", \"x\": \"column\", \"y\": [{\"column\": \"column\", \"aggregate\": \"none|sum|avg|min|max|count\"}], \"series\": \"column or null\", \"bucket\": \"hour|day|week|month|quarter|year or null\", \"horizontal\": false, \"explanation\": \"one short sentence\", \"assumptions\": [\"…\"]}\n```");
        out
    }

    fn parse(&self, json: &JsonObject) -> Result<ChartProposal, AssistInvalid> {
        let mut reasons = Vec::new();
        let chart_type = text(json, "type").and_then(|t| ChartType::of(&t));
        if chart_type.is_none() {
            let names: Vec<String> = ChartType::ALL
                .iter()
                .map(|t| t.name().to_lowercase())
                .collect();
            reasons.push(format!("\"type\" must be one of {}.", names.join(", ")));
        }
        let x = text(json, "x");
        if x.is_none() {
            reasons.push("\"x\" must name a column.".into());
        }
        let y: Vec<Measure> = match json.get("y").and_then(Value::as_array) {
            None => {
                reasons.push("\"y\" must be a list of measures.".into());
                Vec::new()
            }
            Some(items) => items
                .iter()
                .filter_map(|item| {
                    let measure = item.as_object();
                    let Some(column) = measure.and_then(|m| text(m, "column")) else {
                        reasons.push("Each \"y\" entry needs a \"column\".".into());
                        return None;
                    };
                    let name = measure.and_then(|m| text(m, "aggregate"));
                    let aggregate = match &name {
                        None => Some(Aggregate::None),
                        Some(n) => Aggregate::of(n),
                    };
                    if aggregate.is_none() {
                        reasons.push(format!(
                            "Unknown aggregate \"{}\"; use none, sum, avg, min, max or count.",
                            name.unwrap_or_default()
                        ));
                    }
                    Some(Measure::new(&column, aggregate.unwrap_or(Aggregate::None)))
                })
                .collect(),
        };
        let bucket_name = optional(json, "bucket");
        let bucket = bucket_name.as_deref().and_then(TimeBucket::of);
        if let (Some(name), None) = (&bucket_name, bucket) {
            reasons.push(format!(
                "Unknown bucket \"{name}\"; use hour, day, week, month, quarter or year."
            ));
        }
        let (Some(chart_type), Some(x), true) = (chart_type, x, reasons.is_empty()) else {
            return Err(AssistInvalid(reasons));
        };
        let spec = ChartSpec {
            chart_type,
            x,
            y,
            series: optional(json, "series"),
            bucket,
            horizontal: json
                .get("horizontal")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        };
        let problems = spec.validate(&self.profiles);
        if !problems.is_empty() {
            return Err(AssistInvalid(problems));
        }
        let assumptions = json
            .get("assumptions")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_str)
                    .filter(|s| !s.trim().is_empty())
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_default();
        Ok(ChartProposal {
            spec,
            explanation: text(json, "explanation").unwrap_or_default(),
            assumptions,
        })
    }
}

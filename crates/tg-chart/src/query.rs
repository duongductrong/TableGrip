//! The server-side aggregate a chart can run instead of aggregating fetched rows (`ChartQuery.kt`).

use crate::spec::{Aggregate, ChartSpec, Measure};
use tg_sql::{SqlClassifier, SqlDialect, SqlSplitter, StatementKind, kt};

/// A statement that aggregates on the server what a chart would otherwise aggregate from the rows fetched, and the
/// spec for its result.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChartAggregate {
    pub sql: String,
    pub spec: ChartSpec,
}

/// Wraps a query in the GROUP BY `spec` describes, so a chart covers all rows rather than the first ones fetched. The
/// query runs unchanged as a subquery: its own filters and limits still apply. None when `spec` doesn't aggregate,
/// `sql` isn't a single SELECT, or the engine can't round dates to the spec's bucket.
pub fn aggregate_query(dialect: SqlDialect, sql: &str, spec: &ChartSpec) -> Option<ChartAggregate> {
    if !spec.aggregates() {
        return None;
    }
    let statements = SqlSplitter::new(dialect.syntax()).split(sql);
    let [only] = statements.as_slice() else {
        return None;
    };
    let trimmed = kt::trim(&only.text);
    let statement = kt::trim(trimmed.strip_suffix(';').unwrap_or(trimmed));
    let info = SqlClassifier::new(dialect.syntax()).classify(statement);
    if info.kind != StatementKind::Read || info.verb != "SELECT" {
        return None;
    }

    let column = |name: &str| format!("t.{}", dialect.quote_identifier(name));
    let x = match spec.bucket {
        None => column(&spec.x),
        Some(bucket) => dialect.time_bucket(&column(&spec.x), bucket)?,
    };
    let mut keys = vec![(x, spec.x.clone())];
    if let Some(series) = &spec.series {
        keys.push((column(series), series.clone()));
    }
    let measures: Vec<(String, String)> = spec
        .y
        .iter()
        .map(|measure| {
            // A measure without an aggregate still needs one once rows are grouped: the sum is what a chart would show.
            let aggregate = if measure.aggregate == Aggregate::None {
                Aggregate::Sum
            } else {
                measure.aggregate
            };
            let alias = Measure::new(&measure.column, aggregate).label();
            (
                format!("{}({})", aggregate.sql().unwrap(), column(&measure.column)),
                alias,
            )
        })
        .collect();
    let select = keys
        .iter()
        .chain(&measures)
        .map(|(expression, alias)| format!("{expression} AS {}", dialect.quote_identifier(alias)))
        .collect::<Vec<_>>()
        .join(", ");
    let group_by = keys
        .iter()
        .map(|(e, _)| e.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let text = format!(
        "SELECT {select}\nFROM (\n{statement}\n) t\nGROUP BY {group_by}\nORDER BY {group_by}"
    );
    // The result's columns hold the aggregates already: drawn as they are, keeping the type, x and series.
    let aggregated = ChartSpec {
        y: measures
            .iter()
            .map(|(_, alias)| Measure::new(alias, Aggregate::None))
            .collect(),
        bucket: None,
        ..spec.clone()
    };
    Some(ChartAggregate {
        sql: text,
        spec: aggregated,
    })
}

//! Charts picked from a result's columns alone (`ChartSuggest.kt`): dates and numbers make a line, a few categories
//! and a number make bars, two numbers a scatter. Names are never read, only kinds and value counts.

use crate::profile::{ColumnKind, ColumnProfile};
use crate::spec::{Aggregate, ChartSpec, ChartType, Measure};
use tg_sql::TimeBucket;

/// More categories than this make unreadable bars.
pub const MAX_CATEGORIES: usize = 30;
/// Few enough slices for a pie.
pub const MAX_SLICES: usize = 8;
/// Few enough values to split into series.
pub const MAX_SERIES: usize = 10;
/// Points on a line before dates are grouped rather than drawn one per row.
const MAX_LINE_POINTS: usize = 1000;
const DAY_MILLIS: f64 = 86_400_000.0;

/// Charts that fit `profiles`, the most fitting first; empty when none does.
pub fn suggest(profiles: &[ColumnProfile]) -> Vec<ChartSpec> {
    let temporal: Vec<&ColumnProfile> = profiles
        .iter()
        .filter(|p| p.kind == ColumnKind::Temporal && p.distinct > 0)
        .collect();
    let numeric: Vec<&ColumnProfile> = profiles
        .iter()
        .filter(|p| p.kind == ColumnKind::Numeric && p.distinct > 0)
        .collect();
    let categories: Vec<&ColumnProfile> = profiles
        .iter()
        .filter(|p| p.kind == ColumnKind::Category && (1..=MAX_CATEGORIES).contains(&p.distinct))
        .collect();
    let mut specs = Vec::new();
    if let Some(time) = temporal.first() {
        let measures: Vec<&ColumnProfile> = numeric.iter().take(3).copied().collect();
        if !measures.is_empty() {
            let category = categories
                .iter()
                .find(|c| c.distinct <= MAX_SERIES)
                .copied();
            specs.push(over_time(time, &measures, category));
        }
    }
    if let Some(category) = categories.first() {
        let series = categories.iter().skip(1).find(|c| c.distinct <= MAX_SERIES);
        match numeric.first() {
            Some(measure) => {
                let aggregate = if category.unique() && series.is_none() {
                    Aggregate::None
                } else {
                    Aggregate::Sum
                };
                specs.push(ChartSpec {
                    series: series.map(|s| s.name.clone()),
                    horizontal: true,
                    ..ChartSpec::new(
                        ChartType::Bar,
                        &category.name,
                        vec![Measure::new(&measure.name, aggregate)],
                    )
                });
                if category.distinct <= MAX_SLICES && measure.min.unwrap_or(-1.0) >= 0.0 {
                    specs.push(ChartSpec::new(
                        ChartType::Pie,
                        &category.name,
                        vec![Measure::new(&measure.name, aggregate)],
                    ));
                }
            }
            None => specs.push(ChartSpec {
                series: series.map(|s| s.name.clone()),
                horizontal: true,
                ..ChartSpec::new(
                    ChartType::Bar,
                    &category.name,
                    vec![Measure::new(&category.name, Aggregate::Count)],
                )
            }),
        }
    }
    if numeric.len() >= 2 {
        let series = categories.iter().find(|c| c.distinct <= MAX_SERIES);
        specs.push(ChartSpec {
            series: series.map(|s| s.name.clone()),
            ..ChartSpec::new(
                ChartType::Scatter,
                &numeric[0].name,
                vec![Measure::new(&numeric[1].name, Aggregate::None)],
            )
        });
    }
    specs
}

/// A line over `time`; dates that repeat, or too many of them, are grouped into buckets sized to the span so the line
/// keeps a few dozen to a few hundred points.
fn over_time(
    time: &ColumnProfile,
    measures: &[&ColumnProfile],
    category: Option<&ColumnProfile>,
) -> ChartSpec {
    let series = category.filter(|_| measures.len() == 1);
    let grouped = !time.unique() || series.is_some() || time.distinct >= MAX_LINE_POINTS;
    let aggregate = if grouped {
        Aggregate::Sum
    } else {
        Aggregate::None
    };
    ChartSpec {
        series: series.map(|s| s.name.clone()),
        bucket: grouped.then(|| bucket_for(time)),
        ..ChartSpec::new(
            ChartType::Line,
            &time.name,
            measures
                .iter()
                .map(|m| Measure::new(&m.name, aggregate))
                .collect(),
        )
    }
}

/// The bucket giving roughly 20–400 points over the column's span.
pub fn bucket_for(time: &ColumnProfile) -> TimeBucket {
    let span = time.max.unwrap_or(0.0) - time.min.unwrap_or(0.0);
    let days = span / DAY_MILLIS;
    if days <= 3.0 {
        TimeBucket::Hour
    } else if days <= 180.0 {
        TimeBucket::Day
    } else if days <= 730.0 {
        TimeBucket::Week
    } else if days <= 365.0 * 15.0 {
        TimeBucket::Month
    } else {
        TimeBucket::Year
    }
}

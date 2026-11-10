//! A spec applied to rows (`ChartData.kt`): parses values, groups by bucket and series, aggregates, orders and thins
//! out.

use chrono::{Datelike, NaiveDateTime, NaiveTime, Timelike};
use std::collections::{HashMap, HashSet};

use crate::profile::{ColumnKind, ColumnProfile, ColumnProfiler, values};
use crate::spec::{Aggregate, ChartSpec, ChartType, Measure};
use crate::suggest::{MAX_SERIES, MAX_SLICES};
use tg_sql::TimeBucket;

/// An x value: a number, epoch milliseconds (UTC wall-clock) for dates, or a category.
#[derive(Clone, Debug, PartialEq)]
pub enum ChartX {
    Number(f64),
    Category(String),
}

impl ChartX {
    fn number(&self) -> f64 {
        match self {
            Self::Number(n) => *n,
            Self::Category(_) => f64::NAN,
        }
    }

    /// Equality as Kotlin's boxed values have it: doubles by their bits.
    fn key(&self) -> XKey {
        match self {
            Self::Number(n) => XKey::Number(n.to_bits()),
            Self::Category(s) => XKey::Category(s.clone()),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
enum XKey {
    Number(u64),
    Category(String),
}

/// One drawn value. `group` is the series, or the measure when there are several and no series.
#[derive(Clone, Debug, PartialEq)]
pub struct ChartPoint {
    pub x: ChartX,
    pub group: String,
    pub y: f64,
}

/// A spec applied to rows: the points to draw, and what was left out on the way, so the chart can say so rather than
/// look complete.
#[derive(Clone, Debug, PartialEq)]
pub struct ChartFrame {
    pub spec: ChartSpec,
    pub x_kind: ColumnKind,
    pub points: Vec<ChartPoint>,
    /// Groups in legend order.
    pub groups: Vec<String>,
    /// Category x values in drawing order; empty for numbers and dates.
    pub categories: Vec<String>,
    /// Rows with no usable x or y value.
    pub skipped_rows: usize,
    /// Categories or series beyond the limits, folded away or merged into [`ChartData::OTHER`].
    pub omitted_categories: usize,
    pub merged_series: usize,
    /// Points dropped to keep drawing fast; the shape is kept (see [`ChartData::lttb`]).
    pub downsampled: bool,
}

impl ChartFrame {
    pub fn x_label(&self) -> String {
        match self.spec.bucket {
            Some(b) => format!("{} ({})", self.spec.x, b.label().to_lowercase()),
            None => self.spec.x.clone(),
        }
    }

    pub fn y_label(&self) -> String {
        if self.spec.y.len() == 1 {
            self.spec.y[0].label()
        } else {
            self.spec
                .y
                .iter()
                .map(Measure::label)
                .collect::<Vec<_>>()
                .join(", ")
        }
    }
}

pub struct ChartData;

fn distinct<T: PartialEq + Clone>(items: impl IntoIterator<Item = T>) -> Vec<T> {
    let mut out = Vec::new();
    for item in items {
        if !out.contains(&item) {
            out.push(item);
        }
    }
    out
}

#[derive(Default)]
struct Accumulator {
    count: usize,
    sum: f64,
    min: f64,
    max: f64,
    first: f64,
}

impl Accumulator {
    fn new() -> Self {
        Self {
            min: f64::INFINITY,
            max: f64::NEG_INFINITY,
            ..Self::default()
        }
    }

    fn add(&mut self, value: f64) {
        if self.count == 0 {
            self.first = value;
        }
        self.count += 1;
        self.sum += value;
        if value < self.min {
            self.min = value;
        }
        if value > self.max {
            self.max = value;
        }
    }

    fn result(&self, aggregate: Aggregate) -> f64 {
        match aggregate {
            // Several rows at one x without an aggregate: the first one's value, as a line can only have one.
            Aggregate::None => self.first,
            Aggregate::Sum | Aggregate::Count => self.sum,
            Aggregate::Avg => self.sum / self.count as f64,
            Aggregate::Min => self.min,
            Aggregate::Max => self.max,
        }
    }
}

impl ChartData {
    /// Name of the series all the smaller ones are merged into.
    pub const OTHER: &str = "Other";
    pub const NULL_LABEL: &str = "(null)";
    /// Categories on one axis before the smallest are left out.
    pub const MAX_CATEGORIES: usize = 50;
    /// Points per line before it is downsampled.
    pub const MAX_LINE_POINTS: usize = 2000;
    /// Points of a scatter plot before it is thinned out.
    pub const MAX_SCATTER_POINTS: usize = 20_000;

    pub fn build(
        rows: &[Vec<Option<String>>],
        profiles: &[ColumnProfile],
        spec: &ChartSpec,
    ) -> ChartFrame {
        Self::build_with(rows, profiles, spec, &mut || {})
    }

    /// `checkpoint` is called every [`ColumnProfiler::CHECKPOINT_ROWS`] rows, so a caller can note a request to stop.
    /// Panics when the spec names a column `profiles` doesn't have: validate it first.
    pub fn build_with(
        rows: &[Vec<Option<String>>],
        profiles: &[ColumnProfile],
        spec: &ChartSpec,
        checkpoint: &mut dyn FnMut(),
    ) -> ChartFrame {
        let by_name = |name: &str| {
            profiles
                .iter()
                .rev()
                .find(|p| p.name == name)
                .unwrap_or_else(|| panic!("no column {name}"))
        };
        let x = by_name(&spec.x);
        let series = spec.series.as_deref().map(by_name);
        let measures: Vec<(&Measure, &ColumnProfile)> =
            spec.y.iter().map(|m| (m, by_name(&m.column))).collect();

        // Series past the largest few are merged, by the first measure's total.
        let kept_series = series.and_then(|s| Self::kept_series(rows, s, measures[0].1));

        let mut skipped = 0;
        let mut raw: Vec<ChartPoint> = Vec::with_capacity(rows.len() * measures.len());
        for (i, row) in rows.iter().enumerate() {
            if i % ColumnProfiler::CHECKPOINT_ROWS == 0 {
                checkpoint();
            }
            let Some(x_value) = Self::x_value(row[x.index].as_deref(), x.kind, spec.bucket) else {
                skipped += 1;
                continue;
            };
            let series_name = series.map(|s| {
                let name = row[s.index].as_deref().unwrap_or(Self::NULL_LABEL);
                match &kept_series {
                    Some(kept) if !kept.contains(name) => Self::OTHER.to_owned(),
                    _ => name.to_owned(),
                }
            });
            let mut usable = false;
            for (measure, column) in &measures {
                let value = row[column.index].as_deref();
                let y = if measure.aggregate == Aggregate::Count {
                    if value.is_none() { 0.0 } else { 1.0 }
                } else {
                    match values::number(value) {
                        Some(y) => y,
                        None => continue,
                    }
                };
                usable = true;
                raw.push(ChartPoint {
                    x: x_value.clone(),
                    group: series_name.clone().unwrap_or_else(|| measure.label()),
                    y,
                });
            }
            if !usable {
                skipped += 1;
            }
        }
        let merged_series = match (&kept_series, series) {
            (Some(kept), Some(s)) => {
                let all: HashSet<&str> = rows
                    .iter()
                    .map(|r| r[s.index].as_deref().unwrap_or(Self::NULL_LABEL))
                    .collect();
                all.len() - kept.len()
            }
            _ => 0,
        };

        let aggregated = if spec.aggregates() {
            let measure_list: Vec<&Measure> = measures.iter().map(|(m, _)| *m).collect();
            Self::aggregate(raw, &measure_list, series.is_some())
        } else {
            raw
        };
        let ordered = Self::order(aggregated, x.kind, spec);
        let (kept, omitted) = Self::limit_categories(ordered, x.kind, spec);
        let mut groups = distinct(kept.iter().map(|p| p.group.clone()));
        if let Some(i) = groups.iter().position(|g| g == Self::OTHER) {
            let other = groups.remove(i);
            groups.push(other);
        }
        let (thinned, downsampled) = Self::thin(kept, x.kind, spec.chart_type);
        let categories = if x.kind == ColumnKind::Category {
            distinct(thinned.iter().filter_map(|p| match &p.x {
                ChartX::Category(c) => Some(c.clone()),
                ChartX::Number(_) => None,
            }))
        } else {
            vec![]
        };
        ChartFrame {
            spec: spec.clone(),
            x_kind: x.kind,
            points: thinned,
            groups,
            categories,
            skipped_rows: skipped,
            omitted_categories: omitted,
            merged_series,
            downsampled,
        }
    }

    fn x_value(text: Option<&str>, kind: ColumnKind, bucket: Option<TimeBucket>) -> Option<ChartX> {
        match kind {
            ColumnKind::Numeric => values::number(text).map(ChartX::Number),
            ColumnKind::Temporal => values::date_time(text).map(|t| {
                let t = bucket.map_or(t, |b| Self::truncate(t, b));
                ChartX::Number(values::epoch_millis_of(t))
            }),
            ColumnKind::Category => Some(ChartX::Category(
                text.unwrap_or(Self::NULL_LABEL).to_owned(),
            )),
            ColumnKind::Other => None,
        }
    }

    /// `time` rounded down to the start of its `bucket`; weeks start on Monday.
    pub fn truncate(time: NaiveDateTime, bucket: TimeBucket) -> NaiveDateTime {
        let day = time.date();
        match bucket {
            TimeBucket::Hour => day.and_time(NaiveTime::from_hms_opt(time.hour(), 0, 0).unwrap()),
            TimeBucket::Day => day.and_time(NaiveTime::MIN),
            TimeBucket::Week => {
                let back = u64::from(day.weekday().num_days_from_monday());
                (day - chrono::Days::new(back)).and_time(NaiveTime::MIN)
            }
            TimeBucket::Month => day.with_day(1).unwrap().and_time(NaiveTime::MIN),
            TimeBucket::Quarter => {
                let month = (time.month() - 1) / 3 * 3 + 1;
                day.with_day(1)
                    .unwrap()
                    .with_month(month)
                    .unwrap()
                    .and_time(NaiveTime::MIN)
            }
            TimeBucket::Year => day.with_ordinal(1).unwrap().and_time(NaiveTime::MIN),
        }
    }

    /// The [`MAX_SERIES`] series with the largest absolute totals, or None when there are no more than that. Ties are
    /// broken by first appearance (Kotlin breaks them by hash order).
    fn kept_series(
        rows: &[Vec<Option<String>>],
        series: &ColumnProfile,
        measure: &ColumnProfile,
    ) -> Option<HashSet<String>> {
        let mut totals: Vec<(String, f64)> = Vec::new();
        let mut index: HashMap<String, usize> = HashMap::new();
        for row in rows {
            let name = row[series.index].as_deref().unwrap_or(Self::NULL_LABEL);
            let value = values::number(row[measure.index].as_deref())
                .unwrap_or(1.0)
                .abs();
            match index.get(name) {
                Some(&i) => totals[i].1 += value,
                None => {
                    index.insert(name.to_owned(), totals.len());
                    totals.push((name.to_owned(), value));
                }
            }
        }
        if totals.len() <= MAX_SERIES {
            return None;
        }
        totals.sort_by(|a, b| b.1.total_cmp(&a.1));
        Some(
            totals
                .into_iter()
                .take(MAX_SERIES - 1)
                .map(|(n, _)| n)
                .collect(),
        )
    }

    /// One point per x and group. Each group belongs to one measure, so the measure's aggregate applies to it.
    fn aggregate(
        points: Vec<ChartPoint>,
        measures: &[&Measure],
        by_series: bool,
    ) -> Vec<ChartPoint> {
        let aggregate_of = |group: &str| {
            if by_series {
                measures[0].aggregate
            } else {
                measures
                    .iter()
                    .find(|m| m.label() == group)
                    .unwrap()
                    .aggregate
            }
        };
        let mut order: Vec<(ChartX, String)> = Vec::new();
        let mut cells: HashMap<(XKey, String), Accumulator> = HashMap::new();
        for point in points {
            let key = (point.x.key(), point.group.clone());
            let cell = cells.entry(key).or_insert_with(|| {
                order.push((point.x.clone(), point.group.clone()));
                Accumulator::new()
            });
            cell.add(point.y);
        }
        order
            .into_iter()
            .map(|(x, group)| {
                let y = cells[&(x.key(), group.clone())].result(aggregate_of(&group));
                ChartPoint { x, group, y }
            })
            .collect()
    }

    /// Numbers and dates ascending; bar and pie categories largest first, other categories as they came.
    fn order(mut points: Vec<ChartPoint>, kind: ColumnKind, spec: &ChartSpec) -> Vec<ChartPoint> {
        match kind {
            ColumnKind::Numeric | ColumnKind::Temporal => {
                points.sort_by(|a, b| a.x.number().total_cmp(&b.x.number()));
                points
            }
            _ if matches!(spec.chart_type, ChartType::Bar | ChartType::Pie) => {
                let mut totals: HashMap<XKey, f64> = HashMap::new();
                for p in &points {
                    *totals.entry(p.x.key()).or_insert(0.0) += p.y;
                }
                points.sort_by(|a, b| totals[&b.x.key()].total_cmp(&totals[&a.x.key()]));
                points
            }
            _ => points,
        }
    }

    /// Keeps the first categories, which `order` put largest first for bars and pies.
    fn limit_categories(
        points: Vec<ChartPoint>,
        kind: ColumnKind,
        spec: &ChartSpec,
    ) -> (Vec<ChartPoint>, usize) {
        if kind != ColumnKind::Category {
            return (points, 0);
        }
        let categories = distinct(points.iter().map(|p| p.x.key()));
        let limit = if spec.chart_type == ChartType::Pie {
            MAX_SLICES
        } else {
            Self::MAX_CATEGORIES
        };
        if categories.len() <= limit {
            return (points, 0);
        }
        let kept: HashSet<&XKey> = categories.iter().take(limit).collect();
        let omitted = categories.len() - limit;
        (
            points
                .into_iter()
                .filter(|p| kept.contains(&p.x.key()))
                .collect(),
            omitted,
        )
    }

    fn thin(
        points: Vec<ChartPoint>,
        kind: ColumnKind,
        chart_type: ChartType,
    ) -> (Vec<ChartPoint>, bool) {
        if kind == ColumnKind::Category {
            return (points, false);
        }
        match chart_type {
            ChartType::Line | ChartType::Area => {
                let mut names: Vec<String> = Vec::new();
                let mut groups: HashMap<String, Vec<ChartPoint>> = HashMap::new();
                for p in &points {
                    if !groups.contains_key(&p.group) {
                        names.push(p.group.clone());
                    }
                    groups.entry(p.group.clone()).or_default().push(p.clone());
                }
                if groups.values().all(|g| g.len() <= Self::MAX_LINE_POINTS) {
                    return (points, false);
                }
                let thinned = names
                    .iter()
                    .flat_map(|n| Self::lttb(&groups[n], Self::MAX_LINE_POINTS))
                    .collect();
                (thinned, true)
            }
            ChartType::Scatter if points.len() > Self::MAX_SCATTER_POINTS => {
                let step = points.len() as f64 / Self::MAX_SCATTER_POINTS as f64;
                let thinned = (0..Self::MAX_SCATTER_POINTS)
                    .map(|i| points[(i as f64 * step) as usize].clone())
                    .collect();
                (thinned, true)
            }
            _ => (points, false),
        }
    }

    /// Largest-Triangle-Three-Buckets: keeps `threshold` of `points` (sorted by numeric x) that best preserve the
    /// line's shape, always including the first and last.
    pub fn lttb(points: &[ChartPoint], threshold: usize) -> Vec<ChartPoint> {
        if threshold >= points.len() || threshold < 3 {
            return points.to_vec();
        }
        let mut sampled = Vec::with_capacity(threshold);
        let every = (points.len() - 2) as f64 / (threshold - 2) as f64;
        let mut a = 0;
        sampled.push(points[0].clone());
        for i in 0..threshold - 2 {
            let next_start = ((i + 1) as f64 * every) as usize + 1;
            let next_end = ((((i + 2) as f64) * every) as usize + 1).min(points.len());
            let mut avg_x = 0.0;
            let mut avg_y = 0.0;
            for p in &points[next_start..next_end.max(next_start)] {
                avg_x += p.x.number();
                avg_y += p.y;
            }
            let count = next_end.saturating_sub(next_start).max(1) as f64;
            avg_x /= count;
            avg_y /= count;

            let start = (i as f64 * every) as usize + 1;
            let end = ((i + 1) as f64 * every) as usize + 1;
            let ax = points[a].x.number();
            let ay = points[a].y;
            let mut max_area = -1.0;
            let mut chosen = start;
            for (j, p) in points.iter().enumerate().take(end).skip(start) {
                let area = ((ax - avg_x) * (p.y - ay) - (ax - p.x.number()) * (avg_y - ay)).abs();
                if area > max_area {
                    max_area = area;
                    chosen = j;
                }
            }
            sampled.push(points[chosen].clone());
            a = chosen;
        }
        sampled.push(points[points.len() - 1].clone());
        sampled
    }
}

//! What to draw from a result (`ChartSpec.kt`).

use crate::profile::{ColumnKind, ColumnProfile};
use tg_sql::TimeBucket;
use tg_sql::kt::eq_ignore_case;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ChartType {
    Line,
    Area,
    Bar,
    Scatter,
    Pie,
}

impl ChartType {
    pub const ALL: &[ChartType] = &[Self::Line, Self::Area, Self::Bar, Self::Scatter, Self::Pie];

    /// The Kotlin enum name, which is also its label.
    pub fn name(self) -> &'static str {
        match self {
            Self::Line => "Line",
            Self::Area => "Area",
            Self::Bar => "Bar",
            Self::Scatter => "Scatter",
            Self::Pie => "Pie",
        }
    }

    pub fn label(self) -> &'static str {
        self.name()
    }

    /// By name or label, ignoring case; None for anything else.
    pub fn of(text: &str) -> Option<Self> {
        Self::ALL
            .iter()
            .copied()
            .find(|t| eq_ignore_case(t.name(), text))
    }
}

/// How a measure's values are combined per x (and series). `None` plots each row's value as it is.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Aggregate {
    #[default]
    None,
    Sum,
    Avg,
    Min,
    Max,
    Count,
}

impl Aggregate {
    pub const ALL: &[Aggregate] = &[
        Self::None,
        Self::Sum,
        Self::Avg,
        Self::Min,
        Self::Max,
        Self::Count,
    ];

    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::None => "None",
            Self::Sum => "Sum",
            Self::Avg => "Avg",
            Self::Min => "Min",
            Self::Max => "Max",
            Self::Count => "Count",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::None => "Value",
            Self::Avg => "Average",
            other => other.name(),
        }
    }

    pub fn sql(self) -> Option<&'static str> {
        match self {
            Self::None => None,
            Self::Sum => Some("SUM"),
            Self::Avg => Some("AVG"),
            Self::Min => Some("MIN"),
            Self::Max => Some("MAX"),
            Self::Count => Some("COUNT"),
        }
    }

    /// By name or label, ignoring case, or `mean` for the average.
    pub fn of(text: &str) -> Option<Self> {
        Self::ALL
            .iter()
            .copied()
            .find(|a| eq_ignore_case(a.name(), text) || eq_ignore_case(a.label(), text))
            .or_else(|| eq_ignore_case(text, "mean").then_some(Self::Avg))
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Measure {
    pub column: String,
    pub aggregate: Aggregate,
}

impl Measure {
    pub fn new(column: &str, aggregate: Aggregate) -> Self {
        Self {
            column: column.to_owned(),
            aggregate,
        }
    }

    /// How the measure is named in legends and in server-side aggregates: `total`, `sum(total)`.
    pub fn label(&self) -> String {
        if self.aggregate == Aggregate::None {
            self.column.clone()
        } else {
            format!("{}({})", self.aggregate.name().to_lowercase(), self.column)
        }
    }
}

/// What to draw from a result: columns by name, so a spec outlives the result it was made for and applies again when
/// the statement is re-run. Made by the suggester, edited by the user, or proposed by an agent; always validated first.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ChartSpec {
    pub chart_type: ChartType,
    pub x: String,
    pub y: Vec<Measure>,
    pub series: Option<String>,
    pub bucket: Option<TimeBucket>,
    /// Bars along the y axis, which keeps long category names readable.
    pub horizontal: bool,
}

/// At most this many measures in one chart.
pub const MAX_MEASURES: usize = 5;

fn kind_name(kind: ColumnKind) -> String {
    kind.name().to_lowercase()
}

impl ChartSpec {
    pub fn new(chart_type: ChartType, x: &str, y: Vec<Measure>) -> Self {
        Self {
            chart_type,
            x: x.to_owned(),
            y,
            series: None,
            bucket: None,
            horizontal: false,
        }
    }

    /// Whether rows are grouped before drawing, rather than drawn one point per row.
    pub fn aggregates(&self) -> bool {
        self.bucket.is_some() || self.y.iter().any(|m| m.aggregate != Aggregate::None)
    }

    /// Every column the spec reads, in the order first read.
    pub fn columns(&self) -> Vec<String> {
        let mut columns = vec![self.x.clone()];
        for name in self.y.iter().map(|m| &m.column).chain(self.series.as_ref()) {
            if !columns.contains(name) {
                columns.push(name.clone());
            }
        }
        columns
    }

    /// What makes this spec unusable with `profiles`, each specific enough to correct (they are also sent back to an
    /// agent that proposed the spec); empty when it can be drawn.
    pub fn validate(&self, profiles: &[ColumnProfile]) -> Vec<String> {
        let mut problems = Vec::new();
        let names = profiles
            .iter()
            .map(|p| p.name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        // Later profiles win, as with Kotlin's associateBy.
        let find = |name: &str| profiles.iter().rev().find(|p| p.name == name);
        let known = |problems: &mut Vec<String>, name: &str, role: &str| {
            let found = find(name);
            if found.is_none() {
                problems.push(format!(
                    "There is no column \"{name}\" for {role}; the columns are: {names}."
                ));
            }
            found
        };

        let x_column = known(&mut problems, &self.x, "x");
        if self.y.is_empty() {
            problems.push("Choose at least one measure for y.".to_owned());
        }
        if self.y.len() > MAX_MEASURES {
            problems.push(format!(
                "At most {MAX_MEASURES} measures; there are {}.",
                self.y.len()
            ));
        }
        for measure in &self.y {
            let Some(column) = known(&mut problems, &measure.column, "y") else {
                continue;
            };
            if measure.aggregate != Aggregate::Count && column.kind != ColumnKind::Numeric {
                problems.push(format!(
                    "\"{}\" isn't numeric ({}); count it or pick a numeric column.",
                    measure.column,
                    kind_name(column.kind)
                ));
            }
        }
        if let Some(name) = &self.series
            && let Some(column) = known(&mut problems, name, "series")
        {
            if *name == self.x {
                problems.push("The series column can't also be x.".to_owned());
            }
            if column.kind == ColumnKind::Other {
                problems.push(format!(
                    "\"{name}\" can't split series: its values can't be compared."
                ));
            }
            if self.y.len() > 1 {
                problems.push("Split by series with one measure only.".to_owned());
            }
        }
        if let Some(x_column) = x_column {
            let x = &self.x;
            if x_column.kind == ColumnKind::Other {
                problems.push(format!(
                    "\"{x}\" can't be plotted on x: its values are neither numbers, dates nor categories."
                ));
            }
            if let Some(bucket) = self.bucket {
                let unit = bucket.label().to_lowercase();
                if x_column.kind != ColumnKind::Temporal {
                    problems.push(format!(
                        "Only dates can be grouped by {unit}; \"{x}\" is {}.",
                        kind_name(x_column.kind)
                    ));
                }
                if self.y.iter().any(|m| m.aggregate == Aggregate::None) {
                    problems.push(format!(
                        "Grouping by {unit} needs an aggregate (sum, average, count…) for every measure."
                    ));
                }
            }
            match self.chart_type {
                ChartType::Scatter => {
                    if !matches!(x_column.kind, ColumnKind::Numeric | ColumnKind::Temporal) {
                        problems.push("A scatter plot needs numbers or dates on x.".to_owned());
                    }
                }
                ChartType::Pie => {
                    if x_column.kind != ColumnKind::Category {
                        problems.push(format!(
                            "A pie needs categories for its slices; \"{x}\" is {}.",
                            kind_name(x_column.kind)
                        ));
                    }
                    if self.y.len() > 1 || self.series.is_some() {
                        problems.push("A pie shows one measure, without series.".to_owned());
                    }
                }
                ChartType::Line | ChartType::Area | ChartType::Bar => {}
            }
        }
        problems
    }

    /// This spec after one field was edited, with the others brought in line where there is one obvious way: a bucket
    /// only for dates and only with aggregates, a pie with one measure and no series. What can't be fixed that way is
    /// left for [`Self::validate`] to report.
    pub fn adjusted(&self, profiles: &[ColumnProfile]) -> ChartSpec {
        let x_kind = profiles.iter().find(|p| p.name == self.x).map(|p| p.kind);
        let bucket = self.bucket.filter(|_| {
            x_kind == Some(ColumnKind::Temporal) && self.chart_type != ChartType::Scatter
        });
        let mut y: Vec<Measure> = if bucket.is_some() {
            self.y
                .iter()
                .map(|m| {
                    if m.aggregate == Aggregate::None {
                        Measure::new(&m.column, Aggregate::Sum)
                    } else {
                        m.clone()
                    }
                })
                .collect()
        } else {
            self.y.clone()
        };
        let mut series = self.series.clone().filter(|s| *s != self.x);
        if self.chart_type == ChartType::Pie {
            y.truncate(1);
            series = None;
        } else if series.is_some() {
            y.truncate(1);
        }
        ChartSpec {
            y,
            series,
            bucket,
            ..self.clone()
        }
    }
}

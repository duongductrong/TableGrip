//! Select lists for the table viewer (`Projection.kt`).

use regex::Regex;
use std::collections::{BTreeMap, HashMap};
use std::sync::LazyLock;

use super::values::{self, FilterResult, FilterValueError};
use super::{FilterColumn, ValueKind};
use crate::dialect::{SqlDialect, TimeBucket};
use crate::kt;

/// What a [`SelectFunction`] needs besides its column. The label reads before the field: "decimals 2".
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum FunctionArgument {
    Digits,
    Range,
    Fallback,
    Bucket,
}

impl FunctionArgument {
    pub fn label(self) -> &'static str {
        match self {
            Self::Digits => "decimals",
            Self::Range => "from, length",
            Self::Fallback => "if NULL",
            Self::Bucket => "to",
        }
    }

    pub fn placeholder(self) -> &'static str {
        match self {
            Self::Digits => "0",
            Self::Range => "1, 10",
            Self::Fallback => "value",
            Self::Bucket => "day",
        }
    }
}

/// A function applied to a column in the select list; its label is what the picker shows.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum SelectFunction {
    #[default]
    None,
    Upper,
    Lower,
    Trim,
    Length,
    Substring,
    Round,
    Abs,
    Floor,
    Ceil,
    Truncate,
    Coalesce,
    AsText,
}

impl SelectFunction {
    pub const ALL: &[SelectFunction] = &[
        Self::None,
        Self::Upper,
        Self::Lower,
        Self::Trim,
        Self::Length,
        Self::Substring,
        Self::Round,
        Self::Abs,
        Self::Floor,
        Self::Ceil,
        Self::Truncate,
        Self::Coalesce,
        Self::AsText,
    ];

    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::None => "None",
            Self::Upper => "Upper",
            Self::Lower => "Lower",
            Self::Trim => "Trim",
            Self::Length => "Length",
            Self::Substring => "Substring",
            Self::Round => "Round",
            Self::Abs => "Abs",
            Self::Floor => "Floor",
            Self::Ceil => "Ceil",
            Self::Truncate => "Truncate",
            Self::Coalesce => "Coalesce",
            Self::AsText => "AsText",
        }
    }

    pub fn by_name(name: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|f| f.name() == name)
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::None => "value",
            Self::Upper => "UPPER",
            Self::Lower => "LOWER",
            Self::Trim => "TRIM",
            Self::Length => "LENGTH",
            Self::Substring => "SUBSTRING",
            Self::Round => "ROUND",
            Self::Abs => "ABS",
            Self::Floor => "FLOOR",
            Self::Ceil => "CEIL",
            Self::Truncate => "truncate",
            Self::Coalesce => "if NULL…",
            Self::AsText => "as text",
        }
    }

    pub fn argument(self) -> Option<FunctionArgument> {
        match self {
            Self::Substring => Some(FunctionArgument::Range),
            Self::Round => Some(FunctionArgument::Digits),
            Self::Truncate => Some(FunctionArgument::Bucket),
            Self::Coalesce => Some(FunctionArgument::Fallback),
            _ => None,
        }
    }

    /// A short name for the column the result shows under, e.g. `upper(name)`.
    pub fn output_name(self, column: &str) -> String {
        if self == Self::None {
            column.to_owned()
        } else {
            format!("{}({column})", self.name().to_lowercase())
        }
    }
}

/// A compiled select list: `columns` go between `SELECT [DISTINCT]` and `FROM`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SqlProjection {
    pub columns: String,
    pub distinct: bool,
}

impl SqlProjection {
    pub fn new(columns: &str, distinct: bool) -> Self {
        Self {
            columns: columns.to_owned(),
            distinct,
        }
    }

    pub fn clause(&self) -> String {
        if self.distinct {
            format!("DISTINCT {}", self.columns)
        } else {
            self.columns.clone()
        }
    }
}

impl SelectFunction {
    /// Functions that apply to a `kind` column on `dialect`, in sections the UI separates; `None` first.
    pub fn for_kind(kind: ValueKind, dialect: SqlDialect) -> Vec<Vec<SelectFunction>> {
        use SelectFunction as F;
        let own = match kind {
            ValueKind::Text => vec![F::Upper, F::Lower, F::Trim, F::Length, F::Substring],
            ValueKind::Number => vec![F::Round, F::Abs, F::Floor, F::Ceil],
            ValueKind::Date | ValueKind::DateTime
                if dialect.time_bucket("x", TimeBucket::Day).is_some() =>
            {
                vec![F::Truncate]
            }
            _ => vec![],
        };
        let shared = match kind {
            ValueKind::Text => vec![F::Coalesce],
            ValueKind::Boolean => vec![F::AsText],
            ValueKind::Binary => vec![],
            _ => vec![F::Coalesce, F::AsText],
        };
        [vec![F::None], own, shared]
            .into_iter()
            .filter(|s| !s.is_empty())
            .collect()
    }
}

/// A table column, as is or through `function` with its `argument`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SelectField {
    pub id: i64,
    pub column: String,
    pub function: SelectFunction,
    pub argument: String,
    /// The name the result shows under; blank for the default.
    pub alias: String,
    pub enabled: bool,
}

impl SelectField {
    pub fn new(id: i64, column: &str) -> Self {
        Self {
            id,
            column: column.to_owned(),
            function: SelectFunction::None,
            argument: String::new(),
            alias: String::new(),
            enabled: true,
        }
    }
}

/// A hand-written SQL expression, used as is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SelectExpression {
    pub id: i64,
    pub sql: String,
    pub alias: String,
    pub enabled: bool,
}

/// One entry of a select list. Disabled items are kept, so a column can be hidden and shown again.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SelectItem {
    Field(SelectField),
    Expression(SelectExpression),
}

impl SelectItem {
    pub fn id(&self) -> i64 {
        match self {
            Self::Field(f) => f.id,
            Self::Expression(e) => e.id,
        }
    }

    pub fn alias(&self) -> &str {
        match self {
            Self::Field(f) => &f.alias,
            Self::Expression(e) => &e.alias,
        }
    }

    pub fn enabled(&self) -> bool {
        match self {
            Self::Field(f) => f.enabled,
            Self::Expression(e) => e.enabled,
        }
    }
}

/// What the table viewer selects: the enabled items in order, or every column when there are none.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Projection {
    pub items: Vec<SelectItem>,
    pub distinct: bool,
}

/// The outcome of compiling a [`Projection`]: the select list (None for `*`), per item id what's wrong (id 0 for the
/// list as a whole), and whether the rows still map one to one onto table rows with their columns' own names, so they
/// can be edited.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompiledProjection {
    pub projection: Option<SqlProjection>,
    pub issues: BTreeMap<i64, String>,
    pub editable: bool,
}

impl CompiledProjection {
    pub fn is_valid(&self) -> bool {
        self.issues.is_empty()
    }
}

/// Turns a [`Projection`] into a select list for a dialect. Arguments are validated and inlined as literals, never as
/// `?`.
pub struct ProjectionCompiler {
    dialect: SqlDialect,
    columns: Vec<FilterColumn>,
}

fn invalid<T>(message: impl Into<String>) -> FilterResult<T> {
    Err(FilterValueError::new(message))
}

static WHITESPACE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[ \t\n\x0B\x0C\r]+").unwrap());

impl ProjectionCompiler {
    pub fn new(dialect: SqlDialect, columns: &[FilterColumn]) -> Self {
        Self {
            dialect,
            columns: columns.to_vec(),
        }
    }

    pub fn compile(&self, projection: &Projection) -> CompiledProjection {
        let mut issues = BTreeMap::new();
        let enabled: Vec<&SelectItem> = projection.items.iter().filter(|i| i.enabled()).collect();
        if enabled.is_empty() {
            if !projection.items.is_empty() {
                issues.insert(0, "Choose at least one column to show".to_owned());
            }
            let editable = !projection.distinct && issues.is_empty();
            return CompiledProjection {
                projection: None,
                issues,
                editable,
            };
        }
        let mut parts = Vec::new();
        let mut names: HashMap<String, i64> = HashMap::new();
        for item in &enabled {
            let part = match self.item(item) {
                Ok(part) => part,
                Err(e) => {
                    issues.insert(item.id(), e.0);
                    continue;
                }
            };
            let name = self.output_name(item);
            if names.insert(name.to_lowercase(), item.id()).is_some() {
                issues.insert(
                    item.id(),
                    format!(
                        "Another column already shows as “{name}”; give this one a different name"
                    ),
                );
            }
            parts.push(part);
        }
        let plain = enabled.iter().all(|i| {
            matches!(i, SelectItem::Field(f) if f.function == SelectFunction::None && kt::is_blank(&f.alias))
        });
        // Every column, in table order and unchanged, is just `*`.
        let all = plain
            && !projection.distinct
            && enabled
                .iter()
                .map(|i| match i {
                    SelectItem::Field(f) => f.column.as_str(),
                    SelectItem::Expression(_) => "",
                })
                .eq(self.columns.iter().map(|c| c.name.as_str()));
        let sql = (!all).then(|| SqlProjection::new(&parts.join(", "), projection.distinct));
        let editable = plain && !projection.distinct && issues.is_empty();
        CompiledProjection {
            projection: sql.filter(|_| issues.is_empty()),
            issues,
            editable,
        }
    }

    /// The column name the result has for `item`.
    pub fn output_name(&self, item: &SelectItem) -> String {
        let alias = kt::trim(item.alias());
        if !alias.is_empty() {
            return alias.to_owned();
        }
        match item {
            SelectItem::Field(f) => f.function.output_name(&f.column),
            SelectItem::Expression(e) => Self::default_alias(&e.sql),
        }
    }

    /// An expression shows under its own text, cut short to fit every engine's identifier limit.
    pub fn default_alias(sql: &str) -> String {
        let trimmed = kt::trim(sql);
        let trimmed = kt::trim(trimmed.strip_suffix(';').unwrap_or(trimmed));
        WHITESPACE
            .replace_all(trimmed, " ")
            .chars()
            .take(60)
            .collect()
    }

    fn item(&self, item: &SelectItem) -> FilterResult<String> {
        match item {
            SelectItem::Expression(e) => {
                let sql = kt::trim(&e.sql);
                let sql = kt::trim(sql.strip_suffix(';').unwrap_or(sql));
                if sql.is_empty() {
                    return invalid("Enter a SQL expression");
                }
                Ok(format!(
                    "{sql} AS {}",
                    self.dialect.quote_identifier(&self.output_name(item))
                ))
            }
            SelectItem::Field(f) => self.field(f),
        }
    }

    fn field(&self, field: &SelectField) -> FilterResult<String> {
        let Some(column) = self.columns.iter().find(|c| c.name == field.column) else {
            return invalid(if field.column.is_empty() {
                "Choose a column".to_owned()
            } else {
                format!("No column “{}” in this table", field.column)
            });
        };
        let function = field.function;
        if !SelectFunction::for_kind(column.kind, self.dialect)
            .iter()
            .any(|s| s.contains(&function))
        {
            return invalid(format!(
                "“{}” doesn't apply to {} columns",
                function.label(),
                column.kind.label()
            ));
        }
        let c = self.dialect.quote_identifier(&column.name);
        let arguments = self.arguments(function, column, &field.argument)?;
        let Some(sql) = self.dialect.select_function(function, &c, &arguments) else {
            return invalid(format!("This database has no {}", function.label()));
        };
        let alias = kt::trim(&field.alias);
        if function == SelectFunction::None && alias.is_empty() {
            return Ok(c);
        }
        let name = if alias.is_empty() {
            function.output_name(&column.name)
        } else {
            alias.to_owned()
        };
        Ok(format!("{sql} AS {}", self.dialect.quote_identifier(&name)))
    }

    /// `input` as SQL arguments for `function`.
    fn arguments(
        &self,
        function: SelectFunction,
        column: &FilterColumn,
        input: &str,
    ) -> FilterResult<Vec<String>> {
        let text = kt::trim(input);
        Ok(match function.argument() {
            None => vec![],
            Some(FunctionArgument::Digits) => vec![integer(
                if text.is_empty() { "0" } else { text },
                0,
                10,
                "Enter how many decimals to keep, 0 to 10",
            )?],
            Some(FunctionArgument::Range) => {
                let parts: Vec<&str> = text.split(',').map(kt::trim).collect();
                if parts.len() != 2 || parts.iter().any(|p| p.is_empty()) {
                    return invalid("Enter where to start and how many characters, e.g. 1, 10");
                }
                vec![
                    integer(parts[0], 1, i32::MAX, "Start at character 1 or later")?,
                    integer(parts[1], 0, i32::MAX, "Enter a length of 0 or more")?,
                ]
            }
            Some(FunctionArgument::Bucket) => {
                let bucket = if text.is_empty() {
                    TimeBucket::Day
                } else {
                    TimeBucket::of(text).ok_or_else(|| {
                        FilterValueError::new("Truncate to hour, day, week, month, quarter or year")
                    })?
                };
                vec![bucket.name().to_owned()]
            }
            Some(FunctionArgument::Fallback) => vec![match column.kind {
                ValueKind::Number => {
                    if text.is_empty() {
                        return invalid("Enter the number to show instead of NULL");
                    }
                    values::number(text)?.to_plain_string()
                }
                ValueKind::Text => self.dialect.literal(Some(input), false),
                _ => {
                    if text.is_empty() {
                        return invalid("Enter the value to show instead of NULL");
                    }
                    self.dialect.literal(Some(text), false)
                }
            }],
        })
    }
}

fn integer(text: &str, min: i32, max: i32, message: &str) -> FilterResult<String> {
    match text.parse::<i32>() {
        Ok(v) if (min..=max).contains(&v) => Ok(v.to_string()),
        _ => invalid(message),
    }
}

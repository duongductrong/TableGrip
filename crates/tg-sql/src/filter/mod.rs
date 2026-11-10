//! Table viewer filters and select lists: the model (`Filter.kt`), value parsing (`FilterValues.kt`), and their
//! compilers to SQL (`FilterCompiler.kt`, `Projection.kt`).

pub mod compiler;
pub mod projection;
pub mod values;

pub use compiler::{CompiledFilter, FilterCompiler, SqlFilter};
pub use projection::{
    CompiledProjection, FunctionArgument, Projection, ProjectionCompiler, SelectExpression,
    SelectField, SelectFunction, SelectItem, SqlProjection,
};

pub use values::{FilterResult, FilterValueError, ParamValue, SqlParam, TemporalValue};

use crate::kt;
use crate::model::jdbc_types as types;

/// How a column's values are compared, parsed and bound: decides which operators and value editors a filter offers.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ValueKind {
    Text,
    Number,
    Boolean,
    Date,
    Time,
    DateTime,
    Uuid,
    Json,
    Binary,
    /// Enums, network addresses, geometry, arrays…: compared as the server parses their text.
    Other,
}

const BOOLEAN_NAMES: &[&str] = &["bool", "boolean"];
const NUMBER_NAMES: &[&str] = &[
    "int",
    "integer",
    "int2",
    "int4",
    "int8",
    "smallint",
    "bigint",
    "tinyint",
    "mediumint",
    "numeric",
    "decimal",
    "dec",
    "real",
    "number",
    "year",
    "smallserial",
    "bigserial",
];
const BINARY_NAMES: &[&str] = &["bytea", "binary", "varbinary", "bit varying", "varbit"];
const TEXT_NAMES: &[&str] = &["text", "citext", "name", "string", "set"];

impl ValueKind {
    pub const ALL: &[ValueKind] = &[
        Self::Text,
        Self::Number,
        Self::Boolean,
        Self::Date,
        Self::Time,
        Self::DateTime,
        Self::Uuid,
        Self::Json,
        Self::Binary,
        Self::Other,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Number => "number",
            Self::Boolean => "boolean",
            Self::Date => "date",
            Self::Time => "time",
            Self::DateTime => "date-time",
            Self::Uuid => "uuid",
            Self::Json => "json",
            Self::Binary => "binary",
            Self::Other => "other",
        }
    }

    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Text => "Text",
            Self::Number => "Number",
            Self::Boolean => "Boolean",
            Self::Date => "Date",
            Self::Time => "Time",
            Self::DateTime => "DateTime",
            Self::Uuid => "Uuid",
            Self::Json => "Json",
            Self::Binary => "Binary",
            Self::Other => "Other",
        }
    }

    pub fn by_name(name: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|k| k.name() == name)
    }

    pub fn is_temporal(self) -> bool {
        matches!(self, Self::Date | Self::Time | Self::DateTime)
    }

    /// Classifies a column by its database type name, falling back to the JDBC type for names it doesn't know.
    pub fn of(type_name: &str, jdbc_type: i32, size: Option<i32>) -> Self {
        let lower = type_name.to_lowercase();
        let name = kt::trim(lower.split('(').next().unwrap_or(""));
        let name = name.strip_suffix(" unsigned").unwrap_or(name);
        let name = name.strip_suffix(" zerofill").unwrap_or(name);
        let name = kt::trim(name);
        if name.starts_with('_') || name.ends_with("[]") {
            return Self::Other;
        }
        if jdbc_type == types::BOOLEAN || jdbc_type == types::BIT && size.is_none_or(|s| s <= 1) {
            return Self::Boolean;
        }
        if BOOLEAN_NAMES.contains(&name) {
            Self::Boolean
        } else if name == "uuid" || name == "uniqueidentifier" {
            Self::Uuid
        } else if name == "json" || name == "jsonb" {
            Self::Json
        } else if name == "date" {
            Self::Date
        } else if name.starts_with("timestamp") || name.starts_with("datetime") {
            Self::DateTime
        } else if name == "time"
            || name == "timetz"
            || name.starts_with("time with")
            || name.starts_with("time without")
        {
            Self::Time
        } else if NUMBER_NAMES.contains(&name)
            || name.starts_with("serial")
            || name.starts_with("float")
            || name.starts_with("double")
        {
            Self::Number
        } else if BINARY_NAMES.contains(&name) || name.ends_with("blob") {
            Self::Binary
        } else if TEXT_NAMES.contains(&name)
            || name.ends_with("text")
            || name.contains("char")
            || name.contains("clob")
        {
            Self::Text
        } else if name.is_empty() {
            // SQLite columns may have no declared type at all; their values are usually text.
            Self::Text
        } else {
            Self::by_jdbc_type(jdbc_type)
        }
    }

    fn by_jdbc_type(jdbc_type: i32) -> Self {
        match jdbc_type {
            types::TINYINT
            | types::SMALLINT
            | types::INTEGER
            | types::BIGINT
            | types::REAL
            | types::FLOAT
            | types::DOUBLE
            | types::NUMERIC
            | types::DECIMAL => Self::Number,
            types::CHAR
            | types::VARCHAR
            | types::LONGVARCHAR
            | types::NCHAR
            | types::NVARCHAR
            | types::LONGNVARCHAR
            | types::CLOB
            | types::NCLOB => Self::Text,
            types::DATE => Self::Date,
            types::TIME | types::TIME_WITH_TIMEZONE => Self::Time,
            types::TIMESTAMP | types::TIMESTAMP_WITH_TIMEZONE => Self::DateTime,
            types::BINARY | types::VARBINARY | types::LONGVARBINARY | types::BLOB => Self::Binary,
            _ => Self::Other,
        }
    }
}

/// How many values an operator takes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Arity {
    None,
    One,
    Two,
    List,
}

macro_rules! operators {
    ($($name:ident => $label:literal, $arity:ident;)*) => {
        /// A comparison in a filter condition. Its label reads as part of a sentence: "created_at · after · 2026-01-01".
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
        pub enum FilterOperator { $($name),* }

        impl FilterOperator {
            pub const ALL: &[FilterOperator] = &[$(Self::$name),*];

            pub fn base_label(self) -> &'static str {
                match self { $(Self::$name => $label),* }
            }

            pub fn arity(self) -> Arity {
                match self { $(Self::$name => Arity::$arity),* }
            }

            /// The Kotlin enum name.
            pub fn name(self) -> &'static str {
                match self { $(Self::$name => stringify!($name)),* }
            }
        }
    };
}

operators! {
    Equals => "=", One;
    NotEquals => "≠", One;
    Less => "<", One;
    LessOrEqual => "≤", One;
    Greater => ">", One;
    GreaterOrEqual => "≥", One;
    Between => "between", Two;
    NotBetween => "not between", Two;
    In => "is one of", List;
    NotIn => "is none of", List;
    Contains => "contains", One;
    NotContains => "doesn't contain", One;
    StartsWith => "starts with", One;
    EndsWith => "ends with", One;
    Like => "like", One;
    NotLike => "not like", One;
    Regex => "matches regex", One;
    NotRegex => "doesn't match regex", One;
    IsEmpty => "is empty", None;
    IsNotEmpty => "is not empty", None;
    IsTrue => "is true", None;
    IsFalse => "is false", None;
    Today => "is today", None;
    Yesterday => "is yesterday", None;
    ThisWeek => "is this week", None;
    ThisMonth => "is this month", None;
    ThisYear => "is this year", None;
    LastDays => "in the last … days", One;
    IsNull => "is NULL", None;
    IsNotNull => "is not NULL", None;
}

impl FilterOperator {
    pub fn by_name(name: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|o| o.name() == name)
    }

    /// Comparisons read better as words for dates and times.
    pub fn label(self, kind: ValueKind) -> &'static str {
        use FilterOperator::*;
        if !kind.is_temporal() {
            return self.base_label();
        }
        let time = kind == ValueKind::Time;
        match self {
            Equals => {
                if time {
                    "is at"
                } else {
                    "is on"
                }
            }
            NotEquals => {
                if time {
                    "is not at"
                } else {
                    "is not on"
                }
            }
            Less => "is before",
            LessOrEqual => {
                if time {
                    "is at or before"
                } else {
                    "is on or before"
                }
            }
            Greater => "is after",
            GreaterOrEqual => {
                if time {
                    "is at or after"
                } else {
                    "is on or after"
                }
            }
            _ => self.base_label(),
        }
    }

    /// Text matching where letter case can matter.
    pub fn matches_text(self) -> bool {
        use FilterOperator::*;
        matches!(
            self,
            Contains | NotContains | StartsWith | EndsWith | Like | NotLike | Regex | NotRegex
        )
    }

    /// Operators that make sense for `kind`, most useful first, in sections the UI separates. Regex is offered where
    /// the engine has it.
    pub fn for_kind(kind: ValueKind, regex: bool) -> Vec<Vec<FilterOperator>> {
        use FilterOperator::*;
        let nulls = vec![IsNull, IsNotNull];
        let regexes: Vec<FilterOperator> = if regex { vec![Regex, NotRegex] } else { vec![] };
        let with = |mut a: Vec<FilterOperator>, b: &[FilterOperator]| {
            a.extend_from_slice(b);
            a
        };
        match kind {
            ValueKind::Text => vec![
                vec![
                    Equals,
                    NotEquals,
                    Contains,
                    NotContains,
                    StartsWith,
                    EndsWith,
                ],
                with(vec![In, NotIn, Like, NotLike], &regexes),
                with(vec![IsEmpty, IsNotEmpty], &nulls),
                vec![
                    Less,
                    LessOrEqual,
                    Greater,
                    GreaterOrEqual,
                    Between,
                    NotBetween,
                ],
            ],
            ValueKind::Number => vec![
                vec![
                    Equals,
                    NotEquals,
                    Less,
                    LessOrEqual,
                    Greater,
                    GreaterOrEqual,
                ],
                vec![Between, NotBetween, In, NotIn],
                nulls,
                vec![Contains, StartsWith, EndsWith],
            ],
            ValueKind::Boolean => vec![vec![IsTrue, IsFalse], nulls],
            ValueKind::Date | ValueKind::DateTime => vec![
                vec![
                    Equals,
                    NotEquals,
                    Less,
                    LessOrEqual,
                    Greater,
                    GreaterOrEqual,
                ],
                vec![Between, NotBetween, In, NotIn],
                vec![Today, Yesterday, ThisWeek, ThisMonth, ThisYear, LastDays],
                nulls,
            ],
            ValueKind::Time => vec![
                vec![
                    Equals,
                    NotEquals,
                    Less,
                    LessOrEqual,
                    Greater,
                    GreaterOrEqual,
                ],
                vec![Between, NotBetween],
                nulls,
            ],
            ValueKind::Uuid => vec![
                vec![Equals, NotEquals, In, NotIn],
                vec![StartsWith, Contains],
                nulls,
            ],
            ValueKind::Json => vec![
                with(vec![Contains, NotContains, Equals, NotEquals], &regexes),
                nulls,
            ],
            ValueKind::Binary => vec![nulls],
            ValueKind::Other => vec![
                vec![Equals, NotEquals, In, NotIn],
                with(vec![Contains, NotContains, StartsWith, EndsWith], &regexes),
                vec![Less, LessOrEqual, Greater, GreaterOrEqual, Between],
                nulls,
            ],
        }
    }

    /// What a new condition on a `kind` column starts with.
    pub fn default_for(kind: ValueKind) -> Self {
        match kind {
            ValueKind::Text | ValueKind::Json => Self::Contains,
            ValueKind::Boolean => Self::IsTrue,
            ValueKind::Binary => Self::IsNotNull,
            _ => Self::Equals,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Combinator {
    #[default]
    All,
    Any,
}

impl Combinator {
    pub fn keyword(self) -> &'static str {
        match self {
            Self::All => "AND",
            Self::Any => "OR",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Any => "any",
        }
    }
}

/// `column <operator> value(s)`. `value` holds the whole list for list operators; `value2` the upper bound.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FilterCondition {
    pub id: i64,
    pub column: String,
    pub operator: FilterOperator,
    pub value: String,
    pub value2: String,
    pub case_sensitive: bool,
    pub enabled: bool,
}

impl FilterCondition {
    pub fn new(id: i64, column: &str, operator: FilterOperator, value: &str) -> Self {
        Self {
            id,
            column: column.to_owned(),
            operator,
            value: value.to_owned(),
            value2: String::new(),
            case_sensitive: false,
            enabled: true,
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FilterGroup {
    pub id: i64,
    pub combinator: Combinator,
    pub children: Vec<FilterNode>,
    /// Matches rows the group would not: `NOT (…)`.
    pub negated: bool,
    pub enabled: bool,
}

/// A hand-written SQL condition, used as is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RawCondition {
    pub id: i64,
    pub sql: String,
    pub enabled: bool,
}

/// A filter tree, as built in the table viewer. Disabled nodes are kept but ignored.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FilterNode {
    Condition(FilterCondition),
    Group(FilterGroup),
    Raw(RawCondition),
}

impl FilterNode {
    pub fn id(&self) -> i64 {
        match self {
            Self::Condition(c) => c.id,
            Self::Group(g) => g.id,
            Self::Raw(r) => r.id,
        }
    }

    pub fn enabled(&self) -> bool {
        match self {
            Self::Condition(c) => c.enabled,
            Self::Group(g) => g.enabled,
            Self::Raw(r) => r.enabled,
        }
    }
}

/// A column a filter can refer to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FilterColumn {
    pub name: String,
    pub type_name: String,
    pub kind: ValueKind,
}

impl FilterColumn {
    pub fn new(name: &str, type_name: &str, kind: ValueKind) -> Self {
        Self {
            name: name.to_owned(),
            type_name: type_name.to_owned(),
            kind,
        }
    }
}

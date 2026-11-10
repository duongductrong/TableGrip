//! Filter trees to parameterized SQL (`FilterCompiler.kt`).

use chrono::{
    Datelike, Days, FixedOffset, Months, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Timelike,
    Weekday,
};
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};

use super::values::{self, FilterResult, FilterValueError, ParamValue, SqlParam, TemporalValue};
use super::{
    FilterColumn, FilterCondition, FilterGroup, FilterNode, FilterOperator, RawCondition, ValueKind,
};
use crate::decimal::Decimal;
use crate::dialect::SqlDialect;
use crate::kt;
use crate::lexer::{SqlLexer, TokenType};

/// A WHERE condition with its bound parameters; `preview` has the values inlined, for display and copying only.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SqlFilter {
    pub sql: String,
    pub parameters: Vec<SqlParam>,
    pub preview: String,
}

impl SqlFilter {
    /// A condition typed as SQL; None when blank.
    pub fn raw(sql: &str) -> Option<Self> {
        let trimmed = kt::trim(sql);
        let trimmed = kt::trim(trimmed.strip_suffix(';').unwrap_or(trimmed));
        (!trimmed.is_empty()).then(|| Self {
            sql: trimmed.to_owned(),
            parameters: Vec::new(),
            preview: trimmed.to_owned(),
        })
    }
}

/// The outcome of compiling a filter: the condition (None when nothing is active) or, per node id, what's wrong.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompiledFilter {
    pub filter: Option<SqlFilter>,
    pub issues: BTreeMap<i64, String>,
}

impl CompiledFilter {
    pub fn is_valid(&self) -> bool {
        self.issues.is_empty()
    }
}

/// At most this many values in an "is one of" list.
pub const MAX_LIST_SIZE: usize = 1_000;

/// Fragment of SQL kept alongside its parameters and a literal-inlined copy.
#[derive(Clone, Debug)]
struct Sql {
    text: String,
    preview: String,
    params: Vec<SqlParam>,
}

impl Sql {
    fn plus(mut self, other: Sql) -> Sql {
        self.text.push_str(&other.text);
        self.preview.push_str(&other.preview);
        self.params.extend(other.params);
        self
    }

    fn str(mut self, other: &str) -> Sql {
        self.text.push_str(other);
        self.preview.push_str(other);
        self
    }
}

fn t(text: impl Into<String>) -> Sql {
    let text = text.into();
    Sql {
        preview: text.clone(),
        text,
        params: Vec::new(),
    }
}

fn err<T>(message: impl Into<String>) -> FilterResult<T> {
    Err(FilterValueError::new(message))
}

/// Turns a [`FilterGroup`] into parameterized SQL for a dialect. Values are parsed for their column's [`ValueKind`]
/// and bound, never spliced into the SQL. Relative dates resolve against `now` (in `zone`) at compile time.
pub struct FilterCompiler<Tz: TimeZone = FixedOffset> {
    dialect: SqlDialect,
    columns: HashMap<String, FilterColumn>,
    zone: Tz,
    now: NaiveDateTime,
    today: NaiveDate,
    week_start: Weekday,
    issues: BTreeMap<i64, String>,
}

impl<Tz: TimeZone> FilterCompiler<Tz> {
    /// A compiler for `columns`, with `now` the local date-time in `zone`. Weeks start on Monday unless
    /// [`Self::with_week_start`] says otherwise (Kotlin takes it from the default locale).
    pub fn new(
        dialect: SqlDialect,
        columns: &[FilterColumn],
        now: NaiveDateTime,
        zone: Tz,
    ) -> Self {
        let now = now.with_nanosecond(0).unwrap_or(now);
        Self {
            dialect,
            columns: columns
                .iter()
                .map(|c| (c.name.clone(), c.clone()))
                .collect(),
            zone,
            now,
            today: now.date(),
            week_start: Weekday::Mon,
            issues: BTreeMap::new(),
        }
    }

    pub fn with_week_start(mut self, week_start: Weekday) -> Self {
        self.week_start = week_start;
        self
    }

    pub fn compile(&mut self, root: &FilterGroup) -> CompiledFilter {
        self.issues.clear();
        let filter = self.group(root, true).map(|sql| SqlFilter {
            sql: sql.text,
            parameters: sql.params,
            preview: sql.preview,
        });
        if filter.as_ref().is_some_and(|f| !f.parameters.is_empty()) {
            for child in root.children.iter().filter(|c| c.enabled()) {
                self.flag_placeholders(child);
            }
        }
        CompiledFilter {
            filter: filter.filter(|_| self.issues.is_empty()),
            issues: self.issues.clone(),
        }
    }

    fn p(&self, param: SqlParam) -> Sql {
        Sql {
            text: "?".to_owned(),
            preview: self.dialect.literal_param(&param),
            params: vec![param],
        }
    }

    fn join(parts: Vec<Sql>, separator: &str) -> Sql {
        let mut parts = parts.into_iter();
        let first = parts.next().expect("parts");
        parts.fold(first, |acc, part| acc.str(separator).plus(part))
    }

    /// Joins with OR, parenthesized so it can sit inside an AND.
    fn any_of(mut parts: Vec<Sql>) -> Sql {
        if parts.len() == 1 {
            parts.pop().unwrap()
        } else {
            t("(").plus(Self::join(parts, " OR ")).str(")")
        }
    }

    fn group(&mut self, group: &FilterGroup, top: bool) -> Option<Sql> {
        let mut parts = Vec::new();
        for node in group.children.iter().filter(|n| n.enabled()) {
            let part = match node {
                FilterNode::Group(g) => self.group(g, false),
                FilterNode::Raw(r) => self.raw(r),
                FilterNode::Condition(c) => self.condition(c),
            };
            parts.extend(part);
        }
        if parts.is_empty() {
            return None;
        }
        let single = parts.len() == 1;
        let joined = Self::join(parts, &format!(" {} ", group.combinator.keyword()));
        Some(if group.negated {
            t("NOT (").plus(joined).str(")")
        } else if single || top {
            joined
        } else {
            t("(").plus(joined).str(")")
        })
    }

    fn raw(&mut self, node: &RawCondition) -> Option<Sql> {
        match SqlFilter::raw(&node.sql) {
            None => {
                self.issues
                    .insert(node.id, "Enter a SQL condition".to_owned());
                None
            }
            Some(f) => Some(t(format!("({})", f.sql))),
        }
    }

    /// A `?` in hand-written SQL would be taken for a parameter once other conditions bind values.
    fn flag_placeholders(&mut self, node: &FilterNode) {
        match node {
            FilterNode::Group(g) => {
                for child in g.children.iter().filter(|c| c.enabled()) {
                    self.flag_placeholders(child);
                }
            }
            FilterNode::Raw(r) => {
                let lexer = SqlLexer::new(self.dialect.syntax());
                if lexer
                    .tokenize(&r.sql)
                    .iter()
                    .any(|t| t.kind == TokenType::Parameter && t.text(&r.sql) == "?")
                {
                    self.issues.insert(
                        r.id,
                        "“?” can't be combined with other conditions; use the SQL filter for this expression".to_owned(),
                    );
                }
            }
            FilterNode::Condition(_) => {}
        }
    }

    fn condition(&mut self, condition: &FilterCondition) -> Option<Sql> {
        let Some(column) = self.columns.get(&condition.column).cloned() else {
            let message = if condition.column.is_empty() {
                "Choose a column".to_owned()
            } else {
                format!("No column “{}” in this table", condition.column)
            };
            self.issues.insert(condition.id, message);
            return None;
        };
        if !FilterOperator::for_kind(column.kind, self.dialect.supports_regex())
            .iter()
            .any(|s| s.contains(&condition.operator))
        {
            self.issues.insert(
                condition.id,
                format!(
                    "“{}” doesn't apply to {} columns",
                    condition.operator.label(column.kind),
                    column.kind.label()
                ),
            );
            return None;
        }
        match self.compile_condition(condition, &column) {
            Ok(sql) => Some(sql),
            Err(e) => {
                self.issues.insert(condition.id, e.0);
                None
            }
        }
    }

    fn compile_condition(
        &self,
        condition: &FilterCondition,
        column: &FilterColumn,
    ) -> FilterResult<Sql> {
        use FilterOperator as Op;
        let d = self.dialect;
        let c = d.quote_identifier(&column.name);
        let kind = column.kind;
        let text = if kind == ValueKind::Text {
            c.clone()
        } else {
            d.cast_to_text(&c)
        };
        let op = condition.operator;
        Ok(match op {
            Op::IsNull => t(format!("{c} IS NULL")),
            Op::IsNotNull => t(format!("{c} IS NOT NULL")),
            Op::IsEmpty => t(format!("({c} IS NULL OR {text} = '')")),
            Op::IsNotEmpty => t(format!("({c} IS NOT NULL AND {text} <> '')")),
            Op::IsTrue | Op::IsFalse => t(format!("{c} = ")).plus(self.p(SqlParam::new(
                ParamValue::Bool(op == Op::IsTrue),
                ValueKind::Boolean,
            ))),
            Op::Contains | Op::NotContains | Op::StartsWith | Op::EndsWith => {
                let escaped = escape_like(required(&condition.value)?);
                let pattern = match op {
                    Op::StartsWith => format!("{escaped}%"),
                    Op::EndsWith => format!("%{escaped}"),
                    _ => format!("%{escaped}%"),
                };
                self.like(
                    &text,
                    &pattern,
                    condition.case_sensitive,
                    op == Op::NotContains,
                )
            }
            Op::Like | Op::NotLike => self.like(
                &text,
                &required(&condition.value)?.replace('!', "!!"),
                condition.case_sensitive,
                op == Op::NotLike,
            ),
            Op::Regex | Op::NotRegex => {
                let Some((before, after)) =
                    d.regex_match(&text, condition.case_sensitive, op == Op::NotRegex)
                else {
                    return err("This database has no regular expressions; use “like” instead");
                };
                let value = ParamValue::Text(required(&condition.value)?.to_owned());
                t(before)
                    .plus(self.p(SqlParam::new(value, ValueKind::Text)))
                    .str(&after)
            }
            Op::Equals
            | Op::NotEquals
            | Op::Less
            | Op::LessOrEqual
            | Op::Greater
            | Op::GreaterOrEqual => self.compare(&c, &text, op, kind, &condition.value)?,
            Op::Between | Op::NotBetween => {
                self.between(&c, kind, condition, op == Op::NotBetween)?
            }
            Op::In | Op::NotIn => self.in_list(&c, kind, &condition.value, op == Op::NotIn)?,
            Op::Today
            | Op::Yesterday
            | Op::ThisWeek
            | Op::ThisMonth
            | Op::ThisYear
            | Op::LastDays => {
                let (from, until) = self.period(op, &condition.value)?;
                self.day_range(&c, kind, from, until)
            }
        })
    }

    fn like(&self, expression: &str, pattern: &str, case_sensitive: bool, negated: bool) -> Sql {
        let (before, after) = self.dialect.text_match(expression, case_sensitive, negated);
        let value = ParamValue::Text(self.dialect.text_pattern(pattern, case_sensitive));
        t(before)
            .plus(self.p(SqlParam::new(value, ValueKind::Text)))
            .str(&after)
    }

    fn compare(
        &self,
        c: &str,
        text: &str,
        op: FilterOperator,
        kind: ValueKind,
        input: &str,
    ) -> FilterResult<Sql> {
        use FilterOperator as Op;
        let symbol = match op {
            Op::Equals => "=",
            Op::NotEquals => "<>",
            Op::Less => "<",
            Op::LessOrEqual => "<=",
            Op::Greater => ">",
            _ => ">=",
        };
        if kind == ValueKind::Json {
            let value = ParamValue::Text(required(input)?.to_owned());
            return Ok(
                t(format!("{text} {symbol} ")).plus(self.p(SqlParam::new(value, ValueKind::Text)))
            );
        }
        if kind.is_temporal() {
            let value = self.temporal(input, kind)?;
            if let (ValueKind::DateTime, TemporalValue::Day(date)) = (kind, value) {
                // A bare date on a date-time column means the whole day.
                let start = || self.p(SqlParam::new(ParamValue::DateTime(midnight(date)), kind));
                let end = || -> FilterResult<Sql> {
                    Ok(self.p(SqlParam::new(
                        ParamValue::DateTime(midnight(next_day(date)?)),
                        kind,
                    )))
                };
                return Ok(match op {
                    Op::Equals => t(format!("({c} >= "))
                        .plus(start())
                        .str(&format!(" AND {c} < "))
                        .plus(end()?)
                        .str(")"),
                    Op::NotEquals => t(format!("({c} < "))
                        .plus(start())
                        .str(&format!(" OR {c} >= "))
                        .plus(end()?)
                        .str(")"),
                    Op::Less => t(format!("{c} < ")).plus(start()),
                    Op::LessOrEqual => t(format!("{c} < ")).plus(end()?),
                    Op::Greater => t(format!("{c} >= ")).plus(end()?),
                    _ => t(format!("{c} >= ")).plus(start()),
                });
            }
            return Ok(t(format!("{c} {symbol} ")).plus(self.p(bindable(value, kind))));
        }
        Ok(t(format!("{c} {symbol} ")).plus(self.p(scalar(kind, input)?)))
    }

    fn between(
        &self,
        c: &str,
        kind: ValueKind,
        condition: &FilterCondition,
        negated: bool,
    ) -> FilterResult<Sql> {
        if condition.value.is_empty() || condition.value2.is_empty() {
            return err("Enter both bounds");
        }
        if kind.is_temporal() {
            let from = bindable(self.temporal(&condition.value, kind)?, kind);
            let to = self.temporal(&condition.value2, kind)?;
            // A whole day as the upper bound includes that day.
            let inclusive_day = kind == ValueKind::DateTime && matches!(to, TemporalValue::Day(_));
            let upper = match to {
                TemporalValue::Day(date) if inclusive_day => {
                    SqlParam::new(ParamValue::DateTime(midnight(next_day(date)?)), kind)
                }
                _ => bindable(to, kind),
            };
            check_order(&from.value, &upper.value)?;
            return Ok(if negated {
                let or = if inclusive_day {
                    format!(" OR {c} >= ")
                } else {
                    format!(" OR {c} > ")
                };
                t(format!("({c} < "))
                    .plus(self.p(from))
                    .str(&or)
                    .plus(self.p(upper))
                    .str(")")
            } else {
                let and = if inclusive_day {
                    format!(" AND {c} < ")
                } else {
                    format!(" AND {c} <= ")
                };
                t(format!("({c} >= "))
                    .plus(self.p(from))
                    .str(&and)
                    .plus(self.p(upper))
                    .str(")")
            });
        }
        let from = scalar(kind, &condition.value)?;
        let to = scalar(kind, &condition.value2)?;
        check_order(&from.value, &to.value)?;
        let not = if negated { "NOT " } else { "" };
        Ok(t(format!("{c} {not}BETWEEN "))
            .plus(self.p(from))
            .str(" AND ")
            .plus(self.p(to)))
    }

    fn in_list(&self, c: &str, kind: ValueKind, input: &str, negated: bool) -> FilterResult<Sql> {
        let items = values::parse_list(input);
        if items.is_empty() {
            return err("Enter one or more values, separated by commas");
        }
        if items.len() > MAX_LIST_SIZE {
            return err(format!("At most {MAX_LIST_SIZE} values"));
        }
        let has_null = items.iter().any(Option::is_none);
        let mut parts = Vec::new();
        let mut days: Vec<NaiveDate> = Vec::new();
        let mut params: Vec<SqlParam> = Vec::new();
        for value in items.iter().flatten() {
            if kind.is_temporal() {
                let parsed = self.temporal(value, kind)?;
                match parsed {
                    TemporalValue::Day(date) if kind == ValueKind::DateTime => days.push(date),
                    _ => params.push(bindable(parsed, kind)),
                }
            } else if kind == ValueKind::Text || kind == ValueKind::Other {
                // A quoted '' is a real (empty) text value in a list.
                params.push(SqlParam::new(ParamValue::Text(value.clone()), kind));
            } else {
                params.push(scalar(kind, value)?);
            }
        }
        if !params.is_empty() {
            let mut distinct: Vec<SqlParam> = Vec::new();
            for p in params {
                if !distinct.contains(&p) {
                    distinct.push(p);
                }
            }
            let list = Self::join(distinct.into_iter().map(|p| self.p(p)).collect(), ", ");
            if negated && days.is_empty() && !has_null {
                return Ok(t(format!("{c} NOT IN (")).plus(list).str(")"));
            }
            parts.push(t(format!("{c} IN (")).plus(list).str(")"));
        }
        let mut seen = Vec::new();
        for day in days {
            if !seen.contains(&day) {
                seen.push(day);
                parts.push(self.day_range(c, kind, day, next_day(day)?));
            }
        }
        if has_null {
            parts.push(t(format!("{c} IS NULL")));
        }
        let single = parts.len() == 1;
        let any = Self::any_of(parts);
        Ok(if negated {
            t("NOT ").plus(if single {
                t("(").plus(any).str(")")
            } else {
                any
            })
        } else {
            any
        })
    }

    /// `[from, until)` in whole days.
    fn day_range(&self, c: &str, kind: ValueKind, from: NaiveDate, until: NaiveDate) -> Sql {
        let bound = |date: NaiveDate| {
            let value = if kind == ValueKind::DateTime {
                ParamValue::DateTime(midnight(date))
            } else {
                ParamValue::Date(date)
            };
            self.p(SqlParam::new(value, kind))
        };
        t(format!("({c} >= "))
            .plus(bound(from))
            .str(&format!(" AND {c} < "))
            .plus(bound(until))
            .str(")")
    }

    fn period(&self, op: FilterOperator, input: &str) -> FilterResult<(NaiveDate, NaiveDate)> {
        use FilterOperator as Op;
        let today = self.today;
        let range = |from: Option<NaiveDate>, until: Option<NaiveDate>| match (from, until) {
            (Some(f), Some(u)) => Ok((f, u)),
            _ => err("That date is out of range"),
        };
        match op {
            Op::Today => range(Some(today), today.succ_opt()),
            Op::Yesterday => range(today.pred_opt(), Some(today)),
            Op::ThisWeek => {
                let back = (7 + today.weekday().num_days_from_monday()
                    - self.week_start.num_days_from_monday())
                    % 7;
                let start = today.checked_sub_days(Days::new(u64::from(back)));
                range(start, start.and_then(|s| s.checked_add_days(Days::new(7))))
            }
            Op::ThisMonth => {
                let start = today.with_day(1);
                range(
                    start,
                    start.and_then(|s| s.checked_add_months(Months::new(1))),
                )
            }
            Op::ThisYear => {
                let start = today.with_ordinal(1);
                range(
                    start,
                    start.and_then(|s| s.checked_add_months(Months::new(12))),
                )
            }
            _ => {
                let days = values::number(input)?
                    .to_i64_exact()
                    .filter(|d| (1..=36_500).contains(d))
                    .ok_or_else(|| FilterValueError::new("Enter a whole number of days"))?;
                // "The last 7 days" counts today as one of them.
                range(
                    today.checked_sub_days(Days::new(days as u64 - 1)),
                    today.succ_opt(),
                )
            }
        }
    }

    fn temporal(&self, input: &str, kind: ValueKind) -> FilterResult<TemporalValue> {
        let value = values::temporal(required(input)?, self.now, &self.zone)?;
        match value {
            TemporalValue::Day(_) if kind == ValueKind::Time => {
                err("Enter a time of day, e.g. 13:45")
            }
            TemporalValue::TimeOfDay(_) if kind != ValueKind::Time => err(format!(
                "Enter a date, e.g. {}",
                values::format_date(self.today)
            )),
            _ => Ok(value),
        }
    }
}

fn midnight(date: NaiveDate) -> NaiveDateTime {
    date.and_time(NaiveTime::MIN)
}

fn next_day(date: NaiveDate) -> FilterResult<NaiveDate> {
    date.succ_opt()
        .ok_or_else(|| FilterValueError::new("That date is out of range"))
}

fn required(value: &str) -> FilterResult<&str> {
    if value.is_empty() {
        err("Enter a value")
    } else {
        Ok(value)
    }
}

fn escape_like(value: &str) -> String {
    value
        .replace('!', "!!")
        .replace('%', "!%")
        .replace('_', "!_")
}

fn check_order(from: &ParamValue, to: &ParamValue) -> FilterResult<()> {
    let number = |v: &ParamValue| match v {
        ParamValue::Long(n) => Some(Decimal::from_i64(*n)),
        ParamValue::Decimal(d) => Some(d.clone()),
        _ => None,
    };
    let ordered = match (from, to) {
        _ if number(from).is_some() && number(to).is_some() => {
            number(from).unwrap().cmp_value(&number(to).unwrap()) != Ordering::Greater
        }
        // Text order is the column's collation's business.
        (ParamValue::Text(_), _) => true,
        (ParamValue::Date(a), ParamValue::Date(b)) => a <= b,
        (ParamValue::DateTime(a), ParamValue::DateTime(b)) => a <= b,
        (ParamValue::Time(a), ParamValue::Time(b)) => a <= b,
        (ParamValue::Bool(a), ParamValue::Bool(b)) => a <= b,
        _ => true,
    };
    if ordered {
        Ok(())
    } else {
        err("The first bound is after the second")
    }
}

/// The value to bind for a parsed date, date-time or time on a `kind` column.
fn bindable(value: TemporalValue, kind: ValueKind) -> SqlParam {
    let value = match (kind, value) {
        (ValueKind::Date, TemporalValue::Day(d)) => ParamValue::Date(d),
        (ValueKind::Date, TemporalValue::Moment(dt)) => ParamValue::Date(dt.date()),
        (ValueKind::Time, TemporalValue::Moment(dt)) => ParamValue::Time(dt.time()),
        (ValueKind::Time, TemporalValue::TimeOfDay(t)) => ParamValue::Time(t),
        (_, TemporalValue::Day(d)) => ParamValue::DateTime(midnight(d)),
        (_, TemporalValue::Moment(dt)) => ParamValue::DateTime(dt),
        (_, other) => unreachable!("rejected by temporal(): {other:?}"),
    };
    SqlParam::new(value, kind)
}

fn scalar(kind: ValueKind, input: &str) -> FilterResult<SqlParam> {
    let value = match kind {
        ValueKind::Number => {
            let number = values::number(input)?;
            match number.to_i64_exact() {
                Some(n) => ParamValue::Long(n),
                None => ParamValue::Decimal(number),
            }
        }
        ValueKind::Uuid => ParamValue::Uuid(values::uuid(input)?),
        ValueKind::Boolean => ParamValue::Bool(
            values::boolean(input).ok_or_else(|| FilterValueError::new("Enter true or false"))?,
        ),
        ValueKind::Binary => return err("Binary values can only be tested for NULL"),
        _ => ParamValue::Text(required(input)?.to_owned()),
    };
    Ok(SqlParam::new(value, kind))
}

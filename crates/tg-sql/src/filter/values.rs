//! Parsing what users type into filter value fields, and the values filters bind.

use chrono::{
    DateTime, Datelike, Days, FixedOffset, Months, NaiveDate, NaiveDateTime, NaiveTime, TimeDelta,
    TimeZone, Timelike,
};
use regex::Regex;
use std::sync::LazyLock;

use super::ValueKind;
use crate::decimal::Decimal;
use crate::kt;

/// A value the user typed that can't be used for its column; the message says why, in words for the UI.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FilterValueError(pub String);

impl FilterValueError {
    pub fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl std::fmt::Display for FilterValueError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for FilterValueError {}

pub type FilterResult<T> = Result<T, FilterValueError>;

/// A point in time typed into a filter: a whole day, an exact moment, or a time of day.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TemporalValue {
    Day(NaiveDate),
    Moment(NaiveDateTime),
    TimeOfDay(NaiveTime),
}

/// A value bound to a filter's `?`: what the Kotlin core binds as a String, Long, BigDecimal, Boolean, UUID or
/// java.time value.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum ParamValue {
    Text(String),
    Long(i64),
    Decimal(Decimal),
    Bool(bool),
    /// Canonical lower-case form, `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.
    Uuid(String),
    Date(NaiveDate),
    DateTime(NaiveDateTime),
    Time(NaiveTime),
}

/// A value bound to a filter's `?`, with the kind of column it's compared with.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SqlParam {
    pub value: ParamValue,
    pub kind: ValueKind,
}

impl SqlParam {
    pub fn new(value: ParamValue, kind: ValueKind) -> Self {
        Self { value, kind }
    }
}

/// Splits a list typed as `a, b, "c, d"` (commas or new lines; single or double quotes keep separators and spaces). An
/// unquoted `NULL` comes back as None.
pub fn parse_list(text: &str) -> Vec<Option<String>> {
    let chars: Vec<char> = text.chars().collect();
    let mut items = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && (kt::is_whitespace(chars[i]) || chars[i] == ',') {
            i += 1;
        }
        if i >= chars.len() {
            break;
        }
        let quote = chars[i];
        if quote == '"' || quote == '\'' {
            let mut value = String::new();
            i += 1;
            while i < chars.len() {
                if chars[i] == quote {
                    if chars.get(i + 1) == Some(&quote) {
                        value.push(quote);
                        i += 2;
                    } else {
                        break;
                    }
                } else {
                    value.push(chars[i]);
                    i += 1;
                }
            }
            i += 1; // closing quote
            items.push(Some(value));
            while i < chars.len() && chars[i] != ',' && chars[i] != '\n' {
                i += 1;
            }
        } else {
            let start = i;
            while i < chars.len() && chars[i] != ',' && chars[i] != '\n' {
                i += 1;
            }
            let value: String = chars[start..i].iter().collect();
            let value = kt::trim(&value);
            items.push(if kt::eq_ignore_case(value, "null") {
                None
            } else {
                Some(value.to_owned())
            });
        }
    }
    items
}

pub fn number(text: &str) -> FilterResult<Decimal> {
    let cleaned = kt::trim(text).replace(['_', ' '], "");
    Decimal::parse(&cleaned).ok_or_else(|| {
        FilterValueError::new(if cleaned.is_empty() {
            "Enter a number".to_owned()
        } else {
            format!("“{text}” isn't a number")
        })
    })
}

static UUID_PATTERN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
        .unwrap()
});

/// A UUID in its canonical lower-case form; braces and missing hyphens are accepted.
pub fn uuid(text: &str) -> FilterResult<String> {
    let trimmed = kt::trim(text);
    let trimmed = trimmed.strip_prefix('{').unwrap_or(trimmed);
    let trimmed = trimmed.strip_suffix('}').unwrap_or(trimmed);
    if !UUID_PATTERN.is_match(trimmed) {
        return Err(FilterValueError::new(format!("“{text}” isn't a UUID")));
    }
    let hex = trimmed.replace('-', "").to_lowercase();
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    ))
}

/// `true`/`false` as the grid shows them on any engine: `t`, `1`, `yes`…
pub fn boolean(text: &str) -> Option<bool> {
    match kt::trim(text).to_lowercase().as_str() {
        "true" | "t" | "1" | "yes" | "y" | "on" => Some(true),
        "false" | "f" | "0" | "no" | "n" | "off" => Some(false),
        _ => None,
    }
}

// Java's `\s` and `\d` are ASCII-only.
const WS: &str = r"[ \t\n\x0B\x0C\r]";
static OFFSET: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(&format!(r"^([+-]){WS}*([0-9]+){WS}*([a-z]+)")).unwrap());
static AGO: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(&format!(r"^([0-9]+){WS}*([a-z]+){WS}+ago$")).unwrap());
static ANCHOR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(now|today|yesterday|tomorrow)").unwrap());
static TRAILING_OFFSET: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)(?:z|[+-][0-9]{2}(?::?[0-9]{2})?)$").unwrap());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Unit {
    Seconds,
    Minutes,
    Hours,
    Days,
    Weeks,
    Months,
    Years,
}

impl Unit {
    fn is_date_based(self) -> bool {
        matches!(self, Self::Days | Self::Weeks | Self::Months | Self::Years)
    }
}

fn unit(name: &str) -> FilterResult<Unit> {
    Ok(match name {
        "s" | "sec" | "secs" | "second" | "seconds" => Unit::Seconds,
        "min" | "mins" | "minute" | "minutes" => Unit::Minutes,
        "h" | "hr" | "hrs" | "hour" | "hours" => Unit::Hours,
        "d" | "day" | "days" => Unit::Days,
        "w" | "wk" | "week" | "weeks" => Unit::Weeks,
        "mo" | "mon" | "month" | "months" => Unit::Months,
        "y" | "yr" | "year" | "years" => Unit::Years,
        _ => {
            return Err(FilterValueError::new(format!(
                "Unknown unit “{name}”; use s, min, h, d, w, mo or y"
            )));
        }
    })
}

fn out_of_range() -> FilterValueError {
    FilterValueError::new("That date is out of range")
}

fn plus_date(date: NaiveDate, amount: i64, unit: Unit) -> FilterResult<NaiveDate> {
    let months = |m: i64| -> Option<NaiveDate> {
        let m = u32::try_from(m.unsigned_abs()).ok()?;
        if amount >= 0 {
            date.checked_add_months(Months::new(m))
        } else {
            date.checked_sub_months(Months::new(m))
        }
    };
    let days = |d: i64| -> Option<NaiveDate> {
        if d >= 0 {
            date.checked_add_days(Days::new(d as u64))
        } else {
            date.checked_sub_days(Days::new(d.unsigned_abs()))
        }
    };
    match unit {
        Unit::Days => days(amount),
        Unit::Weeks => amount.checked_mul(7).and_then(days),
        Unit::Months => months(amount),
        Unit::Years => amount.checked_mul(12).and_then(months),
        _ => None,
    }
    .ok_or_else(out_of_range)
}

fn plus_date_time(value: NaiveDateTime, amount: i64, unit: Unit) -> FilterResult<NaiveDateTime> {
    if unit.is_date_based() {
        return Ok(plus_date(value.date(), amount, unit)?.and_time(value.time()));
    }
    let delta = match unit {
        Unit::Seconds => TimeDelta::try_seconds(amount),
        Unit::Minutes => TimeDelta::try_minutes(amount),
        _ => TimeDelta::try_hours(amount),
    };
    delta
        .and_then(|d| value.checked_add_signed(d))
        .ok_or_else(out_of_range)
}

fn shift(value: TemporalValue, amount: i64, unit: Unit) -> FilterResult<TemporalValue> {
    Ok(match value {
        TemporalValue::Day(date) if unit.is_date_based() => {
            TemporalValue::Day(plus_date(date, amount, unit)?)
        }
        TemporalValue::Day(date) => {
            TemporalValue::Moment(plus_date_time(date.and_time(NaiveTime::MIN), amount, unit)?)
        }
        TemporalValue::Moment(dt) => TemporalValue::Moment(plus_date_time(dt, amount, unit)?),
        TemporalValue::TimeOfDay(time) => {
            // LocalTime.plus wraps around midnight; date-based units don't apply to it.
            let dt = NaiveDate::MIN.and_time(time);
            TemporalValue::TimeOfDay(plus_date_time(dt, amount, unit).map_or(time, |d| d.time()))
        }
    })
}

fn long(text: &str) -> FilterResult<i64> {
    text.parse()
        .map_err(|_| FilterValueError::new(format!("“{text}” is too large")))
}

/// Parses a date, date-time or time: ISO forms as the grid shows them (`2026-10-28`, `2026-10-28 13:45:00.5+07`,
/// `13:45`), or relative to `now`: `today`, `yesterday`, `now`, `today - 7d`, `now-2h`, `-1w`, `3 months ago`. Units:
/// s, min, h, d, w, mo, y (or their names). A time with an offset is converted to `zone`.
pub fn temporal<Tz: TimeZone>(
    text: &str,
    now: NaiveDateTime,
    zone: &Tz,
) -> FilterResult<TemporalValue> {
    let input = kt::trim(text).to_lowercase();
    if input.is_empty() {
        return Err(FilterValueError::new("Enter a date"));
    }
    if let Some(value) = relative(&input, now)? {
        return Ok(value);
    }
    absolute(&input, zone).ok_or_else(|| {
        FilterValueError::new(format!(
            "“{}” isn't a date or time; try 2026-10-28, 13:45 or today-7d",
            kt::trim(text)
        ))
    })
}

fn relative(input: &str, now: NaiveDateTime) -> FilterResult<Option<TemporalValue>> {
    if let Some(m) = AGO.captures(input) {
        let unit = unit(&m[2])?;
        let amount = long(&m[1])?;
        let start = if unit.is_date_based() {
            TemporalValue::Day(now.date())
        } else {
            TemporalValue::Moment(now)
        };
        return shift(start, -amount, unit).map(Some);
    }
    let anchor = ANCHOR.find(input).map(|m| m.as_str());
    let mut rest = kt::trim(&input[anchor.map_or(0, str::len)..]);
    if anchor.is_none() && !rest.starts_with('+') && !rest.starts_with('-') {
        return Ok(None);
    }
    let mut offsets = Vec::new();
    while !rest.is_empty() {
        let Some(m) = OFFSET.captures(rest) else {
            return Ok(None);
        };
        let amount = long(&m[2])?;
        offsets.push((if &m[1] == "-" { -amount } else { amount }, unit(&m[3])?));
        rest = kt::trim(&rest[m.get(0).unwrap().end()..]);
    }
    let today = now.date();
    let mut value = match anchor {
        Some("now") => TemporalValue::Moment(now),
        Some("yesterday") => TemporalValue::Day(today.pred_opt().ok_or_else(out_of_range)?),
        Some("tomorrow") => TemporalValue::Day(today.succ_opt().ok_or_else(out_of_range)?),
        Some("today") => TemporalValue::Day(today),
        // A bare offset counts from now when it's finer than a day, from today otherwise.
        _ if offsets.iter().all(|(_, u)| u.is_date_based()) => TemporalValue::Day(today),
        _ => TemporalValue::Moment(now),
    };
    for (amount, unit) in offsets {
        value = shift(value, amount, unit)?;
    }
    Ok(Some(value))
}

fn digits(s: &str, n: usize) -> Option<u32> {
    (s.len() == n && s.bytes().all(|b| b.is_ascii_digit())).then(|| s.parse().ok())?
}

/// `DateTimeFormatter.ISO_LOCAL_DATE`, for four-digit years.
fn parse_date(s: &str) -> Option<NaiveDate> {
    let mut parts = s.split('-');
    let (y, m, d) = (parts.next()?, parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    NaiveDate::from_ymd_opt(digits(y, 4)? as i32, digits(m, 2)?, digits(d, 2)?)
}

/// `DateTimeFormatter.ISO_LOCAL_TIME`: `HH:mm[:ss[.fraction]]`.
fn parse_time(s: &str) -> Option<NaiveTime> {
    let (hm, rest) = (s.get(..5)?, &s[5..]);
    let (h, m) = hm.split_once(':')?;
    let (h, m) = (digits(h, 2)?, digits(m, 2)?);
    if rest.is_empty() {
        return NaiveTime::from_hms_opt(h, m, 0);
    }
    let rest = rest.strip_prefix(':')?;
    let (sec, frac) = rest
        .split_once('.')
        .map_or((rest, None), |(s, f)| (s, Some(f)));
    let sec = digits(sec, 2)?;
    let nanos = match frac {
        None => 0,
        Some(f) if f.len() <= 9 && f.bytes().all(|b| b.is_ascii_digit()) => {
            format!("{f:0<9}").parse().ok()?
        }
        Some(_) => return None,
    };
    if h > 23 || m > 59 || sec > 59 {
        return None;
    }
    NaiveTime::from_hms_nano_opt(h, m, sec, nanos)
}

/// `ZoneOffset.of`: `Z`, `±h`, `±hh`, `±hh:mm`, `±hhmm`.
fn parse_offset(id: &str) -> Option<FixedOffset> {
    if id == "Z" {
        return FixedOffset::east_opt(0);
    }
    let sign = match id.as_bytes().first()? {
        b'+' => 1,
        b'-' => -1,
        _ => return None,
    };
    let body = &id[1..];
    let (h, m) = match body.len() {
        1 => (digits(body, 1)?, 0),
        2 => (digits(body, 2)?, 0),
        4 => (digits(&body[..2], 2)?, digits(&body[2..], 2)?),
        5 if &body[2..3] == ":" => (digits(&body[..2], 2)?, digits(&body[3..], 2)?),
        _ => return None,
    };
    if h > 18 || m > 59 || h == 18 && m > 0 {
        return None;
    }
    FixedOffset::east_opt(sign * (h as i32 * 3600 + m as i32 * 60))
}

fn absolute<Tz: TimeZone>(input: &str, zone: &Tz) -> Option<TemporalValue> {
    let normalized = input.replace('/', "-").replace('t', " ");
    let normalized = kt::trim(&normalized);
    let offset = TRAILING_OFFSET
        .find(normalized)
        .filter(|_| normalized.chars().count() > 10 || normalized.contains(':'));
    let local = offset.map_or(normalized, |o| kt::trim(&normalized[..o.start()]));
    let zone_offset = offset.and_then(|o| {
        let v = o.as_str().to_uppercase();
        let v = if v.len() == 5 && !v.contains(':') {
            format!("{}:{}", &v[..3], &v[3..])
        } else {
            v
        };
        parse_offset(&v)
    });
    if local.chars().count() <= 10 && local.contains('-') {
        return parse_date(local).map(TemporalValue::Day);
    }
    if local.contains('-') {
        let iso = local.replace(' ', "T");
        let (date, time) = iso.split_once('T')?;
        let date_time = parse_date(date)?.and_time(parse_time(time)?);
        return Some(TemporalValue::Moment(match zone_offset {
            None => date_time,
            Some(offset) => {
                let at: DateTime<FixedOffset> = offset.from_local_datetime(&date_time).single()?;
                at.with_timezone(zone).naive_local()
            }
        }));
    }
    parse_time(local).map(TemporalValue::TimeOfDay)
}

fn fraction(nanos: u32) -> String {
    if nanos == 0 {
        String::new()
    } else {
        format!(".{}", format!("{nanos:09}").trim_end_matches('0'))
    }
}

/// `LocalDate.toString()`.
pub fn format_date(date: NaiveDate) -> String {
    format!("{:04}-{:02}-{:02}", date.year(), date.month(), date.day())
}

/// A date-time as SQL text: with a space rather than ISO's `T`, so it compares correctly with text stored by SQLite,
/// plus fractional seconds when there are any.
pub fn format_date_time(value: NaiveDateTime) -> String {
    format!(
        "{} {}",
        format_date(value.date()),
        format_time(value.time())
    )
}

pub fn format_time(time: NaiveTime) -> String {
    format!(
        "{:02}:{:02}:{:02}{}",
        time.hour(),
        time.minute(),
        time.second(),
        fraction(time.nanosecond())
    )
}

/// A value as SQL text: dates as `yyyy-MM-dd`, date-times as `yyyy-MM-dd HH:mm:ss[.fraction]`, decimals plain.
pub fn format(value: &ParamValue) -> String {
    match value {
        ParamValue::Text(s) | ParamValue::Uuid(s) => s.clone(),
        ParamValue::Long(n) => n.to_string(),
        ParamValue::Decimal(d) => d.to_plain_string(),
        ParamValue::Bool(b) => b.to_string(),
        ParamValue::Date(d) => format_date(*d),
        ParamValue::DateTime(dt) => format_date_time(*dt),
        ParamValue::Time(t) => format_time(*t),
    }
}

//! What each column of a result holds, as a chart sees it (`ColumnProfiler.kt`).

use std::collections::HashSet;

use tg_sql::model::jdbc_types as types;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ColumnKind {
    Numeric,
    /// Dates and timestamps; times of day alone are `Other`.
    Temporal,
    /// Text and booleans, compared for equality only.
    Category,
    /// Binary, and whatever else can't be put on an axis.
    Other,
}

impl ColumnKind {
    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Numeric => "Numeric",
            Self::Temporal => "Temporal",
            Self::Category => "Category",
            Self::Other => "Other",
        }
    }
}

/// A result column as the profiler needs it: its name, type name and JDBC type (what the Kotlin core's
/// `ResultColumn` carries).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChartColumn {
    pub name: String,
    pub type_name: String,
    pub jdbc_type: i32,
}

impl ChartColumn {
    pub fn new(name: &str, type_name: &str, jdbc_type: i32) -> Self {
        Self {
            name: name.to_owned(),
            type_name: type_name.to_owned(),
            jdbc_type,
        }
    }

    pub fn is_numeric(&self) -> bool {
        [
            types::TINYINT,
            types::SMALLINT,
            types::INTEGER,
            types::BIGINT,
            types::REAL,
            types::FLOAT,
            types::DOUBLE,
            types::NUMERIC,
            types::DECIMAL,
        ]
        .contains(&self.jdbc_type)
    }

    pub fn is_binary(&self) -> bool {
        [
            types::BINARY,
            types::VARBINARY,
            types::LONGVARBINARY,
            types::BLOB,
        ]
        .contains(&self.jdbc_type)
    }
}

/// One column of a result as the chart sees it. `distinct` stops counting past [`ColumnProfiler::DISTINCT_CAP`];
/// `min` and `max` are numbers, or epoch milliseconds for dates, and None for other kinds or when no value parses.
#[derive(Clone, Debug, PartialEq)]
pub struct ColumnProfile {
    pub index: usize,
    pub name: String,
    pub type_name: String,
    pub kind: ColumnKind,
    pub distinct: usize,
    pub nulls: usize,
    pub rows: usize,
    pub min: Option<f64>,
    pub max: Option<f64>,
}

impl ColumnProfile {
    /// Every non-null value differs, so there is nothing to group.
    pub fn unique(&self) -> bool {
        self.distinct == self.rows - self.nulls && self.distinct < ColumnProfiler::DISTINCT_CAP
    }
}

/// Reads result values, which drivers hand over as text, as numbers and dates.
pub mod values {
    use chrono::{NaiveDateTime, NaiveTime};
    use tg_sql::filter::values::{parse_date, parse_time};
    use tg_sql::kt;

    /// A hexadecimal float as Java reads it: `0x`, hex digits with an optional point, a mandatory binary exponent.
    fn hex_float(unsigned: &str) -> Option<f64> {
        let rest = unsigned
            .strip_prefix("0x")
            .or_else(|| unsigned.strip_prefix("0X"))?;
        let (mantissa, exponent) = rest.split_once(['p', 'P'])?;
        let (whole, fraction) = mantissa.split_once('.').unwrap_or((mantissa, ""));
        if whole.len() + fraction.len() == 0 {
            return None;
        }
        let mut value = 0.0_f64;
        for c in whole.chars().chain(fraction.chars()) {
            value = value * 16.0 + f64::from(c.to_digit(16)?);
        }
        let digits = exponent.strip_prefix(['+', '-']).unwrap_or(exponent);
        if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let power: i32 = exponent
            .parse::<i32>()
            .unwrap_or(if exponent.starts_with('-') {
                i32::MIN / 2
            } else {
                i32::MAX / 2
            });
        let power = power.saturating_sub(4 * fraction.len() as i32);
        // Two steps, so a large exponent doesn't overflow before the mantissa brings it back in range.
        let half = power / 2;
        Some(value * 2f64.powi(half) * 2f64.powi(power - half))
    }

    /// Java's `Double.parseDouble` grammar: an optional sign, decimal digits with an optional point, an optional
    /// exponent and an optional `d`/`f` suffix; hexadecimal floats; also `Infinity` and `NaN`.
    fn java_double(text: &str) -> Option<f64> {
        let body = text.strip_suffix(['d', 'D', 'f', 'F']).unwrap_or(text);
        let unsigned = body.strip_prefix(['+', '-']).unwrap_or(body);
        if unsigned == "Infinity" || unsigned == "NaN" {
            return body.replace("Infinity", "inf").parse().ok();
        }
        if let Some(value) = hex_float(unsigned) {
            return Some(if body.starts_with('-') { -value } else { value });
        }
        let bytes = unsigned.as_bytes();
        let mut i = 0;
        let mut digits = 0;
        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
            digits += 1;
        }
        if i < bytes.len() && bytes[i] == b'.' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
                digits += 1;
            }
        }
        if digits == 0 {
            return None;
        }
        if i < bytes.len() && (bytes[i] == b'e' || bytes[i] == b'E') {
            i += 1;
            if i < bytes.len() && (bytes[i] == b'+' || bytes[i] == b'-') {
                i += 1;
            }
            let start = i;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            if i == start {
                return None;
            }
        }
        if i != bytes.len() {
            return None;
        }
        body.parse().ok()
    }

    /// A finite number, or None.
    pub fn number(text: Option<&str>) -> Option<f64> {
        java_double(kt::trim(text?)).filter(|n| n.is_finite())
    }

    /// `+HH:MM`, `+HH:MM:SS` or `Z`, as `DateTimeFormatter.ISO_OFFSET_DATE_TIME` reads offsets.
    fn is_offset(text: &str) -> bool {
        if text == "Z" {
            return true;
        }
        let Some(rest) = text.strip_prefix(['+', '-']) else {
            return false;
        };
        let parts: Vec<&str> = rest.split(':').collect();
        (parts.len() == 2 || parts.len() == 3)
            && parts
                .iter()
                .all(|p| p.len() == 2 && p.bytes().all(|b| b.is_ascii_digit()))
            && parts[0].parse::<u32>().is_ok_and(|h| h <= 18)
            && parts[1..]
                .iter()
                .all(|p| p.parse::<u32>().is_ok_and(|v| v <= 59))
    }

    fn local_date_time(iso: &str) -> Option<NaiveDateTime> {
        let (date, time) = iso.split_once('T')?;
        Some(parse_date(date)?.and_time(parse_time(time)?))
    }

    /// A date or timestamp as its wall-clock time, ignoring any offset: a chart shows times as the database wrote
    /// them. Accepts ISO forms with a space or `T`, fractions, and offsets like `Z`, `+07` or `+07:00`.
    pub fn date_time(text: Option<&str>) -> Option<NaiveDateTime> {
        let value = kt::trim(text?);
        let units: Vec<u16> = value.encode_utf16().collect();
        if units.len() < 10 || units[4] != u16::from(b'-') {
            return None;
        }
        if units.len() == 10 {
            return parse_date(value).map(|d| d.and_time(NaiveTime::MIN));
        }
        let iso = value.replacen(' ', "T", 1).replace(' ', "");
        if let Some(dt) = local_date_time(&iso) {
            return Some(dt);
        }
        // PostgreSQL writes offsets as +07, which java.time only reads as +07:00.
        let short_offset = iso.len() >= 3 && {
            let tail = &iso.as_bytes()[iso.len() - 3..];
            (tail[0] == b'+' || tail[0] == b'-')
                && tail[1].is_ascii_digit()
                && tail[2].is_ascii_digit()
        };
        let iso = if short_offset {
            format!("{iso}:00")
        } else {
            iso
        };
        // The offset starts at the last `Z`, `+` or `-` after the time's `T`.
        let t = iso.find('T')?;
        let at = iso[t..].rfind(['Z', '+', '-']).map(|i| i + t)?;
        let (local, offset) = iso.split_at(at);
        if !is_offset(offset) {
            return None;
        }
        local_date_time(local)
    }

    /// A date-time as epoch milliseconds of the same wall-clock time in UTC, which is how the chart lays dates out.
    pub fn epoch_millis_of(time: NaiveDateTime) -> f64 {
        time.and_utc().timestamp_millis() as f64
    }

    /// [`date_time`] as epoch milliseconds.
    pub fn epoch_millis(text: Option<&str>) -> Option<f64> {
        date_time(text).map(epoch_millis_of)
    }
}

/// Works out what each column of a result holds, from its JDBC type first and, for text, from its values.
pub struct ColumnProfiler;

impl ColumnProfiler {
    /// Distinct values are counted up to here: more than this is "many" for every purpose a chart has.
    pub const DISTINCT_CAP: usize = 1000;
    /// Text columns are sampled to tell numbers and dates stored as text.
    const SAMPLE: usize = 200;
    /// Share of sampled values that must parse for text to count as numbers or dates.
    const PARSES: f64 = 0.95;
    /// How often long loops over rows call their checkpoint.
    pub const CHECKPOINT_ROWS: usize = 4096;

    pub fn profile(columns: &[ChartColumn], rows: &[Vec<Option<String>>]) -> Vec<ColumnProfile> {
        Self::profile_with(columns, rows, &mut || {})
    }

    /// `checkpoint` is called every [`Self::CHECKPOINT_ROWS`] rows, so a caller can note a request to stop.
    pub fn profile_with(
        columns: &[ChartColumn],
        rows: &[Vec<Option<String>>],
        checkpoint: &mut dyn FnMut(),
    ) -> Vec<ColumnProfile> {
        columns
            .iter()
            .enumerate()
            .map(|(index, column)| Self::profile_column(index, column, rows, checkpoint))
            .collect()
    }

    fn profile_column(
        index: usize,
        column: &ChartColumn,
        rows: &[Vec<Option<String>>],
        checkpoint: &mut dyn FnMut(),
    ) -> ColumnProfile {
        let kind = Self::kind_of(column).unwrap_or_else(|| {
            let sample: Vec<&str> = rows
                .iter()
                .filter_map(|r| r[index].as_deref())
                .take(Self::SAMPLE)
                .collect();
            Self::guess(&sample)
        });
        let mut distinct: HashSet<&str> = HashSet::new();
        let mut nulls = 0;
        let mut min = f64::INFINITY;
        let mut max = f64::NEG_INFINITY;
        for (i, row) in rows.iter().enumerate() {
            if i % Self::CHECKPOINT_ROWS == 0 {
                checkpoint();
            }
            let Some(value) = row[index].as_deref() else {
                nulls += 1;
                continue;
            };
            if distinct.len() < Self::DISTINCT_CAP {
                distinct.insert(value);
            }
            let number = match kind {
                ColumnKind::Numeric => values::number(Some(value)),
                ColumnKind::Temporal => values::epoch_millis(Some(value)),
                _ => None,
            };
            let Some(number) = number else { continue };
            if number < min {
                min = number;
            }
            if number > max {
                max = number;
            }
        }
        ColumnProfile {
            index,
            name: column.name.clone(),
            type_name: column.type_name.clone(),
            kind,
            distinct: distinct.len(),
            nulls,
            rows: rows.len(),
            min: min.is_finite().then_some(min),
            max: max.is_finite().then_some(max),
        }
    }

    /// The kind the JDBC type settles; None for text, which the values decide.
    fn kind_of(column: &ChartColumn) -> Option<ColumnKind> {
        if column.is_numeric() {
            return Some(ColumnKind::Numeric);
        }
        if column.is_binary() {
            return Some(ColumnKind::Other);
        }
        match column.jdbc_type {
            types::DATE | types::TIMESTAMP | types::TIMESTAMP_WITH_TIMEZONE => {
                Some(ColumnKind::Temporal)
            }
            types::BIT | types::BOOLEAN => Some(ColumnKind::Category),
            types::TIME
            | types::TIME_WITH_TIMEZONE
            | types::ARRAY
            | types::STRUCT
            | types::JAVA_OBJECT
            | types::CLOB
            | types::NCLOB
            | types::SQLXML => Some(ColumnKind::Other),
            _ => None,
        }
    }

    fn guess(sample: &[&str]) -> ColumnKind {
        if sample.is_empty() {
            return ColumnKind::Category;
        }
        let needed = ((sample.len() as f64 * Self::PARSES) as usize).max(1);
        if sample
            .iter()
            .filter(|v| values::number(Some(v)).is_some())
            .count()
            >= needed
        {
            ColumnKind::Numeric
        } else if sample
            .iter()
            .filter(|v| values::date_time(Some(v)).is_some())
            .count()
            >= needed
        {
            ColumnKind::Temporal
        } else {
            ColumnKind::Category
        }
    }
}

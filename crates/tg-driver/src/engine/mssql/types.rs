//! Result columns and cells as mssql-jdbc reported them: `getColumnTypeName`/`getColumnType` and `getString`.
//! TDS sends values typed, so they're written out here: numbers as Java prints them, `bit` as `1`/`0`, temporal types
//! at their declared scale, GUIDs upper-case. TDS metadata doesn't separate `int` from `bigint` in a nullable column
//! (`intn`), so such columns are named by their first value.

use crate::binary_text;
use crate::result::{Cell, ResultColumn};
use chrono::{Duration, NaiveDate};
use tg_sql::model::jdbc_types as t;
use tiberius::time::{Date, DateTime, DateTime2, DateTimeOffset, SmallDateTime, Time};
use tiberius::{Column, ColumnData, ColumnType};

/// `microsoft.sql.Types.DATETIMEOFFSET` and `SQL_VARIANT`.
const DATETIMEOFFSET: i32 = -155;
const SQL_VARIANT: i32 = -150;

/// `(type name, java.sql.Types)` for a column, from its TDS type and, for the variable ones, its first value.
pub fn column_type(column: &Column, first: Option<&ColumnData<'_>>) -> (&'static str, i32) {
    use ColumnType::*;
    match column.column_type() {
        Bit | Bitn => ("bit", t::BIT),
        Int1 => ("tinyint", t::TINYINT),
        Int2 => ("smallint", t::SMALLINT),
        Int4 => ("int", t::INTEGER),
        Int8 => ("bigint", t::BIGINT),
        Intn => match first {
            Some(ColumnData::U8(_)) => ("tinyint", t::TINYINT),
            Some(ColumnData::I16(_)) => ("smallint", t::SMALLINT),
            Some(ColumnData::I64(_)) => ("bigint", t::BIGINT),
            _ => ("int", t::INTEGER),
        },
        Float4 => ("real", t::REAL),
        Float8 => ("float", t::DOUBLE),
        Floatn => match first {
            Some(ColumnData::F32(_)) => ("real", t::REAL),
            _ => ("float", t::DOUBLE),
        },
        Money => ("money", t::DECIMAL),
        Money4 => ("smallmoney", t::DECIMAL),
        Datetime => ("datetime", t::TIMESTAMP),
        Datetime4 => ("smalldatetime", t::TIMESTAMP),
        Datetimen => match first {
            Some(ColumnData::SmallDateTime(_)) => ("smalldatetime", t::TIMESTAMP),
            _ => ("datetime", t::TIMESTAMP),
        },
        Daten => ("date", t::DATE),
        Timen => ("time", t::TIME),
        Datetime2 => ("datetime2", t::TIMESTAMP),
        DatetimeOffsetn => ("datetimeoffset", DATETIMEOFFSET),
        Guid => ("uniqueidentifier", t::CHAR),
        Decimaln => ("decimal", t::DECIMAL),
        Numericn => ("numeric", t::NUMERIC),
        BigVarBin => ("varbinary", t::VARBINARY),
        BigBinary => ("binary", t::BINARY),
        Image => ("image", t::LONGVARBINARY),
        BigVarChar => ("varchar", t::VARCHAR),
        BigChar => ("char", t::CHAR),
        NVarchar => ("nvarchar", t::NVARCHAR),
        NChar => ("nchar", t::NCHAR),
        Text => ("text", t::LONGVARCHAR),
        NText => ("ntext", t::LONGNVARCHAR),
        Xml => ("xml", t::LONGNVARCHAR),
        Udt => ("udt", t::VARBINARY),
        SSVariant => ("sql_variant", SQL_VARIANT),
        Null => ("", t::NULL),
    }
}

pub fn result_columns(columns: &[Column], first: Option<&tiberius::Row>) -> Vec<ResultColumn> {
    columns
        .iter()
        .enumerate()
        .map(|(i, c)| {
            let value = first.and_then(|r| r.cells().nth(i).map(|(_, v)| v));
            let (name, jdbc) = column_type(c, value);
            ResultColumn::new(c.name(), name, jdbc)
        })
        .collect()
}

/// How binary cells come out: previews in grids, placeholders in exports.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BinaryAs {
    Preview,
    Placeholder,
}

/// Java's `Double.toString`: plain between 10⁻³ and 10⁷, else `d.dddE±n`; always a fraction digit.
pub fn java_double(v: f64) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.into();
    }
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0" } else { "0.0" }.into();
    }
    if (1e-3..1e7).contains(&v.abs()) {
        let s = v.to_string();
        return if s.contains('.') { s } else { s + ".0" };
    }
    let s = format!("{v:e}");
    let (mantissa, exponent) = s.split_once('e').unwrap_or((&s, "0"));
    let mantissa = if mantissa.contains('.') {
        mantissa.to_owned()
    } else {
        format!("{mantissa}.0")
    };
    format!("{mantissa}E{exponent}")
}

/// Java's `Float.toString`, from the float's own shortest digits.
pub fn java_float(v: f32) -> String {
    if v.is_nan() || v.is_infinite() || v == 0.0 {
        return java_double(f64::from(v));
    }
    if (1e-3..1e7).contains(&v.abs()) {
        let s = v.to_string();
        return if s.contains('.') { s } else { s + ".0" };
    }
    let s = format!("{v:e}");
    let (mantissa, exponent) = s.split_once('e').unwrap_or((&s, "0"));
    let mantissa = if mantissa.contains('.') {
        mantissa.to_owned()
    } else {
        format!("{mantissa}.0")
    };
    format!("{mantissa}E{exponent}")
}

/// A scaled integer as `BigDecimal.toPlainString`.
fn decimal(value: i128, scale: u8) -> String {
    let digits = value.unsigned_abs().to_string();
    let scale = scale as usize;
    let sign = if value < 0 { "-" } else { "" };
    if scale == 0 {
        return format!("{sign}{digits}");
    }
    let padded = format!("{digits:0>width$}", width = scale + 1);
    let (int, frac) = padded.split_at(padded.len() - scale);
    format!("{sign}{int}.{frac}")
}

fn day(days: i64, epoch: (i32, u32, u32)) -> NaiveDate {
    NaiveDate::from_ymd_opt(epoch.0, epoch.1, epoch.2).expect("epoch") + Duration::days(days)
}

/// `hh:mm:ss` plus exactly `scale` fraction digits.
fn time_of(time: &Time) -> String {
    let scale = time.scale() as u32;
    let units = 10u64.pow(scale);
    let seconds = time.increments() / units;
    let fraction = time.increments() % units;
    let mut s = format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60
    );
    if scale > 0 {
        s += &format!(".{fraction:0width$}", width = scale as usize);
    }
    s
}

fn date_of(date: &Date) -> String {
    day(i64::from(date.days()), (1, 1, 1))
        .format("%Y-%m-%d")
        .to_string()
}

fn datetime2(dt: &DateTime2) -> String {
    format!("{} {}", date_of(&dt.date()), time_of(&dt.time()))
}

/// `java.sql.Timestamp.toString`: trailing zeros of the fraction dropped, at least one digit.
fn timestamp(date: NaiveDate, millis_of_day: i64) -> String {
    let (seconds, millis) = (millis_of_day / 1000, millis_of_day % 1000);
    let fraction = format!("{millis:03}");
    let fraction = fraction.trim_end_matches('0');
    format!(
        "{} {:02}:{:02}:{:02}.{}",
        date.format("%Y-%m-%d"),
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60,
        if fraction.is_empty() { "0" } else { fraction }
    )
}

fn datetime(dt: &DateTime) -> String {
    // 1/300 s ticks, rounded to milliseconds as SQL Server shows them (.003, .007, .010).
    let millis = (i64::from(dt.seconds_fragments()) * 10 + 1) / 3;
    timestamp(day(i64::from(dt.days()), (1900, 1, 1)), millis)
}

fn small_datetime(dt: &SmallDateTime) -> String {
    timestamp(
        day(i64::from(dt.days()), (1900, 1, 1)),
        i64::from(dt.seconds_fragments()) * 60_000,
    )
}

/// `yyyy-mm-dd hh:mm:ss.fffffff +hh:mm`, local to the stored offset.
fn datetime_offset(dto: &DateTimeOffset) -> String {
    let utc = dto.datetime2();
    let time = utc.time();
    let units = 10u64.pow(time.scale() as u32);
    let offset = i64::from(dto.offset());
    let mut seconds = (time.increments() / units) as i64 + offset * 60;
    let mut days = i64::from(utc.date().days());
    days += seconds.div_euclid(86_400);
    seconds = seconds.rem_euclid(86_400);
    let local_time = Time::new(
        seconds as u64 * units + time.increments() % units,
        time.scale(),
    );
    let sign = if offset < 0 { '-' } else { '+' };
    format!(
        "{} {} {sign}{:02}:{:02}",
        day(days, (1, 1, 1)).format("%Y-%m-%d"),
        time_of(&local_time),
        offset.abs() / 60,
        offset.abs() % 60
    )
}

/// One value as `getString` gave it; `money` columns come as floats from TDS and are shown at their scale of 4.
pub fn cell(value: &ColumnData<'_>, column: &ResultColumn, binary: BinaryAs) -> Cell {
    let money = matches!(column.type_name.as_str(), "money" | "smallmoney");
    Some(match value {
        ColumnData::U8(v) => v.as_ref()?.to_string(),
        ColumnData::I16(v) => v.as_ref()?.to_string(),
        ColumnData::I32(v) => v.as_ref()?.to_string(),
        ColumnData::I64(v) => v.as_ref()?.to_string(),
        ColumnData::F32(v) => java_float(*v.as_ref()?),
        ColumnData::F64(v) if money => format!("{:.4}", v.as_ref()?),
        ColumnData::F64(v) => java_double(*v.as_ref()?),
        ColumnData::Bit(v) => if *v.as_ref()? { "1" } else { "0" }.to_owned(),
        ColumnData::String(v) => v.as_ref()?.to_string(),
        ColumnData::Guid(v) => v.as_ref()?.hyphenated().to_string().to_uppercase(),
        ColumnData::Binary(v) => {
            let bytes = v.as_ref()?;
            match binary {
                BinaryAs::Preview => binary_text::encode(bytes),
                BinaryAs::Placeholder => binary_text::placeholder(bytes.len()),
            }
        }
        ColumnData::Numeric(v) => {
            let n = v.as_ref()?;
            decimal(n.value(), n.scale())
        }
        ColumnData::Xml(v) => v.as_ref()?.to_string(),
        ColumnData::DateTime(v) => datetime(v.as_ref()?),
        ColumnData::SmallDateTime(v) => small_datetime(v.as_ref()?),
        ColumnData::Time(v) => time_of(v.as_ref()?),
        ColumnData::Date(v) => date_of(v.as_ref()?),
        ColumnData::DateTime2(v) => datetime2(v.as_ref()?),
        ColumnData::DateTimeOffset(v) => datetime_offset(v.as_ref()?),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_print_as_java_does() {
        assert_eq!(java_double(2.5), "2.5");
        assert_eq!(java_double(100.0), "100.0");
        assert_eq!(java_double(1e10), "1.0E10");
        assert_eq!(java_double(1.5e-5), "1.5E-5");
        assert_eq!(java_double(-0.0), "-0.0");
        assert_eq!(java_float(0.1), "0.1");
        assert_eq!(decimal(12345, 2), "123.45");
        assert_eq!(decimal(-5, 3), "-0.005");
        assert_eq!(decimal(7, 0), "7");
    }

    #[test]
    fn temporals_print_at_their_scale() {
        assert_eq!(time_of(&Time::new(14_706_500, 3)), "04:05:06.500");
        assert_eq!(time_of(&Time::new(14_706, 0)), "04:05:06");
        // 2024-02-03 is day 738_918 since 0001-01-01.
        assert_eq!(date_of(&Date::new(738_918)), "2024-02-03");
        assert_eq!(
            timestamp(day(0, (1900, 1, 1)), 14_706_500),
            "1900-01-01 04:05:06.5"
        );
        assert_eq!(timestamp(day(0, (1900, 1, 1)), 0), "1900-01-01 00:00:00.0");
    }
}

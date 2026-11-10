//! Values in and out, as sqlite-jdbc 3.50 did them: what `getString`, `getColumnType` and `getColumnTypeName` returned,
//! and how `setString`/`setObject` bound parameters.

use crate::binary_text;
use crate::result::{Cell, ResultColumn};
use crate::sql::Param;
use regex::Regex;
use rusqlite::types::{Value, ValueRef};
use std::sync::LazyLock;
use tg_sql::filter::ParamValue;
use tg_sql::model::jdbc_types as types;

/// SQLite's storage classes, as `sqlite3_column_type` numbers them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Storage {
    Integer,
    Float,
    Text,
    Blob,
    Null,
}

impl Storage {
    pub fn of(value: &ValueRef<'_>) -> Self {
        match value {
            ValueRef::Null => Self::Null,
            ValueRef::Integer(_) => Self::Integer,
            ValueRef::Real(_) => Self::Float,
            ValueRef::Text(_) => Self::Text,
            ValueRef::Blob(_) => Self::Blob,
        }
    }
}

static TYPE_CAST: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"cast\(.*?\s+as\s+(.*?)\s*\)").unwrap());

/// `getColumnTypeName`: the declared type before any `(`, upper-cased; for expressions, the type of a lower-case
/// `cast(… as type)` in the column's name, else the first row's storage class.
pub fn column_type_name(decl_type: Option<&str>, column_name: &str, first: Storage) -> String {
    let decl = decl_type
        .map(str::to_owned)
        .or_else(|| TYPE_CAST.captures(column_name).map(|c| c[1].to_owned()));
    match decl {
        Some(d) => d.split('(').next().unwrap_or("").to_uppercase(),
        None => match first {
            Storage::Integer => "INTEGER",
            Storage::Float => "FLOAT",
            Storage::Blob => "BLOB",
            Storage::Text => "TEXT",
            Storage::Null => "NUMERIC",
        }
        .to_owned(),
    }
}

/// `getColumnType`: from the type name and the first row's value (sqlite-jdbc has the first row stepped already).
pub fn column_type(type_name: &str, first: Storage, first_integer: Option<i64>) -> i32 {
    let t = type_name;
    use Storage::*;
    if matches!(first, Integer | Null) {
        match t {
            "BOOLEAN" => return types::BOOLEAN,
            "TINYINT" => return types::TINYINT,
            "SMALLINT" | "INT2" => return types::SMALLINT,
            "BIGINT" | "INT8" | "UNSIGNED BIG INT" => return types::BIGINT,
            "DATE" | "DATETIME" => return types::DATE,
            "TIMESTAMP" => return types::TIMESTAMP,
            _ => {}
        }
        if first == Integer || matches!(t, "INT" | "INTEGER" | "MEDIUMINT") {
            // getLong of a NULL is 0.
            let v = first_integer.unwrap_or(0);
            return if v > i32::MAX as i64 || v < i32::MIN as i64 {
                types::BIGINT
            } else {
                types::INTEGER
            };
        }
    }
    if matches!(first, Float | Null) {
        match t {
            "DECIMAL" => return types::DECIMAL,
            "DOUBLE" | "DOUBLE PRECISION" => return types::DOUBLE,
            "NUMERIC" => return types::NUMERIC,
            "REAL" => return types::REAL,
            _ => {}
        }
        if first == Float || t == "FLOAT" {
            return types::FLOAT;
        }
    }
    if matches!(first, Text | Null) {
        match t {
            "CHARACTER" | "NCHAR" | "NATIVE CHARACTER" | "CHAR" => return types::CHAR,
            "CLOB" => return types::CLOB,
            "DATE" | "DATETIME" => return types::DATE,
            "TIMESTAMP" => return types::TIMESTAMP,
            _ => {}
        }
        if first == Text || matches!(t, "VARCHAR" | "VARYING CHARACTER" | "NVARCHAR" | "TEXT") {
            return types::VARCHAR;
        }
    }
    if matches!(first, Blob | Null) {
        if t == "BINARY" {
            return types::BINARY;
        }
        if first == Blob || t == "BLOB" {
            return types::BLOB;
        }
    }
    types::NUMERIC
}

/// How a binary column's values are shown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BinaryAs {
    /// Grids: a hex preview.
    Preview,
    /// Exports: `<binary N bytes>`.
    Placeholder,
}

/// `getString` (`sqlite3_column_text`), or `getBytes` for binary columns.
pub fn cell(value: ValueRef<'_>, column: &ResultColumn, binary: BinaryAs) -> Cell {
    match value {
        ValueRef::Null => None,
        ValueRef::Integer(i) => Some(i.to_string()),
        ValueRef::Real(r) => Some(real_text(r)),
        ValueRef::Text(t) => Some(String::from_utf8_lossy(t).into_owned()),
        ValueRef::Blob(b) if column.is_binary() => Some(match binary {
            BinaryAs::Preview => binary_text::encode(b),
            BinaryAs::Placeholder => binary_text::placeholder(b.len()),
        }),
        ValueRef::Blob(b) => Some(String::from_utf8_lossy(b).into_owned()),
        // A binary column whose value isn't a blob still reads through getBytes, which SQLite converts.
    }
    .map(|text| match (value, column.is_binary()) {
        (ValueRef::Integer(_) | ValueRef::Real(_) | ValueRef::Text(_), true) => match binary {
            BinaryAs::Preview => binary_text::encode(text.as_bytes()),
            BinaryAs::Placeholder => binary_text::placeholder(text.len()),
        },
        _ => text,
    })
}

/// A REAL as sqlite-jdbc 3.50's `getString` gave it: SQLite 3.50 rendered with `%!.15g`. (Newer SQLite prints up to
/// 17 digits; the grid keeps what the Kotlin app showed.)
pub fn real_text(value: f64) -> String {
    use rusqlite::ffi;
    // SAFETY: sqlite3_mprintf with a %g conversion and one double argument; the result is freed with sqlite3_free.
    unsafe {
        let p = ffi::sqlite3_mprintf(c"%!.15g".as_ptr(), value);
        if p.is_null() {
            return value.to_string();
        }
        let text = std::ffi::CStr::from_ptr(p).to_string_lossy().into_owned();
        ffi::sqlite3_free(p.cast());
        text
    }
}

/// A parameter as sqlite-jdbc bound it.
pub fn bind(param: &Param<'_>) -> Value {
    match param {
        Param::Value(b) => b.value.clone().map_or(Value::Null, Value::Text),
        Param::Text(t) => Value::Text((*t).to_owned()),
        Param::Filter(p) => match &p.value {
            ParamValue::Text(t) | ParamValue::Uuid(t) => Value::Text(t.clone()),
            ParamValue::Long(l) => Value::Integer(*l),
            ParamValue::Decimal(d) => Value::Text(d.to_java_string()),
            ParamValue::Bool(b) => Value::Integer(i64::from(*b)),
            // setObject(LocalDate…) falls back to toString().
            ParamValue::Date(d) => Value::Text(d.format("%Y-%m-%d").to_string()),
            ParamValue::DateTime(dt) => Value::Text(format!(
                "{}T{}",
                dt.date().format("%Y-%m-%d"),
                java_time(dt.time())
            )),
            ParamValue::Time(t) => Value::Text(java_time(*t)),
        },
    }
}

/// `LocalTime.toString()`: `HH:mm`, then `:ss` unless seconds and nanos are zero, then the fraction in groups of
/// three digits.
pub fn java_time(t: chrono::NaiveTime) -> String {
    use chrono::Timelike;
    let mut s = format!("{:02}:{:02}", t.hour(), t.minute());
    let nanos = t.nanosecond() % 1_000_000_000;
    if t.second() > 0 || nanos > 0 {
        s.push_str(&format!(":{:02}", t.second()));
        if nanos > 0 {
            if nanos.is_multiple_of(1_000_000) {
                s.push_str(&format!(".{:03}", nanos / 1_000_000));
            } else if nanos.is_multiple_of(1_000) {
                s.push_str(&format!(".{:06}", nanos / 1_000));
            } else {
                s.push_str(&format!(".{nanos:09}"));
            }
        }
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reals_render_like_sqlite() {
        assert_eq!(real_text(1.0), "1.0");
        assert_eq!(real_text(49.47), "49.47");
        assert_eq!(real_text(0.1 + 0.2), "0.3");
        assert_eq!(real_text(3_000_000_000.0 * 1.1), "3300000000.0");
        assert_eq!(real_text(1e300), "1.0e+300");
    }

    #[test]
    fn type_names_and_types() {
        assert_eq!(
            column_type_name(Some("varchar(100)"), "x", Storage::Null),
            "VARCHAR"
        );
        assert_eq!(
            column_type_name(None, "cast(a as text)", Storage::Integer),
            "TEXT"
        );
        assert_eq!(
            column_type_name(None, "COUNT(*)", Storage::Integer),
            "INTEGER"
        );
        assert_eq!(column_type("INTEGER", Storage::Null, None), types::INTEGER);
        assert_eq!(
            column_type("INTEGER", Storage::Integer, Some(1 << 40)),
            types::BIGINT
        );
        assert_eq!(column_type("VARCHAR", Storage::Null, None), types::VARCHAR);
        assert_eq!(column_type("BLOB", Storage::Null, None), types::BLOB);
        assert_eq!(column_type("NUMERIC", Storage::Text, None), types::VARCHAR);
        assert_eq!(column_type("WHATEVER", Storage::Null, None), types::NUMERIC);
    }

    #[test]
    fn java_times() {
        let t = |h, m, s, n| chrono::NaiveTime::from_hms_nano_opt(h, m, s, n).unwrap();
        assert_eq!(java_time(t(9, 5, 0, 0)), "09:05");
        assert_eq!(java_time(t(9, 5, 7, 0)), "09:05:07");
        assert_eq!(java_time(t(9, 5, 0, 120_000_000)), "09:05:00.120");
        assert_eq!(java_time(t(9, 5, 0, 1_000)), "09:05:00.000001");
    }
}

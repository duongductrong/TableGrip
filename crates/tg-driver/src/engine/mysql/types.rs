//! Result columns and cells as MariaDB Connector/J 3.5 reported them: `getColumnTypeName`/`getColumnType` from the
//! protocol's column definition (its `client.column.*Column` classes, with the default `tinyInt1isBit`,
//! `transformedBitIsBoolean` and `yearIsDateType`), and `getString` from the text protocol, which is the server's own
//! text except for BIT.

use crate::binary_text;
use crate::result::{Cell, ResultColumn};
use mysql_async::consts::{ColumnFlags, ColumnType};
use mysql_async::{Column, Value};
use tg_sql::model::jdbc_types as t;

const BINARY_CHARSET: u16 = 63;

/// Bytes per character of a collation id, for turning a column's byte length into characters.
fn max_bytes(charset: u16) -> u32 {
    match charset {
        BINARY_CHARSET => 1,
        // utf8mb3
        33 | 83 | 192..=215 | 76 => 3,
        // utf8mb4 (MySQL 8 and MariaDB ids)
        45 | 46 | 224..=247 | 255..=323 => 4,
        // ucs2, utf16, utf32
        35 | 90 | 128..=159 | 54 | 55 | 101..=124 | 60 | 61 | 160..=183 => 4,
        _ => 1,
    }
}

/// How a column reads: as text, binary (shown through [`binary_text`]), or BIT.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reading {
    Text,
    Binary,
    Bit {
        boolean: bool,
    },
    /// DATETIME and TIMESTAMP, which `TimestampColumn` re-formats with this many decimals.
    DateTime {
        decimals: u8,
    },
}

/// `(type name, java.sql.Types, reading)` for one column.
pub fn column_type(c: &Column) -> (String, i32, Reading) {
    let unsigned = c.flags().contains(ColumnFlags::UNSIGNED_FLAG);
    let binary = c.character_set() == BINARY_CHARSET;
    let length = c.column_length();
    let named = |base: &str, jdbc: i32| {
        let name = if unsigned {
            format!("{base} UNSIGNED")
        } else {
            base.to_owned()
        };
        (name, jdbc, Reading::Text)
    };
    let text = |name: &str, jdbc: i32| (name.to_owned(), jdbc, Reading::Text);
    use ColumnType::*;
    match c.column_type() {
        MYSQL_TYPE_TINY if length == 1 => text("BOOLEAN", t::BOOLEAN),
        MYSQL_TYPE_TINY => named("TINYINT", t::TINYINT),
        MYSQL_TYPE_SHORT => named("SMALLINT", t::SMALLINT),
        MYSQL_TYPE_INT24 => named("MEDIUMINT", t::INTEGER),
        MYSQL_TYPE_LONG => named("INTEGER", t::INTEGER),
        MYSQL_TYPE_LONGLONG => named("BIGINT", t::BIGINT),
        MYSQL_TYPE_FLOAT => named("FLOAT", t::REAL),
        MYSQL_TYPE_DOUBLE => named("DOUBLE", t::DOUBLE),
        MYSQL_TYPE_DECIMAL | MYSQL_TYPE_NEWDECIMAL => named("DECIMAL", t::DECIMAL),
        MYSQL_TYPE_YEAR => text("YEAR", t::DATE),
        MYSQL_TYPE_DATE | MYSQL_TYPE_NEWDATE => text("DATE", t::DATE),
        MYSQL_TYPE_TIME | MYSQL_TYPE_TIME2 => text("TIME", t::TIME),
        MYSQL_TYPE_TIMESTAMP | MYSQL_TYPE_TIMESTAMP2 => (
            "TIMESTAMP".into(),
            t::TIMESTAMP,
            Reading::DateTime {
                decimals: c.decimals(),
            },
        ),
        MYSQL_TYPE_DATETIME | MYSQL_TYPE_DATETIME2 => (
            "DATETIME".into(),
            t::TIMESTAMP,
            Reading::DateTime {
                decimals: c.decimals(),
            },
        ),
        MYSQL_TYPE_BIT => (
            "BIT".into(),
            if length == 1 { t::BOOLEAN } else { t::BIT },
            Reading::Bit {
                boolean: length == 1,
            },
        ),
        MYSQL_TYPE_JSON => text("JSON", t::LONGVARCHAR),
        MYSQL_TYPE_NULL => text("NULL", t::NULL),
        MYSQL_TYPE_GEOMETRY => ("GEOMETRY".into(), t::VARBINARY, Reading::Binary),
        MYSQL_TYPE_STRING if binary => ("BINARY".into(), t::VARBINARY, Reading::Binary),
        MYSQL_TYPE_STRING => text("CHAR", t::CHAR),
        MYSQL_TYPE_TINY_BLOB | MYSQL_TYPE_BLOB | MYSQL_TYPE_MEDIUM_BLOB | MYSQL_TYPE_LONG_BLOB => {
            let chars = length / max_bytes(c.character_set());
            let long = chars > 16_777_215 || length == 0;
            let small = matches!(c.column_type(), MYSQL_TYPE_TINY_BLOB | MYSQL_TYPE_BLOB);
            let size = |tiny: &str, plain: &str, medium: &str, huge: &str| {
                if chars <= 255 {
                    tiny
                } else if chars <= 65_535 {
                    plain
                } else if chars <= 16_777_215 {
                    medium
                } else {
                    huge
                }
                .to_owned()
            };
            if binary {
                let jdbc = if !long && small {
                    t::VARBINARY
                } else {
                    t::LONGVARBINARY
                };
                (
                    size("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB"),
                    jdbc,
                    Reading::Binary,
                )
            } else {
                let jdbc = if !long && small {
                    t::VARCHAR
                } else {
                    t::LONGVARCHAR
                };
                (
                    size("TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"),
                    jdbc,
                    Reading::Text,
                )
            }
        }
        // VARCHAR, VAR_STRING, ENUM, SET and anything newer.
        _ => {
            if binary {
                let jdbc = if length > 16_777_215 {
                    t::LONGVARBINARY
                } else {
                    t::VARBINARY
                };
                return ("VARBINARY".into(), jdbc, Reading::Binary);
            }
            let chars = length / max_bytes(c.character_set());
            let (name, jdbc) = if chars <= 65_532 {
                ("VARCHAR", t::VARCHAR)
            } else if chars <= 65_535 {
                ("TEXT", t::LONGVARCHAR)
            } else if chars <= 16_777_215 {
                ("MEDIUMTEXT", t::LONGVARCHAR)
            } else {
                ("LONGTEXT", t::LONGVARCHAR)
            };
            text(name, jdbc)
        }
    }
}

pub fn result_columns(columns: &[Column]) -> (Vec<ResultColumn>, Vec<Reading>) {
    columns
        .iter()
        .map(|c| {
            let (name, jdbc, reading) = column_type(c);
            (ResultColumn::new(&c.name_str(), &name, jdbc), reading)
        })
        .unzip()
}

/// How binary cells come out: previews in grids, placeholders in exports.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BinaryAs {
    Preview,
    Placeholder,
}

/// `BitColumn.decodeStringText`: BIT(1) as a boolean, wider ones as `b'101'` without leading zeros.
fn bit(bytes: &[u8], boolean: bool) -> String {
    if boolean {
        return (bytes.iter().any(|b| *b != 0)).to_string();
    }
    let mut s = String::from("b'");
    let mut started = false;
    for byte in bytes {
        for i in (0..8).rev() {
            let one = byte & (1 << i) != 0;
            if one {
                started = true;
                s.push('1');
            } else if started {
                s.push('0');
            }
        }
    }
    s.push('\'');
    s
}

/// One text-protocol value as `getString` gave it.
pub fn cell(value: Option<Value>, reading: Reading, binary: BinaryAs) -> Cell {
    let bytes = match value? {
        Value::NULL => return None,
        Value::Bytes(b) => b,
        // The text protocol only sends bytes; anything else is formatted as its SQL literal.
        other => other.as_sql(true).into_bytes(),
    };
    Some(match reading {
        Reading::Text => String::from_utf8(bytes)
            .unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned()),
        Reading::Binary => match binary {
            BinaryAs::Preview => binary_text::encode(&bytes),
            BinaryAs::Placeholder => binary_text::placeholder(bytes.len()),
        },
        Reading::Bit { boolean } => bit(&bytes, boolean),
        Reading::DateTime { decimals } => date_time(&String::from_utf8_lossy(&bytes), decimals),
    })
}

/// `TimestampColumn.decodeStringText`: the seconds, then (when the column has decimals) the microseconds printed with
/// `%0<decimals>d`, so `.5` in a DATETIME(1) reads `.500000`. The zero date keeps its zeros.
fn date_time(text: &str, decimals: u8) -> String {
    let (base, fraction) = text.split_once('.').unwrap_or((text, ""));
    if base.starts_with("0000-00-00") {
        let mut s = String::from("0000-00-00 00:00:00");
        if decimals > 0 {
            s.push('.');
            s.push_str(&"0".repeat(decimals as usize));
        }
        return s;
    }
    if decimals == 0 {
        return base.to_owned();
    }
    let digits: String = fraction.chars().take(6).collect();
    let micros: u32 = format!("{digits:0<6}").parse().unwrap_or(0);
    format!("{base}.{micros:0width$}", width = decimals as usize)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bits_read_like_connector_j() {
        assert_eq!(bit(&[0b101], false), "b'101'");
        assert_eq!(bit(&[0, 0b1000_0001], false), "b'10000001'");
        assert_eq!(bit(&[0], false), "b''");
        assert_eq!(bit(&[1], true), "true");
        assert_eq!(bit(&[0], true), "false");
    }

    #[test]
    fn date_times_read_like_connector_j() {
        assert_eq!(
            date_time("2024-02-03 04:05:06.5", 1),
            "2024-02-03 04:05:06.500000"
        );
        assert_eq!(
            date_time("2024-02-03 04:05:06.000001", 6),
            "2024-02-03 04:05:06.000001"
        );
        assert_eq!(
            date_time("2024-02-03 04:05:06.012", 3),
            "2024-02-03 04:05:06.12000"
        );
        assert_eq!(date_time("2024-02-03 04:05:06", 0), "2024-02-03 04:05:06");
        assert_eq!(
            date_time("0000-00-00 00:00:00.00", 2),
            "0000-00-00 00:00:00.00"
        );
    }
}

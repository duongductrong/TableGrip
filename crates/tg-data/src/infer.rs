//! Guesses a column type from sample values (`TypeInference.kt`).

use crate::kotlinx_json;
use chrono::NaiveDate;
use tg_sql::kt;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum InferredKind {
    Integer,
    BigInteger,
    Decimal,
    Boolean,
    Date,
    Timestamp,
    Text,
    Json,
}

impl InferredKind {
    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Integer => "Integer",
            Self::BigInteger => "BigInteger",
            Self::Decimal => "Decimal",
            Self::Boolean => "Boolean",
            Self::Date => "Date",
            Self::Timestamp => "Timestamp",
            Self::Text => "Text",
            Self::Json => "Json",
        }
    }
}

/// What a column's values look like, for creating a new table. `max_length` counts code points of the longest value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InferredType {
    pub kind: InferredKind,
    pub max_length: usize,
    pub nullable: bool,
}

pub struct TypeInference;

const BOOLEAN_WORDS: [&str; 6] = ["true", "false", "yes", "no", "t", "f"];

impl TypeInference {
    /// Strict on purpose: a value is only numeric if storing it as a number gives it back unchanged, so `007`, `+84…`
    /// and `1,234` stay text. Null and blank values don't vote but make the column nullable; a column with no values
    /// at all is nullable text.
    pub fn infer<'a>(values: impl IntoIterator<Item = Option<&'a str>>) -> InferredType {
        let mut seen = 0;
        let mut nullable = false;
        let mut max_length = 0;
        let mut integer = true; // fits a Long
        let mut big_integer = true;
        let mut decimal = true;
        let mut boolean = true;
        let mut boolean_word = false; // a true/yes/t… rather than only 0 and 1
        let mut date = true;
        let mut temporal = true; // Date or Timestamp
        let mut json = true;
        for value in values {
            let Some(value) = value else {
                nullable = true;
                continue;
            };
            max_length = max_length.max(value.chars().count());
            if kt::is_blank(value) {
                nullable = true;
                continue;
            }
            seen += 1;
            let is_integer = big_integer && Self::is_integer_text(value);
            if integer && !(is_integer && value.parse::<i64>().is_ok()) {
                integer = false;
            }
            if !is_integer {
                big_integer = false;
            }
            if decimal && !is_integer && !Self::is_decimal_text(value) {
                decimal = false;
            }
            if boolean {
                let lower = value.to_lowercase();
                if BOOLEAN_WORDS.contains(&lower.as_str()) {
                    boolean_word = true;
                } else if lower != "0" && lower != "1" {
                    boolean = false;
                }
            }
            if temporal {
                let is_date = Self::is_date(value);
                if !is_date {
                    date = false;
                }
                if !is_date && !Self::is_timestamp(value) {
                    temporal = false;
                }
            }
            if json {
                json = Self::is_json(value);
            }
        }
        let kind = if seen == 0 {
            InferredKind::Text
        } else if boolean && boolean_word {
            InferredKind::Boolean
        } else if integer {
            InferredKind::Integer
        } else if big_integer {
            InferredKind::BigInteger
        } else if decimal {
            InferredKind::Decimal
        } else if date {
            InferredKind::Date
        } else if temporal {
            InferredKind::Timestamp
        } else if json {
            InferredKind::Json
        } else {
            InferredKind::Text
        };
        InferredType {
            kind,
            max_length,
            nullable,
        }
    }

    /// `-?(0|[1-9][0-9]*)`: no plus sign and no leading zeros, which would be lost when stored as a number.
    pub fn is_integer_text(text: &str) -> bool {
        let start = usize::from(text.starts_with('-'));
        let end = digits_end(text, start);
        end == text.len() && valid_integer_part(text, start, end)
    }

    /// `-?(0|[1-9][0-9]*)?(\.[0-9]+)?([eE][+-]?[0-9]+)?` with at least one mantissa digit.
    pub fn is_decimal_text(text: &str) -> bool {
        let b = text.as_bytes();
        let mut i = usize::from(text.starts_with('-'));
        let int_start = i;
        i = digits_end(text, i);
        if i > int_start && !valid_integer_part(text, int_start, i) {
            return false;
        }
        let mut has_digits = i > int_start;
        if i < b.len() && b[i] == b'.' {
            i += 1;
            let frac_start = i;
            i = digits_end(text, i);
            if i == frac_start {
                return false;
            }
            has_digits = true;
        }
        if !has_digits {
            return false;
        }
        if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
            i += 1;
            if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
                i += 1;
            }
            let exp_start = i;
            i = digits_end(text, i);
            if i == exp_start {
                return false;
            }
        }
        i == b.len()
    }

    /// `\d{4}-\d{2}-\d{2}` naming a day that exists.
    pub fn is_date(text: &str) -> bool {
        let b = text.as_bytes();
        b.len() == 10
            && b[4] == b'-'
            && b[7] == b'-'
            && digits(&b[0..4])
            && digits(&b[5..7])
            && digits(&b[8..10])
            && valid_date(&text[0..4], &text[5..7], &text[8..10])
    }

    /// A date, `T` or a space, `HH:MM`, optional `:SS` with optional 1 to 9 fraction digits, and an optional offset
    /// (`Z`, `+07`, `+0730`, `+07:30`), all of it valid.
    pub fn is_timestamp(text: &str) -> bool {
        let b = text.as_bytes();
        if b.len() < 16
            || b[4] != b'-'
            || b[7] != b'-'
            || !(b[10] == b'T' || b[10] == b' ')
            || b[13] != b':'
            || !digits(&b[0..4])
            || !digits(&b[5..7])
            || !digits(&b[8..10])
            || !digits(&b[11..13])
            || !digits(&b[14..16])
            || !valid_date(&text[0..4], &text[5..7], &text[8..10])
        {
            return false;
        }
        let mut i = 16;
        let mut second = 0;
        if i < b.len() && b[i] == b':' {
            if b.len() < i + 3 || !digits(&b[i + 1..i + 3]) {
                return false;
            }
            second = number(&b[i + 1..i + 3]);
            i += 3;
            if i < b.len() && b[i] == b'.' {
                let start = i + 1;
                let end = start + b[start..].iter().take_while(|c| c.is_ascii_digit()).count();
                if !(1..=9).contains(&(end - start)) {
                    return false;
                }
                i = end;
            }
        }
        let offset = &text[i..];
        if !offset.is_empty() && !valid_offset(offset) {
            return false;
        }
        number(&b[11..13]) < 24 && number(&b[14..16]) < 60 && second < 60
    }

    /// Whether Kotlin's `Json.parseToJsonElement` reads the text, which must be an object or an array.
    pub fn is_json(text: &str) -> bool {
        let trimmed = kt::trim(text);
        let object = trimmed.starts_with('{') && trimmed.ends_with('}');
        let array = trimmed.starts_with('[') && trimmed.ends_with(']');
        (object || array) && kotlinx_json::parses(trimmed)
    }
}

fn digits(bytes: &[u8]) -> bool {
    bytes.iter().all(u8::is_ascii_digit)
}

fn number(bytes: &[u8]) -> u32 {
    bytes.iter().fold(0, |n, d| n * 10 + u32::from(d - b'0'))
}

fn valid_date(y: &str, m: &str, d: &str) -> bool {
    NaiveDate::from_ymd_opt(y.parse().unwrap(), m.parse().unwrap(), d.parse().unwrap()).is_some()
}

/// `Z`, `z`, `[+-]\d{2}` or `[+-]\d{2}:?\d{2}`, with the hour at most 18 and the minute at most 59.
fn valid_offset(offset: &str) -> bool {
    if offset == "Z" || offset == "z" {
        return true;
    }
    let b = offset.as_bytes();
    if b.len() < 3 || !(b[0] == b'+' || b[0] == b'-') || !digits(&b[1..3]) {
        return false;
    }
    let rest = &b[3..];
    let minutes = match rest.len() {
        0 => return number(&b[1..3]) <= 18,
        3 if rest[0] == b':' => &rest[1..],
        2 => rest,
        _ => return false,
    };
    digits(minutes) && number(&b[1..3]) <= 18 && number(minutes) <= 59
}

fn digits_end(text: &str, from: usize) -> usize {
    let b = text.as_bytes();
    let mut i = from;
    while i < b.len() && b[i].is_ascii_digit() {
        i += 1;
    }
    i
}

fn valid_integer_part(text: &str, start: usize, end: usize) -> bool {
    end > start && (text.as_bytes()[start] != b'0' || end - start == 1)
}

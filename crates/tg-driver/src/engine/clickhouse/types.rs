//! ClickHouse results in `TabSeparatedWithNamesAndTypes`: the server's own text for each value, column types as
//! ClickHouse names them, and the `java.sql.Types` ClickHouse JDBC v2 maps them to.

use crate::result::Cell;
use tg_sql::model::jdbc_types as t;

/// The type inside `Nullable(…)` and `LowCardinality(…)`.
pub fn base_type(ty: &str) -> &str {
    let mut ty = ty.trim();
    loop {
        let inner = ["Nullable(", "LowCardinality("]
            .iter()
            .find_map(|w| ty.strip_prefix(w).and_then(|r| r.strip_suffix(')')));
        match inner {
            Some(i) => ty = i.trim(),
            None => return ty,
        }
    }
}

/// `java.sql.Types` for a ClickHouse type, as ClickHouse JDBC v2 reports it.
pub fn jdbc_type(ty: &str) -> i32 {
    let base = base_type(ty);
    let name = base.split('(').next().unwrap_or(base);
    match name {
        "Int8" => t::TINYINT,
        "UInt8" | "Int16" => t::SMALLINT,
        "UInt16" | "Int32" => t::INTEGER,
        "UInt32" | "Int64" => t::BIGINT,
        "UInt64" | "Int128" | "UInt128" | "Int256" | "UInt256" => t::NUMERIC,
        "Float32" => t::REAL,
        "Float64" => t::DOUBLE,
        "Decimal" | "Decimal32" | "Decimal64" | "Decimal128" | "Decimal256" => t::DECIMAL,
        "Bool" | "Boolean" => t::BOOLEAN,
        "String" | "Enum8" | "Enum16" | "Enum" => t::VARCHAR,
        "FixedString" => t::CHAR,
        "Date" | "Date32" => t::DATE,
        "DateTime" | "DateTime64" | "DateTime32" => t::TIMESTAMP,
        "Array" => t::ARRAY,
        "Nothing" => t::NULL,
        _ => t::OTHER,
    }
}

/// The scale of a `Decimal(P, S)` or `DateTime64(S)`.
pub fn scale(ty: &str) -> Option<i32> {
    let base = base_type(ty);
    let args = base.split_once('(')?.1.trim_end_matches(')');
    let parts: Vec<&str> = args.split(',').map(str::trim).collect();
    if base.starts_with("Decimal(") {
        parts.get(1)?.parse().ok()
    } else if base.starts_with("Decimal") || base.starts_with("DateTime64") {
        parts.first()?.parse().ok()
    } else {
        None
    }
}

/// One TSV field: `\N` is NULL; backslash escapes are undone.
pub fn field(raw: &str) -> Cell {
    if raw == "\\N" {
        return None;
    }
    if !raw.contains('\\') {
        return Some(raw.to_owned());
    }
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('b') => out.push('\u{8}'),
            Some('f') => out.push('\u{c}'),
            Some('0') => out.push('\0'),
            Some('a') => out.push('\u{7}'),
            Some('v') => out.push('\u{b}'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    Some(out)
}

/// One TSV line as cells.
pub fn line(text: &str) -> Vec<Cell> {
    text.split('\t').map(field).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fields_unescape() {
        assert_eq!(
            line("1\t\\N\ta\\tb\\\\c"),
            [Some("1".into()), None, Some("a\tb\\c".into())]
        );
        assert_eq!(field("\\\\N"), Some("\\N".into()));
        assert_eq!(field("it\\'s"), Some("it's".into()));
    }

    #[test]
    fn types_map_like_jdbc_v2() {
        assert_eq!(jdbc_type("Nullable(Int32)"), t::INTEGER);
        assert_eq!(jdbc_type("LowCardinality(Nullable(String))"), t::VARCHAR);
        assert_eq!(jdbc_type("DateTime64(3, 'UTC')"), t::TIMESTAMP);
        assert_eq!(jdbc_type("Decimal(10, 2)"), t::DECIMAL);
        assert_eq!(scale("Decimal(10, 2)"), Some(2));
        assert_eq!(scale("Nullable(DateTime64(3))"), Some(3));
        assert_eq!(jdbc_type("Map(String, UInt8)"), t::OTHER);
    }
}

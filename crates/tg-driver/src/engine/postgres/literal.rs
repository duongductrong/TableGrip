//! Decision B1: PostgreSQL statements run over the simple query protocol, whose results are the server's own text,
//! so parameters are inlined as literals. Text goes in untyped (`'…'`), which the server types from context exactly
//! as pgjdbc's `setObject(…, Types.OTHER)` did; typed filter values get the type pgjdbc bound them with.

use crate::error::{DriverError, QueryError, Result};
use crate::sql::Param;
use tg_sql::filter::ParamValue;
use tg_sql::{SqlLexer, SqlSyntax, TokenType};

/// A string literal that means the same whatever `standard_conforming_strings` is: `E'…'` when it holds a
/// backslash. NUL can't be in PostgreSQL text, so it's refused rather than cut.
pub fn quote(value: &str) -> Result<String> {
    if value.contains('\0') {
        return Err(DriverError::Query(QueryError::new(
            "A value contains a NUL character, which PostgreSQL text can't hold.",
        )));
    }
    let doubled = value.replace('\'', "''");
    Ok(if value.contains('\\') {
        format!("E'{}'", doubled.replace('\\', "\\\\"))
    } else {
        format!("'{doubled}'")
    })
}

fn typed(value: &str, ty: &str) -> Result<String> {
    Ok(format!("{}::{ty}", quote(value)?))
}

/// The literal for one parameter.
pub fn literal(param: &Param<'_>) -> Result<String> {
    match param {
        Param::Value(b) => b.value.as_deref().map_or(Ok("NULL".to_owned()), quote),
        Param::Text(t) => quote(t),
        Param::Filter(p) => match &p.value {
            ParamValue::Text(t) => quote(t),
            ParamValue::Uuid(u) => typed(u, "uuid"),
            ParamValue::Long(l) => typed(&l.to_string(), "int8"),
            ParamValue::Decimal(d) => typed(&d.to_java_string(), "numeric"),
            ParamValue::Bool(b) => Ok(if *b { "TRUE" } else { "FALSE" }.to_owned()),
            ParamValue::Date(d) => typed(&d.format("%Y-%m-%d").to_string(), "date"),
            ParamValue::DateTime(dt) => {
                typed(&dt.format("%Y-%m-%d %H:%M:%S%.f").to_string(), "timestamp")
            }
            ParamValue::Time(t) => typed(&t.format("%H:%M:%S%.f").to_string(), "time"),
        },
    }
}

/// `sql` with each `?` placeholder replaced by its parameter's literal. Without parameters the SQL is left alone, so
/// a `?` in a filter typed as SQL (a jsonb operator) stays an operator, as in Kotlin.
pub fn inline(sql: &str, params: &[Param<'_>]) -> Result<String> {
    if params.is_empty() {
        return Ok(sql.to_owned());
    }
    let mut out = String::with_capacity(sql.len() + params.len() * 8);
    let mut next = params.iter();
    let mut last = 0;
    for token in SqlLexer::new(SqlSyntax::POSTGRESQL).tokenize(sql) {
        if token.kind == TokenType::Parameter && token.text(sql) == "?" {
            let param = next.next().ok_or_else(|| {
                DriverError::Query(QueryError::new("More ? placeholders than parameters"))
            })?;
            out.push_str(&sql[last..token.start]);
            out.push_str(&literal(param)?);
            last = token.end;
        }
    }
    out.push_str(&sql[last..]);
    if next.next().is_some() {
        return Err(DriverError::Query(QueryError::new(
            "More parameters than ? placeholders",
        )));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tg_sql::edit::BoundValue;
    use tg_sql::filter::{SqlParam, ValueKind};

    #[test]
    fn quotes_safely() {
        assert_eq!(quote("it's").unwrap(), "'it''s'");
        assert_eq!(quote(r"a\b'c").unwrap(), r"E'a\\b''c'");
        assert!(quote("a\0b").is_err());
    }

    #[test]
    fn inlines_placeholders_but_not_lookalikes() {
        let v = BoundValue::new(Some("x"), 12, false);
        let n = BoundValue::new(None, 4, true);
        let sql = r#"UPDATE "a?b" SET "c" = ? WHERE "d" = ? AND e = '?' -- ?"#;
        assert_eq!(
            inline(sql, &[Param::Value(&v), Param::Value(&n)]).unwrap(),
            r#"UPDATE "a?b" SET "c" = 'x' WHERE "d" = NULL AND e = '?' -- ?"#
        );
        assert_eq!(inline("SELECT j ? 'k'", &[]).unwrap(), "SELECT j ? 'k'");
        let long = SqlParam::new(ParamValue::Long(-5), ValueKind::Number);
        assert_eq!(
            inline("x = ?", &[Param::Filter(&long)]).unwrap(),
            "x = '-5'::int8"
        );
        assert!(inline("x = ? AND y = ?", &[Param::Filter(&long)]).is_err());
    }
}

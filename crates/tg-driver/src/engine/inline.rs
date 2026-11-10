//! Parameters inlined as literals, for engines whose text results come only from unprepared statements (MySQL's
//! text protocol, as Connector/J's client-side prepared statements did) or whose client binds badly (ClickHouse over
//! HTTP). Each engine says how to write one literal; the placeholders are found with its own lexer.

use crate::error::{DriverError, QueryError, Result};
use crate::sql::Param;
use tg_sql::filter::ParamValue;
use tg_sql::{SqlLexer, SqlSyntax, TokenType};

/// How an engine writes values: a string literal, and whether typed values need a cast.
pub trait Literals {
    fn text(&self, value: &str) -> Result<String>;

    /// A typed value as the engine reads it back; by default its text as a string literal, which servers convert.
    fn typed(&self, value: &str, _kind: Typed) -> Result<String> {
        self.text(value)
    }

    fn number(&self, value: &str) -> String {
        value.to_owned()
    }

    fn boolean(&self, value: bool) -> String {
        if value { "TRUE" } else { "FALSE" }.to_owned()
    }
}

/// The kind of a typed filter value, for engines that cast.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Typed {
    Uuid,
    Date,
    DateTime,
    Time,
}

/// The literal for one parameter, as `dialect.bind` would have bound it (`setString`, `setLong`, `setObject`…).
pub fn literal(param: &Param<'_>, l: &dyn Literals) -> Result<String> {
    match param {
        Param::Value(b) if b.is_default => Ok("DEFAULT".into()),
        Param::Value(b) => b
            .value
            .as_deref()
            .map_or(Ok("NULL".to_owned()), |v| l.text(v)),
        Param::Text(t) => l.text(t),
        Param::Filter(p) => match &p.value {
            ParamValue::Text(t) => l.text(t),
            ParamValue::Uuid(u) => l.typed(u, Typed::Uuid),
            ParamValue::Long(n) => Ok(l.number(&n.to_string())),
            ParamValue::Decimal(d) => Ok(l.number(&d.to_java_string())),
            ParamValue::Bool(b) => Ok(l.boolean(*b)),
            ParamValue::Date(d) => l.typed(&d.format("%Y-%m-%d").to_string(), Typed::Date),
            ParamValue::DateTime(dt) => l.typed(
                &dt.format("%Y-%m-%d %H:%M:%S%.f").to_string(),
                Typed::DateTime,
            ),
            ParamValue::Time(t) => l.typed(&t.format("%H:%M:%S%.f").to_string(), Typed::Time),
        },
    }
}

/// `sql` with each `?` placeholder replaced by its parameter's literal. Without parameters the SQL is left alone, so
/// a `?` in a filter typed as SQL stays what it was.
pub fn inline(
    sql: &str,
    syntax: SqlSyntax,
    params: &[Param<'_>],
    l: &dyn Literals,
) -> Result<String> {
    if params.is_empty() {
        return Ok(sql.to_owned());
    }
    let mut out = String::with_capacity(sql.len() + params.len() * 8);
    let mut next = params.iter();
    let mut last = 0;
    for token in SqlLexer::new(syntax).tokenize(sql) {
        if token.kind == TokenType::Parameter && token.text(sql) == "?" {
            let param = next.next().ok_or_else(|| {
                DriverError::Query(QueryError::new("More ? placeholders than parameters"))
            })?;
            out.push_str(&sql[last..token.start]);
            out.push_str(&literal(param, l)?);
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

/// Standard SQL quoting: `'` doubled, nothing else special.
#[cfg(test)]
pub struct Standard;

#[cfg(test)]
impl Literals for Standard {
    fn text(&self, value: &str) -> Result<String> {
        Ok(format!("'{}'", value.replace('\'', "''")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tg_sql::edit::BoundValue;

    #[test]
    fn inlines_placeholders_but_not_lookalikes() {
        let v = BoundValue::new(Some("it's"), 12, false);
        let n = BoundValue::new(None, 4, true);
        let sql = "UPDATE `a?b` SET c = ? WHERE d = ? AND e = '?' -- ?";
        assert_eq!(
            inline(
                sql,
                SqlSyntax::MYSQL,
                &[Param::Value(&v), Param::Value(&n)],
                &Standard
            )
            .unwrap(),
            "UPDATE `a?b` SET c = 'it''s' WHERE d = NULL AND e = '?' -- ?"
        );
        assert!(
            inline(
                "x = ? AND y = ?",
                SqlSyntax::MYSQL,
                &[Param::Value(&v)],
                &Standard
            )
            .is_err()
        );
    }
}

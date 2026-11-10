//! Result column types as pgjdbc's `ResultSetMetaData` reported them, and how binary cells are shown.

use crate::binary_text;
use crate::result::{Cell, ResultColumn};
use tg_sql::model::jdbc_types as t;
use tokio_postgres::Client;
use tokio_postgres::types::{Kind, Type};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BinaryAs {
    Preview,
    Placeholder,
}

/// `java.sql.Types` for a PostgreSQL type name (`TypeInfoCache`'s table).
pub fn jdbc_type(name: &str) -> i32 {
    match name {
        "int2" => t::SMALLINT,
        "int4" => t::INTEGER,
        "oid" | "int8" => t::BIGINT,
        "money" | "float8" => t::DOUBLE,
        "numeric" => t::NUMERIC,
        "float4" => t::REAL,
        "char" | "bpchar" => t::CHAR,
        "varchar" | "text" | "name" => t::VARCHAR,
        "bytea" => t::BINARY,
        "bool" | "bit" => t::BIT,
        "date" => t::DATE,
        "time" | "timetz" => t::TIME,
        "timestamp" | "timestamptz" => t::TIMESTAMP,
        "refcursor" => 2012,
        "xml" => 2009,
        n if n.starts_with('_') => t::ARRAY,
        _ => t::OTHER,
    }
}

fn type_jdbc(ty: &Type) -> i32 {
    match ty.kind() {
        Kind::Array(_) => t::ARRAY,
        Kind::Domain(base) => type_jdbc(base),
        _ => jdbc_type(ty.name()),
    }
}

/// Columns of a prepared statement: names, pgjdbc type names (`serial`/`bigserial`/`smallserial` for columns filled
/// by a sequence or identity) and JDBC types.
pub async fn result_columns(
    client: &Client,
    columns: &[tokio_postgres::Column],
) -> Vec<ResultColumn> {
    let mut out: Vec<ResultColumn> = columns
        .iter()
        .map(|c| ResultColumn::new(c.name(), c.type_().name(), type_jdbc(c.type_())))
        .collect();
    let keyed: Vec<(usize, u32, i16)> = columns
        .iter()
        .enumerate()
        .filter_map(|(i, c)| {
            Some((
                i,
                c.table_oid().filter(|o| *o != 0)?,
                c.column_id().filter(|a| *a > 0)?,
            ))
        })
        .filter(|(i, _, _)| matches!(out[*i].type_name.as_str(), "int2" | "int4" | "int8"))
        .collect();
    if keyed.is_empty() {
        return out;
    }
    let pairs: Vec<String> = keyed.iter().map(|(_, o, a)| format!("({o},{a})")).collect();
    let sql = format!(
        "SELECT a.attrelid::int8, a.attnum::int8 FROM pg_catalog.pg_attribute a \
         LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum \
         WHERE (a.attrelid, a.attnum) IN ({}) \
         AND (a.attidentity <> '' OR pg_catalog.pg_get_expr(d.adbin, d.adrelid) LIKE '%nextval(%')",
        pairs.join(",")
    );
    if let Ok(rows) = client.simple_query(&sql).await {
        for row in rows {
            if let tokio_postgres::SimpleQueryMessage::Row(r) = row {
                let (oid, num) = (
                    r.get(0).and_then(|v| v.parse::<u32>().ok()),
                    r.get(1).and_then(|v| v.parse::<i16>().ok()),
                );
                for (i, o, a) in &keyed {
                    if Some(*o) == oid && Some(*a) == num {
                        let name = match out[*i].type_name.as_str() {
                            "int4" => "serial",
                            "int8" => "bigserial",
                            _ => "smallserial",
                        };
                        out[*i].type_name = name.to_owned();
                    }
                }
            }
        }
    }
    out
}

/// The bytes of a `bytea` value in the server's text form: `\x` hex, or the older escape format.
pub fn bytea(text: &str) -> Vec<u8> {
    if let Some(hex) = text.strip_prefix("\\x") {
        return (0..hex.len() / 2)
            .filter_map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok())
            .collect();
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'\\' && i + 1 < bytes.len() && bytes[i + 1] == b'\\' {
            out.push(b'\\');
            i += 2;
        } else if bytes[i] == b'\\' && i + 3 < bytes.len() {
            out.push(
                u8::from_str_radix(std::str::from_utf8(&bytes[i + 1..i + 4]).unwrap_or("0"), 8)
                    .unwrap_or(0),
            );
            i += 4;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    out
}

/// Binary columns' cells as grids (hex preview) or exports (`<binary N bytes>`) show them.
pub fn render_binary(columns: &[ResultColumn], rows: &mut [Vec<Cell>], how: BinaryAs) {
    let binary: Vec<usize> = columns
        .iter()
        .enumerate()
        .filter(|(_, c)| c.is_binary())
        .map(|(i, _)| i)
        .collect();
    if binary.is_empty() {
        return;
    }
    for row in rows {
        for &i in &binary {
            if let Some(Some(text)) = row.get(i) {
                let bytes = bytea(text);
                row[i] = Some(match how {
                    BinaryAs::Preview => binary_text::encode(&bytes),
                    BinaryAs::Placeholder => binary_text::placeholder(bytes.len()),
                });
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_bytea() {
        assert_eq!(bytea("\\x00ff10"), [0, 255, 16]);
        assert_eq!(bytea("a\\\\b\\001"), [b'a', b'\\', b'b', 1]);
    }
}

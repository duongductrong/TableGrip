//! Replies as redis-cli prints them, and `INFO` sections (`RedisClient.format`, `parseInfo`).

use redis::Value;
use std::collections::HashMap;

/// `name:value` lines of an `INFO` reply; section headers and blank lines are skipped.
pub fn parse_info(text: &str) -> HashMap<String, String> {
    text.lines()
        .map(str::trim)
        .filter(|l| l.contains(':') && !l.starts_with('#'))
        .filter_map(|l| l.split_once(':'))
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect()
}

/// A reply the way redis-cli shows it: status replies bare, strings quoted, `(integer)`, `(nil)`, numbered arrays.
pub fn format_reply(reply: &Value) -> String {
    match reply {
        Value::Nil => "(nil)".into(),
        Value::Int(i) => format!("(integer) {i}"),
        Value::Boolean(b) => format!("(integer) {}", i64::from(*b)),
        Value::Okay => "OK".into(),
        Value::SimpleString(s) => s.clone(),
        Value::BulkString(b) => quote(&String::from_utf8_lossy(b)),
        Value::VerbatimString { text, .. } => quote(text),
        Value::Double(d) => quote(&d.to_string()),
        Value::ServerError(e) => match e.details() {
            Some(d) => format!("(error) {} {d}", e.code()),
            None => format!("(error) {}", e.code()),
        },
        Value::Array(items) | Value::Set(items) => list(items),
        Value::Map(pairs) => {
            let flat: Vec<Value> = pairs
                .iter()
                .flat_map(|(k, v)| [k.clone(), v.clone()])
                .collect();
            list(&flat)
        }
        Value::Attribute { data, .. } => format_reply(data),
        Value::Push { data, .. } => list(data),
        other => format!("{other:?}"),
    }
}

fn list(items: &[Value]) -> String {
    if items.is_empty() {
        return "(empty array)".into();
    }
    let width = items.len().to_string().len();
    items
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let label = format!("{:>width$}) ", i + 1);
            let indent = " ".repeat(label.len());
            label + &format_reply(item).replace('\n', &format!("\n{indent}"))
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn quote(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c as u32 == 0x7f => {
                out.push_str(&format!("\\x{:02x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bulk(s: &str) -> Value {
        Value::BulkString(s.as_bytes().to_vec())
    }

    #[test]
    fn formats_like_redis_cli() {
        assert_eq!(format_reply(&Value::Nil), "(nil)");
        assert_eq!(format_reply(&Value::Int(3)), "(integer) 3");
        assert_eq!(format_reply(&Value::Okay), "OK");
        assert_eq!(format_reply(&Value::SimpleString("PONG".into())), "PONG");
        assert_eq!(format_reply(&bulk("a \"b\"\n\x01")), r#""a \"b\"\n\x01""#);
        assert_eq!(format_reply(&Value::Array(vec![])), "(empty array)");
        let nested = Value::Array(vec![
            bulk("first"),
            Value::Array(vec![bulk("x"), Value::Int(2)]),
        ]);
        assert_eq!(
            format_reply(&nested),
            "1) \"first\"\n2) 1) \"x\"\n   2) (integer) 2"
        );
        let ten = Value::Array((0..10).map(Value::Int).collect());
        assert!(format_reply(&ten).starts_with(" 1) (integer) 0\n"));
        assert!(format_reply(&ten).ends_with("\n10) (integer) 9"));
    }

    #[test]
    fn parses_info() {
        let info = parse_info(
            "# Server\r\nredis_version:7.4.2\r\nredis_mode:standalone\r\n\r\n# Keyspace\r\ndb0:keys=3,expires=0\r\n",
        );
        assert_eq!(info["redis_version"], "7.4.2");
        assert_eq!(info["db0"], "keys=3,expires=0");
        assert_eq!(super::super::describe_server(&info), "Redis 7.4.2");
        let valkey = parse_info("server_name:valkey\nvalkey_version:8.1.0\nredis_mode:cluster");
        assert_eq!(
            super::super::describe_server(&valkey),
            "Valkey 8.1.0 (cluster)"
        );
        let keydb = parse_info("redis_version:6.3.4\nexecutable:/usr/local/bin/keydb-server");
        assert_eq!(super::super::describe_server(&keydb), "KeyDB 6.3.4");
    }
}

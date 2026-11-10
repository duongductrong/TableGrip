//! Extended JSON the way the Java driver reads and writes it (`BsonDocument.parse`, `toJson`): the shell's relaxed
//! syntax in (unquoted keys, single quotes, `ObjectId('…')`, `ISODate(…)`, `NumberLong(…)`…), relaxed Extended JSON
//! out, with 64-bit integers kept as `{"$numberLong": "…"}` so they read back as the same type.

use bson::{Bson, Document};
use serde_json::{Map, Value, json};

/// Why shell text didn't parse; the message the console shows after "Error: ".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError(pub String);

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A document in shell syntax; blank text is `{}`.
pub fn document(text: &str) -> Result<Document, ParseError> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(Document::new());
    }
    match value(text)? {
        Bson::Document(d) => Ok(d),
        _ => Err(ParseError(format!("Expected a document, got {text}"))),
    }
}

/// Any shell value: a document, array, string, number or `ObjectId('…')`.
pub fn value(text: &str) -> Result<Bson, ParseError> {
    let mut p = Parser {
        chars: text.chars().collect(),
        i: 0,
    };
    let v = p.value()?;
    p.space();
    if p.i < p.chars.len() {
        return Err(p.error("Unexpected text after the value"));
    }
    Bson::try_from(v).map_err(|e| ParseError(format!("Invalid Extended JSON: {e}")))
}

struct Parser {
    chars: Vec<char>,
    i: usize,
}

impl Parser {
    fn error(&self, what: &str) -> ParseError {
        let near: String = self.chars[self.i.min(self.chars.len())..]
            .iter()
            .take(20)
            .collect();
        ParseError(format!("{what} at position {}: {near}", self.i))
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.i).copied()
    }

    fn space(&mut self) {
        while self.peek().is_some_and(char::is_whitespace) {
            self.i += 1;
        }
    }

    fn eat(&mut self, c: char) -> bool {
        self.space();
        if self.peek() == Some(c) {
            self.i += 1;
            true
        } else {
            false
        }
    }

    fn expect(&mut self, c: char) -> Result<(), ParseError> {
        if self.eat(c) {
            Ok(())
        } else {
            Err(self.error(&format!("Expected '{c}'")))
        }
    }

    fn word(&mut self) -> String {
        let start = self.i;
        while self
            .peek()
            .is_some_and(|c| c.is_alphanumeric() || c == '_' || c == '$' || c == '.')
        {
            self.i += 1;
        }
        self.chars[start..self.i].iter().collect()
    }

    fn value(&mut self) -> Result<Value, ParseError> {
        self.space();
        match self.peek() {
            None => Err(self.error("Expected a value")),
            Some('{') => self.object(),
            Some('[') => self.array(),
            Some('"' | '\'') => Ok(Value::String(self.string()?)),
            Some('/') => self.regex(),
            Some(c) if c == '-' || c == '+' || c.is_ascii_digit() || c == '.' => self.number(),
            Some(_) => {
                let start = self.i;
                let mut name = self.word();
                if name == "new" {
                    self.space();
                    name = self.word();
                }
                match name.as_str() {
                    "true" => Ok(Value::Bool(true)),
                    "false" => Ok(Value::Bool(false)),
                    "null" => Ok(Value::Null),
                    "undefined" => Ok(json!({"$undefined": true})),
                    "NaN" | "Infinity" => Ok(json!({"$numberDouble": name})),
                    "MinKey" | "MaxKey" => {
                        if self.eat('(') {
                            self.expect(')')?;
                        }
                        Ok(json!({ format!("${}Key", &name[..3].to_lowercase()): 1 }))
                    }
                    "" => {
                        self.i = start;
                        Err(self.error("Expected a value"))
                    }
                    _ => self.constructor(&name),
                }
            }
        }
    }

    fn arguments(&mut self) -> Result<Vec<Value>, ParseError> {
        self.expect('(')?;
        let mut out = Vec::new();
        if self.eat(')') {
            return Ok(out);
        }
        loop {
            out.push(self.value()?);
            if self.eat(')') {
                return Ok(out);
            }
            self.expect(',')?;
        }
    }

    fn constructor(&mut self, name: &str) -> Result<Value, ParseError> {
        let args = self.arguments()?;
        let text = |i: usize| -> Result<String, ParseError> {
            match args.get(i) {
                Some(Value::String(s)) => Ok(s.clone()),
                Some(Value::Number(n)) => Ok(n.to_string()),
                _ => Err(ParseError(format!("{name}() expects a value"))),
            }
        };
        Ok(match name {
            "ObjectId" => json!({"$oid": text(0)?}),
            "NumberLong" => json!({"$numberLong": text(0)?}),
            "NumberInt" => json!({"$numberInt": text(0)?}),
            "NumberDecimal" => json!({"$numberDecimal": text(0)?}),
            "ISODate" | "Date" => match args.first() {
                None => {
                    json!({"$date": {"$numberLong": chrono::Utc::now().timestamp_millis().to_string()}})
                }
                Some(Value::Number(n)) => json!({"$date": {"$numberLong": n.to_string()}}),
                Some(Value::String(s)) => {
                    json!({"$date": {"$numberLong": date_millis(s)?.to_string()}})
                }
                _ => return Err(ParseError(format!("{name}() expects a date"))),
            },
            "Timestamp" => {
                json!({"$timestamp": {"t": args.first().cloned().unwrap_or(json!(0)), "i": args.get(1).cloned().unwrap_or(json!(0))}})
            }
            "BinData" => {
                let subtype: u8 = text(0)?
                    .parse()
                    .map_err(|_| ParseError("BinData() expects a subtype".into()))?;
                json!({"$binary": {"base64": text(1)?, "subType": format!("{subtype:02x}")}})
            }
            "UUID" => {
                let hex: String = text(0)?.chars().filter(|c| *c != '-').collect();
                let bytes = (0..hex.len())
                    .step_by(2)
                    .map(|i| u8::from_str_radix(hex.get(i..i + 2).unwrap_or("x"), 16))
                    .collect::<Result<Vec<u8>, _>>()
                    .ok()
                    .filter(|b| b.len() == 16)
                    .ok_or_else(|| ParseError("UUID() expects 32 hex digits".into()))?;
                json!({"$binary": {"base64": base64(&bytes), "subType": "04"}})
            }
            "RegExp" => {
                json!({"$regularExpression": {"pattern": text(0)?, "options": text(1).unwrap_or_default()}})
            }
            _ => return Err(ParseError(format!("Unknown value: {name}"))),
        })
    }

    fn object(&mut self) -> Result<Value, ParseError> {
        self.expect('{')?;
        let mut map = Map::new();
        if self.eat('}') {
            return Ok(Value::Object(map));
        }
        loop {
            self.space();
            let key = match self.peek() {
                Some('"' | '\'') => self.string()?,
                _ => {
                    let w = self.word();
                    if w.is_empty() {
                        return Err(self.error("Expected a field name"));
                    }
                    w
                }
            };
            self.expect(':')?;
            let v = self.value()?;
            map.insert(key, v);
            if self.eat('}') {
                return Ok(Value::Object(map));
            }
            self.expect(',')?;
            if self.eat('}') {
                return Ok(Value::Object(map));
            }
        }
    }

    fn array(&mut self) -> Result<Value, ParseError> {
        self.expect('[')?;
        let mut items = Vec::new();
        if self.eat(']') {
            return Ok(Value::Array(items));
        }
        loop {
            items.push(self.value()?);
            if self.eat(']') {
                return Ok(Value::Array(items));
            }
            self.expect(',')?;
            if self.eat(']') {
                return Ok(Value::Array(items));
            }
        }
    }

    fn string(&mut self) -> Result<String, ParseError> {
        let quote = self.peek().unwrap_or('"');
        self.i += 1;
        let mut out = String::new();
        while let Some(c) = self.peek() {
            self.i += 1;
            match c {
                c if c == quote => return Ok(out),
                '\\' => {
                    let e = self
                        .peek()
                        .ok_or_else(|| self.error("Unterminated string"))?;
                    self.i += 1;
                    match e {
                        'n' => out.push('\n'),
                        't' => out.push('\t'),
                        'r' => out.push('\r'),
                        'b' => out.push('\u{8}'),
                        'f' => out.push('\u{c}'),
                        'u' => {
                            let hex: String = self
                                .chars
                                .get(self.i..self.i + 4)
                                .unwrap_or(&[])
                                .iter()
                                .collect();
                            let code = u32::from_str_radix(&hex, 16)
                                .map_err(|_| self.error("Bad \\u escape"))?;
                            self.i += 4;
                            out.push(char::from_u32(code).unwrap_or('\u{FFFD}'));
                        }
                        other => out.push(other),
                    }
                }
                c => out.push(c),
            }
        }
        Err(self.error("Unterminated string"))
    }

    fn regex(&mut self) -> Result<Value, ParseError> {
        self.i += 1;
        let mut pattern = String::new();
        loop {
            let c = self
                .peek()
                .ok_or_else(|| self.error("Unterminated regular expression"))?;
            self.i += 1;
            match c {
                '/' => break,
                '\\' => {
                    pattern.push('\\');
                    if let Some(n) = self.peek() {
                        pattern.push(n);
                        self.i += 1;
                    }
                }
                c => pattern.push(c),
            }
        }
        let options = self.word();
        Ok(json!({"$regularExpression": {"pattern": pattern, "options": options}}))
    }

    fn number(&mut self) -> Result<Value, ParseError> {
        let start = self.i;
        if matches!(self.peek(), Some('-' | '+')) {
            self.i += 1;
        }
        if self.chars[self.i..].starts_with(&['I', 'n', 'f', 'i', 'n', 'i', 't', 'y']) {
            self.i += 8;
            let negative = self.chars[start] == '-';
            return Ok(json!({"$numberDouble": if negative { "-Infinity" } else { "Infinity" }}));
        }
        while self
            .peek()
            .is_some_and(|c| c.is_ascii_digit() || matches!(c, '.' | 'e' | 'E' | '-' | '+'))
        {
            self.i += 1;
        }
        let text: String = self.chars[start..self.i].iter().collect();
        let text = text.strip_prefix('+').unwrap_or(&text);
        let integral = !text.contains(['.', 'e', 'E']);
        if integral && let Ok(i) = text.parse::<i64>() {
            return Ok(Value::from(i));
        }
        let f: f64 = text
            .parse()
            .map_err(|_| ParseError(format!("Bad number: {text}")))?;
        Ok(serde_json::Number::from_f64(f)
            .map(Value::Number)
            .unwrap_or_else(|| json!({"$numberDouble": f.to_string()})))
    }
}

/// `ISODate('…')`'s argument: a full timestamp, or a date or date-time without a zone (UTC), as the shell takes it.
fn date_millis(text: &str) -> Result<i64, ParseError> {
    use chrono::{DateTime, NaiveDate, NaiveDateTime};
    if let Ok(d) = DateTime::parse_from_rfc3339(text) {
        return Ok(d.timestamp_millis());
    }
    for format in [
        "%Y-%m-%dT%H:%M:%S%.f",
        "%Y-%m-%d %H:%M:%S%.f",
        "%Y-%m-%dT%H:%M",
    ] {
        if let Ok(d) = NaiveDateTime::parse_from_str(text, format) {
            return Ok(d.and_utc().timestamp_millis());
        }
    }
    if let Ok(d) = NaiveDate::parse_from_str(text, "%Y-%m-%d") {
        return Ok(d
            .and_hms_opt(0, 0, 0)
            .unwrap_or_default()
            .and_utc()
            .timestamp_millis());
    }
    Err(ParseError(format!("Invalid date: {text}")))
}

fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        for (i, shift) in [18, 12, 6, 0].into_iter().enumerate() {
            if i <= chunk.len() {
                out.push(ALPHABET[(n >> shift) as usize & 63] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// The Java driver's JSON writer: relaxed mode with `$numberLong` kept, compact or indented by two spaces.
pub struct Writer {
    indent: bool,
}

/// One line of relaxed Extended JSON (`MongoClient.Json`).
pub fn to_json(document: &Document) -> String {
    let mut out = String::new();
    Writer { indent: false }.document(&mut out, document, "");
    out
}

/// Indented relaxed Extended JSON, as the console prints documents (`MongoClient.PrettyJson`). 64-bit integers are
/// plain numbers here, like the shell shows them.
pub fn to_pretty_json(document: &Document) -> String {
    let mut out = String::new();
    Writer { indent: true }.document(&mut out, document, "");
    out
}

/// An `_id` (or any value) as one line, the form the browser passes back to replace or delete it.
pub fn value_json(value: &Bson) -> String {
    let mut out = String::new();
    Writer { indent: false }.value(&mut out, value, "");
    out
}

/// A value as the console prints it (`Shell.prettyValue`).
pub fn pretty_value(value: &Bson) -> String {
    let mut out = String::new();
    Writer { indent: true }.value(&mut out, value, "  ");
    out
}

impl Writer {
    fn document(&self, out: &mut String, d: &Document, indentation: &str) {
        out.push('{');
        let inner = format!("{indentation}  ");
        for (n, (k, v)) in d.iter().enumerate() {
            if n > 0 {
                out.push(',');
            }
            if self.indent {
                out.push('\n');
                out.push_str(&inner);
            } else if n > 0 {
                out.push(' ');
            }
            string(out, k);
            out.push_str(": ");
            self.value(out, v, &inner);
        }
        if self.indent && !d.is_empty() {
            out.push('\n');
            out.push_str(indentation);
        }
        out.push('}');
    }

    fn wrapper(&self, out: &mut String, key: &str, inner: impl FnOnce(&mut String)) {
        out.push('{');
        string(out, key);
        out.push_str(": ");
        inner(out);
        out.push('}');
    }

    /// `indentation` is the indentation of the context holding the value.
    fn value(&self, out: &mut String, v: &Bson, indentation: &str) {
        match v {
            Bson::Document(d) => self.document(out, d, indentation),
            Bson::Array(items) => {
                out.push('[');
                let inner = format!("{indentation}  ");
                for (n, item) in items.iter().enumerate() {
                    if n > 0 {
                        out.push_str(", ");
                    }
                    self.value(out, item, &inner);
                }
                out.push(']');
            }
            Bson::String(s) => string(out, s),
            Bson::Int32(i) => out.push_str(&i.to_string()),
            Bson::Int64(i) if self.indent => out.push_str(&i.to_string()),
            Bson::Int64(i) => self.wrapper(out, "$numberLong", |o| string(o, &i.to_string())),
            Bson::Double(f) if f.is_finite() => out.push_str(&crate::engine::java::java_double(*f)),
            Bson::Double(f) => {
                let text = if f.is_nan() {
                    "NaN"
                } else if *f > 0.0 {
                    "Infinity"
                } else {
                    "-Infinity"
                };
                self.wrapper(out, "$numberDouble", |o| string(o, text))
            }
            Bson::Decimal128(d) => {
                self.wrapper(out, "$numberDecimal", |o| string(o, &d.to_string()))
            }
            Bson::Boolean(b) => out.push_str(if *b { "true" } else { "false" }),
            Bson::Null => out.push_str("null"),
            Bson::Undefined => self.wrapper(out, "$undefined", |o| o.push_str("true")),
            Bson::ObjectId(id) => self.wrapper(out, "$oid", |o| string(o, &id.to_hex())),
            Bson::DateTime(d) => {
                let ms = d.timestamp_millis();
                if (0..=253_402_300_799_999).contains(&ms) {
                    let iso = chrono::DateTime::from_timestamp_millis(ms)
                        .map(|t| t.to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true))
                        .unwrap_or_default();
                    self.wrapper(out, "$date", |o| string(o, &iso))
                } else {
                    self.wrapper(out, "$date", |o| {
                        self.wrapper(o, "$numberLong", |o| string(o, &ms.to_string()))
                    })
                }
            }
            Bson::Binary(b) => self.wrapper(out, "$binary", |o| {
                o.push('{');
                string(o, "base64");
                o.push_str(": ");
                string(o, &base64(&b.bytes));
                o.push_str(", ");
                string(o, "subType");
                o.push_str(": ");
                string(o, &format!("{:02x}", u8::from(b.subtype)));
                o.push('}');
            }),
            Bson::RegularExpression(r) => self.wrapper(out, "$regularExpression", |o| {
                o.push('{');
                string(o, "pattern");
                o.push_str(": ");
                string(o, r.pattern.as_str());
                o.push_str(", ");
                string(o, "options");
                o.push_str(": ");
                string(o, r.options.as_str());
                o.push('}');
            }),
            Bson::Timestamp(t) => self.wrapper(out, "$timestamp", |o| {
                o.push_str(&format!("{{\"t\": {}, \"i\": {}}}", t.time, t.increment));
            }),
            Bson::MinKey => self.wrapper(out, "$minKey", |o| o.push('1')),
            Bson::MaxKey => self.wrapper(out, "$maxKey", |o| o.push('1')),
            Bson::Symbol(s) => self.wrapper(out, "$symbol", |o| string(o, s)),
            Bson::JavaScriptCode(c) => self.wrapper(out, "$code", |o| string(o, c)),
            other => out.push_str(&other.clone().into_relaxed_extjson().to_string()),
        }
    }
}

/// A JSON string as the Java writer escapes it: quotes, backslashes, the short escapes, and `\uXXXX` for control,
/// format and separator characters.
fn string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20
                || (0x7f..=0x9f).contains(&(c as u32))
                || matches!(
                    c,
                    '\u{200b}'..='\u{200f}' | '\u{2028}' | '\u{2029}' | '\u{feff}'
                ) =>
            {
                out.push_str(&format!("\\u{:04x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_shell_syntax() {
        let d = document("{_id: 1, 'a b': \"x\", n: NumberLong(5), big: 3000000000, d: 1.0, t: [true, null], o: ObjectId('5f1d7f1d7f1d7f1d7f1d7f1d'), at: ISODate('2026-10-01T10:00:00Z'), r: /a.c/i, m: {$gt: -2},}").unwrap();
        assert_eq!(
            to_json(&d),
            r#"{"_id": 1, "a b": "x", "n": {"$numberLong": "5"}, "big": {"$numberLong": "3000000000"}, "d": 1.0, "t": [true, null], "o": {"$oid": "5f1d7f1d7f1d7f1d7f1d7f1d"}, "at": {"$date": "2026-10-01T10:00:00Z"}, "r": {"$regularExpression": {"pattern": "a.c", "options": "i"}}, "m": {"$gt": -2}}"#
        );
        // What the browser writes reads back as the same document.
        assert_eq!(document(&to_json(&d)).unwrap(), d);
        assert_eq!(document("").unwrap(), Document::new());
        assert!(document("{a: }").is_err());
        assert!(document("[1]").is_err());
        assert_eq!(value("'x'").unwrap(), Bson::String("x".into()));
        assert_eq!(value("{\"$numberLong\": \"7\"}").unwrap(), Bson::Int64(7));
        assert_eq!(
            value_json(&value("ISODate('2026-10-01T10:00:00.5Z')").unwrap()),
            r#"{"$date": "2026-10-01T10:00:00.500Z"}"#
        );
        assert_eq!(
            value_json(&value("UUID('0123456789abcdef0123456789abcdef')").unwrap()),
            r#"{"$binary": {"base64": "ASNFZ4mrze8BI0VniavN7w==", "subType": "04"}}"#
        );
    }

    #[test]
    fn indents_like_the_java_writer() {
        let d = document("{a: 1, b: {c: 'x'}, e: {}, l: [1, {k: 2}], n: NumberLong(3)}").unwrap();
        assert_eq!(
            to_pretty_json(&d),
            "{\n  \"a\": 1,\n  \"b\": {\n    \"c\": \"x\"\n  },\n  \"e\": {},\n  \"l\": [1, {\n      \"k\": 2\n    }],\n  \"n\": 3\n}"
        );
        assert_eq!(pretty_value(&value("[25, 36]").unwrap()), "[25, 36]");
        let mut s = String::new();
        string(&mut s, "a\"\\\n\u{1}é");
        assert_eq!(s, "\"a\\\"\\\\\\n\\u0001é\"");
    }
}

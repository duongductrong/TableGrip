//! A JSON file of records (`importer/JsonSource.kt`): a top-level array (streamed element by element), NDJSON
//! (streamed line by line), or one object wrapping the array (`{"data": [...]}`, read into memory up to
//! [`MAX_DOCUMENT_BYTES`]). Columns are the union of the records' keys in first-seen order, so opening reads a
//! streamed file twice. Scalars become their text as written (strings unquoted, `3.50` stays `3.50`), nested values
//! compact JSON; an element that isn't an object becomes a `value` column.

use super::charset::{self, CharReader, Charset};
use super::{ImportError, ImportOptions, ImportRow, ImportSource, Result, Rows};
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};

/// A wrapped document is parsed into a tree several times its size, so this keeps it within a desktop's memory.
pub const MAX_DOCUMENT_BYTES: u64 = 256 * 1024 * 1024;
const MAX_LINE_CHARS: usize = 16 * 1024 * 1024;
pub const VALUE_COLUMN: &str = "value";

/// A JSON value with numbers kept as written, like kotlinx's `JsonElement`.
#[derive(Clone, Debug, PartialEq)]
pub enum JsonValue {
    Null,
    Bool(bool),
    Number(String),
    String(String),
    Array(Vec<JsonValue>),
    /// Keys in order; a repeated key keeps its first place and its last value.
    Object(Vec<(String, JsonValue)>),
}

impl JsonValue {
    pub fn get(&self, key: &str) -> Option<&JsonValue> {
        match self {
            Self::Object(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    /// Text as an import cell: None for null, strings unquoted, the rest compact JSON.
    pub fn cell(&self) -> Option<String> {
        match self {
            Self::Null => None,
            Self::String(s) => Some(s.clone()),
            Self::Number(n) => Some(n.clone()),
            Self::Bool(b) => Some(b.to_string()),
            other => Some(other.to_string()),
        }
    }

    /// Parses one complete value.
    pub fn parse(text: &str) -> std::result::Result<Self, String> {
        let mut p = Parser::new(CharReader::new(
            Box::new(std::io::Cursor::new(text.as_bytes().to_vec())),
            charset::UTF_8,
            false,
        ));
        let v = p.value()?;
        p.space()?;
        match p.peek()? {
            None => Ok(v),
            Some(c) => Err(format!("Unexpected '{c}' after the value")),
        }
    }
}

/// Compact JSON as kotlinx prints it.
impl std::fmt::Display for JsonValue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Null => f.write_str("null"),
            Self::Bool(b) => write!(f, "{b}"),
            Self::Number(n) => f.write_str(n),
            Self::String(s) => f.write_str(&quote(s)),
            Self::Array(items) => {
                f.write_str("[")?;
                for (i, item) in items.iter().enumerate() {
                    if i > 0 {
                        f.write_str(",")?;
                    }
                    write!(f, "{item}")?;
                }
                f.write_str("]")
            }
            Self::Object(entries) => {
                f.write_str("{")?;
                for (i, (k, v)) in entries.iter().enumerate() {
                    if i > 0 {
                        f.write_str(",")?;
                    }
                    write!(f, "{}:{v}", quote(k))?;
                }
                f.write_str("}")
            }
        }
    }
}

/// A JSON string literal, escaped as kotlinx's `printQuoted` does.
pub fn quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// A streaming JSON reader over characters.
pub struct Parser {
    reader: CharReader,
    peeked: Option<Option<char>>,
}

impl Parser {
    pub fn new(reader: CharReader) -> Self {
        Self {
            reader,
            peeked: None,
        }
    }

    pub fn peek(&mut self) -> std::result::Result<Option<char>, String> {
        if self.peeked.is_none() {
            self.peeked = Some(self.reader.next_char()?);
        }
        Ok(self.peeked.flatten())
    }

    fn bump(&mut self) -> std::result::Result<Option<char>, String> {
        let c = self.peek()?;
        self.peeked = None;
        Ok(c)
    }

    pub fn space(&mut self) -> std::result::Result<(), String> {
        while self.peek()?.is_some_and(char::is_whitespace) {
            self.bump()?;
        }
        Ok(())
    }

    fn expect(&mut self, wanted: char) -> std::result::Result<(), String> {
        self.space()?;
        match self.bump()? {
            Some(c) if c == wanted => Ok(()),
            Some(c) => Err(format!("Expected '{wanted}' but found '{c}'")),
            None => Err(format!("Expected '{wanted}' but the input ended")),
        }
    }

    pub fn value(&mut self) -> std::result::Result<JsonValue, String> {
        self.space()?;
        match self.peek()? {
            None => Err("Expected a value but the input ended".into()),
            Some('{') => {
                self.bump()?;
                let mut entries: Vec<(String, JsonValue)> = Vec::new();
                self.space()?;
                if self.peek()? == Some('}') {
                    self.bump()?;
                    return Ok(JsonValue::Object(entries));
                }
                loop {
                    self.space()?;
                    if self.peek()? != Some('"') {
                        return Err(match self.peek()? {
                            Some('}') => "Trailing comma before the end of an object".into(),
                            Some(c) => format!("Expected a quoted key but found '{c}'"),
                            None => "Expected a key but the input ended".into(),
                        });
                    }
                    self.bump()?;
                    let key = self.string()?;
                    self.expect(':')?;
                    let v = self.value()?;
                    match entries.iter_mut().find(|(k, _)| *k == key) {
                        Some(e) => e.1 = v,
                        None => entries.push((key, v)),
                    }
                    self.space()?;
                    match self.bump()? {
                        Some(',') => continue,
                        Some('}') => return Ok(JsonValue::Object(entries)),
                        Some(c) => return Err(format!("Expected ',' or '}}' but found '{c}'")),
                        None => return Err("Unterminated object".into()),
                    }
                }
            }
            Some('[') => {
                self.bump()?;
                let mut items = Vec::new();
                self.space()?;
                if self.peek()? == Some(']') {
                    self.bump()?;
                    return Ok(JsonValue::Array(items));
                }
                loop {
                    items.push(self.value()?);
                    self.space()?;
                    match self.bump()? {
                        Some(',') => continue,
                        Some(']') => return Ok(JsonValue::Array(items)),
                        Some(c) => return Err(format!("Expected ',' or ']' but found '{c}'")),
                        None => return Err("Unterminated array".into()),
                    }
                }
            }
            Some('"') => {
                self.bump()?;
                Ok(JsonValue::String(self.string()?))
            }
            Some(_) => {
                let mut word = String::new();
                while let Some(c) = self.peek()? {
                    if c.is_whitespace() || matches!(c, ',' | ':' | ']' | '}' | '[' | '{' | '"') {
                        break;
                    }
                    word.push(c);
                    self.bump()?;
                }
                match word.as_str() {
                    "true" => Ok(JsonValue::Bool(true)),
                    "false" => Ok(JsonValue::Bool(false)),
                    "null" => Ok(JsonValue::Null),
                    w if is_number(w) => Ok(JsonValue::Number(w.to_owned())),
                    "" => Err(format!("Unexpected '{}'", self.peek()?.unwrap_or(' '))),
                    w => Err(format!("Unexpected literal '{w}'")),
                }
            }
        }
    }

    /// The rest of a string after its opening quote.
    fn string(&mut self) -> std::result::Result<String, String> {
        let mut out = String::new();
        loop {
            match self.bump()? {
                None => return Err("Unterminated string".into()),
                Some('"') => return Ok(out),
                Some('\\') => match self.bump()? {
                    Some('n') => out.push('\n'),
                    Some('t') => out.push('\t'),
                    Some('r') => out.push('\r'),
                    Some('b') => out.push('\u{8}'),
                    Some('f') => out.push('\u{c}'),
                    Some('u') => {
                        let mut hex = String::new();
                        for _ in 0..4 {
                            hex.extend(self.bump()?);
                        }
                        let unit = u16::from_str_radix(&hex, 16)
                            .map_err(|_| format!("Bad escape \\u{hex}"))?;
                        out.push_str(&String::from_utf16_lossy(&[unit]));
                        // A surrogate pair is two escapes.
                        if (0xD800..0xDC00).contains(&unit) {
                            out.pop();
                            if self.bump()? == Some('\\') && self.bump()? == Some('u') {
                                let mut low = String::new();
                                for _ in 0..4 {
                                    low.extend(self.bump()?);
                                }
                                let low = u16::from_str_radix(&low, 16).unwrap_or(0xFFFD);
                                out.push_str(&String::from_utf16_lossy(&[unit, low]));
                            } else {
                                out.push('\u{FFFD}');
                            }
                        }
                    }
                    Some(c) => out.push(c),
                    None => return Err("Unterminated string".into()),
                },
                Some(c) => out.push(c),
            }
        }
    }
}

fn is_number(w: &str) -> bool {
    let body = w.strip_prefix('-').unwrap_or(w);
    !body.is_empty()
        && body.starts_with(|c: char| c.is_ascii_digit())
        && body
            .chars()
            .all(|c| c.is_ascii_digit() || matches!(c, '.' | 'e' | 'E' | '+' | '-'))
        && w.parse::<f64>().is_ok()
}

/// Records with their 1-based index.
type Records = Box<dyn Iterator<Item = Result<(u64, JsonValue)>>>;

#[derive(Clone, Copy, PartialEq)]
enum Shape {
    Array,
    Lines,
    Document,
}

pub struct JsonSource {
    path: PathBuf,
    options: ImportOptions,
    columns: Vec<String>,
    charset: Charset,
    bom_length: usize,
    shape: Shape,
    document: Vec<JsonValue>,
    buffered: Option<Vec<JsonValue>>,
    consumed: bool,
}

impl JsonSource {
    pub fn open(
        path: &Path,
        requested: &ImportOptions,
        preview_rows: Option<usize>,
    ) -> Result<Self> {
        let mut head = Vec::new();
        File::open(path)?.take(4).read_to_end(&mut head)?;
        let bom = charset::bom_charset(&head);
        let charset = requested
            .charset
            .or(bom.map(|b| b.0))
            .unwrap_or(charset::UTF_8);
        let bom_length = bom.filter(|b| b.0 == charset).map_or(0, |b| b.1);
        let mut source = Self {
            path: path.to_owned(),
            options: ImportOptions {
                charset: Some(charset),
                ..requested.clone()
            },
            columns: Vec::new(),
            charset,
            bom_length,
            shape: Shape::Lines,
            document: Vec::new(),
            buffered: None,
            consumed: false,
        };
        source.shape = source.detect_shape()?;
        let mut keys: Vec<String> = Vec::new();
        let mut collect = |record: &JsonValue| match record {
            JsonValue::Object(entries) => {
                for (k, _) in entries {
                    if !keys.contains(k) {
                        keys.push(k.clone());
                    }
                }
            }
            _ => {
                if !keys.iter().any(|k| k == VALUE_COLUMN) {
                    keys.push(VALUE_COLUMN.into());
                }
            }
        };
        if let Some(limit) = preview_rows {
            let records: Vec<JsonValue> = source
                .records()?
                .take(limit)
                .map(|r| r.map(|(_, v)| v))
                .collect::<Result<_>>()?;
            records.iter().for_each(&mut collect);
            source.buffered = Some(records);
        } else {
            for record in source.records()? {
                collect(&record?.1);
            }
        }
        source.columns = keys;
        Ok(source)
    }

    fn reader(&self) -> Result<CharReader> {
        let mut file = std::io::BufReader::new(File::open(&self.path)?);
        std::io::copy(
            &mut (&mut file).take(self.bom_length as u64),
            &mut std::io::sink(),
        )?;
        Ok(CharReader::new(Box::new(file), self.charset, false))
    }

    fn name(&self) -> String {
        self.path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    /// `[` is an array. `{` is NDJSON when the first line is a whole JSON value and more follows; otherwise the file is
    /// one document: a wrapper around the records, a single record, or several pretty-printed objects back to back.
    fn detect_shape(&mut self) -> Result<Shape> {
        let mut reader = self.reader()?;
        let mut next = || reader.next_char().ok().flatten();
        let mut c = next();
        while c.is_some_and(char::is_whitespace) {
            c = next();
        }
        match c {
            Some('[') => return Ok(Shape::Array),
            Some('{') => {}
            None => return Ok(Shape::Lines),
            Some(_) => {
                return Err(ImportError::new(format!(
                    "{} doesn't start with [ or {{; expected a JSON array of objects, an object wrapping one, or NDJSON",
                    self.name()
                )));
            }
        }
        let mut first_line = String::from("{");
        let mut count = 1;
        loop {
            c = next();
            if c.is_none() || c == Some('\n') || count >= MAX_LINE_CHARS {
                break;
            }
            first_line.extend(c);
            count += 1;
        }
        let complete = c.is_none() || c == Some('\n');
        if complete && JsonValue::parse(&first_line).is_ok() {
            while c.is_some_and(char::is_whitespace) {
                c = next();
            }
            if c.is_some() {
                return Ok(Shape::Lines);
            }
        }
        let size = std::fs::metadata(&self.path)?.len();
        if size > MAX_DOCUMENT_BYTES {
            return Err(ImportError::new(format!(
                "{} is one JSON object of {} MB; an object wrapping the rows is read into memory and must be under {} MB. Save it as a top-level array or NDJSON (one object per line) instead.",
                self.name(),
                size / (1024 * 1024),
                MAX_DOCUMENT_BYTES / (1024 * 1024)
            )));
        }
        let mut parser = Parser::new(self.reader()?);
        let mut values = Vec::new();
        loop {
            parser.space().map_err(ImportError::new)?;
            if parser.peek().map_err(ImportError::new)?.is_none() {
                break;
            }
            let index = values.len() as u64 + 1;
            values.push(parser.value().map_err(|e| invalid(index, &e))?);
        }
        self.document = match values.as_slice() {
            [single] => unwrap(single).unwrap_or(values),
            _ => values,
        };
        Ok(Shape::Document)
    }

    /// Records with their 1-based index, read from the file afresh on every call.
    fn records(&self) -> Result<Records> {
        Ok(match self.shape {
            Shape::Document => Box::new(
                self.document
                    .clone()
                    .into_iter()
                    .enumerate()
                    .map(|(i, r)| Ok((i as u64 + 1, r))),
            ),
            Shape::Array => {
                let mut parser = Parser::new(self.reader()?);
                let mut index = 0u64;
                let mut started = false;
                let mut done = false;
                Box::new(std::iter::from_fn(move || {
                    if done {
                        return None;
                    }
                    let step = (|| -> std::result::Result<Option<JsonValue>, String> {
                        if !started {
                            started = true;
                            parser.expect('[')?;
                            parser.space()?;
                            if parser.peek()? == Some(']') {
                                return Ok(None);
                            }
                        } else {
                            parser.space()?;
                            match parser.bump()? {
                                Some(',') => {}
                                Some(']') => return Ok(None),
                                Some(c) => {
                                    return Err(format!("Expected ',' or ']' but found '{c}'"));
                                }
                                None => return Err("Unterminated array".into()),
                            }
                        }
                        parser.value().map(Some)
                    })();
                    index += 1;
                    match step {
                        Ok(Some(v)) => Some(Ok((index, v))),
                        Ok(None) => {
                            done = true;
                            None
                        }
                        Err(e) => {
                            done = true;
                            Some(Err(invalid(index, &e)))
                        }
                    }
                }))
            }
            // NDJSON: one value per line, blank lines ignored. Parse errors name the physical line.
            Shape::Lines => {
                let mut reader = self.reader()?;
                let mut line = 0u64;
                let mut index = 0u64;
                let mut done = false;
                Box::new(std::iter::from_fn(move || {
                    while !done {
                        let mut text = String::new();
                        let mut any = false;
                        loop {
                            match reader.next_char() {
                                Ok(Some('\n')) => {
                                    any = true;
                                    break;
                                }
                                Ok(Some(c)) => {
                                    any = true;
                                    text.push(c);
                                }
                                _ => break,
                            }
                        }
                        if !any {
                            done = true;
                            return None;
                        }
                        line += 1;
                        let text = text.strip_suffix('\r').unwrap_or(&text);
                        if text.trim().is_empty() {
                            continue;
                        }
                        return Some(match JsonValue::parse(text) {
                            Ok(v) => {
                                index += 1;
                                Ok((index, v))
                            }
                            Err(e) => {
                                done = true;
                                Err(ImportError::at(
                                    format!("Line {line} isn't valid JSON: {e}"),
                                    line,
                                ))
                            }
                        });
                    }
                    None
                }))
            }
        })
    }
}

fn invalid(index: u64, message: &str) -> ImportError {
    ImportError::at(format!("Invalid JSON in record {index}: {message}"), index)
}

/// The largest array of objects inside a single object, or None when it has none (it's then one record).
fn unwrap(element: &JsonValue) -> Option<Vec<JsonValue>> {
    let JsonValue::Object(entries) = element else {
        return None;
    };
    let mut best: Option<&Vec<JsonValue>> = None;
    for (_, v) in entries {
        if let JsonValue::Array(items) = v
            && !items.is_empty()
            && items.iter().all(|i| matches!(i, JsonValue::Object(_)))
            && best.is_none_or(|b| items.len() > b.len())
        {
            best = Some(items);
        }
    }
    best.cloned()
}

impl ImportSource for JsonSource {
    fn columns(&self) -> &[String] {
        &self.columns
    }

    fn options(&self) -> &ImportOptions {
        &self.options
    }

    fn rows(&mut self) -> Result<Rows<'_>> {
        if self.consumed {
            return Err(ImportError::new("rows() can only be read once"));
        }
        self.consumed = true;
        let source: Records = match self.buffered.take() {
            Some(rows) => Box::new(
                rows.into_iter()
                    .enumerate()
                    .map(|(i, r)| Ok((i as u64 + 1, r))),
            ),
            None => self.records()?,
        };
        let columns = self.columns.clone();
        Ok(Box::new(source.map(move |record| {
            let (line, record) = record?;
            let values = match &record {
                JsonValue::Object(_) => columns
                    .iter()
                    .map(|c| record.get(c).and_then(JsonValue::cell))
                    .collect(),
                other => columns
                    .iter()
                    .map(|c| {
                        if c == VALUE_COLUMN {
                            other.cell()
                        } else {
                            None
                        }
                    })
                    .collect(),
            };
            Ok(ImportRow { line, values })
        })))
    }
}

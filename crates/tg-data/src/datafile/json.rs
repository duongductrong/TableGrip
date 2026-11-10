//! JSON records (`datafile/JsonFormat.kt`): a top-level array of objects or NDJSON (one object per line), one table
//! named after the file. Columns are the union of the objects' keys; nested objects and arrays are compact JSON text.
//! Writing keeps the shape, the indentation and each column's JSON type (numbers, booleans and nested values stay
//! unquoted), and leaves out keys that were missing rather than null. Other layouts (an object wrapping the records,
//! UTF-16) open read-only.

use super::{
    CellValue, ColumnKind, DataFileDocument, DataFileError, DataValues, OutputTable, Result,
    SourceRows, TableInfo, table_name,
};
use crate::importer::charset::{self, CharReader};
use crate::importer::json::{Parser, quote};
use crate::importer::{self, ImportFormat, ImportOptions, ImportSource, JsonValue};
use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const HEAD_BYTES: u64 = 64 * 1024;

#[derive(Clone, Copy, PartialEq)]
enum Shape {
    Array,
    Lines,
}

/// How records are laid out: `indent` is None for compact records, else one level of pretty-printing.
#[derive(Clone)]
struct Layout {
    record_per_line: bool,
    indent: Option<String>,
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum Kind {
    String,
    Number,
    Boolean,
    Nested,
    Mixed,
}

#[derive(Clone, Debug)]
struct ColumnState {
    name: String,
    kind: Option<Kind>,
    absent: bool,
    nulls: bool,
}

struct Document {
    path: PathBuf,
    name: String,
    shape: Shape,
    bom_length: usize,
    layout: Layout,
    columns: Arc<Mutex<Vec<ColumnState>>>,
}

pub fn open(path: &Path) -> Result<Box<dyn DataFileDocument>> {
    let mut head = Vec::new();
    std::fs::File::open(path)?
        .take(HEAD_BYTES)
        .read_to_end(&mut head)?;
    let bom = charset::bom_charset(&head);
    if let Some((c, _)) = bom
        && c != charset::UTF_8
    {
        return read_only(
            path,
            format!(
                "Only UTF-8 JSON files can be saved; this one is {}.",
                c.name()
            ),
        );
    }
    let bom_length = bom.map_or(0, |b| b.1);
    let text = String::from_utf8_lossy(&head[bom_length..]).into_owned();
    let shape = match text.trim_start().chars().next() {
        Some('[') => Shape::Array,
        Some('{') => {
            if is_lines(&text) {
                Shape::Lines
            } else {
                return read_only(
                    path,
                    "Only a top-level array of objects or NDJSON (one object per line) can be saved.".into(),
                );
            }
        }
        None => Shape::Lines,
        Some(_) => {
            return Err(DataFileError(format!(
                "{} doesn't start with [ or {{; expected a JSON array of objects or NDJSON",
                path.file_name()
                    .map(|n| n.to_string_lossy())
                    .unwrap_or_default()
            )));
        }
    };
    let document = Document {
        path: path.to_owned(),
        name: table_name(path),
        shape,
        bom_length,
        layout: layout(&text, shape),
        columns: Arc::new(Mutex::new(Vec::new())),
    };
    let mut keys: Vec<ColumnState> = Vec::new();
    for record in document.records()? {
        for (key, _) in record?.1 {
            if !keys.iter().any(|c| c.name == key) {
                keys.push(ColumnState {
                    name: key,
                    kind: None,
                    absent: false,
                    nulls: false,
                });
            }
        }
    }
    *document.columns.lock().unwrap() = keys;
    Ok(Box::new(document))
}

type Records = Box<dyn Iterator<Item = Result<(u64, Vec<(String, JsonValue)>)>>>;

impl Document {
    fn reader(&self) -> Result<CharReader> {
        let mut file = std::io::BufReader::new(std::fs::File::open(&self.path)?);
        std::io::copy(
            &mut (&mut file).take(self.bom_length as u64),
            &mut std::io::sink(),
        )?;
        Ok(CharReader::new(Box::new(file), charset::UTF_8, false))
    }

    /// Records with their 1-based index; reading fails on anything but an object.
    fn records(&self) -> Result<Records> {
        let raw: Box<dyn Iterator<Item = Result<(u64, JsonValue)>>> = match self.shape {
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
                        parser.space()?;
                        if !started {
                            started = true;
                            if parser.peek()? != Some('[') {
                                return Err("Expected '['".into());
                            }
                            parser.value_start()?;
                            parser.space()?;
                            if parser.peek()? == Some(']') {
                                return Ok(None);
                            }
                        } else {
                            match parser.next_separator()? {
                                ',' => {}
                                _ => return Ok(None),
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
                            Some(Err(DataFileError(format!(
                                "Invalid JSON in record {index}: {e}"
                            ))))
                        }
                    }
                }))
            }
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
                                Err(DataFileError(format!("Line {line} isn't valid JSON: {e}")))
                            }
                        });
                    }
                    None
                }))
            }
        };
        Ok(Box::new(raw.map(|r| {
            let (index, value) = r?;
            match value {
                JsonValue::Object(entries) => Ok((index, entries)),
                _ => Err(DataFileError(format!(
                    "Record {index} isn't a JSON object; only arrays of objects open as a table."
                ))),
            }
        })))
    }
}

fn kind_of(value: &JsonValue) -> Kind {
    match value {
        JsonValue::String(_) => Kind::String,
        JsonValue::Bool(_) => Kind::Boolean,
        JsonValue::Number(_) => Kind::Number,
        _ => Kind::Nested,
    }
}

impl DataFileDocument for Document {
    fn tables(&self) -> Vec<TableInfo> {
        vec![TableInfo {
            name: self.name.clone(),
            columns: self
                .columns
                .lock()
                .unwrap()
                .iter()
                .map(|c| c.name.clone())
                .collect(),
        }]
    }

    fn rows(&mut self, _index: usize) -> Result<SourceRows<'_>> {
        let records = self.records()?;
        let columns = self.columns.clone();
        Ok(Box::new(records.map(move |record| {
            let (_, record) = record?;
            let mut states = columns.lock().unwrap();
            Ok(states
                .iter_mut()
                .map(|column| {
                    match record
                        .iter()
                        .find(|(k, _)| *k == column.name)
                        .map(|(_, v)| v)
                    {
                        None => {
                            column.absent = true;
                            None
                        }
                        Some(JsonValue::Null) => {
                            column.nulls = true;
                            None
                        }
                        Some(v) => {
                            let kind = kind_of(v);
                            column.kind = Some(match column.kind {
                                Some(k) if k != kind => Kind::Mixed,
                                _ => kind,
                            });
                            v.cell()
                        }
                    }
                })
                .collect())
        })))
    }

    fn multi_table(&self) -> bool {
        false
    }

    fn notes(&self) -> Vec<String> {
        self.columns
            .lock()
            .unwrap()
            .iter()
            .filter(|c| c.kind == Some(Kind::Mixed))
            .map(|c| {
                format!(
                    "Column \"{}\" mixes JSON types; saving writes its values as strings.",
                    c.name
                )
            })
            .collect()
    }

    fn write(
        &mut self,
        target: &Path,
        tables: &[OutputTable<'_>],
        _removed: &BTreeSet<String>,
    ) -> Result<()> {
        let table = &tables[0];
        let states: Vec<Option<ColumnState>> = {
            let columns = self.columns.lock().unwrap();
            table
                .columns
                .iter()
                .map(|c| columns.iter().find(|s| s.name == c.name).cloned())
                .collect()
        };
        let layout = &self.layout;
        let mut out = std::io::BufWriter::with_capacity(64 * 1024, std::fs::File::create(target)?);
        if self.bom_length > 0 {
            out.write_all("\u{feff}".as_bytes())?;
        }
        if self.shape == Shape::Array {
            out.write_all(if layout.record_per_line { b"[\n" } else { b"[" })?;
        }
        let mut first = true;
        for row in (table.rows)()? {
            let mut record: Vec<(String, Element)> = Vec::new();
            for (i, value) in row.iter().enumerate() {
                let column = &table.columns[i];
                let state = states[i].as_ref();
                let kind = state.and_then(|s| s.kind).unwrap_or(match column.kind {
                    ColumnKind::Text => Kind::String,
                    _ => Kind::Number,
                });
                let element = element(value, kind);
                if matches!(element, Element::Null) && state.is_some_and(|s| s.absent && !s.nulls) {
                    continue;
                }
                record.push((column.name.clone(), element));
            }
            let text = match &layout.indent {
                Some(indent) => pretty(&record, indent),
                None => compact(&record),
            };
            match self.shape {
                Shape::Lines => writeln!(out, "{text}")?,
                Shape::Array => {
                    if !first {
                        out.write_all(if layout.record_per_line { b",\n" } else { b"," })?;
                    }
                    match &layout.indent {
                        Some(indent) => out.write_all(prepend_indent(&text, indent).as_bytes())?,
                        None => out.write_all(text.as_bytes())?,
                    }
                }
            }
            first = false;
        }
        if self.shape == Shape::Array {
            out.write_all(if layout.record_per_line {
                b"\n]\n"
            } else {
                b"]"
            })?;
        }
        out.flush()?;
        Ok(())
    }
}

/// A value to write: a JSON value, or a literal written as it is.
enum Element {
    Null,
    Value(JsonValue),
}

fn element(value: &CellValue, kind: Kind) -> Element {
    let Some(text) = DataValues::text(value) else {
        return Element::Null;
    };
    Element::Value(match (kind, value) {
        // "123" stays a string even though the working copy typed its column as numbers.
        (Kind::String | Kind::Mixed, _) => JsonValue::String(text),
        (_, CellValue::Integer(i)) => JsonValue::Number(i.to_string()),
        (_, CellValue::Real(f)) if f.is_finite() => JsonValue::Number(DataValues::real(*f)),
        (Kind::Number, _) if regex_lite_number(&text) => JsonValue::Number(text),
        (Kind::Boolean, _) if text == "true" || text == "false" => JsonValue::Bool(text == "true"),
        (Kind::Nested, _) => JsonValue::parse(&text).unwrap_or(JsonValue::String(text)),
        _ => JsonValue::String(text),
    })
}

/// `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`
fn regex_lite_number(t: &str) -> bool {
    let b = t.as_bytes();
    let mut i = 0;
    if b.first() == Some(&b'-') {
        i += 1;
    }
    let digits = |i: &mut usize| {
        let start = *i;
        while *i < b.len() && b[*i].is_ascii_digit() {
            *i += 1;
        }
        *i - start
    };
    match b.get(i) {
        Some(b'0') => i += 1,
        Some(c) if c.is_ascii_digit() => {
            digits(&mut i);
        }
        _ => return false,
    }
    if b.get(i) == Some(&b'.') {
        i += 1;
        if digits(&mut i) == 0 {
            return false;
        }
    }
    if matches!(b.get(i), Some(b'e' | b'E')) {
        i += 1;
        if matches!(b.get(i), Some(b'+' | b'-')) {
            i += 1;
        }
        if digits(&mut i) == 0 {
            return false;
        }
    }
    i == b.len()
}

fn compact(record: &[(String, Element)]) -> String {
    let entries: Vec<String> = record
        .iter()
        .map(|(k, v)| {
            let value = match v {
                Element::Null => "null".to_owned(),
                Element::Value(v) => v.to_string(),
            };
            format!("{}:{value}", quote(k))
        })
        .collect();
    format!("{{{}}}", entries.join(","))
}

/// kotlinx's pretty printer: one entry per line, `"key": value`, nested values indented one more level.
fn pretty(record: &[(String, Element)], indent: &str) -> String {
    let object = JsonValue::Object(
        record
            .iter()
            .map(|(k, v)| {
                (
                    k.clone(),
                    match v {
                        Element::Null => JsonValue::Null,
                        Element::Value(v) => v.clone(),
                    },
                )
            })
            .collect(),
    );
    let mut out = String::new();
    pretty_value(&mut out, &object, indent, 0);
    out
}

fn pretty_value(out: &mut String, value: &JsonValue, indent: &str, level: usize) {
    match value {
        JsonValue::Object(entries) if !entries.is_empty() => {
            out.push_str("{\n");
            for (i, (k, v)) in entries.iter().enumerate() {
                out.push_str(&indent.repeat(level + 1));
                out.push_str(&quote(k));
                out.push_str(": ");
                pretty_value(out, v, indent, level + 1);
                if i + 1 < entries.len() {
                    out.push(',');
                }
                out.push('\n');
            }
            out.push_str(&indent.repeat(level));
            out.push('}');
        }
        JsonValue::Array(items) if !items.is_empty() => {
            out.push_str("[\n");
            for (i, v) in items.iter().enumerate() {
                out.push_str(&indent.repeat(level + 1));
                pretty_value(out, v, indent, level + 1);
                if i + 1 < items.len() {
                    out.push(',');
                }
                out.push('\n');
            }
            out.push_str(&indent.repeat(level));
            out.push(']');
        }
        other => out.push_str(&other.to_string()),
    }
}

/// Kotlin's `prependIndent`.
fn prepend_indent(text: &str, indent: &str) -> String {
    text.split('\n')
        .map(|line| {
            if line.trim().is_empty() {
                if line.len() < indent.len() {
                    indent.to_owned()
                } else {
                    line.to_owned()
                }
            } else {
                format!("{indent}{line}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// NDJSON when the first line is a whole JSON value and more follows it.
fn is_lines(text: &str) -> bool {
    let trimmed = text.trim_start();
    let Some(newline) = trimmed.find('\n') else {
        return false;
    };
    JsonValue::parse(&trimmed[..newline]).is_ok() && !trimmed[newline..].trim().is_empty()
}

/// From the start of the file: whether records start on lines of their own, and the indent of a pretty-printed one.
fn layout(text: &str, shape: Shape) -> Layout {
    let plain = Layout {
        record_per_line: true,
        indent: None,
    };
    if shape == Shape::Lines {
        return plain;
    }
    let Some(open) = text.find('[') else {
        return plain;
    };
    let Some(record) = text[open + 1..].find('{').map(|i| i + open + 1) else {
        return plain;
    };
    let record_per_line = text[open..record].contains('\n');
    let body = match text[record..].find('}') {
        Some(end) => &text[record..record + end],
        None => &text[record..],
    };
    if !body.contains('\n') {
        return Layout {
            record_per_line,
            indent: None,
        };
    }
    let key_line = body.split_once('\n').map_or("", |(_, rest)| rest);
    let key_indent: String = key_line
        .chars()
        .take_while(|c| *c == ' ' || *c == '\t')
        .collect();
    let line_start = text[..record].rfind('\n').map_or(0, |i| i + 1);
    let record_indent: String = text[line_start..record]
        .chars()
        .take_while(|c| *c == ' ' || *c == '\t')
        .collect();
    let unit = key_indent
        .strip_prefix(&record_indent)
        .unwrap_or(&key_indent);
    Layout {
        record_per_line: true,
        indent: Some(if unit.is_empty() {
            "  ".into()
        } else {
            unit.to_owned()
        }),
    }
}

/// Opened through the importer's reader, which reads every layout but can't say how to write it back.
fn read_only(path: &Path, reason: String) -> Result<Box<dyn DataFileDocument>> {
    let source = importer::open(path, &ImportOptions::new(ImportFormat::Json))?;
    Ok(Box::new(ReadOnly {
        name: table_name(path),
        columns: source.columns().to_vec(),
        source,
        reason,
    }))
}

struct ReadOnly {
    name: String,
    columns: Vec<String>,
    source: Box<dyn ImportSource>,
    reason: String,
}

impl DataFileDocument for ReadOnly {
    fn tables(&self) -> Vec<TableInfo> {
        vec![TableInfo {
            name: self.name.clone(),
            columns: self.columns.clone(),
        }]
    }

    fn rows(&mut self, _index: usize) -> Result<SourceRows<'_>> {
        let rows = self.source.rows()?;
        Ok(Box::new(
            rows.map(|r| r.map(|r| r.values).map_err(DataFileError::from)),
        ))
    }

    fn multi_table(&self) -> bool {
        false
    }

    fn read_only_reason(&self) -> Option<String> {
        Some(self.reason.clone())
    }

    fn write(&mut self, _: &Path, _: &[OutputTable<'_>], _: &BTreeSet<String>) -> Result<()> {
        Err(DataFileError(self.reason.clone()))
    }
}

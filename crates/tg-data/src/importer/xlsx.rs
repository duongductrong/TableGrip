//! An `.xlsx` sheet (`importer/XlsxSource.kt`), read straight from the zip's XML: the cells' text as the user saw it,
//! dates shown by their number format turned into ISO text. Opening scans the sheet once to find its width (the last
//! column holding a value), then the rows are read again; in preview mode the first rows are buffered instead and
//! size the columns on their own. The sheet's XML is decompressed into memory once.

use super::{
    ImportError, ImportOptions, ImportRow, ImportSource, Result, Rows, fit_row,
    generated_column_names, normalize_column_names,
};
use quick_xml::events::{BytesStart, Event};
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DateKind {
    Date,
    DateTime,
    Time,
}

type Archive = zip::ZipArchive<std::io::BufReader<File>>;

/// Zip names are case-sensitive but some writers disagree with their own relationships on case.
fn find_entry(zip: &Archive, path: &str) -> Option<String> {
    if zip.index_for_name(path).is_some() {
        return Some(path.to_owned());
    }
    zip.file_names()
        .filter_map(|n| n.ok())
        .find(|n| n.eq_ignore_ascii_case(path))
        .map(|n| n.into_owned())
}

fn read_entry(zip: &mut Archive, path: &str, required: bool) -> Result<Option<Vec<u8>>> {
    let Some(name) = find_entry(zip, path) else {
        return if required {
            Err(ImportError::new(format!(
                "The workbook is missing {path}; is it really an .xlsx file?"
            )))
        } else {
            Ok(None)
        };
    };
    let mut entry = zip
        .by_name(&name)
        .map_err(|e| ImportError::new(format!("Couldn't read {path}: {e}")))?;
    let mut bytes = Vec::new();
    entry.read_to_end(&mut bytes)?;
    Ok(Some(bytes))
}

fn attribute(e: &BytesStart, name: &str) -> Option<String> {
    e.attributes()
        .flatten()
        .find(|a| a.key.local_name().as_ref() == name.as_bytes())
        .map(|a| {
            let raw = String::from_utf8_lossy(&a.value).into_owned();
            quick_xml::escape::unescape(&raw).map_or(raw.clone(), |v| v.into_owned())
        })
}

fn malformed(path: &str, e: impl std::fmt::Display) -> ImportError {
    ImportError::new(format!(
        "Couldn't read {path}: the workbook XML is malformed ({e})"
    ))
}

/// Walks an XML part's events.
struct Xml<'a> {
    reader: quick_xml::Reader<&'a [u8]>,
    path: String,
}

enum Node {
    Start(BytesStart<'static>, bool),
    End(String),
    Text(String),
    Eof,
}

impl<'a> Xml<'a> {
    fn new(bytes: &'a [u8], path: &str) -> Self {
        let mut reader = quick_xml::Reader::from_reader(bytes);
        reader.config_mut().trim_text(false);
        Self {
            reader,
            path: path.to_owned(),
        }
    }

    fn next(&mut self) -> Result<Node> {
        loop {
            let event = self
                .reader
                .read_event()
                .map_err(|e| malformed(&self.path, e))?;
            return Ok(match event {
                Event::Start(e) => Node::Start(e.into_owned(), false),
                Event::Empty(e) => Node::Start(e.into_owned(), true),
                Event::End(e) => {
                    Node::End(String::from_utf8_lossy(e.local_name().as_ref()).into_owned())
                }
                Event::Text(t) => Node::Text(
                    t.decode()
                        .map_err(|e| malformed(&self.path, e))?
                        .into_owned(),
                ),
                Event::CData(t) => Node::Text(String::from_utf8_lossy(&t).into_owned()),
                Event::GeneralRef(r) => {
                    let name = String::from_utf8_lossy(&r).into_owned();
                    Node::Text(match r.resolve_char_ref().ok().flatten() {
                        Some(c) => c.to_string(),
                        None => quick_xml::escape::resolve_predefined_entity(&name)
                            .map(str::to_owned)
                            .unwrap_or_default(),
                    })
                }
                Event::Eof => Node::Eof,
                _ => continue,
            });
        }
    }

    /// The text up to the end of the element just started.
    fn element_text(&mut self, empty: bool) -> Result<String> {
        let mut text = String::new();
        if empty {
            return Ok(text);
        }
        let mut depth = 1;
        while depth > 0 {
            match self.next()? {
                Node::Start(_, false) => depth += 1,
                Node::End(_) => depth -= 1,
                Node::Text(t) => text.push_str(&t),
                Node::Eof => return Err(malformed(&self.path, "unexpected end")),
                Node::Start(_, true) => {}
            }
        }
        Ok(text)
    }

    fn skip(&mut self, empty: bool) -> Result<()> {
        self.element_text(empty).map(|_| ())
    }

    /// The text of a plain (`<t>`) or rich (`<r><t>…`) string up to `end`, leaving out phonetic hints (`<rPh>`).
    fn rich_text(&mut self, end: &str) -> Result<String> {
        let mut text = String::new();
        loop {
            match self.next()? {
                Node::Start(e, empty) => match e.local_name().as_ref() {
                    b"t" => text.push_str(&self.element_text(empty)?),
                    b"rPh" | b"phoneticPr" => self.skip(empty)?,
                    _ => {}
                },
                Node::End(name) if name == end => return Ok(unescape_ooxml(&text)),
                Node::Eof => return Err(malformed(&self.path, "unexpected end")),
                _ => {}
            }
        }
    }
}

fn local(e: &BytesStart) -> String {
    String::from_utf8_lossy(e.local_name().as_ref()).into_owned()
}

/// The workbook-level parts: sheet names -> paths, shared strings, date styles and the date system.
pub struct Workbook {
    pub sheets: Vec<(String, String)>,
    pub date1904: bool,
    pub shared_strings: Vec<String>,
    style_kinds: Vec<Option<DateKind>>,
}

impl Workbook {
    fn read(zip: &mut Archive) -> Result<Self> {
        let root = relationships(zip, "_rels/.rels", "")?;
        let workbook_path = root
            .values()
            .find(|(t, _)| t.ends_with("/officeDocument"))
            .map(|(_, p)| p.clone())
            .unwrap_or_else(|| "xl/workbook.xml".into());
        let directory = workbook_path
            .rsplit_once('/')
            .map_or("", |(d, _)| d)
            .to_owned();
        let file_name = workbook_path.rsplit('/').next().unwrap_or("");
        let rels = relationships(
            zip,
            &format!("{}_rels/{file_name}.rels", with_slash(&directory)),
            &directory,
        )?;
        let bytes = read_entry(zip, &workbook_path, true)?.unwrap_or_default();
        let mut xml = Xml::new(&bytes, &workbook_path);
        let mut date1904 = false;
        let mut ids = Vec::new();
        loop {
            match xml.next()? {
                Node::Start(e, _) => match local(&e).as_str() {
                    "workbookPr" => {
                        date1904 = attribute(&e, "date1904")
                            .is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                    }
                    "sheet" => ids.push((
                        attribute(&e, "name").unwrap_or_default(),
                        attribute(&e, "id").unwrap_or_default(),
                    )),
                    _ => {}
                },
                Node::Eof => break,
                _ => {}
            }
        }
        let mut sheets = Vec::new();
        for (name, id) in ids {
            let Some((kind, target)) = rels.get(&id) else {
                continue;
            };
            // Chartsheets and dialog sheets have no cells to import.
            if kind.ends_with("/worksheet") && find_entry(zip, target).is_some() {
                sheets.push((name, target.clone()));
            }
        }
        let part = |suffix: &str, default: &str| {
            rels.values()
                .find(|(t, _)| t.ends_with(suffix))
                .map(|(_, p)| p.clone())
                .unwrap_or_else(|| default.to_owned())
        };
        let shared_strings =
            read_shared_strings(zip, &part("/sharedStrings", "xl/sharedStrings.xml"))?;
        let style_kinds = read_styles(zip, &part("/styles", "xl/styles.xml"))?;
        Ok(Self {
            sheets,
            date1904,
            shared_strings,
            style_kinds,
        })
    }

    pub fn date_kind(&self, style: usize) -> Option<DateKind> {
        self.style_kinds.get(style).copied().flatten()
    }
}

fn with_slash(s: &str) -> String {
    if s.is_empty() {
        String::new()
    } else {
        format!("{s}/")
    }
}

/// Relationship id -> (type, zip path), with targets resolved against `base` (the source part's directory).
fn relationships(
    zip: &mut Archive,
    path: &str,
    base: &str,
) -> Result<HashMap<String, (String, String)>> {
    let mut out = HashMap::new();
    let Some(bytes) = read_entry(zip, path, false)? else {
        return Ok(out);
    };
    let mut xml = Xml::new(&bytes, path);
    loop {
        match xml.next()? {
            Node::Start(e, _) if local(&e) == "Relationship" => {
                if attribute(&e, "TargetMode").as_deref() == Some("External") {
                    continue;
                }
                let (Some(id), Some(target)) = (attribute(&e, "Id"), attribute(&e, "Target"))
                else {
                    continue;
                };
                out.insert(
                    id,
                    (
                        attribute(&e, "Type").unwrap_or_default(),
                        resolve(base, &target),
                    ),
                );
            }
            Node::Eof => return Ok(out),
            _ => {}
        }
    }
}

pub fn resolve(base: &str, target: &str) -> String {
    let path = target.replace("\\", "/");
    let joined = match path.strip_prefix('/') {
        Some(p) => p.to_owned(),
        None => with_slash(base) + &path,
    };
    let mut parts: Vec<&str> = Vec::new();
    for part in joined.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            p => parts.push(p),
        }
    }
    parts.join("/")
}

fn read_shared_strings(zip: &mut Archive, path: &str) -> Result<Vec<String>> {
    let mut out = Vec::new();
    let Some(bytes) = read_entry(zip, path, false)? else {
        return Ok(out);
    };
    let mut xml = Xml::new(&bytes, path);
    loop {
        match xml.next()? {
            Node::Start(e, empty) if local(&e) == "si" => out.push(if empty {
                String::new()
            } else {
                xml.rich_text("si")?
            }),
            Node::Eof => return Ok(out),
            _ => {}
        }
    }
}

/// Per `cellXfs` index, whether its number format shows a date/time. `cellStyleXfs` (named styles) are ignored.
fn read_styles(zip: &mut Archive, path: &str) -> Result<Vec<Option<DateKind>>> {
    let mut custom: HashMap<u32, String> = HashMap::new();
    let mut ids = Vec::new();
    let Some(bytes) = read_entry(zip, path, false)? else {
        return Ok(Vec::new());
    };
    let mut xml = Xml::new(&bytes, path);
    // numFmt also appears under <dxfs> (conditional formatting); only <numFmts> defines what cells use.
    let mut section = String::new();
    loop {
        match xml.next()? {
            Node::Start(e, empty) => {
                let name = local(&e);
                match name.as_str() {
                    "numFmts" | "cellXfs" | "cellStyleXfs" | "dxfs" if !empty => section = name,
                    "numFmt" if section == "numFmts" => {
                        if let Some(id) = attribute(&e, "numFmtId").and_then(|i| i.parse().ok()) {
                            custom.insert(id, attribute(&e, "formatCode").unwrap_or_default());
                        }
                    }
                    "xf" if section == "cellXfs" => ids.push(
                        attribute(&e, "numFmtId")
                            .and_then(|i| i.parse().ok())
                            .unwrap_or(0),
                    ),
                    _ => {}
                }
            }
            Node::End(name) if name == section => section.clear(),
            Node::Eof => break,
            _ => {}
        }
    }
    Ok(ids
        .into_iter()
        .map(|id| match custom.get(&id) {
            Some(code) => format_kind(code),
            None => builtin_kind(id),
        })
        .collect())
}

pub fn builtin_kind(id: u32) -> Option<DateKind> {
    match id {
        14..=17 => Some(DateKind::Date),
        18..=21 | 45..=47 => Some(DateKind::Time),
        22 => Some(DateKind::DateTime),
        _ => None,
    }
}

/// Classifies an Excel custom number format by its d/m/y/h/s tokens, ignoring quoted text, `[…]` (colors, locales,
/// elapsed time) and escapes.
pub fn format_kind(code: &str) -> Option<DateKind> {
    let chars: Vec<char> = code.chars().collect();
    let mut tokens = String::new();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '"' => {
                i = chars[i + 1..]
                    .iter()
                    .position(|c| *c == '"')
                    .map_or(chars.len(), |p| i + 1 + p)
            }
            '[' => {
                i = chars[i + 1..]
                    .iter()
                    .position(|c| *c == ']')
                    .map_or(chars.len(), |p| i + 1 + p)
            }
            '\\' | '_' | '*' => i += 1,
            c => tokens.extend(c.to_lowercase()),
        }
        i += 1;
    }
    let has_time = tokens.contains('h') || tokens.contains('s');
    let has_date =
        tokens.contains('d') || tokens.contains('y') || (tokens.contains('m') && !has_time);
    match (has_date, has_time) {
        (true, true) => Some(DateKind::DateTime),
        (true, false) => Some(DateKind::Date),
        (false, true) => Some(DateKind::Time),
        _ => None,
    }
}

/// Characters XML can't hold are written as `_xHHHH_` (Excel writes CR as `_x000D_`); `_x005F_` escapes a literal
/// underscore.
pub fn unescape_ooxml(text: &str) -> String {
    if !text.contains("_x") {
        return text.to_owned();
    }
    let units: Vec<u16> = text.encode_utf16().collect();
    let mut out: Vec<u16> = Vec::with_capacity(units.len());
    let mut i = 0;
    while i < units.len() {
        if units[i] == u16::from(b'_')
            && i + 6 < units.len()
            && units[i + 1] == u16::from(b'x')
            && units[i + 6] == u16::from(b'_')
        {
            let hex = String::from_utf16_lossy(&units[i + 2..i + 6]);
            if let Ok(code) = u16::from_str_radix(&hex, 16) {
                out.push(code);
                i += 7;
                continue;
            }
        }
        out.push(units[i]);
        i += 1;
    }
    String::from_utf16_lossy(&out)
}

/// 0-based column of a cell reference like `C7` or `AA12`, or None when it's malformed.
pub fn column_index(reference: &str) -> Option<usize> {
    let mut column = 0usize;
    let mut letters = 0;
    for c in reference.chars() {
        let upper = c.to_ascii_uppercase();
        if !upper.is_ascii_uppercase() {
            break;
        }
        column = column * 26 + (upper as usize - 'A' as usize + 1);
        letters += 1;
    }
    (1..=3).contains(&letters).then(|| column - 1)
}

/// A serial date as ISO text, or None when out of range (then it's shown as the number). The 1900 system counts
/// Lotus's phantom 1900-02-29 as day 60, so serials from 61 on are one day ahead of a plain count.
pub fn excel_date(serial: f64, kind: DateKind, date1904: bool) -> Option<String> {
    use chrono::{Days, NaiveDate};
    if serial.is_nan() || !(0.0..2_958_466.0).contains(&serial) {
        return None;
    }
    let mut days = serial.floor() as i64;
    // Math.round: half up.
    let mut seconds = ((serial - days as f64) * 86_400.0 + 0.5).floor() as i64;
    if seconds >= 86_400 {
        days += 1;
        seconds -= 86_400;
    }
    let time = format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60
    );
    if kind == DateKind::Time && days == 0 {
        return Some(time);
    }
    let (start, offset) = if date1904 {
        (NaiveDate::from_ymd_opt(1904, 1, 1)?, days)
    } else if days < 61 {
        (NaiveDate::from_ymd_opt(1899, 12, 31)?, days)
    } else {
        (NaiveDate::from_ymd_opt(1899, 12, 30)?, days)
    };
    let date = start.checked_add_days(Days::new(offset as u64))?;
    let date = date.format("%Y-%m-%d").to_string();
    Some(if kind == DateKind::Date && seconds == 0 {
        date
    } else {
        format!("{date} {time}")
    })
}

/// Integers without `.0`, everything else as plain digits (no exponent): `BigDecimal.stripTrailingZeros()
/// .toPlainString()`.
pub fn plain_number(raw: &str) -> Option<String> {
    let mut text = tg_sql::Decimal::parse(raw)?.to_plain_string();
    if text.contains('.') {
        text = text.trim_end_matches('0').trim_end_matches('.').to_owned();
    }
    if text == "-0" {
        text = "0".into();
    }
    Some(text)
}

/// Streams one worksheet's rows, skipping `skip_rows` and rows without a value.
struct SheetReader<'a> {
    xml: Xml<'a>,
    workbook: &'a Workbook,
    options: &'a ImportOptions,
    last_row: u64,
}

impl<'a> SheetReader<'a> {
    fn new(
        bytes: &'a [u8],
        path: &str,
        workbook: &'a Workbook,
        options: &'a ImportOptions,
    ) -> Self {
        Self {
            xml: Xml::new(bytes, path),
            workbook,
            options,
            last_row: 0,
        }
    }

    fn next_data_row(&mut self) -> Result<Option<ImportRow>> {
        loop {
            let Some(row) = self.next_row()? else {
                return Ok(None);
            };
            if row.line <= self.options.skip_rows {
                continue;
            }
            if row
                .values
                .iter()
                .all(|v| v.as_deref().is_none_or(|s| s.trim().is_empty()))
            {
                continue;
            }
            return Ok(Some(row));
        }
    }

    fn next_row(&mut self) -> Result<Option<ImportRow>> {
        let line = self.last_row + 1;
        let sheet = self.options.sheet.clone().unwrap_or_default();
        let wrap = |e: ImportError| {
            ImportError::at(
                format!(
                    "Couldn't read row {line} of sheet \"{sheet}\": {}",
                    e.message
                ),
                e.line.unwrap_or(line),
            )
        };
        loop {
            match self.xml.next().map_err(wrap)? {
                Node::Start(e, empty) if local(&e) == "row" => {
                    return self.read_row(&e, empty).map(Some);
                }
                Node::Eof => return Ok(None),
                _ => {}
            }
        }
    }

    fn read_row(&mut self, row: &BytesStart, empty: bool) -> Result<ImportRow> {
        let number = attribute(row, "r")
            .and_then(|r| r.parse().ok())
            .unwrap_or(self.last_row + 1);
        self.last_row = number;
        let mut cells: Vec<Option<String>> = Vec::new();
        if empty {
            return Ok(ImportRow {
                line: number,
                values: cells,
            });
        }
        let mut last_column: i64 = -1;
        loop {
            match self.xml.next()? {
                Node::Start(e, cell_empty) if local(&e) == "c" => {
                    let column = attribute(&e, "r")
                        .and_then(|r| column_index(&r))
                        .map_or(last_column + 1, |c| c as i64);
                    last_column = column;
                    if let Some(value) = self.read_cell(&e, cell_empty, number)? {
                        let column = column as usize;
                        if cells.len() <= column {
                            cells.resize(column + 1, None);
                        }
                        cells[column] = Some(value);
                    }
                }
                Node::Start(_, e) => self.xml.skip(e)?,
                Node::End(name) if name == "row" => {
                    return Ok(ImportRow {
                        line: number,
                        values: cells,
                    });
                }
                Node::Eof => return Err(malformed(&self.xml.path, "unexpected end")),
                _ => {}
            }
        }
    }

    /// The cell's text as the user saw it, or None for an empty or error cell.
    fn read_cell(&mut self, cell: &BytesStart, empty: bool, row: u64) -> Result<Option<String>> {
        let kind = attribute(cell, "t");
        let style: usize = attribute(cell, "s")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);
        let mut raw: Option<String> = None;
        let mut inline: Option<String> = None;
        if !empty {
            loop {
                match self.xml.next()? {
                    Node::Start(e, e_empty) => match local(&e).as_str() {
                        "v" => raw = Some(self.xml.element_text(e_empty)?),
                        "is" => {
                            inline = Some(if e_empty {
                                String::new()
                            } else {
                                self.xml.rich_text("is")?
                            })
                        }
                        // <f>: the cached <v> holds the result.
                        _ => self.xml.skip(e_empty)?,
                    },
                    Node::End(name) if name == "c" => break,
                    Node::Eof => return Err(malformed(&self.xml.path, "unexpected end")),
                    _ => {}
                }
            }
        }
        // A cell element with no value is just formatting.
        if raw.is_none() && inline.is_none() {
            return Ok(None);
        }
        let text =
            match kind.as_deref() {
                Some("s") => match raw.as_deref().and_then(|r| r.trim().parse::<usize>().ok()) {
                    Some(i) => Some(self.workbook.shared_strings.get(i).cloned().ok_or_else(
                        || {
                            ImportError::at(
                                format!("Row {row} refers to a missing shared string"),
                                row,
                            )
                        },
                    )?),
                    None => None,
                },
                Some("inlineStr") => inline.or(raw),
                Some("str") => raw,
                Some("b") => raw.map(|r| {
                    let r = r.trim();
                    if r == "1" || r.eq_ignore_ascii_case("true") {
                        "true"
                    } else {
                        "false"
                    }
                    .to_owned()
                }),
                Some("e") => return Ok(None),
                // ISO 8601 already (strict OOXML).
                Some("d") => raw,
                _ => raw
                    .as_deref()
                    .map(str::trim)
                    .filter(|r| !r.is_empty())
                    .map(|r| self.number(r, style)),
            };
        Ok(match text {
            Some(t) if !t.is_empty() => Some(t),
            _ if self.options.empty_as_null => None,
            _ => Some(String::new()),
        })
    }

    fn number(&self, raw: &str, style: usize) -> String {
        if let Some(kind) = self.workbook.date_kind(style)
            && let Ok(serial) = raw.parse::<f64>()
            && let Some(date) = excel_date(serial, kind, self.workbook.date1904)
        {
            return date;
        }
        plain_number(raw).unwrap_or_else(|| raw.to_owned())
    }
}

fn used_width(values: &[Option<String>]) -> usize {
    values
        .iter()
        .rposition(|v| v.as_deref().is_some_and(|s| !s.is_empty()))
        .map_or(0, |i| i + 1)
}

pub struct XlsxSource {
    workbook: Arc<Workbook>,
    sheet: Arc<Vec<u8>>,
    sheet_path: String,
    options: ImportOptions,
    columns: Vec<String>,
    sheets: Vec<String>,
    buffered: Option<Vec<ImportRow>>,
    consumed: bool,
    /// The sheet row the header was read from, when there is one.
    pub header_line: Option<u64>,
}

/// Opens a workbook's zip, or says it isn't one.
pub fn open_archive(path: &Path) -> Result<Archive> {
    let file = std::io::BufReader::new(File::open(path)?);
    zip::ZipArchive::new(file).map_err(|_| {
        ImportError::new(format!(
            "{} isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)",
            path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()
        ))
    })
}

/// Per sheet name, its XML.
pub type SheetParts = Vec<(String, Vec<u8>)>;

/// A workbook's parts and, per sheet name, its XML.
pub fn read_workbook(path: &Path) -> Result<(Workbook, SheetParts)> {
    let mut zip = open_archive(path)?;
    let workbook = Workbook::read(&mut zip)?;
    let mut sheets = Vec::new();
    for (name, part) in &workbook.sheets {
        let bytes = read_entry(&mut zip, part, true)?.unwrap_or_default();
        sheets.push((name.clone(), bytes));
    }
    Ok((workbook, sheets))
}

impl XlsxSource {
    pub fn open(
        path: &Path,
        requested: &ImportOptions,
        preview_rows: Option<usize>,
    ) -> Result<Self> {
        let mut zip = open_archive(path)?;
        let workbook = Workbook::read(&mut zip)?;
        let sheets: Vec<String> = workbook.sheets.iter().map(|s| s.0.clone()).collect();
        if sheets.is_empty() {
            return Err(ImportError::new("The workbook has no worksheets"));
        }
        let sheet = requested.sheet.clone().unwrap_or_else(|| sheets[0].clone());
        let Some((_, sheet_path)) = workbook.sheets.iter().find(|s| s.0 == sheet).cloned() else {
            let all: Vec<String> = sheets.iter().map(|s| format!("\"{s}\"")).collect();
            return Err(ImportError::new(format!(
                "There's no sheet named \"{sheet}\"; the workbook has {}",
                all.join(", ")
            )));
        };
        let bytes = read_entry(&mut zip, &sheet_path, true)?.unwrap_or_default();
        let options = ImportOptions {
            sheet: Some(sheet),
            ..requested.clone()
        };
        let mut header_line = None;
        let (header, width, buffered) = {
            let mut reader = SheetReader::new(&bytes, &sheet_path, &workbook, &options);
            if let Some(limit) = preview_rows {
                let wanted = limit + usize::from(requested.has_header);
                let mut rows = Vec::new();
                while rows.len() < wanted {
                    match reader.next_data_row()? {
                        Some(r) => rows.push(r),
                        None => break,
                    }
                }
                let header = if requested.has_header && !rows.is_empty() {
                    let h = rows.remove(0);
                    header_line = Some(h.line);
                    Some(h.values)
                } else {
                    None
                };
                let width = header.as_deref().map_or(0, used_width).max(
                    rows.iter()
                        .map(|r| used_width(&r.values))
                        .max()
                        .unwrap_or(0),
                );
                (header, width, Some(rows))
            } else {
                let mut first: Option<Vec<Option<String>>> = None;
                let mut widest = 0;
                while let Some(row) = reader.next_data_row()? {
                    if first.is_none() {
                        if requested.has_header {
                            header_line = Some(row.line);
                        }
                        first = Some(row.values.clone());
                    }
                    widest = widest.max(used_width(&row.values));
                }
                (first.filter(|_| requested.has_header), widest, None)
            }
        };
        let columns = match header {
            Some(mut h) => {
                if h.len() < width {
                    h.resize(width, None);
                }
                let mut names = normalize_column_names(&h);
                names.truncate(width);
                names
            }
            None => generated_column_names(width),
        };
        Ok(Self {
            workbook: Arc::new(workbook),
            sheet: Arc::new(bytes),
            sheet_path,
            options,
            columns,
            sheets,
            buffered,
            consumed: false,
            header_line,
        })
    }
}

impl ImportSource for XlsxSource {
    fn columns(&self) -> &[String] {
        &self.columns
    }

    fn options(&self) -> &ImportOptions {
        &self.options
    }

    fn sheets(&self) -> &[String] {
        &self.sheets
    }

    fn rows(&mut self) -> Result<Rows<'_>> {
        if self.consumed {
            return Err(ImportError::new("rows() can only be read once"));
        }
        self.consumed = true;
        let width = self.columns.len();
        if let Some(rows) = self.buffered.take() {
            return Ok(Box::new(
                rows.into_iter()
                    .map(move |r| fit_row(r.line, r.values, width, "Row")),
            ));
        }
        let mut reader =
            SheetReader::new(&self.sheet, &self.sheet_path, &self.workbook, &self.options);
        let mut skipped_header = !self.options.has_header;
        let mut failed = false;
        Ok(Box::new(std::iter::from_fn(move || {
            if failed {
                return None;
            }
            if !skipped_header {
                skipped_header = true;
                if let Err(e) = reader.next_data_row() {
                    failed = true;
                    return Some(Err(e));
                }
            }
            match reader.next_data_row() {
                Ok(Some(mut row)) => {
                    // The width scan saw every row, so nothing is longer than the columns beyond empty cells.
                    row.values.resize(width, None);
                    Some(Ok(row))
                }
                Ok(None) => None,
                Err(e) => {
                    failed = true;
                    Some(Err(e))
                }
            }
        })))
    }
}

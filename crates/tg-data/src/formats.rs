//! Rows as text in a [`CopyFormat`] (`ResultFormats.kt`), streamed so exports of large tables never hold them all in
//! memory: build a [`ResultWriter`], call [`write`](ResultWriter::write) per row, then
//! [`finish`](ResultWriter::finish).
//!
//! JSON numbers follow what the Kotlin core ends up printing, which is not what the column held: a numeric value that
//! `BigDecimal` reads is printed as a long, as an unsigned long, or else as the double nearest to it (`1.50` becomes
//! `1.5`, `1e3` becomes `1000.0`). A value no double can hold (`1e400`) makes the write fail, as it fails in Kotlin.

use std::collections::HashSet;
use std::io::{self, Write};
use tg_sql::model::jdbc_types as types;
use tg_sql::{Decimal, SqlDialect};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CopyFormat {
    Tsv,
    Csv,
    Json,
    Markdown,
    SqlInsert,
    SqlInList,
}

impl CopyFormat {
    pub const ALL: &[CopyFormat] = &[
        Self::Tsv,
        Self::Csv,
        Self::Json,
        Self::Markdown,
        Self::SqlInsert,
        Self::SqlInList,
    ];

    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Tsv => "Tsv",
            Self::Csv => "Csv",
            Self::Json => "Json",
            Self::Markdown => "Markdown",
            Self::SqlInsert => "SqlInsert",
            Self::SqlInList => "SqlInList",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Tsv => "Tab-separated",
            Self::Csv => "CSV",
            Self::Json => "JSON",
            Self::Markdown => "Markdown",
            Self::SqlInsert => "SQL INSERT",
            Self::SqlInList => "SQL IN list",
        }
    }

    pub fn extension(self) -> &'static str {
        match self {
            Self::Tsv => "tsv",
            Self::Csv => "csv",
            Self::Json => "json",
            Self::Markdown => "md",
            Self::SqlInsert | Self::SqlInList => "sql",
        }
    }
}

/// A result column as the writers need it: its name and JDBC type, which says whether its values are numbers.
///
/// The driver layer (M3) will own the shared result types; until then this mirrors the Kotlin `ResultColumn`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResultColumn {
    pub name: String,
    pub type_name: String,
    pub jdbc_type: i32,
}

impl ResultColumn {
    pub fn new(name: &str, type_name: &str, jdbc_type: i32) -> Self {
        Self {
            name: name.to_owned(),
            type_name: type_name.to_owned(),
            jdbc_type,
        }
    }

    pub fn is_numeric(&self) -> bool {
        [
            types::TINYINT,
            types::SMALLINT,
            types::INTEGER,
            types::BIGINT,
            types::REAL,
            types::FLOAT,
            types::DOUBLE,
            types::NUMERIC,
            types::DECIMAL,
        ]
        .contains(&self.jdbc_type)
    }
}

pub struct ResultWriter<'a, W: Write> {
    format: CopyFormat,
    columns: &'a [ResultColumn],
    out: W,
    dialect: SqlDialect,
    insert_prefix: String,
    rows: u64,
    lines: u64,
    seen: HashSet<String>,
}

impl<'a, W: Write> ResultWriter<'a, W> {
    /// Writes the format's opening (the header, `[`, `(`). `dialect` defaults to PostgreSQL and `table_name` to
    /// `table_name`; both only matter for the SQL formats.
    pub fn new(
        format: CopyFormat,
        columns: &'a [ResultColumn],
        out: W,
        include_header: bool,
        dialect: Option<SqlDialect>,
        table_name: Option<&str>,
    ) -> io::Result<Self> {
        let dialect = dialect.unwrap_or(SqlDialect::PostgreSQL);
        let names: Vec<String> = columns
            .iter()
            .map(|c| dialect.quote_identifier(&c.name))
            .collect();
        let insert_prefix = format!(
            "INSERT INTO {} ({}) VALUES (",
            table_name.unwrap_or("table_name"),
            names.join(", ")
        );
        let mut writer = Self {
            format,
            columns,
            out,
            dialect,
            insert_prefix,
            rows: 0,
            lines: 0,
            seen: HashSet::new(),
        };
        match format {
            CopyFormat::Tsv | CopyFormat::Csv => {
                if include_header {
                    let header: Vec<Option<&str>> =
                        columns.iter().map(|c| Some(c.name.as_str())).collect();
                    writer.line(&header)?;
                }
            }
            CopyFormat::Json => writer.out.write_all(b"[")?,
            CopyFormat::Markdown => {
                let names: Vec<String> = columns.iter().map(|c| markdown_cell(&c.name)).collect();
                let aligns: Vec<&str> = columns
                    .iter()
                    .map(|c| if c.is_numeric() { " ---: " } else { " --- " })
                    .collect();
                write!(
                    writer.out,
                    "| {} |\n|{}|",
                    names.join(" | "),
                    aligns.join("|")
                )?;
            }
            CopyFormat::SqlInsert => {}
            CopyFormat::SqlInList => writer.out.write_all(b"(")?,
        }
        Ok(writer)
    }

    pub fn row_count(&self) -> u64 {
        self.rows
    }

    pub fn write(&mut self, row: &[Option<String>]) -> io::Result<()> {
        match self.format {
            CopyFormat::Tsv | CopyFormat::Csv => {
                let cells: Vec<Option<&str>> = row.iter().map(|c| c.as_deref()).collect();
                self.line(&cells)?;
            }
            CopyFormat::Json => {
                let text = self.json_object(row)?;
                if self.rows > 0 {
                    self.out.write_all(b",")?;
                }
                write!(self.out, "\n  {}", text.replace('\n', "\n  "))?;
            }
            CopyFormat::Markdown => {
                let cells: Vec<String> = row
                    .iter()
                    .map(|c| markdown_cell(c.as_deref().unwrap_or("NULL")))
                    .collect();
                write!(self.out, "\n| {} |", cells.join(" | "))?;
            }
            CopyFormat::SqlInsert => {
                if self.rows > 0 {
                    self.out.write_all(b"\n")?;
                }
                let literals: Vec<String> = row
                    .iter()
                    .zip(self.columns)
                    .map(|(value, column)| {
                        self.dialect.literal(value.as_deref(), column.is_numeric())
                    })
                    .collect();
                write!(self.out, "{}{});", self.insert_prefix, literals.join(", "))?;
            }
            CopyFormat::SqlInList => {
                let literal = self
                    .dialect
                    .literal(row[0].as_deref(), self.columns[0].is_numeric());
                if !self.seen.insert(literal.clone()) {
                    return Ok(());
                }
                if self.seen.len() > 1 {
                    self.out.write_all(b", ")?;
                }
                self.out.write_all(literal.as_bytes())?;
            }
        }
        self.rows += 1;
        Ok(())
    }

    /// Writes the closing and flushes, returning the output.
    pub fn finish(mut self) -> io::Result<W> {
        match self.format {
            CopyFormat::Json => self
                .out
                .write_all(if self.rows == 0 { b"]" } else { b"\n]" })?,
            CopyFormat::SqlInList => self.out.write_all(b")")?,
            _ => {}
        }
        self.out.flush()?;
        Ok(self.out)
    }

    /// TSV keeps cells verbatim (as spreadsheets expect) unless they contain separators or newlines; CSV follows
    /// RFC 4180.
    fn line(&mut self, cells: &[Option<&str>]) -> io::Result<()> {
        let separator = if self.format == CopyFormat::Tsv {
            '\t'
        } else {
            ','
        };
        if self.lines > 0 {
            self.out.write_all(b"\n")?;
        }
        self.lines += 1;
        let cells: Vec<String> = cells
            .iter()
            .map(|cell| {
                let text = cell.unwrap_or("");
                if text
                    .chars()
                    .any(|c| matches!(c, '"' | '\n' | '\r') || c == separator)
                {
                    format!("\"{}\"", text.replace('"', "\"\""))
                } else {
                    text.to_owned()
                }
            })
            .collect();
        self.out
            .write_all(cells.join(&separator.to_string()).as_bytes())
    }

    /// One row as kotlinx's pretty printer writes a `JsonObject` (four-space indent). Columns with the same name
    /// collapse into one, in the first one's place and with the last one's value.
    fn json_object(&self, row: &[Option<String>]) -> io::Result<String> {
        let mut entries: Vec<(&str, String)> = Vec::new();
        for (column, value) in self.columns.iter().zip(row) {
            let json = json_value(value.as_deref(), column)?;
            match entries.iter_mut().find(|(name, _)| *name == column.name) {
                Some(entry) => entry.1 = json,
                None => entries.push((&column.name, json)),
            }
        }
        if entries.is_empty() {
            return Ok("{}".to_owned());
        }
        let body: Vec<String> = entries
            .iter()
            .map(|(name, json)| format!("    {}: {json}", quoted(name)))
            .collect();
        Ok(format!("{{\n{}\n}}", body.join(",\n")))
    }
}

fn markdown_cell(value: &str) -> String {
    value.replace('|', "\\|").replace('\n', " ")
}

fn json_value(value: Option<&str>, column: &ResultColumn) -> io::Result<String> {
    let Some(value) = value else {
        return Ok("null".to_owned());
    };
    if column.is_numeric()
        && let Some(decimal) = Decimal::parse(value)
    {
        return json_number(&decimal);
    }
    Ok(quoted(value))
}

/// How kotlinx prints `JsonPrimitive(BigDecimal)`: its `toString()` re-read as a long, an unsigned long, or a double.
fn json_number(decimal: &Decimal) -> io::Result<String> {
    let content = decimal.to_java_string();
    if let Ok(long) = content.parse::<i64>() {
        return Ok(long.to_string());
    }
    if let Ok(unsigned) = content.parse::<u64>() {
        return Ok(unsigned.to_string());
    }
    match content.parse::<f64>() {
        Ok(double) if double.is_finite() => Ok(java_double(double)),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{content} is not a JSON number a double can hold"),
        )),
    }
}

/// `Double.toString`: shortest digits that read back the same; plain between 1e-3 and 1e7, else `d.dddE±n`.
fn java_double(value: f64) -> String {
    if value == 0.0 {
        return if value.is_sign_negative() {
            "-0.0"
        } else {
            "0.0"
        }
        .to_owned();
    }
    let sign = if value < 0.0 { "-" } else { "" };
    let mut scientific = format!("{:e}", value.abs());
    if !scientific.contains('.') {
        // One digit is enough to read it back, but Java prints two, the nearer pair to the exact value: 4.9E-324 for
        // the smallest double, where the shortest is 5E-324.
        let two = format!("{:.1e}", value.abs());
        if !two.contains(".0e") && two.parse::<f64>() == Ok(value.abs()) {
            scientific = two;
        }
    }
    let (mantissa, exponent) = scientific.split_once('e').unwrap();
    let exponent: i32 = exponent.parse().unwrap();
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    let abs = value.abs();
    if (1e-3..1e7).contains(&abs) {
        if exponent >= 0 {
            let split = exponent as usize + 1;
            let padded = format!("{digits:0<split$}");
            let (int, frac) = padded.split_at(split);
            format!("{sign}{int}.{}", if frac.is_empty() { "0" } else { frac })
        } else {
            format!("{sign}0.{}{digits}", "0".repeat((-exponent - 1) as usize))
        }
    } else {
        let (first, rest) = digits.split_at(1);
        format!(
            "{sign}{first}.{}E{exponent}",
            if rest.is_empty() { "0" } else { rest }
        )
    }
}

/// kotlinx's `printQuoted`: control characters, quote and backslash are escaped; everything else is as it is.
fn quoted(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Renders a block of cells as text, e.g. for the clipboard.
pub struct ResultFormats;

impl ResultFormats {
    pub fn format(
        format: CopyFormat,
        columns: &[ResultColumn],
        rows: &[Vec<Option<String>>],
        include_header: bool,
        dialect: Option<SqlDialect>,
        table_name: Option<&str>,
    ) -> io::Result<String> {
        let mut writer = ResultWriter::new(
            format,
            columns,
            Vec::new(),
            include_header,
            dialect,
            table_name,
        )?;
        for row in rows {
            writer.write(row)?;
        }
        Ok(String::from_utf8(writer.finish()?).expect("the writers only write text"))
    }
}

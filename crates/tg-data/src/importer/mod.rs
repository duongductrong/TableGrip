//! Reading a user's file as rows for "Import data into a table" (`importer/ImportReader.kt`): CSV/TSV, JSON/NDJSON and
//! XLSX, with the encoding, delimiter and sheet detected when not given.

pub mod charset;
mod csv;
mod json;
pub mod xlsx;

pub use charset::Charset;
pub use csv::{CsvParser, detect_charset};
pub use json::JsonValue;

use crate::infer::{InferredType, TypeInference};
use std::fmt;
use std::path::Path;

/// File formats rows can be imported from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImportFormat {
    Csv,
    Json,
    Xlsx,
}

impl ImportFormat {
    pub fn label(self) -> &'static str {
        match self {
            Self::Csv => "CSV / TSV",
            Self::Json => "JSON / NDJSON",
            Self::Xlsx => "Excel workbook",
        }
    }

    pub fn extensions(self) -> &'static [&'static str] {
        match self {
            Self::Csv => &["csv", "tsv", "txt"],
            Self::Json => &["json", "ndjson", "jsonl"],
            Self::Xlsx => &["xlsx", "xlsm"],
        }
    }

    /// The format the file's extension implies, or None when it isn't one TableGrip reads (e.g. legacy `.xls`).
    pub fn detect(path: &Path) -> Option<Self> {
        let extension = path.extension()?.to_str()?.to_lowercase();
        [Self::Csv, Self::Json, Self::Xlsx]
            .into_iter()
            .find(|f| f.extensions().contains(&extension.as_str()))
    }
}

/// How to read a file. None fields are detected when the file is opened; [`ImportSource::options`] has them filled
/// in so the UI can show (and let the user override) what was picked.
#[derive(Clone, Debug, PartialEq)]
pub struct ImportOptions {
    pub format: ImportFormat,
    /// CSV: None = detect from the first records among `,` `;` tab and `|`.
    pub delimiter: Option<char>,
    pub quote: char,
    /// CSV and XLSX; when false columns are named column_1, column_2…
    pub has_header: bool,
    /// CSV and JSON: None = detect (BOM for UTF-8/UTF-16, else UTF-8 if it decodes cleanly, else windows-1252).
    pub charset: Option<Charset>,
    /// XLSX: None = first sheet.
    pub sheet: Option<String>,
    /// Empty unquoted CSV field / empty cell -> None. Quoted `""` and JSON `""` stay empty strings.
    pub empty_as_null: bool,
    /// Lines (CSV) or sheet rows (XLSX) to skip before the header, e.g. a report title.
    pub skip_rows: u64,
}

impl ImportOptions {
    pub fn new(format: ImportFormat) -> Self {
        Self {
            format,
            delimiter: None,
            quote: '"',
            has_header: true,
            charset: None,
            sheet: None,
            empty_as_null: true,
            skip_rows: 0,
        }
    }

    fn validate(&self) -> Result<()> {
        if self.delimiter == Some(self.quote) {
            return Err(ImportError::new("The delimiter and the quote must differ"));
        }
        if [self.delimiter, Some(self.quote)]
            .iter()
            .any(|c| matches!(c, Some('\n' | '\r')))
        {
            return Err(ImportError::new(
                "Line breaks can't be delimiters or quotes",
            ));
        }
        Ok(())
    }
}

/// One data row; `line` is the 1-based position in the source for error messages (CSV physical line where the
/// record starts, XLSX row number, JSON record index).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportRow {
    pub line: u64,
    pub values: Vec<Option<String>>,
}

/// A file that can't be read as asked; `line` (as in [`ImportRow::line`]) points at the offending spot when known.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportError {
    pub message: String,
    pub line: Option<u64>,
}

impl ImportError {
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            line: None,
        }
    }

    pub fn at(message: impl Into<String>, line: u64) -> Self {
        Self {
            message: message.into(),
            line: Some(line),
        }
    }
}

impl fmt::Display for ImportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ImportError {}

impl From<std::io::Error> for ImportError {
    fn from(e: std::io::Error) -> Self {
        Self::new(format!("Couldn't read the file: {e}"))
    }
}

pub type Result<T> = std::result::Result<T, ImportError>;

/// The rows of a source, read as the iterator is advanced.
pub type Rows<'a> = Box<dyn Iterator<Item = Result<ImportRow>> + 'a>;

/// An open file. Rows are read lazily; rows shorter than the columns are padded with None, longer ones are an error
/// naming the line.
pub trait ImportSource {
    /// Header names, trimmed, de-duplicated case-insensitively (name, name_2), blanks -> column_N.
    fn columns(&self) -> &[String];

    /// The options with detected delimiter/charset/sheet filled in.
    fn options(&self) -> &ImportOptions;

    /// XLSX sheet names in workbook order, else empty.
    fn sheets(&self) -> &[String] {
        &[]
    }

    /// The data rows. Single pass: a second call is an error.
    fn rows(&mut self) -> Result<Rows<'_>>;
}

/// What the preview grid shows: the first rows, and per column the type [`TypeInference`] guessed from them.
#[derive(Clone, Debug)]
pub struct ImportPreview {
    pub columns: Vec<String>,
    pub rows: Vec<ImportRow>,
    pub types: Vec<InferredType>,
    pub options: ImportOptions,
    pub sheets: Vec<String>,
}

/// Opens `file` for reading all its rows. CSV reads up to a few hundred KB to detect the encoding and delimiter; XLSX
/// scans the sheet once to find its width; a JSON array or NDJSON is scanned once for the union of keys.
pub fn open(file: &Path, options: &ImportOptions) -> Result<Box<dyn ImportSource>> {
    open_source(file, options, None)
}

/// Up to `limit` rows plus the inferred type of each column (from those rows), for the preview grid.
pub fn preview(file: &Path, options: &ImportOptions, limit: usize) -> Result<ImportPreview> {
    let mut source = open_source(file, options, Some(limit))?;
    let rows: Vec<ImportRow> = source.rows()?.take(limit).collect::<Result<_>>()?;
    let types = (0..source.columns().len())
        .map(|i| TypeInference::infer(rows.iter().map(|r| r.values[i].as_deref())))
        .collect();
    Ok(ImportPreview {
        columns: source.columns().to_vec(),
        rows,
        types,
        options: source.options().clone(),
        sheets: source.sheets().to_vec(),
    })
}

fn open_source(
    file: &Path,
    options: &ImportOptions,
    preview_rows: Option<usize>,
) -> Result<Box<dyn ImportSource>> {
    options.validate()?;
    if !file.is_file() {
        return Err(ImportError::new(format!(
            "{} doesn't exist or isn't a file",
            file.display()
        )));
    }
    if std::fs::File::open(file).is_err() {
        return Err(ImportError::new(format!(
            "{} can't be read; check its permissions",
            file.display()
        )));
    }
    Ok(match options.format {
        ImportFormat::Csv => Box::new(csv::CsvSource::open(file, options)?),
        ImportFormat::Json => Box::new(json::JsonSource::open(file, options, preview_rows)?),
        ImportFormat::Xlsx => Box::new(xlsx::XlsxSource::open(file, options, preview_rows)?),
    })
}

/// Trims header names, names blanks column_N (1-based position), and suffixes repeats _2, _3… (case-insensitively,
/// as SQL would).
pub fn normalize_column_names(raw: &[Option<String>]) -> Vec<String> {
    let mut used = std::collections::HashSet::new();
    raw.iter()
        .enumerate()
        .map(|(i, name)| {
            let trimmed = name.as_deref().map(str::trim).unwrap_or("");
            let base = if trimmed.is_empty() {
                format!("column_{}", i + 1)
            } else {
                trimmed.to_owned()
            };
            let mut unique = base.clone();
            let mut suffix = 2;
            while !used.insert(unique.to_lowercase()) {
                unique = format!("{base}_{suffix}");
                suffix += 1;
            }
            unique
        })
        .collect()
}

pub fn generated_column_names(count: usize) -> Vec<String> {
    (1..=count).map(|i| format!("column_{i}")).collect()
}

/// Pads `values` to `width` with None. Extra values are an error, except blank ones: a trailing delimiter (`a,b,`)
/// is too common in exported files to reject.
pub fn fit_row(
    line: u64,
    mut values: Vec<Option<String>>,
    width: usize,
    unit: &str,
) -> Result<ImportRow> {
    if values.len() > width {
        if values[width..]
            .iter()
            .all(|v| v.as_deref().is_none_or(|s| s.trim().is_empty()))
        {
            values.truncate(width);
        } else {
            return Err(ImportError::at(
                format!(
                    "{unit} {line} has {} fields but there are only {width} columns",
                    values.len()
                ),
                line,
            ));
        }
    }
    values.resize(width, None);
    Ok(ImportRow { line, values })
}

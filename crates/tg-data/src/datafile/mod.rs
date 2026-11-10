//! Data files TableGrip opens as connections (`datafile/*.kt`): CSV, Excel and JSON, each read into a SQLite working
//! copy and written back in the file's own layout when the working copy changes.

mod csv;
mod json;
pub mod store;
pub mod sync;
pub mod working_copy;
pub mod xlsx;

pub use store::{DataFileConnection, DataFileStore};
pub use sync::{DataFileState, DataFileSync, HeldReason};

use std::collections::BTreeSet;
use std::fmt;
use std::path::Path;
use tg_core::connection::DataFileOptions;
use tg_sql::model::DatabaseType;

/// A data file that can't be read or written as asked.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DataFileError(pub String);

impl fmt::Display for DataFileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for DataFileError {}

impl From<crate::importer::ImportError> for DataFileError {
    fn from(e: crate::importer::ImportError) -> Self {
        Self(e.message)
    }
}

impl From<std::io::Error> for DataFileError {
    fn from(e: std::io::Error) -> Self {
        Self(e.to_string())
    }
}

impl From<rusqlite::Error> for DataFileError {
    fn from(e: rusqlite::Error) -> Self {
        Self(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, DataFileError>;

/// A table as read from a file: unique (case-insensitively), non-blank column names.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableInfo {
    pub name: String,
    pub columns: Vec<String>,
}

/// Rows as read from a file: text as the user would see it, None for an empty cell. Single pass.
pub type SourceRows<'a> = Box<dyn Iterator<Item = Result<Vec<Option<String>>>> + 'a>;

/// What a column holds, decided by the working copy from every value in it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ColumnKind {
    /// Every value is an integer written the way [`DataValues::integer`] writes it back.
    Integer,
    /// Every value is a number written the way [`DataValues::real`] writes it back.
    Real,
    Text,
}

impl ColumnKind {
    pub fn sql_type(self) -> &'static str {
        match self {
            Self::Integer => "INTEGER",
            Self::Real => "REAL",
            Self::Text => "TEXT",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct OutputColumn {
    pub name: String,
    pub kind: ColumnKind,
}

/// A value from the working copy.
#[derive(Clone, Debug, PartialEq)]
pub enum CellValue {
    Null,
    Integer(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// The rows of a table to write, read when called.
pub type RowsFn<'a> = Box<dyn Fn() -> Result<Vec<Vec<CellValue>>> + 'a>;

/// A table to write. `source` is the name it had in the file when it came from there (renamed tables keep pointing at
/// their sheet); None for a table that's new. Unchanged tables are passed too, so a format that rewrites the whole
/// file has them, but `changed` false lets one that patches parts leave theirs alone.
pub struct OutputTable<'a> {
    pub name: String,
    pub source: Option<String>,
    pub changed: bool,
    pub columns: Vec<OutputColumn>,
    pub rows: RowsFn<'a>,
}

/// One read of a data file: its tables, and how to write them back in the file's own layout.
pub trait DataFileDocument: Send {
    /// In file order.
    fn tables(&self) -> Vec<TableInfo>;

    /// Table `index`'s rows. Each table's rows can be read once.
    fn rows(&mut self, index: usize) -> Result<SourceRows<'_>>;

    /// Whether the file can hold more than one table (an Excel workbook's sheets); a CSV holds exactly one.
    fn multi_table(&self) -> bool;

    /// Why changes can't be written back to this file, which then opens read-only; None when they can.
    fn read_only_reason(&self) -> Option<String> {
        None
    }

    /// Things the user should know before editing, e.g. that saving a sheet replaces its formulas with their values.
    fn notes(&self) -> Vec<String> {
        Vec::new()
    }

    /// Why writing changes to `tables` (names as read from this document) loses something the file has, so the user
    /// is asked first; None when writing them loses nothing.
    fn confirm_before_writing(&self, _tables: &BTreeSet<String>) -> Option<String> {
        None
    }

    /// Writes `tables` (every table the file should now hold, in order) to `target`, a fresh file next to the
    /// original. `removed` names the tables of this document that are gone. A single-table document gets exactly one.
    fn write(
        &mut self,
        target: &Path,
        tables: &[OutputTable<'_>],
        removed: &BTreeSet<String>,
    ) -> Result<()>;
}

/// The formats TableGrip opens.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DataFileFormat {
    Csv,
    Xlsx,
    Json,
}

impl DataFileFormat {
    pub const ALL: [Self; 3] = [Self::Csv, Self::Xlsx, Self::Json];

    /// The profile type that opens this format.
    pub fn db_type(self) -> DatabaseType {
        match self {
            Self::Csv => DatabaseType::CSV_FILE,
            Self::Xlsx => DatabaseType::EXCEL_FILE,
            Self::Json => DatabaseType::JSON_FILE,
        }
    }

    /// Lower-case extensions this format claims, without the dot.
    pub fn extensions(self) -> &'static [&'static str] {
        match self {
            Self::Csv => &["csv", "tsv", "tab", "txt"],
            Self::Xlsx => &["xlsx", "xlsm"],
            Self::Json => &["json", "ndjson", "jsonl"],
        }
    }

    pub fn of(db_type: &DatabaseType) -> Result<Self> {
        Self::ALL
            .into_iter()
            .find(|f| f.db_type().id == db_type.id)
            .ok_or_else(|| DataFileError(format!("{} isn't a data file", db_type.display_name)))
    }

    /// The format the path's extension names, or None.
    pub fn detect(path: &Path) -> Option<Self> {
        let extension = path.extension()?.to_str()?.to_lowercase();
        Self::ALL
            .into_iter()
            .find(|f| f.extensions().contains(&extension.as_str()))
    }

    /// Reads `path`. The document remembers how the file is laid out so that it can write it back the same way.
    pub fn open(self, path: &Path, options: &DataFileOptions) -> Result<Box<dyn DataFileDocument>> {
        match self {
            Self::Csv => csv::open(path, options),
            Self::Xlsx => xlsx::open(path, options),
            Self::Json => json::open(path),
        }
    }
}

/// The file's name without its extension, or `data`.
pub(crate) fn table_name(path: &Path) -> String {
    path.file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "data".into())
}

/// The one way numbers are written back. A column is typed as a number only when every value in the file already
/// reads exactly like this, so loading and saving an untouched file never changes a character of it ("007", "1.50"
/// and "1e5" stay text).
pub struct DataValues;

impl DataValues {
    pub fn integer(value: i64) -> String {
        value.to_string()
    }

    pub fn real(value: f64) -> String {
        if !value.is_finite() {
            return crate::formats::java_double(value);
        }
        if value == value.floor() && value.abs() < 1e15 {
            return (value as i64).to_string();
        }
        // BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        let decimal = tg_sql::Decimal::parse(&crate::formats::java_double(value))
            .map(|d| d.to_plain_string())
            .unwrap_or_default();
        if decimal.contains('.') {
            decimal
                .trim_end_matches('0')
                .trim_end_matches('.')
                .to_owned()
        } else {
            decimal
        }
    }

    pub fn is_integer(text: &str) -> bool {
        text.parse::<i64>().is_ok_and(|v| Self::integer(v) == text)
    }

    pub fn is_real(text: &str) -> bool {
        let trimmed = text.trim();
        // Java's parseDouble reads hex floats and a trailing d/f; none of those survive the round trip anyway.
        trimmed
            .parse::<f64>()
            .is_ok_and(|v| v.is_finite() && Self::real(v) == text)
    }

    /// A value from the working copy as text, the way the file holds it.
    pub fn text(value: &CellValue) -> Option<String> {
        match value {
            CellValue::Null => None,
            CellValue::Integer(i) => Some(Self::integer(*i)),
            CellValue::Real(f) => Some(Self::real(*f)),
            CellValue::Text(s) => Some(s.clone()),
            CellValue::Blob(b) => Some(b.iter().map(|x| format!("{x:02x}")).collect()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::DataValues;

    #[test]
    fn numbers_only_become_numbers_when_they_read_back_unchanged() {
        for text in ["0", "-12", "9007199254740993"] {
            assert!(DataValues::is_integer(text), "{text}");
        }
        for text in ["007", "+5", "1.0", "1e3", " 1", ""] {
            assert!(!DataValues::is_integer(text), "{text}");
        }
        for text in ["1.5", "-0.25", "3", "0.001", "123456.789"] {
            assert!(DataValues::is_real(text), "{text}");
        }
        for text in ["1.50", "1e5", ".5", "NaN", "Infinity", "1.0"] {
            assert!(!DataValues::is_real(text), "{text}");
        }
        assert_eq!(DataValues::real(2.0), "2");
        assert_eq!(DataValues::real(0.1 + 0.2), "0.30000000000000004");
        assert_eq!(DataValues::real(1e20), "100000000000000000000");
        assert_eq!(DataValues::real(1.5e-7), "0.00000015");
    }
}

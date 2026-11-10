//! What statements return (`driver/QueryResult.kt`). Cells are text, as JDBC's `getString` gives them (decision A1 in
//! the S3 log): the server's own rendering of each value, binary values as [`crate::binary_text`] previews.

use std::time::Duration;
use tg_sql::SortColumn;
use tg_sql::filter::{SqlFilter, SqlProjection};
pub use tg_sql::model::ResultColumn;
use tg_sql::model::TableRef;

/// One value: None is SQL NULL.
pub type Cell = Option<String>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rows {
    pub columns: Vec<ResultColumn>,
    pub rows: Vec<Vec<Cell>>,
    /// More rows existed than were read.
    pub truncated: bool,
    pub elapsed_ms: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum QueryResult {
    Rows(Rows),
    Update { affected_rows: u64, elapsed_ms: u64 },
}

impl QueryResult {
    pub fn elapsed_ms(&self) -> u64 {
        match self {
            Self::Rows(r) => r.elapsed_ms,
            Self::Update { elapsed_ms, .. } => *elapsed_ms,
        }
    }

    pub fn into_rows(self) -> Option<Rows> {
        match self {
            Self::Rows(r) => Some(r),
            Self::Update { .. } => None,
        }
    }
}

/// How a console statement runs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExecOptions {
    /// Rows read at most; one more is read to set `truncated`.
    pub max_rows: usize,
    /// None: no limit.
    pub timeout: Option<Duration>,
}

impl Default for ExecOptions {
    fn default() -> Self {
        Self {
            max_rows: 1_000,
            timeout: None,
        }
    }
}

/// A page of a table for the table viewer (`fetchPage`'s arguments).
#[derive(Clone, Debug)]
pub struct PageRequest<'a> {
    pub table: &'a TableRef,
    pub filter: Option<&'a SqlFilter>,
    pub order_by: &'a [SortColumn],
    pub limit: usize,
    pub offset: u64,
    pub timeout: Option<Duration>,
    /// The columns to read; all of them when None.
    pub projection: Option<&'a SqlProjection>,
}

impl<'a> PageRequest<'a> {
    pub fn new(table: &'a TableRef) -> Self {
        Self {
            table,
            filter: None,
            order_by: &[],
            limit: 100,
            offset: 0,
            timeout: None,
            projection: None,
        }
    }
}

/// The rows a table export reads (`exportTable`'s and `countRows`' arguments besides the sink).
#[derive(Clone, Copy, Debug)]
pub struct TableQuery<'a> {
    pub table: &'a TableRef,
    pub filter: Option<&'a SqlFilter>,
    pub order_by: &'a [SortColumn],
    pub projection: Option<&'a SqlProjection>,
}

impl<'a> TableQuery<'a> {
    pub fn new(table: &'a TableRef) -> Self {
        Self {
            table,
            filter: None,
            order_by: &[],
            projection: None,
        }
    }
}

/// Where an export writes. Binary cells arrive as `<binary N bytes>` placeholders, as in Kotlin.
pub trait RowSink: Send {
    fn begin(&mut self, columns: &[ResultColumn]) -> std::io::Result<()>;
    fn write(&mut self, row: &[Cell]) -> std::io::Result<()>;
    fn finish(&mut self) -> std::io::Result<()>;
}

/// Progress callbacks run on the driver's task; keep them cheap.
pub type Progress<'a> = &'a (dyn Fn(u64) + Send + Sync);

/// Rows an export reads between progress calls, as Kotlin's `streamTo`.
pub const PROGRESS_EVERY: u64 = 5_000;

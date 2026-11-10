//! Data in and out of the app: CSV, JSON and XLSX files and their working copies, the import wizard's readers
//! and type inference, result export and clipboard tables, connection importers and test data.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/{datafile,importer,imports,export,testdata,tools}` (milestone
//! M5 of docs/plans/rust-migration.md). So far only the parts without I/O are here (milestone M1): type inference,
//! the result formats and clipboard tables. They are held to the Kotlin output through `conformance/core` (subjects
//! `data-*`).

pub mod clipboard;
pub mod datafile;
pub mod formats;
pub mod importer;
pub mod imports;
pub mod infer;
mod kotlinx_json;

pub use clipboard::ClipboardTable;
pub use formats::{CopyFormat, ResultColumn, ResultFormats, ResultWriter};
pub use infer::{InferredKind, InferredType, TypeInference};

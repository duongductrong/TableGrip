//! SQL without I/O: lexer, splitter, classifier, formatter, keywords, parameters, per-engine dialects, filters and
//! projections, DDL scripts, schema diffs and completion.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/{sql,dialect,filter,ddl,schema,completion}` (milestone M1 of
//! docs/plans/rust-migration.md), held to the Kotlin output through `conformance/core`. Offsets are byte offsets into
//! the `&str` they index; the golden tests convert them to the UTF-16 offsets Kotlin records.

pub mod classifier;
pub mod completion;
pub mod ddl;
pub mod decimal;
pub mod dialect;
pub mod edit;
pub mod filter;
pub mod formatter;
pub mod keywords;
pub mod kt;
pub mod lexer;
pub mod model;
pub mod parameters;
pub mod schema;
pub mod splitter;
pub mod syntax;

pub use classifier::{SqlClassifier, StatementInfo, StatementKind};
pub use decimal::Decimal;
pub use dialect::{SortColumn, SortDirection, SqlDialect, TimeBucket};
pub use formatter::{KeywordCase, SqlFormatter};
pub use lexer::{SqlLexer, Token, TokenType};
pub use model::DatabaseType;
pub use parameters::SqlParameters;
pub use splitter::{SqlSplitter, SqlStatement};
pub use syntax::SqlSyntax;

//! SQL without I/O: lexer, splitter, classifier, formatter, keywords, parameters, per-engine dialects, filters and
//! projections, DDL scripts, schema diffs and completion.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/{sql,dialect,filter,ddl,schema,completion}` (milestone M1 of
//! docs/plans/rust-migration.md), held to the Kotlin output through `conformance/core`.

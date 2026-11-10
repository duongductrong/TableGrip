//! Charts without I/O: specs, suggestions, column profiles, the points to draw and the server-side aggregate query.
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/chart` (milestone M1 of docs/plans/rust-migration.md), held to
//! the Kotlin output through `conformance/core` (subjects `chart-*`).

pub mod data;
pub mod profile;
pub mod query;
pub mod spec;
pub mod suggest;

pub use data::{ChartData, ChartFrame, ChartPoint, ChartX};
pub use profile::{ChartColumn, ColumnKind, ColumnProfile, ColumnProfiler, values};
pub use query::{ChartAggregate, aggregate_query};
pub use spec::{Aggregate, ChartSpec, ChartType, Measure};
pub use tg_sql::TimeBucket;

//! Database connections: the relational, key-value and document traits, query results and sessions, introspection,
//! and one engine per feature. Ported from `core/src/main/kotlin/dev/tablegrip/core/{driver,metadata,edit}` and
//! `engines/*` (milestones M3 and M4 of docs/plans/rust-migration.md; the design is in the S3 log there).
//!
//! # Using it
//!
//! [`connect`] opens a profile (through its SSH tunnel, if any) and returns a [`Connection`]: one trait object per
//! paradigm, as Kotlin's `sealed interface DatabaseConnection`. Everything is `async` on tokio. To cancel, drop the
//! future (e.g. `CancellationToken::run_until_cancelled`): an engine cancels the statement on the server when that
//! happens. Cells are text (`Option<String>`), the server's own rendering, with binary values as
//! [`binary_text`] previews.
//!
//! # Adding an engine
//!
//! A relational engine implements two traits from [`sql`] and gets the whole [`RelationalConnection`] from
//! [`relational::SqlDatabase`] (pages, export, counts, edits, scripts, inserts, snapshots, the console session):
//!
//! 1. Add a feature named after the engine in `Cargo.toml` (`mysql = ["dep:mysql_async"]`), add it to `default`,
//!    and pin the client crate in the root `[workspace.dependencies]`.
//! 2. Create `src/engine/<name>/mod.rs` behind `#[cfg(feature = "<name>")]` in [`engine`]. Look at
//!    `engine/sqlite` or `engine/duckdb` (a blocking client on a worker thread), `engine/postgres` (an async client
//!    and a pool), `engine/mysql` and `engine/mssql` (a client owned by a task per statement, so a dropped future can
//!    cancel on the server and keep the session), or `engine/clickhouse` (HTTP).
//! 3. Implement [`sql::SqlConn`] for one connection:
//!    - `run` for console statements (any kind, honours `max_rows`), and `query`, `execute` and `stream` for the SQL
//!      the core generates. Bind [`sql::Param`]s the way the Kotlin dialect's `bind` did, and give cells as the
//!      engine's text (JDBC `getString`), binary as [`binary_text::encode`] (grid) or [`binary_text::placeholder`]
//!      (export).
//!    - The catalog methods (`namespaces`, `tables`, `details`, `columns_of`, `relations`, `structure`). Kotlin read
//!      these from JDBC `DatabaseMetaData`, so `type_name`, `jdbc_type`, `size` and `scale` must match what that
//!      engine's JDBC driver reported; [`introspect`] has the shared assembly (index grouping, foreign keys, sorting).
//!      Record a golden with `IntrospectionGoldenTest` (Kotlin) and hold the engine to it (`tests/introspection.rs`).
//!    - Dropping any future mid-statement cancels it on the server; `discard` marks the connection to be closed
//!      rather than pooled. Map engine errors to [`QueryError`] with SQLSTATE, vendor code and position.
//!    - `begin`/`commit`/`rollback` default to `BEGIN`/`COMMIT`/`ROLLBACK`; override where the engine differs. The
//!      shared insert path also sends `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`/`RELEASE SAVEPOINT`; translate them in
//!      `execute` where the engine spells them otherwise (SQL Server) or has none (DuckDB, ClickHouse).
//!    - Shared helpers: `engine::inline` writes parameters as literals with the engine's quoting, `engine::java`
//!      prints numbers and timestamps as Java did, and [`structure`] has `GenericStructure`'s rebuilt DDL. An engine
//!      using rustls calls `engine::use_ring` first (two crypto providers are compiled in; see the S4 log).
//! 4. Implement [`sql::ConnSource`]: `pooled()` from a fixed pool of 4, `dedicated()` for consoles and the activity
//!    monitor, and optionally `backup_into` and `activity_monitor` (an [`activity::ActivityReader`] over a dedicated
//!    connection).
//! 5. Add a match arm in [`engine::connect_relational`] (and `engine::identify` if a family has look-alikes). Open
//!    the tunnel with [`engine::open_tunnel`] and connect to the address it returns.
//! 6. Add `tests/<name>.rs` that runs `contract::run` (in `tests/contract/mod.rs`) against a server named by
//!    `TG_IT_<NAME>=host:port`, skipping when it's unset, and note the Docker command in the file.
//!
//! A key-value or document engine implements [`KeyValueConnection`] or [`DocumentConnection`] directly; `connect`
//! wraps it in the read-only guard ([`keyvalue::Guarded`], [`document::Guarded`]).

pub mod activity;
pub mod binary_text;
pub mod document;
pub mod engine;
pub mod error;
pub mod introspect;
pub mod keyvalue;
pub mod relational;
pub mod result;
pub mod sql;
pub mod structure;

pub use document::DocumentConnection;
pub use engine::{Connection, Secrets, TestOutcome, connect, test};
pub use error::{DriverError, QueryError, Result};
pub use keyvalue::KeyValueConnection;
pub use relational::{QuerySession, RelationalConnection};
pub use result::{
    Cell, ExecOptions, PageRequest, QueryResult, ResultColumn, RowSink, Rows, TableQuery,
};

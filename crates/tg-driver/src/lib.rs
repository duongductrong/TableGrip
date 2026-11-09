//! Database connections: the relational, key-value and document traits, query results and sessions,
//! introspection, and one engine per feature (PostgreSQL, MySQL/MariaDB, SQLite, SQL Server, ClickHouse, DuckDB,
//! Redis, MongoDB, etcd).
//!
//! Ported from `core/src/main/kotlin/dev/tablegrip/core/{driver,metadata}` and `engines/*` (milestones M3 and M4 of
//! docs/plans/rust-migration.md).

---
title: Supported databases
description: Every database TableGrip connects to, grouped by family, with what each one supports.
---

TableGrip speaks to relational databases through JDBC and to Redis, MongoDB and etcd through their own clients. Hosted
services that are compatible with a family (Neon, Supabase, PlanetScale, Azure SQL…) have their own presets, which set
sensible defaults such as TLS.

In all, TableGrip connects to **34 databases** and opens **3 kinds of data file** out of the box, and to any other
database you have a JDBC driver for.

## At a glance

| Family | Databases | Good to know |
| --- | --- | --- |
| **PostgreSQL** | PostgreSQL · CockroachDB · TimescaleDB · YugabyteDB · Neon · Supabase · Amazon Redshift | Server activity with lock trees, backup and restore with `pg_dump`, all-or-nothing DDL. Hosted presets turn on TLS. Redshift connects with the PostgreSQL driver, without server activity or `pg_dump`. |
| **MySQL** | MySQL · MariaDB · TiDB · PlanetScale · OceanBase | Server activity, backup and restore with `mysqldump`. Uses MariaDB Connector/J, or your own MySQL Connector/J. OceanBase uses its own driver, OceanBase Connector/J. |
| **SQL Server & Oracle** | SQL Server · Azure SQL · Oracle · Firebird | All-or-nothing DDL on SQL Server. Azure SQL comes with encryption on. |
| **Analytics** | ClickHouse · DuckDB · Trino · Presto · Apache Doris · StarRocks · QuestDB | Query-only except DuckDB (no row editing or transactions). DuckDB opens a file or `:memory:`. Trino and Presto connect to one catalog. Doris and StarRocks speak the MySQL protocol, QuestDB the PostgreSQL one. |
| **Embedded** | SQLite · H2 · HSQLDB · Apache Derby | Open a file, or an in-memory database for H2 and HSQLDB. SQLite backs up with `VACUUM INTO`. |
| **Key-value** | Redis · Valkey · KeyDB · Dragonfly · etcd | Redis standalone, Cluster and Sentinel, TLS. etcd needs no download. |
| **Document** | MongoDB · FerretDB | `mongodb://` and `mongodb+srv://` URLs. |
| **Data files** | CSV / TSV · Excel `.xlsx` · JSON / NDJSON | Opened as tables. Committed changes are written back to the file. |
| **Anything else** | Any other JDBC database | Bring the driver's jar. |

## What works where

Not every feature makes sense for every database. This table shows the main differences.

| Feature | PostgreSQL family | MySQL family | SQL Server | SQLite | DuckDB | ClickHouse |
| --- | --- | --- | --- | --- | --- | --- |
| Edit rows in the grid | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| All-or-nothing schema changes | ✓ | one by one | ✓ | ✓ | ✓ | one by one |
| [Server activity](/docs/activity) | ✓ | ✓ | — | — | — | — |
| [Backup & restore](/docs/backup-and-restore) | `pg_dump` / `pg_restore` | `mysqldump` / `mysql` | — | `VACUUM INTO` | `EXPORT DATABASE` | — |

"One by one" means statements are applied in order and stop at the first failure; the dialog tells you this before you
run anything.

> [!NOTE]
> Rows of a table without a primary key can be inserted, but not updated or deleted from the grid, because TableGrip
> could not tell which row you meant.

## Databases TableGrip doesn't list

Any database with a JDBC driver works. Choose **Other (JDBC driver)**, or paste a `jdbc:` URL such as
`jdbc:db2://…` or `jdbc:snowflake://…`, then pick the driver's `.jar` file. See
[Drivers](/docs/drivers#your-own-jars).

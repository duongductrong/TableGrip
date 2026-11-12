---
title: Table structure
description: See a table's columns, indexes, keys, constraints, triggers and DDL, read from the database's own catalog.
---

Every table tab has two views, switched at the top left of its toolbar: **Data** (the rows) and **Structure**.

![The Structure view of the orders table, listing its columns with types, nullability and defaults](shot:structure)

## What Structure shows

| Section | Contents |
| --- | --- |
| **Columns** | Every column with its type and properties |
| **Indexes** | Each index and the columns it covers |
| **Foreign Keys** | The table's foreign keys and what they reference |
| **Constraints** | Primary key, unique, foreign key, check and exclusion constraints |
| **Triggers** | Each trigger and its definition |
| **DDL** | The `CREATE TABLE` statement for the table |

Everything is read from each engine's own catalog rather than a generic driver API, so details such as exclusion
constraints and trigger bodies are exact:

- **PostgreSQL:** `pg_constraint`, `pg_trigger` and the `pg_get_*def` functions; the DDL is assembled from them.
- **MySQL / MariaDB:** `SHOW CREATE TABLE` and `information_schema`.
- **SQLite:** `sqlite_master`.

From the toolbar you can **copy the DDL** or **open it in a new console**.

## Opening Structure from the explorer

Right-click a table in the explorer:

- **Open Structure** opens the table on its Structure view.
- **Show DDL** opens it on the DDL section; **Copy DDL** copies it without opening anything.
- **Edit Structure…** opens the [table designer](/docs/schema-tools#table-designer).

Double-click a column, index or foreign key in the explorer to open the matching section directly.

> [!TIP]
> A table opened on Structure doesn't load any rows until you switch to Data, so it's cheap to inspect even huge tables.

## Functions, procedures and sequences

Under each schema the explorer also lists **functions**, **procedures**, **sequences** and **materialized views**, next
to tables and views. A function shows its arguments and result type beside its name, so overloads are easy to tell
apart. They're also in [Go to anything](/docs/navigation) (⌘P) once the connection is open.

Opening one shows its properties above its source, read-only:

- **Functions and procedures:** language, arguments, result, owner and the full `CREATE` statement.
- **Sequences:** current value, increment, minimum, maximum, start, cache and cycle, and a `CREATE SEQUENCE` statement.

**Copy source** copies the statement; **Open in new console** opens it ready to change and run: as `CREATE OR REPLACE` on
PostgreSQL and MariaDB, and `CREATE OR ALTER` on SQL Server. MySQL has neither, so drop the routine before recreating it.

PostgreSQL, MySQL, MariaDB and SQL Server are read from their own catalogs; PostgreSQL leaves out functions that belong
to an extension. Other JDBC engines list routines from the driver's metadata, with their source where the engine
provides it.

## Changing the structure

The pencil in the Structure view (or **Edit Structure…**) opens the table designer, which previews every change as SQL
before applying it. See [Schema tools](/docs/schema-tools#table-designer).

---
title: Schema tools
description: Design tables, compare schemas and generate migrations, draw ER diagrams, import data and generate test rows.
---

The schema tools live in the explorer's right-click menus:

| Right-click a… | You get |
| --- | --- |
| **Schema** (or a connection, for its default schema) | **New Table…**, **Import Data…**, **Show ER Diagram**, **Compare Schema With…** |
| **Table** | **Edit Structure…**, **Show in ER Diagram**, **Import Data…**, **Generate Test Data…** |

## How changes are applied

Every tool that changes the schema or writes rows works the same way:

1. It **shows the statements first**, so you see exactly what will run.
2. It respects **read-only** connections (the tools that write are hidden there) and **asks again on Production**.
3. On databases with transactional DDL (**PostgreSQL, SQLite, SQL Server, DuckDB**) everything runs **all-or-nothing**.
   Elsewhere, statements apply one by one and stop at the first failure; the dialog tells you this up front.

## Table designer

Open it with **New Table…**, **Edit Structure…**, or the pencil in a table's [Structure view](/docs/table-structure).

- Columns, indexes and foreign keys are editable lists.
- **Renames are renames**, not a drop and re-add, so no data is lost, and they follow into indexes and keys.
- The **SQL preview** updates as you type. Press <kbd>⌘</kbd> <kbd>S</kbd> to apply.

On SQLite, some changes (such as changing a column's type) can't be made in place. The designer then rebuilds the
table the way SQLite documents it: rows, indexes, triggers, `UNIQUE` and `CHECK` constraints come back, and foreign keys
are checked before committing.

## Schema compare

**Compare Schema With…** compares any two relational connections and schemas, for example staging against production.

- Differences are listed per table, each with a checkbox. **Drops start unchecked**, so nothing is removed unless you
  ask for it.
- The migration is written in the **target's dialect**.
- Copy it, save it, open it in a console, or run it through the usual confirmation dialog.

## ER diagram

**Show ER Diagram** draws a schema; **Show in ER Diagram** draws a table and its neighbours.

- Tables are laid out in layers by their foreign keys, with crow's-foot ends.
- Drag tables to arrange them. Pinch or <kbd>⌘</kbd>-scroll to zoom; scroll with two fingers to pan.
- Find a table by name, and double-click one to open its rows.
- **Export** as PNG (light or dark, at 2×) or as a Mermaid `erDiagram` to paste into docs or a pull request.

## Import data

**Import Data…** loads a file into a new or existing table.

| Format | Notes |
| --- | --- |
| CSV / TSV | Delimiter and encoding detected |
| Excel | `.xlsx` workbooks |
| JSON | An array of objects, or NDJSON |

1. **Preview** the rows first.
2. Choose a **new table** (column types are inferred, names cleaned to `snake_case`) or an **existing table** (columns
   matched by name).
3. Rows are inserted in batches **in one transaction**. If a row fails, it's named by its line number and nothing is
   kept. A table created for the import is dropped again.

> [!TIP]
> To work with a file without importing it at all, open it directly as a table. See
> [CSV, Excel & JSON files](/docs/data-files).

## Generate test data

**Generate Test Data…** fills a table with realistic rows:

- A **generator per column**, picked from its name and type: emails that match the name, phone numbers, prices, past
  timestamps and more. Change any of them.
- **NULL ratios** and **uniqueness** per column.
- **Foreign keys** are drawn from the referenced table's existing rows, so the data stays consistent.
- The **same seed gives the same rows**, which makes test setups reproducible.
- **Preview** before inserting.

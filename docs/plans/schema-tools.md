# Schema tools (M4)

Five features that work on a schema rather than on rows: a table designer, schema compare, an ER diagram, importing
files into tables, and generating test data. They share one core: a dialect-aware DDL generator and a snapshot of a
namespace's tables.

## Shared core

| Piece | Where | What |
| --- | --- | --- |
| `SchemaSnapshot` | `core/schema` | Every table of a namespace with columns, indexes and foreign keys; read through `RelationalConnection.schemaSnapshot(namespace, onProgress)` (one metadata call for columns, then keys per table). |
| `DdlGenerator` | `core/ddl` | `CREATE TABLE`, `ADD/DROP/RENAME COLUMN`, column type / nullability / default changes, indexes and foreign keys, per dialect. Says when an engine can't do a change in place (SQLite) and produces the table-rebuild script instead. |
| `insertRows` | `RelationalConnection` | Batched, parameterized inserts in one transaction, with progress and cancellation; refuses read-only connections. Shared by import and test data. |

Everything that runs DDL or writes rows goes through the connection's safety settings: read-only refuses, Production asks
with the statements in front of the user, and transactional DDL (PostgreSQL, SQLite, SQL Server, DuckDB) rolls back as a
whole on failure; MySQL and others say up front that statements apply one by one.

## 1. Table designer

- Explorer: table → **Edit Structure…**, schema → **New Table…**. Opens a designer tab.
- Columns, Indexes and Foreign Keys as editable lists: name, type (free text with the engine's common types offered),
  nullable, default, primary key; index columns and uniqueness; FK columns, referenced table/columns, ON UPDATE/DELETE.
- Changes are diffed against what was read; the DDL preview below updates as you type. Problems (empty or duplicate
  names, a FK to columns that don't exist) are flagged on their row and block Apply.
- **Apply** shows the statements once more, runs them, refreshes the explorer and completion, and reopens the table.
  **Open in Console** hands the script over instead.

## 2. Schema compare

- Explorer: schema → **Compare Schema With…**; or from the toolbar of the compare tab. Source and target are any two
  relational connections + namespaces (e.g. staging → prod).
- Differences grouped as tables added / removed / changed; changed tables list column, index and FK differences.
  Each difference has a checkbox; the migration script (in the **target's** dialect) follows the selection.
- **Run…** goes through the same confirmation dialog as the designer (statements, warnings, Production check); or
  **Open in Console** against the target, Copy, Save as `.sql`. Drops are unchecked by default.

## 3. ER diagram

- Explorer: schema → **Show ER Diagram**, table → **Show in ER Diagram** (that table and its neighbours).
- Tables as boxes (PK / FK markers, types), foreign keys as connectors with crow's-foot ends. Layered layout (referenced
  tables to the left), then drag boxes to tidy. Pan by dragging the background, zoom by pinching the trackpad, ⌘-scroll or the toolbar,
  Fit. Search highlights a table; double-click opens its data.
- Export: PNG (rendered at 2×), Mermaid `erDiagram` (copy or save), and the layout is kept while the tab is open.

## 4. Import CSV, Excel, JSON

- Explorer: schema → **Import Data…** (new table) or table → **Import Data…** (existing table); drop not needed.
- Readers in `core/importer`: CSV/TSV (RFC 4180, delimiter and encoding detection, BOM), `.xlsx` (read with the JDK's
  zip + StAX, no POI), JSON (array of objects, or NDJSON; nested values kept as JSON text).
- Wizard in one dialog: file options with a live preview grid → target (new table name, or existing table) → column
  mapping (source column → target column / skip, inferred type for new tables) → import with progress and Cancel.
  Rows go in batches in one transaction: a bad row reports its line and nothing is kept.

## 5. Test data generator

- Explorer / table toolbar: **Generate Test Data…**.
- One generator per column, chosen from the column's type and name (`email`, `first_name`, `phone`, `city`,
  `created_at`, `url`, `uuid`, `price`…), overridable; respects NOT NULL, size limits, unique indexes, and fills foreign
  keys from the referenced table's existing keys. Seeded, so the same seed gives the same rows.
- Preview of the first rows, row count, then insert through `insertRows`. Refused on read-only; Production asks.

## Status

Done; see the README's *Schema tools*. Known limits: composite foreign keys get independent values from the test data
generator; schema compare between different engines copies types as the source spells them (with a warning);
`.xls` (legacy Excel) isn't read.

## Order of work

1. Core: snapshot, DDL generator (+ tests on SQLite and the generator's output for PostgreSQL / MySQL / SQL Server).
2. Table designer. 3. Schema compare. 4. ER diagram. 5. Import. 6. Test data. Each lands with tests and a README section.

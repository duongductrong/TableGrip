# Data files as connections

CSV/TSV, Excel (`.xlsx`, `.xlsm`) and JSON/NDJSON files open as connections, next to databases. Tables, SQL consoles,
filters, editing, import and schema tools work on them unchanged, and committed changes are written back to the file.

## Shape

```
DatabaseType (ConnectionKind.DataFile)  ──►  DataFileStore.connect
                                               │
             DataFileFormat (Csv/Xlsx/Json) ◄──┤  reads the file, writes it back in its own layout
             WorkingCopy (SQLite)           ◄──┤  the tables, edited like any SQLite database
             DataFileSync                   ◄──┤  file ⇄ working copy: save, reload, conflicts, state
             DataFileConnection : RelationalConnection (delegates to the SQLite Database)
```

- **Kept apart from databases.** Everything lives in `core/datafile`. The only touch points elsewhere are the
  `DataFile` connection kind with three `DatabaseType`s, the `dataFile` options on `ConnectionConfig`, one branch in
  `DatabaseRegistry`, and the app's dialog, explorer menu, banner and file drop. Database code doesn't know files exist.
- **Formats are plugins.** A `DataFileFormat` reads a file into `SourceTable`s and returns a `DataFileDocument` that
  remembers the file's layout and writes `OutputTable`s back. Formats know nothing about SQL. Adding one (Parquet, ODS,
  a folder of CSVs) is an implementation, an entry in `DataFileFormats.all` and its `DatabaseType`.
- **SQLite working copy.** The file is loaded into a temporary SQLite database under `~/.datagrok/cache/datafiles`
  (deleted on close; copies left by a crash are removed on the next open). Each table gets an `_row INTEGER PRIMARY KEY`
  so the table viewer can edit rows; it's never written to the file. Opening a data file needs the SQLite driver, like
  SQLite profiles do.

## Fidelity rules

- A column is numeric only when every value already reads back character for character (`007`, `1.50`, `1e5` stay
  text), so an untouched file is never rewritten and an edit changes only what was edited.
- CSV keeps its encoding, BOM, delimiter and line ending; quotes only what needs it; `""` stays an empty string.
- Excel is patched, not regenerated: unchanged sheets are copied byte for byte; changed ones keep everything but
  `<sheetData>` (widths, frozen panes, merged cells, drawings, page setup); cells take their column's style, so dates
  stay dates. SQL `CREATE`/`ALTER … RENAME`/`DROP TABLE` add, rename and remove sheets. The calc chain is dropped for
  Excel to rebuild. Formulas in a changed sheet become values: saving such a sheet asks first.
- JSON keeps array vs NDJSON, indentation, value types and missing (vs null) keys. Wrapped documents open read-only.

## Sync

- Writes through the app (grid edits, import, schema tools) are saved before the call returns; console commits are
  noticed within a second (`PRAGMA data_version`).
- Saves go to a temp file that atomically replaces the original.
- A change on disk is read back in when nothing is unsaved; otherwise the changes are held and the user picks
  "overwrite" or "reload". Failures (a file locked by another program) are retried with backoff.
- Quitting or disconnecting with held or failed changes asks first.

## Not yet

Legacy `.xls`, `.ods`, Parquet; a folder of CSVs as one connection; keeping formulas when their sheet changes.

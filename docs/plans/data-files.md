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
- **SQLite working copy.** The file is loaded into a temporary SQLite database under `~/.tablegrip/cache/datafiles`
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

## Opening from the file manager

- **File associations.** The installers register `.csv`, `.tsv`, `.xlsx`, `.xlsm`, `.ndjson` and `.jsonl` (`fileAssociation`
  in `app/build.gradle.kts`), so Finder's Open With, double-click once TableGrip is the default, and a file dropped on the
  Dock icon open them. Plain `.json` and `.txt` are left out: most aren't tables.
- **One app, one window per file.** `FileOpenRequests` takes files from the command line (Windows, Linux) and from
  macOS's open-file events, installed before the UI so the launching file isn't missed. Each file gets a
  `DataFileWindow`: its grid with editing, filters and saving, and a workbook's sheets as tabs along the bottom in the
  workbook's order. Opening the same file again raises its window. Files dropped on a file window open in windows too.
- **Same connection as the main window.** The window uses the file's data file connection (saving a profile the first
  time), so the file is in the explorer as well, and its SQL console, structure and links open in the main window.
- **The main window comes when it's needed.** A launch to open a file shows only that file; the main window appears
  when something opens in it. Closing it while file windows remain hides it; the app quits once no window is left.
  The app's dialogs (driver download, export, unsaved files) show in the file window in front while it's hidden.

## Not yet

Legacy `.xls`, `.ods`, Parquet; a folder of CSVs as one connection; keeping formulas when their sheet changes; hiding
the `_row` key column in the grid; a single running instance on Windows and Linux (each file opened from the file
manager starts its own process there).

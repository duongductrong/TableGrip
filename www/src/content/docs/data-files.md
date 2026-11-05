---
title: CSV, Excel & JSON files
description: Open data files as tables, query them with SQL, edit them in the grid, and save changes back to the file.
---

TableGrip opens CSV, Excel and JSON files as if they were database tables. You get the same grid, filters and SQL
console, and when you commit a change it is written back to the file, keeping its format.

## Supported files

| Format | Extensions | Becomes |
| --- | --- | --- |
| **CSV / TSV** | `.csv`, `.tsv`, `.tab`, `.txt` | One table |
| **Excel** | `.xlsx`, `.xlsm` | One table per sheet |
| **JSON** | `.json` (an array of objects), `.ndjson`, `.jsonl` | One table |

## Opening a file

- **Explorer:** **+ ▾** → **Open Data File…**, or the same item in the explorer's right-click menu.
- **Drag and drop** one or more files onto the main window.
- **New Connection:** paste the file's path into **Paste**, or choose it in the **File** row with **Browse…**. For
  CSV you can set the **Delimiter** (Detect, Comma, Semicolon, Tab, Pipe), the **Encoding**, and whether **The first row
  names the columns**.
- **From your file manager:** CSV, TSV, Excel, NDJSON and JSONL files can be opened with TableGrip directly
  (double-click or **Open With**). Such a file opens in a window of its own, with buttons to open another file, reload
  it, reveal it in the file manager, or **query it with SQL in the main window**.

## Querying with SQL

Open a console on the file's connection (<kbd>⌘</kbd> <kbd>T</kbd>) and write SQL against it as against any database.
Under the hood, the file is loaded into a private SQLite working copy, so joins, aggregates and window functions all
work.

```sql
SELECT country, count(*) AS customers
FROM customers
GROUP BY country
ORDER BY customers DESC;
```

## Saving changes back to the file

- Changes you commit from the table viewer, an import or a schema tool are written to the file **straight away**.
  Changes committed from a console are written within a second.
- Writes go to a temporary file first, which then replaces the original in one step, so a crash never leaves a
  half-written file.
- Right-click the file's connection for **Save to File Now**, **Reload from File**, **Reveal in File Manager** and
  **Open in Separate Window**.

### What is kept

| Format | Kept as it was |
| --- | --- |
| **CSV / TSV** | Encoding, byte-order mark, delimiter and line endings |
| **Excel** | Everything outside the rows of the sheets you changed. In a sheet that is rewritten, formulas become their values. |
| **JSON** | The shape of the file and each value's type. Wrapped or UTF-16 JSON opens read-only. |

### When the file changes on disk

If another program changes the file and you have nothing unsaved, TableGrip reloads it. If you do have unsaved
changes, it asks: **Keep mine, overwrite** the file, or **Discard mine, reload** it.

Closing the app or the connection with changes that aren't written yet opens **Unsaved Data Files**, with **Save and
Continue** or **Discard Changes**.

> [!NOTE]
> The working copy lives in `~/.tablegrip/cache/datafiles/` only while the file's connection is open. TableGrip adds
> a hidden row-key column to it so edits find the right row; that column is never written to your file.

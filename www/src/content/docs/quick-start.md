---
title: Quick start
description: From a fresh install to your first query in five minutes.
---

This walk-through takes you from opening TableGrip for the first time to browsing a table and running a query.

## 1. Add a connection

When you open TableGrip with no connections, the explorer on the left says **No connections yet** and offers three
ways to start:

- **Import Them…** appears when TableGrip finds connections in TablePlus, DBeaver, DataGrip, `~/.pgpass` or `.env`
  files on this computer. It's the fastest way in; see [Move from another tool](/docs/moving-in).
- **New Connection…** opens the connection dialog.
- **Open Data File…** opens a CSV, Excel or JSON file as a table. See [CSV, Excel & JSON files](/docs/data-files).

For a new connection:

1. Click **New Connection…**.
2. If you have a connection URL, paste it in the **Paste** field at the top and click **Parse**. Everything else fills
   in by itself. For example:

   ```text
   postgresql://app:secret@localhost:5432/shop
   ```

3. Otherwise choose the **Type** (PostgreSQL, MySQL, SQLite…) and fill in **Host**, **Port**, **Database**, **User** and
   **Password**. For a file database such as SQLite, choose the **File** instead.
4. Click **Test Connection**. If the driver isn't installed yet, TableGrip offers to download it.
5. Click **Save**.

The password goes into your system keychain, never into TableGrip's own files.

> [!TIP]
> Give production connections the **Production** environment under **Options**. TableGrip then colours them red
> everywhere and asks before any statement that changes data. See [Safety & environments](/docs/safety).

## 2. Browse a table

Click the connection in the explorer to connect. Expand it to see its schemas and tables, then **double-click a table**
to open its rows in the [data grid](/docs/data-grid).

![A table open in the data grid, next to the connection explorer](shot:grid)

From here you can:

- Sort by clicking a column header.
- Filter with the bar above the grid, for example `created_at` **in the last 7 days**. See
  [Filtering data](/docs/filtering).
- Edit a cell by double-clicking it. Your change waits, highlighted, until you press <kbd>⌘</kbd> <kbd>↩</kbd> to submit.
- Switch to **Structure** (top left of the tab) to see the columns, indexes and keys.

## 3. Write a query

Press <kbd>⌘</kbd> <kbd>T</kbd> to open a console for the current connection, and start typing. Completion suggests
tables and columns as you go.

```sql
SELECT status, count(*) AS orders, sum(total) AS revenue
FROM orders
GROUP BY status
ORDER BY revenue DESC;
```

Press <kbd>⌘</kbd> <kbd>↩</kbd> to run the statement under the caret. The results open below.

![A console with a query and its results](shot:editor)

## 4. Find anything

Press <kbd>⌘</kbd> <kbd>P</kbd> (or <kbd>⇧</kbd> twice) and type a few letters of a table, a saved query or an action.
It's the quickest way to get around. See [Go to anything & sessions](/docs/navigation).

## Where to next

- [SQL editor](/docs/sql-editor): completion, formatting, `EXPLAIN` and fixes for errors.
- [Saved queries & history](/docs/saved-queries): keep the queries you run often.
- [AI agent](/docs/ai-agent): ask questions about your schema in plain language.
- [Keyboard shortcuts](/docs/keyboard-shortcuts): the full list.

---
title: Saved queries & history
description: Save queries as plain .sql files, run them with parameters, and find anything you ran before.
---

## Save a query

Press <kbd>⌘</kbd> <kbd>S</kbd> in a new console, give the query a name, and it is saved under **Saved Queries**
(<kbd>⌘</kbd> <kbd>4</kbd>). After that:

| To | Press |
| --- | --- |
| Save changes in place | <kbd>⌘</kbd> <kbd>S</kbd> |
| Save to a file anywhere on disk | <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>S</kbd> |

Saved queries are plain `.sql` files in `~/.tablegrip/queries/`, folders included. You can edit them in another editor,
share them, or keep the folder in git.

### Tie a query to a connection

Turn on **Always run on &lt;connection&gt;** when saving and the query opens on that connection from then on. TableGrip
records this as a first line in the file:

```sql
-- connection: shop-production
SELECT count(*) FROM orders WHERE status = 'paid';
```

Without that line, a saved query opens on whichever connection is current.

## Parameters

Write `:name` or `${name}` in a query and TableGrip asks for the values before running it:

```sql
SELECT *
FROM orders
WHERE status = :status
  AND created_at >= ${since};
```

- Values are inserted **as written**: type `'paid'` (with quotes) for text and `42` for a number.
- Parameters inside strings and comments are ignored, and `a::int` is a PostgreSQL cast, not a parameter.
- The last values you used are offered again next time.

## Query history

Every statement you run is recorded in the **Query History** tool window, newest first, so you can find and re-open
something you ran last week. Open it with <kbd>⌘</kbd> <kbd>2</kbd>. History is stored in `~/.tablegrip/history.jsonl`.

> [!TIP]
> [Go to anything](/docs/navigation#go-to-anything) (<kbd>⌘</kbd> <kbd>P</kbd>) also searches saved queries and the
> recent queries of the current connection.

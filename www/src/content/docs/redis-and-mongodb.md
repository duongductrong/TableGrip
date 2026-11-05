---
title: Redis & MongoDB
description: Browse and edit keys in Redis, Valkey and etcd, and documents in MongoDB, with a visual query builder.
---

Key-value stores and document databases don't have tables, so TableGrip gives each its own browser. They share the
same ideas as the rest of the app: edits are explicit, read-only connections stay read-only, and a console is always
one click away.

## Key browser (Redis, Valkey, KeyDB, Dragonfly, etcd)

Right-click a database (`db0`, `db1`…) → **Browse Keys**, or **New Console** to type commands.

### Finding keys

- **Search** with a pattern such as `user:*`: `*` matches anything and `?` a single character.
- **Filter by type** to show only hashes, lists, streams and so on.
- Keys are **grouped into a tree** by their delimiter (`:` for Redis, `/` for etcd), with **Expand all** and
  **Collapse all**.
- Large keyspaces are scanned in pages: **Load more** continues the scan, **Scan again** starts over.

### Viewing and editing values

**Strings, hashes, lists, sets, sorted sets and streams** can all be viewed and edited:

- **Strings** open as text. **Format JSON** pretty-prints JSON values, **Copy value** copies them, and
  **Save** (<kbd>⌘</kbd> <kbd>S</kbd>) or **Revert** finishes the edit.
- **Collections** (hash, list, set, sorted set, stream) list their elements with **Edit** and **Remove** on each,
  a row to add a new field, item or member, and a filter to find elements in large collections.

Above the value you can **Copy key name**, **Reload value**, **Rename key**, **Delete key** and **Change expiry**.
Other key types are shown, and can be changed from the console.

### Expiry (TTL)

**Change expiry** sets **Expire after** a number of seconds, minutes, hours or days, with quick picks for 1 min, 1 hour,
1 day and 1 week. **Remove expiry** makes the key permanent.

### New keys and commands

**New key** asks for the type, the first value and an optional TTL. For anything the browser doesn't cover, use the
**command console**. On read-only connections only commands that read are allowed.

## Document browser (MongoDB, FerretDB)

Right-click a collection → **Open Documents**, or **New Console with find()** to write a query by hand.

### Querying

Above the documents are the fields **Filter**, **Sort**, **Project**, **Skip** and **Limit**, written as MongoDB JSON,
for example `{ "status": "paid", "total": { "$gt": 200 } }`. Press **Apply** to run them; **Not applied** reminds you
when a field has changed since, and **Clear** resets it.

### Query builder

Press <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>F</kbd> to switch to the visual builder. Drag fields onto the **Filter**, **Sort**
and **Show** cards, or drag a column header up into them. Filter operators are = ≠ &gt; ≥ &lt; ≤, in, not in, contains,
starts with, ends with and matches regex, combined with **Match all** or **any**.

### Viewing and editing

- Switch between a **Grid** and **JSON** view, and open the value panel for nested values.
- **Add document** (<kbd>⌘</kbd> <kbd>N</kbd>), **Add field**, edit values in place, or **Delete selected documents**.
- Changes are staged: **Preview pending writes** shows exactly what will be sent, **Submit changes**
  (<kbd>⌘</kbd> <kbd>↩</kbd> or <kbd>⌘</kbd> <kbd>S</kbd>) sends them, and **Revert all changes** drops them. Undo and
  redo work as in the [data grid](/docs/data-grid).
- Paging shows the current page; click **of ?** to count all matching documents.

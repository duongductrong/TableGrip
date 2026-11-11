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

The console completes as you type, like the SQL editor: command names and subcommands (`CONFIG GET`), the options a
command takes where its syntax allows them (`SET key value EX …`, `SCAN 0 MATCH … TYPE hash`), and existing key names
wherever a key goes, read from the database. The line above the prompt shows the command's syntax. <kbd>Tab</kbd> or
<kbd>↩</kbd> picks a suggestion, <kbd>⌃</kbd> <kbd>Space</kbd> opens the list and <kbd>Esc</kbd> closes it; <kbd>↩</kbd>
runs the line once there's nothing to pick, and <kbd>⌘</kbd> <kbd>↩</kbd> always runs it. etcd's console completes
etcdctl's commands, flags and keys the same way. Read-only connections only suggest commands that read.

## Document browser (MongoDB, FerretDB)

Right-click a collection → **Open Documents**, or **New Console with find()** to write a query by hand.

### Shell console

The console is a script editor like the SQL console's, with the replies below it. It runs Mongo shell commands without
JavaScript (`db.orders.find({status: 'A'}).sort({total: -1})`, `db.orders.aggregate([...])`, `db.runCommand({...})`,
`show collections`, `use <db>`). A command can span lines, and a line starting with `.` (`.sort(...)`) continues the
one above; `;` or the end of a finished line ends it, and `//` and `/* */` are comments. Press <kbd>⌘</kbd> <kbd>⏎</kbd>
to run the command at the caret or the selected ones, and <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>⏎</kbd> to run the whole
script, which stops at the first command that fails. The script is kept with your session.

The editor colours the shell's syntax, pairs brackets and quotes, and completes as you type: collections after `db.`, a
collection's methods with their brackets (`find({})`, `aggregate([])`), `.sort()` and `.limit()` after `find()`, and
inside the arguments whatever that argument takes: the collection's fields and their values, query operators, update
operators (`$set`, `$inc`…), pipeline stages and accumulators with `'$field'` paths, and command names in `runCommand`.
Fields are learnt from a sample of the collection's documents. The signature of the method being called shows under the
editor, and read-only connections don't suggest methods that write.

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

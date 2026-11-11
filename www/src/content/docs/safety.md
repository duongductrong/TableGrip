---
title: Safety & environments
description: Environment colours, read-only connections, confirmations and transactions that keep production data safe.
---

Mistakes against a production database are expensive. TableGrip gives every connection a few switches that make the
dangerous ones obvious and the risky statements deliberate.

## Environments

Tag each connection with an environment under **Options → Environment**. The tag colours the connection's badge in the
explorer, on tabs and in the status bar, so you always see where you are.

| Environment | Badge | Default colour |
| --- | --- | --- |
| Local | `LOCAL` | grey `#6F7A8C` |
| Development | `DEV` | green `#4CA35A` |
| Staging | `STG` | orange `#D9822B` |
| Production | `PROD` | red `#DB4B4B` |
| None | no badge | |

Choosing **Production** also turns on **Confirm before running statements that change data or schema**. You can
change the colours in [Settings → Color Scheme](/docs/settings#color-scheme).

## Read-only connections

Turn on **Read-only** under **Options** and TableGrip refuses statements that change data or schema **before they reach
the server**: `INSERT`, `UPDATE`, `DELETE`, DDL, restores into it, and cancelling or terminating sessions in
[Activity](/docs/activity). Use it for reporting replicas and for anyone who should only look.

> [!TIP]
> Read-only in TableGrip is a safety net, not a permission system. For hard guarantees, also connect with a database
> user that only has read privileges.

## Confirm writes

With **Confirm before running statements that change data or schema** on, every write asks first, showing what is
about to run. It's on automatically for Production.

## Statements that always ask

Whatever the connection's options, these always need a confirmation:

- `DELETE` or `UPDATE` **without a `WHERE` clause**
- `DROP`
- `TRUNCATE`

## Transactions you control

- **Grid edits** stay pending until you submit them, then run in one transaction, and are refused if a row changed
  underneath you. See [Data grid](/docs/data-grid#editing-data).
- **Consoles** default to auto-commit. Switch to **manual commit** to run statements, review the result, and then
  **Commit** or **Roll back**.
- **Closing** a console with pending work asks what to do. **Quitting** the app asks about anything a restart can't
  bring back: open transactions and unsubmitted edits.

## Schema changes and restores

- Every tool that changes the schema or writes rows (table designer, schema compare, import, test data) **shows the
  SQL first** and respects read-only connections. On Production it asks again.
- **Restores** go into a **new database** by default. Restoring into Production asks you to type the target database's
  name again.

## AI agents

Agents only see your schema by default, never your data, and production connections never share data unless their own
options say so. Queries an agent proposes that would change data are only opened for you to review. See
[What the agent can see](/docs/agent-privacy).

AI apps connected through the [MCP server](/docs/mcp-server) follow the same rules, and may change data only where a
connection's **Options → Agent writes** allows it. Writes on Production, and risky statements, ask you first.

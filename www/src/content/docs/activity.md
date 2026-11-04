---
title: Server activity
description: See what is running on a PostgreSQL or MySQL server, what blocks what, and cancel or terminate sessions.
---

**Activity** lists the server's sessions and shows which ones are waiting on locks held by others. It is the first
place to look when "the database is slow" or a migration hangs.

Open it by right-clicking a server connection → **Activity**, or press <kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>A</kbd> for the
current connection.

## Reading the lock tree

Sessions waiting on a lock sit **indented under the session holding it**, so a chain of blocked queries reads from the
top down to its root cause.

- **Filter** Running, Blocked or All sessions.
- **Search** by user, database or query text.
- **Refresh** every 2, 5 or 10 seconds, or pause.
- Select a session to see its **full query**, and open it in a console.

Activity uses a connection of its own, so it keeps working even when the connection pool is stuck behind a lock.

## Stopping a session

| Action | What it does |
| --- | --- |
| **Cancel Query** | Stops the session's current statement; the session stays connected. |
| **Terminate** | Ends the whole session and rolls back its open transaction. |

**Terminate always asks first.** Cancelling asks first on Production and on connections that confirm writes.
Read-only connections can't signal sessions at all.

## TableGrip's own sessions

TableGrip names its sessions so they can be told apart: `TableGrip` as the application name in `pg_stat_activity` on
PostgreSQL, and through the `program_name` connection attribute on MySQL. They're hidden unless you turn off **Hide
TableGrip**.

## Background warnings

While an Activity tab is open but not in front, it refreshes every 15 seconds. When sessions have been waiting on a lock
for more than 5 seconds, the **status bar warns** you; click the warning to see them.

## Where it works

| Database | Supported | Lock information from |
| --- | --- | --- |
| PostgreSQL and compatible services | ✓ | `pg_stat_activity` |
| MySQL | ✓ | `performance_schema.data_lock_waits` |
| MariaDB | ✓ | `INNODB_LOCK_WAITS` |
| File and embedded databases | — | |

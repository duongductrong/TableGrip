# DB operations plan: Activity monitor, backup and restore

Dated 2026-10-31. Settled from the discussion of the "DB operations" section in the feature proposal.

## 0. Decisions made

| Question | Decision |
| --- | --- |
| First-release scope | PostgreSQL and MySQL/MariaDB for both features. Other engines: hide or disable, **always with a reason** |
| Dump/restore tools | Not bundled with the app. Downloaded lazily from the official source, only after the user confirms |
| Managing downloaded tools | Settings has a dedicated page: view, update, remove, change path, open folder |
| Scheduled backup | Yes, but **off by default**. Runs only when the user turns it on and configures it fully |
| Activity monitor location | A tab in the workspace, one tab per connection (see §1.1) |

## 1. Activity monitor

### 1.1 Location and how to open it

Activity is a **tab in the workspace**, not a tool window. Reason: the session table has many columns, and long SQL and
the blocking tree need room, while a side tool window (like History) is narrow. A tab also lets you open the activity of
two connections side by side, and drag and close it like any other tab.

Ways to open it (all reopen an existing tab instead of creating a duplicate):

- Explorer: right-click a connection → **Activity**.
- Shortcut **⌥⌘A** (Ctrl+Alt+A), applies to the selected connection. Add it to `Shortcuts.kt` and `ShortcutsDialog`.
- Status bar: when an open connection has a session blocked for more than 5 s, show the chip `⚠ 2 blocked`; clicking it
  opens Activity with the "Blocked" filter preset. The chip runs only after the Activity tab for that connection has
  been opened at least once, or the user turns it on in Settings, so the app does not query the server on its own.

Unsupported engine: the menu item is still shown but disabled, with the tooltip "Activity does not support SQLite yet — a
DB file has no server sessions" or "… does not support ClickHouse yet, planned for a later release".

### 1.2 Tab layout

```
┌ Toolbar: [⟳ 2s ▾] [⏸] | Filter: [Active ▾] [User ▾] [DB ▾] [> 10s] | [Hide idle] [Hide TableGrip sessions]
├──────────────────────────────────────────────┬──────────────────────────────┐
│ Session tree (DataGrid, columns pid/user/db/ │ Selected session details:    │
│ state/time/wait/truncated SQL).              │ Full SQL (highlighted),      │
│ Sessions holding a lock are at the root,     │ client, app, tx/query start, │
│ sessions waiting on them are indented below. │ locks held/awaited.          │
│                                              │ [Open in console] [EXPLAIN]  │
│                                              │ [Copy] [Cancel] [Terminate]  │
└──────────────────────────────────────────────┴──────────────────────────────┘
```

- Coloring: running longer than the threshold (yellow → red), *idle in transaction* (orange), waiting on a lock (light red).
- TableGrip's own sessions are labeled `TableGrip` and hidden by default.
- Refresh: 1 / 2 / 5 / 10 s or paused. Refresh stops when the tab is not visible.

### 1.3 Core

`core/.../activity/`:

```kotlin
data class SessionActivity(
    val id: String,            // pid (PG) / processlist id (MySQL)
    val user: String?, val database: String?, val client: String?, val application: String?,
    val state: SessionState,   // Active, Idle, IdleInTransaction, Waiting, Other
    val waitEvent: String?,
    val query: String?, val queryStart: Instant?, val transactionStart: Instant?,
    val blockedBy: List<String>,
    val own: Boolean,          // TableGrip's own session
)

interface ActivityReader {
    suspend fun sessions(): List<SessionActivity>
    suspend fun cancel(id: String)     // cancels the running query only
    suspend fun terminate(id: String)  // closes the connection
    suspend fun missingPrivileges(): List<String>
}
```

| | PostgreSQL | MySQL / MariaDB |
| --- | --- | --- |
| Sessions | `pg_stat_activity` (drop `backend_type <> 'client backend'`) | `information_schema.PROCESSLIST` |
| Blocking | `pg_blocking_pids(pid)` | MySQL 8: `performance_schema.data_lock_waits`; MariaDB / 5.7: `information_schema.INNODB_LOCK_WAITS` |
| Cancel / terminate | `pg_cancel_backend` / `pg_terminate_backend` | `KILL QUERY id` / `KILL id` |
| Missing privileges | other users' queries are hidden without `pg_read_all_stats`; kill needs `pg_signal_backend` | `PROCESS`; killing other users' sessions needs `CONNECTION_ADMIN` / `SUPER` |
| Identifying TableGrip | `application_name = 'TableGrip'` | `connection_id()` of TableGrip's connections |

The monitor uses **its own dedicated connection**, not one from the console pool, so it keeps working when the pool is
stuck. If privileges are missing, show a banner that states which privilege and the consequence (for example: "Only
sessions of user `app` are visible — `pg_read_all_stats` is required").

### 1.4 Safety

- **Read-only** connection: the Cancel/Terminate buttons are disabled, with an explanatory tooltip.
- **Production** connection, or confirm-writes turned on: ask for confirmation, stating the pid, user, and SQL statement.
- Terminating a session that is *in a transaction*: add the warning "the transaction will be rolled back".
- Do not allow killing the Activity tab's own session.

## 2. External tools (pg_dump, pg_restore, mysqldump…)

### 2.1 Source and how to download

| Tool | Source | Notes |
| --- | --- | --- |
| `pg_dump`, `pg_restore`, `psql` | Official PostgreSQL binaries (EDB zip for macOS/Windows; Linux: distro package) | Downloaded to `~/.tablegrip/tools/postgresql/<version>/`, with a SHA-256 pin like `drivers.lock.json` |
| `mysqldump`, `mysql` | **Not downloaded**. Use the version the user has installed | MySQL binaries are GPL; show install instructions (Homebrew, apt, installer) instead of distributing them |
| `mariadb-dump` | Same as MySQL | |

Look for existing tools before offering a download: `PATH`, `/opt/homebrew/opt/libpq/bin`, `/opt/homebrew/bin`,
`/Applications/Postgres.app/Contents/Versions/*/bin`, `C:\Program Files\PostgreSQL\*\bin`, `C:\Program Files\MySQL\*\bin`.

### 2.2 Confirmation flow

When the user opens Backup and no suitable tool is available, the dialog shows:

- What is needed: `pg_dump` / `pg_restore` 17.x (because the server is 17.2; the tool must be the same version as the
  server or newer).
- Source: full URL, publisher, license.
- Download size and size after extraction, install folder.
- Buttons: **Download** · **Use installed version…** (choose a file) · **Cancel**.

No silent downloads. `autoDownloadDrivers` does **not** apply to tools.

### 2.3 Settings → Tools

A new page, next to the Drivers page, reusing the style of `DriversPanel`:

- Tool list: version, source (Downloaded / System / User-selected), path, size.
- Actions: Update, Remove, Remove old versions, Open folder, Change path, Verify (runs `--version`).
- Total size of `~/.tablegrip/tools/`.

## 3. Backup and restore

### 3.1 Backup

Open from: right-click a connection/database/schema → **Backup…**.

- PG formats: Custom `-Fc` (default), Plain SQL, Directory `-Fd -j N`.
- Scope: whole DB · selected schemas · selected tables; schema only / data only.
- PG options: `--no-owner`, `--no-privileges`, `--clean --if-exists`, compression.
- MySQL: default `--single-transaction --routines --triggers --events`, optional `--no-data`, table selection.
- Default target file `~/Backups/<connection>/<db>_<yyyyMMdd-HHmm>.dump`.
- An "Equivalent command" panel (password replaced with a variable), with a Copy button.

### 3.2 Restore

- Choose a file; for custom/directory formats, read `pg_restore --list` so objects can be ticked.
- By default, suggest a **new database** (`<db>_restore_<date>`), never overwriting.
- Production: require retyping the database name. Read-only: refuse.
- Plain SQL: run with `psql` / `mysql` using `ON_ERROR_STOP`.

### 3.3 Execution

- Password: for PG via `PGPASSWORD` in the child process env; for MySQL via a temporary `--defaults-extra-file` with
  mode 0600, deleted in `finally`. Never put a password on the command line.
- SSH: open a tunnel like a normal connection, and point the tool at `127.0.0.1:<port>`.
- Progress: read stderr from `--verbose` and show the object being processed. Cancel = kill the process and delete the
  partial file.
- Log each run to `~/.tablegrip/logs/backup/`.
- Check the version before running (`ServerIdentity` against `--version`).
- Engines with no external tool: SQLite uses `VACUUM INTO`, DuckDB uses `EXPORT DATABASE` (nothing to download).
  Other engines: menu disabled with a reason.

### 3.4 Scheduled backup (off by default)

- Turn on in Settings → Backups → Schedule. Each schedule: connection, scope, format, folder, frequency, keep the last N copies.
- Can be turned on only when the requirements are met: tool available, password in the keychain, folder writable. Say
  clearly which one is missing.
- The first release runs only **while the app is open**; if a schedule is missed, it runs when the app opens (with a
  notification). Running in the background while the app is closed (launchd / Task Scheduler / systemd timer) is left
  for a later release, and must also be turned on by the user.
- Production connection: the schedule can run, but show a warning about server load when the schedule is created.

## 4. Implementation order

1. ✅ `ActivityReader` for PostgreSQL + Activity tab + cancel/terminate + safety. Test with `-Pit.postgres`.
2. ✅ MySQL/MariaDB reader. "Blocked" chip on the status bar.
3. ✅ `ToolLibrary` (find, verify, download with confirmation) + Settings → Tools.
4. ✅ PostgreSQL backup/restore, then MySQL. SQLite/DuckDB.
5. ✅ Scheduled backup.

## 5. Differences from the design during implementation

- The "blocked" chip runs only for connections with an open Activity tab (background refresh every 15 s); there is no
  Settings option yet to watch every connection.
- "Unsupported" engines still show the menu item (Compose cannot disable individual context menu items); the tab/dialog
  that opens explains the reason.
- The PostgreSQL download package is EDB 18.6-2 (macOS universal, Windows x64). The Windows build has so far been checked
  only by hash and zip structure, and has not been run on Windows. Linux: there is no official zip package, so show
  instructions to install from the distro.
- There is no restore for SQLite/DuckDB: the backup is a separate database, opened with a new connection.
- The EXPLAIN item in the session details is PostgreSQL only: it opens the query in a console with its estimated plan
  (generic for prepared `$1` queries). MySQL and MariaDB don't offer it yet.

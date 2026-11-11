# TableGrip user guide

Everything TableGrip does, feature by feature. For installing and building, see the [README](../README.md).
Shortcuts are written for macOS; on Windows and Linux use Ctrl instead of ⌘.

- [Language](#language)
- [SQL editor](#sql-editor)
- [Getting around](#getting-around)
- [Saved queries](#saved-queries)
- [Filtering table data](#filtering-table-data)
- [Data grid](#data-grid)
- [Table structure](#table-structure)
- [Schema tools](#schema-tools)
- [Activity](#activity)
- [Backup and restore](#backup-and-restore)
- [Connections](#connections)
- [Drivers](#drivers)
- [Organising connections](#organising-connections)
- [Importing connections](#importing-connections)
- [Connecting](#connecting)
- [Safety](#safety)
- [MCP server](#mcp-server)

## Language

TableGrip is available in English, Vietnamese (Tiếng Việt), Simplified Chinese (简体中文) and Traditional Chinese
(繁體中文). Settings → Appearance → Language picks one; the default follows the system's language, and Chinese written
in traditional characters (Taiwan, Hong Kong, Macau) gets Traditional Chinese. The new language shows after a restart,
which the setting offers with **Restart Now**. In `settings.json` it is `"language"`: `system`, `en`, `vi`, `zh-CN` or
`zh-TW`.

Menus, dialogs, messages and dates follow the language. Your data, SQL, SQL keywords, database error messages, logs
and what is sent to AI agents stay as they are.

## SQL editor

Syntax highlighting, line numbers with the statement ⌘Enter would run marked in the gutter, current-line and
matching-bracket highlights, auto-paired brackets and quotes.

| Action | Shortcut |
| --- | --- |
| Run statement at caret / selection · run script | ⌘Enter · ⇧⌘Enter |
| Completion | as you type, or ⌃Space |
| Comment / uncomment lines | ⌘/ |
| Duplicate · delete · move lines | ⌘D · ⌘⌫ · ⌥⇧↑/↓ |
| Indent / outdent | Tab / ⇧Tab |
| Find · replace | ⌘F · ⌘R (Enter / ⇧Enter for next / previous) |
| Reformat SQL | ⌥⌘L |

Toolbar: execute, execute script, cancel, **Explain** (plan for the statement at the caret), reformat, find.

**Fixing errors**: under a failed statement in the output, **Quick fix** replaces a misspelt table or column with the one
closest real name (from completion's schema, nothing leaves the machine); **Fix with AI** asks the agent picked in the chat
panel; **Ask in chat** drafts the question there. A fix is shown as a diff first and only applied when you choose:
Apply replaces the failed statement (⌘Z undoes it), Apply & Run also runs it through the usual checks. A fix that would
change data where the original only read is flagged and can't be run from the card.

**Completion** understands the statement around the caret: tables, views and schemas after `FROM`/`JOIN`, columns of
the tables and aliases in scope (qualified when several tables are joined), `alias.` / `schema.` members, CTE columns,
`INSERT INTO t (` column lists, operators after a column, functions and keywords. After `JOIN` it proposes whole join
clauses from foreign keys (`JOIN orders o ON o.user_id = u.id`). Matching is fuzzy by word starts (`uid` → `user_id`),
and inside `col = '…'` or `col IN (…)` it suggests real values sampled from the column. Metadata loads lazily per
connection and refreshes after DDL or an explorer refresh.

## Getting around

**Go to anything** (⌘P, or Shift twice): one field that finds tables and views of connected connections, open tabs,
saved queries, connections, actions and recent queries of the current connection. Matching is fuzzy by word starts
(`uo` → `user_orders`). ⏎ opens; ⇧⏎ opens a table's structure.

**Sessions**: open consoles (with unsaved text and the caret) and tables (with their filter and view) come back on the
next start, from `~/.tablegrip/session.json`, saved every few seconds while something changes. Quitting only asks about
work a restart can't bring back: open transactions and unsubmitted edits. Tables load once their tab is shown.

**Explorer**: the search field narrows the tree to connections, and to tables and columns already loaded, whose name
matches. Right-click a connection → Add to Favorites (listed first) or Move to Group…; the group is also under
Options in the connection dialog.

## Saved queries

⌘S in a new console names the query and saves it under Saved Queries (⌘4); later ⌘S saves in place and
⇧⌘S saves to a file anywhere. Queries are plain `.sql` files in `~/.tablegrip/queries/`, folders included, so they can be
edited elsewhere or kept in git. "Always run on <connection>" writes a `-- connection: <name>` first line, which opens
the query on that connection; without it a query opens on the current one.

**Parameters**: `:name` and `${name}` outside strings and comments (`a::int` is a cast) ask for values before running.
Values go in as written: `'paid'` for text, `42` for a number. The last values are offered again.

## Filtering table data

The table viewer's filter bar has two modes (the choice is remembered): **Builder** and **SQL**.

**Builder** composes typed conditions — `column · operator · value` rows, combined as *all* (AND) or *any* (OR), with
nested groups (which can be negated) and hand-written SQL conditions mixed in. Columns are classified from their database
type, and each kind gets its own operators and value parsing:

| Kind | Operators |
| --- | --- |
| Text | = ≠, contains, starts / ends with, is one of, like, regex (PostgreSQL, MySQL), is empty, NULL checks, < > between |
| Number | = ≠ < ≤ > ≥, between, is one of, NULL checks, starts with / contains (as text) |
| Date, date-time | is on / before / after, between, is one of, is today / yesterday / this week / month / year, in the last N days |
| Boolean | is true / false · UUID, JSON, enums and other types: equality, lists and text matching |

Values are bound as parameters of the column's JDBC type, never spliced into SQL. Dates accept `2026-10-28`,
`2026-10-28 13:45:00+07`, or relative forms — `today`, `now-2h`, `today-7d`, `3 months ago` (units s, min, h, d, w, mo,
y) — re-resolved on every reload; the field shows what they resolve to and offers quick picks. A date on a date-time
column means the whole day (`created_at is on 2026-10-28` matches any time that day). Lists take commas or new lines,
quotes for values containing commas, and `NULL`. Text matching is case-insensitive unless **Aa** is on. Value fields
suggest real values from the column as you type (⌃Space to ask). Problems are flagged on their row before anything
runs. ⏎ in any field applies; the compiled `WHERE` clause is shown below the conditions, ready to copy or "Edit as SQL".
Right-click a cell → Filter by This Value adds a typed condition.

**SQL** takes a raw WHERE clause with the same completion as the editor against the viewed table — columns, operators,
and values from the data — without clauses that can't appear in a filter. Switching from Builder carries the conditions
over as SQL; switching back keeps hand-written SQL as a SQL condition.

## Data grid

Shared by table viewers and console results (`app/.../grid`). Columns stretch to fill the width; drag a header border
to resize (double-click to fit), drag a header to reorder, right-click a header to sort, hide or show columns. Rows and
columns are virtualized; row numbers stay pinned.

| Action | Shortcut |
| --- | --- |
| Move / extend selection | arrows / ⇧ arrows, Home/End, PgUp/PgDn, ⌘↑ ⌘↓ |
| Select all · copy (TSV) · copy with header | ⌘A · ⌘C · ⇧⌘C |
| Edit cell | Enter, F2, double-click, or just type |
| Commit edit · next cell · cancel | Enter · Tab · Esc |
| Add row · delete rows · revert rows · set NULL | ⌘N · ⌘⌫ · ⌥⌘Z · ⌥⌘N |
| Undo · redo the last change to the data | ⌘Z · ⇧⌘Z or ⌘Y |
| Submit pending changes | ⌘Enter |
| Sort · add secondary sort | click header · ⇧-click header |

Undo and redo step through pending changes one action at a time: a typed cell, a paste (with the rows it added), Set
NULL over a selection, deleted rows, a row edited as JSON, even *Revert all*. Each undo selects the cell it touched and
says what it undid; nothing reaches the database until you submit. The toolbar and the cell menu offer them too.

Right-click cells to copy as CSV / JSON / Markdown / SQL INSERT / IN-list or "Filter by This Value". Selecting several
cells shows their count, sum, average, min and max in the status bar.

**JSON cells** open as an editable tree: click a value to change it (Enter confirms, Esc drops it), click a boolean to
switch it, double-click a key to rename it, and use a row's type menu, + and trash buttons to change its type, add or
remove. ⌘Z and ⇧⌘Z undo and redo tree changes; ⌘Enter applies; *Text* switches to the raw JSON. The tree writes the value back only when something changes,
keeps key order and every number's exact digits, and refuses a number that isn't valid JSON, so it can't save broken
JSON. Values it can't edit losslessly (invalid JSON, a scalar, or an object that repeats a key) open as text.
A text column holding a JSON object or array (common in SQLite, MariaDB, SQL Server) opens in the same tree, but stays
text: the value is saved exactly as edited and never refused for not being JSON, and an edit changed back restores
the original text byte for byte.

**Foreign keys**: cells of a foreign-key column get an arrow; clicking it (or ⌘B) opens the referenced table filtered to
that row. Right-click a row → Show Referencing Rows opens the rows of other tables that point at it, e.g. a user's
orders.

**Value panel** (toolbar, right): the focused cell's full value. JSON opens as a collapsible tree; binary values show as
a hex dump, and images (PNG, JPEG, GIF, BMP up to 16 KB) as the picture; dates and timestamps also show in UTC, the local
time zone and Unix time. Grids show binary values as `0x…` hex (the first 16 KB).

In consoles, right-click a result tab → Pin Tab (or the pin in its footer) keeps that result when the console runs
again, to compare before and after. Edits stay pending
(highlighted) until submitted; they run in one transaction and are refused if a row changed underneath. Tables without
a primary key only allow inserts. On Windows/Linux use Ctrl instead of ⌘.

## Table structure

Each table tab switches between **Data** and **Structure** (toolbar, top left). Structure shows Columns, Indexes,
Foreign Keys, Constraints (primary key, unique, foreign key, check, exclusion), Triggers and the DDL, read from each
engine's own catalog: `pg_constraint` / `pg_trigger` / `pg_get_*def` on PostgreSQL (the DDL is assembled from them),
`SHOW CREATE TABLE` and `information_schema` on MySQL/MariaDB, `sqlite_master` on SQLite. Copy the DDL or open it in a
new console from the toolbar. In the explorer, right-click a table → Open Structure / Show DDL / Copy DDL, or
double-click a column, index or foreign key to open its section. Rows only load once the Data view is shown.

## Schema tools

Right-click a schema (or a connection, for its default schema) or a table in the explorer. Everything that changes the
schema or writes rows shows the statements first, respects read-only connections, and asks again on Production.
Transactional DDL (PostgreSQL, SQLite, SQL Server, DuckDB) runs all-or-nothing; elsewhere statements apply one by one
and stop at the first failure, which the dialog says up front.

- **Table designer** — *New Table…* / *Edit Structure…* (also the pencil in a table's Structure view). Columns, indexes
  and foreign keys as editable lists; renames are renames, not drop-and-add, and follow into indexes and keys. The SQL
  preview updates as you type (⌘S applies). SQLite changes it can't make in place rebuild the table the documented way:
  rows, indexes, triggers, UNIQUE and CHECK constraints come back, with foreign keys checked before committing.
- **Schema compare** — *Compare Schema With…*: any two relational connections and schemas (staging → production).
  Differences per table, each with a checkbox; drops start unchecked. The migration is written in the target's dialect:
  copy it, save it, open it in a console, or run it through the same confirmation dialog.
- **ER diagram** — *Show ER Diagram* / *Show in ER Diagram* (the table and its neighbours). Layered by foreign keys,
  crow's-foot ends, drag to arrange, pinch or ⌘-scroll to zoom, two-finger scroll to pan, find a table, double-click to open its rows. Export as PNG
  (light or dark, 2×) or Mermaid `erDiagram`.
- **Import data** — *Import Data…*: CSV/TSV (delimiter and encoding detected), Excel `.xlsx`, JSON arrays or NDJSON.
  Preview, then a new table (types inferred, names cleaned to snake_case) or an existing one (columns matched by name).
  Batched inserts in one transaction: a bad row is named by its line and nothing is kept; a table created for the
  import is dropped again.
- **Test data** — *Generate Test Data…*: a generator per column picked from its name and type (emails that match the
  name, phone numbers, prices, past timestamps…), NULL ratios, uniqueness, foreign keys drawn from the referenced
  table's rows. Same seed, same rows. Preview before inserting.

## Activity

Right-click a server connection → **Activity** (⌥⌘A / Ctrl+Alt+A for the current connection) lists the server's sessions
and what blocks what: sessions waiting on a lock sit indented under the session holding it. Filter Running / Blocked /
All, search by user, database or query, and refresh every 2, 5 or 10 s or pause; it only refreshes while the tab is in
front, over a connection of its own so it still works when the pool is stuck. Select a session for its full query, then
open it in a console, **Cancel Query** or **Terminate** it. Terminating always asks first, and so does cancelling on
Production or confirm-writes connections; read-only connections can't signal sessions at all. TableGrip's own sessions
(named `TableGrip` in `pg_stat_activity`) are hidden unless you turn off Hide TableGrip. Supported on PostgreSQL (and
PostgreSQL-compatible services), MySQL and MariaDB; on MySQL, lock waits come from `performance_schema.data_lock_waits`
(or `INNODB_LOCK_WAITS` on MariaDB) and TableGrip's sessions are named by the `program_name` connection attribute. File
and embedded databases don't offer it. While an Activity tab is open but not in front it refreshes every 15 s, and the
status bar warns when sessions have waited on a lock for more than 5 s; click the warning to see them.

## Backup and restore

Right-click a connection → **Back Up…** / **Restore…**. PostgreSQL uses `pg_dump` (custom, plain SQL or parallel
directory format), `pg_restore` (pick which tables and objects to restore from a custom or directory backup) and
`psql`; MySQL and MariaDB use `mysqldump` / `mariadb-dump` (`--single-transaction`, with routines, triggers and events)
and `mysql`. SQLite backs itself up with `VACUUM INTO`, DuckDB with `EXPORT DATABASE`. The dialog shows the equivalent
command to copy. Passwords go through `PGPASSWORD` or an owner-only `--defaults-extra-file` that is deleted afterwards,
never the command line; SSH tunnels work as for connections. Every run is logged to `~/.tablegrip/logs/backup/`.

Restores go into a **new database** by default. Read-only connections can't be restored into, and Production asks for
the target database's name to be typed again. A cancelled or failed backup deletes its partial file.

**Tools are never bundled.** TableGrip looks on PATH and in the usual places (Homebrew, Postgres.app, the EDB and MySQL
installers, `/usr/lib/postgresql`), picks the newest `pg_dump` that can read the server's version, and **Settings →
Tools** shows every copy found and lets you choose one by hand. When none fits it offers to download the official
PostgreSQL client tools (EDB's builds for macOS and Windows, pinned by SHA-256), saying where from, how big and under
which license, and only after you confirm; only `pg_dump`, `pg_restore`, `psql` and their libraries (~28 MB) are kept in
`~/.tablegrip/tools/`, removable from the same page. MySQL's clients are GPL and never downloaded: install them yourself.

**Scheduled backups** (Settings → Backups) are off until you turn them on. Each schedule backs up one connection every
hour, day or week while TableGrip is open, keeps its newest N backups (deleting only its own older ones), runs a missed
backup soon after the app opens, and says what would stop it (no tools, no saved password, a folder it can't write).

## Connections

Everything lives in `~/.tablegrip/` (override with `-Dtablegrip.home`): `connections.json` (no secrets),
`settings.json`, `themes/`, `history.jsonl`, `session.json`, `queries/` and `logs/`. `settings.json` can be edited by
hand (comments allowed; edits apply while the app runs), and `themes/` holds your own colour themes; see
[docs/plans/themes.md](docs/plans/themes.md). Passwords go to the OS credential store
via [java-keyring](https://github.com/javakeyring/java-keyring) under the service name `TableGrip`:
macOS Keychain, Windows Credential Manager, or the Secret Service (GNOME Keyring / KWallet) on Linux.

## Drivers

No JDBC driver ships with TableGrip. The connection dialog says what the chosen driver needs and offers it:

- **Catalog drivers** (PostgreSQL, MariaDB Connector/J, SQLite, SQL Server, ClickHouse, DuckDB, H2, HSQLDB, Derby,
  Jaybird for Firebird, Trino JDBC)
  download from Maven Central, or a mirror set in
  Settings → Drivers, into `~/.tablegrip/drivers/`. Every jar must match the SHA-256 pinned in
  `core/src/main/resources/dev/tablegrip/core/drivers/drivers.lock.json`, which `:core:lockDrivers` writes from the catalog
  in `core/build.gradle.kts`. The catalog only takes jars whose license a person reviewed and the allowlist accepts
  (permissive, or LGPL as a separate user download); `check` fails when the lockfile is stale.
- **Non-JDBC clients** (Lettuce for Redis, the MongoDB Java driver) download the same way; TableGrip's own adapter for each
  (`engines/redis`, `engines/mongodb`) ships
  inside the app as `engines/<name>.jar`, off the classpath, and loads together with the client's jars.
- **Your own jars** for drivers TableGrip can't distribute (MySQL Connector/J is GPL): choose them in the dialog.
- **Offline**: "Import from folder…" installs catalog drivers from jars already on disk, checked against the same hashes.

MySQL connections use MariaDB Connector/J by default. Each driver loads in its own class loader; `:app:verifyNoBundledDrivers`
(part of `check` and every `package*` task) fails if a driver ends up in the installer.
`:app:thirdPartyNotices` (also part of `check`) writes `THIRD-PARTY-NOTICES.txt` into the app jar and fails on any
shipped library whose license hasn't been reviewed into `bundledLicenses` in `app/build.gradle.kts`.

## Organising connections

Connections can sit in groups (folders in the explorer) and in Favorites, listed first. Drag a connection onto a group,
or onto any connection in it, to move it there; onto Favorites to star it. While dragging, two zones appear under the
tree: **No group** takes it out of its group and **＋ New group** asks for a name. Selected connections (⇧/⌘-click) move
together. Right-click a group for New Connection in it, Rename Group… (renaming to an existing name merges them) and
Ungroup; right-click a connection for Move to Group… and Remove from Group.

## Importing connections

**Import Connections** (the explorer's **+ ▾** menu, which says how many connections other tools have, the connection
menu in the title bar, or go-to-anything) finds connections other tools already have on this computer and lists them to tick:

| Source | Read from | Passwords |
| --- | --- | --- |
| `Connections.plist` | a macOS SQL client's `~/Library/Application Support/<app>/Data/Connections.plist`, folders from `ConnectionGroups.plist` | stay in that client's Keychain items |
| DBeaver | every project's `.dbeaver/data-sources*.json` in `workspace6` (macOS, Linux, Windows) | from `credentials-config.json`, unless protected by a master password |
| IDE data sources | `.idea/dataSources.xml` + `dataSources.local.xml` of each IDE's recent projects, global data sources, SSH configs | stay in the IDE's credential store |
| libpq | `~/.pgpass` (`$PGPASSFILE`), `~/.pg_service.conf` (`$PGSERVICEFILE`, `$PGSYSCONFDIR`) | yes |
| `.env` files | **Scan Project…**: any `*_URL` holding a connection URL, Laravel's `DB_*`, Docker's `POSTGRES_*` / `MYSQL_*`, libpq's `PG*` | yes |

**Add File…** takes any of these files (or an exported copy) and works out which it is. Names, folders (as groups),
environments (plist tags, DBeaver connection types), read-only flags and SSH tunnels carry over. Connections that
are already in TableGrip, or found in two tools, start unticked; PostgreSQL connections imported without a password get
one from `~/.pgpass` when it has a matching entry. Nothing is changed in the other tools. With no connections yet, the
explorer says how many it found and offers to import them.

In the connection dialog's SSH tab, **From config** fills the tunnel from a `Host` in `~/.ssh/config` (following
`Include` and `Host *` defaults).

## Connecting

Paste any of these into **New Connection → Paste** and press Parse:

| Form | Example |
| --- | --- |
| libpq URI / keyword-value | `postgresql://user:pass@h1:5432,h2:5433/app?sslmode=require` · `host=db port=5432 dbname='my db'` |
| pgjdbc | `jdbc:postgresql://[::1]:5740/accounting?currentSchema=app` |
| MySQL / MariaDB | `mysql://user:pass@host/db?ssl-mode=REQUIRED` · `jdbc:mariadb://host/db?sslMode=verify-full` |
| Advanced Connector/J, MariaDB | `jdbc:mysql:replication://…`, `address=(host=…)(port=…)` — kept as a raw JDBC URL |
| SQLite | `jdbc:sqlite:/path/app.db` · `sqlite:///path/app.db` · `~/data/app.sqlite3` |
| SQL Server, ClickHouse, DuckDB, H2, HSQLDB, Derby | `jdbc:sqlserver://db:1433;databaseName=shop` · `jdbc:clickhouse://ch:8123/default` · `jdbc:duckdb:/data/a.duckdb` |
| Oracle | `jdbc:oracle:thin:@//db:1521/FREEPDB1`; in the form the database field is the service name. Bring `ojdbc11.jar` (Oracle's license) |
| Firebird | `jdbc:firebird://db:3050//var/lib/firebird/data/app.fdb` · `jdbc:firebirdsql://db/employee` (an alias) |
| Trino | `jdbc:trino://trino:8080/hive/sales`; in the form the database field is `catalog` or `catalog/schema` |
| Redis, Valkey (URL) | `redis://:pass@cache:6379` · `rediss://` (TLS) · `valkey://host` |
| MongoDB | `mongodb://user@host:27017/shop?authSource=admin` · `mongodb+srv://user@cluster0.example.net/shop` (password goes to the keychain) |
| etcd | host and port (2379); user and password if auth is on; `ssl=true` for HTTPS. Built in: nothing to download |
| Redis, Valkey, KeyDB, Dragonfly | host and port in the form; properties `mode=cluster` / `mode=sentinel` + `sentinelMaster=…`, `ssl=true` |
| Any other JDBC driver | `jdbc:db2://…`, `jdbc:snowflake://…` — becomes "Other (JDBC driver)"; choose the driver's jar |

libpq parameters are translated to pgjdbc properties (`application_name` → `ApplicationName`, `connect_timeout` →
`connectTimeout`, `target_session_attrs` → `targetServerType`, `ssl=true` → `sslmode=require`). Passwords found in a
string go to the keychain, never into the profile.

**Drivers:** MySQL and MariaDB
servers can use either Connector/J or MariaDB Connector/J; TableGrip writes the URL in the chosen driver's scheme (adding
`permitMysqlScheme` when MariaDB Connector/J gets a `jdbc:mysql:` URL), translates `sslMode` values between the two
vocabularies, and sets a 10 s connect timeout where the driver would otherwise wait forever. Extra driver properties go
in **Options → Driver properties**.

## Safety

Each connection can be tagged with an environment (colours its badge everywhere; Production also turns on
confirmations), made **read-only** (writes and DDL are refused before reaching the server), and set to **confirm
writes**. DELETE/UPDATE without WHERE, DROP and TRUNCATE always ask first. Consoles default to auto-commit; switch to
manual commit to review changes and Commit/Roll back, and closing a console with pending work asks what to do.

**AI agents** only get what Settings › Agent › *Data shared with agents* allows: *Schema only* by default (names, types,
DDL, your SQL and error messages, never values), *Schema and sample data*, or *Off*. A connection can override it under
Options; production connections never share data unless their own Options say so. The same rules apply to AI apps
connected through the [MCP server](#mcp-server), which may also change data only where a connection's *Agent writes*
option allows it.

## MCP server

AI apps such as Claude Desktop, Claude Code, Cursor and VS Code can use your saved connections through TableGrip's
MCP server, `tablegrip mcp`. Turn it on in **Settings › MCP**, install the `tablegrip` command and add TableGrip to
your apps from the same page. Each connection's **Options › AI agents** decides whether apps see its schema only or
its data too, and **Options › Agent writes** whether they may change anything. Writes on production connections and
risky statements ask you first. Calls are listed under **Settings › MCP › Recent activity**, and the statements they
run appear in Query History marked *MCP*.

Setup for each app, every tool, and troubleshooting: **[MCP server guide](mcp.md)**.

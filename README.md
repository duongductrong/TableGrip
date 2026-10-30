# Datagrok

A desktop database client (in the spirit of TablePlus / DataGrip) built with Kotlin,
Compose for Desktop and [Jewel](https://jewel-ui.dev) — the IntelliJ look & feel for Compose.

## Requirements

- JDK 17+ to run Gradle.
- **JetBrains Runtime (JBR) 25** to run the app. Jewel's decorated window only works on JBR.
  Gradle provisions it automatically via the foojay toolchain resolver on the first `run`.

## Commands

```bash
scripts/run.sh                      # launch the app (--fresh: throwaway profile; scripts\run.cmd on Windows)
./gradlew :app:run                  # launch the app
./gradlew :app:run -Pdatagrok.home=/tmp/dg   # launch with a throwaway profile/settings/history directory
./gradlew :core:test                # run core tests
./gradlew :core:test -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306   # also test against real servers (seed: core/src/test/resources/structure-it)
./gradlew :core:test -Pit.network=true          # also download the real catalog drivers from Maven Central
./gradlew :core:lockDrivers                      # after changing the driver catalog in core/build.gradle.kts
./gradlew :app:packageDistributionForCurrentOS   # build a native installer (dmg/msi/deb)
```

In VS Code, ⇧⌘B (Ctrl+Shift+B) runs **Build & Launch** from `.vscode/tasks.json`. Zed picks up `.zed/tasks.json`;
bind ⇧⌘B to it in `~/.config/zed/keymap.json`:
`{ "context": "Workspace", "bindings": { "cmd-shift-b": ["task::Spawn", { "task_name": "Build & Launch" }] } }`.

## Layout

| Module | Purpose |
| --- | --- |
| `core` | UI-free domain: connection profiles + keychain, `Database` (pooled, introspection, paging) and per-console `QuerySession`s with cancellation, SQL dialects, lexer and statement splitter, settings, query history. |
| `app`  | Compose Desktop + Jewel UI, one package per feature: `connections`, `explorer`, `workspace`, `console`, `table`. |

Versions live in `gradle/libs.versions.toml`. Jewel (`0.41.0-262.10968.63`) is pinned to Compose
`1.12.x` and Kotlin `2.3.20`; bump them together following the
[Jewel samples](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel/samples/standalone).

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
next start, from `~/.datagrok/session.json`, saved every few seconds while something changes. Quitting only asks about
work a restart can't bring back: open transactions and unsubmitted edits. Tables load once their tab is shown.

**Explorer**: the search field narrows the tree to connections, and to tables and columns already loaded, whose name
matches. Right-click a connection → Add to Favorites (listed first) or Move to Group…; the group is also under
Options in the connection dialog.

## Saved queries

⌘S in a new console names the query and saves it under Saved Queries (⌘4), like TablePlus; later ⌘S saves in place and
⇧⌘S saves to a file anywhere. Queries are plain `.sql` files in `~/.datagrok/queries/`, folders included, so they can be
edited elsewhere or kept in git. "Always run on <connection>" writes a `-- connection: <name>` first line, which opens
the query on that connection; without it a query opens on the current one.

**Parameters**: `:name` and `${name}` outside strings and comments (`a::int` is a cast) ask for values before running,
as in DataGrip. Values go in as written: `'paid'` for text, `42` for a number. The last values are offered again.

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
| Add row · delete rows · revert rows · set NULL | ⌘N · ⌘⌫ · ⌘Z · ⌥⌘N |
| Submit pending changes | ⌘Enter |
| Sort · add secondary sort | click header · ⇧-click header |

Right-click cells to copy as CSV / JSON / Markdown / SQL INSERT / IN-list or "Filter by This Value". Selecting several
cells shows their count, sum, average, min and max in the status bar.

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

## Connections

Everything lives in `~/.datagrok/` (override with `-Ddatagrok.home`): `connections.json` (no secrets),
`settings.json`, `history.jsonl`, `session.json`, `queries/` and `logs/`. Passwords go to the OS credential store
via [java-keyring](https://github.com/javakeyring/java-keyring) under the service name `Datagrok`:
macOS Keychain, Windows Credential Manager, or the Secret Service (GNOME Keyring / KWallet) on Linux.

## Drivers

No JDBC driver ships with Datagrok. The connection dialog says what the chosen driver needs and offers it:

- **Catalog drivers** (PostgreSQL, MariaDB Connector/J, SQLite, SQL Server, ClickHouse, DuckDB, H2, HSQLDB, Derby)
  download from Maven Central, or a mirror set in
  Settings → Drivers, into `~/.datagrok/drivers/`. Every jar must match the SHA-256 pinned in
  `core/src/main/resources/dev/datagrok/core/drivers/drivers.lock.json`, which `:core:lockDrivers` writes from the catalog
  in `core/build.gradle.kts`. The catalog only takes jars whose license a person reviewed and the allowlist accepts
  (permissive, or LGPL as a separate user download); `check` fails when the lockfile is stale.
- **Non-JDBC clients** (Lettuce for Redis, the MongoDB Java driver) download the same way; Datagrok's own adapter for each
  (`engines/redis`, `engines/mongodb`) ships
  inside the app as `engines/<name>.jar`, off the classpath, and loads together with the client's jars.
- **Your own jars** for drivers Datagrok can't distribute (MySQL Connector/J is GPL): choose them in the dialog.
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
| TablePlus | `~/Library/Application Support/com.tinyapp.TablePlus/Data/Connections.plist` (also Setapp), folders from `ConnectionGroups.plist` | stay in TablePlus' Keychain items |
| DBeaver | every project's `.dbeaver/data-sources*.json` in `workspace6` (macOS, Linux, Windows) | from `credentials-config.json`, unless protected by a master password |
| DataGrip and other JetBrains IDEs | `.idea/dataSources.xml` + `dataSources.local.xml` of each IDE's recent projects, global data sources, SSH configs | stay in the IDE's credential store |
| libpq | `~/.pgpass` (`$PGPASSFILE`), `~/.pg_service.conf` (`$PGSERVICEFILE`, `$PGSYSCONFDIR`) | yes |
| `.env` files | **Scan Project…**: any `*_URL` holding a connection URL, Laravel's `DB_*`, Docker's `POSTGRES_*` / `MYSQL_*`, libpq's `PG*` | yes |

**Add File…** takes any of these files (or an exported copy) and works out which it is. Names, folders (as groups),
environments (TablePlus tags, DBeaver connection types), read-only flags and SSH tunnels carry over. Connections that
are already in Datagrok, or found in two tools, start unticked; PostgreSQL connections imported without a password get
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
| Redis, Valkey (URL) | `redis://:pass@cache:6379` · `rediss://` (TLS) · `valkey://host` |
| MongoDB | `mongodb://user@host:27017/shop?authSource=admin` · `mongodb+srv://user@cluster0.example.net/shop` (password goes to the keychain) |
| etcd | host and port (2379); user and password if auth is on; `ssl=true` for HTTPS. Built in: nothing to download |
| Redis, Valkey, KeyDB, Dragonfly | host and port in the form; properties `mode=cluster` / `mode=sentinel` + `sentinelMaster=…`, `ssl=true` |
| Any other JDBC driver | `jdbc:firebirdsql://…`, `jdbc:oracle:thin:@…` — becomes "Other (JDBC driver)"; choose the driver's jar |

libpq parameters are translated to pgjdbc properties (`application_name` → `ApplicationName`, `connect_timeout` →
`connectTimeout`, `target_session_attrs` → `targetServerType`, `ssl=true` → `sslmode=require`). Passwords found in a
string go to the keychain, never into the profile.

**Drivers:** PostgreSQL JDBC, MySQL Connector/J, MariaDB Connector/J and SQLite JDBC are bundled. MySQL and MariaDB
servers can use either Connector/J or MariaDB Connector/J; Datagrok writes the URL in the chosen driver's scheme (adding
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
Options; production connections never share data unless their own Options say so.

## Roadmap

- **M1 – daily driver** (done): explorer tree, table viewer (paging, sort, WHERE filter, count), SQL console
  (highlighting, run statement at caret / selection / script, cancel, output log, history).
- **M2 – safe editing** (done): inline editing with preview + submit, manual/auto-commit transactions in consoles,
  read-only connections, confirm-before-write and dangerous-statement warnings, environment colours (PROD/STG/DEV),
  SSH tunnels (trust-on-first-use host keys), streaming export to CSV/TSV/JSON/SQL/Markdown.
- **M3 – editor** (done): context-aware completion with FK joins and value suggestions, formatter, EXPLAIN,
  find/replace, line actions, smart table filters, saved queries, parameter prompts. Next: snippets.
- **M4 – schema tools** (done): table designer, schema compare and migrations, ER diagram, CSV/Excel/JSON import,
  test data generator.
- **M5 – ship**: go-to-anything and session restore (done); signed installers, auto-update.

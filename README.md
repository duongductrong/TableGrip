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

**Completion** understands the statement around the caret: tables, views and schemas after `FROM`/`JOIN`, columns of
the tables and aliases in scope (qualified when several tables are joined), `alias.` / `schema.` members, CTE columns,
`INSERT INTO t (` column lists, operators after a column, functions and keywords. After `JOIN` it proposes whole join
clauses from foreign keys (`JOIN orders o ON o.user_id = u.id`). Matching is fuzzy by word starts (`uid` → `user_id`),
and inside `col = '…'` or `col IN (…)` it suggests real values sampled from the column. Metadata loads lazily per
connection and refreshes after DDL or an explorer refresh.

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

Right-click cells to copy as CSV / JSON / Markdown / SQL INSERT / IN-list or "Filter by This Value". Edits stay pending
(highlighted) until submitted; they run in one transaction and are refused if a row changed underneath. Tables without
a primary key only allow inserts. On Windows/Linux use Ctrl instead of ⌘.

## Table structure

Each table tab switches between **Data** and **Structure** (toolbar, top left). Structure shows Columns, Indexes,
Foreign Keys, Constraints (primary key, unique, foreign key, check, exclusion), Triggers and the DDL, read from each
engine's own catalog: `pg_constraint` / `pg_trigger` / `pg_get_*def` on PostgreSQL (the DDL is assembled from them),
`SHOW CREATE TABLE` and `information_schema` on MySQL/MariaDB, `sqlite_master` on SQLite. Copy the DDL or open it in a
new console from the toolbar. In the explorer, right-click a table → Open Structure / Show DDL / Copy DDL, or
double-click a column, index or foreign key to open its section. Rows only load once the Data view is shown.

## Connections

Everything lives in `~/.datagrok/` (override with `-Ddatagrok.home`): `connections.json` (no secrets),
`settings.json`, `history.jsonl` and `logs/`. Passwords go to the OS credential store
via [java-keyring](https://github.com/javakeyring/java-keyring) under the service name `Datagrok`:
macOS Keychain, Windows Credential Manager, or the Secret Service (GNOME Keyring / KWallet) on Linux.

## Connecting

Paste any of these into **New Connection → Paste** and press Parse:

| Form | Example |
| --- | --- |
| libpq URI / keyword-value | `postgresql://user:pass@h1:5432,h2:5433/app?sslmode=require` · `host=db port=5432 dbname='my db'` |
| pgjdbc | `jdbc:postgresql://[::1]:5740/accounting?currentSchema=app` |
| MySQL / MariaDB | `mysql://user:pass@host/db?ssl-mode=REQUIRED` · `jdbc:mariadb://host/db?sslMode=verify-full` |
| Advanced Connector/J, MariaDB | `jdbc:mysql:replication://…`, `address=(host=…)(port=…)` — kept as a raw JDBC URL |
| SQLite | `jdbc:sqlite:/path/app.db` · `sqlite:///path/app.db` · `~/data/app.sqlite3` |

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

## Roadmap

- **M1 – daily driver** (done): explorer tree, table viewer (paging, sort, WHERE filter, count), SQL console
  (highlighting, run statement at caret / selection / script, cancel, output log, history).
- **M2 – safe editing** (done): inline editing with preview + submit, manual/auto-commit transactions in consoles,
  read-only connections, confirm-before-write and dangerous-statement warnings, environment colours (PROD/STG/DEV),
  SSH tunnels (trust-on-first-use host keys), streaming export to CSV/TSV/JSON/SQL/Markdown.
- **M3 – editor** (done): context-aware completion with FK joins and value suggestions, formatter, EXPLAIN,
  find/replace, line actions, smart table filters. Next: snippets, parameter prompts.
- **M4 – schema tools**: structure editor, DDL, CSV import, more drivers.
- **M5 – ship**: go-to-anything, session restore, signed installers, auto-update.

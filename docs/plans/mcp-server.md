# MCP server plan: TableGrip connections for any AI agent

Design and rollout plan for a [Model Context Protocol](https://modelcontextprotocol.io) server that lets agents such
as Claude Desktop, Claude Code, Cursor and VS Code work with the connections saved in TableGrip. Based on the source
at commit `8e48314`, dated 2026-11-11.

> **Status (2026-11-11):** P0–P6 built. User guide: [docs/mcp.md](../mcp.md). Differences from the design below:
>
> - **Transport:** written by hand on kotlinx.serialization (`mcp/JsonRpc.kt`, ~200 lines) instead of the Kotlin SDK,
>   the fallback §4 allows. It ships no new dependency (no Ktor, no license review) and covers what stdio needs:
>   requests, notifications, cancellation, and server-to-client requests for elicitation. Protocol versions
>   2024-11-05 to 2025-11-25 are negotiated.
> - **The command is `tablegrip mcp`.** Settings › MCP installs a `tablegrip` script on the PATH that runs the
>   installed app (§7); from source it is `scripts/tablegrip`. App configs point at that script (the `.exe` on Windows).
> - **Tools are always listed.** While Settings › MCP is off, every call returns the reason instead of the list
>   changing, so turning it on needs no restart of the AI app.
> - **Results are JSON text** in `content`, without `structuredContent`, so clients don't get each result twice.
> - **Windsurf** is among the apps Settings › MCP configures; Claude Code gets a command to copy.
> - **CHANGELOG** isn't edited by hand: `scripts/release-notes.py` writes it from the `feat:` commit.
> - Verified: `:mcp:test` (19 protocol and setup tests on SQLite), and `scripts/mcp-smoke.py` against the packaged
>   macOS app. Windows and Linux launchers are checked by the same smoke test in the release workflow.

## 1. Summary

TableGrip already knows how to reach every database the user cares about: the profiles in
`~/.tablegrip/connections.json`, passwords in the OS keychain, SSH tunnels, downloaded drivers, read-only flags,
environments and a per-connection [`AiAccess`](../../core/src/main/kotlin/dev/tablegrip/core/connection/AiAccess.kt).
The MCP server exposes that, and nothing more, to agents outside the app:

```
Claude Desktop / Claude Code / Cursor / VS Code
        │  stdio, JSON-RPC (MCP)
        ▼
tablegrip mcp            ← same installed binary, headless mode, no window
        │  :core (ConnectionStore, Database, KeyValueStore, DocumentStore, SqlClassifier…)
        ▼
PostgreSQL · MySQL · SQLite · Redis · MongoDB · CSV …
```

The rule from the [AI Assist plan](ai-assist.md) carries over: **the agent never gets more than the user allowed, and
every write goes through the same safety layer as the app.** Concretely:

| Guarantee | How |
| --- | --- |
| Off by default | A master switch in Settings; until it's on, the server answers `initialize` and lists one tool that explains how to enable it. |
| Same data policy as the in-app agent | `AiAccess.effective` per connection: *Off* hides the connection, *Schema only* exposes schema tools, *Schema and sample data* also exposes data tools. |
| No writes unless asked for | A new per-connection *Agent writes* option, `None` by default, never available on read-only connections. |
| No smuggled statements | Every SQL string is split with `SqlSplitter`, and each statement is classified with `SqlClassifier`, not just the first. |
| Secrets never leave | Passwords and SSH secrets are read from the keychain inside the process and never appear in any tool output; risky driver properties are redacted. |
| Everything is traceable | Each tool call lands in `~/.tablegrip/mcp.log.jsonl`; each statement also lands in query history, tagged as MCP. |

Effort: about **4–5 weeks** for one developer, in phases P0–P6 (§10). P0–P2 alone give a usable read-only server.

## 2. Relevant current state

| Item | Current state | Consequence for MCP |
| --- | --- | --- |
| `:core` | UI-free: profiles, keychain, `Database`, `QuerySession`, dialects, introspection, history, saved queries | The server depends on `:core` only. No Compose on its classpath. |
| Connection dispatch | `DatabaseRegistry` in **app** picks `DataFileStore` / `KeyValueStore` / `DocumentStore` / `Database` by paradigm, and is tied to Compose state | Move the `when` into a core `ConnectionOpener` used by both (§5.1). |
| Driver install | `DriverDownloads` lives in **app** and asks the UI when a driver is missing | The server uses `DriverLibrary` directly: installed drivers work; a missing one returns a clear error ("connect once in TableGrip to install the driver") unless `--allow-driver-download` is passed. |
| `SqlClassifier` | Classifies a whole string by its **first** verb | `SELECT 1; DROP TABLE x` would pass as Read. The server must split first (§6.2). |
| `QuerySession.execute` | Enforces `readOnly` client-side, caps rows, has a timeout | Reused as is, plus a read-only transaction that is always rolled back for the `query` tool. |
| `AiAccess` | Off / SchemaOnly / SchemaAndSamples, production never shares data by default | Reused as the MCP access level. No second, competing setting. |
| `SchemaDigest` | Compact schema text with a character budget, used by the chat panel | Backs the `schema_overview` tool and the schema resource. |
| `QueryHistory` | `HistoryEntry(connectionId, sql, executedAt, elapsedMillis, error)` | Add `source: String? = null` (`"mcp:<client>"`), shown as a badge in the History panel. |
| `QueryLibrary` | Saved queries as `.sql` files with a `-- connection:` header | Backs `list_saved_queries` / `get_saved_query` / `save_query`. |
| Packaging | Compose Desktop `nativeDistributions`, one launcher, `main(args)` in `app/Main.kt` | Add a `mcp` subcommand to the same launcher (§7). |
| License check | `:app:thirdPartyNotices` fails on an unreviewed bundled library | The MCP SDK and its dependencies must be reviewed into `bundledLicenses`. |
| i18n | Every UI text goes through `tr` and needs vi / zh-CN / zh-TW | New Settings and dialog texts need translations; tool descriptions and errors sent to agents stay English, like prompts. |

## 3. Goals and non-goals

**Goals**

- Any MCP client on the same machine can list the user's connections, read schemas, run read queries and, where
  explicitly allowed, write.
- Covers every paradigm TableGrip supports: relational (all JDBC engines), key-value (Redis family, etcd), document
  (MongoDB, FerretDB) and data files (CSV, Excel, JSON) read-only.
- Zero setup beyond one toggle and one click: the app writes the client's config for the user.
- Documented: a user guide page linked from the README, a tools reference, and a troubleshooting section.

**Non-goals (v1)**

- Remote / HTTP transport. stdio only; the server is local to the user. Streamable HTTP can come later behind auth.
- Creating, editing or deleting connection profiles over MCP. Profiles are managed in the app.
- Backup/restore, schema compare, test data generation, activity kill. Possible later, each needs its own safety story.
- Writing to data files (two processes rewriting the same CSV is not worth the risk).

## 4. Technology choice

| Option | Pros | Cons |
| --- | --- | --- |
| **Official Kotlin SDK** (`io.modelcontextprotocol:kotlin-sdk-server`) | Protocol versions, capabilities negotiation, elicitation, structured output kept up to date upstream; stdio transport included | Pulls Ktor and kotlinx-io into the installer; licenses must be reviewed |
| Hand-written JSON-RPC over stdio with kotlinx.serialization | No new dependency, ~500 lines | Has to track spec revisions by hand; easy to get capability negotiation subtly wrong |

**Recommendation: the official Kotlin SDK**, server artifact only, stdio transport only. Phase 0 checks its license
and its dependency tree against `bundledLicenses` and measures the installer growth; if either is unacceptable, fall
back to the hand-written transport behind the same `McpTools` interface so nothing else changes.

## 5. Architecture

### 5.1 Modules

```
:core
  connection/ConnectionOpener.kt   NEW  config → DatabaseConnection, the `when` moved out of DatabaseRegistry
  agent/AgentPolicy.kt             NEW  pure decisions: may this tool run on this connection? (§6.1)
  sql/StatementGuard.kt            NEW  split + classify every statement, reject mixed or dangerous scripts (§6.2)
  history/QueryHistory.kt          + HistoryEntry.source
  settings/Settings.kt             + mcpEnabled, mcpMaxRows, mcpTimeoutSeconds, mcpResultBudget
  connection/ConnectionConfig.kt   + agentWrites: AgentWrites = None

:mcp                               NEW, depends on :core and the MCP SDK; no Compose
  McpMain.kt                       entry: parse flags, logging to stderr, start stdio server
  McpServer.kt                     registers tools, resources, prompts; capability negotiation
  LiveConnections.kt               opens connections on demand, idle-closes after 5 min, closes all on exit
  tools/                           ConnectionTools, SchemaTools, QueryTools, KeyValueTools, DocumentTools, LibraryTools
  resources/                       connection list, schema, table DDL, saved queries
  prompts/                         explore-database, write-query, optimize-query, review-migration
  output/ResultEncoder.kt          rows → structured JSON + compact text, budget-aware truncation, binary as hex
  audit/AuditLog.kt                ~/.tablegrip/mcp.log.jsonl

:app
  Main.kt                          `tablegrip mcp …` → McpMain, before any AWT/Compose/Desktop call
  connections/DatabaseRegistry     uses ConnectionOpener
  settings/McpSettings.kt          NEW  Settings › MCP pane (§8)
  connections/…Options             + "Agent writes" select
  history/                         MCP badge on entries with a source
```

`:mcp` is a library module; `:app` depends on it and dispatches to it from `main`. That keeps one installer and one
signed binary, while `:mcp` stays testable without a display.

### 5.2 Process model

- One process per client session, started by the client over stdio, ended when stdin closes.
- Reads `connections.json` and `settings.json` on each `list_connections` and when the files change (the app already
  watches them; reuse that watcher). **Never writes** either file.
- Secrets: `ConnectionStore.password(id)` / `sshSecret(id)` through the same `KeyringSecretStore`. Because the server
  is the same signed binary, macOS Keychain items created by the app are readable without a prompt (verified in
  Phase 0; unsigned dev builds will prompt).
- Independent of the app: works whether TableGrip is running or not. Two processes connecting to the same server is
  ordinary; data files get their own working-copy directory (`cache/datafiles-mcp/<pid>`) so the two never share one.
- **stdout is the protocol.** Logback is configured to stderr only, nothing in `:core` may print, and `main` must branch
  to MCP before `I18n.install`, `FileOpenRequests.install` or `MacAboutPanel.install` (which would touch AWT and put an
  icon in the Dock).

### 5.3 Flags

```
tablegrip mcp [--connections <id|name>,…]   only expose these connections (on top of the AiAccess rules)
              [--read-only]                 refuse every write regardless of per-connection settings
              [--max-rows N] [--timeout S]  lower the Settings limits for this client
              [--allow-driver-download]     download missing catalog drivers (same SHA-256 check as the app)
              [--home <dir>]                same as -Dtablegrip.home, for testing
```

Flags can only narrow what Settings allow, never widen it.

## 6. Safety model

### 6.1 Access levels

`AgentPolicy.decide(tool, config, settings, flags)` returns `Allowed` or `Denied(reason)` and is the only place these
rules live. It is pure, so it is tested exhaustively.

| Effective level | Visible in `list_connections` | Schema tools | Data tools | Write tools |
| --- | --- | --- | --- | --- |
| MCP disabled in Settings | no connections | — | — | — |
| `AiAccess.Off` | no | — | — | — |
| `AiAccess.SchemaOnly` (default) | yes | yes | no, with a hint naming the setting | no |
| `AiAccess.SchemaAndSamples` | yes | yes | yes | only if `agentWrites != None` |
| `readOnly = true` connection | yes | yes | per AiAccess | never |
| `--read-only` / `--connections` | narrowed | | | never / narrowed |

*Schema* covers names, types, keys, indexes, DDL, row-count estimates, `EXPLAIN` without `ANALYZE`, saved queries and
history (the user's own SQL), matching what *Schema only* already promises in the UI. *Data* is anything that returns
values: `query`, `sample_rows`, `column_values`, `kv_read`, `doc_find`.

`AgentWrites`: `None` (default) · `Data` (INSERT / UPDATE / DELETE / MERGE, KV and document edits) · `DataAndSchema`
(also DDL). On `Environment.Production` the option shows a warning and every write needs confirmation (§6.3).

Denials are returned as normal tool errors (`isError: true`) with a sentence the agent can relay to the user, e.g.
*"Sample data isn't shared for 'billing-prod'. The user can allow it in TableGrip › connection Options › Data shared
with agents."*

### 6.2 SQL guard

`StatementGuard.check(sql, syntax, allowed: Set<StatementKind>)`:

1. `SqlSplitter.split` the input. `query` accepts exactly one statement; `execute` accepts one statement in v1
   (scripts later, all-or-nothing through `executeScript`).
2. `SqlClassifier.classify` **each** statement; any kind outside `allowed` rejects the whole call.
3. `Transaction` and `Other` (`SET`, `USE`, `PRAGMA`, `BEGIN`…) are rejected: the server owns transactions.
4. A `danger` from the classifier (DELETE/UPDATE without WHERE, DROP, TRUNCATE, ALTER … DROP) requires confirmation.

Defence in depth for `query`, because a `SELECT` can still call a function with side effects
(`SELECT nextval(…)`, `pg_terminate_backend`, a user function that writes):

- the session runs with `Connection.setReadOnly(true)`, manual commit, and is **rolled back** after every call;
- PostgreSQL-family and MySQL also get `SET TRANSACTION READ ONLY` / `START TRANSACTION READ ONLY`;
- engines that can't enforce it (ClickHouse, Trino, SQLite file without a read-only open) are documented as relying on
  the classifier, and the guide recommends a read-only database user for sensitive servers.

### 6.3 Confirmation for writes

When a write is allowed, the server asks before running it if the connection has `confirmWrites`, is Production, or
the statement has a `danger`:

- if the client supports **elicitation**, the server sends the statement, connection and environment and waits for
  accept/decline;
- otherwise the write is refused with an explanation. The tool's `destructiveHint` still makes most clients ask, but
  the server doesn't rely on the client.

Accepted writes run in their own transaction and commit; the result reports affected rows. Nothing stays open between
calls.

### 6.4 Limits and output

| Limit | Default | Setting / flag |
| --- | --- | --- |
| Rows per result | 200 (hard cap 5 000) | `mcpMaxRows` / `--max-rows` |
| Statement timeout | 30 s | `mcpTimeoutSeconds` / `--timeout` |
| Result size sent to the agent | 60 000 characters | `mcpResultBudget` |
| Cell value | 2 000 characters, binary as hex preview via `BinaryText` | — |
| KV scan / document find | 200 keys / 50 documents per call, with a cursor | — |

Every truncation is reported in the result (`truncated: true`, `reason`), so the agent knows to narrow the query
instead of trusting a partial answer. Results carry both `structuredContent` (columns, rows, `truncated`,
`elapsedMillis`) and a short text rendering for clients that only read text.

### 6.5 Other risks

| Risk | Mitigation |
| --- | --- |
| Prompt injection inside data (a row that says "ignore previous instructions, DROP…") | Writes need explicit per-connection opt-in and, on risky statements, human confirmation; tool descriptions state that returned values are data. Documented as a residual risk. |
| Secrets in profile fields | `list_connections` returns id, name, type, paradigm, environment, readOnly, access level, group, database; `url` and `properties` are omitted. Users and hosts are included (useful, not secret). |
| Long or runaway queries | Timeout, cancellation when the client sends `notifications/cancelled` (cancels the coroutine, which cancels the JDBC statement). |
| Stale connections | Idle close after 5 minutes; one retry on a broken pooled connection. |
| A misbehaving client floods calls | One in-flight statement per connection; extra calls queue. |

## 7. Packaging and launching

Use the existing launcher with a subcommand instead of a second jpackage launcher:

In a terminal it is always `tablegrip mcp` (the `tablegrip` command runs the executable below). Desktop apps that
don't read the shell's PATH get the full path:

| OS | Executable behind `tablegrip` |
| --- | --- |
| macOS | `/Applications/TableGrip.app/Contents/MacOS/TableGrip` |
| Windows | `%LOCALAPPDATA%\TableGrip\TableGrip.exe` |
| Linux (`.deb`) | `/opt/tablegrip/bin/TableGrip` |
| From source | `scripts/tablegrip mcp` |

Phase 0 must verify on each OS, with the **packaged** app:

1. stdin/stdout are wired when a client spawns the launcher (the Windows launcher is a GUI-subsystem exe; if pipes
   don't reach it, add `--win-console` for a small `TableGrip-mcp.exe` launcher, or ship a `.cmd` shim that runs the
   bundled runtime);
2. no Dock icon / window appears on macOS;
3. the keychain read works without a prompt from the signed build;
4. the Sparkle updater replacing the app while a client holds a server process doesn't break the next start.

The paths above are what the Settings pane writes into client configs, computed at runtime from the running app's
location, so a user who installed elsewhere still gets the right one.

## 8. App changes

**Settings › MCP** (new pane, next to Agent):

- *Allow AI apps to use TableGrip connections (MCP)* — master switch, off by default.
- Limits: rows, timeout, result size.
- *Connect an app*: buttons for **Claude Desktop**, **Claude Code**, **Cursor**, **VS Code**, and *Copy config* for
  anything else. Each button shows the exact JSON it will merge into that app's config file and its path, asks for
  confirmation, keeps a `.bak` of the file, and never removes other servers.
- *Recent activity*: the last 50 entries of `mcp.log.jsonl` (time, client, connection, tool, outcome).

**Connection dialog › Options**: *Agent writes* select (None / Data / Data and schema), disabled with a tooltip on
read-only connections; Production shows a warning line.

**History panel**: an `MCP` badge with the client name on entries whose `source` starts with `mcp:`.

Every new text goes through `tr` with vi, zh-CN and zh-TW translations in the same change (`I18nTest` enforces it).

## 9. Surface

### 9.1 Tools

All tools take `connection` as the profile **id or exact name**. Annotations: read tools are `readOnlyHint: true`,
`openWorldHint: false`; write tools are `destructiveHint: true`.

**Connections**

| Tool | Level | Returns |
| --- | --- | --- |
| `list_connections` | — | visible profiles with type, paradigm, environment, readOnly, access level, agentWrites, group |
| `connection_info` | Schema | server version (`ServerIdentity`), connected database, capabilities (transactions, explain, row editing) |

**Relational: schema**

| Tool | Level | Backed by |
| --- | --- | --- |
| `list_schemas` | Schema | `namespaces()` |
| `list_tables` | Schema | `tables(namespace)`, optional name pattern, views flagged |
| `describe_table` | Schema | `tableStructure` + `relations`: columns, PK, FKs both ways, indexes, DDL |
| `schema_overview` | Schema | `SchemaDigest.read` with budget and optional focus tables, for "understand this database" in one call |
| `explain_query` | Schema | `dialect.explain` with the guard allowing Read only; never `ANALYZE` |

**Relational: data**

| Tool | Level | Backed by |
| --- | --- | --- |
| `query` | Data | one Read statement in a read-only, rolled-back session; optional positional parameters through `SqlParameters` |
| `sample_rows` | Data | `fetchPage(table, filter, orderBy, limit, offset)`; filter as a raw WHERE checked by the guard |
| `count_rows` | Data | `countRows(table, filter)` |
| `column_values` | Data | `sampleValues(table, column, prefix, limit)`: distinct values, e.g. for enums or status columns |

**Relational: writes**

| Tool | Level | Backed by |
| --- | --- | --- |
| `execute` | Write (`Data` or `DataAndSchema`) | one Write or DDL statement in its own transaction, confirmation per §6.3, returns affected rows |

**Key-value** (Redis family, etcd)

| Tool | Level | Backed by |
| --- | --- | --- |
| `kv_keyspaces` | Schema | `keyspaces()` with key counts |
| `kv_scan` | Data | `scan(keyspace, pattern, cursor, count)` with type and TTL |
| `kv_read` | Data | `read(keyspace, key, limit)` |
| `kv_command` | Data / Write | `command(keyspace, args)`: an allowlist of read commands (`GET`, `HGETALL`, `TYPE`, `TTL`, `INFO`, `XRANGE`…) at Data level; anything else needs `agentWrites`, and `FLUSHALL`, `FLUSHDB`, `CONFIG`, `SHUTDOWN`, `DEBUG`, `SCRIPT`, `EVAL` are always refused |
| `kv_write` | Write | `edit` / `delete` / `expire` / `rename` as typed operations |

**Document** (MongoDB, FerretDB)

| Tool | Level | Backed by |
| --- | --- | --- |
| `doc_databases`, `doc_collections`, `doc_indexes` | Schema | `databases()`, `collections()`, `indexes()` |
| `doc_infer_schema` | Schema | field paths and types inferred from a 100-document sample; returns paths, never values |
| `doc_find` | Data | `find(filter, sort, skip, limit, projection)` with JSON filter |
| `doc_count` | Data | `count(filter)` |
| `doc_write` | Write | `insert` / `update` / `replace` / `delete` as typed operations |

**Data files** appear as relational connections (they're SQLite working copies) with writes always disabled.

**Library**

| Tool | Level | Backed by |
| --- | --- | --- |
| `list_saved_queries` / `get_saved_query` | Schema | `QueryLibrary.list` / `read` |
| `save_query` | Schema | `QueryLibrary.save` into an `mcp/` folder by default; never overwrites without `overwrite: true` |
| `query_history` | Schema | `QueryHistory.recent(connectionId, limit)` |

### 9.2 Resources

| URI | Content |
| --- | --- |
| `tablegrip://connections` | same as `list_connections` |
| `tablegrip://connections/{id}/schema` | `SchemaDigest` text for the default namespace |
| `tablegrip://connections/{id}/tables/{namespace}/{table}` | DDL and structure |
| `tablegrip://queries/{path}` | a saved query's SQL |

Resource templates are listed so clients that browse resources can attach a schema to the conversation without a
tool call. Resources follow the same `AgentPolicy`.

### 9.3 Prompts

| Prompt | Arguments | What it sets up |
| --- | --- | --- |
| `explore-database` | connection | schema overview, then a short tour of the main tables and how they relate |
| `write-query` | connection, request | read the relevant tables first, write one statement, run it with `query`, explain the result |
| `optimize-query` | connection, sql | `explain_query`, then suggest indexes or rewrites as SQL for the user to review, without running DDL |
| `review-migration` | connection, sql | classify, show danger, describe impact on the affected tables, never execute |

## 10. Rollout

| Phase | Content | Effort | Done when |
| --- | --- | --- | --- |
| **P0 Spike** | SDK license + dependency review; `tablegrip mcp` over stdio from the packaged app on macOS, Windows, Linux; keychain without prompt; no Dock icon | 2–3 days | A packaged build answers `initialize` and `tools/list` from Claude Desktop on all three OSes |
| **P1 Core groundwork** | `ConnectionOpener` (app switched to it), `AgentPolicy`, `StatementGuard`, `HistoryEntry.source`, new settings and `agentWrites` fields | 3–4 days | Pure unit tests cover every row of §6.1 and every guard rule; app behaviour unchanged |
| **P2 Read-only relational server** | `:mcp` module, connection + schema + data tools, resources, audit log, limits, cancellation | 1–1.5 weeks | Read flows work end to end against SQLite in tests and PostgreSQL/MySQL with `-Pit.*` |
| **P3 Other paradigms** | Key-value, document, data-file tools; prompts | 4–5 days | Redis and MongoDB tools tested against containers; data files read-only |
| **P4 Writes** | `execute`, `kv_write`, `doc_write`, elicitation confirmations, Production rules | 3–4 days | Writes refused by default, allowed only per §6.1, confirmed per §6.3 |
| **P5 App UI** | Settings › MCP, client config writers, Options select, History badge, translations | 4–5 days | One click configures each listed client; `I18nTest` green |
| **P6 Docs and release** | `docs/mcp.md`, README, guide, development guide, CHANGELOG, CI smoke test | 2–3 days | Docs reviewed; release workflow smoke-tests `mcp` on each packaged OS |

P1–P2 can ship on their own as a beta ("read-only MCP server"), which is already the most requested use: asking an
agent about a schema or for a query that runs against real data.

## 11. Testing

| Level | What |
| --- | --- |
| Unit (`:core`) | `AgentPolicy` table-driven over AiAccess × readOnly × agentWrites × environment × flags; `StatementGuard` with smuggling (`SELECT 1; DROP …`), CTE writes, `EXPLAIN ANALYZE`, comments and string literals hiding semicolons, per-dialect syntax |
| Protocol (`:mcp`) | In-process client over piped streams: `initialize`, `tools/list` per access level, each tool's happy path and denial on SQLite, truncation flags, secrets absent from every output (assert the test password string never appears), cancellation stops a `SELECT` on a slow query |
| Integration | Existing `-Pit.postgres`, `-Pit.mysql`, … properties: read-only transaction actually blocks `SELECT nextval()` / a writing function; Redis and MongoDB via the existing engine tests' containers |
| Manual | [MCP Inspector](https://github.com/modelcontextprotocol/inspector) against `scripts/tablegrip mcp`; Claude Desktop, Claude Code, Cursor with the configs the app writes |
| CI | `./gradlew check` runs the unit and protocol tests; the release workflow starts each packaged app with `mcp`, sends `initialize`, expects a valid response within 10 s |

## 12. Documentation

### 12.1 New user page: `docs/mcp.md`

1. **What it is**: one paragraph and the diagram from §1.
2. **Turn it on**: Settings › MCP › switch, then *Connect an app*.
3. **Set up by hand**, per client, with copy-ready config:

   ```json
   {
     "mcpServers": {
       "tablegrip": {
         "command": "/Applications/TableGrip.app/Contents/MacOS/TableGrip",
         "args": ["mcp"]
       }
     }
   }
   ```

   ```bash
   claude mcp add --scope user tablegrip -- tablegrip mcp
   ```

   plus the Windows and Linux paths from §7, and Cursor / VS Code file locations.
4. **What agents can see and do**: the §6.1 table in user terms, how to allow sample data, how to allow writes, why
   Production asks.
5. **Tools reference**: every tool from §9.1 with its arguments, one example call and its access level.
6. **Resources and prompts**.
7. **Flags** (§5.3) with examples, e.g. a read-only server limited to two connections for a shared project.
8. **Activity and history**: where calls are logged.
9. **Troubleshooting**: "no connections listed" (switch off / AiAccess Off), "driver not installed", keychain prompt on
   dev builds, Windows path with spaces, client shows no tools (stdout pollution → check `mcp.log.jsonl` and stderr).
10. **Security notes**: prompt injection through data, recommend a read-only database user for production.

### 12.2 Changes to existing docs

**README.md**

- Header links row: add `[MCP server](docs/mcp.md)` between *User guide* and *Build from source*.
- Highlights: add a bullet after *Safe by default*:

  > - **Your connections, in any AI app.** A built-in MCP server lets Claude, Cursor and VS Code read schemas and run
  >   queries through the connections you already saved, with the same read-only, environment and data-sharing rules.
  >   [Set it up →](docs/mcp.md)

- Under *Safe by default*, mention that MCP access is off until enabled.

**docs/guide.md**: a short `## MCP server` section after *Safety* linking to `mcp.md`, and a line in *Safety*
pointing out that MCP follows *Data shared with agents*.

**docs/development.md**: `mcp` row in *Project layout*; a *Running the MCP server* section (`scripts/tablegrip mcp`,
`--home /tmp/dg`, MCP Inspector); note that nothing in `:core` may write to stdout.

**CHANGELOG.md**: entry under the release that ships P2.

## 13. Open questions

1. Should *Schema only* also allow `query` when the statement is an aggregate (`COUNT`, `SUM` …)? Proposed: **no**;
   aggregates still leak data, and one clear rule beats a clever one.
2. Should the in-app CLI agents (`CliAgentAdapter`) get this server automatically, so the chat panel can run queries
   itself? Natural follow-up after P4; needs its own UX for confirmations inside the chat.
3. Streamable HTTP for remote clients: wait for user demand, and design auth first.

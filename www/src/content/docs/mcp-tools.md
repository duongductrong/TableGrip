---
title: MCP tools
nav: MCP tools & options
description: Every tool, resource and prompt TableGrip's MCP server offers, and the options tablegrip mcp takes.
---

What [the MCP server](/docs/mcp-server) offers AI apps. You rarely call these yourself: the app picks the tool, and
this page is for knowing what it can do.

Every tool that works on a connection takes `connection`, the connection's id or name as `list_connections` returns
it. The **Needs** column says what the connection must allow:

| Needs | Connection options |
| --- | --- |
| **Schema** | **AI agents** isn't **Off** |
| **Data** | **AI agents** is **Schema and sample data** |
| **Writes** | **Data** access, and **Agent writes** isn't **None** (**Data and schema** for DDL) |

## Connections

| Tool | Needs | What it does |
| --- | --- | --- |
| `list_connections` | — | The shared connections, with type, environment, `dataAccess` and `writes`. Apps start here. |
| `connection_info` | Schema | Connects and describes the server: version, and whether it has EXPLAIN and transactions. |

## SQL databases

| Tool | Needs | What it does |
| --- | --- | --- |
| `list_schemas` | Schema | Schemas (or MySQL databases), and the default one. |
| `list_tables` | Schema | Tables and views of a schema, optionally filtered by `pattern`. |
| `describe_table` | Schema | Columns, primary key, foreign keys both ways, indexes, constraints, triggers and DDL. `table` may be `schema.table`. |
| `schema_overview` | Schema | The whole default schema as compact text, `focus` tables first. |
| `explain_query` | Schema | The plan for one read statement, without running it. |
| `query` | Data | Runs one read statement; returns `columns`, `rows`, `rowCount` and `truncated`. |
| `sample_rows` | Data | A table's rows without SQL: `columns`, `where`, `order_by` (`"created_at desc"`), `limit`, `offset`. |
| `count_rows` | Data | Counts rows, optionally with `where`. |
| `column_values` | Data | Distinct values of a column, optionally starting with `prefix`. |
| `execute` | Writes | Runs one `INSERT`, `UPDATE`, `DELETE` or `MERGE` (or DDL, when allowed) and commits it. Returns `affectedRows`. |

A call and its result:

```json
{ "name": "query", "arguments": { "connection": "shop", "sql": "SELECT tier, count(*) FROM customers GROUP BY tier" } }
```

```json
{
  "columns": [{ "name": "tier", "type": "TEXT" }, { "name": "count(*)", "type": "INTEGER" }],
  "rows": [["basic", "20"], ["gold", "10"]],
  "rowCount": 2,
  "truncated": false,
  "elapsedMs": 3
}
```

Values come back as text, exactly as the database returns them, so big numbers and decimals keep their precision. When
`truncated` is `true`, a `note` says why: too many rows, or too many characters.

## Key-value stores

For Redis, Valkey, KeyDB, Dragonfly and etcd.

| Tool | Needs | What it does |
| --- | --- | --- |
| `kv_keyspaces` | Schema | Keyspaces (Redis's numbered databases) with key counts. |
| `kv_scan` | Data | Keys matching a pattern such as `user:*`, with type and TTL, a page at a time (`cursor`). |
| `kv_read` | Data | One key's value: string, hash, list, set, sorted set or stream. |
| `kv_command` | Data or Writes | One command as a list of arguments, e.g. `["HGETALL", "user:1"]`. Read commands need data access, others need writes. |
| `kv_write` | Writes | `set`, `hset`, `hdel`, `rpush`, `lrem`, `sadd`, `srem`, `zadd`, `expire`, `persist`, `rename` or `delete` one key. |

`FLUSHALL`, `FLUSHDB`, `CONFIG`, `EVAL`, `SCRIPT`, `SHUTDOWN`, `CLIENT`, `SELECT` and other commands that wipe,
reconfigure or stop the server are always refused.

## Document stores

For MongoDB and FerretDB. `database` defaults to the connection's own database when it has one.

| Tool | Needs | What it does |
| --- | --- | --- |
| `doc_databases` | Schema | Databases. |
| `doc_collections` | Schema | Collections and views of a database, with document counts. |
| `doc_indexes` | Schema | A collection's indexes. |
| `doc_infer_schema` | Schema | Field paths and their types, from a sample of documents. Names and types only, no values. |
| `doc_find` | Data | Documents matching a `filter`, with `sort`, `projection`, `limit` and `skip` (JSON). |
| `doc_count` | Data | Counts documents matching a filter. |
| `doc_aggregate` | Data | Runs an aggregation `pipeline`. `$out` and `$merge` are refused. |
| `doc_write` | Writes | `insert`, `replace`, `update` (`set` and `unset` top-level fields) or `delete` documents by `_id`. |

## Data files

CSV, TSV, Excel and JSON files work like SQL databases (each file or sheet is a table) and are always read-only to AI
apps. See [Data files](/docs/data-files).

## Saved queries and history

| Tool | Needs | What it does |
| --- | --- | --- |
| `list_saved_queries` | Schema | Your [saved queries](/docs/saved-queries), optionally only those of one connection. |
| `get_saved_query` | Schema | A saved query's SQL, by the `path` the list gives. |
| `save_query` | Schema | Saves SQL to your saved queries, in an `mcp` folder by default. Doesn't run it, and won't overwrite without `overwrite: true`. |
| `query_history` | Schema | Recent statements, by you and by AI apps, with errors. |

Both only include connections the app can see.

## Resources

Apps that browse MCP resources can attach these to a conversation without calling a tool:

| URI | Content |
| --- | --- |
| `tablegrip://connections` | The shared connections, as JSON |
| `tablegrip://connections/{id}/schema` | A SQL connection's default schema: tables and columns |
| `tablegrip://connections/{id}/tables/{schema}/{table}` | One table's columns, keys, indexes and DDL |
| `tablegrip://queries/{path}` | A saved query's SQL |

## Prompts

Some apps show these as slash commands:

| Prompt | Arguments | What it does |
| --- | --- | --- |
| `explore-database` | connection | A tour: the main tables, how they relate, and example questions with SQL. |
| `write-query` | connection, request | Reads the tables it needs, writes and runs one query, and answers from the result. |
| `optimize-query` | connection, sql | Explains the plan and suggests rewrites or indexes, without running them. |
| `review-migration` | connection, sql | Reviews a migration for data loss, locking and rollback, without running it. |

## Command-line options

```text
tablegrip mcp [options]

  --connections <a,b>      Only these connections, by id or name
  --read-only              Refuse every write, whatever the connections allow
  --max-rows <n>           Fewer rows per result than Settings → MCP allows
  --timeout <seconds>      A shorter statement timeout than Settings → MCP allows
  --allow-driver-download  Download a missing driver (checked like the app does) instead of failing
  --home <dir>             Use the TableGrip profile in <dir> instead of ~/.tablegrip
  -h, --help               Show this help
```

Options only narrow what Settings allow, never widen it. For example, an app with read-only access to two connections:

```bash
claude mcp add --scope user tablegrip-reports -- tablegrip mcp --read-only --connections "shop-dev,analytics"
```

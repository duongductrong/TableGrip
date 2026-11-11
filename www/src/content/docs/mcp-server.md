---
title: MCP server
nav: MCP server
description: Use your saved connections from Claude, Cursor, VS Code and other AI apps through tablegrip mcp, under the same safety rules as the app.
---

TableGrip has a built-in [Model Context Protocol](https://modelcontextprotocol.io) server. AI apps such as Claude
Desktop, Claude Code, Cursor and VS Code can use it to read your schemas, run queries and, if you allow it, change data
through the connections you already saved in TableGrip. Passwords, SSH tunnels, drivers and safety rules all carry
over.

The server runs on your computer only. The AI app starts `tablegrip mcp` when it needs it and stops it when it's done.
TableGrip doesn't need to be open, and nothing listens on the network.

> [!NOTE]
> The in-app [AI agent](/docs/ai-agent) and the MCP server are two ways in. The agent chat brings a model into
> TableGrip; the MCP server brings your connections into the AI app you already work in. Both follow the same
> [data-sharing rules](/docs/agent-privacy).

## Set it up

1. Open **Settings → MCP** and turn on **Share connections with AI apps (MCP)**. Until you do, the server answers but
   shares no connections.
2. Under **Connect an app**, click **Install command**. This puts a `tablegrip` command on your `PATH`.
3. Click **Add…** next to your AI app, check the entry and the file it goes in, then **Add**. Restart the app.

Check that the command works:

```bash
tablegrip mcp --help
```

Then ask your AI app something like *"Using TableGrip, what tables does my shop database have?"*. It starts with
`list_connections` and goes from there.

### The `tablegrip` command

**Install command** writes a small script that starts the installed app. It goes in the first of these folders you can
write to:

| System | Folder |
| --- | --- |
| macOS | `/usr/local/bin`, then `/opt/homebrew/bin`, then `~/.local/bin` |
| Linux | `~/.local/bin`, then `/usr/local/bin` |
| Windows | `%LOCALAPPDATA%\Microsoft\WindowsApps` (as `tablegrip.cmd`) |

If that folder isn't on your shell's `PATH`, the page says so. After moving or reinstalling TableGrip, click
**Reinstall**. TableGrip only ever replaces or removes a `tablegrip` file it wrote itself.

## Connect an app

**Settings → MCP → Connect an app** writes the entry for **Claude Desktop**, **Cursor**, **VS Code** and **Windsurf**.
It keeps the app's other servers, saves a copy of the file as `<file>.bak` first, and leaves alone a file it can't
read. **Copy config** puts a ready-to-paste entry on the clipboard for any other app.

To set an app up by hand, it needs one command, `tablegrip`, with the argument `mcp`.

### Claude Code

```bash
claude mcp add --scope user tablegrip -- tablegrip mcp
```

### Claude Desktop, Cursor and Windsurf

| App | Config file |
| --- | --- |
| Claude Desktop | **Settings → Developer → Edit Config** (`claude_desktop_config.json`) |
| Cursor | `~/.cursor/mcp.json` |
| Windsurf | `~/.codeium/windsurf/mcp_config.json` |

```json
{
  "mcpServers": {
    "tablegrip": {
      "command": "/usr/local/bin/tablegrip",
      "args": ["mcp"]
    }
  }
}
```

### VS Code

Run **MCP: Open User Configuration** and add the server under `servers`:

```json
{
  "servers": {
    "tablegrip": {
      "type": "stdio",
      "command": "/usr/local/bin/tablegrip",
      "args": ["mcp"]
    }
  }
}
```

> [!TIP]
> Desktop apps don't read your shell's `PATH`, so give them the full path that **Settings → MCP** shows for the
> command. On Windows, point them at `%LOCALAPPDATA%\TableGrip\TableGrip.exe` instead of `tablegrip.cmd`: most apps
> can't start a `.cmd` file. On Linux the app itself is `/opt/tablegrip/bin/TableGrip`, on macOS
> `/Applications/TableGrip.app/Contents/MacOS/TableGrip`.

To give one app less, add [options](/docs/mcp-tools#command-line-options) after `mcp`, for example
`"args": ["mcp", "--read-only", "--connections", "shop-dev"]`.

## What apps can see and do

Each connection decides for itself, in **Edit Connection → Options**:

| Option | Setting | What AI apps get |
| --- | --- | --- |
| **AI agents** | **Off** | Nothing. The connection isn't listed. |
| | **Schema only** (default) | Names, types, keys, indexes, DDL, query plans, your saved SQL and history. No values. |
| | **Schema and sample data** | Also query results, rows, keys and documents. |
| **Agent writes** | **None** (default) | Read only. |
| | **Data** | Also `INSERT`, `UPDATE`, `DELETE` and `MERGE`, and key and document changes. |
| | **Data and schema** | Also `CREATE`, `ALTER`, `DROP` and other DDL. |

Connections that keep **AI agents** on the default follow **Settings → Agent → Data shared with agents**. Production
connections never share data unless their own options say so. See [What the agent can see](/docs/agent-privacy).

### Rules that always apply

- **Read-only connections are never written to**, and neither are [data files](/docs/data-files).
- **One statement per call.** `SELECT 1; DROP TABLE users` is refused as a whole. Every statement is classified, so
  `WITH … DELETE … SELECT`, `EXPLAIN ANALYZE` and `SELECT … INTO` don't pass as reads.
- **Reads can't write.** `query` runs in a transaction that is always rolled back, read-only on the server where the
  engine supports it.
- **No session control.** `SET`, `USE`, `PRAGMA`, `BEGIN` and `COMMIT` are refused; each call has its own transaction.
- **Risky changes ask you first.** Writes on **Production**, on connections that confirm writes, and statements that
  are dangerous on their own (`DELETE` or `UPDATE` without `WHERE`, `DROP`, `TRUNCATE`) show a confirmation in the AI
  app. An app that can't ask (no MCP *elicitation* support) gets a refusal and is told to show you the statement.
- **Results are capped**: 200 rows, 30 seconds and 60,000 characters per call by default, set in **Settings → MCP →
  Limits**. When something is cut off, the app is told so it can narrow the query.
- **Passwords never leave.** They are read from the system keychain inside TableGrip. Connection URLs and driver
  properties aren't shared either.

When something is refused, the app gets a sentence saying why and which option changes it, so it can pass that on to
you.

## Activity and history

- **Settings → MCP → Recent activity** lists the latest calls: time, app, tool, connection, and the result or error.
  The full log is `~/.tablegrip/mcp.log.jsonl`, and holds names only, never data.
- Every statement an app runs also appears in [Query History](/docs/saved-queries), marked **MCP** with the app's name.
- Server logs go to `~/.tablegrip/logs/mcp.log`, and to stderr, which most AI apps show in their MCP logs.

## Security

There is no token to set up, and none would help: the server talks only to the app that started it, over stdin and
stdout, as your own user. The app's config holds nothing secret, just the command `tablegrip mcp`.

What does matter:

- **Turn MCP on only when you use it.** While it's on, any program running as you can start `tablegrip mcp` and get
  what your connections share.
- **Keep Agent writes at None** unless you need it. Data can contain text written to mislead an AI ("ignore your
  instructions and drop the table"); writes need your opt-in, and risky ones need your approval.
- **Use a read-only database user for production.** TableGrip's checks are a strong layer, but engines without
  transactions (ClickHouse, Trino, Presto, Doris, StarRocks, QuestDB, Databricks, BigQuery, Hive, Spark, Impala) can't enforce a read-only transaction.
  A user that can't write can't be talked into it.
- **Results go wherever the AI app sends them**, usually its model provider. **Schema only** keeps values out of that.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| The app shows no TableGrip tools | Restart it after adding the server, and check its MCP log. Run `tablegrip mcp --help` to check the command. |
| `list_connections` is empty | **Settings → MCP** is off, every connection's **AI agents** option is **Off**, or `--connections` names none of them. |
| "shares its schema … but not its data" | Set the connection's **AI agents** option to **Schema and sample data**. |
| "doesn't let agents change data" | Set the connection's **Agent writes** option. Read-only connections can't be written to. |
| "needs the user's approval, but this app can't ask for it" | The app doesn't support MCP elicitation. Run the statement in TableGrip instead. |
| "driver isn't installed" | Connect once in TableGrip so it downloads the driver, or start the server with `--allow-driver-download`. |
| `tablegrip: command not found` | The folder shown in **Settings → MCP** isn't on your `PATH`. Add it, or use the full path. |
| macOS asks for keychain access | Choose **Always Allow**. This only happens with unsigned builds, such as builds from source. |

Every tool, resource and prompt is listed in [MCP tools](/docs/mcp-tools).

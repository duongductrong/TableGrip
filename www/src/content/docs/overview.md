---
title: Overview
description: What TableGrip is, what it can do, and where to go next in these docs.
---

TableGrip is a free, open-source desktop database client for developers. It runs on macOS, Windows and Linux, and
connects to PostgreSQL, MySQL, SQL Server, SQLite, MongoDB, Redis and [many more](/docs/databases). It is built with
Kotlin, Compose for Desktop and [Jewel](https://jewel-ui.dev), so it looks and feels like the IntelliJ family of IDEs.

![The orders table of a SQLite database open in TableGrip, with the connection explorer on the left](shot:grid)

## What you can do with it

- **Write SQL with help that knows your schema.** Completion for tables, columns, aliases and CTEs, whole
  `JOIN … ON` clauses proposed from foreign keys, real values sampled from a column, a formatter, `EXPLAIN`, and quick
  fixes for misspelt names. → [SQL editor](/docs/sql-editor)
- **Edit data safely.** Changes in the grid stay pending until you submit them, then run in one transaction. Foreign
  keys are clickable, and a value panel shows JSON as a tree, binary as hex and images as pictures.
  → [Data grid](/docs/data-grid)
- **Filter without writing SQL.** A typed condition builder that understands dates like `today-7d` or
  `3 months ago`, or a raw `WHERE` clause with completion. → [Filtering data](/docs/filtering)
- **Work on the schema.** Table designer, schema compare with generated migrations, ER diagrams, data import and a
  test data generator. → [Schema tools](/docs/schema-tools)
- **Look after servers.** Live server activity with lock trees, backup and restore through `pg_dump` and
  `mysqldump`, and scheduled backups. → [Server activity](/docs/activity), [Backup & restore](/docs/backup-and-restore)
- **Ask an AI agent.** Use Claude Code, Codex, Gemini CLI or any compatible API. By default the agent sees your schema,
  never your data. → [AI agent](/docs/ai-agent)
- **Move in quickly.** Import connections from DBeaver, IDE data sources (`.idea/dataSources.xml`), `Connections.plist`, `~/.pgpass` and `.env` files, or
  paste any connection URL. → [Move from another tool](/docs/moving-in)

## Principles

TableGrip is built around a few promises that show up everywhere in the app:

| Promise | What it means in practice |
| --- | --- |
| **Nothing changes until you say so** | Grid edits are pending until you submit. Schema changes show their SQL first. Restores go into a new database by default. |
| **Safe by default** | Production connections ask before writes, read-only connections refuse them, and `DELETE` without `WHERE` always asks. See [Safety & environments](/docs/safety). |
| **Your secrets stay in the OS** | Passwords live in the macOS Keychain, Windows Credential Manager or the Linux Secret Service, never in TableGrip's files. |
| **No surprise downloads** | No database driver ships with the app. The one you need is downloaded on first connect and checked against a pinned SHA-256. |
| **Plain files you own** | Settings, saved queries and history are readable JSON and `.sql` files in `~/.tablegrip/`. |

## Find your way around these docs

| If you want to… | Start here |
| --- | --- |
| Install TableGrip and connect for the first time | [Installation](/docs/installation), then [Quick start](/docs/quick-start) |
| Bring your connections from another app | [Move from another tool](/docs/moving-in) |
| Learn the editor, the grid and filters | [Query & edit](/docs/sql-editor) |
| Use the AI agent and understand what it sees | [AI agent](/docs/ai-agent), [What the agent can see](/docs/agent-privacy) |
| Work faster with the keyboard | [Keyboard shortcuts](/docs/keyboard-shortcuts) |
| Fix a problem | [Troubleshooting & FAQ](/docs/troubleshooting) |
| Build the app or contribute | [Build from source](/docs/build-from-source), [Contributing](/docs/contributing) |

> [!NOTE]
> Shortcuts in these docs are written for macOS. On Windows and Linux, use <kbd>Ctrl</kbd> where you see <kbd>⌘</kbd>.

## Free and open source

TableGrip is released under the [Apache License 2.0](https://github.com/duongductrong/TableGrip/blob/master/LICENSE).
The source code, issue tracker and releases are on
[GitHub](https://github.com/duongductrong/TableGrip). If it saves you time, you can support its development through
[GitHub Sponsors](https://github.com/sponsors/duongductrong), [Ko-fi](https://ko-fi.com/duongductrong) or
[PayPal](https://www.paypal.me/duongductrong).

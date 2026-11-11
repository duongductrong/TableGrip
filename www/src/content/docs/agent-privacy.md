---
title: What the agent can see
nav: Privacy & data sharing
description: Exactly what TableGrip sends to an AI agent, how to change it per connection, and why production is protected.
---

An AI agent only knows what TableGrip sends it. You decide how much that is, for the whole app and for each connection.

## The three levels

Set the default in **Settings → Agent → Data shared with agents → Connections share**:

| Level | The agent receives | The agent never receives |
| --- | --- | --- |
| **Off** | Nothing. AI actions are hidden for these connections. | Anything |
| **Schema only** (default) | Table and column names, types, DDL, your SQL and error messages | Any value stored in your tables |
| **Schema and sample data** | All of the above, plus sample values and the results of queries you let it run | |

With **Schema only**, the agent can still write and fix queries, because it knows your schema. It just can't see the
answers. When it asks to run a query, the result stays with you.

## Per-connection overrides

Each connection can override the default in its **Options → AI agents**: choose **Use default (Settings › Agent)** or
a specific level. For example, share sample data from a local development database while keeping everything else at
schema only.

## Production is protected

A connection tagged **Production** never shares data, whatever the app default is, **unless its own options say so**.
Raising the default to "Schema and sample data" doesn't silently include production.

## What else is limited

- **Mentions** of grid cells send the values only when the connection shares data; otherwise only the shape of the
  selection (which columns, how many rows) is sent.
- The `@` menu only offers connections that are shared with agents.
- The schema summary sent with a question is capped at 40,000 characters, and the console text at 4,000.
- Query results sent back to the agent are capped at 50 rows.

## Where your data goes

- **Command-line agents** (Claude Code, Codex…) send what TableGrip gives them to their own provider, under the terms
  of your account with that provider.
- **API providers** receive requests directly from TableGrip at the base URL you set. Point it at a local server
  (Ollama, LM Studio, vLLM) to keep everything on your machine.
- **Changing settings sends nothing.** Requests only go out when you ask the agent something.

## Agents can't act on their own

CLI agents are started in their read-only or ask modes, with no tools to edit files or run commands. Every query is run
by TableGrip, after you click **Run**, and anything that would change data is only opened for you to review. See
[AI agent → When the agent wants to run a query](/docs/ai-agent#when-the-agent-wants-to-run-a-query).

## AI apps over MCP

The same levels decide what AI apps such as Claude Desktop, Cursor and VS Code get through the
[MCP server](/docs/mcp-server): **Off** hides a connection from them, **Schema only** gives them its schema, and
**Schema and sample data** lets them run read-only queries. Changing data takes one more option,
**Options → Agent writes**, which is **None** by default.

---
title: Move from another tool
description: Bring your existing connections from TablePlus, DBeaver, DataGrip, ~/.pgpass and .env files in a few clicks.
---

You don't have to re-enter your connections. TableGrip finds the ones other tools already have on this computer and
lets you tick the ones to bring over. Nothing is changed in the other tools.

## Open Import Connections

Any of these opens the same dialog:

- The explorer's **+ ▾** menu → **Import from Other Tools…**. The menu already says how many connections other tools have.
- The connection menu in the title bar → **Import Connections…**.
- [Go to anything](/docs/navigation#go-to-anything) (<kbd>⌘</kbd> <kbd>P</kbd>), then type `import`.
- With no connections yet, the empty explorer says how many it found and offers **Import Them…**.

Tick the connections you want and confirm. Names, folders (as [groups](/docs/organising-connections#groups)),
environments, read-only flags and SSH tunnels carry over.

## Where connections are found

| Source | Read from | Passwords |
| --- | --- | --- |
| **TablePlus** | `~/Library/Application Support/com.tinyapp.TablePlus/Data/Connections.plist` (also the Setapp edition); folders from `ConnectionGroups.plist` | Stay in TablePlus' Keychain items |
| **DBeaver** | Every project's `.dbeaver/data-sources*.json` in `workspace6` (macOS, Linux, Windows) | Read from `credentials-config.json`, unless protected by a master password |
| **DataGrip** and other JetBrains IDEs | `.idea/dataSources.xml` + `dataSources.local.xml` of each IDE's recent projects, global data sources, SSH configs | Stay in the IDE's credential store |
| **libpq** | `~/.pgpass` (`$PGPASSFILE`), `~/.pg_service.conf` (`$PGSERVICEFILE`, `$PGSYSCONFDIR`) | Yes |
| **`.env` files** | **Scan Project…**: any `*_URL` holding a connection URL, Laravel's `DB_*`, Docker's `POSTGRES_*` / `MYSQL_*`, libpq's `PG*` | Yes |

TablePlus tags and DBeaver connection types become [environments](/docs/safety#environments).

### Good to know

- Connections that are already in TableGrip, or that were found in two tools, start **unticked**, so you don't get
  duplicates.
- PostgreSQL connections imported without a password get one from `~/.pgpass` when it has a matching entry.
- **Add File…** takes any of the files above (or an exported copy from another machine) and works out which kind it is.
- **Scan Project…** points at a project folder and reads its `.env` files, which is handy for app databases you run
  locally.

## Paste a connection URL

Have a URL from a dashboard or a colleague? Choose **New Connection → Paste**, paste it, and press **Parse**. TableGrip
understands libpq, JDBC, MySQL, MongoDB, Redis and SQLite forms. See [Connecting](/docs/connecting#paste-a-connection-url)
for every supported form.

## SSH tunnels from your SSH config

In the connection dialog's **SSH** tab, **From config** fills the tunnel from a `Host` entry in `~/.ssh/config`,
following `Include` and `Host *` defaults, so jump hosts you already use from the terminal work right away.

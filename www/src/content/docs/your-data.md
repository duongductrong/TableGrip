---
title: Where your data lives
description: Every file TableGrip writes, where passwords go, and what (little) leaves your machine.
---

TableGrip keeps everything it writes in **one folder in your home directory**, as plain files you can read, back up
or sync. Secrets never go in that folder.

## The ~/.tablegrip folder

| Path | Contents |
| --- | --- |
| `connections.json` | Your connection profiles: hosts, ports, users, options. **No passwords.** |
| `settings.json` | Your [settings](/docs/settings#settingsjson), only the ones you changed |
| `session.json` | Open tabs and the window layout, to [reopen next time](/docs/navigation#sessions) |
| `history.jsonl` | [Query history](/docs/saved-queries#query-history), one JSON line per statement |
| `queries/` | [Saved queries](/docs/saved-queries) as `.sql` files, in folders |
| `chats/` | [Agent](/docs/ai-agent) conversations, one JSON file each |
| `agent/` | The working folder command-line agents run in |
| `themes/` | Your own [themes](/docs/themes#write-your-own-theme) |
| `drivers/` | Downloaded [drivers](/docs/drivers), one folder per driver and version |
| `tools/` | Downloaded PostgreSQL client tools, if you allowed it |
| `cache/datafiles/` | Working copies of open [data files](/docs/data-files), removed when their connection closes |
| `known_hosts` | SSH host keys you accepted for tunnels |
| `logs/` | The app log, `tablegrip.log`, and backup logs in `logs/backup/` |

On Windows the folder is `%USERPROFILE%\.tablegrip`.

> [!TIP]
> Keep `queries/` in git, or sync it between machines: it's just `.sql` files. Copying `connections.json` to another
> computer moves your connections too; you'll be asked for the passwords there.

### Use a different folder

Start TableGrip with the Java system property `-Dtablegrip.home=/path/to/folder` to keep everything somewhere else,
for example a throwaway profile for testing.

## Passwords and keys

Passwords, SSH passphrases and AI API keys go to the operating system's credential store, under the service name
**`TableGrip`**:

| System | Store |
| --- | --- |
| macOS | Keychain |
| Windows | Credential Manager |
| Linux | Secret Service (GNOME Keyring or KWallet) |

Passwords found in a pasted connection URL go there too, never into `connections.json`. Deleting a connection removes
its entries.

## What leaves your machine

TableGrip has **no telemetry, no analytics and no account**. Network requests happen only when:

| When | Goes to | Sends |
| --- | --- | --- |
| You connect to a database | That database, possibly through your SSH tunnel | Your queries |
| A driver or tool is downloaded | Maven Central (or your mirror), EDB for PostgreSQL tools | A normal file download |
| Checking for updates (macOS, Windows) | GitHub | Only a request for the list of releases; nothing about you or your databases |
| You ask the AI agent something | Your agent's provider | What the [data sharing level](/docs/agent-privacy) allows |

## Logs

The app log is `~/.tablegrip/logs/tablegrip.log`. It rolls over daily and at 10 MB, keeps 7 days, and never grows past
100 MB in total. Each backup or restore run also writes its own log to `~/.tablegrip/logs/backup/`.

Logs don't contain passwords, but they can contain host names and SQL. Check them before attaching them to a public
issue.

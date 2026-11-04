---
title: Troubleshooting & FAQ
description: Answers to common questions, fixes for connection and driver problems, and how to report a bug.
---

## Connecting

### "Connection refused" or a timeout

- Check the host and port, and that the server accepts connections from your machine (firewall, security group,
  `listen_addresses` on PostgreSQL, `bind-address` on MySQL).
- If the database is in a private network, use an [SSH tunnel](/docs/connecting#ssh).
- For a database on the SSH host itself, use `localhost` as the host on the **General** tab.

### "SSL required" or certificate errors

Hosted databases usually require TLS. Add the driver's TLS property under **Options → Driver properties**, such as
`sslmode=require` for PostgreSQL. See [Connecting → TLS / SSL](/docs/connecting#tls-ssl). The Neon, Supabase,
PlanetScale and Azure SQL presets set this for you.

### The password isn't remembered

Passwords are stored in your system keychain. On Linux, this needs a running Secret Service (GNOME Keyring or
KWallet); install `gnome-keyring` if your desktop doesn't provide one. On macOS, if you clicked **Deny** when Keychain
asked, open **Keychain Access**, find the `TableGrip` item, and allow TableGrip in its **Access Control** tab.

### MySQL connects, then fails on authentication

TableGrip uses MariaDB Connector/J for MySQL by default. A few MySQL 8 setups (for example `caching_sha2_password`
without TLS) work better with Oracle's MySQL Connector/J. Download it and choose its jar. See
[Drivers → MySQL and MariaDB](/docs/drivers#mysql-and-mariadb).

## Drivers

### The driver download fails

- Behind a proxy or without access to Maven Central? Set a mirror in **Settings → Drivers → Maven repository**.
- Fully offline? Copy the jars from another machine and use **Import from folder…**. See
  [Drivers → Working offline](/docs/drivers#working-offline).

### A downloaded driver is rejected

The downloaded file doesn't match the hash pinned in TableGrip, so it was rejected. This usually means a proxy or
mirror altered the file. Try again, or download from Maven Central directly.

## Queries and data

### My query only returns 1,000 rows

That's the **Max result rows** limit in [Settings → Query Execution](/docs/settings#query-execution). Raise it, or page
through a table in the table viewer instead. Exports of a table stream **all rows**, regardless of the limit.

### I can't edit rows in this table

- Rows of a table **without a primary key** can only be inserted, not updated or deleted from the grid.
- **Read-only** connections refuse all writes.
- **ClickHouse** is query-only in TableGrip.

### My grid changes were refused because a row changed

Someone (or something) changed the row after you loaded it. TableGrip refuses to overwrite it. Reload the table
(<kbd>⌘</kbd> <kbd>R</kbd>), then make your change again.

### A statement asks for confirmation every time

That's on purpose for `DELETE` / `UPDATE` without `WHERE`, `DROP` and `TRUNCATE`, and for every write on Production or
**Confirm writes** connections. See [Safety & environments](/docs/safety).

## Backups

### No suitable pg_dump is found

`pg_dump` must be at least as new as the server. Install a newer PostgreSQL client, or let TableGrip download the
official tools when it offers. **Settings → Tools** shows every copy found and lets you pick one. See
[Backup & restore → Client tools](/docs/backup-and-restore#client-tools).

### A scheduled backup didn't run

Scheduled backups only run **while TableGrip is open**; a missed one runs soon after the app starts. The schedule in
**Settings → Backups** says what is stopping it: missing tools, no saved password, or a folder it can't write to.

## AI agent

### "No agent found"

TableGrip looks for agent commands such as `claude` or `codex` on your `PATH`. Install one, then use **Rescan installed
agents** in the agent picker, or switch to an [API provider](/docs/ai-agent#api-provider).

On macOS, an app started from the Dock may not see a `PATH` set only in your shell profile. If the agent works in
the terminal but isn't found, make sure it is installed in a standard location such as `/opt/homebrew/bin` or
`/usr/local/bin`.

### The agent can't see my data

That's the default: agents see your **schema only**. Change it in **Settings → Agent** or per connection. See
[What the agent can see](/docs/agent-privacy).

## Settings and appearance

### My settings were reset

If `settings.json` has a syntax error, TableGrip uses the defaults and shows the error at the top of Settings. It won't
overwrite your file; fix the error and the settings come back.

### My custom theme doesn't show up

Theme files that can't be read are listed in **Settings → Appearance** with the reason. Check the JSON, and that
`appearance` is `dark` or `light`. See [Themes](/docs/themes#rules).

## FAQ

### Is TableGrip free?

Yes. It is free and open source under the Apache License 2.0, with no paid tier and no account.

### Does TableGrip collect data about me?

No. There is no telemetry or analytics. See [What leaves your machine](/docs/your-data#what-leaves-your-machine).

### Can I use it with a database that isn't listed?

Yes, if it has a JDBC driver: choose **Other (JDBC driver)** and pick the driver's jar.

### Can I move my setup to a new computer?

Copy `~/.tablegrip/` to the new machine. Passwords stay in the old machine's keychain, so you'll enter them again.

### Where do I report a bug or ask for a feature?

On [GitHub issues](https://github.com/duongductrong/TableGrip/issues). Please include:

- the TableGrip version and your operating system;
- the database and its version;
- the steps that lead to the problem, what you expected, and what happened instead;
- relevant lines from `~/.tablegrip/logs/tablegrip.log`, with passwords, host names and data removed.

Security problems should be reported privately through
[GitHub's security advisories](https://github.com/duongductrong/TableGrip/security/advisories/new), not as a public
issue.

---
title: Backup & restore
description: Back up and restore databases with pg_dump and mysqldump, and schedule backups that run on their own.
---

Right-click a connection → **Back Up…** or **Restore…**. TableGrip drives the database's own tools, so a backup made
here is a normal dump you can restore anywhere.

## What each database uses

| Database | Back up | Restore |
| --- | --- | --- |
| **PostgreSQL** | `pg_dump`: custom, plain SQL or parallel directory format | `pg_restore` (choose which tables and objects to restore from a custom or directory backup) or `psql` |
| **MySQL / MariaDB** | `mysqldump` / `mariadb-dump` with `--single-transaction`, including routines, triggers and events | `mysql` |
| **SQLite** | `VACUUM INTO`, a consistent copy of the file | |
| **DuckDB** | `EXPORT DATABASE` | |

The dialog shows the **equivalent command** so you can copy it and run it yourself.

## Restoring safely

- Restores go into a **new database** by default, so an existing one is never overwritten by accident.
- Read-only connections can't be restored into.
- On Production, you have to type the target database's name again to confirm.
- A cancelled or failed backup deletes its partial file.

## Passwords and tunnels

Passwords are passed through `PGPASSWORD` or an owner-only `--defaults-extra-file` that is deleted afterwards, **never on
the command line**, where other users on the machine could see them. SSH tunnels work just as they do for the
connection itself.

Every run is logged to `~/.tablegrip/logs/backup/`.

## Client tools

TableGrip never bundles `pg_dump` or `mysqldump`. It looks for them on your `PATH` and in the usual places (Homebrew,
Postgres.app, the EDB and MySQL installers, `/usr/lib/postgresql`), and picks the newest `pg_dump` that can read the
server's version.

**Settings → Tools** lists every copy it found and lets you choose one by hand.

### When no suitable pg_dump is installed

TableGrip offers to download the official PostgreSQL client tools (EDB's builds for macOS and Windows, pinned by
SHA-256). It tells you where they come from, how big they are and under which license, and only downloads after you
confirm. Only `pg_dump`, `pg_restore`, `psql` and their libraries (about 28 MB) are kept, in `~/.tablegrip/tools/`, and
you can remove them from the same settings page.

> [!NOTE]
> MySQL's client tools are GPL licensed and are never downloaded for you. Install them yourself, for example with
> `brew install mysql-client` on macOS.

## Scheduled backups

Turn on scheduled backups in **Settings → Backups**. They are off until you do.

- Each schedule backs up **one connection** every **hour, day or week** while TableGrip is open.
- It keeps the **newest N backups**, deleting only its own older ones.
- A backup missed while the app was closed runs soon after it opens.
- The schedule says what would stop it from running: no tools, no saved password, or a folder it can't write to.

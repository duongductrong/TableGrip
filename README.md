<div align="center">

<img src="app/src/icons/TableGrip.png" alt="TableGrip logo" width="128" height="128">

# TableGrip

**A fast, native-feeling desktop database client for developers.**

In the spirit of TablePlus and DataGrip, built with Kotlin, Compose for Desktop and [Jewel](https://jewel-ui.dev),
the IntelliJ look & feel for Compose.

[![Release](https://img.shields.io/github/v/release/duongductrong/TableGrip?include_prereleases&sort=semver)](https://github.com/duongductrong/TableGrip/releases)
[![License](https://img.shields.io/github/license/duongductrong/TableGrip)](LICENSE)
![Platforms](https://img.shields.io/badge/platform-macOS%20%7C%20Windows%20%7C%20Linux-lightgrey)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose%20Desktop-1.12-4285F4?logo=jetpackcompose&logoColor=white)
[![GitHub Sponsors](https://img.shields.io/badge/Sponsor-%E2%9D%A4-ff69b4?style=flat&logo=github)](https://github.com/sponsors/duongductrong)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Donate-FF5E5B?style=flat&logo=ko-fi&logoColor=white)](https://ko-fi.com/duongductrong)

[Download](https://github.com/duongductrong/TableGrip/releases) ·
[User guide](docs/guide.md) ·
[Build from source](#build-from-source) ·
[Contributing](CONTRIBUTING.md)

</div>

---

## Highlights

- **A SQL editor that knows your schema.** Completion for tables, columns, aliases and CTEs, whole `JOIN … ON` clauses
  proposed from foreign keys, real values sampled from the column, a formatter, `EXPLAIN`, and quick fixes for
  misspelt names.
- **A data grid built for editing.** Inline edits stay pending until you submit them in one transaction, foreign keys
  are clickable, and a value panel shows JSON as a tree, binary as hex and images as pictures.
- **Filters without writing SQL.** A typed condition builder with dates like `today-7d` or `3 months ago`, or a raw
  `WHERE` clause with completion.
- **Schema tools.** Table designer, schema compare with generated migrations, ER diagrams (PNG or Mermaid), CSV, Excel
  and JSON import, and a test data generator.
- **Operations.** Live server activity with lock trees, backup and restore through `pg_dump` / `mysqldump`, and
  scheduled backups.
- **Safe by default.** Environment colours, read-only connections, confirmations on Production, warnings before
  `DELETE` without `WHERE`, and AI agents that see your schema only, never your data, unless you allow it.
- **Moving in is easy.** Import connections from TablePlus, DBeaver, DataGrip, `~/.pgpass` and `.env` files, or paste any
  connection URL.
- **Go to anything** (⌘P) for tables, tabs, saved queries and actions, and sessions that come back after a restart.

Every feature is described in the **[user guide](docs/guide.md)**.

## Supported databases

| Family | Databases | Good to know |
| --- | --- | --- |
| **PostgreSQL** | PostgreSQL · CockroachDB · TimescaleDB · Neon · Supabase | Server activity with lock trees, backup and restore with `pg_dump`, all-or-nothing DDL. Hosted presets turn on TLS. |
| **MySQL** | MySQL · MariaDB · TiDB · PlanetScale | Server activity, backup and restore with `mysqldump`. Uses MariaDB Connector/J, or your own MySQL Connector/J. |
| **SQL Server** | SQL Server · Azure SQL | All-or-nothing DDL. Azure SQL comes with encryption on. |
| **Analytics** | ClickHouse · DuckDB | ClickHouse is query-only (no row editing or transactions). DuckDB opens a file or `:memory:`. |
| **Embedded** | SQLite · H2 · HSQLDB · Apache Derby | Open a file, or an in-memory database for H2 and HSQLDB. SQLite backs up with `VACUUM INTO`. |
| **Key-value** | Redis · Valkey · KeyDB · Dragonfly · etcd | Redis standalone, Cluster and Sentinel, TLS. etcd needs no download. |
| **Document** | MongoDB · FerretDB | `mongodb://` and `mongodb+srv://` URLs. |
| **Data files** | CSV / TSV · Excel `.xlsx` · JSON / NDJSON | Opened as tables. Committed changes are written back to the file. |
| **Anything else** | Oracle, Firebird and any other JDBC database | Bring the driver's jar. |

> [!NOTE]
> No driver ships inside the app. When you first connect, TableGrip downloads the one you need from Maven Central and
> checks it against a pinned SHA-256. You can also use your own jars, or install drivers offline from a folder.
> [More on drivers →](docs/guide.md#drivers)

## Install

Download the installer for your platform from the [releases page](https://github.com/duongductrong/TableGrip/releases):

| Platform | Package |
| --- | --- |
| macOS | `.dmg` (signed and notarized) |
| Windows | `.msi` |
| Linux | `.deb` |

On macOS the app checks for updates once a day, downloads them in the background and installs them the next time you
quit; both can be turned off in Settings.

Settings, connections, history and saved queries live in `~/.tablegrip/`. Passwords never touch that folder: they go to
the macOS Keychain, Windows Credential Manager or the Secret Service on Linux.

## Build from source

```bash
git clone https://github.com/duongductrong/TableGrip.git
cd tablegrip
scripts/run.sh
```

Requirements, tests, packaging and the project layout are in the **[development guide](docs/development.md)**.

## Contributing

Bug reports, ideas and pull requests are welcome. Read the **[contributing guide](CONTRIBUTING.md)** for how to set up
the project, what `./gradlew check` verifies and how commits are written.

Everyone taking part in this project is expected to follow the **[Code of Conduct](CODE_OF_CONDUCT.md)**.

## Sponsor

TableGrip is free and open source. If it saves you time, you can support its development:

- [GitHub Sponsors](https://github.com/sponsors/duongductrong) — recurring support
- [Ko-fi](https://ko-fi.com/duongductrong) — one-time tip
- [PayPal](https://www.paypal.me/duongductrong) — direct support

## License

TableGrip is released under the [Apache License 2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE) and in
`THIRD-PARTY-NOTICES.txt` inside the app.

<div align="center">
<sub>Built with Kotlin, Compose for Desktop and Jewel.</sub>
</div>

---
title: Project layout
description: How the code is organised into modules and packages, and the main ideas that tie it together.
---

TableGrip is a Kotlin project built with Gradle. The UI uses Compose for Desktop with
[Jewel](https://jewel-ui.dev), JetBrains' IntelliJ look and feel for Compose.

## Modules

| Module | Purpose |
| --- | --- |
| `core` | The UI-free domain: connection profiles and the keychain, `Database` (pooled, introspection, paging), per-console `QuerySession`s with cancellation, SQL dialects, the lexer and statement splitter, settings and query history. Everything here is unit tested. |
| `app` | The Compose Desktop + Jewel UI, one package per feature. |
| `engines` | Adapters for non-JDBC databases (`redis`, `mongodb`), shipped as `engines/<name>.jar` outside the classpath and loaded together with their client's jars. |
| `assets/branding` | The app icon's sources and export scripts. |
| `www` | This website and its docs. |
| `docs` | The original user guide, development notes, and design notes in `docs/plans/`. |

### Packages in `core`

`dev.tablegrip.core` is grouped by concern:

| Package | Holds |
| --- | --- |
| `connection` | Connection profiles, database types, URL parsing, SSH tunnels, the secret store |
| `driver`, `drivers` | The driver catalog, downloads, hash checks and class loaders |
| `sql`, `dialect`, `completion` | Lexing, statement splitting, per-engine SQL dialects and completion |
| `metadata`, `schema`, `ddl` | Reading catalogs, table structure, schema compare and DDL generation |
| `edit`, `filter` | Pending grid edits and the typed filter compiler |
| `datafile`, `importer`, `imports`, `export` | Data files as tables, data import, connection import from other tools, export |
| `activity`, `backup`, `tools` | Server activity, backup and restore, and locating client tools |
| `agent`, `assist` | AI agents (CLI and HTTP providers), mentions, chat storage and fix suggestions |
| `settings`, `theme`, `session`, `history`, `queries` | Settings, themes, workspace sessions, query history and saved queries |
| `testdata`, `chart` | The test data generator and chart data |

### Packages in `app`

`dev.tablegrip.app` has one package per feature: `connections`, `explorer`, `workspace`, `console`, `table`, `grid`,
`documents`, `keyvalue`, `files`, `schema`, `activity`, `backup`, `agent`, `chart`, `export`, `search`, `settings`,
`update` and `ui`. `AppState` is the root of the UI state: it owns the app-wide services and wires the feature view
models together.

## Ideas worth knowing

- **Nothing is bundled that can be downloaded.** Drivers and client tools are fetched at runtime and verified; build
  checks fail if one ends up in the installer.
- **Each driver gets its own class loader**, so drivers can't clash with each other or with the app.
- **Pending changes are data.** Grid edits, schema changes and imports are built as a list of statements first, shown
  to the user, and only then run, in one transaction where the database allows.
- **Plain files.** Settings, sessions, history, saved queries and chats are JSON or `.sql` files in the profile folder
  (`TableGripPaths`), written atomically.
- **Secrets only in the OS store**, through [java-keyring](https://github.com/javakeyring/java-keyring), under the
  service name `TableGrip`.

## Dependencies

Versions live in `gradle/libs.versions.toml`. Jewel is pinned to a Compose `1.12.x` and Kotlin `2.3.20`; bump them
together, following the
[Jewel samples](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel/samples/standalone).

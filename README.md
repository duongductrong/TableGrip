# Datagrok

A desktop database client (in the spirit of TablePlus / DataGrip) built with Kotlin,
Compose for Desktop and [Jewel](https://jewel-ui.dev) — the IntelliJ look & feel for Compose.

## Requirements

- JDK 17+ to run Gradle.
- **JetBrains Runtime (JBR) 25** to run the app. Jewel's decorated window only works on JBR.
  Gradle provisions it automatically via the foojay toolchain resolver on the first `run`.

## Commands

```bash
./gradlew :app:run                  # launch the app
./gradlew :core:test                # run core tests
./gradlew :app:packageDistributionForCurrentOS   # build a native installer (dmg/msi/deb)
```

## Layout

| Module | Purpose |
| --- | --- |
| `core` | UI-free domain: connection profiles, JDBC sessions, query results. JDBC drivers for PostgreSQL, MySQL, SQLite. |
| `app`  | Compose Desktop + Jewel UI: decorated window, database explorer, SQL consoles, result grid. |

Versions live in `gradle/libs.versions.toml`. Jewel (`0.41.0-262.10968.63`) is pinned to Compose
`1.12.x` and Kotlin `2.3.20`; bump them together following the
[Jewel samples](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel/samples/standalone).

## Connections

Profiles are saved to `~/.datagrok/connections.json` (no secrets). Passwords go to the OS credential store
via [java-keyring](https://github.com/javakeyring/java-keyring) under the service name `Datagrok`:
macOS Keychain, Windows Credential Manager, or the Secret Service (GNOME Keyring / KWallet) on Linux.

## Next steps

- Schema tree with columns/indexes (`LazyTree`), table data viewer with paging & inline editing
- SQL editor: syntax highlighting, autocomplete, proper statement splitting
- Connection pooling (HikariCP is already a dependency), cancellable queries, SSH tunnels

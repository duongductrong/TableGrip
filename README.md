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
./gradlew :app:run -Pdatagrok.home=/tmp/dg   # launch with a throwaway profile/settings/history directory
./gradlew :core:test                # run core tests
./gradlew :app:packageDistributionForCurrentOS   # build a native installer (dmg/msi/deb)
```

## Layout

| Module | Purpose |
| --- | --- |
| `core` | UI-free domain: connection profiles + keychain, `Database` (pooled, introspection, paging) and per-console `QuerySession`s with cancellation, SQL dialects, lexer and statement splitter, settings, query history. |
| `app`  | Compose Desktop + Jewel UI, one package per feature: `connections`, `explorer`, `workspace`, `console`, `table`. |

Versions live in `gradle/libs.versions.toml`. Jewel (`0.41.0-262.10968.63`) is pinned to Compose
`1.12.x` and Kotlin `2.3.20`; bump them together following the
[Jewel samples](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel/samples/standalone).

## Data grid

Shared by table viewers and console results (`app/.../grid`). Columns stretch to fill the width; drag a header border
to resize (double-click to fit), drag a header to reorder, right-click a header to sort, hide or show columns. Rows and
columns are virtualized; row numbers stay pinned.

| Action | Shortcut |
| --- | --- |
| Move / extend selection | arrows / ⇧ arrows, Home/End, PgUp/PgDn, ⌘↑ ⌘↓ |
| Select all · copy (TSV) · copy with header | ⌘A · ⌘C · ⇧⌘C |
| Edit cell | Enter, F2, double-click, or just type |
| Commit edit · next cell · cancel | Enter · Tab · Esc |
| Add row · delete rows · revert rows · set NULL | ⌘N · ⌘⌫ · ⌘Z · ⌥⌘N |
| Submit pending changes | ⌘Enter |
| Sort · add secondary sort | click header · ⇧-click header |

Right-click cells to copy as CSV / JSON / Markdown / SQL INSERT / IN-list or "Filter by This Value". Edits stay pending
(highlighted) until submitted; they run in one transaction and are refused if a row changed underneath. Tables without
a primary key only allow inserts. On Windows/Linux use Ctrl instead of ⌘.

## Connections

Everything lives in `~/.datagrok/` (override with `-Ddatagrok.home`): `connections.json` (no secrets),
`settings.json`, `history.jsonl` and `logs/`. Passwords go to the OS credential store
via [java-keyring](https://github.com/javakeyring/java-keyring) under the service name `Datagrok`:
macOS Keychain, Windows Credential Manager, or the Secret Service (GNOME Keyring / KWallet) on Linux.

## Roadmap

- **M1 – daily driver** (done): explorer tree, table viewer (paging, sort, WHERE filter, count), SQL console
  (highlighting, run statement at caret / selection / script, cancel, output log, history).
- **M2 – safe editing** (done): inline editing with preview + submit, manual/auto-commit transactions in consoles,
  read-only connections, confirm-before-write and dangerous-statement warnings, environment colours (PROD/STG/DEV),
  SSH tunnels (trust-on-first-use host keys), streaming export to CSV/TSV/JSON/SQL/Markdown.
- **M3 – editor**: autocomplete, formatter, EXPLAIN, snippets.
- **M4 – schema tools**: structure editor, DDL, CSV import, more drivers.
- **M5 – ship**: go-to-anything, session restore, signed installers, auto-update.

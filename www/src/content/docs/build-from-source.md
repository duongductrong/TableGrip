---
title: Build from source
description: Run TableGrip from a checkout, run the tests, and build an installer for your platform.
---

## Requirements

- **JDK 17 or newer** to run Gradle.
- **JetBrains Runtime (JBR) 25** to run the app, because Jewel's decorated window only works on JBR. You don't need to
  install it: Gradle downloads it through the foojay toolchain resolver on the first run.
- Git, and about 2 GB of free disk space for Gradle's caches.

## Run the app

```bash
git clone https://github.com/duongductrong/TableGrip.git
cd tablegrip
scripts/run.sh
```

On Windows, use `scripts\run.cmd`. The first run takes a few minutes while Gradle downloads its dependencies and the
JBR.

`scripts/run.sh` takes a few options:

| Command | Runs the app… |
| --- | --- |
| `scripts/run.sh` | With your normal profile, `~/.tablegrip` |
| `scripts/run.sh --fresh` | With a throwaway profile in a temporary folder |
| `scripts/run.sh --home DIR` | With the profile in `DIR` |
| `scripts/run.sh -- --info` | Passing everything after `--` to Gradle |

The same through Gradle directly:

```bash
./gradlew :app:run
./gradlew :app:run -Ptablegrip.home=/tmp/dg
```

> [!TIP]
> Use `--fresh` or `--home` while developing, so your real connections, settings and history stay untouched.

## Tests

```bash
./gradlew :core:test                                                            # core tests
./gradlew :core:test -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306   # also against real servers
./gradlew :core:test -Pit.network=true                                          # also download the real catalog drivers
./gradlew check                                                                 # everything CI runs
```

The integration tests against real servers seed them from `core/src/test/resources/structure-it`. A quick way to get
servers on those ports:

```bash
docker run -d -p 55432:5432 -e POSTGRES_PASSWORD=postgres postgres:17
docker run -d -p 53306:3306 -e MYSQL_ROOT_PASSWORD=mysql mysql:8
```

`./gradlew check` runs the tests and the build checks that keep drivers out of the installer and every shipped
library's license reviewed. See [Driver catalog & licenses](/docs/driver-catalog).

## Build an installer

```bash
./gradlew :app:packageDistributionForCurrentOS
```

This builds the native installer for the system you're on: a `.dmg` on macOS, an `.msi` on Windows, a `.deb` on Linux.
It lands under `app/build/compose/binaries/`. Signing and notarization only happen in the release workflow; see
[Releases](/docs/releases).

## Editor setup

- **IntelliJ IDEA:** open the folder as a Gradle project.
- **VS Code:** <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>B</kbd> runs **Build & Launch** from `.vscode/tasks.json`.
- **Zed:** picks up `.zed/tasks.json`. Bind <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>B</kbd> to it in
  `~/.config/zed/keymap.json`:

```json
{ "context": "Workspace", "bindings": { "cmd-shift-b": ["task::Spawn", { "task_name": "Build & Launch" }] } }
```

## The website

These docs and the landing page live in `www/`: TanStack Start, Tailwind CSS and shadcn/ui.

```bash
cd www
pnpm install
pnpm dev        # http://localhost:3000
```

Each docs page is a Markdown file in `www/src/content/docs/`, and the sidebar is `www/src/content/docs-nav.ts`.
Screenshots are taken from the real app by `www/scripts/capture.sh`; see `www/README.md`.

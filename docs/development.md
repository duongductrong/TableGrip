# Development

How to build, run and test TableGrip from source. For how to report issues and send pull requests, see the
[contributing guide](../CONTRIBUTING.md).

- [Requirements](#requirements)
- [Running the app](#running-the-app)
- [Tests](#tests)
- [Packaging](#packaging)
- [Editor setup](#editor-setup)
- [Project layout](#project-layout)
- [Dependencies](#dependencies)
- [Drivers and licenses](#drivers-and-licenses)
- [Releases](#releases)

## Requirements

- JDK 17+ to run Gradle.
- **JetBrains Runtime (JBR) 25** to run the app, because Jewel's decorated window only works on JBR. Gradle provisions
  it automatically through the foojay toolchain resolver on the first `run`.

## Running the app

```bash
git clone https://github.com/duongductrong/tablegrip.git
cd tablegrip
scripts/run.sh                                  # scripts\run.cmd on Windows
scripts/run.sh --fresh                          # with a throwaway profile
./gradlew :app:run                              # the same through Gradle
./gradlew :app:run -Ptablegrip.home=/tmp/dg     # with a throwaway profile/settings/history directory
```

The app keeps its data in `~/.tablegrip/` (override with `-Dtablegrip.home`). Use a throwaway profile while developing
so your own connections and settings stay untouched.

## Tests

```bash
./gradlew :core:test                                                            # core tests
./gradlew :core:test -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306   # also against real servers
./gradlew :core:test -Pit.network=true                                          # also download the real catalog drivers
./gradlew check                                                                 # everything CI runs
```

The integration tests against real servers seed them from `core/src/test/resources/structure-it`.

## Packaging

```bash
./gradlew :app:packageDistributionForCurrentOS  # native installer for this OS (dmg/msi/deb)
```

## Editor setup

- **VS Code:** ⇧⌘B (Ctrl+Shift+B) runs **Build & Launch** from `.vscode/tasks.json`.
- **Zed:** picks up `.zed/tasks.json`. Bind ⇧⌘B to it in `~/.config/zed/keymap.json`:
  ```json
  { "context": "Workspace", "bindings": { "cmd-shift-b": ["task::Spawn", { "task_name": "Build & Launch" }] } }
  ```

## Project layout

| Module | Purpose |
| --- | --- |
| `core` | UI-free domain: connection profiles and keychain, `Database` (pooled, introspection, paging) and per-console `QuerySession`s with cancellation, SQL dialects, lexer and statement splitter, settings, query history. |
| `app` | Compose Desktop + Jewel UI, one package per feature: `connections`, `explorer`, `workspace`, `console`, `table`. |
| `engines` | Adapters for non-JDBC databases (`redis`, `mongodb`), shipped as `engines/<name>.jar` off the classpath and loaded together with their client's jars. |
| `assets/branding` | App icon sources and export scripts ([details](../assets/branding/README.md)). |
| `docs` | [User guide](guide.md), this page, and design notes in `plans/`. |

## Dependencies

Versions live in `gradle/libs.versions.toml`. Jewel (`0.41.0-262.10968.63`) is pinned to Compose `1.12.x` and
Kotlin `2.3.20`; bump them together following the
[Jewel samples](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel/samples/standalone).

## Drivers and licenses

No JDBC driver ships with the app; users download them at runtime (see the
[user guide](guide.md#drivers)). Three checks, all part of `./gradlew check`, keep it that way:

| Task | Fails when |
| --- | --- |
| `:app:verifyNoBundledDrivers` | a driver ends up in the installer (also runs before every `package*` task) |
| `:app:thirdPartyNotices` | a shipped library's license hasn't been reviewed into `bundledLicenses` in `app/build.gradle.kts` |
| `:core:verifyDriverLock` | `core/src/main/resources/dev/tablegrip/core/drivers/drivers.lock.json` is stale |

After changing the driver catalog in `core/build.gradle.kts`, regenerate the lockfile:

```bash
./gradlew :core:lockDrivers
```

## Releases

The version is `tablegrip.version` in `gradle.properties`. Releases go through a PR:

1. Run **Release Prepare** (Actions › Release Prepare › Run workflow) with a bump (`patch`, `minor`, `major`) and a
   channel (`stable`, `beta`), or an exact version for the first release. Pushing a commit to `master` whose subject
   starts with `release(minor):` (or `release(minor-beta):`) does the same.
2. It opens `chore: release vX.Y.Z`, which bumps the version and adds the release's entry to
   [CHANGELOG.md](../CHANGELOG.md), written from the Conventional Commits since the previous release. Edit the notes
   on the PR's branch if needed.
3. Merge it. The merge commit is tagged `vX.Y.Z` and `.github/workflows/release.yml` builds, signs and notarizes the
   installers (after you approve the `release` environment), publishes the GitHub Release and updates the feeds.

Locally, `python3 scripts/bump-version.py minor --channel beta` and
`python3 scripts/release-notes.py changelog --version 1.1.0-beta.1 --repo duongductrong/tablegrip` show what the PR
would contain (revert them afterwards).
Signing secrets and the update feed are described in [plans/auto-update.md](plans/auto-update.md).

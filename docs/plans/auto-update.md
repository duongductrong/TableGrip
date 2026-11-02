# Auto-update

Status: **not started**. As of 2026-11-02 there is no update code on any branch (`master`, `feature/m1-data-grid`,
`feature/phase0-driver-library`); the only mention is the M5 roadmap line. This plan says what has to change before
updates can work, and how to build them.

## What blocks updates today

Problems in the current packaging that would break or weaken any updater, whichever one is picked:

| # | Problem | Where | Effect |
|---|---|---|---|
| 1 | `packageVersion = "1.0.0"` is hard-coded, and the app can't read its own version. | `app/build.gradle.kts:66` | An updater compares versions; every build is "1.0.0", so nothing is ever newer. |
| 2 | No `windows { upgradeUuid = … }`. | `app/build.gradle.kts` `nativeDistributions` | Compose generates a new UpgradeCode per build: a newer MSI installs next to the old one instead of replacing it. |
| 3 | MSI is per-machine (Compose's default). | same | Every update asks for admin (UAC); silent updates are impossible for most users. |
| 4 | No code signing or notarization for macOS, no Authenticode for Windows. | no `macOS { signing / notarization }`, no CI | Gatekeeper blocks the downloaded update; Sparkle refuses an unsigned or differently-signed bundle; SmartScreen warns on Windows. |
| 5 | The repository is private and has no license. | GitHub | Release assets on a private repo can't be downloaded without a token. The plan is to open-source it under Apache-2.0, which makes GitHub the host (see *Feeds and hosting*). |
| 6 | No CI / release pipeline (`.github/` doesn't exist). | — | Signing keys need a place to live that isn't a laptop; feeds have to be generated the same way every time. In a public repo, also a place forks and PRs can't reach. |
| 7 | One build per architecture (the bundled JBR isn't universal). | — | An arm64 Mac must never be offered the x64 bundle: one feed per OS and architecture. |

## Options

| | Sparkle 2 (macOS) + WinSparkle (Windows) | Conveyor (Hydraulic) | Hand-written updater in Kotlin |
|---|---|---|---|
| How | Native updater frameworks behind a tiny C shim, called from Kotlin with JNA. Same appcast format and Ed25519 keys on both. | Replaces jpackage: builds, signs, notarizes and publishes; Sparkle on macOS, MSIX on Windows. | Kotlin checks a signed manifest, downloads, verifies, then a helper script swaps the app after quit. |
| Replacing the app | Sparkle: atomic bundle swap, handles translocation, quarantine, permissions, relaunch. WinSparkle: runs the new MSI. | Handled. | Ours to get right on every OS (the hard, dangerous part). |
| Security | Ed25519 on every archive, plus Apple code-signature match. Proven, audited. | Same model, managed. | Only as good as our code. |
| Cost / lock-in | Free (MIT; WinSparkle MIT). Keeps the Compose Gradle packaging. | Free for open-source projects under an OSI license, paid otherwise. Packaging, signing and publishing move to Conveyor's config and CLI. | Free, most work, most risk. |
| Windows install | MSI, run by WinSparkle; per-user so no UAC. | MSIX, updated in the background by Windows itself. | Ours. |
| Linux | Notify-only (Phase 3). | `.deb` with an apt repository: real updates. | Notify-only. |
| Native UI | Sparkle/WinSparkle dialogs (what users of Mac apps expect). | Same on macOS; Windows' own on Windows. | Jewel UI. |

**Decided: Sparkle 2 + WinSparkle.** It is what "like Sparkle" means, it keeps the current build, the
security-sensitive part (verifying and swapping a running app) is done by code many apps already trust, and it stays
free whatever happens to the license later. It's also what Snapzy uses, so the release scripts carry over.

Conveyor (free once the project is open source) would have covered signing, Windows and Linux updates in one tool, at
the cost of moving packaging out of the Compose Gradle plugin; not taken. A hand-written updater only makes sense for
Linux, where it is reduced to "tell the user".

## Design

### Version

- One source: `datagrok.version` (e.g. `1.4.0`) and `datagrok.build` (an increasing integer from CI) in
  `gradle.properties`. `packageVersion` reads it; a generated resource `datagrok-version.properties` lets the app show
  it (About, Settings › Updates) and compare it.
- macOS: `CFBundleShortVersionString` = version, `CFBundleVersion` = build. Sparkle compares `CFBundleVersion`, so the
  build number must only ever go up. Windows: MSI ProductVersion = version (MSI only compares the first three parts).

### Feeds and hosting

Plain GitHub, the way Snapzy (`duongductrong/Snapzy`) already ships: nothing to pay for, run or configure, and its
release workflow and `update-appcast.sh` can be reused.

| What | Where | URL the app uses |
|---|---|---|
| Archives (zip/DMG, MSI) | GitHub Release of the tag | `https://github.com/duongductrong/datagrok/releases/download/v1.4.0/Datagrok-1.4.0-macos-arm64.zip` (redirects to GitHub's CDN; Sparkle and WinSparkle follow it) |
| Appcasts | `appcast/` folder on `master`, committed by the release workflow | `https://raw.githubusercontent.com/duongductrong/datagrok/master/appcast/macos-arm64.xml` |
| Release notes | GitHub Release body (from `CHANGELOG.md`), and an HTML copy as a release asset | `sparkle:releaseNotesLink` to the asset |

What plain GitHub costs, and how each is handled:

- **The feed URL is baked into installed copies.** It names the owner and repo, so a rename or a move to an
  organisation relies on GitHub's redirect from the old name, which stops working if anyone ever creates a repo at the
  old name. Mitigation: the app sets the feed URL at runtime through the shim (`dg_updater_set_feed`), not only in
  `Info.plist`, so a later release can move to another host; copies older than that release keep reading the old URL,
  so keep the old one updated for a while after a move.
- **`raw.githubusercontent.com` caches for about five minutes** and isn't meant as a download host. A check per user
  per day is far below any limit, and five minutes of delay doesn't matter for updates. Only the small XML comes from
  there; the archives come from Releases.
- **Every release adds a commit to `master`** ("chore: update appcast for v1.4.0"), like Snapzy. If `master` is
  protected, the release workflow is the one actor allowed to bypass it for `appcast/`.
- Upgrading to a custom domain later is possible but only helps copies released after the switch; there's no reason
  to do it up front.

The rules:

- HTTPS only; the app refuses `http:` feed or download URLs.
- One appcast per target: `macos-arm64.xml`, `macos-x64.xml`, `windows-x64.xml`. The URL is chosen at runtime from the
  OS and `os.arch`.
- Channels within each appcast, not separate files: beta items get `<sparkle:channel>beta</sparkle:channel>`, and the
  app allows the `beta` channel only when the setting says so (Sparkle's `allowedChannels(for:)`; on Windows the
  bridge fetches the beta-inclusive or stable-only variant, since WinSparkle has no channels: `windows-x64.xml` and
  `windows-x64-beta.xml`). A beta is published as a GitHub *pre-release*.
- Each item carries `sparkle:edSignature` and `length` for its archive, `sparkle:minimumSystemVersion`, a release
  notes link, and `sparkle:criticalUpdate` / `sparkle:phasedRolloutInterval` when needed. Keep the last few releases
  in each appcast so delta updates and "what's new since your version" work.
- Releases are never deleted or re-uploaded under the same tag: installed copies may be mid-download, and a changed
  file fails its signature. A bad release is pulled by removing its item from the appcast and shipping a higher build.
- macOS archive: the signed, notarized, stapled `.dmg` (or a `.zip` of the `.app`, smaller, and what Sparkle prefers
  for delta updates). Windows archive: the signed `.msi`.

### Keys and signing

- **Ed25519 update key**: generated once with Sparkle's `generate_keys`; the private key lives only in the CI secret
  store (plus an offline backup), never in the repo or on a dev machine's keychain long-term. The public key is baked
  in: `SUPublicEDKey` in `Info.plist` on macOS, `win_sparkle_set_eddsa_public_key` on Windows.
- **Signed feed**: sign the appcast too (`generate_appcast` does it) and set `SURequireSignedFeed` so a compromised
  host can't even show fake release notes or point to an old version.
- **Apple**: Developer ID Application certificate, hardened runtime, notarization and stapling in CI. Sparkle checks
  that the update is signed by the same Team ID as the running app, so this is a second, independent check.
- **Windows**: Authenticode-sign the MSI and the exe launcher (Azure Trusted Signing is the cheap option; an EV
  certificate also clears SmartScreen at once).
- **Key loss / rotation**: Sparkle accepts an update signed by *either* the Ed25519 key or a matching Apple
  certificate, which is the documented way to rotate one of them. Write the rotation steps down before the first release.

### A public repo

Signatures are what make the update trustworthy, not where it's hosted: anyone can read the appcast and archives, and
that's fine. What has to be protected is who can *produce* a signed release:

- Signing secrets (Ed25519 key, Apple certificate and notarization credentials, Windows signing credentials) live in a
  GitHub **environment** named `release`, with a required reviewer (the maintainer), usable only from `v*` tags.
  Workflows triggered by pull requests never see them (GitHub withholds secrets from forks; never use
  `pull_request_target` to build PR code).
- Branch protection on `master` (the release workflow may bypass it, for `appcast/` only) and tag protection on `v*`.
  A `CODEOWNERS` entry for `appcast/` so a PR touching it needs the maintainer's review.
- Third-party actions pinned by commit SHA; Sparkle and WinSparkle downloaded at pinned versions and checked against
  pinned SHA-256s, like `Tools.kt` already does for tools.
- Signed feed (`SURequireSignedFeed`): someone who gets a change into `appcast/` on `master` (a careless merge, a
  leaked token), but doesn't have the key, still can't serve a feed the app accepts. Snapzy doesn't do this yet.
- Publish build provenance (`actions/attest-build-provenance`) and the SHA-256 of each archive in the release notes,
  so people who download by hand can check too.

### Native bridge

`app/native/updater/` holds a small shim per OS with the same C interface, loaded with JNA (already shipped, Apache-2.0):

```c
void dg_updater_start(const char *feedUrl, int autoCheck, int autoDownload);
void dg_updater_check_now(void);           // "Check for Updates…": shows UI, also when up to date
void dg_updater_check_in_background(void); // silent; UI only if an update exists
void dg_updater_set_feed(const char *feedUrl);
void dg_updater_set_can_quit(int (*canQuit)(void), void (*requestQuit)(void));
```

- **macOS** (`DatagrokUpdater.dylib`, ~80 lines of Swift/Obj-C): wraps `SPUStandardUpdaterController`. All calls are
  dispatched to the main queue (AWT already runs NSApp there). `Sparkle.framework` goes in `Contents/Frameworks`, the
  dylib in `Contents/app/`, and both are signed with the app.
- **Windows** (`WinSparkle.dll` + a few lines of C, or JNA straight on WinSparkle's C API): `win_sparkle_init` and
  friends; `can_shutdown` and `shutdown_request` callbacks are the two above.
- Kotlin side: `dev.datagrok.app.update.Updater` (interface) with `SparkleUpdater`, `WinSparkleUpdater`, and
  `NoopUpdater` for `:app:run` and Linux. Dev runs never load the shim, so a debug build can't update itself.

### Quitting for an install

Installing means quitting, and quitting must go through `AppState.requestExit` (open transactions, unsubmitted
edits, unsaved data files) like ⌘Q does:

- macOS: Sparkle asks the app to terminate with `[NSApp terminate:]`, which arrives at the quit handler in
  `Main.kt:61`; that already routes to `requestExit`. If the user cancels there, Sparkle keeps the update for the next
  quit. **To verify**: that `exitApplication()` ends the JVM process promptly, since Sparkle's installer waits for it.
- Windows: `can_shutdown` returns false while `requestExit` would ask something; `shutdown_request` calls `requestExit`.
- Never install while a long export, import, backup or restore is running: treat those like unsaved work.

### User-facing behaviour

- **Menu**: "Check for Updates…" in the app menu (macOS) and Help (Windows).
- **Settings › Updates** (new panel, values in `settings.json` like everything else and pushed to the shim at start):
  - Check for updates automatically (default on; once a day, Sparkle's default interval).
  - Download and install updates automatically (default on): downloads in the background, installs on the next quit,
    no dialog. Off: Sparkle's dialog with release notes and *Install*, *Remind Me Later*, *Skip This Version*.
  - Channel: Stable / Beta.
  - Current version, last check time, "Check Now".
- No first-run "may we check?" prompt (`SUEnableAutomaticChecks` = YES); the setting is the opt-out.
- Privacy: no system profiling (`SUEnableSystemProfiling` = NO). The only thing sent is the request for the feed
  (with a `Datagrok/<version>` user agent). Say so in Settings › Updates, next to the agent data-sharing setting.
- Release notes come from `CHANGELOG.md`, so they can't drift from the code.

## Release pipeline (GitHub Actions)

On a `v*` tag (pre-release if the version has a `-beta` suffix), in the `release` environment:

1. Build per target on its own runner: `macos-14` (arm64), `macos-13` (x64), `windows-latest`.
2. `createDistributable`, then add `Sparkle.framework` and the shim (macOS) or `WinSparkle.dll` (Windows).
3. Sign: `codesign --options runtime --timestamp` inside-out (dylibs, framework, JBR, launcher, app); Authenticode on
   Windows.
4. Package the DMG / MSI, notarize and staple (macOS), sign the MSI.
5. `sign_update` each archive with the Ed25519 key.
6. Create the GitHub Release as a **draft** and upload every archive to it.
7. A smoke job installs the previous release, points it at a staging feed (a local file, as Snapzy's
   `test-update-local.sh` does), and checks it updates to the new one.
8. Publish the release, then update the appcast per target (signed feed) and commit it to `appcast/` on `master`.
   Archives first, appcast last, so a feed never points to a file that isn't there yet.

## Phases

- **Decided**: Sparkle 2 + WinSparkle; appcasts on `master` served from `raw.githubusercontent.com`, archives on
  GitHub Releases; the project is licensed Apache-2.0.
- **Phase 0, prerequisites** (fixes problems 1–6): `LICENSE` (Apache-2.0) and `NOTICE` at the root, shipped in the app
  next to `THIRD-PARTY-NOTICES.txt`; version source, `upgradeUuid`, `perUserInstall = true`,
  Apple and Windows signing in CI with the `release` environment, Ed25519 key pair. Useful even without an updater:
  signed installers are the other half of M5.
- **Phase 1, macOS**: Sparkle shim, feeds, menu item, Settings › Updates, quit handling, staging-feed test.
- **Phase 2, Windows**: WinSparkle bridge, MSI upgrade test (old version replaced, settings and `~/.datagrok` kept).
- **Phase 3**: Linux `.deb` gets notify-only (read the same feed, verify its signature with the JDK's `Ed25519`
  `Signature`, show a banner linking to the download); delta updates on macOS; a Jewel-styled update dialog via
  Sparkle's custom user driver, if the native one looks out of place.

## Tests

- Version comparison and feed URL selection (OS, arch, channel): unit tests in `app`.
- Quit gating: with an open transaction, a Sparkle/WinSparkle quit request shows the usual dialog, and Cancel leaves
  the app running.
- Manual release checklist per OS: update from N-1 and N-2, update with the app in `~/Downloads` (translocation), with
  no network, with a tampered archive (must be rejected), with a feed listing an older build (must be ignored).

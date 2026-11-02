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

- One source in `gradle.properties`: `datagrok.version` (`1.4.0`, or `1.4.0-beta.2` for a pre-release) and
  `datagrok.build`, an integer that only goes up. Local builds use `1`; the release workflow passes the commit count
  of `master` (`git rev-list --count HEAD`), so the same commit always gets the same build. The build fails on a
  malformed version or a build outside 1–65535.
- The app reads both from the generated `datagrok-version.properties` (`dev.datagrok.app.update.AppVersion`).
- macOS: `CFBundleShortVersionString` = `1.4.0` (installers can't carry a suffix), `CFBundleVersion` = build. Sparkle
  compares `CFBundleVersion`.
- Windows: MSI ProductVersion = `MAJOR.MINOR.BUILD` (`1.4.412`). MSI ignores a fourth part and won't replace an
  installed copy of the same version, so `1.4.0-beta.2` and `1.4.0` would otherwise collide; the build number keeps
  them apart and always increasing.
- The tag must equal `datagrok.version` (`v1.4.0`) and be on `master`, or the release workflow stops.

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

- **macOS** (built, Phase 1): `app/src/macos/updater/DatagrokUpdater.m`, ~100 lines of Objective-C around
  `SPUStandardUpdaterController`, compiled by `:app:buildUpdaterBridge` into a universal
  `Contents/Frameworks/libDatagrokUpdater.dylib`. Its C functions (`dg_updater_start`, `_configure`, `_check`,
  `_last_check`, `_terminate`) are called from Kotlin with JNA. Sparkle runs on the main thread (AppKit's); only
  `dg_updater_start` waits for it, and it's called off the AWT event thread to avoid a deadlock with AppKit.
- `:app:downloadSparkle` fetches Sparkle's release archive (pinned version and SHA-256), which also provides
  `sign_update` to the workflows. `createDistributable` then copies `Sparkle.framework` into `Contents/Frameworks` with
  `ditto` (keeps its symlinks), drops its XPC services (only sandboxed apps need them), and signs inside out:
  Autoupdate, Updater.app, the framework, the bridge, then the app with `src/macos/entitlements.plist`. Without
  signing properties it ad-hoc signs, so local builds still run.
- Info.plist: `SUPublicEDKey` from `datagrok.sparklePublicKey`, `SURequireSignedFeed`, `SUVerifyUpdateBeforeExtraction`,
  `SUEnableAutomaticChecks`, and `SUEnableSystemProfiling` = NO. The feed URL isn't in Info.plist: the bridge's delegate
  gives it, so a later version can move it.
- Kotlin: `dev.datagrok.app.update.Updater`. `Updater.create()` gives the Sparkle one only in an installed Mac app
  that has the bridge; `:app:run`, tests, Windows and Linux get one that does nothing and says why.
- **Windows** (Phase 2): `WinSparkle.dll` behind the same Kotlin interface; its `can_shutdown` and
  `shutdown_request` callbacks map to `requestExit`.

### Quitting for an install

Installing means quitting, and quitting must go through `AppState.requestExit` (open transactions, unsubmitted
edits, unsaved data files) like ⌘Q does:

- The JVM quitting on its own (`exitApplication`) doesn't tell AppKit the app is terminating, and Sparkle installs a
  downloaded update when it sees that. So once the user has agreed to quit, `Main.kt`'s `exit()` asks the updater to
  quit through `[NSApp terminate:]`, and the quit handler lets that one request through (`Updater.quitting`).
- Sparkle's own "Install and Relaunch" sends `[NSApp terminate:]` too: it reaches the quit handler, goes through
  `requestExit` like ⌘Q, and if the user cancels there, the update waits for the next quit.
- Windows: `can_shutdown` returns false while `requestExit` would ask something; `shutdown_request` calls `requestExit`.
- Never install while a long export, import, backup or restore is running: treat those like unsaved work. (Not done
  yet: today only open transactions, unsubmitted edits and unsaved data files hold a quit.)

### User-facing behaviour

- **Check for Updates…** in Go to Anything (⌘P) and as *Check Now* in Settings › Updates. The app has no menu bar of
  its own, and Java can't add items to the macOS app menu.
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

`.github/workflows/release.yml`. A `v*` tag makes a signed draft release; "Run workflow" by hand makes an unsigned dry
run whose installers stay as workflow artifacts.

Secrets, in the `release` environment (Settings › Environments; add yourself as required reviewer and allow only
`v*` tags):

| Secret | What |
|---|---|
| `MAC_CERTIFICATE_P12` | Developer ID Application certificate and key, exported as .p12, base64 |
| `MAC_CERTIFICATE_PASSWORD` | its export password |
| `MAC_SIGNING_IDENTITY` | `Name (TEAMID)`, the part after "Developer ID Application: " |
| `MAC_NOTARIZATION_APPLE_ID`, `MAC_NOTARIZATION_PASSWORD`, `MAC_NOTARIZATION_TEAM_ID` | Apple ID, an app-specific password for it, team ID |
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID` | an Entra app with a federated credential for this repo's `release` environment (OIDC, no client secret) and the *Artifact Signing Certificate Profile Signer* role |
| `AZURE_SIGNING_ENDPOINT`, `AZURE_SIGNING_ACCOUNT`, `AZURE_CERTIFICATE_PROFILE` | the Artifact Signing (formerly Trusted Signing) account's region endpoint, name, and certificate profile |

The full pipeline, once Phase 1 adds Sparkle. Phase 0 does steps 1, 3, 4 and 6 (Compose's own signing and
notarization on macOS, Artifact Signing on Windows, plus an unsigned `.deb`); publishing the draft is still by hand.
A version with a `-` suffix becomes a pre-release.

1. Build per target on its own runner: `macos-15` (arm64), `macos-15-intel` (x64), `windows-latest`, `ubuntu-latest`.
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
- **Phase 0, prerequisites** (fixes problems 1–6). Done in code: `LICENSE` (Apache-2.0) and `NOTICE`, shipped in
  the app and shown by the installers; version source; `upgradeUuid`; `perUserInstall = true`; installers bundle the
  JBR toolchain instead of whatever `JAVA_HOME` is; `.github/workflows/release.yml` builds, signs, notarizes and
  attaches everything to a draft release. Left for the maintainer: the accounts and secrets below, and the Ed25519 key
  pair (needed from Phase 1).
- **Phase 1, macOS** (done in code): Sparkle bridge and bundling, Settings › Updates, *Check for Updates…* in ⌘P,
  quit handling, `scripts/appcast.py` (feeds from `appcast/releases.json`), `appcast.yml` (on a published release:
  add it to the feeds, sign them, commit), and the release workflow signing each DMG. `scripts/test-update.sh`
  updates a local 99.0.0 build to 99.0.1 end to end; checked on 2026-11-02 on Apple silicon, ad-hoc signed: the
  automatic check downloaded the update and it installed on quit, and a feed changed after signing was refused (no
  download). Left for the maintainer: generate the real key pair (`generate_keys`), put the public key in
  `datagrok.sparklePublicKey` and the private key (`generate_keys -x`) in `SPARKLE_PRIVATE_KEY`.
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

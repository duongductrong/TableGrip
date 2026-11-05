---
title: Releases
description: How versions are numbered, how a release is prepared and published, and how the update feeds are built.
---

This page is for maintainers. Users only need [Updates](/docs/updates).

## Versions

The version is `tablegrip.version` in `gradle.properties`. Releases are `MAJOR.MINOR.PATCH`, with `-beta.N` for
pre-releases on the beta channel.

## Making a release

Releases go through a pull request:

1. **Start it.** Run **Release Prepare** (Actions › Release Prepare › Run workflow) with a bump (`patch`, `minor`,
   `major`) and a channel (`stable`, `beta`), or an exact version for the first release. Pushing a commit to `master`
   whose subject starts with `release(minor):` (or `release(minor-beta):`) does the same.
2. **Review it.** The workflow opens `chore: release vX.Y.Z`, which bumps the version and adds the release's entry to
   [`CHANGELOG.md`](https://github.com/duongductrong/TableGrip/blob/master/CHANGELOG.md), written from the Conventional
   Commits since the previous release. Edit the notes on the PR's branch if needed.
3. **Merge it.** The merge commit is tagged `vX.Y.Z`, and `.github/workflows/release.yml` builds, signs and notarizes
   the installers (after you approve the `release` environment), publishes the GitHub Release and updates the feeds.

## Trying it locally

These show what the release PR would contain. Revert their changes afterwards.

```bash
python3 scripts/bump-version.py minor --channel beta
python3 scripts/release-notes.py changelog --version 1.1.0-beta.1 --repo duongductrong/TableGrip
```

## Update feeds

The apps check appcast feeds in the repository, one per platform and channel:

```text
https://raw.githubusercontent.com/duongductrong/TableGrip/master/appcast/macos-arm64.xml
https://raw.githubusercontent.com/duongductrong/TableGrip/master/appcast/macos-x64.xml
https://raw.githubusercontent.com/duongductrong/TableGrip/master/appcast/windows-x64.xml
https://raw.githubusercontent.com/duongductrong/TableGrip/master/appcast/windows-x64-beta.xml
```

Each update is signed with an **Ed25519** key, and the apps refuse an update whose signature doesn't verify.

To test the updater against a feed of your own, set `TABLEGRIP_UPDATE_FEED` to its URL. It must be HTTPS, or HTTP on
`localhost`. `scripts/test-update.sh` helps with that.

## Signing

The release workflow signs and notarizes the macOS app, and can sign the Windows installer with Authenticode when its
secrets are configured. The secrets and the feed setup are described in
[`docs/plans/auto-update.md`](https://github.com/duongductrong/TableGrip/blob/master/docs/plans/auto-update.md).

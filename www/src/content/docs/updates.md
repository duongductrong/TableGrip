---
title: Updates
description: How TableGrip keeps itself up to date on macOS and Windows, release channels, and updating on Linux.
---

## macOS

TableGrip updates itself with [Sparkle](https://sparkle-project.org), the standard updater for Mac apps.

- It checks for a new version **once a day**.
- With **Download and install updates automatically** on (the default), the update downloads in the background and is
  **installed the next time you quit**. Nothing interrupts your work.
- With it off, TableGrip asks first and shows the release notes.

## Windows

TableGrip uses [WinSparkle](https://winsparkle.org) and the `.msi` installer.

- It checks for a new version automatically, in the background.
- Windows always shows the update dialog **before installing**, so on Windows the automatic setting only makes the
  check automatic.

## Linux

There is no built-in updater on Linux. Download the newer `.deb` from the
[releases page](https://github.com/duongductrong/TableGrip/releases) and install it over the old one:

```bash
sudo apt install ./TableGrip_*.deb
```

Your settings and connections are kept.

## Check now

Two ways, on macOS and Windows:

- **Settings → Updates → Check for Updates**.
- [Go to anything](/docs/navigation#go-to-anything) (<kbd>⌘</kbd> <kbd>P</kbd>) → **Check for Updates…**

When an update is ready, the same page shows **Download and Install…** or **Restart to Update**, plus **What's new** and
a link to the **Releases page**.

## Channels

| Channel | You get |
| --- | --- |
| **Stable** (default) | Finished releases only |
| **Beta** | Pre-releases too, a little earlier and a little rougher |

Switch in **Settings → Updates → Channel**. Every release's notes are in the
[changelog](https://github.com/duongductrong/TableGrip/blob/master/CHANGELOG.md).

## Turning updates off

In **Settings → Updates**, turn off **Check for updates automatically**. You can still check by hand at any time.

## Is it safe?

- Every update is verified with an **Ed25519 signature** before it is installed. A tampered download is rejected.
- On macOS, the app is also signed and notarized by Apple.
- A check only asks GitHub for the list of releases. **Nothing about you or your databases is sent.**

## Which version am I running?

- **macOS:** the TableGrip menu → **About TableGrip** shows the version and build.
- **All systems:** **Settings → Updates** shows `TableGrip <version>`.

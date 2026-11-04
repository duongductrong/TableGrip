---
title: Installation
description: Download and install TableGrip on macOS, Windows or Linux.
---

TableGrip is a free download from the
[releases page on GitHub](https://github.com/duongductrong/TableGrip/releases). Pick the installer for your system:

| System | Package | Notes |
| --- | --- | --- |
| **macOS** | `.dmg` | Signed and notarized by Apple. Updates itself. |
| **Windows** | `.msi` | Installs for your user only, so no administrator rights are needed. Updates itself. |
| **Linux** | `.deb` | For Debian, Ubuntu and their derivatives. Update by installing the newer package. |

## macOS

1. Download the `.dmg` from the [latest release](https://github.com/duongductrong/TableGrip/releases).
2. Open it and drag **TableGrip** into **Applications**.
3. Open TableGrip from Applications or Spotlight.

Because the app is signed and notarized, macOS opens it without warnings.

## Windows

1. Download the `.msi` from the [latest release](https://github.com/duongductrong/TableGrip/releases).
2. Run it and follow the installer. It installs for the current user, with a **TableGrip** group in the Start menu.
3. Start TableGrip from the Start menu.

> [!NOTE]
> If Windows SmartScreen shows "Windows protected your PC", choose **More info → Run anyway**. Releases that aren't
> code-signed for Windows can trigger this warning.

## Linux

Download the `.deb` from the [latest release](https://github.com/duongductrong/TableGrip/releases) and install it:

```bash
sudo apt install ./TableGrip_*.deb
```

Then start **TableGrip** from your applications menu.

Passwords are stored with the **Secret Service** (GNOME Keyring or KWallet). Most desktop environments run one
already; if yours doesn't, install `gnome-keyring` so TableGrip can save passwords.

## What gets installed

The installer contains the app and its own Java runtime, so you don't need Java on your machine. It does **not**
contain any database driver: TableGrip downloads the driver for each database the first time you connect. See
[Drivers](/docs/drivers).

Your settings and connections are created on first start in a folder in your home directory, `~/.tablegrip/`.
[Where your data lives](/docs/your-data) explains every file in it.

## Updates

On macOS and Windows, TableGrip checks for updates by itself and can install them for you. On Linux, download the
newer `.deb` from the releases page. See [Updates](/docs/updates).

## Build it yourself

Prefer to run from source? See [Build from source](/docs/build-from-source).

## Next step

Connect to your first database: [Quick start](/docs/quick-start).

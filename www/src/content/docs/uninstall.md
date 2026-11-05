---
title: Uninstall
description: Remove TableGrip, and optionally everything it stored on your computer.
---

Removing the app leaves your settings, connections and saved queries in place, so reinstalling later picks up where you
left off. To remove everything, also delete the data folder and the saved passwords.

## 1. Remove the app

| System | How |
| --- | --- |
| **macOS** | Quit TableGrip, then drag **TableGrip** from **Applications** to the Trash. |
| **Windows** | **Settings → Apps → Installed apps**, find **TableGrip**, then **Uninstall**. |
| **Linux** | `sudo apt remove tablegrip` |

## 2. Remove your data (optional)

Everything TableGrip writes lives in one folder, `~/.tablegrip/`: connections, settings, saved queries, query history,
agent chats, downloaded drivers and tools, and logs. Delete the folder to remove it all.

```bash
rm -rf ~/.tablegrip
```

On Windows the folder is `%USERPROFILE%\.tablegrip`.

> [!WARNING]
> Saved queries live in `~/.tablegrip/queries/`. Copy them somewhere first if you want to keep them.

## 3. Remove saved passwords (optional)

Passwords and API keys are stored in your system's credential store under the service name **`TableGrip`**:

| System | Where to look |
| --- | --- |
| **macOS** | **Keychain Access**: search for `TableGrip` and delete the items. |
| **Windows** | **Credential Manager → Windows Credentials**: remove the entries for `TableGrip`. |
| **Linux** | **Passwords and Keys** (Seahorse) or KWallet Manager: search for `TableGrip`. |

There is one item per connection password, one per SSH password or key passphrase (`<connection>:ssh`), and one per
AI provider API key (`agent-provider:anthropic`, `agent-provider:openai`).

> [!TIP]
> Deleting a connection inside TableGrip already removes its keychain items. If you delete your connections before
> uninstalling, only API keys may be left.

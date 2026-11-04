---
title: Go to anything & sessions
nav: Navigation & sessions
description: Jump to any table, tab, query or action from the keyboard, and pick up exactly where you left off.
---

## Go to anything

Press <kbd>⌘</kbd> <kbd>P</kbd>, or <kbd>⇧</kbd> twice, and start typing. One field finds:

- **Tables and views** of every connected connection
- **Open tabs**
- **Saved queries**
- **Connections**
- **Recent queries** of the current connection
- **Actions**, such as New Console, New Connection…, Import Connections…, Activity, Settings, Keyboard Shortcuts and
  Check for Updates…

![Go to anything, listing tables, tabs and actions that match what was typed](shot:goto)

Matching is fuzzy by word starts: `uo` finds `user_orders`, `ncon` finds **New Console**.

| Key | Does |
| --- | --- |
| <kbd>↩</kbd> | Open the selected item |
| <kbd>⇧</kbd> <kbd>↩</kbd> | Open a table on its [Structure](/docs/table-structure) view |
| <kbd>Esc</kbd> | Close |

> [!TIP]
> With the **DataGrip / IntelliJ** keymap, Go to anything is <kbd>⌘</kbd> <kbd>O</kbd> instead. See
> [Keyboard shortcuts](/docs/keyboard-shortcuts#keymap-presets).

## The window at a glance

| Area | What it is | Shortcut |
| --- | --- | --- |
| **Tool stripe** (far left) | Buttons for the tool windows below, and the agent | |
| **Connections** | The explorer: connections, schemas, tables, columns | <kbd>⌘</kbd> <kbd>1</kbd> |
| **Query History** | Every statement you ran | <kbd>⌘</kbd> <kbd>2</kbd> |
| **Saved Queries** | Your `.sql` files | <kbd>⌘</kbd> <kbd>4</kbd> |
| **Tabs** (centre) | Consoles, tables, Activity, ER diagrams… | <kbd>⌘</kbd> <kbd>W</kbd> closes one |
| **Agent** (right) | The [AI chat](/docs/ai-agent) | <kbd>⌘</kbd> <kbd>3</kbd> |
| **Status bar** (bottom) | Breadcrumbs, the selection summary, warnings | |

Press <kbd>⌘</kbd> <kbd>B</kbd> to hide or show the sidebar. Move between tabs with <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>]</kbd>
/ <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>[</kbd> or <kbd>⌃</kbd> <kbd>Tab</kbd>.

With no tab open, the workspace reminds you how to start: double-click a table to browse it, or press
<kbd>⌘</kbd> <kbd>T</kbd> for a new console.

## Sessions

TableGrip remembers your workspace. When you quit and open it again, you get back:

- **Consoles**, including unsaved text and the caret position
- **Tables**, with their filter and whether Data or Structure was showing
- The tool window, the agent chat, the window size and the panel widths

The session is saved every few seconds while something changes, to `~/.tablegrip/session.json`. Tables only load their
rows once their tab is shown, so a big session still opens quickly.

Quitting only asks about work a restart can't bring back: **open transactions** and **unsubmitted edits**.

What comes back at start is configurable in [Settings → Startup & Layout](/docs/settings#startup-layout): turn off
**Reopen tabs**, or pick which tool window and whether the agent chat open first.

## Zoom

Make everything larger or smaller, for a projector or a small screen:

| Action | Shortcut |
| --- | --- |
| Zoom in | <kbd>⌘</kbd> <kbd>=</kbd> or <kbd>⌘</kbd> <kbd>+</kbd> |
| Zoom out | <kbd>⌘</kbd> <kbd>-</kbd> |
| Reset | <kbd>⌘</kbd> <kbd>0</kbd> |

Pinching on a trackpad zooms too. Levels go from 50% to 300%.

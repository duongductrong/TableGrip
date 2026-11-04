---
title: Themes & appearance
description: Pick a built-in theme, follow your OS's light and dark mode, tweak colours, or write a theme of your own.
---

## Light, dark, or both

In [Settings → Appearance](/docs/settings#appearance), **Mode** decides when the app is light or dark:

- **Sync with OS** (default) switches with your system's appearance.
- **Light** or **Dark** keeps one.

You pick a **dark theme** and a **light theme** separately, so syncing with the OS can, for example, move between
One Light by day and Dracula by night.

## Built-in themes

| Family | Dark | Light |
| --- | --- | --- |
| **Graphite** (default) | Graphite Dark | Graphite Light |
| **VS Code Modern** | VS Code Dark Modern | VS Code Light Modern |
| **Dracula** | Dracula | Alucard |
| **One** | One Dark | One Light |
| **Catppuccin** | Catppuccin Frappé | Catppuccin Latte |
| **Contrast** | Contrast Dark | Contrast Light |

## Small tweaks without a theme

- **Accent colour** replaces the theme's accent (selection, focus, primary buttons).
- **Font size** and **Zoom** make the interface larger or smaller. Zoom also has shortcuts:
  <kbd>⌘</kbd> <kbd>=</kbd>, <kbd>⌘</kbd> <kbd>-</kbd> and <kbd>⌘</kbd> <kbd>0</kbd>, or pinch on a trackpad.
- **Panel corners** go from Square to Very rounded.
- **Color Scheme** overrides the colour of each kind of SQL token, separately for dark and light themes, and the
  [environment](/docs/safety#environments) badge colours.
- The SQL editor's **font**, **size** and **line height** are under [Settings → SQL Editor](/docs/settings#sql-editor).

## Write your own theme

Themes are JSON files in `~/.tablegrip/themes/`. **Open themes folder** in Settings → Appearance takes you there. The
folder is watched, so saving a file updates the app while it runs.

The easiest start is **Customize current theme**: it copies the current built-in family into the folder for you to
edit. A theme in the folder with the same name as a built-in one replaces it.

A theme file holds one or more themes, typically a dark and a light one:

```json
{
  "$schema": "https://tablegrip.dev/schema/theme-v1.json",
  "name": "My Theme",
  "author": "me",
  "themes": [
    {
      "name": "My Dark",
      "appearance": "dark",
      "colors": {
        "accent": "#E5C07B",
        "island.background": "#1E1F22"
      },
      "syntax": {
        "keyword": "#CF8E6D",
        "comment": { "color": "#7A7E85", "fontStyle": "italic" }
      },
      "chart": ["#548AF7", "#E5A33E"]
    }
  ]
}
```

### Rules

- **Every key is optional.** Anything you leave out comes from the base theme; unknown keys are ignored.
- **Colours** are `#RRGGBB` or `#RRGGBBAA`.
- **`appearance`** is `dark` or `light`, and decides which theme list the theme appears in.
- A **`syntax`** entry is a colour, or an object with `color`, `fontStyle` (`normal` or `italic`) and `fontWeight`
  (`normal` or `bold`).
- **`chart`** is the list of colours used for chart series, in order.
- A file that can't be read is listed in Settings with its error, and skipped.

### Colour keys

| Key | Colours |
| --- | --- |
| `frame.background` | The window behind the panels |
| `island.background`, `island.border` | The panels ("islands") and their border |
| `divider` | Lines between areas |
| `accent`, `accent.foreground` | The accent, and text drawn on it |
| `editor.currentLine`, `editor.findMatch`, `editor.findMatch.current`, `editor.bracket`, `editor.bracket.border` | The SQL editor |
| `grid.modified`, `grid.inserted`, `grid.deleted` | Pending changes in the data grid |
| `chart.*`, `diagram.*` | Charts and ER diagrams |

### Syntax keys

`keyword`, `string`, `number`, `comment`, `quotedIdentifier`, `parameter`.

> [!TIP]
> Open a built-in theme with **Customize current theme** to see every key it sets. It's the most complete reference
> for what you can change.

---
title: JSON & cell values
description: Read and edit long text, JSON, binary data, images and timestamps in the value panel and the JSON tree editor.
---

## The value panel

Open the value panel from the grid toolbar (on the right). It shows the full value of the focused cell and follows the
selection as you move:

| Value | Shown as |
| --- | --- |
| **JSON** | A collapsible tree |
| **Binary** | A hex dump |
| **Images** (PNG, JPEG, GIF, BMP up to 16 KB) | The picture |
| **Dates and timestamps** | The value, plus the same moment in UTC, your local time zone and Unix time |

In the grid itself, binary values are shown as `0x…` hex (the first 16 KB).

## Editing JSON as a tree

JSON cells open as an editable tree instead of a wall of text:

- **Change a value:** click it, type, then <kbd>↩</kbd> to confirm or <kbd>Esc</kbd> to drop the change.
- **Flip a boolean:** click it.
- **Rename a key:** double-click it.
- **Change a type, add or remove:** use the row's type menu, the **+** button and the trash button.
- **Undo / redo** tree changes: <kbd>⌘</kbd> <kbd>Z</kbd> / <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>Z</kbd>.
- **Apply:** <kbd>⌘</kbd> <kbd>↩</kbd>. The change then waits with your other [pending edits](/docs/data-grid#editing-data).
- **Raw text:** switch to **Text** to edit the JSON source directly.

### It won't break your JSON

The tree writes the value back only when something actually changed. It keeps the key order and every number's exact
digits, and it refuses a number that isn't valid JSON, so it can't save broken JSON.

Values it couldn't edit without losing something (invalid JSON, a bare scalar, or an object with a repeated key) open as
plain text instead.

### JSON stored in text columns

SQLite, MariaDB and SQL Server often keep JSON in a text column. When such a cell holds a JSON object or array, it opens
in the same tree, but stays text:

- The value is saved exactly as you edited it, and is never refused for not being JSON.
- If you change something and then change it back, the original text is restored byte for byte.

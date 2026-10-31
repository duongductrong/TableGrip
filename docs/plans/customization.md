# Customization — proposal

> **Status (2026-10-31): all 4 phases implemented.** Decisions made: `Settings` keeps a flat structure (no migration
> needed); the keymap has Datagrok / DataGrip / VS Code presets. Items **not done**, because they touch the core of the
> editor or grid: word wrap (the editor relies on not soft-wrapping), leading comma when formatting, UI density (island
> spacing), and turning off the prompt when closing an unsaved tab (to avoid data loss). Drag-and-drop for tool stripe
> buttons is also not done; instead there are up/down buttons in Settings and a "Hide" right-click menu item.

Goal: let users tailor the app to their personal habits. **All defaults stay exactly as they are today**: an old
`settings.json` (or none at all) must produce the same look and behavior as now.

## Current state

`Settings` (core) has 18 fields, and the Settings UI has 7 pages. The only real personalization is **Theme**
(System/Light/Dark) and **Editor font size**. Everything else is hard-coded:

| Area | Hard-coded value | File |
| --- | --- | --- |
| Shortcuts | ~60 keys in `object Shortcuts` | `ui/Shortcuts.kt` |
| Accent, frame, island colors | `DarkIslands` / `LightIslands` | `ui/DatagrokTheme.kt` |
| SQL syntax colors | 2 fixed palettes | `console/SqlEditor.kt:603` |
| Environment colors (DEV/STG/PROD) | 4 fixed colors | `ui/DatagrokTheme.kt:150` |
| Indent | 4 spaces (editor + formatter) | `SqlEditor.kt:342`, `SqlFormatter.kt:8` |
| Keyword case when formatting | always UPPERCASE | `SqlFormatter.kt:46` |
| Auto-pair brackets/quotes | always on | `SqlEditor.kt:128` |
| Editor / UI font | Jewel default font | `DatagrokTheme.kt` |
| Grid row height | 26dp | `grid/GridGeometry.kt:7` |
| NULL display | `<null>` | `grid/DataGrid.kt:464` |
| Date/time format | `yyyy-MM-dd HH:mm:ss.SSS` | `grid/ValueFormats.kt:211` |
| Window size, split ratios | 1360×860, 0.24 / 0.68, not remembered | `Main.kt:86`, `MainScreen.kt` |
| Tool window open at startup | always Explorer | `AppState.kt:65` |

## Principles

1. **Default = current.** Each new field has a default equal to the value that is hard-coded today; `ignoreUnknownKeys`
   is already enabled, so old and new files can be read in both directions.
2. **Declare once.** Keep the `SettingsPages` model (declare a field → the dialog renders it automatically). Add the
   missing field types: `BooleanField` (checkbox), `ColorField` (color swatch + hex), `FontField` (system font combo).
3. **Resettable.** Each page has "Restore defaults"; a field that differs from its default shows a "modified" dot, as in
   JetBrains.
4. **Core stays UI-free.** Colors are stored as hex strings, keys as strings (`"meta+shift+E"`), and the app parses
   them.

## Proposal by phase

### Phase 1 — Editor & Grid (low risk, highest value)

**Settings › SQL Editor**

| Setting | Default | Options |
| --- | --- | --- |
| Font family | current font | combo of monospace fonts on the machine (JetBrains Mono, Fira Code, Menlo…) |
| Font size | 13 | (already exists) |
| Line height | 1.0× | 1.0–2.0 |
| Tab size | 4 | 2 / 4 / 8 |
| Indent using | Spaces | Spaces / Tab |
| Auto-close brackets & quotes | on | on / off |
| Auto-popup completion while typing | on | on / only on ⌃Space |
| Highlight current line | on | on / off |
| Word wrap | off | on / off |

**Settings › SQL Formatter** (new page)

| Setting | Default | Options |
| --- | --- | --- |
| Keyword case | UPPER | UPPER / lower / keep as is |
| Indent | follows the editor's Tab size | |
| Comma | trailing | trailing / leading |

**Settings › Data Editor**

| Setting | Default | Options |
| --- | --- | --- |
| Row density | Comfortable (26dp) | Compact (22) / Comfortable (26) / Spacious (32) |
| NULL display | `<null>` | any text (`NULL`, `∅`, empty…) |
| Date/time format | `yyyy-MM-dd HH:mm:ss.SSS` | ISO preset / without milliseconds / custom pattern (with preview) |
| Display time zone | as today | UTC / Local / per server |
| Numbers: thousands separator | off | on / off |
| Alternating row color (zebra) | off | on / off |
| Page size | 200 | (already exists) |

### Phase 2 — Appearance

**Settings › Appearance**

| Setting | Default | Options |
| --- | --- | --- |
| Theme | Sync with OS | (already exists) |
| Accent color | `#3574F0` | 8 presets + custom color |
| UI font size | Jewel default | 11–16 |
| UI density | Default | Compact / Default (island and stripe spacing) |
| Island corner radius | 10dp | 0 / 6 / 10 |

**Settings › Color Scheme** (new page)

- Separate SQL syntax palettes for Light and Dark: keyword, string, number, comment, quoted identifier, parameter.
- A few built-in schemes for quick selection: **Datagrok (default)**, Darcula, GitHub, Solarized, Monokai.
- Environment colors: LOCAL / DEV / STG / PROD (some teams use a different color for PROD).

### Phase 3 — Shortcuts

**Settings › Keymap** (new page)

- A table of all actions (taken from `Shortcuts.groups`, which already has descriptions), searchable by name or by key.
- Click a row → press a new key combination to assign it; report a **conflict** when the key is already used in the same
  context.
- Presets: **Datagrok (default)**, JetBrains/DataGrip, VS Code, TablePlus.
- Store only the user's overrides (`keymapOverrides: Map<actionId, String>`), so later changes to the defaults still
  reach users who haven't customized anything.
- Technical: change `Shortcuts` from an `object` of `val`s to a registry looked up by id, read through a
  `CompositionLocal`; tooltips and the Shortcuts dialog update automatically because they share the same source.

### Phase 4 — Layout & startup

**Settings › Behavior** (new page)

| Setting | Default | Options |
| --- | --- | --- |
| Remember window size/position | on (new) | on / off |
| Remember sidebar and Agent panel width | on (new) | on / off |
| Tool window on app start | Connections | Connections / History / Saved Queries / hidden |
| Agent panel on app start | closed | open / closed / as last time |
| Restore tabs from previous session | on | (currently always on) on / off |
| Confirm when closing an unsaved console tab | as today | on / off |
| Status bar | breadcrumbs + statistics | toggle each part on/off |

Same phase: **show/hide and reorder buttons on the tool stripe** (right-click → Hide; drag to reorder).

### Out of scope (proposed for later)

- Import/Export settings to a file (share configuration within a team) — easy once the schema is stable.
- Per-connection settings (own page size, timeout) — `ConnectionConfig` already has some (`confirmWrites`, AI access).
- External plugins/themes as `.json` files.

## Common technical changes

- `core/settings/Settings.kt`: add new fields and group them into sub-objects (`editor`, `grid`, `appearance`, `keymap`,
  `layout`) **with migration** from the old flat fields (`editorFontSize`, `pageSize`…) so old files are still read
  correctly. Or keep it flat for simplicity — to be decided.
- `settings/SettingsForm.kt`: add `BooleanField`, `ColorField`, `FontField`, "Restore defaults", and the modified dot.
- `ui/DatagrokTheme.kt`: `IslandsPalette`, syntax colors, and fonts come from settings instead of constants.
- Tests: each setting has a "default = old value" test and a JSON round-trip test; tests for parsing/formatting key
  strings.

## Decisions needed from you

1. Phase order: 1 → 2 → 3 → 4 as above, or prioritize Keymap earlier?
2. Keep `Settings` flat, or split into groups (with migration)?
3. Do we need keymap presets for DataGrip / VS Code / TablePlus already in phase 3, or only allow manual assignment at
   first?
4. Which small items should be dropped / added (for example zebra rows, leading comma, island corner radius)?

# Colour themes

TableGrip's look comes from a **colour theme**: a JSON file in a format modelled on Zed's theme files. The app ships six
families, each with a dark and a light variant. Users add their own by dropping files into `~/.tablegrip/themes/`.
Settings pick one theme for dark mode and one for light mode.

| Family | Dark | Light |
|---|---|---|
| Graphite (default, Int UI Islands) | Graphite Dark | Graphite Light |
| VS Code Modern | VS Code Dark Modern | VS Code Light Modern |
| Dracula | Dracula | Alucard |
| One | One Dark | One Light |
| Catppuccin | Catppuccin Frappé | Catppuccin Latte |
| Contrast | Contrast Dark | Contrast Light |

The built-ins live in `app/src/main/resources/themes/` and use the same format users do. Graphite leaves out the text
and control keys, so Jewel's Int UI colours (and today's look) stay exactly as they were.

## Settings

```jsonc
// ~/.tablegrip/settings.json (comments and trailing commas are fine)
{
  "theme": { "mode": "System", "dark": "One Dark", "light": "One Light" },
  // Keys laid over a theme by its name, like Zed's theme_overrides / VS Code's colorCustomizations
  "themeOverrides": {
    "One Dark": { "colors": { "accent": "#E5C07B" }, "syntax": { "keyword": "#FF79C6" } }
  },
  "accentColor": "#D95C9A" // over every theme
}
```

- `mode` is `System`, `Light` or `Dark`. Older files with `"theme": "Dark"` still load.
- An unknown theme name falls back to Graphite for that mode.
- Only settings that differ from the defaults are written.
- The file is checked once a second, and hand edits apply straight away.
- If the file doesn't parse, the app keeps its current settings, shows the error in Settings and **doesn't overwrite
  the file** until it is fixed.
- Settings → Appearance has *Open settings.json*, *Open themes folder* and *Customize current theme*. The last one
  copies the theme's family file into the themes folder. A user theme with a built-in theme's name replaces the
  built-in.

## Theme file

```jsonc
{
  "$schema": "https://tablegrip.dev/schema/theme-v1.json",
  "name": "Mine",
  "author": "me",
  "themes": [
    {
      "name": "Mine Dark",
      "appearance": "dark",
      "colors": { "island.background": "#102030", "accent": "#FFCC00" },
      "syntax": { "keyword": "#C678DD", "comment": { "color": "#5C6370", "fontStyle": "italic" } },
      "chart": ["#61AFEF", "#E5C07B", "#98C379"]
    }
  ]
}
```

Colours are `#RRGGBB` or `#RRGGBBAA` (alpha last, as in VS Code and Zed). Every key is optional. Unknown keys are
ignored, so files can carry keys from later versions. Missing keys are filled in three ways:

- **From the theme's own core colours.** A selection tint comes from the accent; diagram boxes and chart grids come
  from the island and divider.
- **From Graphite's variant of the same appearance.** This covers neutral details such as find-match highlights.
- **Text and control keys** keep Jewel's Int UI colours until the theme sets at least one control key.

| Group | Keys |
|---|---|
| Window | `frame.background`, `island.background`, `island.border`, `divider`, `accent`, `accent.foreground`, `toolbar.hover.background`, `chip.hover.background`, `scrim` |
| Text | `text`, `text.muted`, `text.disabled`, `text.error`, `text.warning` |
| Controls | `border`, `border.focused`, `text.link`, `input.background`, `input.border`, `popup.background`, `popup.border`, `list.selection.background`, `list.selection.inactive.background`, `list.hover.background`, `button.background`, `button.foreground`, `button.hover.background`, `button.secondary.border` |
| SQL editor | `editor.background`, `editor.foreground`, `editor.currentLine`, `editor.findMatch`, `editor.findMatch.current`, `editor.bracket`, `editor.bracket.border` |
| Data grid | `grid.modified`, `grid.inserted`, `grid.deleted` |
| Charts | `chart.background`, `chart.text`, `chart.text.muted`, `chart.grid`, plus the `chart` series list |
| ER diagram | `diagram.background`, `diagram.grid`, `diagram.box`, `diagram.header`, `diagram.header.text`, `diagram.border`, `diagram.text`, `diagram.text.muted`, `diagram.line`, `diagram.primaryKey`, `diagram.foreignKey`, `diagram.match` |
| Syntax | `keyword`, `string`, `number`, `comment`, `quotedIdentifier`, `parameter`. Each is a colour or `{ color, fontStyle, fontWeight }` |

The resolver is `resolveTheme` in `app/.../ui/theme/ColorTheme.kt`. Jewel's controls are restyled in
`ThemedControls.kt`.

## Importing VS Code and Zed themes (later)

An importer only has to produce a `ThemeFamily`: read the other editor's file, map its keys, and write the result into
`~/.tablegrip/themes/`. Keys it can't map are simply left out and fall back as described above. Proposed mapping:

| TableGrip | VS Code `colors` | Zed `style` |
|---|---|---|
| `frame.background` | `titleBar.activeBackground` / `sideBar.background` | `background` / `title_bar.background` |
| `island.background` | `editor.background` | `editor.background` / `panel.background` |
| `divider` | `panel.border` / `editorGroup.border` | `border.variant` |
| `accent` | `focusBorder` / `button.background` | `text.accent` / `border.focused` |
| `text`, `text.muted` | `foreground`, `descriptionForeground` | `text`, `text.muted` |
| `input.background`, `input.border` | `input.background`, `input.border` | `element.background`, `border` |
| `popup.background`, `popup.border` | `menu.background` / `editorWidget.background`, `menu.border` | `elevated_surface.background`, `border` |
| `list.selection.background` | `list.activeSelectionBackground` | `element.selected` |
| `list.hover.background` | `list.hoverBackground` | `element.hover` |
| `button.background`, `button.foreground` | `button.background`, `button.foreground` | — |
| `editor.currentLine` | `editor.lineHighlightBackground` | `editor.active_line.background` |
| `editor.findMatch(.current)` | `editor.findMatchHighlightBackground`, `editor.findMatchBackground` | `search.match_background` |
| `syntax.keyword` | `tokenColors` scope `keyword` | `syntax.keyword` |
| `syntax.string` | `string` | `syntax.string` |
| `syntax.number` | `constant.numeric` | `syntax.number` |
| `syntax.comment` | `comment` | `syntax.comment` |
| `syntax.quotedIdentifier` | `variable` / `support.type.property-name` | `syntax.property` |
| `syntax.parameter` | `variable.parameter` | `syntax.variable.special` |
| `chart` | `charts.blue`, `charts.yellow`, … | `players[].cursor` |

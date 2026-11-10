# Jewel usage inventory (TableGrip app)

Input for the Rust UI kit ([../rust-ui-kit.md](../rust-ui-kit.md)). It records how `app/src/main` uses each Jewel component
and TableGrip's own shell pieces, so the Rust kit can match behavior. Only TableGrip's own code was read; Jewel's sources
were not.

Conventions:
- Paths are relative to `app/src/main/kotlin/dev/tablegrip/app/` and tests to `app/src/test/kotlin/dev/tablegrip/app/`.
- "Calls" counts call expressions in `app/src/main` (comments and definitions excluded). Counts come from a regex scan,
  so treat them as ±2.
- "Via wrapper" means more call sites reach the component through an app helper that is counted once.

## Cross-cutting facts

| Topic | Finding |
|---|---|
| Theme entry | `ui/TableGripTheme.kt:107` builds Int UI dark/light with `GlobalColors` from the `ColorTheme` (`:146`), a `DividerStyle(islands.divider)` (`:137`), a title bar style in frame colour (`:119`), then `ThemedControls` (`:140`). |
| Style overrides | `ui/theme/ThemedControls.kt:67` replaces colours (keeping Int UI metrics and icons) for default/outlined button, text field, text area, combo box, dropdown, menu, popup container, tooltip, lazy tree, simple list item, link, default tab, and editor tab. With no `controls` (Graphite) it does nothing: every Int UI default applies. |
| Text field API | Every `TextField` and `TextArea` uses `TextFieldState` (state-based), except `grid/ValuePanel.kt:135` and `:139`, which pass a `TextFieldState` positionally too. No `value`/`onValueChange` usage. |
| Keyboard | 102 `onPreviewKeyEvent` and 13 `onKeyEvent` handlers in 22 files; 35 `focusRequester`, 50 `requestFocus()`, 11 `onFocusChanged`, 2 `focusProperties`. Most keyboard behavior lives in the app, attached around Jewel controls. |
| Tooltip + control | The dominant idiom is `Tooltip({ Text(label) }) { IconButton(...) { Icon(key, label) } }`, wrapped in about 8 private helpers (see IconButton). |
| Hand-rolled lists | No Jewel `SimpleListItem` call. 8 popups/lists draw their own rows with `JewelTheme.treeStyle.colors.backgroundSelectedActive` as the highlight (see Simple list items). |
| Zoom | All content is wrapped in `Zoomed` (`ui/UiZoom.kt:62`), which scales `LocalDensity`. Jewel metrics are therefore always seen at 50–300 %. |

## Foundation

### Text
| Item | Detail |
|---|---|
| Calls | ~1009 in 61 files. Top: `keyvalue/KeyBrowserView.kt` (84), `table/FilterBuilderView.kt` (54), `agent/AgentPanel.kt` (48), `connections/ConnectionDialog.kt` (46), `schema/TableDesignerView.kt` (36), `console/ConsoleView.kt` (36) |
| Params used | `color` 457 (of which `globalColors.text.info` 227, `.text.error` 33, `.text.warning` 9), `maxLines` 159, `fontSize` 113, `overflow` 95 (Ellipsis), `fontWeight` 85, `style` 54 (`editorTextStyle` 40), `softWrap` 20, `textAlign` 12, `textDecoration` 5 |
| States | none (plain text); selection only inside `SelectionContainer` (agent chat, `agent/AgentPanel.kt:219`), with `DisableSelection` around 4 tooltips (`agent/AgentPanel.kt:409`) |
| Wrappers | `table/FilterBuilderView.kt` `Tag()` (coloured 10 sp label), `ui/TitleBarView.kt:120` `EnvironmentChip`, `ui/TitleBarView.kt:109` `Badge` |
| Behavior to replicate | Default text style comes from `Settings.uiFontSize` (`ui/TableGripTheme.kt:112`); editor style from `Settings.editorFontFamily` (`:114`) |
| Tests | Every snapshot test: `ui/MainScreenSnapshotTest.kt`, `ui/theme/ThemeGallerySnapshotTest.kt`, `activity/ActivitySnapshotTest.kt`, `schema/SchemaToolsSnapshotTest.kt`, `grid/GridSnapshotTest.kt`; text lookups (`onNodeWithText`) in almost every UI test |

### Icon / AllIconsKeys
| Item | Detail |
|---|---|
| Calls | `Icon(` 168 in 45 files; 342 `AllIconsKeys` references in total (also via `iconKey =` in menus, `IconKey` params) |
| Params used | `contentDescription = null` 97; `Modifier.size(...)` 49 (12, 14, 16 dp); `tint` 7 (white on accent in `ui/ToolStripe.kt:144`, `text.info` in `ui/StatusBar.kt:249`) |
| Custom icons | No painter/resource icons beside the app icon; `ui/UiZoom.kt:129` draws a magnifier on `Canvas` because Int UI's zoom icons don't fit |
| Tests | Snapshot tests; `documents/DocumentBrowserUiTest.kt:143` and `keyvalue/KeyBrowserUiTest.kt` find icon buttons by content description |

80 distinct keys (count of references):

| Group | Keys |
|---|---|
| General | ChevronDown 20, Add 17, Warning 15, Error 9, ChevronRight 8, Filter 7, Delete 7, Information 5, Settings 4, CloseSmall 4, ArrowUp 4, ArrowRight 4, ArrowDown 4, Remove 3, InspectionsOK 3, Keyboard 2, ChevronUp 2, ChevronLeft 1, ZoomIn 1, ZoomOut 1, Reset 1, Pin 1, PinSelected 1, InlineAdd 1, HideToolWindow 1, FitContent 1, ExpandComponent 1, CollapseComponent 1, ActualZoom 1 |
| Actions | Search 15, Refresh 14, Copy 12, Rollback 8, Edit 8, Close 5, Commit 4, Suspend 3, Preview 3, Play_forward 3, Play_first 3, Play_back 3, MenuSaveall 3, Execute 3, Download 3, Collapseall 3, PreviewDetails 2, MenuOpen 2, Expandall 2, Undo 1, Redo 1, ShowCode 1, ShowAsTree 1, Show 1, RunAll 1, ReformatCode 1, PreviousOccurence 1, NextOccurence 1, Play_last 1, PinTab 1, ListFiles 1, Lightning 1, Checked 1, CheckOut 1 |
| Nodes | DataColumn 11, DataTables 10, Console 8, Folder 7, DataSchema 4, FieldPK 3, Padlock 1, Favorite 1, Bookmark 1 |
| Other | Toolwindows.ToolWindowAskAI 6, Vcs.History 5, FileTypes.Text 4, ToolbarDecorator.Import 3, FileTypes.Diagram 1, Diff.ArrowLeftRight 1, Graph.Layout 1, ObjectBrowser.Sorted 1 |

### Divider
| Item | Detail |
|---|---|
| Calls | 64 in 30 files, plus 16 via `Separator()` (`table/TableDataView.kt:318`) |
| Variants | `Orientation.Horizontal` 57 (almost always `Modifier.fillMaxWidth()`); `Orientation.Vertical` 7: full height (`settings/SettingsScreen.kt:71`, `activity/ActivityView.kt:89`, `table/FilterBuilderView.kt:242`, `documents/QueryBuilderView.kt:170`, `console/ChartView.kt:90`) or 18 dp toolbar separator (`table/TableDataView.kt:319`, `console/ConsoleView.kt:161`); short 20 dp rule in `ui/ToolStripe.kt:74` |
| Style | Colour comes from `islands.divider` via `LocalDividerStyle` (`ui/TableGripTheme.kt:137`); no per-call colour or thickness |
| Not Jewel | `ui/StatusBar.kt:396` `StatusDivider` is a 1×12 dp `Box` in `borders.normal` |
| Tests | Snapshot tests only |

### Link
| Item | Detail |
|---|---|
| Calls | 98 in 25 files. Top: `settings/DriversPanel.kt` (16), `tools/ToolsPanel.kt` (9), `backup/BackupDialog.kt` (9), `table/FilterBuilderView.kt` (7), `keyvalue/KeyBrowserView.kt` (5) |
| Params used | Always `Link(text, onClick = ...)`; `enabled = false` in 5 places (`schema/TableDesignerView.kt:458`, `keyvalue/KeyBrowserView.kt:209`, `backup/BackupsPanel.kt:99`, `console/FixView.kt:50`, `console/ChartView.kt:107`). Wrapped in `Tooltip` at `table/FilterBuilderView.kt:832`. No external-link icon, no custom style |
| States | enabled, disabled, hover/pressed (themed colours in `ui/theme/ThemedControls.kt:300`; same `c.link` for all states, so visited = normal) |
| Tests | `table/FilterBuilderUiTest.kt` ("Edit as SQL"), `settings/SettingsScreenTest.kt:136` ("Reset to defaults"), `console/ConsoleChartTest.kt` ("Aggregate on server"), `console/ConsoleFixTest.kt` ("Fix with AI") |

### Island (TableGrip)
| Item | Detail |
|---|---|
| Definition | `ui/TableGripTheme.kt:178`: `Box` clipped to `RoundedCornerShape(islands.cornerRadius)`, background `islands.island`, 1 dp border `islands.islandBorder` |
| Calls | 7 in 4 files: `ui/MainScreen.kt:65`, `:113`, `:117`, `:124`; `ui/AppDialog.kt:76`; `search/GoToAnything.kt:236`; `files/DataFileWindowView.kt:101` |
| Params used | Default radius = `Settings.islandCornerRadius` clamped to 0–24 dp (`ui/TableGripTheme.kt:110`); explicit `shape` in dialogs (concentric radius `14 dp − inset`, min 4 dp, `ui/AppDialog.kt:76`) and go-to-anything (`PanelCornerRadius − PanelInset`) |
| Layout constants | `IslandGap = 6.dp` (`ui/MainScreen.kt:44`), `DialogIslandGap = 4.dp` (`ui/AppDialog.kt:105`) |
| Tests | `ui/MainScreenSnapshotTest.kt`, `ui/theme/ThemeGallerySnapshotTest.kt` |

## Basic controls

### DefaultButton / OutlinedButton
| Item | Detail |
|---|---|
| Calls | DefaultButton 59 in 31 files; OutlinedButton 99 in 32 files. Top: `grid/ValuePanel.kt` (10 outlined), `connections/ConnectionDialog.kt` (8+4), `keyvalue/KeyBrowserView.kt` (6+8), `queries/QueriesPanel.kt` (5+4), `schema/SchemaCompareView.kt` (5+3) |
| Params used | `onClick` + `enabled` (46 calls have `enabled`); `Modifier` only twice (`workspace/WorkspaceView.kt:222` padding). No custom `style` |
| Content | Mostly `Text(label)`. Icon + text row: `keyvalue/KeyBrowserView.kt:133` (Add + "New key"), `schema/ErDiagramView.kt:151` ("Export" + ChevronDown, opens a PopupMenu). Coloured text: `agent/AgentPanel.kt:165` (`DangerColor`) |
| Patterns | Dialog footer = `OutlinedButton("Cancel")` then `DefaultButton(primary)` (e.g. `queries/QueriesPanel.kt:180`, `tools/ToolsPanel.kt:190`, `activity/ActivityView.kt:356`). Wrapped in `Tooltip` at `keyvalue/KeyBrowserView.kt:802` and via `SignalButton` (`activity/ActivityView.kt:309`) |
| States | enabled, disabled, hovered, pressed, focused (themed in `ui/theme/ThemedControls.kt:128` / `:151`) |
| App behavior | Enter does not trigger a "default" button. Dialogs add `onPreviewKeyEvent` on their content `Column` that calls `submit()` on Enter/NumPadEnter (`queries/QueriesPanel.kt:155`, `:200`; `connections/ConnectionDialog.kt:819`) |
| Tests | `conformance/ConformanceTest.kt` + `ui-kit/conformance/scenarios/button.json`; `update/UpdatesPanelTest.kt:47`; `connections/ImportConnectionsTest.kt:42` ("Import 1 Connection"); `grid/RecordEditorUiTest.kt` ("Apply"); `keyvalue/KeyBrowserUiTest.kt` ("New key"); `console/ConsoleFixTest.kt` ("Apply & Run") |

### IconButton
| Item | Detail |
|---|---|
| Calls | 54 direct in 18 files, plus 50 via `ToolButton` (`table/TableDataView.kt:299`) and 9 via `HeaderButton` (`ui/TitleBarView.kt:132`) |
| Params used | `onClick`, `enabled` (run/cancel/commit/move up/down), `Modifier.size(24/22/32 dp)` (`schema/TableDesignerView.kt:517`, `:555`; `ui/TitleBarView.kt:134`), `Modifier.width(20.dp)` (`table/FilterBuilderView.kt:1255`), `clientRegion(...)` in the title bar |
| Content | `Icon(key, desc)`, sometimes `Modifier.size(14.dp)`; one text toggle "Aa" whose colour shows on/off (`console/SqlEditor.kt:511`) |
| Wrappers | `ToolButton` (Tooltip+IconButton+Icon, `table/TableDataView.kt:299`), `HeaderButton` (`ui/TitleBarView.kt:132`), `RemoveButton` (`table/FilterBuilderView.kt:802`), `SmallIcon` (`schema/TableDesignerView.kt:516`), `EditorIconButton` (`grid/DataGrid.kt:630`), `TreeIconButton` (`grid/JsonTreeEditor.kt:441`), `RowAction` (`agent/ChatHistoryList.kt:233`), `MessageAction` (`agent/AgentPanel.kt:408`) |
| Not IconButton | Tool stripe buttons (`ui/ToolStripe.kt:125`) and status widgets (`ui/StatusBar.kt:375`) are `Box`/`Row` + `clickable` + `hoverable` with their own hover/selected colours |
| States | enabled, disabled, hovered, pressed; no toggled/selected IconButton |
| Tests | `documents/DocumentBrowserUiTest.kt:129` ("Next page"), `keyvalue/KeyBrowserUiTest.kt` ("Delete key"); `settings/SettingsScreenTest.kt:65` clicks the stripe's "Settings" (custom, not IconButton) |

### Tooltip
| Item | Detail |
|---|---|
| Calls | 116 in 31 files. Top: `table/FilterBuilderView.kt` (19), `keyvalue/KeyBrowserView.kt` (16), `table/SelectBuilderView.kt` (12), `console/ConsoleView.kt` (9), `agent/AgentPanel.kt` (8) |
| Params used | `Tooltip(tooltip = { Text(...) }) { target }`; `Modifier.weight(...)` twice (`ui/StatusBar.kt:76`, `table/FilterBuilderView.kt:821`). No placement or style per call |
| Content | Plain `Text` (82 with a literal); shortcut label in the text, e.g. `"Undo (${Shortcuts.Undo})"`; multi-line rich content in `ui/StatusBar.kt:310` (`ConnectionTooltip`); width-capped `Text(Modifier.widthIn(max = 420.dp))` (`explorer/ExplorerPanel.kt:509`); editor-font text (`table/FilterBuilderView.kt:821`) |
| Targets | IconButton (most), Text with ellipsis (show full text), Checkbox (`schema/TableDesignerView.kt:511`), Link, OutlinedButton, warning Icon (`schema/SchemaCompareView.kt:249`), custom chips |
| Tests | None assert tooltips |

### CircularProgressIndicator (+Big)
| Item | Detail |
|---|---|
| Calls | 19 in 12 files; Big 2 (`schema/ImportDialog.kt:306`, `connections/ConnectionProgress.kt:80`) |
| Params used | Default size, or `Modifier.size(12.dp)` / `size(10.dp)` inline in status bar and toolbars (`ui/StatusBar.kt:148`, `:285`; `agent/ModelPicker.kt:170`; `keyvalue/KeyBrowserView.kt:199`; `console/ChartView.kt:210`); centred overlay (`chart/ChartRenderer.kt:112`, `keyvalue/KeyBrowserView.kt:420`) |
| Tests | Rendered (animated) inside snapshot tests only |

### Checkbox / CheckboxRow / TriStateCheckbox
| Item | Detail |
|---|---|
| Calls | Checkbox 17 in 12 files (+4 via `CheckCell`, `schema/TableDesignerView.kt:509`); CheckboxRow 19 in 8 files; TriStateCheckbox 2 |
| Checkbox | `Checkbox(checked, onCheckedChange)` positional or named. Used as row "enabled" toggles in builders (`table/FilterBuilderView.kt:666`, `:729`, `:780`; `table/SelectBuilderView.kt:222`, `:266`; `documents/QueryBuilderView.kt:413`), table cells centred in fixed-width `Box` (`schema/ImportDialog.kt:245`, `schema/TableDesignerView.kt:355`), settings boolean (`settings/SettingsScreen.kt:186`). `enabled` only via `CheckCell` |
| CheckboxRow | `CheckboxRow(label, checked, onCheckedChange = ...)` in dialogs: `backup/BackupDialog.kt:173`–`:206` (7), `connections/ConnectionDialog.kt:540`, `:609`, `:610`, `:687`, `schema/ImportDialog.kt:191`, `export/ExportDialog.kt:117`. Conditional rendering, never `enabled = false` |
| TriStateCheckbox | `TriStateCheckbox(ToggleableState, onClick)` as group header: `schema/SchemaCompareView.kt:221` (On/Off/Indeterminate from selected children), `connections/ImportConnections.kt:255` |
| States | checked, unchecked, indeterminate (tri-state only), disabled (CheckCell), focused |
| Tests | `conformance/ConformanceTest.kt` + `ui-kit/conformance/scenarios/checkbox.json` (click, disabled, Space, Enter, focus, Tab); `connections/ImportConnectionsTest.kt:42` (rows and group); `table/FilterBuilderUiTest.kt:182` changes the row toggle by shortcut (⌘/) |

### RadioButtonRow
| Item | Detail |
|---|---|
| Calls | 7 in 3 files: `grid/ValuePanel.kt:195`–`:197` (True/False/NULL, NULL conditional), `schema/ImportDialog.kt:211`, `:213`, `export/ExportDialog.kt:120`, `:121` |
| Params used | `RadioButtonRow(label, selected = ..., onClick = ...)`; no `enabled`, no grouping helper (app keeps the exclusive state) |
| Tests | None direct (`grid/GridSnapshotTest.kt` renders ValuePanel) |

### Inline banners
| Item | Detail |
|---|---|
| Calls | Error 3, Warning 3, Success 5, Information 3; files `connections/DriverBanner.kt` (10), `connections/DataFileStatus.kt:56`, `:73`, `keyvalue/KeyBrowserView.kt:440`, `:441` |
| Params used | `(text, modifier, linkActions = { action("Label") { ... } })` with up to 3 link actions (comment at `connections/DriverBanner.kt:120`: "A banner shows at most three links"); `iconActions = { iconAction(AllIconsKeys.Actions.Close, "Dismiss", "Dismiss") { ... } }` for dismiss (`keyvalue/KeyBrowserView.kt:438`) |
| Wrappers | `DriverBanner` (`connections/DriverBanner.kt:28`), `ProfileJarsBanner` (`:184`), `DataFileBanner` (`connections/DataFileStatus.kt:44`), `MessageBanner` (`keyvalue/KeyBrowserView.kt:428`: success auto-hides after 4 s, error stays) |
| Not Jewel | `activity/ActivityView.kt:131` `PrivilegesBanner` and `documents/QueryBuilderView.kt:190` `Notice` are hand-made |
| Tests | `connections/ConnectionDialogTest.kt:84` (clicks the banner's "Download" link action) |

## Input

### TextField
| Item | Detail |
|---|---|
| Calls | 93 in 28 files. Top: `connections/ConnectionDialog.kt` (21), `keyvalue/KeyBrowserView.kt` (14), `queries/QueriesPanel.kt` (6), `schema/TableDesignerView.kt` (6), `settings/SettingsScreen.kt` (4), `table/FilterBuilderView.kt` (4) |
| Params used | `placeholder` 69; `fillMaxWidth` 41; `width(...)` 26 (100–240 dp); `weight` 25; `textStyle = editorTextStyle` 26; `focusRequester` 21; `onKeyboardAction` 18 (Enter submit); `outline` 16; `leadingIcon` 14 (Search icon, `Modifier.padding(end = 6.dp)`); `enabled` 5; `outputTransformation` 5 (`MaskPassword`, `connections/ConnectionDialog.kt:506`, `settings/ApiKeyPanel.kt:61`); `trailingIcon` 3; `readOnly` 1 (`keyvalue/CommandConsole.kt:210`) |
| Key handlers | `onPreviewKeyEvent` on the field's modifier in 18 calls: list navigation with ↑/↓/PgUp/PgDn, Enter/NumPadEnter accept (Shift variant), Esc close (`search/GoToAnything.kt:238`, `agent/ModelPicker.kt:152`, `agent/ChatHistoryList.kt:128`, `:246`); completion with Ctrl+Space, ↑/↓ wrapping, Enter/Tab accept, Esc dismiss (`table/FilterBuilderView.kt:1146`) |
| Composite wrappers | Field + `Popup(focusable = false)` suggestion list below it (`table/FilterBuilderView.kt:1096` `ValueField`); field + trailing chevron opening a `PopupMenu` of the field's width (`schema/TableDesignerView.kt:275` `TypeField`); `PickerButton` (`table/FilterBuilderView.kt:865`) draws a button with the text field's metrics and borders (error > focused > hovered > normal) |
| States | enabled, disabled, read-only, focused, hovered, error outline, placeholder, password-masked |
| Tests | `conformance/ConformanceTest.kt` + `ui-kit/conformance/scenarios/text-field.json`; `settings/SettingsScreenTest.kt:65` (typing, invalid → error message, valid → applied); `connections/ConnectionDialogTest.kt` (6 × `performTextInput`); `table/FilterBuilderUiTest.kt:84`, `:182`; `keyvalue/KeyBrowserUiTest.kt`; `agent/MentionSourcesTest.kt` |

### TextArea
| Item | Detail |
|---|---|
| Calls | 11 in 8 files: `activity/ActivityView.kt:286`, `:354`, `agent/AgentPanel.kt:161`, `connections/ConnectionDialog.kt:639`, `keyvalue/KeyBrowserView.kt:780`, `:1063`, `grid/ValuePanel.kt:139`, `table/TableDataView.kt:473`, `documents/DocumentBrowserView.kt`, `console/ConsoleView.kt` |
| Params used | `textStyle` 11 (editor style); `readOnly` 9 (SQL / query previews); `fillMaxWidth` 9; `weight` 7; `placeholder` 4; `height` / `heightIn` 1 each; `onPreviewKeyEvent` 1 (`keyvalue/KeyBrowserView.kt:780`, ⌘S save) |
| Tests | `keyvalue/KeyBrowserUiTest.kt` (value editing), `grid/GridSnapshotTest.kt` (ValuePanel) |

### Outline (error / warning)
| Item | Detail |
|---|---|
| Calls | 16, all `if (problem) Outline.Error else Outline.None` on TextField. `Outline.Warning` is never used |
| Files | `settings/SettingsScreen.kt:178`, `:184`, `:210`; `schema/TableDesignerView.kt:130`, `:251`, `:283`, `:347`, `:416`; `schema/TestDataDialog.kt:219`; `schema/ImportDialog.kt:247`; `keyvalue/KeyBrowserView.kt:958`, `:1048`; `table/SelectBuilderView.kt:278`, `:360`; `table/FilterBuilderView.kt:758`, `:786` |
| Manual outlines | `globalColors.outlines.error/focused` read directly for custom borders in `table/FilterBuilderView.kt` (PickerButton), `grid/CellEditors.kt`, `grid/DataGrid.kt`, `documents/QueryBuilderView.kt`, `explorer/ConnectionDrag.kt` (drop targets), `console/SqlEditor.kt` |
| Theme | `focusedWarning`, `focusedError`, `warning`, `error` stay Int UI's; only `focused` follows the theme (`ui/TableGripTheme.kt:153`) |
| Tests | `settings/SettingsScreenTest.kt:65` (error state snapshot `settings-screen-error.png`), `table/FilterBuilderUiTest.kt:84` ("“abc” isn't a number") |

## Popups and lists

### ListComboBox
| Item | Detail |
|---|---|
| Calls | 26 in 11 files. `connections/ConnectionDialog.kt` (9: `:449`, `:456`, `:479`, `:546`, `:566`, `:595`, `:615`, `:670`, `:678`), `backup/BackupDialog.kt:130`, `:141`, `:159`, `keyvalue/KeyBrowserView.kt:167`, `table/TableDataView.kt:256`, `console/ConsoleView.kt:162` |
| Params used | Always non-editable `ListComboBox(items: List<String>, selectedIndex, onSelectedItemChange = { index -> ... }, modifier = Modifier.width(110–320.dp))`; `fillMaxWidth` once (`console/ChartView.kt:247`); `enabled = false` twice (`connections/ConnectionDialog.kt:456` single driver, `console/ConsoleView.kt:162` while running/read-only) |
| Patterns | A first placeholder item meaning "nothing chosen" (`connections/ConnectionDialog.kt:546` "Choose a host…", `documents/QueryBuilderView.kt:415` "Field…"); `selectedIndex.coerceAtLeast(0)` everywhere; selection change has side effects (fills other fields) |
| Not used | `EditableListComboBox`, custom item content, keyboard handlers around it |
| Tests | `connections/ConnectionDialogTest.kt:142` (renders the selected label); otherwise snapshots |

### PopupMenu (+ separator, submenus)
| Item | Detail |
|---|---|
| Calls | PopupMenu 14 in 9 files, plus one `Dropdown` (`ui/TitleBarView.kt:72`) with `menuContent`. `selectableItem` 33, `separator()` 11, `passiveItem` 1 (`schema/TableDesignerView.kt:589`). Submenus: none |
| Params used | `PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End or Start)`; `Modifier.widthIn(min = ...)` (`schema/TableDesignerView.kt:297`, `:588`); `Modifier.focusProperties { canFocus = true }` with `InputMode` check (`table/FilterBuilderView.kt:1018`) |
| Items | `selectableItem(selected, enabled?, iconKey?, onClick) { Text(...) }`: `selected` marks the current choice (zoom level, transaction mode, connection); `iconKey` 11; `enabled = false` 4 (`ui/StatusBar.kt:234`, `:237`, `:297`; `ui/UiZoom.kt:118`). No keybinding hints in items |
| App behavior | Open state is an app `Boolean`; anchor is the surrounding `Box`. Keyboard: dismiss by keyboard calls `onDone()` to move focus on (`table/FilterBuilderView.kt:1021`); operator sections split by `separator()` |
| Files | `ui/StatusBar.kt:229`, `:291`; `ui/UiZoom.kt:110`; `explorer/ExplorerPanel.kt:570`; `agent/AgentPanel.kt:659`; `schema/ErDiagramView.kt:158`; `table/SelectBuilderView.kt:320`, `:345` |
| Context menus | Compose `ContextMenuArea` 11 sites (`explorer/ExplorerPanel.kt` ×3, `ui/ToolStripe.kt:109`, `console/ConsoleView.kt` result tabs, `grid/DataGrid.kt`, `activity/ActivityView.kt`, `queries/QueriesPanel.kt`, `schema/ErDiagramView.kt`), with `ContextMenuDivider` (`explorer/ExplorerPanel.kt:250`) and `ContextMenuDataProvider` (`console/ConsoleView.kt:85`); drawn in Jewel's menu style |
| Tests | `explorer/ExplorerContextMenuTest.kt` (context menus); `table/FilterBuilderUiTest.kt:182` (keyboard builder flow) |

### Simple list items
| Item | Detail |
|---|---|
| Calls | No Jewel `SimpleListItem` call; style overridden in `ui/theme/ThemedControls.kt:299` only |
| Hand-rolled rows | Highlight = `JewelTheme.treeStyle.colors.backgroundSelectedActive`: `search/GoToAnything.kt:311`, `agent/ModelPicker.kt:196`, `agent/ChatHistoryList.kt:210` (also on hover), `agent/MentionSuggestions.kt:100`, `completion/CompletionPopup.kt:71`, `table/FilterBuilderView.kt:986`, `:1203`, `grid/CellEditors.kt:263`; `grid/DataGrid.kt:314` reads tree colours for grid selection |
| Behavior | Highlighted index driven by keys in the owning TextField; list scrolls the highlight into view (`search/GoToAnything.kt:258`) |
| Tests | `agent/MentionSourcesTest.kt`, `console/SqlEditorTest.kt` (completion popup), `documents/DocumentBrowserUiTest.kt:225` (filter completion) |

### VerticalScrollbar / HorizontalScrollbar
| Item | Detail |
|---|---|
| Calls | Vertical 10 in 8 files, Horizontal 2 (`grid/DataGrid.kt:264`, `grid/ResultJsonView.kt:259`) |
| Params used | First argument is a `LazyListState` (`explorer/ExplorerPanel.kt:153`, `agent/AgentPanel.kt:225`, `grid/DataGrid.kt:260`, `grid/JsonTreeEditor.kt:192`) or `ScrollState` (`table/FilterBuilderView.kt:252`, `table/SelectBuilderView.kt:156`, horizontal grid). Always overlaid: `Modifier.align(TopEnd).fillMaxHeight().padding(...)` in a `Box`. `padding(top = HeaderHeight)` / `padding(start = rowNumberWidth)` on the grid |
| Custom style | `keyvalue/KeyBrowserView.kt:281` `visibleScrollbarStyle()`: `TrackClickBehavior.JumpToSpot`, `ScrollbarVisibility.AlwaysVisible` (8 dp track, 1 dp padding); used at `:269`, `:896`, `:1037` |
| App behavior | `grid/DataGrid.kt:122` `claimPointer()` consumes all pointer events on the grid's scrollbars so the grid doesn't select cells under them |
| Tests | `keyvalue/KeyBrowserUiTest.kt:151` (snapshot of always-visible bars); `grid/DataGridTest.kt` (grid scrolling) |

### VerticallyScrollableContainer
| Item | Detail |
|---|---|
| Calls | 3: `settings/SettingsScreen.kt:100`, `:136`, `keyvalue/KeyBrowserView.kt:1037` (with `visibleScrollbarStyle()`) |
| Tests | `settings/SettingsScreenTest.kt` (pages render, snapshots) |

## Layout

### TabStrip / TabData / SimpleTabContent
| Item | Detail |
|---|---|
| Calls | Jewel `TabStrip` 4: `connections/ConnectionDialog.kt:358`, `schema/TableDesignerView.kt:157`, `table/StructureView.kt:34`, `console/ConsoleView.kt:267`. `SimpleTabContent` 5 |
| Variant | Only `TabData.Default` with `style = JewelTheme.defaultTabStyle`. `TabData.Editor` is never used; the editor tab style is only themed (`ui/theme/ThemedControls.kt:316`) |
| Params used | `selected`, `content = { state -> SimpleTabContent(label, state, iconKey?) }`, `closable` (false in dialogs; true for console result tabs), `onClose`, `onClick`. Labels carry counts ("Columns 12") or "SSH ✓". Pinned result tab shows `Actions.PinTab` icon and a `ContextMenuArea` (Pin/Unpin, Close Other Unpinned) (`console/ConsoleView.kt:277`) |
| Theme | Selected underline = accent; hover/pressed background = `c.hover` (`ui/theme/ThemedControls.kt:322`) |
| Workspace tabs | The main editor tabs are not Jewel: `workspace/WorkspaceView.kt:111` own `TabStrip` + `PillTab` (`:165`): accent-filled pill when selected, middle-click closes, close icon on hover/selected, drag to reorder with spring animation and edge auto-scroll |
| Tests | `connections/ConnectionDialogTest.kt:142` (clicks the "Options" tab); `workspace/WorkspaceTabDragTest.kt` (custom pill tabs: drag, reorder, edge scroll) |

### HorizontalSplitLayout / VerticalSplitLayout
| Item | Detail |
|---|---|
| Calls | Horizontal 6 in 5 files, Vertical 2 |
| Params used | `first`, `second`, `modifier = fillMaxSize()`, `firstPaneMinWidth` / `secondPaneMinWidth` (e.g. 220/420 dp sidebar, 480/300 dp agent, 300/220 dp value panel, 80/80 dp console editor/results), `state = rememberSplitLayoutState(ratio)` (0.34, 0.48, 0.6, 0.62, 0.72) |
| Island variant | `ui/MainScreen.kt:63`, `:122`: `dividerStyle = DividerStyle(Transparent, DividerMetrics(0.dp, 0.dp))`, `draggableWidth = IslandGap` (6 dp), panes padded by `IslandGap / 2`, so the gap between islands is the drag handle |
| State persistence | `AppState.kt:91`, `:93` hold `SplitLayoutState(0.24f)` / `(0.68f)`; `workspace/SessionKeeper.kt:41`–`:76` saves `dividerPosition` and restores it clamped to 0.1–0.6 (sidebar) and 0.3–0.9 (agent) |
| Files | `ui/MainScreen.kt:63`, `:122`; `schema/SchemaCompareView.kt:84`; `keyvalue/KeyBrowserView.kt:103`; `table/TableDataView.kt:167`; `documents/DocumentBrowserView.kt:135`; vertical `console/ConsoleView.kt:82`, `schema/TableDesignerView.kt:105` |
| Tests | No drag tests; panes appear in all MainScreen snapshots |

### Lazy Tree
| Item | Detail |
|---|---|
| Calls | `LazyTree` 1 (`explorer/ExplorerPanel.kt:139`); tree built with `buildTree` (`explorer/ExplorerViewModel.kt:115`); `TreeState(SelectableLazyListState(LazyListState()))` (`:104`); `treeStyle` read in 10 files |
| Params used | `tree`, `treeState`, `style = explorerTreeStyle()` (`explorer/ExplorerPanel.kt:176`: indent 16 dp, row corner 4 dp, element padding 6 dp, content padding 4/2 dp, min height 26 dp, chevron gap 4 dp), `onElementDoubleClick` (opens the node) |
| App behavior | Lazy loading on expand via `snapshotFlow { treeState.openNodes }` (`:119`); programmatic `openNodes(...)` (`:124`, `:129`, `ExplorerViewModel.kt:240`, `:277`); "Collapse All" sets `openNodes = emptySet()` (`:241`); leaves get a manual inset because no chevron is drawn (`:146`); per-row `ContextMenuArea` and blank-area menu; rows are drag sources and drop targets (`explorer/ConnectionDrag.kt`, reads `treeState.selectedKeys` for multi-select at `:106`); overlay `VerticalScrollbar` on `treeState.lazyListState` |
| Keyboard | Arrow-key navigation and selection come from Jewel; the app adds no key handler. Must be measured on the Kotlin build |
| Tests | `explorer/ExplorerContextMenuTest.kt` (right-click rows / blank area), `explorer/ConnectionGroupingTest.kt` |

### DecoratedWindow / TitleBar / newFullscreenControls
| Item | Detail |
|---|---|
| Calls | DecoratedWindow 2 (`Main.kt:99`, `files/DataFileWindowView.kt:65`); TitleBar 2 (`ui/TitleBarView.kt:45`, `files/DataFileWindowView.kt:112`), both `Modifier.newFullscreenControls()` |
| Params used | `onCloseRequest`, `state`, `visible`, `title`, `icon = AppIcon`, `onPreviewKeyEvent`, `onKeyEvent` |
| Title bar content | Start: connection `Dropdown` (`clientRegion("connection_widget")`, height 30 dp). Center: tab title in `text.info`. End: `HeaderButton`s (32 dp, `clientRegion(name)`) |
| App behavior | Main window preview keys: skip while `Shortcuts.recording`; double-Shift opens go-to-anything (`search/GoToAnything.kt:340`, 400 ms); tab switching and zoom shortcuts (`Main.kt:107`–`:111`). `onKeyEvent` → `handleWindowShortcut` (`Main.kt:136`–`:177`). `FollowThemeAppearance` sets NSAppearance (`ui/WindowAppearance.kt:13`); pinch zoom (`ui/PinchZoom.kt`); file drop target. Title bar is not zoomed (`Main.kt:127` comment) |
| Tests | `files/FileWindowsTest.kt` (file window logic) |

## TableGrip shell pieces (`ui/`)

| Piece | Where | Behavior to replicate | Tests |
|---|---|---|---|
| AppDialog | `ui/AppDialog.kt:45` (state) / `:84` (size); 29 calls in 19 files | `DialogWindow` with frame background, custom title bar (32 dp) on macOS/Windows via JBR, content in an `Island` inset 4 dp (0 dp = edge-to-edge panel), content wrapped in `Zoomed`. Esc closes only for a bare KeyDown Escape with no modifiers, after `onKeyEvent` declined (`isCloseKey`, `:98`). `resizable = false` in 7 calls; `closeOnEscape = false` in `grid/RecordEditorDialog.kt:119` (pane warns first); `closeOnEscape = !running` in `schema/TestDataDialog.kt:205`. Enter-to-submit added by callers | `ui/AppDialogTest.kt:14`; `grid/RecordEditorUiTest.kt:105` |
| DialogTitleBar | `ui/DialogTitleBar.kt:80` | Whole bar drags the dialog; elements marked `Modifier.interactive()` keep clicks (hit-test regions in window pixels, scaled from AWT points, `:118`); centered title uses the wider of the two insets (`:48`); Windows caption buttons get `controls.dark` from bar luminance (`:73`); null on Linux (native bar) | `ui/DialogTitleBarTest.kt:22` |
| ToolStripe | `ui/ToolStripe.kt:65` (left), `:87` (right) | 44 dp column; 32 dp buttons, radius 8, accent fill + white icon when selected, `stripeHover` on hover; groups Tools / Actions (20 dp divider) / Bottom / Settings; order and hidden items from settings; right-click "Hide <item>" | `settings/SettingsScreenTest.kt:65` (Settings button), MainScreen snapshots |
| StatusBar | `ui/StatusBar.kt:65` | 26 dp row: breadcrumbs, ellipsized status with full-text tooltip, widgets (running spinner, selection, pending edits, caret, transaction menu, read-only, zoom, blocked sessions, connection dot + menu). `StatusWidget` (`:375`): 20 dp, radius 4, hover background only if clickable | MainScreen snapshots |
| UiZoom | `ui/UiZoom.kt` | Levels 50…300 (13 steps), `snap` to nearest, `step` up/down; `Zoomed` scales density; ⌘+/⌘=/⌘-/⌘0 and keypad +/- (`handleZoomShortcut`, `:70`); status bar control visible only when ≠100 % or its menu is open | None found |
| Shortcuts / keymap | `ui/Shortcuts.kt` (67 actions) | `Shortcut.matches`: KeyDown only, exact modifiers, `primary` = ⌘ on macOS / Ctrl elsewhere, `ctrl` = physical Control on macOS, Enter also matches NumPadEnter (`:33`); platform labels (`⌥⇧⌘L` vs `Ctrl+Alt+Shift+L`); presets TableGrip / IDE Classic / VS Code; user bindings override; `Shortcuts.recording` suspends window shortcuts while Settings → Keymap records (`settings/KeymapPanel.kt:154`) | `ui/ShortcutsTest.kt`, `ui/KeymapTest.kt`, `settings/SettingsScreenTest.kt:136`, `search/GoToAnythingTest.kt:33` |
| ThemedControls | `ui/theme/ThemedControls.kt:67` | Colour-only overrides for 14 styles (see Cross-cutting). Notable choices: disabled button background `Unspecified`; outlined button border hover/pressed = `textMuted`; text field caret = text colour in all states; list/tree selection = `selection` (active) / `selectionInactive`; menu hover = focused = pressed = `selection`; tab underline selected = accent | `ui/theme/ThemeGallerySnapshotTest.kt`, `ui/theme/ColorThemeTest.kt` |

## Test coverage summary

| Component | Interacted with in tests | Render-only (snapshots) |
|---|---|---|
| DefaultButton / OutlinedButton | conformance `button.json`, UpdatesPanelTest, ImportConnectionsTest, RecordEditorUiTest, KeyBrowserUiTest, ConsoleFixTest | all |
| CheckboxRow / Checkbox | conformance `checkbox.json`, ImportConnectionsTest | SchemaToolsSnapshotTest, BackupFlowTest |
| TextField | conformance `text-field.json`, SettingsScreenTest, ConnectionDialogTest, FilterBuilderUiTest, KeyBrowserUiTest, MentionSourcesTest | all |
| Outline.Error | SettingsScreenTest, FilterBuilderUiTest | |
| TabStrip (Jewel) | ConnectionDialogTest | ConsoleChartTest, ConsoleFixTest |
| Pill tabs (app) | WorkspaceTabDragTest | |
| ListComboBox | (label only) ConnectionDialogTest | BackupFlowTest |
| Inline banner link action | ConnectionDialogTest | |
| LazyTree + context menu | ExplorerContextMenuTest | MainScreenSnapshotTest |
| Scrollbars | | KeyBrowserUiTest, GridSnapshotTest |
| AppDialog / DialogTitleBar | AppDialogTest, DialogTitleBarTest, RecordEditorUiTest | |
| Shortcuts | ShortcutsTest, KeymapTest, SettingsScreenTest, GoToAnythingTest | |
| RadioButtonRow, TriStateCheckbox, Tooltip, PopupMenu, split layouts, UiZoom, CircularProgressIndicator, Link (assert) | no direct assertions | various |

Conformance (`conformance/ConformanceTest.kt`, `Subjects.kt`) currently hosts only `checkbox` (CheckboxRow), `button`
(Default/Outlined) and `text-field` (TextField with `width(220.dp)`). The directory is untracked in git at the time of
writing.

## Priority & risks

| Rank | Component | Why it's hard |
|---|---|---|
| 1 | TextField (+ composites) | 93 sites, state-based API, 18 preview key handlers that must win over the field's own keys (↑/↓/Enter/Tab/Esc/Ctrl+Space), suggestion popups that keep focus in the field, password masking, error outline, leading/trailing slots, editor font. Highest usage × behavior. |
| 2 | Keyboard and focus model | 102 `onPreviewKeyEvent` + 13 `onKeyEvent` across 22 files; ordering matters (window preview → focused control → window `onKeyEvent`); Esc semantics in AppDialog; `Shortcuts.recording`; double-Shift; Enter-to-submit per dialog. The Rust kit needs Compose-like preview/bubble phases, not only action dispatch. |
| 3 | Lazy Tree (Explorer) | Single call but carries lazy loading, programmatic expansion, multi-select read by drag-and-drop, double-click, per-row and blank-area context menus, custom metrics, Jewel's built-in keyboard navigation (unspecified in app code: must be measured). |
| 4 | Window chrome (DecoratedWindow, TitleBar, DialogTitleBar) | Depends on JBR custom title bars: native traffic lights / caption buttons, drag vs. interactive hit regions, fullscreen controls, NSAppearance, Linux fallback. Phase 0 spike risk for GPUI. |
| 5 | PopupMenu / Dropdown / ContextMenuArea | Three menu entry points sharing one style; selected/disabled/icon items, separators, alignment Start/End, min width tied to anchor, keyboard dismissal signalling `InputMode`. No submenus, which narrows scope. |
| 6 | Split layouts | Island variant where the 6 dp transparent gap is the handle, min pane sizes, persisted ratio with clamping. |
| 7 | Scrollbars | Overlay positioning, LazyList and ScrollState adapters, "always visible + jump to spot" variant, pointer claiming on the grid. Visibility/fade timing is Jewel's and must be measured. |
| 8 | Tabs | Only `TabData.Default` (no editor tab), but closable result tabs with icons and context menus; the app's own pill tab strip (drag reorder, auto-scroll, middle-click) is outside Jewel and already has tests. |
| 9 | ThemedControls parity | Graphite uses pure Int UI defaults that the theme never sets; every other theme overrides colours only. Colour tokens for ~14 styles × ~5 states must be measured from renders. |
| Low | Text, Icon, Divider, Link, buttons, checkbox family, radio, banners, progress, Island | Simple parameter surfaces. Icon set (80 keys) needs a licensing/asset decision; banners need the 3-link limit and icon actions. |

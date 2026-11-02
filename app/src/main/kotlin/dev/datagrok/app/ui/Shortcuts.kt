package dev.datagrok.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.nativeKeyCode
import dev.datagrok.app.grid.isMac
import dev.datagrok.core.settings.KeyBinding
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A key combination. `primary` is ⌘ on macOS and Ctrl elsewhere; `ctrl` is the physical Control key on macOS (on other
 * platforms it is the same as `primary`). Modifiers must match exactly, so ⌘D and ⌘⇧D are different shortcuts.
 */
data class Shortcut(
    val key: Key,
    val keyLabel: String,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key != key && !(key == Key.Enter && event.key == Key.NumPadEnter)) return false
        val wantsCtrl = if (isMac) ctrl else ctrl || primary
        val wantsMeta = isMac && primary
        return event.isCtrlPressed == wantsCtrl && event.isMetaPressed == wantsMeta && event.isShiftPressed == shift && event.isAltPressed == alt
    }

    /** E.g. `⌥⇧⌘L` on macOS, `Ctrl+Alt+Shift+L` elsewhere. */
    val label: String
        get() =
            if (isMac) {
                buildString {
                    if (ctrl) append('⌃')
                    if (alt) append('⌥')
                    if (shift) append('⇧')
                    if (primary) append('⌘')
                    append(keyLabel)
                }
            } else {
                (listOfNotNull("Ctrl".takeIf { ctrl || primary }, "Alt".takeIf { alt }, "Shift".takeIf { shift }) + keyLabel).joinToString("+")
            }

    override fun toString(): String = label

    fun toBinding(): KeyBinding = KeyBinding(key.nativeKeyCode, keyLabel, primary, shift, alt, ctrl)

    companion object {
        /** The shortcut [event] presses, or null for a lone modifier key (still being held for a combination). */
        fun of(event: KeyEvent): Shortcut? {
            if (event.key in ModifierKeys) return null
            val code = event.key.nativeKeyCode
            return Shortcut(
                key = event.key,
                keyLabel = keyLabel(code),
                primary = if (isMac) event.isMetaPressed else event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                ctrl = isMac && event.isCtrlPressed,
            )
        }
    }
}

/** A rebindable command: [id] is stable across versions and is what settings store. */
class ShortcutAction(val id: String, val group: String, val description: String, val default: Shortcut)

fun KeyBinding.toShortcut(): Shortcut = Shortcut(Key(key), label, primary, shift, alt, ctrl)

private val ModifierKeys =
    setOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.Function)

/** How a key reads in menus: symbols for the keys macOS draws as symbols, AWT's name otherwise. */
private fun keyLabel(code: Int): String =
    when (code) {
        java.awt.event.KeyEvent.VK_ENTER -> EnterLabel
        java.awt.event.KeyEvent.VK_BACK_SPACE -> BackspaceLabel
        java.awt.event.KeyEvent.VK_UP -> "↑"
        java.awt.event.KeyEvent.VK_DOWN -> "↓"
        java.awt.event.KeyEvent.VK_LEFT -> "←"
        java.awt.event.KeyEvent.VK_RIGHT -> "→"
        java.awt.event.KeyEvent.VK_TAB -> "Tab"
        java.awt.event.KeyEvent.VK_SPACE -> "Space"
        java.awt.event.KeyEvent.VK_DELETE -> if (isMac) "⌦" else "Delete"
        java.awt.event.KeyEvent.VK_PAGE_UP -> "PgUp"
        java.awt.event.KeyEvent.VK_PAGE_DOWN -> "PgDn"
        java.awt.event.KeyEvent.VK_SLASH -> "/"
        java.awt.event.KeyEvent.VK_BACK_SLASH -> "\\"
        java.awt.event.KeyEvent.VK_COMMA -> ","
        java.awt.event.KeyEvent.VK_PERIOD -> "."
        java.awt.event.KeyEvent.VK_SEMICOLON -> ";"
        java.awt.event.KeyEvent.VK_QUOTE -> "'"
        java.awt.event.KeyEvent.VK_OPEN_BRACKET -> "["
        java.awt.event.KeyEvent.VK_CLOSE_BRACKET -> "]"
        java.awt.event.KeyEvent.VK_MINUS -> "-"
        java.awt.event.KeyEvent.VK_EQUALS -> "="
        java.awt.event.KeyEvent.VK_BACK_QUOTE -> "`"
        else -> java.awt.event.KeyEvent.getKeyText(code)
    }

/**
 * Ready-made keymaps, as changes over Datagrok's own. They only rebind what the other tool binds differently and
 * Datagrok has an action for.
 */
enum class KeymapPreset(val label: String) {
    Default("Datagrok"),
    DataGrip("DataGrip / IntelliJ"),
    VsCode("VS Code");

    fun bindings(): Map<String, Shortcut> =
        when (this) {
            Default -> emptyMap()
            DataGrip ->
                mapOf(
                    // Stop, and Go to Database Object; the rest already matches.
                    "Cancel" to Shortcut(Key.F2, "F2", primary = true),
                    "GoToAnything" to Shortcut(Key.O, "O", primary = true),
                )
            VsCode ->
                mapOf(
                    "Agent" to if (isMac) Shortcut(Key.I, "I", primary = true, ctrl = true) else Shortcut(Key.I, "I", primary = true, alt = true),
                    "NextTab" to if (isMac) Shortcut(Key.DirectionRight, "→", primary = true, alt = true) else Shortcut(Key.PageDown, "PgDn", primary = true),
                    "PreviousTab" to if (isMac) Shortcut(Key.DirectionLeft, "←", primary = true, alt = true) else Shortcut(Key.PageUp, "PgUp", primary = true),
                    "Duplicate" to Shortcut(Key.DirectionDown, "↓", alt = true, shift = true),
                    "DeleteLine" to Shortcut(Key.K, "K", primary = true, shift = true),
                    "MoveLineUp" to Shortcut(Key.DirectionUp, "↑", alt = true),
                    "MoveLineDown" to Shortcut(Key.DirectionDown, "↓", alt = true),
                    "Format" to Shortcut(Key.F, "F", alt = true, shift = true),
                    "Replace" to if (isMac) Shortcut(Key.F, "F", primary = true, alt = true) else Shortcut(Key.H, "H", primary = true),
                )
        }

    companion object {
        fun of(name: String): KeymapPreset = entries.firstOrNull { it.name == name } ?: Default
    }
}

private val EnterLabel = if (isMac) "⏎" else "Enter"
private val BackspaceLabel = if (isMac) "⌫" else "Backspace"

/** Every app shortcut in one place, so key handling, tooltips and the shortcuts dialog always agree. */
object Shortcuts {
    private val registry = mutableListOf<ShortcutAction>()

    /** Every rebindable action, in declaration order. */
    val actions: List<ShortcutAction>
        get() = registry

    /** Bindings that differ from the defaults: the preset's, then the user's own. Snapshot state, so views follow. */
    private var bindings by mutableStateOf<Map<String, Shortcut>>(emptyMap())

    /** Whether a key is being recorded in Settings → Keymap; window-wide shortcuts stand aside meanwhile. */
    var recording by mutableStateOf(false)

    fun current(action: ShortcutAction): Shortcut = bindings[action.id] ?: action.default

    fun byId(id: String): ShortcutAction? = registry.firstOrNull { it.id == id }

    /** Applies [preset] and then the user's own [custom] bindings, by action id; unknown ids are ignored. */
    fun configure(preset: KeymapPreset, custom: Map<String, KeyBinding>) {
        bindings = preset.bindings() + custom.mapValues { it.value.toShortcut() }
    }

    /** What [action] is bound to by [preset] alone, without the user's changes. */
    fun presetBinding(preset: KeymapPreset, action: ShortcutAction): Shortcut = preset.bindings()[action.id] ?: action.default

    private fun action(group: String, description: String, default: Shortcut) =
        PropertyDelegateProvider<Shortcuts, ReadOnlyProperty<Shortcuts, Shortcut>> { _, property ->
            val action = ShortcutAction(property.name, group, description, default)
            registry += action
            ReadOnlyProperty { _, _ -> current(action) }
        }

    // Window
    val NewConsole by action("General", "New console for the current connection", Shortcut(Key.T, "T", primary = true))
    val CloseTab by action("General", "Close tab", Shortcut(Key.W, "W", primary = true))
    val NextTab by action("General", "Next tab", if (isMac) Shortcut(Key.RightBracket, "]", primary = true, shift = true) else Shortcut(Key.PageDown, "PgDn", primary = true))
    val PreviousTab by action("General", "Previous tab", if (isMac) Shortcut(Key.LeftBracket, "[", primary = true, shift = true) else Shortcut(Key.PageUp, "PgUp", primary = true))
    val NextTabAlt by action("General", "Next tab (alternative)", Shortcut(Key.Tab, "Tab", ctrl = true))
    val PreviousTabAlt by action("General", "Previous tab (alternative)", Shortcut(Key.Tab, "Tab", ctrl = true, shift = true))
    val Explorer by action("General", "Connections", if (isMac) Shortcut(Key.One, "1", primary = true) else Shortcut(Key.One, "1", alt = true))
    val History by action("General", "Query History", if (isMac) Shortcut(Key.Two, "2", primary = true) else Shortcut(Key.Two, "2", alt = true))
    val Agent by action("General", "Agent", if (isMac) Shortcut(Key.Three, "3", primary = true) else Shortcut(Key.Three, "3", alt = true))
    val Queries by action("General", "Saved Queries", if (isMac) Shortcut(Key.Four, "4", primary = true) else Shortcut(Key.Four, "4", alt = true))
    /** Like TablePlus' Open Anything; a double ⇧ opens it too, like JetBrains' Search Everywhere. */
    val GoToAnything by action("General", "Go to anything", Shortcut(Key.P, "P", primary = true))
    val Activity by action("General", "Activity: sessions and locks", Shortcut(Key.A, "A", primary = true, alt = true))
    val Settings by action("General", "Settings", if (isMac) Shortcut(Key.Comma, ",", primary = true) else Shortcut(Key.S, "S", primary = true, alt = true))
    val ShowShortcuts by action("General", "Keyboard shortcuts", Shortcut(Key.Slash, "/", primary = true, shift = true))
    /** Cursor's key: mentions what is selected in the agent chat, or just opens it when nothing is. */
    val AddToChat by action("General", "Mention the selection in the agent chat", Shortcut(Key.L, "L", primary = true))
    /** VS Code's key; a focused grid takes it first on a foreign-key cell (Go to Referenced Row). */
    val ToggleSidebar by action("General", "Show / hide the sidebar", Shortcut(Key.B, "B", primary = true))
    /** VS Code's and the browsers' keys; ⌘= is ⌘+ without ⇧, and both zoom in. */
    val ZoomIn by action("General", "Zoom in", Shortcut(Key.Equals, "=", primary = true))
    val ZoomInAlt by action("General", "Zoom in (alternative)", Shortcut(Key.Equals, "+", primary = true, shift = true))
    val ZoomOut by action("General", "Zoom out", Shortcut(Key.Minus, "-", primary = true))
    val ResetZoom by action("General", "Reset zoom", Shortcut(Key.Zero, "0", primary = true))

    // Console
    val RunStatement by action("Console", "Execute statement at caret or selection", Shortcut(Key.Enter, EnterLabel, primary = true))
    val RunScript by action("Console", "Execute script", Shortcut(Key.Enter, EnterLabel, primary = true, shift = true))
    val Cancel by action("Console", "Cancel running query", if (isMac) Shortcut(Key.Period, ".", primary = true) else Shortcut(Key.F2, "F2", primary = true))
    val Explain by action("Console", "Explain plan", Shortcut(Key.E, "E", primary = true, shift = true))
    val Save by action("Console", "Save query / submit table changes", Shortcut(Key.S, "S", primary = true))
    val SaveAs by action("Console", "Save script to a file", Shortcut(Key.S, "S", primary = true, shift = true))

    // SQL editor
    val Completion by action("SQL Editor", "Code completion", Shortcut(Key.Spacebar, "Space", ctrl = true))
    val Comment by action("SQL Editor", "Comment / uncomment lines", Shortcut(Key.Slash, "/", primary = true))
    val Duplicate by action("SQL Editor", "Duplicate line or selection", Shortcut(Key.D, "D", primary = true))
    val DeleteLine by action("SQL Editor", "Delete line", if (isMac) Shortcut(Key.Backspace, BackspaceLabel, primary = true) else Shortcut(Key.Y, "Y", primary = true))
    val MoveLineUp by action("SQL Editor", "Move line up", Shortcut(Key.DirectionUp, "↑", alt = true, shift = true))
    val MoveLineDown by action("SQL Editor", "Move line down", Shortcut(Key.DirectionDown, "↓", alt = true, shift = true))
    val Format by action("SQL Editor", "Reformat SQL", Shortcut(Key.L, "L", primary = true, alt = true))
    val Find by action("SQL Editor", "Find", Shortcut(Key.F, "F", primary = true))
    val Replace by action("SQL Editor", "Replace", Shortcut(Key.R, "R", primary = true))
    val Indent by action("SQL Editor", "Indent lines", Shortcut(Key.Tab, "Tab"))
    val Outdent by action("SQL Editor", "Outdent lines", Shortcut(Key.Tab, "Tab", shift = true))

    // Data grid
    val Copy by action("Data Grid", "Copy cells", Shortcut(Key.C, "C", primary = true))
    val CopyWithHeader by action("Data Grid", "Copy cells with header", Shortcut(Key.C, "C", primary = true, shift = true))
    val SelectAll by action("Data Grid", "Select all", Shortcut(Key.A, "A", primary = true))
    val EditCell by action("Data Grid", "Edit cell", Shortcut(Key.F2, "F2"))
    val ExpandEditor by action("Data Grid", "Expand the cell editor", Shortcut(Key.Enter, EnterLabel, shift = true))
    val SetNull by action("Data Grid", "Set cell to NULL", Shortcut(Key.N, "N", primary = true, alt = true))
    val AddRow by action("Data Grid", "Add row", Shortcut(Key.N, "N", primary = true))
    val DeleteRows by action("Data Grid", "Delete rows", Shortcut(Key.Backspace, BackspaceLabel, primary = true))
    val RevertRows by action("Data Grid", "Revert rows", Shortcut(Key.Z, "Z", primary = true))
    val Submit by action("Data Grid", "Submit changes / apply in the cell editor", Shortcut(Key.Enter, EnterLabel, primary = true))
    val Reload by action("Data Grid", "Reload table", Shortcut(Key.R, "R", primary = true))
    /** DataGrip's Go to Declaration key, which on a foreign-key cell goes to the referenced row. */
    val GoToReferenced by action("Data Grid", "Go to the row a foreign key points at", Shortcut(Key.B, "B", primary = true))

    // Table filter: anywhere in a table viewer
    val FocusFilter by action("Table Filter", "Focus filter", Shortcut(Key.F, "F", primary = true))
    val FilterByValue by action("Table Filter", "Filter by the selected cell's value", Shortcut(Key.F, "F", primary = true, alt = true))
    val ExcludeValue by action("Table Filter", "Exclude the selected cell's value", Shortcut(Key.F, "F", primary = true, alt = true, shift = true))
    val ToggleFilterMode by action("Table Filter", "Switch between builder and SQL; show or hide a collection's query builder", Shortcut(Key.F, "F", primary = true, shift = true))
    val ClearFilter by action("Table Filter", "Clear filter", Shortcut(Key.Backspace, BackspaceLabel, primary = true, shift = true))

    // Filter builder: in a condition row. Row editing mirrors line editing in the SQL editor.
    val ApplyFilter by action("Filter Builder", "Apply filter", Shortcut(Key.Enter, EnterLabel, primary = true))
    val AddCondition by action("Filter Builder", "Add condition below", Shortcut(Key.N, "N", primary = true))
    val AddConditionGroup by action("Filter Builder", "Add group below", Shortcut(Key.N, "N", primary = true, shift = true))
    val AddSqlCondition by action("Filter Builder", "Add SQL condition below", Shortcut(Key.N, "N", primary = true, alt = true))
    val ChooseColumn by action("Filter Builder", "Choose column", Shortcut(Key.C, "C", primary = true, alt = true))
    val ChooseOperator by action("Filter Builder", "Choose operator", Shortcut(Key.O, "O", primary = true, alt = true))
    val ToggleCombinator by action("Filter Builder", "Switch the group between all / any", Shortcut(Key.A, "A", primary = true, shift = true))
    val MatchCase by action("Filter Builder", "Match case", Shortcut(Key.C, "C", alt = true))
    val PreviousCondition by action("Filter Builder", "Previous condition", Shortcut(Key.DirectionUp, "↑"))
    val NextCondition by action("Filter Builder", "Next condition", Shortcut(Key.DirectionDown, "↓"))

    class Entry(val description: String, val keys: String)

    class Group(val title: String, val entries: List<Entry>)

    private fun entry(description: String, vararg shortcuts: Shortcut) = Entry(description, shortcuts.joinToString("  /  ") { it.label })

    /** What the shortcuts dialog lists, with the keys as currently bound. */
    val groups: List<Group>
        get() = listOf(
            Group(
                "General",
                listOf(
                    Entry("Go to anything: tables, tabs, queries, actions", "${GoToAnything.label}  /  ${if (isMac) "⇧⇧" else "Shift Shift"}"),
                    entry("New console for the current connection", NewConsole),
                    entry("Close tab", CloseTab),
                    entry("Next tab", NextTab, NextTabAlt),
                    entry("Previous tab", PreviousTab, PreviousTabAlt),
                    entry("Connections", Explorer),
                    entry("Query History", History),
                    entry("Agent", Agent),
                    entry("Mention the selection or statement in the agent chat, or open it", AddToChat),
                    entry("Show / hide the sidebar", ToggleSidebar),
                    entry("Zoom in / out", ZoomIn, ZoomOut),
                    entry("Reset zoom", ResetZoom),
                    entry("Saved Queries", Queries),
                    entry("Activity: sessions and locks of the current connection", Activity),
                    entry("Settings", Settings),
                    entry("Keyboard shortcuts", ShowShortcuts),
                ),
            ),
            Group(
                "Console",
                listOf(
                    entry("Execute statement at caret or selection", RunStatement),
                    entry("Execute script", RunScript),
                    entry("Cancel running query", Cancel),
                    entry("Explain plan", Explain),
                    entry("Save query (the first time: name it in Saved Queries)", Save),
                    entry("Save script to a file…", SaveAs),
                ),
            ),
            Group(
                "SQL Editor",
                listOf(
                    entry("Code completion", Completion),
                    entry("Comment / uncomment lines", Comment),
                    entry("Duplicate line or selection", Duplicate),
                    entry("Delete line", DeleteLine),
                    entry("Move line up / down", MoveLineUp, MoveLineDown),
                    entry("Indent / outdent lines", Indent, Outdent),
                    entry("Reformat SQL", Format),
                    entry("Find", Find),
                    entry("Replace", Replace),
                ),
            ),
            Group(
                "Data Grid",
                listOf(
                    entry("Copy cells", Copy),
                    entry("Copy cells with header", CopyWithHeader),
                    entry("Select all", SelectAll),
                    Entry("Edit cell", "$EnterLabel  /  ${EditCell.label}"),
                    entry("Expand the cell editor (${Submit.label} applies)", ExpandEditor),
                    entry("Set cell to NULL", SetNull),
                    entry("Add row", AddRow),
                    entry("Delete rows", DeleteRows),
                    entry("Revert rows", RevertRows),
                    entry("Submit changes", Submit, Save),
                    entry("Reload table", Reload),
                    entry("Go to the row a foreign key points at", GoToReferenced),
                ),
            ),
            Group(
                "Table Filter",
                listOf(
                    entry("Focus filter (adds a condition on the current column)", FocusFilter),
                    entry("Filter by the selected cell's value", FilterByValue),
                    entry("Exclude the selected cell's value", ExcludeValue),
                    entry("Switch between builder and SQL; show or hide a collection's query builder", ToggleFilterMode),
                    entry("Clear filter", ClearFilter),
                    Entry("Apply filter", "$EnterLabel  /  ${ApplyFilter.label}"),
                    Entry("Back to the grid", "Esc"),
                ),
            ),
            Group(
                "Filter Builder",
                listOf(
                    entry("Add condition below", AddCondition),
                    entry("Add group below", AddConditionGroup),
                    entry("Add SQL condition below", AddSqlCondition),
                    entry("Duplicate condition", Duplicate),
                    entry("Remove condition", DeleteLine),
                    entry("Enable / disable condition", Comment),
                    entry("Move condition up / down", MoveLineUp, MoveLineDown),
                    entry("Previous / next condition", PreviousCondition, NextCondition),
                    entry("Choose column", ChooseColumn),
                    entry("Choose operator", ChooseOperator),
                    entry("Switch the group between all / any", ToggleCombinator),
                    entry("Match case", MatchCase),
                ),
            ),
        )
}

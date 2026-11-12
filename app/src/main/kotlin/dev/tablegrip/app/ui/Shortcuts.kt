package dev.tablegrip.app.ui

import dev.tablegrip.core.i18n.tr
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
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.core.settings.KeyBinding
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
class ShortcutAction(val id: String, val group: String, val description: String, val default: Shortcut) {
    /** [group] as the interface shows it; [group] itself stays English, since keymap contexts are matched by it. */
    val groupLabel: String
        get() =
            when (group) {
                "General" -> tr("General")
                "Console" -> tr("Console")
                "SQL Editor" -> tr("SQL Editor")
                "Data Grid" -> tr("Data Grid")
                "Table Filter" -> tr("Table Filter")
                "Filter Builder" -> tr("Filter Builder")
                else -> group
            }
}

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
 * Ready-made keymaps, as changes over TableGrip's own. They only rebind what the other tool binds differently and
 * TableGrip has an action for.
 */
enum class KeymapPreset(val label: String) {
    Default("TableGrip"),
    IdeClassic("IDE Classic"),
    VsCode("VS Code");

    fun bindings(): Map<String, Shortcut> =
        when (this) {
            Default -> emptyMap()
            IdeClassic ->
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
    val NewConsole by action("General", tr("New console for the current connection"), Shortcut(Key.T, "T", primary = true))
    val CloseTab by action("General", tr("Close tab"), Shortcut(Key.W, "W", primary = true))
    val NextTab by action("General", tr("Next tab"), if (isMac) Shortcut(Key.RightBracket, "]", primary = true, shift = true) else Shortcut(Key.PageDown, "PgDn", primary = true))
    val PreviousTab by action("General", tr("Previous tab"), if (isMac) Shortcut(Key.LeftBracket, "[", primary = true, shift = true) else Shortcut(Key.PageUp, "PgUp", primary = true))
    val NextTabAlt by action("General", tr("Next tab (alternative)"), Shortcut(Key.Tab, "Tab", ctrl = true))
    val PreviousTabAlt by action("General", tr("Previous tab (alternative)"), Shortcut(Key.Tab, "Tab", ctrl = true, shift = true))
    val Explorer by action("General", tr("Connections"), if (isMac) Shortcut(Key.One, "1", primary = true) else Shortcut(Key.One, "1", alt = true))
    val History by action("General", tr("Query History"), if (isMac) Shortcut(Key.Two, "2", primary = true) else Shortcut(Key.Two, "2", alt = true))
    val Agent by action("General", tr("Agent"), if (isMac) Shortcut(Key.Three, "3", primary = true) else Shortcut(Key.Three, "3", alt = true))
    val Queries by action("General", tr("Saved Queries"), if (isMac) Shortcut(Key.Four, "4", primary = true) else Shortcut(Key.Four, "4", alt = true))
    /** One field for everything; a double ⇧ opens it too. */
    val GoToAnything by action("General", tr("Go to anything"), Shortcut(Key.P, "P", primary = true))
    val Activity by action("General", tr("Activity: sessions and locks"), Shortcut(Key.A, "A", primary = true, alt = true))
    val Settings by action("General", tr("Settings"), if (isMac) Shortcut(Key.Comma, ",", primary = true) else Shortcut(Key.S, "S", primary = true, alt = true))
    val ShowShortcuts by action("General", tr("Keyboard shortcuts"), Shortcut(Key.Slash, "/", primary = true, shift = true))
    /** Cursor's key: mentions what is selected in the agent chat, or just opens it when nothing is. */
    val AddToChat by action("General", tr("Mention the selection in the agent chat"), Shortcut(Key.L, "L", primary = true))
    /** VS Code's key; a focused grid takes it first on a foreign-key cell (Go to Referenced Row). */
    val ToggleSidebar by action("General", tr("Show / hide the sidebar"), Shortcut(Key.B, "B", primary = true))
    /** VS Code's and the browsers' keys; ⌘= is ⌘+ without ⇧, and both zoom in. */
    val ZoomIn by action("General", tr("Zoom in"), Shortcut(Key.Equals, "=", primary = true))
    val ZoomInAlt by action("General", tr("Zoom in (alternative)"), Shortcut(Key.Equals, "+", primary = true, shift = true))
    val ZoomOut by action("General", tr("Zoom out"), Shortcut(Key.Minus, "-", primary = true))
    val ResetZoom by action("General", tr("Reset zoom"), Shortcut(Key.Zero, "0", primary = true))

    // Console
    val RunStatement by action("Console", tr("Execute statement at caret or selection"), Shortcut(Key.Enter, EnterLabel, primary = true))
    val RunScript by action("Console", tr("Execute script"), Shortcut(Key.Enter, EnterLabel, primary = true, shift = true))
    val Cancel by action("Console", tr("Cancel running query"), if (isMac) Shortcut(Key.Period, ".", primary = true) else Shortcut(Key.F2, "F2", primary = true))
    val Explain by action("Console", tr("Explain plan"), Shortcut(Key.E, "E", primary = true, shift = true))
    val ExplainAnalyze by action("Console", tr("Explain analyze: run the statement and show its actual plan"), Shortcut(Key.E, "E", primary = true, alt = true, shift = true))
    val Save by action("Console", tr("Save query / submit table changes"), Shortcut(Key.S, "S", primary = true))
    val SaveAs by action("Console", tr("Save script to a file"), Shortcut(Key.S, "S", primary = true, shift = true))

    // SQL editor
    val Completion by action("SQL Editor", tr("Code completion"), Shortcut(Key.Spacebar, "Space", ctrl = true))
    val Comment by action("SQL Editor", tr("Comment / uncomment lines"), Shortcut(Key.Slash, "/", primary = true))
    val Duplicate by action("SQL Editor", tr("Duplicate line or selection"), Shortcut(Key.D, "D", primary = true))
    val DeleteLine by action("SQL Editor", tr("Delete line"), if (isMac) Shortcut(Key.Backspace, BackspaceLabel, primary = true) else Shortcut(Key.Y, "Y", primary = true))
    val MoveLineUp by action("SQL Editor", tr("Move line up"), Shortcut(Key.DirectionUp, "↑", alt = true, shift = true))
    val MoveLineDown by action("SQL Editor", tr("Move line down"), Shortcut(Key.DirectionDown, "↓", alt = true, shift = true))
    val Format by action("SQL Editor", tr("Reformat SQL"), Shortcut(Key.L, "L", primary = true, alt = true))
    val Find by action("SQL Editor", tr("Find"), Shortcut(Key.F, "F", primary = true))
    val Replace by action("SQL Editor", tr("Replace"), Shortcut(Key.R, "R", primary = true))
    val Indent by action("SQL Editor", tr("Indent lines"), Shortcut(Key.Tab, "Tab"))
    val Outdent by action("SQL Editor", tr("Outdent lines"), Shortcut(Key.Tab, "Tab", shift = true))

    // Data grid
    val Copy by action("Data Grid", tr("Copy cells"), Shortcut(Key.C, "C", primary = true))
    val CopyWithHeader by action("Data Grid", tr("Copy cells with header"), Shortcut(Key.C, "C", primary = true, shift = true))
    val SelectAll by action("Data Grid", tr("Select all"), Shortcut(Key.A, "A", primary = true))
    val EditCell by action("Data Grid", tr("Edit cell"), Shortcut(Key.F2, "F2"))
    val ExpandEditor by action("Data Grid", tr("Expand the cell editor"), Shortcut(Key.Enter, EnterLabel, shift = true))
    val SetNull by action("Data Grid", tr("Set cell to NULL"), Shortcut(Key.N, "N", primary = true, alt = true))
    val AddRow by action("Data Grid", tr("Add row"), Shortcut(Key.N, "N", primary = true))
    val DeleteRows by action("Data Grid", tr("Delete rows"), Shortcut(Key.Backspace, BackspaceLabel, primary = true))
    /** Undo and redo step through the pending edits, like a spreadsheet; they're never sent until submitted. */
    val Undo by action("Data Grid", tr("Undo the last change to the data"), Shortcut(Key.Z, "Z", primary = true))
    val Redo by action("Data Grid", tr("Redo"), Shortcut(Key.Z, "Z", primary = true, shift = true))
    val RedoAlt by action("Data Grid", tr("Redo (alternative)"), Shortcut(Key.Y, "Y", primary = true))
    /** Drops every pending edit of the selected rows at once. */
    val RevertRows by action("Data Grid", tr("Revert rows"), Shortcut(Key.Z, "Z", primary = true, alt = true))
    val Submit by action("Data Grid", tr("Submit changes / apply in the cell editor"), Shortcut(Key.Enter, EnterLabel, primary = true))
    val Reload by action("Data Grid", tr("Reload table"), Shortcut(Key.R, "R", primary = true))
    /** The usual Go to Declaration key, which on a foreign-key cell goes to the referenced row. */
    val GoToReferenced by action("Data Grid", tr("Go to the row a foreign key points at"), Shortcut(Key.B, "B", primary = true))

    // Table filter: anywhere in a table viewer
    val FocusFilter by action("Table Filter", tr("Focus filter"), Shortcut(Key.F, "F", primary = true))
    val FilterByValue by action("Table Filter", tr("Filter by the selected cell's value"), Shortcut(Key.F, "F", primary = true, alt = true))
    val ExcludeValue by action("Table Filter", tr("Exclude the selected cell's value"), Shortcut(Key.F, "F", primary = true, alt = true, shift = true))
    val ToggleFilterMode by action("Table Filter", tr("Switch between builder and SQL; show or hide a collection's query builder"), Shortcut(Key.F, "F", primary = true, shift = true))
    val ClearFilter by action("Table Filter", tr("Clear filter"), Shortcut(Key.Backspace, BackspaceLabel, primary = true, shift = true))

    // Filter builder: in a condition row. Row editing mirrors line editing in the SQL editor.
    val ApplyFilter by action("Filter Builder", tr("Apply filter"), Shortcut(Key.Enter, EnterLabel, primary = true))
    val AddCondition by action("Filter Builder", tr("Add condition below"), Shortcut(Key.N, "N", primary = true))
    val AddConditionGroup by action("Filter Builder", tr("Add group below"), Shortcut(Key.N, "N", primary = true, shift = true))
    val AddSqlCondition by action("Filter Builder", tr("Add SQL condition below"), Shortcut(Key.N, "N", primary = true, alt = true))
    val ChooseColumn by action("Filter Builder", tr("Choose column"), Shortcut(Key.C, "C", primary = true, alt = true))
    val ChooseOperator by action("Filter Builder", tr("Choose operator"), Shortcut(Key.O, "O", primary = true, alt = true))
    val ToggleCombinator by action("Filter Builder", tr("Switch the group between all / any"), Shortcut(Key.A, "A", primary = true, shift = true))
    val MatchCase by action("Filter Builder", tr("Match case"), Shortcut(Key.C, "C", alt = true))
    val PreviousCondition by action("Filter Builder", tr("Previous condition"), Shortcut(Key.DirectionUp, "↑"))
    val NextCondition by action("Filter Builder", tr("Next condition"), Shortcut(Key.DirectionDown, "↓"))

    class Entry(val description: String, val keys: String)

    class Group(val title: String, val entries: List<Entry>)

    private fun entry(description: String, vararg shortcuts: Shortcut) = Entry(description, shortcuts.joinToString("  /  ") { it.label })

    /** What the shortcuts dialog lists, with the keys as currently bound. */
    val groups: List<Group>
        get() = listOf(
            Group(
                tr("General"),
                listOf(
                    Entry(tr("Go to anything: tables, tabs, queries, actions"), "${GoToAnything.label}  /  ${if (isMac) "⇧⇧" else "Shift Shift"}"),
                    entry(tr("New console for the current connection"), NewConsole),
                    entry(tr("Close tab"), CloseTab),
                    entry(tr("Next tab"), NextTab, NextTabAlt),
                    entry(tr("Previous tab"), PreviousTab, PreviousTabAlt),
                    entry(tr("Connections"), Explorer),
                    entry(tr("Query History"), History),
                    entry(tr("Agent"), Agent),
                    entry(tr("Mention the selection or statement in the agent chat, or open it"), AddToChat),
                    entry(tr("Show / hide the sidebar"), ToggleSidebar),
                    entry(tr("Zoom in / out"), ZoomIn, ZoomOut),
                    entry(tr("Reset zoom"), ResetZoom),
                    entry(tr("Saved Queries"), Queries),
                    entry(tr("Activity: sessions and locks of the current connection"), Activity),
                    entry(tr("Settings"), Settings),
                    entry(tr("Keyboard shortcuts"), ShowShortcuts),
                ),
            ),
            Group(
                tr("Console"),
                listOf(
                    entry(tr("Execute statement at caret or selection"), RunStatement),
                    entry(tr("Execute script"), RunScript),
                    entry(tr("Cancel running query"), Cancel),
                    entry(tr("Explain plan"), Explain),
                    entry(tr("Explain analyze: run the statement and show its actual plan"), ExplainAnalyze),
                    entry(tr("Save query (the first time: name it in Saved Queries)"), Save),
                    entry(tr("Save script to a file…"), SaveAs),
                ),
            ),
            Group(
                tr("SQL Editor"),
                listOf(
                    entry(tr("Code completion"), Completion),
                    entry(tr("Comment / uncomment lines"), Comment),
                    entry(tr("Duplicate line or selection"), Duplicate),
                    entry(tr("Delete line"), DeleteLine),
                    entry(tr("Move line up / down"), MoveLineUp, MoveLineDown),
                    entry(tr("Indent / outdent lines"), Indent, Outdent),
                    entry(tr("Reformat SQL"), Format),
                    entry(tr("Find"), Find),
                    entry(tr("Replace"), Replace),
                ),
            ),
            Group(
                tr("Data Grid"),
                listOf(
                    entry(tr("Copy cells"), Copy),
                    entry(tr("Copy cells with header"), CopyWithHeader),
                    entry(tr("Select all"), SelectAll),
                    Entry(tr("Edit cell"), "$EnterLabel  /  ${EditCell.label}"),
                    entry(tr("Expand the cell editor ({0} applies)", Submit.label), ExpandEditor),
                    entry(tr("Set cell to NULL"), SetNull),
                    entry(tr("Add row"), AddRow),
                    entry(tr("Delete rows"), DeleteRows),
                    entry(tr("Undo the last change to the data"), Undo),
                    entry(tr("Redo"), Redo, RedoAlt),
                    entry(tr("Revert rows: drop all their pending changes"), RevertRows),
                    entry(tr("Submit changes"), Submit, Save),
                    entry(tr("Reload table"), Reload),
                    entry(tr("Go to the row a foreign key points at"), GoToReferenced),
                ),
            ),
            Group(
                tr("Table Filter"),
                listOf(
                    entry(tr("Focus filter (adds a condition on the current column)"), FocusFilter),
                    entry(tr("Filter by the selected cell's value"), FilterByValue),
                    entry(tr("Exclude the selected cell's value"), ExcludeValue),
                    entry(tr("Switch between builder and SQL; show or hide a collection's query builder"), ToggleFilterMode),
                    entry(tr("Clear filter"), ClearFilter),
                    Entry(tr("Apply filter"), "$EnterLabel  /  ${ApplyFilter.label}"),
                    Entry(tr("Back to the grid"), "Esc"),
                ),
            ),
            Group(
                tr("Filter Builder"),
                listOf(
                    entry(tr("Add condition below"), AddCondition),
                    entry(tr("Add group below"), AddConditionGroup),
                    entry(tr("Add SQL condition below"), AddSqlCondition),
                    entry(tr("Duplicate condition"), Duplicate),
                    entry(tr("Remove condition"), DeleteLine),
                    entry(tr("Enable / disable condition"), Comment),
                    entry(tr("Move condition up / down"), MoveLineUp, MoveLineDown),
                    entry(tr("Previous / next condition"), PreviousCondition, NextCondition),
                    entry(tr("Choose column"), ChooseColumn),
                    entry(tr("Choose operator"), ChooseOperator),
                    entry(tr("Switch the group between all / any"), ToggleCombinator),
                    entry(tr("Match case"), MatchCase),
                ),
            ),
        )
}

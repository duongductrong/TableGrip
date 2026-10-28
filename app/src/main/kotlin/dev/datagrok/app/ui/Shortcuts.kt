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
import dev.datagrok.app.grid.isMac

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
}

private val EnterLabel = if (isMac) "⏎" else "Enter"
private val BackspaceLabel = if (isMac) "⌫" else "Backspace"

/** Every app shortcut in one place, so key handling, tooltips and the shortcuts dialog always agree. */
object Shortcuts {
    // Window
    val NewConsole = Shortcut(Key.T, "T", primary = true)
    val CloseTab = Shortcut(Key.W, "W", primary = true)
    val NextTab = if (isMac) Shortcut(Key.RightBracket, "]", primary = true, shift = true) else Shortcut(Key.PageDown, "PgDn", primary = true)
    val PreviousTab = if (isMac) Shortcut(Key.LeftBracket, "[", primary = true, shift = true) else Shortcut(Key.PageUp, "PgUp", primary = true)
    val NextTabAlt = Shortcut(Key.Tab, "Tab", ctrl = true)
    val PreviousTabAlt = Shortcut(Key.Tab, "Tab", ctrl = true, shift = true)
    val Explorer = if (isMac) Shortcut(Key.One, "1", primary = true) else Shortcut(Key.One, "1", alt = true)
    val History = if (isMac) Shortcut(Key.Two, "2", primary = true) else Shortcut(Key.Two, "2", alt = true)
    val Settings = if (isMac) Shortcut(Key.Comma, ",", primary = true) else Shortcut(Key.S, "S", primary = true, alt = true)
    val ShowShortcuts = Shortcut(Key.Slash, "/", primary = true, shift = true)

    // Console
    val RunStatement = Shortcut(Key.Enter, EnterLabel, primary = true)
    val RunScript = Shortcut(Key.Enter, EnterLabel, primary = true, shift = true)
    val Cancel = if (isMac) Shortcut(Key.Period, ".", primary = true) else Shortcut(Key.F2, "F2", primary = true)
    val Explain = Shortcut(Key.E, "E", primary = true, shift = true)
    val Save = Shortcut(Key.S, "S", primary = true)
    val SaveAs = Shortcut(Key.S, "S", primary = true, shift = true)

    // SQL editor
    val Completion = Shortcut(Key.Spacebar, "Space", ctrl = true)
    val Comment = Shortcut(Key.Slash, "/", primary = true)
    val Duplicate = Shortcut(Key.D, "D", primary = true)
    val DeleteLine = if (isMac) Shortcut(Key.Backspace, BackspaceLabel, primary = true) else Shortcut(Key.Y, "Y", primary = true)
    val MoveLineUp = Shortcut(Key.DirectionUp, "↑", alt = true, shift = true)
    val MoveLineDown = Shortcut(Key.DirectionDown, "↓", alt = true, shift = true)
    val Format = Shortcut(Key.L, "L", primary = true, alt = true)
    val Find = Shortcut(Key.F, "F", primary = true)
    val Replace = Shortcut(Key.R, "R", primary = true)
    val Indent = Shortcut(Key.Tab, "Tab")
    val Outdent = Shortcut(Key.Tab, "Tab", shift = true)

    // Data grid
    val Copy = Shortcut(Key.C, "C", primary = true)
    val CopyWithHeader = Shortcut(Key.C, "C", primary = true, shift = true)
    val SelectAll = Shortcut(Key.A, "A", primary = true)
    val EditCell = Shortcut(Key.F2, "F2")
    val SetNull = Shortcut(Key.N, "N", primary = true, alt = true)
    val AddRow = Shortcut(Key.N, "N", primary = true)
    val DeleteRows = Shortcut(Key.Backspace, BackspaceLabel, primary = true)
    val RevertRows = Shortcut(Key.Z, "Z", primary = true)
    val Submit = Shortcut(Key.Enter, EnterLabel, primary = true)
    val Reload = Shortcut(Key.R, "R", primary = true)

    class Entry(val description: String, val keys: String)

    class Group(val title: String, val entries: List<Entry>)

    private fun entry(description: String, vararg shortcuts: Shortcut) = Entry(description, shortcuts.joinToString("  /  ") { it.label })

    /** What the shortcuts dialog lists. */
    val groups: List<Group> =
        listOf(
            Group(
                "General",
                listOf(
                    entry("New console for the current connection", NewConsole),
                    entry("Close tab", CloseTab),
                    entry("Next tab", NextTab, NextTabAlt),
                    entry("Previous tab", PreviousTab, PreviousTabAlt),
                    entry("Database Explorer", Explorer),
                    entry("Query History", History),
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
                    entry("Save script", Save),
                    entry("Save script as…", SaveAs),
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
                    entry("Set cell to NULL", SetNull),
                    entry("Add row", AddRow),
                    entry("Delete rows", DeleteRows),
                    entry("Revert rows", RevertRows),
                    entry("Submit changes", Submit, Save),
                    entry("Reload table", Reload),
                ),
            ),
        )
}

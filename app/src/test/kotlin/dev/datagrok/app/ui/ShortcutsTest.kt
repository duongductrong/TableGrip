package dev.datagrok.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortcutsTest {
    /** Everything reachable while typing in a console: window, console and editor shortcuts share one key space. */
    private val consoleScope =
        with(Shortcuts) {
            listOf(
                NewConsole, CloseTab, NextTab, PreviousTab, NextTabAlt, PreviousTabAlt, Explorer, History, Settings, ShowShortcuts,
                RunStatement, RunScript, Cancel, Explain, Save, SaveAs,
                Completion, Comment, Duplicate, DeleteLine, MoveLineUp, MoveLineDown, Format, Find, Replace, Indent, Outdent,
            )
        }

    @Test
    fun `console shortcuts do not collide`() {
        val duplicates = consoleScope.groupBy { it.copy(keyLabel = "") }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "Colliding shortcuts: ${duplicates.values}")
    }

    @Test
    fun `every listed entry has keys`() {
        Shortcuts.groups.flatMap { it.entries }.forEach { assertTrue(it.keys.isNotBlank(), it.description) }
    }

    @Test
    fun `labels follow the platform convention`() {
        val label = Shortcuts.Format.label
        if (System.getProperty("os.name").startsWith("Mac")) assertEquals("⌥⌘L", label) else assertEquals("Ctrl+Alt+L", label)
    }
}

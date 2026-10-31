package dev.datagrok.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortcutsTest {
    /** Everything reachable while typing in a console: window, console and editor shortcuts share one key space. */
    private val consoleScope =
        with(Shortcuts) {
            listOf(
                NewConsole, CloseTab, NextTab, PreviousTab, NextTabAlt, PreviousTabAlt, Explorer, History, Agent, Settings, ShowShortcuts, AddToChat, ToggleSidebar,
                RunStatement, RunScript, Cancel, Explain, Save, SaveAs,
                Completion, Comment, Duplicate, DeleteLine, MoveLineUp, MoveLineDown, Format, Find, Replace, Indent, Outdent,
            )
        }

    @Test
    fun `console shortcuts do not collide`() {
        val duplicates = consoleScope.groupBy { it.copy(keyLabel = "") }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "Colliding shortcuts: ${duplicates.values}")
    }

    /** Everything reachable from a filter builder row: window, table-wide filter and row shortcuts. */
    private val builderScope =
        with(Shortcuts) {
            listOf(
                NewConsole, CloseTab, NextTab, PreviousTab, NextTabAlt, PreviousTabAlt, Explorer, History, Agent, Settings, ShowShortcuts, AddToChat, ToggleSidebar, Reload, Save,
                FocusFilter, ToggleFilterMode, ClearFilter,
                ApplyFilter, AddCondition, AddConditionGroup, AddSqlCondition, Duplicate, DeleteLine, Comment, MoveLineUp, MoveLineDown,
                PreviousCondition, NextCondition, ChooseColumn, ChooseOperator, ToggleCombinator, MatchCase,
            )
        }

    /** Keys the focused grid handles, plus the table-wide ones that must get past it. */
    private val gridScope =
        with(Shortcuts) {
            listOf(Copy, CopyWithHeader, SelectAll, EditCell, SetNull, AddRow, DeleteRows, RevertRows, Submit, FilterByValue, ExcludeValue, FocusFilter, ToggleFilterMode, ClearFilter)
        }

    @Test
    fun `filter shortcuts do not collide`() {
        for (scope in listOf(builderScope, gridScope)) {
            val duplicates = scope.groupBy { it.copy(keyLabel = "") }.filterValues { it.size > 1 }
            assertTrue(duplicates.isEmpty(), "Colliding shortcuts: ${duplicates.values}")
        }
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

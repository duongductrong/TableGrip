package dev.datagrok.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.datagrok.app.table.TableEditModel
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

@OptIn(ExperimentalTestApi::class)
class DataGridTest {
    private fun result(columns: Int, rows: Int) =
        QueryResult.Rows(
            columns = listOf(ResultColumn("id", "integer", Types.INTEGER)) + (1 until columns).map { ResultColumn("c$it", "text", Types.VARCHAR) },
            rows = (0 until rows).map { r -> listOf("$r") + (1 until columns).map { c -> "r${r}c$c" } },
            truncated = false,
            elapsedMillis = 1,
        )

    @Test
    fun `renders only the columns near the viewport`() = runComposeUiTest {
        val state = DataGridState()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(ResultGridModel(result(40, 50)), state) } } }

        onNodeWithText("c1").assertExists()
        onNodeWithText("c39").assertDoesNotExist()
        onNodeWithText("r0c1").assertExists()
        onNodeWithText("r49c1").assertDoesNotExist()
    }

    @Test
    fun `the agent chat key hands over the selected block`() = runComposeUiTest {
        val state = DataGridState()
        var mentioned: Triple<List<String>, IntRange, List<List<String?>>>? = null
        val actions = GridActions(onMention = { columns, rows, values -> mentioned = Triple(columns.map { it.name }, rows, values) })
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(ResultGridModel(result(4, 50)), state, actions = actions) } } }

        onNodeWithText("r1c1").performMouseInput { click(center) }
        onRoot().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown); pressKey(Key.DirectionRight) }
            withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.L) }
        }
        waitForIdle()
        assertEquals(Triple(listOf("c1", "c2"), 1..2, listOf(listOf("r1c1", "r1c2"), listOf("r2c1", "r2c2"))), mentioned)
    }

    @Test
    fun `click selects, keyboard moves and extends the selection`() = runComposeUiTest {
        val state = DataGridState()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(ResultGridModel(result(4, 50)), state) } } }

        onNodeWithText("r1c1").performMouseInput { click(center) }
        waitForIdle()
        assertEquals(GridCell(1, 1), state.focus)

        onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) }
        }
        waitForIdle()
        val selection = state.selection!!
        assertEquals(GridCell(2, 2), selection.anchor)
        assertEquals(GridCell(3, 2), selection.focus)
    }

    @Test
    fun `header click sorts, shift-click adds a sort`() = runComposeUiTest {
        val state = DataGridState()
        val calls = mutableListOf<Triple<String, SortDirection?, Boolean>>()
        setContent {
            IntUiTheme(isDark = false) {
                Box(Modifier.size(800.dp, 400.dp)) {
                    DataGrid(ResultGridModel(result(3, 5)), state, actions = GridActions(onSort = { c, d, a -> calls += Triple(c, d, a) }))
                }
            }
        }
        onNodeWithText("c1").performMouseInput { click(center) }
        waitForIdle()
        assertEquals(Triple("c1", SortDirection.Asc, false), calls.single())
    }

    @Test
    fun `double click edits a cell and enter commits it`() = runComposeUiTest {
        val state = DataGridState()
        val model = TableEditModel(result(3, 5), primaryKey = listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("r2c1").performMouseInput { doubleClick(center) }
        waitForIdle()
        assertEquals(GridCell(2, 1), state.editing)

        onNodeWithText("r2c1").performTextInput("!")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(null, state.editing)
        assertEquals("r2c1!", model.value(2, 1))
        assertTrue(model.isModified(2, 1))
        // Enter moves down, like a spreadsheet.
        assertEquals(GridCell(3, 1), state.focus)
        onNodeWithText("r2c1!").assertExists()
    }

    @Test
    fun `clicking inside the editor keeps editing`() = runComposeUiTest {
        val state = DataGridState()
        val model = TableEditModel(result(3, 5), primaryKey = listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("r2c1").performMouseInput { doubleClick(center) }
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        onNodeWithText("r2c1").performMouseInput { click(centerLeft + Offset(12f, 0f)) }
        waitForIdle()

        assertEquals(GridCell(2, 1), state.editing)
    }

    @Test
    fun `modifier and function keys alone do not start editing`() = runComposeUiTest {
        val state = DataGridState()
        val model = TableEditModel(result(3, 5), primaryKey = listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("r1c1").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput {
            pressKey(Key.ShiftLeft)
            pressKey(Key.ShiftRight)
            pressKey(Key.CapsLock)
            pressKey(Key.F5)
        }
        waitForIdle()
        assertEquals(null, state.editing)
        assertEquals(0, model.changeCount)

        // Shift-selecting still works after the bare Shift press.
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        waitForIdle()
        assertEquals(null, state.editing)
        assertEquals(GridCell(1, 1), state.selection!!.anchor)
        assertEquals(GridCell(2, 1), state.selection!!.focus)
    }

    @Test
    fun `only keys that type a character start editing`() {
        assertEquals("x", typedText('x'.code))
        assertEquals(" ", typedText(' '.code))
        assertEquals("é", typedText('é'.code))
        // Shift, Caps Lock, Fn… arrive from AWT as CHAR_UNDEFINED.
        assertEquals(null, typedText(java.awt.event.KeyEvent.CHAR_UNDEFINED.code))
        assertEquals(null, typedText(0))
        assertEquals(null, typedText(8))
        assertEquals(null, typedText(127))
        assertEquals(null, typedText(0xD800))
    }

    @Test
    fun `typing over a cell replaces it, and new rows show defaults`() = runComposeUiTest {
        val state = DataGridState()
        val model = TableEditModel(result(3, 5), primaryKey = listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("r0c2").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.X) }
        waitForIdle()
        assertEquals(GridCell(0, 2), state.editing)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("x", model.value(0, 2))

        onRoot().performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals(6, model.rowCount)
        assertEquals(RowStatus.Inserted, model.rowStatus(5))
        // One updated row plus one inserted row.
        assertEquals(2, model.changeCount)
    }
}

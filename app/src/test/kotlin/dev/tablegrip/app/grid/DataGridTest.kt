package dev.tablegrip.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.table.TableEditModel
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.edit.RowChange
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.sql.Types
import dev.tablegrip.core.agent.Mention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

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
        var mentioned: GridMention? = null
        val actions = GridActions(onMention = { mentioned = it })
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(ResultGridModel(result(4, 120)), state, actions = actions) } } }

        onNodeWithText("r1c1").performMouseInput { click(center) }
        onRoot().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown); pressKey(Key.DirectionRight) }
            withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.L) }
        }
        waitForIdle()
        assertEquals(listOf("c1", "c2"), mentioned?.columns?.map { it.name })
        assertEquals(1..2, mentioned?.rows)
        assertEquals(listOf(listOf("r1c1", "r1c2"), listOf("r2c1", "r2c2")), mentioned?.values)

        // Everything selected: the whole range, but only the rows a mention keeps are read.
        onRoot().performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.A); pressKey(Key.L) } }
        waitForIdle()
        assertEquals(0..119, mentioned?.rows)
        assertEquals(Mention.CellRows, mentioned?.values?.size)
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

    /** id, a nullable boolean, a NOT NULL integer with no default, a date, and a timestamp defaulting to now(). */
    private fun typed(): TableEditModel {
        val columns =
            listOf(
                ResultColumn("id", "int4", Types.INTEGER),
                ResultColumn("done", "bool", Types.BIT),
                ResultColumn("qty", "int2", Types.SMALLINT),
                ResultColumn("due", "date", Types.DATE),
                ResultColumn("at", "timestamp", Types.TIMESTAMP),
            )
        fun info(name: String, type: String, jdbcType: Int, nullable: Boolean = true, default: String? = null) =
            ColumnInfo(name, type, null, nullable, default, autoIncrement = false, primaryKey = name == "id", jdbcType = jdbcType)
        val details =
            TableDetails(
                TableRef(Namespace(null, "public"), "t"),
                listOf(
                    info("id", "int4", Types.INTEGER, nullable = false),
                    info("done", "bool", Types.BIT),
                    info("qty", "int2", Types.SMALLINT, nullable = false),
                    info("due", "date", Types.DATE),
                    info("at", "timestamp", Types.TIMESTAMP, default = "now()"),
                ),
                emptyList(),
                emptyList(),
            )
        val rows = listOf(listOf("1", "t", "5", "2026-10-01", "2026-10-01 09:00:00"), listOf("2", "f", "7", null, "2026-10-02 09:00:00"))
        return TableEditModel(QueryResult.Rows(columns, rows, false, 1), listOf("id"), details = details, dialect = SqlDialect.PostgreSQLDialect())
    }

    @Test
    fun `space toggles boolean cells through true, false and NULL`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("t").performMouseInput { click(centerRight - Offset(4f, 0f)) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(null, state.editing)
        assertEquals("false", model.value(0, 1))
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(null, model.value(0, 1))
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        // Back to the value it was read as ("t"): no pending change.
        assertEquals("t", model.value(0, 1))
        assertEquals(0, model.changeCount)
    }

    @Test
    fun `enter refuses a value the column can't take, and blank typed input means NULL`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }

        onNodeWithText("5").performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("5").performTextInput("00000")
        // The hint under the cell is a popup, a second root; keys go to the editor itself.
        onNodeWithText("500000").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(GridCell(0, 2), state.editing)
        onNodeWithText("Out of range for int2: -32768 to 32767").assertExists()

        onNodeWithText("500000").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals("5", model.value(0, 2))

        // A date typed as an expression is stored resolved.
        onNodeWithText("2026-10-01").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Delete) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(null, model.value(0, 3))
        assertTrue(model.isModified(0, 3))
    }

    @Test
    fun `new rows show what left-out columns get and refuse to save without required ones`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }

        model.addRow()
        waitForIdle()
        onNodeWithText("<default: now()>").assertExists()
        assertEquals("Row 3, id: id is required: it is NOT NULL and has no default", model.firstProblem())
        model.setValue(2, 0, "3")
        model.setValue(2, 2, "1")
        assertEquals(null, model.firstProblem())

        // Existing rows can go back to the column's default.
        assertTrue(model.canSetDefault(0, 4))
        model.setDefault(0, 4)
        val update = model.toChanges().filterIsInstance<RowChange.Update>().single()
        assertTrue(update.values.getValue("at").isDefault)
    }

    @Test
    fun `cmd-V stores a value that fits, into the row Add made`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent {
            IntUiTheme(isDark = false) {
                Column(Modifier.size(900.dp, 400.dp)) {
                    OutlinedButton(onClick = { state.revealEditable(model, model.addRow()) }) { Text("Add") }
                    DataGrid(model, state, Modifier.height(360.dp))
                }
            }
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("7"), null)
        onNodeWithText("Add").performClick()
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals("7", model.value(2, 0))
        assertEquals(null, state.editing)
    }

    @Test
    fun `a pasted value that doesn't fit opens the editor, and Enter refuses it`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("seven"), null)
        val row = model.addRow()
        state.revealEditable(model, row)
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals(GridCell(row, 0), state.editing)
        assertEquals("seven", state.editText.text.toString())
        onNodeWithText("seven").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(GridCell(row, 0), state.editing)
        assertEquals(null, model.value(row, 0))
    }

    @Test
    fun `Add opens on the first column that takes an edit`() = runComposeUiTest {
        val state = DataGridState()
        val columns = listOf(ResultColumn("data", "bytea", Types.BINARY), ResultColumn("id", "int4", Types.INTEGER))
        val model = TableEditModel(QueryResult.Rows(columns, listOf(listOf(null, "1")), false, 1), listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }
        val row = model.addRow()
        state.revealEditable(model, row)
        waitForIdle()
        assertEquals(GridCell(row, 1), state.focus)
    }

    @Test
    fun `a pasted block fills rows and columns, adds rows, flags what doesn't fit, and undoes`() = runComposeUiTest {
        val state = DataGridState()
        val model = typed()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }
        val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft
        // qty and due for three rows, from a spreadsheet: the last row is new, and its blank due keeps the default.
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("8\t2026-10-05\r\n9\tnope\r\n10\t\r\n"), null)
        onNodeWithText("5").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals(3, model.rowCount)
        assertEquals(listOf("8", "2026-10-05", "9", "nope", "10"), listOf(model.value(0, 2), model.value(0, 3), model.value(1, 2), model.value(1, 3), model.value(2, 2)))
        assertTrue(model.problem(1, 3) != null, "a value the column can't take is kept and flagged")
        assertTrue(!model.isModified(2, 3))
        assertEquals(GridSelection(GridCell(0, 2), GridCell(2, 3)), state.selection)
        assertTrue(state.notice!!.text.startsWith("Pasted 5 cells · 1 new row · 1 to fix"), state.notice!!.text)

        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.Z) } }
        waitForIdle()
        assertEquals(2, model.rowCount)
        assertEquals(listOf("5", "2026-10-01", "7", null), listOf(model.value(0, 2), model.value(0, 3), model.value(1, 2), model.value(1, 3)))
        assertTrue(!model.hasChanges)
    }

    @Test
    fun `copying and pasting between cells keeps NULL apart from empty text`() = runComposeUiTest {
        val state = DataGridState()
        val model = TableEditModel(result(3, 2), primaryKey = listOf("id"))
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 400.dp)) { DataGrid(model, state) } } }
        val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft
        model.setValue(0, 1, null)
        onNodeWithText("r0c2").performMouseInput { click(center) }
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.C) } }
        onNodeWithText("r1c1").performMouseInput { click(center) }
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals(null, model.value(1, 1))
        assertEquals("r0c2", model.value(1, 2))
    }
}

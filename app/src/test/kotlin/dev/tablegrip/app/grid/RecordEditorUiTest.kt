package dev.tablegrip.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.table.TableEditModel
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RecordEditorUiTest {
    private fun model() =
        TableEditModel(
            QueryResult.Rows(
                listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("name", "varchar"), ResultColumn("price", "numeric", Types.NUMERIC)),
                listOf(listOf("1", "Lamp", "19.90"), listOf("2", "Desk", "120.00")),
                false,
                1,
            ),
            listOf("id"),
        )

    @Test
    fun `each record in the JSON view has an edit button that opens it`() = runComposeUiTest {
        val model = model()
        val state = DataGridState()
        setContent { TableGripTheme(isDark = false) { Box(Modifier.size(700.dp, 400.dp)) { ResultJsonView(model, state) } } }
        waitUntil { onAllNodesWithTag(RecordEditTag).fetchSemanticsNodes().size == 2 }
        onNodeWithText("Add Row…").assertIsEnabled()
        onAllNodesWithTag(RecordEditTag)[1].performClick()
        waitForIdle()
        val request = assertNotNull(state.recordEdit)
        assertEquals(1, request.row)
        assertEquals("\"Desk\"", request.base["name"].toString())

        onNodeWithText("Add Row…").performClick()
        waitForIdle()
        assertNull(state.recordEdit?.row, "Add opens a new record")
    }

    @Test
    fun `typing a change shows what it does and Apply stages it`() = runComposeUiTest {
        val model = model()
        val state = DataGridState()
        state.editRecord(model, 0)
        val request = state.recordEdit!!
        var applied: Int? = null
        setContent {
            TableGripTheme(isDark = false) {
                Box(Modifier.size(700.dp, 500.dp)) { RecordEditorPane(request, model, "public.items", onClose = { state.recordEdit = null }, onApplied = { row, _ -> applied = row }) }
            }
        }
        waitForIdle()
        onNodeWithText("id = 1").assertExists()
        waitUntil { runCatching { onNodeWithText("No changes yet", substring = true).fetchSemanticsNode() }.isSuccess }
        onNodeWithText("Apply").assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextReplacement("{\n  \"id\": 1,\n  \"name\": \"Floor lamp\",\n  \"price\": 19.9\n}\n")
        waitUntil { runCatching { onNodeWithText("Changes name", substring = true).fetchSemanticsNode() }.isSuccess }
        onNodeWithText("Apply").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(0, applied)
        assertEquals("Floor lamp", model.value(0, 1))
        assertEquals("19.90", model.value(0, 2), "19.9 is the same number: not rewritten")
    }

    @Test
    fun `broken JSON is pointed at and can't be applied`() = runComposeUiTest {
        val model = model()
        val state = DataGridState()
        state.editRecord(model, 0)
        val request = state.recordEdit!!
        setContent { TableGripTheme(isDark = false) { Box(Modifier.size(700.dp, 500.dp)) { RecordEditorPane(request, model, "public.items", onClose = {}, onApplied = { _, _ -> }) } } }
        waitForIdle()
        onNode(hasSetTextAction()).performTextReplacement("{\n  \"name\": \"Lamp\"\n  \"price\": 1\n}")
        waitUntil { runCatching { onNodeWithText("Line 3", substring = true).fetchSemanticsNode() }.isSuccess }
        onNodeWithText("Apply").assertIsNotEnabled()
        onNodeWithText("Format").assertIsNotEnabled()
        onNodeWithText("Show").assertExists()
        assertFalse(model.hasChanges)
    }

    @Test
    fun `Esc with unapplied edits warns first, then closes`() = runComposeUiTest {
        val model = model()
        val state = DataGridState()
        state.editRecord(model, 0)
        val request = state.recordEdit!!
        var closed = false
        setContent { TableGripTheme(isDark = false) { Box(Modifier.size(700.dp, 500.dp)) { RecordEditorPane(request, model, "public.items", onClose = { closed = true }, onApplied = { _, _ -> }) } } }
        waitForIdle()
        val editor = onNode(hasSetTextAction())
        editor.performTextReplacement("{\"name\": \"x\"}")
        waitForIdle()
        editor.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(closed)
        onNodeWithText("Press Esc again to discard your edits").assertExists()
        editor.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(closed)
    }

    @Test
    fun `a page reloaded under the record can't be applied to the new one`() = runComposeUiTest {
        val model = model()
        val state = DataGridState()
        state.editRecord(model, 0)
        val request = state.recordEdit!!
        val reloaded = model()
        setContent { TableGripTheme(isDark = false) { Box(Modifier.size(700.dp, 500.dp)) { RecordEditorPane(request, reloaded, "public.items", onClose = {}, onApplied = { _, _ -> }) } } }
        waitForIdle()
        onNodeWithText("The page reloaded", substring = true).assertExists()
        onNodeWithText("Copy the JSON").assertExists()
        onNodeWithText("Apply").assertIsNotEnabled()
    }

}

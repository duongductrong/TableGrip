package dev.tablegrip.app.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.table.TableEditModel
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

@OptIn(ExperimentalTestApi::class)
class JsonTreeEditorUiTest {
    private val original = """{"name":"An","age":30,"price":19.90,"tags":["x"]}"""

    private fun model() =
        TableEditModel(
            QueryResult.Rows(listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("meta", "jsonb", Types.OTHER)), listOf(listOf("1", original)), false, 1),
            primaryKey = listOf("id"),
        )

    private val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft

    @Test
    fun `a value changed in the tree is saved as valid JSON, leaving the rest as it was`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 500.dp)) { DataGrid(model, state) } } }

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("30").performMouseInput { click(center) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText("30")).performTextReplacement("31")
        onNode(hasSetTextAction() and hasText("31")).performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("\"An\"").performMouseInput { click(center) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText("An")).performTextReplacement("Bình \"B\"")
        onNodeWithText("tags:").performKeyInput { withKeyDown(primary) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(null, state.editing)
        assertEquals("""{"name":"Bình \"B\"","age":31,"price":19.90,"tags":["x"]}""", model.value(0, 1))
    }

    @Test
    fun `a number that isn't JSON is refused and nothing changes`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 500.dp)) { DataGrid(model, state) } } }

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("30").performMouseInput { click(center) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText("30")).performTextReplacement("3,1")
        onNode(hasSetTextAction() and hasText("3,1")).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText("3,1")).assertExists()
        onNode(hasSetTextAction() and hasText("3,1")).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("tags:").performKeyInput { withKeyDown(primary) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(null, state.editing)
        assertEquals(original, model.value(0, 1))
        assertFalse(model.isModified(0, 1))
    }

    @Test
    fun `opening the tree and closing it leaves the value untouched`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 500.dp)) { DataGrid(model, state) } } }

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("tags:").assertExists()
        onNodeWithText("tags:").performKeyInput { withKeyDown(primary) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(original, model.value(0, 1))
        assertFalse(model.isModified(0, 1))
    }

    @Test
    fun `a click selects a row at once, without waiting out the double-click window`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 500.dp)) { DataGrid(model, state) } } }

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithText("age:").performMouseInput { click(center) }
        // One frame: well inside any double-click timeout.
        mainClock.advanceTimeByFrame()
        onNodeWithText("$.age").assertExists()
        // A second click right after is a double-click: on a key, it opens the rename.
        onNodeWithText("age:").performMouseInput { click(center) }
        mainClock.advanceTimeByFrame()
        mainClock.autoAdvance = true
        waitForIdle()
        onNode(hasSetTextAction() and hasText("age")).assertExists()
    }
}


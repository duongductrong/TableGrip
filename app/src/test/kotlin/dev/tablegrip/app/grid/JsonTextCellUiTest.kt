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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ComposeUiTest
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
import kotlin.test.assertTrue
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

/** JSON kept in a plain text column: edited in the tree, but always saved as the column's text. */
@OptIn(ExperimentalTestApi::class)
class JsonTextCellUiTest {
    private val original = """{"name": "An", "age": 30, "price": 19.90}"""

    private fun model(value: String = original) =
        TableEditModel(
            QueryResult.Rows(listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("note", "text", Types.VARCHAR)), listOf(listOf("1", value)), false, 1),
            primaryKey = listOf("id"),
        )

    private val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft

    private fun ComposeUiTest.show(model: TableEditModel, state: DataGridState) {
        setContent { IntUiTheme(isDark = false) { Box(Modifier.size(900.dp, 500.dp)) { DataGrid(model, state) } } }
    }

    private fun ComposeUiTest.setAge(from: String, to: String) {
        onNodeWithText(from).performMouseInput { click(center) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText(from)).performTextReplacement(to)
        onNode(hasSetTextAction() and hasText(to)).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    private fun ComposeUiTest.apply() {
        onNodeWithText("price:").performKeyInput { withKeyDown(primary) { pressKey(Key.Enter) } }
        waitForIdle()
    }

    @Test
    fun `a text cell holding JSON opens as a tree and saves the edit as text`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        show(model, state)

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("Saved as text", substring = true).assertExists()
        setAge("30", "31")
        apply()

        assertEquals(null, state.editing)
        assertEquals("""{"name":"An","age":31,"price":19.90}""", model.value(0, 1))
        assertTrue(model.isModified(0, 1))
    }

    @Test
    fun `opening the tree and closing it leaves the text untouched`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        show(model, state)

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        apply()

        assertEquals(original, model.value(0, 1))
        assertFalse(model.isModified(0, 1))
    }

    @Test
    fun `an edit changed back restores the exact original text`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        show(model, state)

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        setAge("30", "31")
        setAge("31", "30")
        apply()

        assertEquals(original, model.value(0, 1))
        assertFalse(model.isModified(0, 1))
    }

    @Test
    fun `text that stops being JSON is still saved as typed`() = runComposeUiTest {
        val state = DataGridState()
        val model = model()
        show(model, state)

        onNodeWithText(original).performMouseInput { doubleClick(center) }
        waitForIdle()
        onNodeWithText("Text").performMouseInput { click(center) }
        waitForIdle()
        onNode(hasSetTextAction() and hasText(original) and isFocused()).performTextReplacement("not json {")
        onNode(hasSetTextAction() and hasText("not json {") and isFocused()).performKeyInput { withKeyDown(primary) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(null, state.editing)
        assertEquals("not json {", model.value(0, 1))
    }

    @Test
    fun `plain text and JSON scalars keep the one-line editor`() = runComposeUiTest {
        val values = listOf("hello", "42", "{not json}")
        val state = DataGridState()
        val model =
            TableEditModel(
                QueryResult.Rows(
                    listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("note", "text", Types.VARCHAR)),
                    values.mapIndexed { i, value -> listOf("${i + 1}", value) },
                    false,
                    values.size.toLong(),
                ),
                primaryKey = listOf("id"),
            )
        show(model, state)
        for (value in values) {
            onNodeWithText(value).performMouseInput { doubleClick(center) }
            waitForIdle()
            assertEquals(EditPopup.None, state.editPopup, value)
            onNodeWithText("Saved as text", substring = true).assertDoesNotExist()
            onNode(hasSetTextAction() and hasText(value)).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
        }
    }
}

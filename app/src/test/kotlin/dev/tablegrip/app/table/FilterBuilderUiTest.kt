package dev.tablegrip.app.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeysDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.GridCell
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.filter.Combinator
import dev.tablegrip.core.filter.FilterOperator
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the filter builder in a real table viewer over SQLite. With `-Psnapshot.dir=/tmp` it also saves screenshots of
 * the bar in both themes.
 */
@OptIn(ExperimentalTestApi::class)
class FilterBuilderUiTest {
    private val home = createTempDirectory("tablegrip-filter")
    private val db = home.resolve("shop.db").toString()

    init {
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { s ->
                s.executeUpdate(
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, status TEXT, total NUMERIC, paid BOOLEAN, created_at DATETIME)"
                )
                s.executeUpdate(
                    """
                    INSERT INTO orders VALUES
                      (1, 'Alice', 'shipped', 120.5, 1, '2026-10-01 09:15:00'),
                      (2, 'Bob',   'pending', 35,    0, '2026-10-12 18:30:00'),
                      (3, 'Carol', 'shipped', 980,   1, '2026-09-03 11:00:00'),
                      (4, 'Dave',  'refunded', 15,   1, '2026-10-20 08:45:00'),
                      (5, 'Erin',  'pending', 410,   0, NULL)
                    """.trimIndent()
                )
            }
        }
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"${db.toString().replace('\\', '/')}"}]""")
    }

    private fun rows(tab: TableDataTab) = (0 until tab.model!!.rowCount).map { tab.model!!.value(it, 0)!!.toLong() }

    @Test
    fun `builds, validates and applies a typed filter`() {
        val out = System.getProperty("snapshot.dir")
        for (dark in if (out == null) listOf(false) else listOf(true, false)) {
            val app =
                AppState(
                    settingsStore = SettingsStore(home.resolve("settings-$dark.json")),
                    connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                    history = QueryHistory(home.resolve("history.jsonl")),
                )
            runComposeUiTest(testTimeout = 5.minutes) {
                setContent { TableGripTheme(isDark = dark) { Box(Modifier.size(1300.dp, 640.dp)) { MainScreen(app) } } }
                val config = app.connections.profiles.single()
                runOnUiThread { app.workspace.openTable(config, TableRef(Namespace(null, null), "orders")) }
                waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null && (app.workspace.selected as TableDataTab).details != null }
                val tab = app.workspace.selected as TableDataTab
                assertEquals(FilterMode.Builder, tab.filterMode)

                // An empty bar invites a first condition.
                onNodeWithText("Add condition").performClick()
                waitForIdle()
                assertEquals(1, tab.builder.root.children.size)
                val first = tab.builder.root.children.single() as ConditionState
                assertEquals("id", first.column)

                // The column picker searches as you type and picks with Enter.
                // The column list beside the conditions names it too, but only the picker is clickable.
                onAllNodes(hasText("id", substring = false) and hasClickAction()).onFirst().performClick()
                waitForIdle()
                onNode(isFocused() and hasSetTextAction()).performTextInput("tot")
                waitForIdle()
                out?.let {
                    Thread.sleep(300)
                    ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(it, "filter-column-picker-${if (dark) "dark" else "light"}.png"))
                }
                onNode(isFocused() and hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
                waitForIdle()
                assertEquals("total", first.column)
                assertEquals(FilterOperator.Equals, first.operator)

                runOnUiThread {
                    val builder = tab.builder
                    val columns = tab.filterColumns.associateBy { it.name }
                    builder.clear()
                    builder.addCondition(builder.root, columns.getValue("status")).apply {
                        operator = FilterOperator.In
                        value.setTextAndPlaceCursorAtEnd("shipped, pending")
                    }
                    builder.addCondition(builder.root, columns.getValue("total")).apply {
                        operator = FilterOperator.Between
                        value.setTextAndPlaceCursorAtEnd("30")
                        value2.setTextAndPlaceCursorAtEnd("abc")
                    }
                    val group = builder.addGroup(builder.root, columns.getValue("paid"))
                    builder.addCondition(group, columns.getValue("created_at")).apply {
                        operator = FilterOperator.GreaterOrEqual
                        value.setTextAndPlaceCursorAtEnd("2026-10-10")
                    }
                    builder.addRaw(builder.root, "length(customer) > 2")
                }
                waitForIdle()
                // A bad bound blocks applying and is reported next to its row.
                onNodeWithText("Apply").performClick()
                waitForIdle()
                assertTrue(tab.showFilterIssues)
                onNodeWithText("“abc” isn't a number").assertExists()
                onNodeWithText("1 condition needs attention").assertExists()
                out?.let { save(it, "filter-invalid", dark) }

                runOnUiThread { (tab.builder.root.children[1] as ConditionState).value2.setTextAndPlaceCursorAtEnd("500") }
                waitForIdle()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.appliedTree != null }
                waitForIdle()
                // status in (shipped, pending) AND total in [30, 500] AND (paid OR created_at >= Oct 10) AND length > 2
                assertEquals(listOf(1L, 2L), rows(tab).sorted())
                assertEquals(Combinator.Any, (tab.builder.root.children[2] as GroupState).combinator)
                assertTrue(!tab.isFilterDirty)
                onNodeWithText("Edit as SQL").assertExists()
                out?.let { save(it, "filter-applied", dark) }

                // Switching to SQL carries the conditions over as an editable WHERE clause.
                runOnUiThread { tab.switchFilterMode(FilterMode.Sql) }
                waitForIdle()
                assertTrue(tab.filter.text.startsWith("\"status\" IN ('shipped', 'pending')"))
                out?.let { save(it, "filter-sql", dark) }

                runOnUiThread { tab.clearFilter() }
                waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.model?.rowCount == 5 }
                runOnUiThread { tab.switchFilterMode(FilterMode.Builder) }
                waitForIdle()
                // The cleared SQL leaves nothing behind in the builder.
                assertTrue(tab.builder.isEmpty)
            }
            app.dispose()
        }
    }

    @Test
    fun `builds and applies a filter from the keyboard`() {
        val app =
            AppState(
                settingsStore = SettingsStore(home.resolve("settings-keys.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = QueryHistory(home.resolve("history.jsonl")),
            )
        runComposeUiTest(testTimeout = 5.minutes) {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1300.dp, 640.dp)) { MainScreen(app) } } }
            val config = app.connections.profiles.single()
            runOnUiThread { app.workspace.openTable(config, TableRef(Namespace(null, null), "orders")) }
            waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null && (app.workspace.selected as TableDataTab).details != null }
            val tab = app.workspace.selected as TableDataTab
            val builder = tab.builder
            val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft
            // Key is a value class, so no varargs: modifiers come as a list.
            fun keys(key: Key, modifiers: List<Key> = emptyList()) {
                onNode(isFocused()).performKeyInput { withKeysDown(modifiers) { pressKey(key) } }
                waitForIdle()
            }
            fun type(text: String) {
                onNode(isFocused() and hasSetTextAction()).performTextInput(text)
                waitForIdle()
            }
            fun focusedText() = onNode(isFocused() and hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

            // From the grid, ⌘F starts a first condition with the caret in its value.
            runOnUiThread { tab.grid.requestFocus() }
            waitForIdle()
            keys(Key.F, listOf(primary))
            assertEquals(1, builder.rows().size)
            type("1")
            // ⌘N adds the next one below, ⌘D duplicates it, ⌘⌫ (Ctrl+Y) takes the copy away again.
            keys(Key.N, listOf(primary))
            type("4")
            keys(Key.D, listOf(primary))
            assertEquals(listOf("1", "4", "4"), builder.rows().map { (it as ConditionState).value.text.toString() })
            if (isMac) keys(Key.Backspace, listOf(primary)) else keys(Key.Y, listOf(primary))
            assertEquals(2, builder.rows().size)
            assertEquals("4", focusedText())

            // ↑ steps to the first row, ⌘/ disables it, ⇧⌘A flips the group's combinator (twice: back to all).
            keys(Key.DirectionUp)
            assertEquals("1", focusedText())
            keys(Key.Slash, listOf(primary))
            assertTrue(!builder.rows().first().enabled)
            keys(Key.A, listOf(primary, Key.ShiftLeft))
            assertEquals(Combinator.Any, builder.root.combinator)
            keys(Key.A, listOf(primary, Key.ShiftLeft))
            assertEquals(Combinator.All, builder.root.combinator)
            // ⌥⇧↓ moves the row below its sibling.
            keys(Key.DirectionDown, listOf(Key.AltLeft, Key.ShiftLeft))
            assertEquals(listOf("4", "1"), builder.rows().map { (it as ConditionState).value.text.toString() })

            keys(Key.Enter, listOf(primary))
            waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.appliedTree != null }
            assertEquals(listOf(4L), rows(tab))

            // Esc goes back to the grid, where ⌥⇧⌘F excludes the focused cell's value.
            keys(Key.Escape)
            onNode(isFocused() and hasSetTextAction()).assertDoesNotExist()
            runOnUiThread { tab.grid.select(GridCell(0, 2)) }
            keys(Key.F, listOf(primary, Key.AltLeft, Key.ShiftLeft))
            waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.model?.rowCount == 0 }
            val excluded = builder.rows().last() as ConditionState
            assertEquals(Triple("status", FilterOperator.NotEquals, "refunded"), Triple(excluded.column, excluded.operator, excluded.value.text.toString()))

            // ⇧⌘⌫ clears it all, ⇧⌘F switches to SQL with the caret in the field.
            keys(Key.Backspace, listOf(primary, Key.ShiftLeft))
            waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.model?.rowCount == 5 }
            assertTrue(builder.isEmpty)
            keys(Key.F, listOf(primary, Key.ShiftLeft))
            assertEquals(FilterMode.Sql, tab.filterMode)
            onNode(isFocused() and hasSetTextAction()).assertExists()
        }
        app.dispose()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(dir: String, name: String, dark: Boolean) {
        Thread.sleep(300)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
    }
}

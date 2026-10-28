package dev.datagrok.app.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.datagrok.app.completion.CompletionSource
import dev.datagrok.app.grid.isMac
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.completion.ColumnEntry
import dev.datagrok.core.completion.Relation
import dev.datagrok.core.completion.SchemaProvider
import dev.datagrok.core.completion.SqlCompleter
import dev.datagrok.core.completion.TableEntry
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.sql.SqlSyntax
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SqlEditorTest {
    private val schema =
        object : SchemaProvider {
            override val defaultNamespace = "public"

            override fun namespaces() = listOf("public")

            override fun tables(namespace: String?) = listOf(TableEntry("users"), TableEntry("orders"))

            override fun columns(namespace: String?, table: String) =
                when (table) {
                    "users" -> listOf(ColumnEntry("id", "int4", true), ColumnEntry("name", "text"), ColumnEntry("email", "text"))
                    "orders" -> listOf(ColumnEntry("id", "int4", true), ColumnEntry("user_id", "int4"), ColumnEntry("total", "numeric"))
                    else -> null
                }

            override fun relations(namespace: String?, table: String) = listOf(Relation("orders", listOf("user_id"), "users", listOf("id")))
        }

    private val source =
        SqlCompleter(SqlDialect.PostgreSQL).let { completer ->
            CompletionSource({ text, caret -> completer.complete(text, caret, schema) }, SqlDialect.PostgreSQL, cache = null)
        }

    private val primary = if (isMac) Key.MetaLeft else Key.CtrlLeft

    private fun ComposeUiTest.editor() = onAllNodes(hasSetTextAction())[0]

    /** The window plus any popup layers (which are separate roots), composited. */
    private fun ComposeUiTest.snapshot(file: File) {
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it].captureToImage().toAwtImage() }
        val base = roots.first()
        val out = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            roots.forEach { drawImage(it, 0, 0, null) }
            dispose()
        }
        ImageIO.write(out, "png", file)
    }

    private fun editorState(text: String) = TextFieldState(text).apply { edit { selection = TextRange(length) } }

    @Test
    fun `ctrl-space completes a table and enter accepts it`() = runComposeUiTest {
        val state = editorState("SELECT * FROM us")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(800.dp, 400.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
        editor().performClick()
        editor().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForIdle()
        editor().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("SELECT * FROM users", state.text.toString())
    }

    @Test
    fun `join suggestions use foreign keys`() = runComposeUiTest {
        val state = editorState("SELECT *\nFROM users u\nJOIN ")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(900.dp, 420.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
        editor().performClick()
        editor().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForIdle()
        System.getProperty("snapshot.dir")?.let { snapshot(File(it, "editor-completion.png")) }
        editor().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("SELECT *\nFROM users u\nJOIN orders o ON o.user_id = u.id", state.text.toString())
    }

    @Test
    fun `comment toggling and formatting through shortcuts`() = runComposeUiTest {
        val state = editorState("select id,name from users where id=1")
        setContent { DatagrokTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
        editor().performClick()
        editor().performKeyInput { withKeyDown(primary) { pressKey(Key.Slash) } }
        waitForIdle()
        assertTrue(state.text.startsWith("-- select"), state.text.toString())
        editor().performKeyInput { withKeyDown(primary) { pressKey(Key.Slash) } }
        editor().performKeyInput { withKeyDown(primary) { withKeyDown(Key.AltLeft) { pressKey(Key.L) } } }
        waitForIdle()
        assertEquals("SELECT\n    id,\n    name\nFROM users\nWHERE id = 1", state.text.toString())
        System.getProperty("snapshot.dir")?.let { snapshot(File(it, "editor-formatted.png")) }
    }
}

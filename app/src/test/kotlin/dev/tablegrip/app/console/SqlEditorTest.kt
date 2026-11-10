package dev.tablegrip.app.console

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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.completion.ColumnEntry
import dev.tablegrip.core.completion.Relation
import dev.tablegrip.core.completion.SchemaProvider
import dev.tablegrip.core.completion.SqlCompleter
import dev.tablegrip.core.completion.TableEntry
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.sql.SqlSyntax
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(800.dp, 400.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 420.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
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
        setContent { TableGripTheme(isDark = false) { Box(Modifier.size(800.dp, 400.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
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

    @Test
    fun `gutter stays put on the frame right after an edit`() = runComposeUiTest {
        val state = editorState("SELECT 1;\n\nSELECT 2")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(600.dp, 300.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13) } } }
        editor().performClick()
        waitForIdle()
        // The line-number column only: its pixels must not move while typing on the same line.
        fun gutter() = onAllNodes(isRoot())[0].captureToImage().toAwtImage().let { it.getSubimage(0, 0, 30, it.height) }
        val before = gutter()
        mainClock.autoAdvance = false
        editor().performTextInput("x")
        mainClock.advanceTimeByFrame()
        val sameFrame = gutter()
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals("SELECT 1;\n\nSELECT 2x", state.text.toString())
        assertTrue(samePixels(before, sameFrame), "gutter shifted on the frame after typing")
        assertTrue(samePixels(before, gutter()), "gutter shifted after typing")
    }

    @Test
    fun `quotes and brackets are paired once, also when text comes from an input method`() = runComposeUiTest {
        val state = editorState("SELECT * FROM t WHERE a = 'x' and ")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(800.dp, 400.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13, completion = source) } } }
        editor().performClick()
        state.edit { selection = TextRange(length) }
        // performTextInput commits text the way an input method does, without key events.
        editor().performTextInput("'")
        waitForIdle()
        assertEquals("SELECT * FROM t WHERE a = 'x' and ''", state.text.toString())
        assertEquals(TextRange(state.text.length - 1), state.selection)
        editor().performTextInput("b")
        editor().performTextInput("'")
        waitForIdle()
        assertEquals("SELECT * FROM t WHERE a = 'x' and 'b'", state.text.toString(), "the closing quote is stepped over")
        assertEquals(TextRange(state.text.length), state.selection)
        editor().performTextInput(" in ")
        editor().performTextInput("(")
        waitForIdle()
        assertEquals("SELECT * FROM t WHERE a = 'x' and 'b' in ()", state.text.toString())
    }

    @Test
    fun `caret is glyph-tall, centred in its line and blinks`() = runComposeUiTest {
        val state = TextFieldState("\n")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(600.dp, 300.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, 13) } } }
        editor().performClick()
        waitForIdle()
        mainClock.autoAdvance = false

        /** Caret pixels: what's on screen with the caret shown minus the same frame half a blink later. */
        fun caretRows(line: Int): IntRange {
            state.edit { selection = TextRange(line) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val on = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            System.getProperty("snapshot.dir")?.let { ImageIO.write(on, "png", File(it, "editor-caret-line$line.png")) }
            mainClock.advanceTimeBy(600)
            val off = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
            val rows = (0 until on.height).filter { y -> (0 until on.width).any { x -> on.getRGB(x, y) != off.getRGB(x, y) } }
            assertTrue(rows.isNotEmpty(), "no caret drawn on line $line")
            return rows.first()..rows.last()
        }
        val first = caretRows(0)
        val second = caretRows(1)
        val pitch = second.first - first.first
        val height = first.last - first.first + 1
        assertEquals(height, second.last - second.first + 1)
        assertTrue(height < pitch, "caret ($height px) should be shorter than the line pitch ($pitch px)")
        val lineCentre = 8 + pitch / 2f
        val caretCentre = first.first + height / 2f
        assertTrue(kotlin.math.abs(caretCentre - lineCentre) <= 1.5f, "caret centre $caretCentre vs line centre $lineCentre")
        mainClock.autoAdvance = true
    }

    @Test
    fun `line index counts newlines before the offset`() {
        assertEquals(0, lineIndexAt("abc", 2))
        assertEquals(1, lineIndexAt("a\nb", 2))
        assertEquals(2, lineIndexAt("a\n\n", 3))
        assertEquals(2, lineIndexAt("a\n\n", 99))
    }

    private fun samePixels(a: BufferedImage, b: BufferedImage): Boolean =
        a.width == b.width && a.height == b.height && (0 until a.width).all { x -> (0 until a.height).all { y -> a.getRGB(x, y) == b.getRGB(x, y) } }
}

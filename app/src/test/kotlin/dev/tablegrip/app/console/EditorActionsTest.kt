package dev.tablegrip.app.console

import androidx.compose.ui.text.TextRange
import dev.tablegrip.core.sql.SqlSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Notation: `|` is the caret; `[` and `]` delimit a selection. */
class EditorActionsTest {
    private fun state(marked: String): EditorText {
        val caret = marked.indexOf('|')
        if (caret >= 0) return EditorText(marked.removeRange(caret, caret + 1), caret)
        val start = marked.indexOf('[')
        val end = marked.indexOf(']') - 1
        return EditorText(marked.replace("[", "").replace("]", ""), TextRange(start, end))
    }

    private fun EditorText.marked(): String =
        if (selection.collapsed) text.substring(0, selection.start) + "|" + text.substring(selection.start)
        else text.substring(0, selection.min) + "[" + text.substring(selection.min, selection.max) + "]" + text.substring(selection.max)

    private val pg = SqlSyntax.PostgreSQL

    @Test
    fun `toggles line comments at the common indentation`() {
        assertEquals("-- SELECT |1", EditorActions.toggleLineComment(state("SELECT |1")).marked())
        assertEquals("SELECT |1", EditorActions.toggleLineComment(state("-- SELECT |1")).marked())
        assertEquals("[  -- a\n  --   b]\nx", EditorActions.toggleLineComment(state("  [a\n    b\n]x")).marked())
        assertEquals("[-- a\n\n-- b]", EditorActions.toggleLineComment(state("[a\n\nb]")).marked(), "blank lines stay blank")
        assertEquals("[-- a\n-- -- b]", EditorActions.toggleLineComment(state("[a\n-- b]")).marked(), "mixed lines all get commented")
    }

    @Test
    fun `duplicates the line or the selection`() {
        assertEquals("SELECT 1\nSELECT |1\nx", EditorActions.duplicate(state("SELECT |1\nx")).marked())
        assertEquals("ab[b]c", EditorActions.duplicate(state("a[b]c")).marked())
    }

    @Test
    fun `deletes and moves lines`() {
        assertEquals("a\n|c", EditorActions.deleteLines(state("a\nb|\nc")).marked())
        assertEquals("a|", EditorActions.deleteLines(state("a\nb|")).marked())
        assertEquals("b|\na\nc", EditorActions.moveLines(state("a\nb|\nc"), up = true).marked())
        assertEquals("a\nc\nb|", EditorActions.moveLines(state("a\nb|\nc"), up = false).marked())
        assertEquals("a|\nb", EditorActions.moveLines(state("a|\nb"), up = true).marked(), "first line can't move up")
    }

    @Test
    fun `indents and outdents blocks`() {
        assertEquals("[    a\n    b]", EditorActions.indent(state("[a\nb]"), outdent = false).marked())
        assertEquals("[a\n  b]", EditorActions.indent(state("[    a\n      b]"), outdent = true).marked())
        assertEquals("a|", EditorActions.indent(state("    a|"), outdent = true).marked())
    }

    @Test
    fun `pairs brackets and quotes, steps over closers, wraps selections`() {
        assertEquals("count(|)", EditorActions.typeChar(state("count|"), '(', pg)!!.marked())
        assertEquals("count()|", EditorActions.typeChar(state("count(|)"), ')', pg)!!.marked())
        assertEquals("x = '|'", EditorActions.typeChar(state("x = |"), '\'', pg)!!.marked())
        assertEquals("x = 'ab'|", EditorActions.typeChar(state("x = 'ab|'"), '\'', pg)!!.marked())
        assertNull(EditorActions.typeChar(state("it|"), '\'', pg), "no pairing after a letter (it's)")
        assertNull(EditorActions.typeChar(state("|abc"), '(', pg), "no pairing before a word")
        assertEquals("([a b])", EditorActions.typeChar(state("[a b]"), '(', pg)!!.marked())
        assertEquals("f|", EditorActions.backspace(state("f(|)"))!!.marked())
        assertNull(EditorActions.backspace(state("f(|x)")))
    }

    @Test
    fun `finds matching brackets outside strings`() {
        val text = "f(a, g(b), ')') + 1"
        assertEquals(1 to 14, EditorActions.matchingBracket(text, 2, pg))
        assertEquals(6 to 8, EditorActions.matchingBracket(text, 9, pg))
        assertNull(EditorActions.matchingBracket("x", 1, pg))
    }

    @Test
    fun `finds all matches`() {
        assertEquals(listOf(0..1, 6..7), EditorActions.findAll("ab cd AB", "ab", matchCase = false))
        assertEquals(listOf(0..1), EditorActions.findAll("ab cd AB", "ab", matchCase = true))
    }
}

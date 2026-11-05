package dev.tablegrip.app.console

import androidx.compose.ui.text.TextRange
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlSyntax
import dev.tablegrip.core.sql.TokenType

/** Editor text plus selection, before or after an action. */
data class EditorText(val text: String, val selection: TextRange) {
    constructor(text: String, caret: Int) : this(text, TextRange(caret))
}

/**
 * IntelliJ-style editing actions as pure functions, so they're easy to test and reuse. Lines are always whole lines
 * touched by the selection.
 */
object EditorActions {
    private const val Indent = "    "

    /** ⌘/ — comments the lines out with `-- `, or uncomments them if they all are. */
    fun toggleLineComment(state: EditorText): EditorText {
        val (from, to) = lineRange(state)
        val lines = state.text.substring(from, to).split('\n')
        val code = lines.filter { it.isNotBlank() }
        val uncomment = code.isNotEmpty() && code.all { it.trimStart().startsWith("--") }
        val minIndent = code.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        var delta = 0
        val changed =
            lines.joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    uncomment -> {
                        val at = line.indexOf("--")
                        val remove = if (line.startsWith("-- ", at)) 3 else 2
                        delta -= remove
                        line.removeRange(at, at + remove)
                    }
                    else -> {
                        delta += 3
                        line.substring(0, minIndent) + "-- " + line.substring(minIndent)
                    }
                }
            }
        val text = state.text.replaceRange(from, to, changed)
        // Keep the caret on its line; extend a selection over the changed block.
        return if (state.selection.collapsed) {
            val single = lines.size == 1
            EditorText(text, TextRange((state.selection.start + if (single) delta else 0).coerceIn(from, from + changed.length)))
        } else {
            EditorText(text, TextRange(from, from + changed.length))
        }
    }

    /** ⌘D — duplicates the selection, or the current line when nothing is selected. */
    fun duplicate(state: EditorText): EditorText {
        val sel = state.selection
        if (!sel.collapsed) {
            val chunk = state.text.substring(sel.min, sel.max)
            return EditorText(state.text.substring(0, sel.max) + chunk + state.text.substring(sel.max), TextRange(sel.max, sel.max + chunk.length))
        }
        val (from, to) = lineRange(state)
        val line = state.text.substring(from, to)
        val text = state.text.substring(0, to) + "\n" + line + state.text.substring(to)
        return EditorText(text, TextRange(sel.start + line.length + 1))
    }

    /** ⌘⌫ — deletes the lines touched by the selection. */
    fun deleteLines(state: EditorText): EditorText {
        val (from, to) = lineRange(state)
        val end = if (to < state.text.length) to + 1 else to
        val start = if (end == to && from > 0) from - 1 else from
        val text = state.text.removeRange(start, end)
        return EditorText(text, TextRange(start.coerceAtMost(text.length)))
    }

    /** ⌥⇧↑ / ⌥⇧↓ — moves the selected lines past the neighbouring line. */
    fun moveLines(state: EditorText, up: Boolean): EditorText {
        val (from, to) = lineRange(state)
        val text = state.text
        if (up && from == 0 || !up && to >= text.length) return state
        val block = text.substring(from, to)
        return if (up) {
            val prevStart = text.lastIndexOf('\n', from - 2) + 1
            val previous = text.substring(prevStart, from - 1)
            val moved = text.substring(0, prevStart) + block + "\n" + previous + text.substring(to)
            val shift = -(previous.length + 1)
            EditorText(moved, TextRange(state.selection.start + shift, state.selection.end + shift))
        } else {
            val nextEnd = text.indexOf('\n', to + 1).let { if (it < 0) text.length else it }
            val next = text.substring(to + 1, nextEnd)
            val moved = text.substring(0, from) + next + "\n" + block + text.substring(nextEnd)
            val shift = next.length + 1
            EditorText(moved, TextRange(state.selection.start + shift, state.selection.end + shift))
        }
    }

    /** Tab with a multi-line selection indents every line; ⇧Tab outdents (also for a single line). */
    fun indent(state: EditorText, outdent: Boolean, unit: String = Indent): EditorText {
        val (from, to) = lineRange(state)
        val lines = state.text.substring(from, to).split('\n')
        // Outdenting takes one tab, or up to one indent's worth of spaces (a tab indent counts as four).
        val spaces = if (unit == "\t") Indent.length else unit.length
        var firstDelta = 0
        var total = 0
        val changed =
            lines.mapIndexed { index, line ->
                val delta =
                    if (outdent) (if (line.startsWith("\t")) -1 else -line.takeWhile { it == ' ' }.length.coerceAtMost(spaces))
                    else if (line.isEmpty()) 0
                    else unit.length
                if (index == 0) firstDelta = delta
                total += delta
                if (delta > 0) unit + line else if (delta == 0) line else line.substring(-delta)
            }.joinToString("\n")
        val text = state.text.replaceRange(from, to, changed)
        val sel = state.selection
        return if (sel.collapsed) EditorText(text, TextRange((sel.start + firstDelta).coerceAtLeast(from)))
        // A selection of whole lines stays whole lines, as in IntelliJ.
        else EditorText(text, TextRange(if (sel.min == from) from else (sel.min + firstDelta).coerceAtLeast(from), sel.max + total))
    }

    private val Pairs = mapOf('(' to ')', '[' to ']', '\'' to '\'', '"' to '"', '`' to '`')

    /**
     * Auto-pairing for a typed [char]: wraps a selection, inserts the closing character, or steps over a closing
     * character that's already there. Returns null when the default typing behaviour should apply.
     */
    fun typeChar(state: EditorText, char: Char, syntax: SqlSyntax): EditorText? {
        val sel = state.selection
        val text = state.text
        val next = text.getOrNull(sel.end)
        val closing = Pairs[char]
        val isQuote = char == '\'' || char == '"' || char == '`'
        // Step over a closer the editor inserted.
        if (sel.collapsed && next == char && (char in Pairs.values)) {
            if (!isQuote || insideLiteral(text, sel.start, syntax)) return EditorText(text, TextRange(sel.start + 1))
        }
        if (closing == null) return null
        if (!sel.collapsed) {
            val wrapped = char + text.substring(sel.min, sel.max) + closing
            return EditorText(text.replaceRange(sel.min, sel.max, wrapped), TextRange(sel.min + 1, sel.max + 1))
        }
        if (isQuote) {
            // Don't pair inside words (it's) or inside an existing literal.
            val prev = text.getOrNull(sel.start - 1)
            if (prev != null && (prev.isLetterOrDigit() || prev == char) || insideLiteral(text, sel.start, syntax)) return null
        }
        if (next != null && (next.isLetterOrDigit() || next == '_')) return null
        return EditorText(text.substring(0, sel.start) + char + closing + text.substring(sel.start), TextRange(sel.start + 1))
    }

    /** Backspace between an empty pair, e.g. `(|)`, deletes both characters. */
    fun backspace(state: EditorText): EditorText? {
        val sel = state.selection
        if (!sel.collapsed || sel.start == 0) return null
        val prev = state.text[sel.start - 1]
        val next = state.text.getOrNull(sel.start) ?: return null
        if (Pairs[prev] != next) return null
        return EditorText(state.text.removeRange(sel.start - 1, sel.start + 1), TextRange(sel.start - 1))
    }

    /** The bracket next to the caret and its partner as (opening, closing), ignoring strings and comments. */
    fun matchingBracket(text: String, caret: Int, syntax: SqlSyntax): Pair<Int, Int>? {
        val tokens = SqlLexer(syntax).tokenize(text).filter { it.type == TokenType.Operator && (text[it.start] == '(' || text[it.start] == ')') }
        val at = tokens.firstOrNull { it.start == caret - 1 } ?: tokens.firstOrNull { it.start == caret } ?: return null
        val index = tokens.indexOf(at)
        val opening = text[at.start] == '('
        var depth = 0
        val range = if (opening) index until tokens.size else index downTo 0
        for (i in range) {
            depth += if (text[tokens[i].start] == '(') 1 else -1
            if (depth == 0) return minOf(at.start, tokens[i].start) to maxOf(at.start, tokens[i].start)
        }
        return null
    }

    /** All matches of [query] (plain text) in [text]. */
    fun findAll(text: String, query: String, matchCase: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = !matchCase)
            if (at < 0) break
            result += at until at + query.length
            from = at + query.length.coerceAtLeast(1)
        }
        return result
    }

    /** Start of the first line and end of the last line touched by the selection. */
    private fun lineRange(state: EditorText): Pair<Int, Int> {
        val text = state.text
        val sel = state.selection
        // A selection ending at the start of a line doesn't include that line.
        val endPos = if (!sel.collapsed && sel.max > 0 && text[sel.max - 1] == '\n') sel.max - 1 else sel.max
        val from = text.lastIndexOf('\n', sel.min - 1).let { if (it < 0) 0 else it + 1 }
        val to = text.indexOf('\n', endPos).let { if (it < 0) text.length else it }
        return from to to
    }

    private fun insideLiteral(text: String, offset: Int, syntax: SqlSyntax): Boolean =
        SqlLexer(syntax).tokenize(text).any {
            (it.type == TokenType.String || it.type == TokenType.QuotedIdentifier || it.type == TokenType.Comment) && offset > it.start && offset < it.end
        }
}

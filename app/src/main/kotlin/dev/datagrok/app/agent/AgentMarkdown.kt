package dev.datagrok.app.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** The parts of an agent's Markdown the chat renders: prose, and fenced code that gets its own block. */
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock

    /** [runRequest]: the agent asks Datagrok to run this (a ```sql run fence) rather than suggesting it. */
    data class Code(val language: String, val code: String, val runRequest: Boolean = false) : MarkdownBlock {
        val isSql: Boolean
            get() = language.lowercase() in SqlLanguages
    }
}

private val SqlLanguages = setOf("sql", "postgresql", "postgres", "mysql", "sqlite", "tsql", "plsql", "psql")
private val InlineCode = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x28888888))
private val Inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

/**
 * A small Markdown subset: fenced code, headings, bullets, **bold** and `code`. Tolerates a fence that hasn't closed yet,
 * since answers are rendered while they stream. [open]: the text is still arriving, so a `**` or `` ` `` the last
 * paragraph hasn't closed yet styles what follows it instead of showing as a stray mark.
 */
internal fun parseMarkdown(source: String, open: Boolean = false): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = mutableListOf<String>()
    fun flushProse(last: Boolean = false) {
        val text = prose.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(inline(text, open && last))
        prose.clear()
    }
    val lines = source.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            flushProse()
            val info = line.trimStart().removePrefix("```").trim().split(Regex("\\s+"))
            val language = info.first()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) code += lines[index++]
            blocks += MarkdownBlock.Code(language, code.joinToString("\n"), runRequest = info.drop(1).any { it.equals("run", ignoreCase = true) })
        } else if (line.isBlank()) {
            flushProse()
        } else {
            prose += line
        }
        index++
    }
    flushProse(last = true)
    return blocks
}

/**
 * Parses an answer as it streams without redoing what has settled. Text before the last blank line outside a fence
 * can't change as more is appended, so its blocks are kept and only the tail is parsed again on each frame; together
 * they are exactly what [parseMarkdown] makes of the whole.
 */
internal class StreamingMarkdown {
    private var settled = ""
    private var settledBlocks = emptyList<MarkdownBlock>()

    fun parse(source: String, open: Boolean): List<MarkdownBlock> {
        // A final answer that replaced what streamed: what had settled is gone.
        if (!source.startsWith(settled)) {
            settled = ""
            settledBlocks = emptyList()
        }
        val end = settledEnd(source, settled.length)
        if (end > settled.length) {
            settledBlocks = settledBlocks + parseMarkdown(source.substring(settled.length, end))
            settled = source.substring(0, end)
        }
        return settledBlocks + parseMarkdown(source.substring(settled.length), open)
    }

    /** Just past the last complete blank line outside a fence, scanning from [from], which is such a point too. */
    private fun settledEnd(source: String, from: Int): Int {
        var end = from
        var start = from
        var fenced = false
        while (true) {
            val newline = source.indexOf('\n', start)
            if (newline < 0) return end
            val line = source.substring(start, newline)
            if (line.trimStart().startsWith("```")) fenced = !fenced
            else if (!fenced && line.isBlank()) end = newline + 1
            start = newline + 1
        }
    }
}

private fun inline(text: String, open: Boolean = false): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    lines.forEachIndexed { number, raw ->
        if (number > 0) append('\n')
        val tail = open && number == lines.lastIndex
        val heading = Regex("^#{1,6}\\s+").find(raw)
        val bullet = Regex("^(\\s*)[-*+]\\s+").find(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(raw.substring(heading.range.last + 1), tail) }
            bullet != null -> {
                append(bullet.groupValues[1] + "•  ")
                appendInline(raw.substring(bullet.range.last + 1), tail)
            }
            else -> appendInline(raw, tail)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String, open: Boolean = false) {
    var position = 0
    for (match in Inline.findAll(text)) {
        append(text, position, match.range.first)
        val (bold, code) = match.destructured
        if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        else withStyle(InlineCode) { append(code) }
        position = match.range.last + 1
    }
    if (!open) {
        append(text, position, text.length)
        return
    }
    // Still streaming: styles what an unclosed mark has started, and holds back a lone `*` that may be half of `**`.
    val rest = text.substring(position).removeSuffix("*")
    val bold = rest.indexOf("**")
    val code = rest.indexOf('`')
    when {
        bold >= 0 && (code < 0 || bold < code) -> {
            append(rest, 0, bold)
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(bold + 2)) }
        }
        code >= 0 -> {
            append(rest, 0, code)
            withStyle(InlineCode) { append(rest.substring(code + 1)) }
        }
        else -> append(rest)
    }
}

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
 * since answers are rendered while they stream.
 */
internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = mutableListOf<String>()
    fun flushProse() {
        val text = prose.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(inline(text))
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
    flushProse()
    return blocks
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { number, raw ->
        if (number > 0) append('\n')
        val heading = Regex("^#{1,6}\\s+").find(raw)
        val bullet = Regex("^(\\s*)[-*+]\\s+").find(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(raw.substring(heading.range.last + 1)) }
            bullet != null -> {
                append(bullet.groupValues[1] + "•  ")
                appendInline(raw.substring(bullet.range.last + 1))
            }
            else -> appendInline(raw)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var position = 0
    for (match in Inline.findAll(text)) {
        append(text, position, match.range.first)
        val (bold, code) = match.destructured
        if (bold.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        else withStyle(InlineCode) { append(code) }
        position = match.range.last + 1
    }
    append(text, position, text.length)
}

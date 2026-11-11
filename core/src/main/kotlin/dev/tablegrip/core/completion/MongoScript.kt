package dev.tablegrip.core.completion

import dev.tablegrip.core.sql.Token
import dev.tablegrip.core.sql.TokenType

/** A command in a Mongo shell script: its [text] without comments, and where it sits in the script. */
data class MongoStatement(val text: String, val start: Int, val end: Int)

/**
 * Mongo shell scripts as the console's editor reads them: tokens to colour, and the commands in them. A command ends at
 * a `;`, or at the end of a line once its brackets are closed, unless the next line goes on with `.` (a chained
 * `.sort(…)`), so a command may span lines.
 */
object MongoScript {
    /** Shell words, and the constructors values are written with. */
    private val Keywords =
        setOf(
            "db", "show", "use", "true", "false", "null", "undefined", "new", "it", "exit",
            "ObjectId", "ISODate", "Date", "NumberLong", "NumberInt", "NumberDecimal", "Timestamp", "UUID", "BinData", "RegExp", "MinKey", "MaxKey",
        )

    /** Never fails: unterminated strings and comments run to the end. `$operators` come back as [TokenType.Parameter]. */
    fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            val c = text[i]
            val next = text.getOrNull(i + 1)
            val type: TokenType
            when {
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    type = TokenType.Whitespace
                }
                c == '/' && next == '/' -> {
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    type = TokenType.Comment
                }
                c == '/' && next == '*' -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    type = TokenType.Comment
                }
                c == '\'' || c == '"' || c == '`' -> {
                    i = skipString(text, i)
                    type = TokenType.String
                }
                c.isDigit() || c == '.' && next?.isDigit() == true -> {
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
                    type = TokenType.Number
                }
                c == '$' && next != null && (next.isLetter() || next == '_' || next == '$') -> {
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '.')) i++
                    type = TokenType.Parameter
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    // Only as a word of its own: `db.use` names a collection, not the shell's `use`.
                    type = if (text.substring(start, i) in Keywords && text.getOrNull(start - 1) != '.') TokenType.Keyword else TokenType.Identifier
                }
                else -> {
                    i++
                    type = TokenType.Operator
                }
            }
            tokens += Token(type, start, i)
        }
        return tokens
    }

    /** The commands in [text], in order. */
    fun split(text: String): List<MongoStatement> {
        val statements = ArrayList<MongoStatement>()
        val code = StringBuilder()
        var start = -1
        var end = -1
        var depth = 0

        fun finish() {
            if (start >= 0) statements += MongoStatement(code.toString().trim(), start, end)
            code.clear()
            start = -1
            depth = 0
        }

        for (token in tokenize(text)) {
            val value = text.substring(token.start, token.end)
            when (token.type) {
                TokenType.Comment -> continue
                TokenType.Whitespace -> {
                    if (start < 0) continue
                    if (depth <= 0 && '\n' in value && !continues(text, token.end)) finish() else code.append(value)
                    continue
                }
                TokenType.Operator ->
                    when (value) {
                        ";" -> if (depth <= 0) {
                            finish()
                            continue
                        }
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> depth--
                    }
                else -> Unit
            }
            if (start < 0) start = token.start
            end = token.end
            code.append(value)
        }
        finish()
        return statements
    }

    /** Whether the line from [offset] goes on with the command above it: a chained `.method(…)`. */
    private fun continues(text: String, offset: Int): Boolean {
        var i = offset
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && text.getOrNull(i + 1) == '/' -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                c == '/' && text.getOrNull(i + 1) == '*' -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                else -> return c == '.' && text.getOrNull(i + 1)?.isDigit() != true
            }
        }
        return false
    }

    /**
     * The command at [caret]: the one around it, else the one just before it on the same line or with only blank space
     * between, as ⌘⏎ runs it.
     */
    fun statementAt(text: String, caret: Int): MongoStatement? {
        val statements = split(text)
        statements.firstOrNull { caret in it.start..it.end }?.let { return it }
        return statements.lastOrNull { it.end <= caret && text.substring(it.end, caret).isBlank() }
    }

    private fun skipString(text: CharSequence, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return text.length
    }
}

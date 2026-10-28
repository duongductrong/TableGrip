package dev.datagrok.core.sql

enum class TokenType { Whitespace, Comment, Keyword, Identifier, QuotedIdentifier, String, Number, Parameter, Operator }

data class Token(val type: TokenType, val start: Int, val end: Int) {
    fun text(source: CharSequence): String = source.substring(start, end)
}

/** A forgiving SQL tokenizer: never fails, unterminated strings and comments run to the end of input. */
class SqlLexer(private val syntax: SqlSyntax) {

    fun tokenize(sql: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < sql.length) {
            val start = i
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            val type: TokenType
            when {
                c.isWhitespace() -> {
                    while (i < sql.length && sql[i].isWhitespace()) i++
                    type = TokenType.Whitespace
                }
                c == '-' && next == '-' || c == '#' && syntax.hashComments -> {
                    i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                    type = TokenType.Comment
                }
                c == '/' && next == '*' -> {
                    i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    type = TokenType.Comment
                }
                c == '\'' -> {
                    i = skipQuoted(sql, i, '\'', syntax.backslashEscapes)
                    type = TokenType.String
                }
                c == '"' -> {
                    i = skipQuoted(sql, i, '"', syntax.backslashEscapes && syntax.doubleQuotedStrings)
                    type = if (syntax.doubleQuotedStrings) TokenType.String else TokenType.QuotedIdentifier
                }
                c == '[' && syntax.bracketIdentifiers -> {
                    // `]]` inside the brackets is an escaped bracket.
                    i++
                    while (i < sql.length) {
                        if (sql[i] == ']') {
                            if (sql.getOrNull(i + 1) == ']') i += 2 else { i++; break }
                        } else i++
                    }
                    type = TokenType.QuotedIdentifier
                }
                c == '`' && syntax.backtickIdentifiers -> {
                    i = skipQuoted(sql, i, '`', backslashEscapes = false)
                    type = TokenType.QuotedIdentifier
                }
                c == '$' && syntax.dollarQuotedStrings && dollarTag(sql, i) != null -> {
                    val tag = dollarTag(sql, i)!!
                    i = sql.indexOf(tag, i + tag.length).let { if (it < 0) sql.length else it + tag.length }
                    type = TokenType.String
                }
                c == '$' && next?.isDigit() == true -> {
                    i++
                    while (i < sql.length && sql[i].isDigit()) i++
                    type = TokenType.Parameter
                }
                c.isDigit() || c == '.' && next?.isDigit() == true -> {
                    i = skipNumber(sql, i)
                    type = TokenType.Number
                }
                c.isLetter() || c == '_' -> {
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                    type = if (sql.substring(start, i).uppercase() in SqlKeywords.all) TokenType.Keyword else TokenType.Identifier
                }
                c == ':' && next != null && (next.isLetter() || next == '_') && sql.getOrNull(i - 1) != ':' -> {
                    i++
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    type = TokenType.Parameter
                }
                c == '?' -> {
                    i++
                    type = TokenType.Parameter
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

    private fun skipQuoted(sql: CharSequence, start: Int, quote: Char, backslashEscapes: Boolean): Int {
        var i = start + 1
        while (i < sql.length) {
            val c = sql[i]
            when {
                backslashEscapes && c == '\\' -> i += 2
                c == quote && sql.getOrNull(i + 1) == quote -> i += 2
                c == quote -> return i + 1
                else -> i++
            }
        }
        return sql.length
    }

    private fun skipNumber(sql: CharSequence, start: Int): Int {
        var i = start
        while (i < sql.length && (sql[i].isDigit() || sql[i] == '.')) i++
        if (i < sql.length && (sql[i] == 'e' || sql[i] == 'E')) {
            val sign = if (sql.getOrNull(i + 1) == '+' || sql.getOrNull(i + 1) == '-') 1 else 0
            if (sql.getOrNull(i + 1 + sign)?.isDigit() == true) {
                i += 1 + sign
                while (i < sql.length && sql[i].isDigit()) i++
            }
        }
        return i
    }

    /** Returns the `$tag$` opening a dollar-quoted string at [start], if any. */
    private fun dollarTag(sql: CharSequence, start: Int): String? {
        var i = start + 1
        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) {
            if (i == start + 1 && sql[i].isDigit()) return null
            i++
        }
        return if (sql.getOrNull(i) == '$') sql.substring(start, i + 1) else null
    }
}

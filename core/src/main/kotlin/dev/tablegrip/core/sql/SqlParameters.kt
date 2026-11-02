package dev.tablegrip.core.sql

/**
 * Named placeholders in a script, as DataGrip recognises them: `:name` and `${name}`, outside strings, comments and
 * quoted identifiers (`a::int` is a cast, not a parameter). Values replace them as written, so text values need their
 * quotes: `'paid'`, while `42` or `now()` go in as they are.
 */
class SqlParameters(syntax: SqlSyntax) {
    private val lexer = SqlLexer(syntax)

    private data class Occurrence(val name: String, val start: Int, val end: Int)

    /** Distinct parameter names in order of first appearance. */
    fun find(sql: String): List<String> = occurrences(sql).map { it.name }.distinct()

    /** [sql] with each parameter replaced by its value; parameters without one are left as they are. */
    fun substitute(sql: String, values: Map<String, String>): String {
        val out = StringBuilder(sql.length)
        var last = 0
        for (occurrence in occurrences(sql)) {
            val value = values[occurrence.name] ?: continue
            out.append(sql, last, occurrence.start).append(value)
            last = occurrence.end
        }
        return out.append(sql, last, sql.length).toString()
    }

    private fun occurrences(sql: String): List<Occurrence> {
        val tokens = lexer.tokenize(sql)
        return buildList {
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.type == TokenType.Parameter && sql[token.start] == ':' -> add(Occurrence(token.text(sql).drop(1), token.start, token.end))
                    // `${name}` lexes as `$`, `{`, the name, `}`.
                    token.type == TokenType.Operator && sql[token.start] == '$' && sql.getOrNull(token.end) == '{' -> {
                        val close = sql.indexOf('}', token.end)
                        val name = if (close > 0) sql.substring(token.end + 1, close).trim() else ""
                        if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                            add(Occurrence(name, token.start, close + 1))
                            while (i + 1 < tokens.size && tokens[i + 1].start <= close) i++
                        }
                    }
                }
                i++
            }
        }
    }
}

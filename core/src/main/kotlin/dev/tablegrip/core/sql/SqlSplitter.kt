package dev.tablegrip.core.sql

/**
 * A statement inside a script. [start]/[end] cover the statement text, without leading/trailing comments and the
 * delimiter; [delimiterEnd] includes the delimiter (equals [end] for a trailing statement without one).
 */
data class SqlStatement(val text: String, val start: Int, val end: Int, val delimiterEnd: Int)

/**
 * Splits scripts into statements, respecting quotes, comments, dollar quoting, MySQL's `DELIMITER` command, T-SQL's `GO`
 * and `BEGIN ... END` bodies inside `CREATE` statements (e.g. SQLite triggers).
 */
class SqlSplitter(private val syntax: SqlSyntax) {
    private val lexer = SqlLexer(syntax)

    fun split(sql: String): List<SqlStatement> {
        val tokens = lexer.tokenize(sql)
        val statements = mutableListOf<SqlStatement>()
        var delimiter = ";"
        var start = -1
        var end = -1
        var skipUntil = 0
        var isCreate = false
        var blockDepth = 0

        fun finish(delimiterEnd: Int) {
            if (start >= 0) statements += SqlStatement(sql.substring(start, end), start, end, delimiterEnd)
            start = -1
            isCreate = false
            blockDepth = 0
        }

        for (token in tokens) {
            if (token.start < skipUntil) continue
            if (token.type == TokenType.Whitespace || token.type == TokenType.Comment) continue

            val isCode = token.type == TokenType.Operator || token.type == TokenType.Keyword || token.type == TokenType.Identifier
            if (isCreate && token.type == TokenType.Keyword) {
                when (token.text(sql).uppercase()) {
                    "BEGIN",
                    "CASE" -> blockDepth++
                    "END" -> if (blockDepth > 0) blockDepth--
                }
            }
            if (isCode && blockDepth == 0 && sql.startsWith(delimiter, token.start)) {
                finish(token.start + delimiter.length)
                skipUntil = token.start + delimiter.length
                continue
            }
            if (syntax.batchSeparator && token.text(sql).equals("GO", ignoreCase = true) && aloneOnLine(sql, token.start, token.end)) {
                finish(token.end)
                skipUntil = sql.indexOf('\n', token.end).let { if (it < 0) sql.length else it }
                continue
            }
            if (syntax.delimiterCommand && start < 0 && token.text(sql).equals("DELIMITER", ignoreCase = true)) {
                val lineEnd = sql.indexOf('\n', token.end).let { if (it < 0) sql.length else it }
                sql.substring(token.end, lineEnd).trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }?.let {
                    delimiter = it
                }
                skipUntil = lineEnd
                continue
            }
            if (start < 0) {
                start = token.start
                isCreate = token.text(sql).equals("CREATE", ignoreCase = true)
            }
            end = token.end
        }
        finish(end)
        return statements
    }

    /** Whether only whitespace (or a `GO 5`-style count) shares the line with [start]..[end]. */
    private fun aloneOnLine(sql: String, start: Int, end: Int): Boolean {
        val lineStart = sql.lastIndexOf('\n', start - 1) + 1
        val lineEnd = sql.indexOf('\n', end).let { if (it < 0) sql.length else it }
        return sql.substring(lineStart, start).isBlank() && sql.substring(end, lineEnd).trim().all { it.isDigit() }
    }

    /**
     * The statement to run for a caret at [offset]: the one containing it, else the one just before it on the same
     * stretch of whitespace (caret right after `;`), else the next one.
     */
    fun statementAt(sql: String, offset: Int): SqlStatement? {
        val statements = split(sql)
        statements.firstOrNull { offset in it.start..it.delimiterEnd }?.let { return it }
        statements.lastOrNull { it.delimiterEnd <= offset && sql.substring(it.delimiterEnd, offset).none { c -> c == '\n' } }
            ?.let { return it }
        return statements.lastOrNull { it.delimiterEnd <= offset && sql.substring(it.delimiterEnd, offset).isBlank() }
            ?: statements.firstOrNull { it.start >= offset && sql.substring(offset, it.start).isBlank() }
    }
}

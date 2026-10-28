package dev.datagrok.core.sql

/**
 * Reformats SQL in a conventional layout: upper-case keywords, one clause per line, multi-item lists one item per
 * line, AND/OR conditions on their own lines, and indented subqueries. Works on tokens, so strings, comments, quoted
 * identifiers and dollar-quoted bodies are never altered.
 */
class SqlFormatter(syntax: SqlSyntax, private val indent: String = "    ") {
    private val lexer = SqlLexer(syntax)

    fun format(sql: String): String {
        val tokens = merge(sql, lexer.tokenize(sql).filter { it.type != TokenType.Whitespace })
        if (tokens.isEmpty()) return sql.trim()
        return Writer(tokens).run().trimEnd()
    }

    /** A token with its final text (keywords upper-cased, adjacent operator characters joined). */
    private data class Tok(val type: TokenType, val text: String, val newlineBefore: Boolean) {
        val upper = text.uppercase()

        fun isOp(vararg ops: String) = type == TokenType.Operator && text in ops

        val isWord: Boolean
            get() = type == TokenType.Keyword || type == TokenType.Identifier
    }

    private fun merge(sql: String, tokens: List<Token>): List<Tok> {
        val result = mutableListOf<Tok>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val newline = i > 0 && sql.substring(tokens[i - 1].end, token.start).contains('\n')
            if (token.type == TokenType.Operator && token.text(sql) !in Single) {
                // Join "<", "=" into "<=", ":" ":" into "::"... when written without a gap.
                var end = token.end
                var j = i + 1
                while (j < tokens.size && tokens[j].type == TokenType.Operator && tokens[j].start == end && tokens[j].text(sql) !in Single) {
                    end = tokens[j].end
                    j++
                }
                result += Tok(TokenType.Operator, sql.substring(token.start, end), newline)
                i = j
                continue
            }
            val text = token.text(sql)
            result += Tok(token.type, if (token.type == TokenType.Keyword) text.uppercase() else text, newline)
            i++
        }
        return result
    }

    private inner class Writer(private val tokens: List<Tok>) {
        private val out = StringBuilder()
        private var level = 0
        /** Indent levels at which each open subquery parenthesis started. */
        private val subqueries = ArrayDeque<Int>()
        private var parenDepth = 0
        /** Paren depth of each open subquery, to recognize clause keywords at its top level. */
        private val subqueryDepths = ArrayDeque<Int>().apply { addLast(0) }
        private var listClause = false
        private var conditionClause = false

        fun run(): String {
            var i = 0
            while (i < tokens.size) {
                i = emit(i)
            }
            return out.toString()
        }

        private val atTopLevel: Boolean
            get() = parenDepth == subqueryDepths.last()

        private fun emit(index: Int): Int {
            val tok = tokens[index]
            val prev = tokens.getOrNull(index - 1)

            if (tok.type == TokenType.Comment) {
                if (tok.newlineBefore && out.isNotEmpty()) newline() else space(prev, tok, index)
                out.append(tok.text)
                if (tok.text.startsWith("--") || tok.text.startsWith("#")) newline()
                return index + 1
            }

            if (tok.isOp(";")) {
                trimTrailing()
                out.append(";\n\n")
                level = 0
                parenDepth = 0
                subqueries.clear()
                subqueryDepths.clear()
                subqueryDepths.addLast(0)
                listClause = false
                conditionClause = false
                return index + 1
            }

            // Multi-word clauses: GROUP BY, ORDER BY, LEFT OUTER JOIN, UNION ALL, INSERT INTO, DELETE FROM...
            if (tok.isWord && atTopLevel) {
                val clause = clauseAt(index)
                if (clause != null && clause.second == ClauseKind.InlineCondition) {
                    // ON stays on its JOIN's line but starts a condition list.
                    space(prev, tok, index)
                    out.append(tok.upper).append(' ')
                    listClause = false
                    conditionClause = true
                    return index + 1
                }
                if (clause != null) {
                    val (words, kind) = clause
                    if (out.isNotEmpty()) newline(level)
                    out.append(tokens.subList(index, index + words).joinToString(" ") { it.upper })
                    val next = index + words
                    listClause = kind == ClauseKind.List
                    conditionClause = kind == ClauseKind.Condition
                    // A list with several items goes one item per line; a single item stays on the clause's line.
                    if (kind == ClauseKind.List && hasTopLevelComma(next)) {
                        newline(level + 1)
                    } else if (kind == ClauseKind.Standalone) {
                        // e.g. UNION: the next SELECT starts its own line anyway.
                    } else if (next < tokens.size && !tokens[next].isOp(";", ")")) {
                        out.append(' ')
                    }
                    return next
                }
                if (conditionClause && (tok.upper == "AND" || tok.upper == "OR") && !isBetweenAnd(index)) {
                    newline(level + 1)
                    out.append(tok.upper)
                    out.append(' ')
                    return index + 1
                }
            }

            if (tok.isOp("(")) {
                space(prev, tok, index)
                out.append('(')
                parenDepth++
                val next = tokens.getOrNull(index + 1)
                if (next != null && (next.upper == "SELECT" || next.upper == "WITH")) {
                    subqueries.addLast(level)
                    subqueryDepths.addLast(parenDepth)
                    level++
                    newline(level)
                }
                return index + 1
            }
            if (tok.isOp(")")) {
                if (subqueryDepths.size > 1 && subqueryDepths.last() == parenDepth) {
                    subqueryDepths.removeLast()
                    level = subqueries.removeLast()
                    newline(level)
                } else {
                    trimTrailingSpaces()
                }
                out.append(')')
                parenDepth--
                return index + 1
            }
            if (tok.isOp(",")) {
                trimTrailingSpaces()
                out.append(',')
                if (listClause && atTopLevel) newline(level + 1) else out.append(' ')
                return index + 1
            }

            space(prev, tok, index)
            out.append(tok.text)
            return index + 1
        }

        /** Whether the BETWEEN ... AND ... at [index] is the range's AND rather than a condition separator. */
        private fun isBetweenAnd(index: Int): Boolean {
            for (i in index - 1 downTo maxOf(0, index - 4)) {
                if (tokens[i].upper == "BETWEEN") return true
                if (tokens[i].upper == "AND" || tokens[i].upper == "OR") return false
            }
            return false
        }

        private fun hasTopLevelComma(from: Int): Boolean {
            var depth = 0
            for (i in from until tokens.size) {
                val t = tokens[i]
                when {
                    t.isOp("(") -> depth++
                    t.isOp(")") -> if (depth-- == 0) return false
                    t.isOp(";") -> return false
                    depth == 0 && t.isOp(",") -> return true
                    depth == 0 && t.isWord && clauseAt(i) != null -> return false
                }
            }
            return false
        }

        private fun clauseAt(index: Int): Pair<Int, ClauseKind>? {
            for ((words, kind) in Clauses) {
                if (index + words.size > tokens.size) continue
                if (words.indices.all { tokens[index + it].upper == words[it] && tokens[index + it].isWord }) return words.size to kind
            }
            return null
        }

        private fun space(prev: Tok?, tok: Tok, index: Int) {
            if (prev == null || out.isEmpty() || out.last() == '\n' || out.last() == ' ' || out.last() == '(') return
            val noSpace =
                tok.isOp(".", "::") ||
                    prev.isOp(".", "::") ||
                    // Function calls: name( — but keep "IN (", "VALUES (", "AS (".
                    (tok.isOp("(") && prev.type == TokenType.Identifier && !isTableName(index - 1)) ||
                    (tok.isOp("(") && prev.type == TokenType.Keyword && prev.upper in CallLikeKeywords) ||
                    // Unary minus/plus after an operator, comma, paren or keyword.
                    (prev.isOp("-", "+") && isUnary(prev))
            if (!noSpace) out.append(' ')
        }

        /** `INSERT INTO t (a, b)` and `CREATE TABLE t (...)`: a table name, not a function call. */
        private fun isTableName(index: Int): Boolean {
            var i = index - 1
            while (i >= 1 && tokens[i].isOp(".")) i -= 2
            return tokens.getOrNull(i)?.upper in setOf("INTO", "TABLE", "UPDATE", "JOIN", "FROM")
        }

        private fun isUnary(op: Tok): Boolean {
            val index = tokens.indexOf(op)
            val before = tokens.getOrNull(index - 1) ?: return true
            return before.type == TokenType.Operator && !before.isOp(")") || before.type == TokenType.Keyword
        }

        private fun newline(indentLevel: Int = level) {
            trimTrailingSpaces()
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            repeat(indentLevel) { out.append(indent) }
        }

        private fun trimTrailingSpaces() {
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        }

        private fun trimTrailing() {
            while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
        }
    }

    private enum class ClauseKind {
        /** Followed by a comma-separated list: SELECT, GROUP BY, ORDER BY, SET... */
        List,
        /** Followed by AND/OR conditions: WHERE, HAVING, ON (of joins). */
        Condition,
        /** Followed by a single expression or table reference. */
        Simple,
        /** Stands on its own line: UNION, INTERSECT, EXCEPT. */
        Standalone,
        /** Stays on the current line but starts conditions: ON. */
        InlineCondition,
    }

    private companion object {
        val Single = setOf("(", ")", ",", ";", ".")

        val CallLikeKeywords = setOf("COUNT", "CAST", "COALESCE", "EXISTS", "REPLACE", "LEFT", "RIGHT", "DATE", "TIME", "TIMESTAMP")

        /** Longest first, so "LEFT OUTER JOIN" wins over "LEFT JOIN". */
        val Clauses: List<Pair<List<String>, ClauseKind>> =
            listOf(
                    "SELECT DISTINCT" to ClauseKind.List,
                    "SELECT" to ClauseKind.List,
                    "FROM" to ClauseKind.List,
                    "WHERE" to ClauseKind.Condition,
                    "GROUP BY" to ClauseKind.List,
                    "ORDER BY" to ClauseKind.List,
                    "HAVING" to ClauseKind.Condition,
                    "LIMIT" to ClauseKind.Simple,
                    "OFFSET" to ClauseKind.Simple,
                    "UNION ALL" to ClauseKind.Standalone,
                    "UNION" to ClauseKind.Standalone,
                    "INTERSECT" to ClauseKind.Standalone,
                    "EXCEPT" to ClauseKind.Standalone,
                    "LEFT OUTER JOIN" to ClauseKind.Simple,
                    "RIGHT OUTER JOIN" to ClauseKind.Simple,
                    "FULL OUTER JOIN" to ClauseKind.Simple,
                    "LEFT JOIN" to ClauseKind.Simple,
                    "RIGHT JOIN" to ClauseKind.Simple,
                    "FULL JOIN" to ClauseKind.Simple,
                    "INNER JOIN" to ClauseKind.Simple,
                    "CROSS JOIN" to ClauseKind.Simple,
                    "JOIN" to ClauseKind.Simple,
                    "ON" to ClauseKind.InlineCondition,
                    "INSERT INTO" to ClauseKind.Simple,
                    "VALUES" to ClauseKind.List,
                    "UPDATE" to ClauseKind.Simple,
                    "SET" to ClauseKind.List,
                    "DELETE FROM" to ClauseKind.Simple,
                    "RETURNING" to ClauseKind.List,
                    "WITH RECURSIVE" to ClauseKind.Simple,
                    "WITH" to ClauseKind.Simple,
                )
                .map { (words, kind) -> words.split(' ') to kind }
                .sortedByDescending { it.first.size }
    }
}

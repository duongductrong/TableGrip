package dev.datagrok.core.sql

enum class StatementKind {
    /** Only reads data: SELECT, SHOW, EXPLAIN without ANALYZE... */
    Read,
    /** Changes rows: INSERT, UPDATE, DELETE, MERGE, CALL... */
    Write,
    /** Changes schema or permissions: CREATE, ALTER, DROP, TRUNCATE, GRANT... */
    Ddl,
    /** BEGIN, COMMIT, ROLLBACK, SAVEPOINT... */
    Transaction,
    /** Session settings and anything unrecognized: SET, USE, PRAGMA... */
    Other,
}

data class StatementInfo(val kind: StatementKind, val verb: String, val danger: String?) {
    val changesData: Boolean
        get() = kind == StatementKind.Write || kind == StatementKind.Ddl
}

/** Classifies statements so the UI can enforce read-only connections and ask before risky ones. */
class SqlClassifier(syntax: SqlSyntax) {
    private val lexer = SqlLexer(syntax)

    fun classify(sql: String): StatementInfo {
        val tokens = lexer.tokenize(sql).filter { it.type != TokenType.Whitespace && it.type != TokenType.Comment }
        val words = tokens.map { if (it.type == TokenType.Keyword || it.type == TokenType.Identifier) it.text(sql).uppercase() else it.text(sql) }
        if (words.isEmpty()) return StatementInfo(StatementKind.Other, "", null)

        // For WITH ... AS (...) <verb>, the verb is the first keyword outside the CTE parentheses.
        val verbIndex = if (words[0] == "WITH") topLevelIndexOf(words, MainVerbs, from = 1) ?: 0 else 0
        val verb = words[verbIndex]
        val kind =
            when (verb) {
                // EXPLAIN ANALYZE runs the statement; a PostgreSQL CTE can modify data even under a SELECT.
                in ReadVerbs ->
                    if (verb == "EXPLAIN" && "ANALYZE" in words || words[0] == "WITH" && words.any { it in DataVerbs }) StatementKind.Write
                    else StatementKind.Read
                in WriteVerbs -> StatementKind.Write
                in DdlVerbs -> StatementKind.Ddl
                in TransactionVerbs -> StatementKind.Transaction
                else -> StatementKind.Other
            }
        val hasWhere = topLevelIndexOf(words, setOf("WHERE"), from = verbIndex) != null
        val danger =
            when {
                (verb == "DELETE" || verb == "UPDATE") && !hasWhere -> "$verb without WHERE affects every row"
                verb == "DROP" -> "DROP permanently removes ${words.getOrNull(verbIndex + 1)?.lowercase() ?: "an object"}"
                verb == "TRUNCATE" -> "TRUNCATE removes every row"
                verb == "ALTER" && "DROP" in words.drop(verbIndex) -> "ALTER … DROP removes part of a schema object"
                else -> null
            }
        return StatementInfo(kind, verb, danger)
    }

    private fun topLevelIndexOf(words: List<String>, targets: Set<String>, from: Int): Int? {
        var depth = 0
        for (i in from until words.size) {
            when (words[i]) {
                "(" -> depth++
                ")" -> depth--
                in targets -> if (depth == 0) return i
            }
        }
        return null
    }

    private companion object {
        val ReadVerbs = setOf("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE")
        val WriteVerbs = setOf("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT", "CALL", "COPY", "LOAD", "EXEC", "EXECUTE", "DO")
        val DdlVerbs = setOf("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE", "COMMENT", "REINDEX", "VACUUM", "CLUSTER")
        val TransactionVerbs = setOf("BEGIN", "START", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")
        val MainVerbs = setOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")
        val DataVerbs = setOf("INSERT", "UPDATE", "DELETE", "MERGE")
    }
}

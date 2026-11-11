package dev.tablegrip.core.sql

/**
 * Checks SQL that comes from outside the user's own editor (an agent over MCP) before it runs. [SqlClassifier] judges a
 * statement by its first verb, so `SELECT 1; DROP TABLE t` would pass as a read: this splits first, accepts exactly one
 * statement, and classifies that one. Session and transaction control is refused outright, since the caller owns the
 * transaction.
 */
class StatementGuard(syntax: SqlSyntax) {
    private val splitter = SqlSplitter(syntax)
    private val classifier = SqlClassifier(syntax)
    private val lexer = SqlLexer(syntax)

    sealed interface Verdict {
        /** [sql] is the single statement, without its delimiter. */
        data class Accepted(val sql: String, val info: StatementInfo) : Verdict

        data class Rejected(val reason: String) : Verdict
    }

    /** One statement whose kind is in [allowed]. Reasons are English: they are read by the agent, not shown in the UI. */
    fun check(sql: String, allowed: Set<StatementKind>): Verdict {
        val statements = splitter.split(sql).filter { it.text.isNotBlank() }
        if (statements.isEmpty()) return Verdict.Rejected("The SQL is empty.")
        if (statements.size > 1) return Verdict.Rejected("Send one statement per call; this has ${statements.size}.")
        val statement = statements.single().text.trim()
        val info = classifier.classify(statement)
        if (info.kind == StatementKind.Read && writesFromSelect(statement)) {
            return Verdict.Rejected("SELECT … INTO creates a table or writes a file, so it isn't a read.")
        }
        if (info.kind !in allowed) return Verdict.Rejected(refusal(info, allowed))
        return Verdict.Accepted(statement, info)
    }

    /** `SELECT … INTO new_table` (PostgreSQL, SQL Server) and `INTO OUTFILE` (MySQL) write, though they start with SELECT. */
    private fun writesFromSelect(sql: String): Boolean {
        var depth = 0
        for (token in lexer.tokenize(sql)) {
            if (token.type == TokenType.Whitespace || token.type == TokenType.Comment) continue
            val text = token.text(sql)
            when {
                text == "(" -> depth++
                text == ")" -> depth--
                depth == 0 && (token.type == TokenType.Keyword || token.type == TokenType.Identifier) && text.equals("INTO", ignoreCase = true) -> return true
            }
        }
        return false
    }

    private fun refusal(info: StatementInfo, allowed: Set<StatementKind>): String {
        val verb = info.verb.ifEmpty { "This statement" }
        return when (info.kind) {
            StatementKind.Transaction -> "$verb isn't accepted: each call runs in a transaction of its own."
            StatementKind.Other -> "$verb isn't accepted: session commands (SET, USE, PRAGMA…) and unrecognised statements can't be run by agents."
            StatementKind.Read -> "$verb reads data; use the query tool."
            StatementKind.Write ->
                if (StatementKind.Read in allowed && StatementKind.Write !in allowed && StatementKind.Ddl !in allowed) "$verb changes data; this tool only reads."
                else "$verb changes data, which this connection doesn't allow agents to do."
            StatementKind.Ddl ->
                if (StatementKind.Read in allowed && StatementKind.Write !in allowed) "$verb changes the schema; this tool only reads."
                else "$verb changes the schema, which this connection doesn't allow agents to do."
        }
    }

    companion object {
        val Reads = setOf(StatementKind.Read)
    }
}

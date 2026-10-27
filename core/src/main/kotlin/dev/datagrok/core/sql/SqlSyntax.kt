package dev.datagrok.core.sql

/** Lexical rules that differ between SQL dialects. */
data class SqlSyntax(
    val backtickIdentifiers: Boolean = false,
    /** MySQL treats `"..."` as a string literal by default (unless ANSI_QUOTES is on). */
    val doubleQuotedStrings: Boolean = false,
    val hashComments: Boolean = false,
    val dollarQuotedStrings: Boolean = false,
    val backslashEscapes: Boolean = false,
    /** MySQL client `DELIMITER //` command, used around procedure bodies. */
    val delimiterCommand: Boolean = false,
) {
    companion object {
        val PostgreSQL = SqlSyntax(dollarQuotedStrings = true)
        val MySQL =
            SqlSyntax(
                backtickIdentifiers = true,
                doubleQuotedStrings = true,
                hashComments = true,
                backslashEscapes = true,
                delimiterCommand = true,
            )
        val SQLite = SqlSyntax(backtickIdentifiers = true)
    }
}

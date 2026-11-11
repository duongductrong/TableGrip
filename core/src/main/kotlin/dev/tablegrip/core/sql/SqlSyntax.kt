package dev.tablegrip.core.sql

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
    /** T-SQL `[quoted name]`. */
    val bracketIdentifiers: Boolean = false,
    /** T-SQL tools' `GO` on a line of its own, which ends a batch. */
    val batchSeparator: Boolean = false,
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
        val Standard = SqlSyntax()
        val TSql = SqlSyntax(bracketIdentifiers = true, batchSeparator = true)
        val ClickHouse = SqlSyntax(backtickIdentifiers = true, backslashEscapes = true)

        /** Spark SQL, Hive and Impala: backtick identifiers, backslash escapes in strings. */
        val SparkSql = SqlSyntax(backtickIdentifiers = true, backslashEscapes = true)

        /** BigQuery's and Spanner's GoogleSQL: backticks, backslash escapes, and `#` comments. */
        val GoogleSql = SqlSyntax(backtickIdentifiers = true, backslashEscapes = true, hashComments = true)

        /** Microsoft Access: `[bracketed]` names. */
        val Access = SqlSyntax(bracketIdentifiers = true)
    }
}

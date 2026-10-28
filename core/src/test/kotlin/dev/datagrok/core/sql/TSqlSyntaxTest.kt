package dev.datagrok.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class TSqlSyntaxTest {
    @Test
    fun `brackets quote names, with doubled closing brackets inside`() {
        val sql = "SELECT [a]]b], [c;d] FROM t"
        val quoted = SqlLexer(SqlSyntax.TSql).tokenize(sql).filter { it.type == TokenType.QuotedIdentifier }.map { it.text(sql) }
        assertEquals(listOf("[a]]b]", "[c;d]"), quoted)
    }

    @Test
    fun `GO alone on a line ends a batch, GO inside text does not`() {
        val script = "SELECT 'GO'\nGO\nSELECT 1 AS go\n  go  \nSELECT 2;\nSELECT 3\nGO 5\n"
        assertEquals(
            listOf("SELECT 'GO'", "SELECT 1 AS go", "SELECT 2", "SELECT 3"),
            SqlSplitter(SqlSyntax.TSql).split(script).map { it.text },
        )
    }

    @Test
    fun `other dialects treat GO as a name`() {
        assertEquals(1, SqlSplitter(SqlSyntax.PostgreSQL).split("SELECT 1\nGO\n").size)
    }
}

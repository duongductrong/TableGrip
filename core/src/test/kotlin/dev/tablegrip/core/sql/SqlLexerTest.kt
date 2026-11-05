package dev.tablegrip.core.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLexerTest {
    private fun types(sql: String, syntax: SqlSyntax = SqlSyntax.PostgreSQL) =
        SqlLexer(syntax).tokenize(sql).filter { it.type != TokenType.Whitespace }.map { it.type to it.text(sql) }

    @Test
    fun `classifies tokens`() {
        assertEquals(
            listOf(
                TokenType.Keyword to "select",
                TokenType.Identifier to "id",
                TokenType.Operator to ",",
                TokenType.QuotedIdentifier to "\"Name\"",
                TokenType.Operator to ",",
                TokenType.Number to "1.5e3",
                TokenType.Keyword to "FROM",
                TokenType.Identifier to "t",
                TokenType.Keyword to "WHERE",
                TokenType.Identifier to "x",
                TokenType.Operator to "=",
                TokenType.Parameter to ":name",
                TokenType.Comment to "-- done",
            ),
            types("select id, \"Name\", 1.5e3 FROM t WHERE x = :name -- done"),
        )
    }

    @Test
    fun `does not treat casts as parameters`() {
        assertEquals(listOf(TokenType.Operator, TokenType.Operator, TokenType.Keyword), types("::text").map { it.first }.takeLast(3))
    }

    @Test
    fun `treats double quotes as strings in MySQL`() {
        assertEquals(TokenType.String, types("\"x\"", SqlSyntax.MySQL).single().first)
    }
}

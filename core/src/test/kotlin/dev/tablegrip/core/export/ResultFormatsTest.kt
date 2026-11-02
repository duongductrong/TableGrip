package dev.tablegrip.core.export

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultFormatsTest {
    private val columns = listOf(ResultColumn("id", "int4", Types.INTEGER), ResultColumn("name", "text", Types.VARCHAR))
    private val rows = listOf(listOf("1", "O'Brien"), listOf("2", null), listOf("3", "a,\"b\"\nc"))

    @Test
    fun `tsv and csv quote only when needed`() {
        assertEquals("id\tname\n1\tO'Brien\n2\t\n3\t\"a,\"\"b\"\"\nc\"", ResultFormats.format(CopyFormat.Tsv, columns, rows))
        assertEquals("1,O'Brien", ResultFormats.format(CopyFormat.Csv, columns, rows.take(1), includeHeader = false))
        assertEquals("3,\"a,\"\"b\"\"\nc\"", ResultFormats.format(CopyFormat.Csv, columns, rows.drop(2), includeHeader = false))
    }

    @Test
    fun `json keeps numbers numeric and nulls null`() {
        val json = ResultFormats.format(CopyFormat.Json, columns, rows.take(2)).replace(Regex("\\s+"), "")
        assertEquals("""[{"id":1,"name":"O'Brien"},{"id":2,"name":null}]""", json)
    }

    @Test
    fun `sql insert escapes per dialect`() {
        assertEquals(
            "INSERT INTO t (\"id\", \"name\") VALUES (1, 'O''Brien');\nINSERT INTO t (\"id\", \"name\") VALUES (2, NULL);",
            ResultFormats.format(CopyFormat.SqlInsert, columns, rows.take(2), dialect = SqlDialect.PostgreSQL, tableName = "t"),
        )
        assertEquals(
            "INSERT INTO t (`id`, `name`) VALUES (1, 'a\\\\b');",
            ResultFormats.format(CopyFormat.SqlInsert, columns, listOf(listOf("1", "a\\b")), dialect = SqlDialect.MySQL, tableName = "t"),
        )
    }

    @Test
    fun `markdown and in-list`() {
        assertEquals("| id | name |\n| ---: | --- |\n| 2 | NULL |", ResultFormats.format(CopyFormat.Markdown, columns, listOf(rows[1])))
        assertEquals("(1, 2, 3)", ResultFormats.format(CopyFormat.SqlInList, columns, rows))
    }
}

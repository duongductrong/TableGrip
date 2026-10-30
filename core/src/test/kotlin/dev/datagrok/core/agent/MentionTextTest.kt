package dev.datagrok.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionTextTest {
    private val a = MentionText.First
    private val b = MentionText.First + 1
    private val select = Mention.Sql("Query 1", 12..14, "PostgreSQL", "SELECT *\nFROM orders\nWHERE paid;\n")
    private val count = Mention.Sql("Query 2", 3..3, "PostgreSQL", "SELECT count(*) FROM customers")

    @Test
    fun `labels name the source and lines`() {
        assertEquals("Query 1:12-14", select.label)
        assertEquals("Query 2:3", count.label)
    }

    @Test
    fun `plain text shows each mention as its label`() {
        assertEquals("why is @Query 1:12-14 slow?", MentionText("why is $a slow?", mapOf(a to select)).plain)
    }

    @Test
    fun `the prompt numbers mentions in order and adds what each one is`() {
        val text = MentionText("compare $b with $a", mapOf(a to select, b to count))
        assertEquals(
            """
            compare @Query 2:3 [1] with @Query 1:12-14 [2]

            # Mentioned

            [1] PostgreSQL SQL from Query 2, line 3:
            ```sql
            SELECT count(*) FROM customers
            ```

            [2] PostgreSQL SQL from Query 1, lines 12-14:
            ```sql
            SELECT *
            FROM orders
            WHERE paid;
            ```
            """.trimIndent(),
            text.prompt,
        )
    }

    @Test
    fun `a mention used twice is sent once`() {
        val text = MentionText("$a and again $a", mapOf(a to select))
        assertEquals(listOf(a to select), text.used)
        assertEquals("@Query 1:12-14 [1] and again @Query 1:12-14 [1]", text.prompt.substringBefore("\n"))
    }

    @Test
    fun `a failed statement reads as the statement and its error`() {
        val error = Mention.Error("Query 1", "SQLite", "SELECT amount FROM orders", "no such column: amount")
        assertEquals("Query 1 error", error.label)
        assertEquals("This SQLite statement from Query 1 failed:\n```sql\nSELECT amount FROM orders\n```\nError: no such column: amount", error.context)
    }

    @Test
    fun `a huge mention is cut to the budget and says so`() {
        val huge = Mention.Sql("Query 1", 1..1, "SQLite", "x".repeat(Mention.Budget + 5))
        assertTrue(huge.context.contains("x".repeat(Mention.Budget) + "\n… (5 more characters not shown)\n```"), huge.context.takeLast(80))
    }

    @Test
    fun `a table reads as its structure once it is known`() {
        val table = Mention.Table("shop", "Shop", "SQLite", "main", "orders")
        assertEquals("orders", table.label)
        assertEquals("SQLite table main.orders on connection Shop:\n(its structure couldn't be read)", table.context)
        assertEquals("SQLite table main.orders on connection Shop:\nTable columns:\n  id integer pk", table.copy(details = "Table columns:\n  id integer pk").context)
    }

    @Test
    fun `cells send their values only when the connection shares data`() {
        val columns = listOf("id" to "INTEGER", "note" to "TEXT")
        val shared = Mention.Cells("orders", "SQLite", columns, 3..4, listOf(listOf("3", "a\nb"), listOf("4", null)))
        assertEquals("orders rows 3-4", shared.label)
        assertEquals("Cells from orders (SQLite), 2 column(s) × 2 row(s), rows 3-4:\nid integer | note text\n3 | a b\n4 | NULL", shared.context)
        val withheld = shared.copy(rows = 7..7, values = null)
        assertEquals("orders row 7", withheld.label)
        assertTrue(withheld.context.endsWith("id integer | note text\n(Values not shared: the connection only shares its schema with agents.)"), withheld.context)
    }

    @Test
    fun `columns and saved queries name themselves`() {
        assertEquals("orders.total", Mention.Column("Shop", "SQLite", "orders", "total", "REAL", false).label)
        assertEquals("Column orders.id of SQLite connection Shop: integer, primary key", Mention.Column("Shop", "SQLite", "orders", "id", "INTEGER", true).context)
        val query = Mention.Query("Top customers", "SELECT 1")
        assertEquals("Top customers", query.label)
        assertEquals("Saved query \"Top customers\":\n```sql\nSELECT 1\n```", query.context)
    }

    @Test
    fun `a removed mention is not sent and an unknown stand-in is dropped`() {
        val text = MentionText("just ${b}this", mapOf(a to select))
        assertEquals("just this", text.prompt)
    }
}

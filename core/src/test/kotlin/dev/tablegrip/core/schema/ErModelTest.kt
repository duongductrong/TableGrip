package dev.tablegrip.core.schema

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.ForeignKeyInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErModelTest {
    private val ns = Namespace(null, "app")

    private fun table(name: String, vararg references: String, nullable: Boolean = false) =
        TableDetails(
            TableRef(ns, name),
            listOf(ColumnInfo("id", "integer", null, nullable = false, defaultValue = null, autoIncrement = true, primaryKey = true)) +
                references.map { ColumnInfo("${it}_id", "integer", null, nullable = nullable, defaultValue = null, autoIncrement = false, primaryKey = false) },
            emptyList(),
            references.map { ForeignKeyInfo("${name}_$it", listOf("${it}_id"), TableRef(ns, it), listOf("id")) },
        )

    private val schema =
        listOf(table("teams"), table("users", "teams", nullable = true), table("orders", "users"), table("products"), table("items", "orders", "products"), table("log"))

    @Test
    fun `referenced tables sit left of what references them, unrelated ones below`() {
        val boxes = ErLayout.layout(schema) { 200f }
        assertTrue(boxes.getValue(TableRef(ns, "teams")).x < boxes.getValue(TableRef(ns, "users")).x)
        assertTrue(boxes.getValue(TableRef(ns, "users")).x < boxes.getValue(TableRef(ns, "orders")).x)
        assertTrue(boxes.getValue(TableRef(ns, "orders")).x < boxes.getValue(TableRef(ns, "items")).x)
        // products is only used by items, so it sits right next to it rather than in the first layer.
        assertEquals(boxes.getValue(TableRef(ns, "orders")).x, boxes.getValue(TableRef(ns, "products")).x)
        val graphBottom = boxes.filterKeys { it.name != "log" }.values.maxOf { it.bottom }
        assertTrue(boxes.getValue(TableRef(ns, "log")).y > graphBottom)
        // No two boxes overlap.
        val all = boxes.values.toList()
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            assertTrue(a.right <= b.x || b.right <= a.x || a.bottom <= b.y || b.bottom <= a.y, "$a overlaps $b")
        }
    }

    @Test
    fun `survives reference cycles and self references`() {
        val cyclic = listOf(table("a", "b"), table("b", "a"), table("c", "c"))
        assertEquals(3, ErLayout.layout(cyclic) { 180f }.size)
    }

    @Test
    fun `neighbourhood is the table and what it links to directly`() {
        assertEquals(setOf("orders", "users", "items"), ErLayout.neighbourhood(schema, TableRef(ns, "orders")).map { it.table.name }.toSet())
    }

    @Test
    fun `writes Mermaid with keys and optional relationships`() {
        val text = Mermaid.erDiagram(schema.take(3) + TableDetails(TableRef(ns, "order items"), emptyList(), emptyList(), emptyList()), SqlDialect.PostgreSQL)
        assertTrue("    users {\n        integer id PK\n        integer teams_id FK\n    }" in text, text)
        assertTrue("teams |o--o{ users : \"teams_id\"" in text, text)
        assertTrue("users ||--o{ orders : \"users_id\"" in text, text)
        assertTrue("\"order items\" {" in text, text)
    }
}

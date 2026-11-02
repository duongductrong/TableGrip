package dev.tablegrip.app.table

import dev.tablegrip.core.completion.ColumnEntry
import dev.tablegrip.core.completion.CompletionKind
import dev.tablegrip.core.completion.SchemaProvider
import dev.tablegrip.core.completion.TableEntry
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterCompletionTest {
    private val schema =
        object : SchemaProvider {
            override val defaultNamespace = "public"

            override fun namespaces() = listOf("public")

            override fun tables(namespace: String?) = listOf(TableEntry("orders"))

            override fun columns(namespace: String?, table: String) =
                listOf(ColumnEntry("id", "int4", true), ColumnEntry("status", "text"), ColumnEntry("total", "numeric"), ColumnEntry("created_at", "timestamptz"))

            override fun relations(namespace: String?, table: String) = emptyList<dev.tablegrip.core.completion.Relation>()
        }

    private val source = filterCompletion(SqlDialect.PostgreSQL, TableRef(Namespace(null, "public"), "orders"), schema, cache = null)

    private fun complete(marked: String) = marked.indexOf('|').let { source.complete(marked.removeRange(it, it + 1), it) }

    @Test
    fun `suggests the viewed table's columns with ranges relative to the filter`() {
        val result = complete("sta|")
        assertEquals("status", result.items.first().label)
        assertEquals(0 until 3, result.replaceStart until result.replaceEnd)
        assertTrue(complete("ca|").items.any { it.label == "created_at" }, "word-initial match")
    }

    @Test
    fun `offers operators after a column and no clauses that can't appear in a filter`() {
        val items = complete("status |").items
        assertEquals("=", items.first().label)
        assertTrue(items.none { it.kind == CompletionKind.Keyword && it.label in setOf("FROM", "ORDER BY", "GROUP BY", "LIMIT") })
        assertTrue(items.any { it.label == "IS NULL" })
        assertTrue(complete("|").items.none { it.label == "count()" }, "aggregates don't belong in WHERE")
    }

    @Test
    fun `recognizes where values go, in quotes and IN lists`() {
        assertEquals("status", complete("status = 'pa|").value?.column)
        assertEquals("pa", complete("status = 'pa|").value?.prefix)
        assertEquals("orders", complete("total > 10 AND status IN ('paid', |").value?.table)
    }
}

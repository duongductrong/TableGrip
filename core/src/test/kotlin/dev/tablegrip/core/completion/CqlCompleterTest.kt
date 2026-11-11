package dev.tablegrip.core.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CqlCompleterTest {
    private val catalog =
        FakeCatalog(
            containers = mapOf(emptyList<String>() to listOf("shop", "Analytics"), listOf("shop") to listOf("orders", "customers"), listOf("Analytics") to listOf("Events")),
            fields = mapOf(listOf("shop", "orders") to listOf("customer_id", "order_id", "total", "Status"), listOf("shop", "customers") to listOf("id", "name")),
        )

    /** Cassandra's own test: the verbs a read-only profile may run. */
    private val reads = { s: String -> s.trim().split(Regex("\\s+")).first().uppercase() in setOf("SELECT", "DESCRIBE", "DESC", "USE", "LIST", "EXPLAIN") }

    private fun labels(text: String, isRead: ((String) -> Boolean)? = null) = CqlCompleter.labels(text, catalog, isRead)

    @Test
    fun `statements at the start, and only reads for a read-only profile`() {
        assertTrue(labels("|").containsAll(listOf("SELECT", "INSERT INTO", "UPDATE", "DELETE FROM", "BEGIN BATCH", "USE", "CREATE TABLE")))
        assertEquals(listOf("SELECT", "USE", "DESCRIBE", "LIST ROLES"), labels("|", reads).sortedBy { listOf("SELECT", "USE", "DESCRIBE", "LIST ROLES").indexOf(it) })
        assertEquals("insert into |", CqlCompleter.accept("ins|", catalog))
    }

    @Test
    fun `keyspaces after USE, quoted when they need it`() {
        assertEquals(listOf("shop", "\"Analytics\""), labels("USE |"))
        assertEquals("USE \"Analytics\"|", CqlCompleter.accept("USE An|", catalog))
    }

    @Test
    fun `tables of the keyspace in use, and keyspaces to qualify with`() {
        val items = labels("USE shop;\nSELECT * FROM |")
        assertTrue(items.containsAll(listOf("orders", "customers", "shop")), "$items")
        assertEquals("SELECT * FROM shop.|", CqlCompleter.accept("SELECT * FROM sh|", catalog, "shop"))
        assertEquals(listOf("orders", "customers"), labels("SELECT * FROM shop.|"))
        assertEquals(listOf("orders"), labels("INSERT INTO shop.or|"))
    }

    @Test
    fun `columns of the statement's table where a column goes`() {
        assertTrue(labels("SELECT | FROM shop.orders").containsAll(listOf("customer_id", "order_id", "total", "\"Status\"", "count()", "*")))
        assertTrue(labels("SELECT * FROM shop.orders WHERE |").containsAll(listOf("customer_id", "token()")))
        assertEquals("total", labels("SELECT * FROM shop.orders WHERE customer_id = 1 AND to|").first())
        assertTrue(labels("UPDATE shop.orders SET |").contains("total"))
        assertTrue(labels("INSERT INTO shop.orders (customer_id, |").contains("order_id"))
        assertTrue(labels("USE shop; SELECT name FROM customers WHERE |").contains("id"))
    }

    @Test
    fun `clauses that follow`() {
        assertTrue(labels("SELECT * FROM shop.orders |").containsAll(listOf("WHERE", "LIMIT", "ALLOW FILTERING")))
        assertTrue(labels("SELECT * FROM shop.orders WHERE customer_id |").containsAll(listOf("=", "IN", "CONTAINS")))
        assertTrue(labels("SELECT * FROM shop.orders WHERE customer_id = 42 |").containsAll(listOf("AND", "LIMIT", "ALLOW FILTERING")))
        assertEquals(listOf("BY"), labels("SELECT * FROM shop.orders ORDER |"))
        assertEquals(listOf("FILTERING"), labels("SELECT * FROM shop.orders ALLOW |"))
        assertTrue(labels("UPDATE shop.orders |").containsAll(listOf("SET", "USING TTL")))
        assertTrue(labels("INSERT INTO shop.orders (customer_id) |").contains("VALUES"))
        assertTrue(labels("BEGIN |").contains("BATCH"))
    }

    @Test
    fun `nothing inside strings or comments`() {
        assertTrue(labels("SELECT * FROM shop.orders WHERE name = 'sh|").isEmpty())
        assertTrue(labels("-- sel|").isEmpty())
    }

    @Test
    fun `lower case typing keeps keywords lower case`() {
        assertEquals("select * from shop.orders wh|", CqlCompleter.accept("select * from shop.orders wh|", catalog).let { it.replace("where |", "wh|") })
        assertEquals("select * from shop.orders where |", CqlCompleter.accept("select * from shop.orders wh|", catalog))
        assertFalse(labels("SELECT * FROM shop.orders |").contains("INSERT INTO"))
    }
}

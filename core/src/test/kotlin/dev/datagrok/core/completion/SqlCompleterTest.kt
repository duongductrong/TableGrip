package dev.datagrok.core.completion

import dev.datagrok.core.dialect.SqlDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlCompleterTest {
    private val schema =
        object : SchemaProvider {
            override val defaultNamespace = "public"

            override fun namespaces() = listOf("public", "audit")

            override fun tables(namespace: String?) =
                when (namespace ?: "public") {
                    "public" -> listOf(TableEntry("users"), TableEntry("orders"), TableEntry("teams"), TableEntry("order_items"), TableEntry("active_users", isView = true))
                    "audit" -> listOf(TableEntry("events"))
                    else -> emptyList()
                }

            private val columns =
                mapOf(
                    "users" to listOf(ColumnEntry("id", "int4", true), ColumnEntry("name", "text"), ColumnEntry("email", "text"), ColumnEntry("team_id", "int4")),
                    "orders" to listOf(ColumnEntry("id", "int4", true), ColumnEntry("user_id", "int4"), ColumnEntry("total", "numeric"), ColumnEntry("status", "text")),
                    "teams" to listOf(ColumnEntry("id", "int4", true), ColumnEntry("name", "text")),
                    "order_items" to listOf(ColumnEntry("id", "int4", true), ColumnEntry("order_id", "int4")),
                    "events" to listOf(ColumnEntry("id", "int8", true), ColumnEntry("payload", "jsonb")),
                )

            override fun columns(namespace: String?, table: String) = columns[table]

            private val relations =
                listOf(
                    Relation("orders", listOf("user_id"), "users", listOf("id")),
                    Relation("users", listOf("team_id"), "teams", listOf("id")),
                    Relation("order_items", listOf("order_id"), "orders", listOf("id")),
                )

            override fun relations(namespace: String?, table: String) = relations.filter { it.fromTable == table || it.toTable == table }
        }

    private val completer = SqlCompleter(SqlDialect.PostgreSQL)

    /** Completes at the `|` in [text]. */
    private fun complete(text: String): CompletionResult {
        val caret = text.indexOf('|')
        return completer.complete(text.removeRange(caret, caret + 1), caret, schema)
    }

    private fun labels(text: String) = complete(text).items.map { it.label }

    @Test
    fun `statement keywords at the start`() {
        assertEquals("SELECT", labels("sel|").first())
        assertEquals("select ", complete("sel|").items.first().insertText, "keyword case follows what was typed")
    }

    @Test
    fun `tables and schemas after FROM`() {
        val items = labels("SELECT * FROM |")
        assertTrue(items.containsAll(listOf("users", "orders", "active_users", "audit")))
        assertEquals(listOf("orders", "order_items"), labels("SELECT * FROM or|"))
        // "au" matches the audit schema and, by word initials, active_users.
        assertEquals("audit.", complete("SELECT * FROM au|").items.first { it.kind == CompletionKind.Schema }.insertText)
        assertTrue("active_users" in labels("SELECT * FROM au|"))
        assertEquals(listOf("events"), labels("SELECT * FROM audit.|"))
    }

    @Test
    fun `columns of the tables in scope, through aliases`() {
        val items = complete("SELECT | FROM users u")
        assertEquals("id", items.items.first().label)
        assertTrue(items.items.any { it.label == "email" && it.detail!!.startsWith("text") })
        assertEquals(listOf("name"), labels("SELECT u.na| FROM users u JOIN orders o ON o.user_id = u.id").filter { it == "name" })
        assertEquals(listOf("total"), labels("SELECT o.to| FROM users u JOIN orders o ON o.user_id = u.id"))
        assertEquals(listOf("id", "user_id", "total", "status", "*"), labels("SELECT o.| FROM orders AS o"))
    }

    @Test
    fun `columns are qualified when several aliased tables are in scope`() {
        val item = complete("SELECT stat| FROM users u JOIN orders o ON o.user_id = u.id").items.first()
        assertEquals("status", item.label)
        assertEquals("o.status", item.insertText)
    }

    @Test
    fun `fuzzy matching by word initials`() {
        assertEquals("user_id", labels("SELECT * FROM orders WHERE uid|").first())
        assertEquals("order_items", labels("SELECT * FROM oi|").first())
    }

    @Test
    fun `join suggestions follow foreign keys in both directions`() {
        val joins = complete("SELECT * FROM users u JOIN |").items.filter { it.kind == CompletionKind.Join }.map { it.insertText }
        assertTrue("orders o ON o.user_id = u.id" in joins, joins.toString())
        assertTrue("teams t ON t.id = u.team_id" in joins, joins.toString())
        // Without aliases, conditions use table names.
        assertTrue("orders ON orders.user_id = users.id" in complete("SELECT * FROM users JOIN |").items.map { it.insertText })
    }

    @Test
    fun `insert column lists and CTE columns`() {
        assertEquals(listOf("id", "user_id", "total", "status"), labels("INSERT INTO orders (|"))
        assertEquals(listOf("n"), labels("WITH x AS (SELECT name AS n, id FROM users) SELECT x.| FROM x").take(1))
    }

    @Test
    fun `value contexts for comparisons and IN lists`() {
        val inString = complete("SELECT * FROM orders WHERE status = 'pa|'")
        assertEquals(ValueContext("public".takeIf { false }, "orders", "status", "pa", quoted = true), inString.value)
        assertTrue(inString.items.isEmpty())

        val inList = complete("SELECT * FROM orders o WHERE o.status IN ('paid', |")
        assertEquals("status", inList.value?.column)
        assertEquals("orders", inList.value?.table)

        // Outside quotes, columns stay available for column-to-column comparisons.
        val bare = complete("SELECT * FROM orders o JOIN users u ON o.user_id = u.|")
        assertNull(bare.value)
        assertTrue(bare.items.any { it.label == "id" })
        assertNotNull(complete("SELECT * FROM orders WHERE total > |").value)
    }

    @Test
    fun `operators and predicates after a column`() {
        val items = complete("SELECT * FROM orders WHERE status |").items
        assertEquals(listOf("=", "<>", ">", "<", ">=", "<="), items.take(6).map { it.label })
        assertTrue(items.any { it.label == "IS NULL" } && items.any { it.label == "LIKE" })
        assertEquals(listOf("IN", "IS NULL", "IS NOT NULL"), labels("SELECT * FROM orders WHERE status i|").take(3))
    }

    @Test
    fun `nothing inside comments and quoting of awkward names`() {
        assertTrue(complete("SELECT 1 -- fr|").items.isEmpty())
        assertEquals("\"Order\"", completer.quote("Order"))
        assertEquals("\"user\"", completer.quote("user"))
        assertEquals("orders", completer.quote("orders"))
    }

    @Test
    fun `the statement around the caret is used and unknown columns are reported`() {
        assertEquals("users", labels("SELECT 1; SELECT * FROM us|; SELECT 2").first())
        val unknown =
            SqlCompleter(SqlDialect.PostgreSQL).complete(
                "SELECT  FROM mystery",
                7,
                object : SchemaProvider by schema {
                    override fun columns(namespace: String?, table: String) = if (table == "mystery") null else schema.columns(namespace, table)
                },
            )
        assertEquals(setOf<Pair<String?, String>>(null to "mystery"), unknown.pendingTables)
    }

    @Test
    fun `statements after existing ones without a delimiter`() {
        assertEquals("SELECT", labels("SELECT * FROM users\n\nsel|").first())
        assertEquals("SELECT", labels("SELECT * FROM users\nsel|").first(), "a new line may start a new statement")
        // The earlier statement's tables don't leak into the next one.
        assertEquals(listOf("id", "user_id", "total", "status"), complete("SELECT * FROM teams t\n\nSELECT * FROM orders WHERE |").items.filter { it.kind == CompletionKind.Column }.map { it.label })
        assertEquals(listOf("id", "user_id", "total", "status"), complete("SELECT * FROM users WHERE id = 1\n\nSELECT | FROM orders").items.filter { it.kind == CompletionKind.Column }.map { it.label })
        // ... but a blank line inside one statement doesn't split it.
        assertTrue("email" in labels("SELECT *\nFROM users\n\nWHERE em|"))
        assertTrue("name" in labels("SELECT name FROM users\nUNION\n\nSELECT | FROM teams"))
    }

    @Test
    fun `subqueries and union branches have their own scope`() {
        val outer = complete("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders) AND |").items.filter { it.kind == CompletionKind.Column }.map { it.label }
        assertEquals(listOf("id", "name", "email", "team_id"), outer)
        // A correlated subquery sees the outer query's tables too.
        val inner = complete("SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = |").items.map { it.insertText }
        assertTrue("u.email" in inner && "o.total" in inner, inner.toString())
        assertEquals(listOf("id", "user_id", "total", "status"), complete("SELECT * FROM users UNION SELECT | FROM orders").items.filter { it.kind == CompletionKind.Column }.map { it.label })
        // Derived tables expose their select list.
        assertEquals(listOf("id", "n"), labels("SELECT t.| FROM (SELECT id, name AS n FROM users) t").filter { it != "*" })
    }

    @Test
    fun `keywords that complete a phrase`() {
        assertEquals(listOf("BY"), labels("SELECT * FROM users ORDER |"))
        assertEquals(listOf("BY"), labels("SELECT * FROM users GROUP |"))
        assertEquals(listOf("JOIN", "OUTER JOIN"), labels("SELECT * FROM users u LEFT |"))
        assertEquals("NULL", labels("SELECT * FROM users WHERE name IS |").first())
        assertEquals("NULL", labels("SELECT * FROM users WHERE name IS NOT |").first())
        assertEquals(listOf("SELECT", "ALL"), labels("SELECT 1 UNION |"))
        assertEquals("OFFSET", labels("SELECT * FROM users LIMIT 10 |").first())
        assertEquals("VALUES", labels("INSERT INTO users (id, name) |").first())
        assertTrue(labels("SELECT id AS |").isEmpty(), "an alias is being made up")
        assertEquals("WHERE ", complete("SELECT * FROM users WH|").items.first().insertText, "keywords insert a trailing space")
    }

    @Test
    fun `the word around the caret is completed and replaced`() {
        val result = complete("SELECT * FROM users u WHERE u.na|me = 1")
        assertEquals("na", result.prefix)
        assertEquals(listOf("name"), result.items.map { it.label })
        val text = "SELECT * FROM users u WHERE u.name = 1"
        assertEquals("name", text.substring(result.replaceStart, result.replaceEnd))
    }

    @Test
    fun `lists that aren't values and parameters`() {
        assertNull(complete("UPDATE users SET name = 'a', |").value)
        assertTrue(labels("UPDATE users SET name = 'a', |").contains("email"))
        assertTrue(labels("INSERT INTO users (id, |").containsAll(listOf("name", "email")))
        assertTrue(complete("SELECT * FROM users WHERE name = :na|").items.isEmpty())
    }
}

package dev.tablegrip.core.completion

import dev.tablegrip.core.driver.graph.Neo4jClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CypherCompleterTest {
    private val catalog =
        FakeCatalog(
            graph =
                mapOf(
                    GraphNames.Labels to listOf("Person", "Movie", "Old Thing"),
                    GraphNames.RelationshipTypes to listOf("KNOWS", "ACTED_IN"),
                    GraphNames.PropertyKeys to listOf("name", "born", "title"),
                ),
        )

    private fun labels(text: String, readOnly: Boolean = false) = CypherCompleter.labels(text, catalog, if (readOnly) Neo4jClient.Companion::isReadCypher else null)

    @Test
    fun `clauses at the start, without the writing ones for a read-only profile`() {
        assertTrue(labels("|").containsAll(listOf("MATCH", "OPTIONAL MATCH", "CREATE", "MERGE", "RETURN", "CALL")))
        val read = labels("|", readOnly = true)
        assertTrue("MATCH" in read && "CREATE" !in read && "MERGE" !in read, "$read")
        assertFalse(labels("MATCH (n) |", readOnly = true).any { it in setOf("SET", "DELETE", "DETACH DELETE", "CREATE") })
    }

    @Test
    fun `labels in node patterns, types in relationship patterns`() {
        assertEquals(listOf("Person", "Movie", "Old Thing"), labels("MATCH (p:|"))
        assertEquals(listOf("Person"), labels("MATCH (p:Pe|"))
        assertEquals("MATCH (p:`Old Thing`|)", CypherCompleter.accept("MATCH (p:Ol|)", catalog))
        assertEquals(listOf("KNOWS", "ACTED_IN"), labels("MATCH (p:Person)-[r:|"))
        assertEquals(listOf("KNOWS"), labels("MATCH (p)-[:ACTED_IN|KN‸"))
        assertEquals(listOf("Person", "Movie", "Old Thing"), labels("MATCH (n) WHERE n:|"))
    }

    @Test
    fun `property keys after a variable and in a pattern's map`() {
        assertEquals(listOf("name", "born", "title"), labels("MATCH (p:Person) RETURN p.|"))
        assertEquals("MATCH (p:Person {name: |", CypherCompleter.accept("MATCH (p:Person {na|", catalog))
        assertTrue(labels("MATCH (p:Person {name: |").isEmpty())
    }

    @Test
    fun `variables and functions in expressions`() {
        val items = labels("MATCH (p:Person)-[r:KNOWS]->(friend) RETURN |")
        assertTrue(items.containsAll(listOf("p", "r", "friend", "count()", "collect()", "DISTINCT")), "$items")
        assertTrue(labels("MATCH (p:Person) WHERE p.born > 1970 |").containsAll(listOf("AND", "RETURN", "WITH")))
        assertTrue(labels("MATCH (p:Person) RETURN p.name |").containsAll(listOf("AS", "ORDER BY", "LIMIT")))
        assertEquals(listOf("BY"), labels("MATCH (p) RETURN p ORDER |"))
    }

    @Test
    fun `procedures after CALL`() {
        assertTrue(labels("CALL |").containsAll(listOf("db.labels()", "db.relationshipTypes()", "db.propertyKeys()")))
        assertEquals("CALL db.labels(|)", CypherCompleter.accept("CALL db.lab|", catalog))
        assertTrue(labels("CALL db.|").contains("db.labels()"))
        assertFalse(labels("CALL |", readOnly = true).contains("db.createLabel()"))
    }

    @Test
    fun `nothing in strings or comments`() {
        assertTrue(labels("MATCH (p {name: 'Pe|").isEmpty())
        assertTrue(labels("// MAT|").isEmpty())
    }
}

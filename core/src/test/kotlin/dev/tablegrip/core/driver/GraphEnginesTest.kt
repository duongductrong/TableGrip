package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.graph.Neo4jClient
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Neo4j over its HTTP API. The server tests are skipped unless the property is passed:
 * ```
 * docker run -d --name dg-it-neo4j -p 57474:7474 -e NEO4J_AUTH=neo4j/tablegrip123 neo4j:5.26.2
 * -Pit.neo4j=localhost:57474
 * ```
 */
class GraphEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `cypher reads are told from writes`() {
        listOf(
            "MATCH (n:Person) RETURN n", "MATCH (n) WHERE n.note = 'CREATE me' RETURN n // DELETE", "CALL db.labels()", "SHOW INDEXES",
            "EXPLAIN MATCH (n) RETURN n", "MATCH (n) RETURN n.`set` AS x",
        ).forEach { assertTrue(Neo4jClient.isReadCypher(it), it) }
        listOf("CREATE (n:Person)", "MATCH (n) DETACH DELETE n", "MATCH (n) SET n.a = 1", "MERGE (n:A {id: 1})", "CALL apoc.periodic.iterate('a', 'b', {})", "LOAD CSV FROM 'x' AS row RETURN row", "").forEach {
            assertFalse(Neo4jClient.isReadCypher(it), it)
        }
        assertEquals(listOf("RETURN 'a;b'", "RETURN 1"), Neo4jClient.splitStatements("RETURN 'a;b'; // c;\nRETURN 1;"))
        assertEquals(Neo4jClient.Target("Person", relationship = false), Neo4jClient.Target.of(listOf(":Person")))
        assertEquals(Neo4jClient.Target("ACTED_IN", relationship = true), Neo4jClient.Target.of(listOf("[:ACTED_IN]")))
    }

    private fun server(readOnly: Boolean = false, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty("it.neo4j")?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = "neo4j", name = "neo4j", type = DatabaseType.Neo4j, host = host, port = port.toInt(), user = "neo4j", readOnly = readOnly)
            RecordStore.connect(config, "tablegrip123", drivers = drivers).use { test(it) }
        }

    @Test
    fun `neo4j browses labels and relationships and writes safely`() = server { neo ->
        assertTrue(neo.server().startsWith("Neo4j Kernel 5."), neo.server())
        neo.execute("MATCH (n:TgPerson) DETACH DELETE n")
        neo.execute(
            "UNWIND range(1, 30) AS i CREATE (:TgPerson {name: 'Person ' + i, born: 1950 + i, since: date('2020-01-01')});" +
                "MATCH (a:TgPerson), (b:TgPerson) WHERE b.born = a.born + 1 CREATE (a)-[:TG_KNOWS {weight: a.born % 3}]->(b)",
        )
        neo.execute("CREATE INDEX tg_person_name IF NOT EXISTS FOR (p:TgPerson) ON (p.name)")
        val containers = neo.containers()
        assertEquals(30L, containers.single { it.name == ":TgPerson" }.count)
        assertEquals(29L, containers.single { it.name == "[:TG_KNOWS]" }.count)
        val definition = neo.definition(listOf(":TgPerson"))
        assertTrue("tg_person_name" in definition && "\"born\"" in definition, definition)

        val people = listOf(":TgPerson")
        val first = neo.page(people, "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals(30L, first.total)
        assertEquals(10, neo.page(people, "", first.cursor, 20).records.size)
        assertEquals(10, neo.page(people, "n.born > 1970", null, 50).records.size)
        val record = neo.page(people, "n.name = 'Person 1'", null, 5).records.single()
        assertEquals(listOf(JsonPrimitive("TgPerson")), record.meta["labels"]!!.jsonArray.toList())
        assertEquals("2020-01-01", record.document["since"]!!.jsonPrimitive.content)

        // Only the edited property is written: the date stays a date.
        neo.put(people, record.key, JsonObject(record.document + ("name" to JsonPrimitive("Renamed"))))
        val types = neo.execute("MATCH (n:TgPerson {name: 'Renamed'}) RETURN valueType(n.since), n.born") as ConsoleReply.Table
        assertTrue(types.rows.rows.single()[0]!!.startsWith("DATE"), types.rows.rows.single()[0])
        assertFailsWith<IOException>("a stale edit is refused") { neo.put(people, record.key, record.document) }
        val created = neo.put(people, null, buildJsonObject { put("name", "New"); put("born", 2000) })
        assertEquals(1, neo.page(people, "n.name = 'New'", null, 5).records.size)

        // Relationships: browsed with their ends, edited, deleted; not created from the browser.
        val knows = listOf("[:TG_KNOWS]")
        val edge = neo.page(knows, "a.name = 'Person 2'", null, 5).records.single()
        assertTrue(edge.meta["start"] != null && edge.meta["end"] != null)
        neo.put(knows, edge.key, buildJsonObject { put("weight", 42) })
        assertEquals("42", (neo.execute("MATCH (:TgPerson {name: 'Person 2'})-[r:TG_KNOWS]->() RETURN r.weight") as ConsoleReply.Table).rows.rows.single()[0])
        assertFailsWith<IOException> { neo.put(knows, null, buildJsonObject { put("weight", 1) }) }

        val doomed = neo.page(people, "n.name IN ['New', 'Person 3']", null, 5).records
        assertEquals(2L, neo.delete(people, doomed.map { it.key!! } + created))
        assertEquals(0L, neo.delete(people, doomed.map { it.key!! }), "already gone")
        assertEquals(29L, neo.page(people, "", null, 1).total)

        val rows = neo.execute("MATCH (p:TgPerson) RETURN p.name AS name, p.born AS born ORDER BY born LIMIT 3") as ConsoleReply.Table
        assertEquals(listOf("name", "born"), rows.rows.columns.map { it.name })
        assertEquals("number", rows.rows.columns[1].typeName)
        assertTrue(neo.execute("CREATE (:TgTmp) WITH 1 AS x MATCH (t:TgTmp) DELETE t") is ConsoleReply.Text)
        assertFailsWith<IOException> { neo.execute("MATCH (n RETURN n") }
        neo.execute("DROP INDEX tg_person_name IF EXISTS; MATCH (n:TgPerson) DETACH DELETE n")
        assertNull(neo.containers().firstOrNull { it.name == ":TgPerson" && (it.count ?: 0) > 0 })
    }

    @Test
    fun `a read-only neo4j profile only reads`() = server(readOnly = true) { neo ->
        assertTrue(neo.execute("MATCH (n) RETURN count(n)") is ConsoleReply.Table)
        assertFailsWith<ReadOnlyViolationException> { neo.execute("CREATE (:TgNope)") }
    }
}

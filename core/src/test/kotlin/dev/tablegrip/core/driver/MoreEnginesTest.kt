package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.http.HttpConsole
import dev.tablegrip.core.driver.http.RestClient
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Solr, Qdrant, RabbitMQ and InfluxDB 3 over their HTTP APIs. The server tests are skipped unless the properties are
 * passed:
 * ```
 * docker run -d --name dg-it-solr -p 58983:8983 solr:9.7.0 solr-precreate tg_products
 * docker run -d --name dg-it-qdrant -p 56333:6333 qdrant/qdrant:v1.12.5
 * docker run -d --name dg-it-rabbit -p 55673:15672 -e RABBITMQ_DEFAULT_USER=tg -e RABBITMQ_DEFAULT_PASS=tablegrip rabbitmq:4.0.5-management
 * docker run -d --name dg-it-influx3 -p 58181:8181 influxdb:3-core influxdb3 serve --node-id n1 --object-store memory --without-auth   (3.12.2)
 * docker run -d --name dg-it-consul -p 58500:8500 hashicorp/consul:1.20.2 agent -dev -client=0.0.0.0
 * -Pit.consul=localhost:58500 -Pit.solr=localhost:58983 -Pit.qdrant=localhost:56333 -Pit.rabbitmq=localhost:55673 -Pit.influxdb3=localhost:58181
 * ```
 */
class MoreEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `http consoles find the list in a reply and tell reads from writes`() {
        val reply = kotlinx.serialization.json.Json.parseToJsonElement("""{"result": {"points": [{"id": 1}, {"id": 2}]}, "time": 0.1}""")
        assertEquals(2, HttpConsole.firstList(reply)!!.size)
        assertTrue(HttpConsole.isRead("POST /collections/a/points/scroll\n{}", listOf("/points/scroll")))
        assertFalse(HttpConsole.isRead("POST /collections/a/points/delete\n{}", listOf("/points/scroll")))
        assertTrue(HttpConsole.isRead("GET /api/queues", emptyList()))
        assertFalse(HttpConsole.isRead("DELETE /api/queues/%2F/q/contents", emptyList()))
    }

    private fun server(property: String, type: DatabaseType, user: String = "", password: String? = null, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), user = user)
            RecordStore.connect(config, password, drivers = drivers).use { test(it) }
        }

    @Test
    fun `solr pages documents and writes by version`() = server("it.solr", DatabaseType.Solr) { solr ->
        assertTrue(solr.server().startsWith("Apache Solr 9."), solr.server())
        solr.execute("POST /solr/tg_products/update?commit=true\n{\"delete\": {\"query\": \"*:*\"}}")
        val docs = (1..35).joinToString(",") { """{"id": "p${"%02d".format(it)}", "name_s": "Product $it", "price_i": $it, "cat_s": "${if (it % 2 == 0) "even" else "odd"}"}""" }
        solr.execute("POST /solr/tg_products/update?commit=true\n[$docs]")
        assertEquals(35L, solr.containers().single { it.name == "tg_products" }.count)
        assertTrue("\"uniqueKey\": \"id\"" in solr.definition(listOf("tg_products")))

        val path = listOf("tg_products")
        val first = solr.page(path, "", null, 20)
        assertEquals(35L, first.total)
        assertEquals("p01", first.records.first().document["id"]!!.jsonPrimitive.content)
        assertEquals(15, solr.page(path, "", first.cursor, 20).records.size)
        assertEquals(17, solr.page(path, "cat_s:even", null, 50).records.size)
        assertEquals(5, solr.page(path, "{\"query\": \"*:*\", \"filter\": \"price_i:[1 TO 5]\"}", null, 50).records.size)

        val record = solr.page(path, "id:p01", null, 1).records.single()
        val key = solr.put(path, record.key, JsonObject(record.document + ("name_s" to JsonPrimitive("Renamed"))))
        assertEquals("Renamed", solr.page(path, "id:p01", null, 1).records.single().document["name_s"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale version is refused") { solr.put(path, record.key, record.document) }
        assertFailsWith<IOException> { solr.put(path, null, buildJsonObject { put("id", "p02") }) }
        solr.put(path, null, buildJsonObject { put("id", "new"); put("name_s", "New") })
        assertEquals(2L, solr.delete(path, listOf(key, solr.page(path, "id:new", null, 1).records.single().key!!)))
        assertEquals(0L, solr.delete(path, listOf(key)))
        assertEquals(34L, solr.page(path, "", null, 1).total)
        val rows = solr.execute("POST /solr/tg_products/select\n{\"query\": \"cat_s:odd\", \"limit\": 3}") as ConsoleReply.Table
        assertEquals(3, rows.rows.rows.size)
    }

    @Test
    fun `qdrant scrolls points and edits payloads`() = server("it.qdrant", DatabaseType.Qdrant) { qdrant ->
        assertTrue(qdrant.server().startsWith("Qdrant 1."), qdrant.server())
        runCatching { qdrant.execute("DELETE /collections/tg_docs") }
        qdrant.execute("PUT /collections/tg_docs\n{\"vectors\": {\"size\": 3, \"distance\": \"Cosine\"}}")
        val points = (1..25).joinToString(",") { """{"id": $it, "vector": [0.$it, 0.5, 0.1], "payload": {"city": "${if (it % 5 == 0) "Hanoi" else "Hue"}", "n": $it}}""" }
        qdrant.execute("PUT /collections/tg_docs/points?wait=true\n{\"points\": [$points]}")
        assertEquals(25L, qdrant.containers().single { it.name == "tg_docs" }.count)
        assertTrue("\"distance\": \"Cosine\"" in qdrant.definition(listOf("tg_docs")))

        val path = listOf("tg_docs")
        val first = qdrant.page(path, "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals(5, qdrant.page(path, "", first.cursor, 20).records.size)
        assertEquals(5, qdrant.page(path, "{\"must\": [{\"key\": \"city\", \"match\": {\"value\": \"Hanoi\"}}]}", null, 50).records.size)

        val record = first.records.first()
        qdrant.put(path, record.key, JsonObject(record.document + ("city" to JsonPrimitive("Da Nang"))))
        assertFailsWith<IOException> { qdrant.put(path, record.key, record.document) }
        qdrant.put(path, null, buildJsonObject { put("id", 99); put("vector", JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2), JsonPrimitive(0.3)))); put("city", "New") })
        assertFailsWith<IOException> { qdrant.put(path, null, buildJsonObject { put("id", 99); put("vector", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1), JsonPrimitive(1)))) }) }
        val fresh = qdrant.page(path, "{\"must\": [{\"key\": \"city\", \"match\": {\"value\": \"New\"}}]}", null, 5).records
        assertEquals(1L, qdrant.delete(path, fresh.map { it.key!! }))
        assertEquals(0L, qdrant.delete(path, fresh.map { it.key!! }))
        val hits = qdrant.execute("POST /collections/tg_docs/points/search\n{\"vector\": [0.2, 0.5, 0.1], \"limit\": 3, \"with_payload\": true}") as ConsoleReply.Table
        assertEquals(listOf("id", "score"), hits.rows.columns.take(2).map { it.name })
        qdrant.execute("DELETE /collections/tg_docs")
    }

    @Test
    fun `rabbitmq lists queues, peeks and publishes`() = server("it.rabbitmq", DatabaseType.RabbitMQ, "tg", "tablegrip") { rabbit ->
        assertTrue(rabbit.server().startsWith("RabbitMQ 4."), rabbit.server())
        runCatching { rabbit.execute("DELETE /api/queues/%2F/tg_jobs") }
        rabbit.execute("PUT /api/queues/%2F/tg_jobs\n{\"durable\": true}")
        val path = listOf("/", "tg_jobs")
        repeat(3) { rabbit.put(path, null, buildJsonObject { put("payload", "job $it") }) }
        assertTrue(rabbit.containers().any { it.name == "/" && it.hasChildren })
        assertTrue(rabbit.containers(listOf("/")).any { it.name == "tg_jobs" && it.browsable })
        assertTrue("\"durable\": true" in rabbit.definition(path))
        val messages = rabbit.page(path, "", null, 10)
        assertEquals(listOf("job 0", "job 1", "job 2"), messages.records.map { it.document["payload"]!!.jsonPrimitive.content })
        assertNull(messages.records.first().key, "messages are read-only")
        assertEquals(3, rabbit.page(path, "", null, 10).records.size, "peeking requeues")
        assertFalse(rabbit.isRead("POST /api/queues/%2F/tg_jobs/get\n{}"))
        assertTrue(rabbit.execute("GET /api/queues") is ConsoleReply.Table)
        rabbit.execute("DELETE /api/queues/%2F/tg_jobs")
    }

    @Test
    fun `a wrong rabbitmq password is refused`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.rabbitmq")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "r", name = "r", type = DatabaseType.RabbitMQ, host = host, port = port.toInt(), user = "tg")
        assertFailsWith<IOException> { RecordStore.connect(config, "wrong", drivers = drivers) }
    }

    @Test
    fun `influxdb 3 lists databases and tables and pages rows`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.influxdb3")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "i3", name = "i3", type = DatabaseType.InfluxDB3, host = host, port = port.toInt())
        val now = System.currentTimeMillis() / 1000
        val lines = (0 until 30).joinToString("\n") { "tg_home,room=r${it % 3} temp=${20 + it}.5,hum=${it}i ${now - it * 60}" }
        RestClient("InfluxDB", config, host, port.toInt()).send("POST", "/api/v3/write_lp?db=tg_sensors&precision=second", lines, "text/plain").also { assertTrue(it.ok, it.body) }
        RecordStore.connect(config, null, drivers = drivers).use { influx ->
            assertTrue(influx.server().startsWith("InfluxDB 3 Core 3."), influx.server())
            assertTrue(influx.containers().any { it.name == "tg_sensors" })
            assertTrue(influx.containers().none { it.name.startsWith("_") })
            assertTrue(influx.containers(listOf("tg_sensors")).any { it.name == "tg_home" })
            assertTrue("\"temp\"" in influx.definition(listOf("tg_sensors", "tg_home")))
            val path = listOf("tg_sensors", "tg_home")
            val first = influx.page(path, "", null, 20)
            assertEquals("time", first.records.first().document.keys.first())
            assertEquals(10, influx.page(path, "", first.cursor, 20).records.size)
            assertEquals(10, influx.page(path, "room = 'r1'", null, 50).records.size)
            val reply = influx.execute("USE tg_sensors;\nSELECT count(*) AS n FROM tg_home") as ConsoleReply.Table
            assertEquals("30", reply.rows.rows.single().single())
            assertTrue(influx.execute("USE tg_sensors;\ninfluxql: SHOW MEASUREMENTS") is ConsoleReply.Table)
            assertFailsWith<IOException> { influx.execute("SELECT 1") }
            assertFalse(influx.canWrite(path))
        }
    }

    @Test
    fun `consul browses folders and writes with check-and-set`() = server("it.consul", DatabaseType.Consul) { consul ->
        assertTrue(consul.server().startsWith("Consul 1."), consul.server())
        consul.execute("DELETE /v1/kv/tg_app?recurse")
        val path = listOf("/", "tg_app", "config")
        repeat(25) { consul.put(path, null, buildJsonObject { put("key", "tg_app/config/key$it"); put("value", "value $it") }) }
        consul.put(listOf("/", "tg_app"), null, buildJsonObject { put("key", "tg_app/name"); put("value", "demo") })
        assertEquals(listOf("/"), consul.containers().map { it.name })
        assertTrue(consul.containers(listOf("/")).any { it.name == "tg_app" && it.hasChildren })
        assertEquals(listOf("config"), consul.containers(listOf("/", "tg_app")).map { it.name })

        val first = consul.page(path, "", null, 20)
        assertEquals(25L, first.total)
        assertEquals(5, consul.page(path, "", first.cursor, 20).records.size)
        assertEquals(1, consul.page(listOf("/", "tg_app"), "", null, 20).records.size, "only the keys directly in the folder")
        assertEquals(11, consul.page(path, "key1", null, 50).records.size)

        val record = consul.page(path, "key0", null, 1).records.single()
        consul.put(path, record.key, JsonObject(record.document + ("value" to JsonPrimitive("changed"))))
        assertFailsWith<IOException>("a stale index is refused") { consul.put(path, record.key, record.document) }
        assertFailsWith<IOException> { consul.put(path, null, buildJsonObject { put("key", "tg_app/config/key1"); put("value", "x") }) }
        val doomed = consul.page(path, "key2", null, 50).records.map { it.key!! }
        assertEquals(doomed.size.toLong(), consul.delete(path, doomed))
        assertEquals(0L, consul.delete(path, doomed))
        assertTrue(consul.execute("GET /v1/kv/tg_app/?recurse") is ConsoleReply.Table)
        consul.execute("DELETE /v1/kv/tg_app?recurse")
    }
}

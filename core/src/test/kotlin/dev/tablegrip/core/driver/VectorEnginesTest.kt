package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.vector.WeaviateClient
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Weaviate, Chroma and Milvus over their HTTP APIs. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-weaviate -p 58090:8080 -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true -e DEFAULT_VECTORIZER_MODULE=none \
 *   -e PERSISTENCE_DATA_PATH=/var/lib/weaviate -e CLUSTER_HOSTNAME=node1 semitechnologies/weaviate:1.28.2
 * docker run -d --name dg-it-chroma -p 58000:8000 chromadb/chroma:1.0.0
 * docker run -d --name dg-it-milvus -p 59530:19530 -e ETCD_USE_EMBED=true -e ETCD_DATA_DIR=/var/lib/milvus/etcd -e COMMON_STORAGETYPE=local \
 *   milvusdb/milvus:v2.5.4 milvus run standalone
 * -Pit.weaviate=localhost:58090 -Pit.chroma=localhost:58000 -Pit.milvus=localhost:59530
 * ```
 */
class VectorEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `json filters become graphql`() {
        val filter = Json.parseToJsonElement("""{"operator": "And", "operands": [{"path": ["city"], "operator": "Equal", "valueText": "Hanoi"}]}""")
        assertEquals("""{operator: And, operands: [{path: ["city"], operator: Equal, valueText: "Hanoi"}]}""", WeaviateClient.graphQl(filter))
    }

    private fun server(property: String, type: DatabaseType, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            RecordStore.connect(ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt()), null, drivers = drivers).use { test(it) }
        }

    private fun vector(vararg values: Double) = JsonArray(values.map(::JsonPrimitive))

    @Test
    fun `weaviate pages objects, filters and edits`() = server("it.weaviate", DatabaseType.Weaviate) { w ->
        assertTrue(w.server().startsWith("Weaviate 1."), w.server())
        runCatching { w.execute("DELETE /v1/schema/TgArticle") }
        w.execute("POST /v1/schema\n{\"class\": \"TgArticle\", \"vectorizer\": \"none\", \"properties\": [{\"name\": \"title\", \"dataType\": [\"text\"]}, {\"name\": \"city\", \"dataType\": [\"text\"]}, {\"name\": \"n\", \"dataType\": [\"int\"]}]}")
        val path = listOf("TgArticle")
        repeat(25) { w.put(path, null, buildJsonObject { put("title", "Article $it"); put("city", if (it % 5 == 0) "Hanoi" else "Hue"); put("n", it); put("vector", vector(it / 25.0, 0.5, 0.1)) }) }
        assertEquals(25L, w.containers().single { it.name == "TgArticle" }.count)
        assertTrue("\"vectorizer\": \"none\"" in w.definition(path))
        val first = w.page(path, "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals(5, w.page(path, "", first.cursor, 20).records.size)
        assertEquals(5, w.page(path, """{"path": ["city"], "operator": "Equal", "valueText": "Hanoi"}""", null, 50).records.size)

        val record = w.page(path, """{"path": ["n"], "operator": "Equal", "valueInt": 3}""", null, 5).records.single()
        w.put(path, record.key, JsonObject(record.document + ("title" to JsonPrimitive("Renamed"))))
        assertEquals("Renamed", w.page(path, """{"path": ["n"], "operator": "Equal", "valueInt": 3}""", null, 5).records.single().document["title"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale edit is refused") { w.put(path, record.key, record.document) }
        val doomed = w.page(path, """{"path": ["city"], "operator": "Equal", "valueText": "Hanoi"}""", null, 50).records.map { it.key!! }
        assertEquals(5L, w.delete(path, doomed))
        assertEquals(0L, w.delete(path, doomed))
        val rows = w.execute("{ Get { TgArticle(limit: 3) { title _additional { id } } } }") as ConsoleReply.Table
        assertEquals(listOf("id", "title"), rows.rows.columns.map { it.name })
        assertTrue(w.isRead("{ Get { TgArticle { title } } }"))
        w.execute("DELETE /v1/schema/TgArticle")
    }

    @Test
    fun `chroma pages records, filters and edits`() = server("it.chroma", DatabaseType.Chroma) { c ->
        assertTrue(c.server().startsWith("Chroma 1."), c.server())
        val base = "/api/v2/tenants/default_tenant/databases/default_database/collections"
        runCatching { c.execute("DELETE $base/tg_docs") }
        c.execute("POST $base\n{\"name\": \"tg_docs\"}")
        val path = listOf("tg_docs")
        repeat(25) { c.put(path, null, buildJsonObject { put("id", "d$it"); put("document", "Document $it"); put("metadata", buildJsonObject { put("city", if (it % 5 == 0) "Hanoi" else "Hue"); put("n", it) }); put("embedding", vector(it / 25.0, 0.5, 0.1)) }) }
        assertEquals(25L, c.containers().single { it.name == "tg_docs" }.count)
        val first = c.page(path, "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals(5, c.page(path, "", first.cursor, 20).records.size)
        assertEquals(5, c.page(path, """{"city": "Hanoi"}""", null, 50).records.size)
        assertFailsWith<IOException> { c.put(path, null, buildJsonObject { put("id", "d1"); put("embedding", vector(1.0, 1.0, 1.0)) }) }

        val record = c.page(path, """{"n": 3}""", null, 5).records.single()
        c.put(path, record.key, JsonObject(record.document + ("document" to JsonPrimitive("Renamed"))))
        assertEquals("Renamed", c.page(path, """{"n": 3}""", null, 5).records.single().document["document"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale edit is refused") { c.put(path, record.key, record.document) }
        val doomed = c.page(path, """{"city": "Hanoi"}""", null, 50).records.map { it.key!! }
        assertEquals(5L, c.delete(path, doomed))
        assertEquals(0L, c.delete(path, doomed))
        assertTrue(c.execute("GET $base") is ConsoleReply.Table)
        c.execute("DELETE $base/tg_docs")
    }

    @Test
    fun `milvus queries entities and upserts them`() = server("it.milvus", DatabaseType.Milvus) { m ->
        assertTrue(m.server().startsWith("Milvus"), m.server())
        runCatching { m.execute("POST /v2/vectordb/collections/drop\n{\"collectionName\": \"tg_docs\"}") }
        m.execute("POST /v2/vectordb/collections/create\n{\"collectionName\": \"tg_docs\", \"dimension\": 3, \"primaryFieldName\": \"id\", \"idType\": \"Int64\", \"vectorFieldName\": \"vector\", \"metricType\": \"COSINE\", \"enableDynamicField\": true}")
        val path = listOf("tg_docs")
        repeat(25) { m.put(path, null, buildJsonObject { put("id", it + 1); put("vector", vector((it + 1) / 25.0, 0.5, 0.1)); put("city", if (it % 5 == 0) "Hanoi" else "Hue") }) }
        // Milvus reads with bounded consistency: new entities show up within a few seconds.
        suspend fun settled(filter: String, size: Int) {
            val deadline = System.currentTimeMillis() + 20_000
            while (m.page(path, filter, null, 100).records.size != size && System.currentTimeMillis() < deadline) Thread.sleep(500)
        }
        settled("", 25)
        assertTrue(m.containers().any { it.name == "tg_docs" })
        assertTrue("\"vector\"" in m.definition(path))
        val first = m.page(path, "", null, 20)
        assertEquals(20, first.records.size)
        assertTrue("vector" !in first.records.first().document, "vectors stay out of the grid")
        assertEquals(5, m.page(path, "", first.cursor, 20).records.size)
        assertEquals(5, m.page(path, "city == \"Hanoi\"", null, 50).records.size)

        val record = m.page(path, "id == 3", null, 5).records.single()
        m.put(path, record.key, JsonObject(record.document + ("city" to JsonPrimitive("Da Nang"))))
        settled("city == \"Da Nang\"", 1)
        assertEquals("Da Nang", m.page(path, "id == 3", null, 5).records.single().document["city"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale edit is refused") { m.put(path, record.key, record.document) }
        assertFailsWith<IOException> { m.put(path, null, buildJsonObject { put("id", 3); put("vector", vector(1.0, 1.0, 1.0)) }) }
        val doomed = m.page(path, "city == \"Hanoi\"", null, 50).records.map { it.key!! }
        assertEquals(5L, m.delete(path, doomed))
        settled("city == \"Hanoi\"", 0)
        assertEquals(0L, m.delete(path, doomed))
        val rows = m.execute("POST /v2/vectordb/entities/query\n{\"collectionName\": \"tg_docs\", \"filter\": \"id < 5\", \"outputFields\": [\"id\", \"city\"]}") as ConsoleReply.Table
        assertTrue(rows.rows.rows.isNotEmpty())
        assertFailsWith<IOException> { m.execute("POST /v2/vectordb/collections/describe\n{\"collectionName\": \"tg_missing\"}") }
        m.execute("POST /v2/vectordb/collections/drop\n{\"collectionName\": \"tg_docs\"}")
    }
}

package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.driver.http.HttpStatement
import dev.tablegrip.core.driver.search.ElasticClient
import dev.tablegrip.core.driver.search.MeiliClient
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.drivers.DriverSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Elasticsearch, OpenSearch and Meilisearch over their REST APIs. The server tests are skipped unless the properties are
 * passed:
 * ```
 * docker run -d --name dg-it-es -p 59200:9200 -e discovery.type=single-node -e xpack.security.enabled=false docker.elastic.co/elasticsearch/elasticsearch:8.17.4
 * docker run -d --name dg-it-os -p 59201:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true opensearchproject/opensearch:2.19.1
 * docker run -d --name dg-it-meili -p 57700:7700 -e MEILI_MASTER_KEY=tablegrip-test-key getmeili/meilisearch:v1.13.3
 * -Pit.elasticsearch=localhost:59200 -Pit.opensearch=localhost:59201 -Pit.meilisearch=localhost:57700
 * ```
 */
class SearchEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `search clients are built in`() {
        listOf(DriverId.ElasticRest, DriverId.Meilisearch).forEach { assertEquals(DriverResolution.Ready(it, null, DriverSource.BuiltIn), drivers.resolve(it)) }
        assertEquals(DatabaseType.Elasticsearch.drivers, DatabaseType.OpenSearch.drivers)
    }

    @Test
    fun `dev tools requests are parsed`() {
        assertEquals(HttpStatement("GET", "/_cat/indices", null), HttpStatement.parse("get _cat/indices"))
        assertEquals(HttpStatement("POST", "/orders/_search?size=5", "{\"query\": {}}"), HttpStatement.parse("# find\nPOST /orders/_search?size=5\n{\"query\": {}}\n"))
        assertFailsWith<IllegalArgumentException> { HttpStatement.parse("orders/_search") }
    }

    @Test
    fun `elasticsearch reads are told from writes`() {
        listOf(
            "GET /orders/_search", "HEAD /orders", "POST /orders/_search\n{}", "POST /orders/_count", "POST /_sql?format=json\n{\"query\": \"SELECT 1\"}",
            "POST /_msearch\n{}\n{}", "POST /orders/_validate/query", "POST /_search/scroll", "POST /_plugins/_sql\n{\"query\": \"SELECT * FROM orders\"}",
        ).forEach { assertTrue(ElasticClient.isReadRequest(it), it) }
        listOf(
            "PUT /orders", "DELETE /orders", "POST /orders/_doc\n{}", "POST /orders/_update_by_query", "POST /orders/_delete_by_query", "POST /_bulk",
            "POST /_plugins/_sql\n{\"query\": \"DELETE FROM orders\"}", "POST /orders/_close", "nonsense",
        ).forEach { assertFalse(ElasticClient.isReadRequest(it), it) }
    }

    @Test
    fun `the query field becomes a search body`() {
        assertEquals("{\"query\":{\"match_all\":{}}}", ElasticClient.searchBody(" ").toString())
        assertEquals("{\"query\":{\"query_string\":{\"query\":\"status:paid\"}}}", ElasticClient.searchBody("status:paid").toString())
        assertEquals("{\"query\":{\"term\":{\"a\":1}}}", ElasticClient.searchBody("{\"term\": {\"a\": 1}}").toString())
        assertEquals("{\"query\":{\"term\":{\"a\":1}},\"sort\":[\"b\"]}", ElasticClient.searchBody("{\"query\": {\"term\": {\"a\": 1}}, \"sort\": [\"b\"]}").toString())
    }

    @Test
    fun `meilisearch reads are told from writes`() {
        listOf("GET /indexes", "POST /indexes/movies/search\n{}", "POST /multi-search", "POST /indexes/movies/documents/fetch").forEach { assertTrue(MeiliClient.isReadRequest(it), it) }
        listOf("POST /indexes/movies/documents\n[]", "DELETE /indexes/movies", "PATCH /indexes/movies/settings", "POST /indexes").forEach { assertFalse(MeiliClient.isReadRequest(it), it) }
    }

    private fun server(property: String, type: DatabaseType, password: String? = null, readOnly: Boolean = false, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), readOnly = readOnly)
            RecordStore.connect(config, password, drivers = drivers).use { test(it) }
        }

    @Test
    fun `elasticsearch browses, searches and writes`() = elastic("it.elasticsearch", DatabaseType.Elasticsearch, "Elasticsearch 8")

    @Test
    fun `opensearch browses, searches and writes`() = elastic("it.opensearch", DatabaseType.OpenSearch, "OpenSearch 2")

    private fun elastic(property: String, type: DatabaseType, expected: String) = server(property, type) { es ->
        assertTrue(es.server().startsWith(expected), es.server())
        runCatching { es.execute("DELETE /tg_orders") }
        es.execute("PUT /tg_orders\n{\"mappings\": {\"properties\": {\"status\": {\"type\": \"keyword\"}, \"total\": {\"type\": \"double\"}, \"note\": {\"type\": \"text\"}}}}")
        es.execute("POST /_aliases\n{\"actions\": [{\"add\": {\"index\": \"tg_orders\", \"alias\": \"tg_current\"}}]}")
        val bulk = (1..45).joinToString("\n") { "{\"index\": {\"_id\": \"o$it\"}}\n{\"status\": \"${if (it % 3 == 0) "paid" else "open"}\", \"total\": $it.5, \"note\": \"order number $it\"}" }
        es.execute("POST /tg_orders/_bulk?refresh=true\n$bulk")

        val containers = es.containers()
        val index = containers.single { it.name == "tg_orders" }
        assertEquals("index", index.kind)
        assertEquals(45L, index.count)
        assertEquals("alias", containers.single { it.name == "tg_current" }.kind)
        assertTrue("\"status\"" in es.definition(listOf("tg_orders")))

        // Pages cover every document once.
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = es.page(listOf("tg_orders"), "", cursor, 20)
            assertEquals(45L, page.total)
            ids += page.records.map { it.meta["_id"]!!.jsonPrimitive.content }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals(45, ids.toSet().size)
        assertEquals(15, es.page(listOf("tg_orders"), "status:paid", null, 50).records.size)
        assertEquals(15, es.page(listOf("tg_orders"), "{\"term\": {\"status\": \"paid\"}}", null, 50).records.size)
        val sorted = es.page(listOf("tg_orders"), "{\"query\": {\"match_all\": {}}, \"sort\": [{\"total\": \"desc\"}]}", null, 1).records.single()
        assertEquals("o45", sorted.meta["_id"]!!.jsonPrimitive.content)
        // Through the alias, the index shows, since writes need it.
        assertEquals("tg_orders", es.page(listOf("tg_current"), "", null, 1).records.single().meta["_index"]!!.jsonPrimitive.content)

        // Writes: create with and without an _id, replace, and a stale replace that must fail.
        val created = es.put(listOf("tg_orders"), null, buildJsonObject { put("_id", "new1"); put("status", "open"); put("total", 1.0) })
        assertEquals("new1", created["_id"]!!.jsonPrimitive.content)
        assertNotNull(es.put(listOf("tg_orders"), null, buildJsonObject { put("status", "open") })["_id"])
        val record = es.page(listOf("tg_orders"), "_id:o1", null, 5).records.single()
        val edited = JsonObject(record.document + ("status" to JsonPrimitive("paid")))
        es.put(listOf("tg_orders"), record.key, edited)
        assertEquals("paid", es.page(listOf("tg_orders"), "_id:o1", null, 5).records.single().document["status"]!!.jsonPrimitive.content)
        assertFailsWith<java.io.IOException>("a stale version doesn't overwrite") { es.put(listOf("tg_orders"), record.key, record.document) }
        assertFailsWith<java.io.IOException> { es.put(listOf("tg_orders"), null, buildJsonObject { put("_id", "new1") }) }

        val fresh = es.page(listOf("tg_orders"), "_id:o1 OR _id:o2", null, 5).records
        assertEquals(2L, es.delete(listOf("tg_orders"), fresh.map { it.key!! }))
        assertEquals(0L, es.delete(listOf("tg_orders"), fresh.map { it.key!! }), "already gone")
        assertEquals(45L, es.page(listOf("tg_orders"), "", null, 1).total)

        // The console: hits and _cat lists as rows, the rest as JSON.
        val hits = es.execute("GET /tg_orders/_search\n{\"query\": {\"term\": {\"status\": \"paid\"}}, \"size\": 3}") as ConsoleReply.Table
        assertEquals(3, hits.rows.rows.size)
        assertTrue(hits.rows.columns.map { it.name }.containsAll(listOf("_id", "status", "total")))
        assertTrue(es.execute("GET /_cat/indices?format=json") is ConsoleReply.Table)
        assertTrue(es.execute("GET /tg_orders/_mapping") is ConsoleReply.Text)
        assertFailsWith<java.io.IOException> { es.execute("GET /tg_missing/_search") }
        es.execute("DELETE /tg_orders")
    }

    @Test
    fun `a read-only profile only searches`() = server("it.elasticsearch", DatabaseType.Elasticsearch, readOnly = true) { es ->
        assertTrue(es.execute("GET /_cat/indices?format=json") is ConsoleReply)
        assertFailsWith<ReadOnlyViolationException> { es.execute("PUT /tg_ro") }
        assertFailsWith<ReadOnlyViolationException> { es.put(listOf("x"), null, JsonObject(emptyMap())) }
        assertFalse(es.canWrite(listOf("x")))
    }

    @Test
    fun `meilisearch browses, searches and writes`() = server("it.meilisearch", DatabaseType.Meilisearch, password = "tablegrip-test-key") { meili ->
        assertTrue(meili.server().startsWith("Meilisearch 1."), meili.server())
        runCatching { meili.execute("DELETE /indexes/tg_movies") }
        meili.execute("POST /indexes\n{\"uid\": \"tg_movies\", \"primaryKey\": \"id\"}")
        meili.execute("PUT /indexes/tg_movies/settings/filterable-attributes\n[\"year\"]")
        val documents = (1..30).map { buildJsonObject { put("id", it); put("title", if (it % 5 == 0) "Space odyssey $it" else "Drama $it"); put("year", 1990 + it) } }
        meili.put(listOf("tg_movies"), null, documents.first())
        meili.execute("POST /indexes/tg_movies/documents\n" + kotlinx.serialization.json.JsonArray(documents.drop(1)))
        // The batch above is a task too; a write through put() waits for every earlier task of the index.
        meili.put(listOf("tg_movies"), null, buildJsonObject { put("id", 31); put("title", "Late"); put("year", 2030) })

        assertEquals(31L, meili.containers().single { it.name == "tg_movies" }.count)
        assertTrue("\"primaryKey\": \"id\"" in meili.definition(listOf("tg_movies")))
        val first = meili.page(listOf("tg_movies"), "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals(31L, first.total)
        assertEquals(11, meili.page(listOf("tg_movies"), "", first.cursor, 20).records.size)
        assertNull(meili.page(listOf("tg_movies"), "", first.cursor, 20).cursor)
        assertEquals(6, meili.page(listOf("tg_movies"), "space", null, 20).records.size)
        assertEquals(10, meili.page(listOf("tg_movies"), "{\"filter\": \"year > 2011\"}", null, 50).records.size)

        val record = meili.page(listOf("tg_movies"), "", null, 1).records.single()
        assertEquals(buildJsonObject { put("id", 1) }, record.key)
        meili.put(listOf("tg_movies"), record.key, JsonObject(record.document + ("title" to JsonPrimitive("Renamed"))))
        assertEquals("Renamed", meili.page(listOf("tg_movies"), "", null, 1).records.single().document["title"]!!.jsonPrimitive.content)
        assertFailsWith<java.io.IOException> { meili.put(listOf("tg_movies"), record.key, JsonObject(record.document + ("id" to JsonPrimitive(99)))) }
        assertFailsWith<java.io.IOException> { meili.put(listOf("tg_movies"), null, buildJsonObject { put("title", "no id") }) }
        assertEquals(2L, meili.delete(listOf("tg_movies"), listOf(buildJsonObject { put("id", 1) }, buildJsonObject { put("id", 2) })))
        assertEquals(29L, meili.page(listOf("tg_movies"), "", null, 1).total)

        val hits = meili.execute("POST /indexes/tg_movies/search\n{\"q\": \"drama\", \"limit\": 4}") as ConsoleReply.Table
        assertEquals(4, hits.rows.rows.size)
        assertTrue(meili.execute("GET /indexes/tg_movies/settings") is ConsoleReply.Text)
        meili.execute("DELETE /indexes/tg_movies")
    }

    @Test
    fun `a wrong meilisearch key is refused`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.meilisearch")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "meili", name = "meili", type = DatabaseType.Meilisearch, host = host, port = port.toInt())
        val error = assertFailsWith<java.io.IOException> { RecordStore.connect(config, "wrong", drivers = drivers) }
        assertTrue("credentials" in error.message.orEmpty(), error.message)
    }
}

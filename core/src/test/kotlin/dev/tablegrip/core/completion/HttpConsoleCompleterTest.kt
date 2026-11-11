package dev.tablegrip.core.completion

import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.driver.search.ElasticClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpConsoleCompleterTest {
    private val elastic = StatementCompleter.of(EngineFamily.Elasticsearch)!!
    private val catalog =
        FakeCatalog(
            containers = mapOf(emptyList<String>() to listOf("orders", "products")),
            fields = mapOf(listOf("orders") to listOf("status", "total", "created_at"), listOf("products") to listOf("title")),
        )

    private fun labels(text: String, completer: StatementCompleter = elastic, isRead: ((String) -> Boolean)? = null) = completer.labels(text, catalog, isRead)

    @Test
    fun `methods on the request line, only the reading ones for a read-only profile`() {
        assertEquals(listOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH"), labels("|"))
        assertEquals(listOf("POST", "PUT", "PATCH"), labels("P|"))
        val read = labels("|", isRead = ElasticClient.Companion::isReadRequest)
        assertTrue("GET" in read && "POST" in read && "DELETE" !in read && "PUT" !in read, "$read")
    }

    @Test
    fun `paths segment by segment, with index names`() {
        val first = labels("GET /|")
        assertTrue(first.containsAll(listOf("_cat", "_cluster", "_search", "orders", "products")), "$first")
        assertEquals("GET /_cat/|", elastic.accept("GET /_ca|", catalog))
        assertTrue(labels("GET /_cat/|").containsAll(listOf("indices", "health", "nodes")))
        // `GET /orders` is a request of its own, so no slash follows.
        assertEquals("GET /orders|", elastic.accept("GET /ord|", catalog, "orders"))
        assertEquals("POST /orders/|", elastic.accept("POST /ord|", catalog, "orders"))
        assertTrue(labels("POST /orders/|").containsAll(listOf("_search", "_count", "_doc", "_delete_by_query")))
        assertEquals("GET /orders|", elastic.accept("GET ord|", catalog, "orders").replace("/orders/", "/orders"))
        assertTrue(labels("GET /orders/_search?|").isEmpty())
    }

    @Test
    fun `read-only paths leave out the endpoints that write`() {
        val read = labels("POST /orders/|", isRead = ElasticClient.Companion::isReadRequest)
        assertTrue("_search" in read && "_count" in read, "$read")
        assertFalse("_delete_by_query" in read || "_doc" in read || "_update_by_query" in read, "$read")
    }

    @Test
    fun `query DSL keys in a search body`() {
        assertTrue(labels("GET /orders/_search\n{\n  |\n}").containsAll(listOf("query", "size", "sort", "aggs", "_source")))
        assertEquals("GET /orders/_search\n{\"query\": {|}}", elastic.accept("GET /orders/_search\n{\"qu|}", catalog).replace("}}", "}}"))
        assertTrue(labels("GET /orders/_search\n{\"query\": {|}}").containsAll(listOf("match", "bool", "term", "range")))
        assertTrue(labels("GET /orders/_search\n{\"query\": {\"bool\": {\"must\": [{|}]}}}").containsAll(listOf("match", "term")))
        assertEquals(listOf("must", "must_not"), labels("GET /orders/_search\n{\"query\": {\"bool\": {\"mu|").take(2))
    }

    @Test
    fun `fields of the index where a field goes`() {
        assertTrue(labels("GET /orders/_search\n{\"query\": {\"match\": {|}}}").containsAll(listOf("status", "total", "created_at")))
        assertEquals("GET /orders/_search\n{\"query\": {\"match\": {\"status\": |}}}", elastic.accept("GET /orders/_search\n{\"query\": {\"match\": {\"st|}}}", catalog))
        assertEquals("GET /orders/_search\n{\"query\": {\"range\": {\"total\": {|}}}}", elastic.accept("GET /orders/_search\n{\"query\": {\"range\": {\"to|}}}", catalog))
        assertEquals(listOf("status", "total", "created_at"), labels("GET /orders/_search\n{\"query\": {\"exists\": {\"field\": \"|\"}}}"))
        assertTrue(labels("GET /orders/_search\n{\"sort\": [{\"total\": {\"order\": |}}]}").containsAll(listOf("asc", "desc")))
        assertTrue(labels("GET /orders/_search\n{\"aggs\": {\"by_status\": {\"terms\": {\"field\": |}}}}").contains("status"))
    }

    @Test
    fun `a key whose colon is already there inserts only the name`() {
        assertEquals("GET /orders/_search\n{\"size\"|: 10}", elastic.accept("GET /orders/_search\n{\"si|: 10}", catalog))
    }

    @Test
    fun `Mango selectors for CouchDB, search parameters for Meilisearch`() {
        val couch = StatementCompleter.of(EngineFamily.CouchDB)!!
        assertTrue(labels("POST /orders/_find\n{|}", couch).containsAll(listOf("selector", "fields", "sort", "limit")))
        assertTrue(labels("POST /orders/_find\n{\"selector\": {|}}", couch).containsAll(listOf("\$and", "\$or", "status")))
        assertTrue(labels("POST /orders/_find\n{\"selector\": {\"total\": {|}}}", couch).containsAll(listOf("\$gt", "\$in", "\$exists")))
        val meili = StatementCompleter.of(EngineFamily.Meilisearch)!!
        assertTrue(labels("POST /indexes/|", meili).containsAll(listOf("orders", "products")))
        assertTrue(labels("POST /indexes/orders/search\n{|}", meili).containsAll(listOf("q", "filter", "limit", "sort", "attributesToRetrieve")))
        assertEquals(listOf("status", "total", "created_at"), labels("POST /indexes/orders/search\n{\"attributesToRetrieve\": [|]}", meili))
    }

    @Test
    fun `Weaviate's GraphQL takes classes and their properties`() {
        val weaviate = StatementCompleter.of(EngineFamily.Weaviate)!!
        assertEquals(listOf("Get", "Aggregate", "Explore"), labels("{\n  |", weaviate))
        assertEquals(listOf("orders", "products"), labels("{\n  Get {\n    |", weaviate))
        assertTrue(labels("{\n  Get {\n    products(limit: 10) {\n      |", weaviate).containsAll(listOf("title", "_additional")))
        assertTrue(labels("{\n  Get {\n    products(|", weaviate).containsAll(listOf("limit", "where", "nearText")))
        assertTrue(labels("GET /v1/schema/|", weaviate).containsAll(listOf("orders", "products")))
    }

    @Test
    fun `path names are encoded as segments`() {
        assertEquals("%2F", HttpConsoleCompleter.encode("/"))
        assertEquals("my%20index", HttpConsoleCompleter.encode("my index"))
    }
}

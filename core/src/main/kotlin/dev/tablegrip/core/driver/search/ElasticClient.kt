package dev.tablegrip.core.driver.search

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.HttpStatement
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.driver.http.RestResponse
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Elasticsearch and OpenSearch through their REST API, which TableGrip speaks itself. A user and password are HTTP
 * basic auth; the `apiKey` property sends an Elasticsearch API key instead (the encoded form Kibana shows). Indices,
 * aliases and data streams are the containers; documents are read with `_search`, from/size paging (so up to the index's
 * `max_result_window`, 10,000 by default), and written by `_id` with `if_seq_no`/`if_primary_term`, so a document
 * changed since it was read is never overwritten. Writes wait for a refresh, so the browser sees them on reload.
 */
class ElasticClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient(config.type.displayName, config, host, port)
        val apiKey = config.properties["apiKey"]?.trim()?.takeIf { it.isNotEmpty() }
        rest.authorization =
            when {
                apiKey != null -> "ApiKey $apiKey"
                config.user.isNotBlank() -> RestClient.basic(config.user.trim(), password)
                else -> null
            }
        return Connection(config, rest).also { it.info() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val engine = config.type.displayName
        private val showHidden = config.properties["showHidden"]?.toBoolean() == true

        /** Each container's kind, from the last listing: aliases and data streams are written differently. */
        private val kinds = ConcurrentHashMap<String, String>()

        @Volatile private var description: String? = null

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** Sends a request, failing with the server's own reason for an error status. */
        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement = checked(rest.send(method, path, body?.toString())).json()

        private fun checked(response: RestResponse): RestResponse {
            if (response.ok) return response
            throw IOException("$engine: ${errorReason(response)}")
        }

        /** `GET /`: the distribution and version, which is also how connecting checks the server is one. */
        internal fun info(): String {
            val response = rest.send("GET", "/")
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", engine, errorReason(response)))
            val json = runCatching { checked(response).json().jsonObject }.getOrElse {
                throw IOException(tr("{0} doesn't look like Elasticsearch or OpenSearch: {1}", rest.base, response.body.take(120)))
            }
            val version = json["version"]?.jsonObject ?: throw IOException(tr("{0} doesn't look like Elasticsearch or OpenSearch: {1}", rest.base, response.body.take(120)))
            val name = if (version["distribution"]?.jsonPrimitive?.contentOrNull == "opensearch") "OpenSearch" else "Elasticsearch"
            val cluster = json["cluster_name"]?.jsonPrimitive?.contentOrNull
            return ("$name ${version["number"]?.jsonPrimitive?.contentOrNull.orEmpty()}" + (cluster?.let { " ($it)" } ?: "")).also { description = it }
        }

        override suspend fun server(): String = io { description ?: info() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val visible = { name: String -> showHidden || !name.startsWith(".") }
                val indices =
                    call("GET", "/_cat/indices?format=json&h=index,docs.count,health,status&s=index").jsonArray.map { it.jsonObject }.filter { visible(it.string("index")) }
                        .map { row ->
                            val notes = listOfNotNull(row["health"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "green" }, row["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "open" })
                            RecordContainer(listOf(row.string("index")), "index", row["docs.count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(), notes.joinToString(" · ").ifEmpty { null })
                        }
                val aliases =
                    call("GET", "/_cat/aliases?format=json&h=alias,index").jsonArray.map { it.jsonObject }.filter { visible(it.string("alias")) }
                        .groupBy({ it.string("alias") }, { it.string("index") })
                        .map { (alias, targets) -> RecordContainer(listOf(alias), "alias", detail = "→ " + targets.sorted().joinToString(", ")) }
                // Data streams need their own privilege, and older servers have none: without them the list just lacks them.
                val streams =
                    runCatching { call("GET", "/_data_stream").jsonObject["data_streams"]?.jsonArray.orEmpty() }.getOrDefault(emptyList())
                        .map { it.jsonObject.string("name") }.filter(visible)
                        .map { RecordContainer(listOf(it), "data stream") }
                (indices + aliases + streams).sortedBy { it.name }.onEach { kinds[it.name] = it.kind }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(call("GET", "/${segment(container.single())}")) }

        override val queryLanguage =
            RecordLanguage(
                tr("Query"),
                tr("status:active AND total:>10   or a query DSL object, e.g. {\"match\": {\"title\": \"shoes\"}}"),
                tr("Lucene query string syntax, or JSON: a query (match, term, bool…) or a whole search body with query, sort and _source."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val from = cursor?.toIntOrNull() ?: 0
                val body = JsonObject(searchBody(query) + mapOf("from" to JsonPrimitive(from), "size" to JsonPrimitive(limit), "seq_no_primary_term" to JsonPrimitive(true)))
                val reply = call("POST", "/${segment(container.single())}/_search", body).jsonObject
                val hits = reply["hits"]?.jsonObject
                // Through an alias, a data stream or a pattern the index shows, since that is where a write goes.
                val records = hits?.get("hits")?.jsonArray.orEmpty().map { record(it.jsonObject, showIndex = it.jsonObject["_index"]?.jsonPrimitive?.contentOrNull != container.single()) }
                val total = hits?.get("total")?.let { if (it is JsonObject) it["value"]?.jsonPrimitive?.longOrNull else it.jsonPrimitive.longOrNull }
                val lowerBound = (hits?.get("total") as? JsonObject)?.get("relation")?.jsonPrimitive?.contentOrNull == "gte"
                val next = from + records.size
                val more = records.size == limit && (total == null || lowerBound || next < total)
                RecordPage(records, if (more) next.toString() else null, total, lowerBound)
            }

        override fun canWrite(container: List<String>): Boolean = kinds[container.singleOrNull()] != "data stream"

        override suspend fun template(container: List<String>): JsonObject =
            io {
                val mappings = call("GET", "/${segment(container.single())}/_mapping").jsonObject
                val fields = mappings.values.flatMap { (it.jsonObject["mappings"] as? JsonObject)?.get("properties")?.jsonObject?.keys.orEmpty() }.distinct()
                JsonObject(fields.associateWith { JsonNull })
            }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val id = document["_id"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: throw IOException(tr("_id must be a string")) }
                val source = JsonObject(document - "_id")
                val reply =
                    if (key == null) {
                        val index = segment(container.single())
                        if (id == null) call("POST", "/$index/_doc?refresh=wait_for", source)
                        else conflictChecked { rest.send("PUT", "/$index/_create/${segment(id)}?refresh=wait_for", source.toString()) }
                    } else {
                        val existing = key.string("_id")
                        if (id != null && id != existing) throw IOException(tr("A document's _id can't change; add a new document instead."))
                        conflictChecked { rest.send("PUT", "/${segment(key.string("_index"))}/_doc/${segment(existing)}?${versionCheck(key)}refresh=wait_for", source.toString()) }
                    }.jsonObject
                keyOf(reply)
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                keys.count { key ->
                    val path = "/${segment(key.string("_index"))}/_doc/${segment(key.string("_id"))}"
                    val response = rest.send("DELETE", "$path?${versionCheck(key)}refresh=wait_for")
                    when {
                        response.status == 404 -> false
                        // A version check also fails on a document deleted since: that one is simply gone.
                        response.status == 409 && rest.send("HEAD", path).status == 404 -> false
                        response.status == 409 -> throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", key.string("_id")))
                        else -> checked(response).json().jsonObject["result"]?.jsonPrimitive?.contentOrNull == "deleted"
                    }
                }.toLong()
            }

        private fun conflictChecked(send: () -> RestResponse): JsonElement {
            val response = send()
            if (response.status == 409) throw IOException(tr("The document was changed or created by someone else since it was read; reload and try again."))
            return checked(response).json()
        }

        override val consoleLanguage =
            RecordLanguage(
                tr("Dev Tools"),
                "GET /orders/_search\n{\n  \"query\": { \"match\": { \"status\": \"paid\" } }\n}",
                tr("Requests as in Kibana's Dev Tools: a method and a path, then a JSON body. Search replies show as rows, SQL replies as tables, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            "GET /${container.firstOrNull() ?: "_all"}/_search\n{\n  \"query\": { \"match_all\": {} },\n  \"size\": 20\n}"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val request = HttpStatement.parse(statement)
                val ndjson = request.endpoint.substringAfterLast('/').let { it == "_bulk" || it == "_msearch" }
                val started = System.currentTimeMillis()
                val response = rest.send(request.method, request.path, request.body?.let { if (ndjson) it.trimEnd() + "\n" else it }, if (ndjson) "application/x-ndjson" else "application/json")
                val elapsed = System.currentTimeMillis() - started
                if (!response.ok) {
                    val detail = runCatching { JsonTables.pretty(response.json()) }.getOrDefault(response.body)
                    throw IOException("${response.status}: $detail")
                }
                val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull() ?: return@io ConsoleReply.Text(response.body.ifEmpty { "${response.status}" }, elapsed)
                tableOf(json, elapsed) ?: ConsoleReply.Text(JsonTables.pretty(json), elapsed)
            }

        override fun isRead(statement: String): Boolean = isReadRequest(statement)

        override fun close() = Unit

        private fun record(hit: JsonObject, showIndex: Boolean): Record {
            val key = buildJsonObject {
                put("_index", hit.string("_index"))
                put("_id", hit.string("_id"))
                hit["_seq_no"]?.let { put("_seq_no", it) }
                hit["_primary_term"]?.let { put("_primary_term", it) }
            }
            val meta = buildJsonObject {
                put("_id", hit.string("_id"))
                if (showIndex) put("_index", hit.string("_index"))
                hit["_score"]?.takeIf { it !is JsonNull }?.let { put("_score", it) }
            }
            return Record(key, hit["_source"] as? JsonObject ?: JsonObject(emptyMap()), meta)
        }

        private fun keyOf(reply: JsonObject) = buildJsonObject {
            put("_index", reply.string("_index"))
            put("_id", reply.string("_id"))
            reply["_seq_no"]?.let { put("_seq_no", it) }
            reply["_primary_term"]?.let { put("_primary_term", it) }
        }

        private fun versionCheck(key: JsonObject): String {
            val seqNo = key["_seq_no"]?.jsonPrimitive?.longOrNull ?: return ""
            val term = key["_primary_term"]?.jsonPrimitive?.longOrNull ?: return ""
            return "if_seq_no=$seqNo&if_primary_term=$term&"
        }

        /** Search hits, SQL replies (Elasticsearch's columns and rows, OpenSearch's schema and datarows) and `_cat` lists as rows. */
        private fun tableOf(json: JsonElement, elapsed: Long): ConsoleReply.Table? {
            if (json is JsonArray && json.isNotEmpty() && json.all { it is JsonObject }) return ConsoleReply.Table(JsonTables.rows(json.map { it.jsonObject }, elapsedMillis = elapsed))
            val reply = json as? JsonObject ?: return null
            val hits = (reply["hits"] as? JsonObject)?.get("hits") as? JsonArray
            if (hits != null && hits.isNotEmpty() && reply["aggregations"] == null) {
                val total = (reply["hits"]?.jsonObject?.get("total") as? JsonObject)?.get("value")?.jsonPrimitive?.longOrNull
                return ConsoleReply.Table(
                    JsonTables.records(hits.map { record(it.jsonObject, showIndex = true) }, elapsed),
                    note = total?.let { tr("{0} of {1} hits", hits.size, it) },
                )
            }
            buckets(reply["aggregations"] as? JsonObject, elapsed)?.let { return it }
            val columns = (reply["columns"] ?: reply["schema"]) as? JsonArray
            val rows = (reply["rows"] ?: reply["datarows"]) as? JsonArray
            if (columns != null && rows != null) {
                val names = columns.map { it.jsonObject.string("name") }
                val types = columns.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                return ConsoleReply.Table(JsonTables.columnar(names, types, rows.map { it.jsonArray }, elapsed))
            }
            return null
        }
    }

    companion object {
        /** A search body from what's typed in the query field: blank for all, a query string, a query, or a whole body. */
        internal fun searchBody(query: String): JsonObject {
            val text = query.trim()
            if (text.isEmpty()) return buildJsonObject { put("query", buildJsonObject { put("match_all", JsonObject(emptyMap())) }) }
            if (!text.startsWith("{")) return buildJsonObject { put("query", buildJsonObject { put("query_string", buildJsonObject { put("query", text) }) }) }
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
            return if (json.keys.any { it in BodyKeys }) json else buildJsonObject { put("query", json) }
        }

        private val BodyKeys =
            setOf("query", "sort", "_source", "aggs", "aggregations", "post_filter", "knn", "collapse", "track_total_hits", "fields", "highlight", "min_score", "runtime_mappings")

        /** A single bucket aggregation (terms, histogram…) as rows: its key and count, and each sub-aggregation's value. */
        private fun buckets(aggregations: JsonObject?, elapsed: Long): ConsoleReply.Table? {
            val (name, aggregation) = aggregations?.entries?.singleOrNull() ?: return null
            val buckets = (aggregation as? JsonObject)?.get("buckets") as? JsonArray ?: return null
            if (buckets.isEmpty() || buckets.any { it !is JsonObject }) return null
            val rows = buckets.map { bucket ->
                JsonObject(bucket.jsonObject.mapValues { (_, value) -> (value as? JsonObject)?.get("value") ?: value })
            }
            return ConsoleReply.Table(JsonTables.rows(rows, leading = listOf("key_as_string", "key", "doc_count"), elapsedMillis = elapsed), note = tr("Buckets of {0}", name))
        }

        /** Endpoints a POST only reads from. */
        private val ReadEndpoints =
            setOf("_search", "_msearch", "_count", "_mget", "_explain", "_field_caps", "_analyze", "_termvectors", "_mtermvectors", "_sql", "_async_search", "_pit", "_rank_eval")

        private val ReadPaths = listOf("_validate/query", "_search/template", "_msearch/template", "_render/template", "_search/scroll", "_sql/translate", "_eql/search", "_sql/close")

        private val SqlPlugins = listOf("_plugins/_sql", "_plugins/_ppl", "_opendistro/_sql")

        /**
         * GET and HEAD never change anything; a POST reads only to the search, count, explain and SQL endpoints. The
         * OpenSearch SQL plugin can delete when its setting allows it, so a statement there mentioning DELETE is a write.
         */
        internal fun isReadRequest(statement: String): Boolean {
            val request = runCatching { HttpStatement.parse(statement) }.getOrNull() ?: return false
            if (request.method == "GET" || request.method == "HEAD") return true
            if (request.method != "POST") return false
            val endpoint = request.endpoint.trimEnd('/')
            if (SqlPlugins.any { endpoint.endsWith(it) || endpoint.endsWith("$it/explain") }) return !Regex("\\bdelete\\b", RegexOption.IGNORE_CASE).containsMatchIn(request.body.orEmpty())
            if (ReadPaths.any { endpoint.endsWith(it) }) return true
            return endpoint.substringAfterLast('/') in ReadEndpoints
        }

        /** The reason an error reply gives, as Elasticsearch and OpenSearch shape it. */
        internal fun errorReason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val error = json?.get("error")
            val reason =
                when (error) {
                    is JsonObject -> {
                        val cause = (error["root_cause"] as? JsonArray)?.firstOrNull() as? JsonObject
                        (cause ?: error)["reason"]?.jsonPrimitive?.contentOrNull ?: error["type"]?.jsonPrimitive?.contentOrNull
                    }
                    is JsonPrimitive -> error.contentOrNull
                    else -> json?.get("message")?.jsonPrimitive?.contentOrNull
                }
            return "${reason ?: response.body.take(200).ifEmpty { "HTTP ${response.status}" }} (${response.status})"
        }

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("The reply has no {0}", name))
    }
}

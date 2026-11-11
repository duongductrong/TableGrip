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
 * Meilisearch through its REST API. The password is the API key (the master key, or one allowed to do what you do).
 * Indexes are the containers. With nothing typed the browser pages through the documents themselves, all of them; a
 * search pages through hits, up to the index's `maxTotalHits` (1,000 by default). Writes are tasks: TableGrip waits for
 * each to finish and reports its error. Meilisearch has no document versions, so a replace overwrites whatever is there.
 */
class MeiliClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Meilisearch", config, host, port)
        password?.takeIf { it.isNotEmpty() }?.let { rest.authorization = "Bearer $it" }
        return Connection(config, rest).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        /** Each index's primary key, from the last listing. */
        private val primaryKeys = ConcurrentHashMap<String, String>()

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement = checked(rest.send(method, path, body?.toString())).json()

        private fun checked(response: RestResponse): RestResponse {
            if (response.ok) return response
            throw IOException("Meilisearch: ${errorReason(response)}")
        }

        internal fun version(): String {
            val response = rest.send("GET", "/version")
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Meilisearch", errorReason(response)))
            val version = runCatching { checked(response).json().jsonObject["pkgVersion"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: throw IOException(tr("{0} doesn't look like Meilisearch: {1}", rest.base, response.body.take(120)))
            return "Meilisearch $version"
        }

        override suspend fun server(): String = io { version() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val stats = runCatching { call("GET", "/stats").jsonObject["indexes"]?.jsonObject }.getOrNull()
                call("GET", "/indexes?limit=1000").jsonObject["results"]?.jsonArray.orEmpty().map { it.jsonObject }.map { index ->
                    val uid = index.string("uid")
                    val primaryKey = index["primaryKey"]?.jsonPrimitive?.contentOrNull
                    if (primaryKey != null) primaryKeys[uid] = primaryKey
                    val count = stats?.get(uid)?.jsonObject?.get("numberOfDocuments")?.jsonPrimitive?.longOrNull
                    RecordContainer(listOf(uid), "index", count, primaryKey?.let { tr("key {0}", it) })
                }.sortedBy { it.name }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val uid = segment(container.single())
                val index = call("GET", "/indexes/$uid").jsonObject
                val settings = call("GET", "/indexes/$uid/settings")
                JsonTables.pretty(buildJsonObject {
                    put("uid", index["uid"] ?: JsonNull)
                    put("primaryKey", index["primaryKey"] ?: JsonNull)
                    put("settings", settings)
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("Search"),
                tr("Words to search for, or search parameters, e.g. {\"q\": \"shoes\", \"filter\": \"price < 50\", \"sort\": [\"price:asc\"]}"),
                tr("Plain text searches the index; a JSON object holds search parameters (q, filter, sort…). Nothing lists every document."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val uid = container.single()
                val offset = cursor?.toIntOrNull() ?: 0
                val primaryKey = primaryKey(uid)
                val text = query.trim()
                if (text.isEmpty()) {
                    val reply = call("GET", "/indexes/${segment(uid)}/documents?offset=$offset&limit=$limit").jsonObject
                    val documents = reply["results"]?.jsonArray.orEmpty().map { it.jsonObject }
                    val total = reply["total"]?.jsonPrimitive?.longOrNull
                    return@io RecordPage(documents.map { record(it, primaryKey) }, next(offset, documents.size, limit, total), total)
                }
                val parameters =
                    if (text.startsWith("{")) runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                    else buildJsonObject { put("q", text) }
                val body = JsonObject(parameters + mapOf("offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(limit), "showRankingScore" to JsonPrimitive(true)))
                val reply = call("POST", "/indexes/${segment(uid)}/search", body).jsonObject
                val hits = reply["hits"]?.jsonArray.orEmpty().map { it.jsonObject }
                val total = (reply["estimatedTotalHits"] ?: reply["totalHits"])?.jsonPrimitive?.longOrNull
                RecordPage(hits.map { record(it, primaryKey) }, next(offset, hits.size, limit, total), total, totalIsLowerBound = reply["estimatedTotalHits"] != null)
            }

        private fun next(offset: Int, size: Int, limit: Int, total: Long?): String? =
            (offset + size).takeIf { size == limit && (total == null || it < total) }?.toString()

        private fun record(hit: JsonObject, primaryKey: String?): Record {
            val document = JsonObject(hit.filterKeys { it !in HitExtras })
            val meta = hit["_rankingScore"]?.let { buildJsonObject { put("_rankingScore", it) } } ?: JsonObject(emptyMap())
            val key = primaryKey?.let { name -> document[name]?.let { buildJsonObject { put(name, it) } } }
            return Record(key, document, meta)
        }

        /** The index's primary key, asked for when the listing didn't have it (an index created since, or still empty). */
        private fun primaryKey(uid: String): String? =
            primaryKeys[uid] ?: call("GET", "/indexes/${segment(uid)}").jsonObject["primaryKey"]?.jsonPrimitive?.contentOrNull?.also { primaryKeys[uid] = it }

        override suspend fun template(container: List<String>): JsonObject =
            io {
                val uid = container.single()
                val fields = runCatching { call("GET", "/indexes/${segment(uid)}/stats").jsonObject["fieldDistribution"]?.jsonObject?.keys }.getOrNull().orEmpty()
                val primaryKey = primaryKey(uid)
                JsonObject((listOfNotNull(primaryKey) + fields.filter { it != primaryKey }).associateWith { JsonNull })
            }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val uid = container.single()
                val primaryKey = primaryKey(uid)
                if (key != null) {
                    val (name, value) = key.entries.single()
                    if (document[name] != value) throw IOException(tr("A document's {0} can't change; add a new document instead.", name))
                } else if (primaryKey != null && document[primaryKey].let { it == null || it is JsonNull }) {
                    throw IOException(tr("A new document needs its {0}.", primaryKey))
                }
                wait(call("POST", "/indexes/${segment(uid)}/documents", JsonArray(listOf(document))))
                val name = primaryKey(uid) ?: throw IOException(tr("Meilisearch couldn't tell this index's primary key."))
                buildJsonObject { put(name, document[name] ?: JsonNull) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                if (keys.isEmpty()) return@io 0L
                val task = wait(call("POST", "/indexes/${segment(container.single())}/documents/delete-batch", JsonArray(keys.map { it.values.single() })))
                task["details"]?.jsonObject?.get("deletedDocuments")?.jsonPrimitive?.longOrNull ?: keys.size.toLong()
            }

        /** Waits for the task a write enqueued, up to a minute, failing with its error. */
        private fun wait(enqueued: JsonElement): JsonObject {
            val uid = enqueued.jsonObject["taskUid"]?.jsonPrimitive?.longOrNull ?: throw IOException(tr("Meilisearch didn't say which task the write is."))
            val deadline = System.currentTimeMillis() + 60_000
            var delay = 20L
            while (true) {
                val task = call("GET", "/tasks/$uid").jsonObject
                when (task["status"]?.jsonPrimitive?.contentOrNull) {
                    "succeeded" -> return task
                    "failed", "canceled" -> throw IOException("Meilisearch: " + (task["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: task.toString()))
                }
                if (System.currentTimeMillis() > deadline) throw IOException(tr("Meilisearch is still working on task {0}; it will finish on its own.", uid))
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(500)
            }
        }

        override val consoleLanguage =
            RecordLanguage(
                tr("REST"),
                "POST /indexes/movies/search\n{\n  \"q\": \"space\",\n  \"limit\": 20\n}",
                tr("Requests to the Meilisearch API: a method and a path, then a JSON body. Hits and lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "POST /indexes/$it/search\n{\n  \"q\": \"\",\n  \"limit\": 20\n}" } ?: "GET /indexes"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val request = HttpStatement.parse(statement)
                val started = System.currentTimeMillis()
                val response = rest.send(request.method, request.path, request.body)
                val elapsed = System.currentTimeMillis() - started
                if (!response.ok) throw IOException("${response.status}: " + runCatching { JsonTables.pretty(response.json()) }.getOrDefault(response.body))
                val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull() ?: return@io ConsoleReply.Text(response.body.ifEmpty { "${response.status}" }, elapsed)
                val rows = ((json as? JsonObject)?.let { it["hits"] ?: it["results"] } ?: json) as? JsonArray
                if (rows != null && rows.isNotEmpty() && rows.all { it is JsonObject }) ConsoleReply.Table(JsonTables.rows(rows.map { it.jsonObject }, elapsedMillis = elapsed))
                else ConsoleReply.Text(JsonTables.pretty(json), elapsed)
            }

        override fun isRead(statement: String): Boolean = isReadRequest(statement)

        override fun close() = Unit
    }

    companion object {
        /** What a search adds to each hit; `_geo` and `_vectors` are the document's own. */
        private val HitExtras = setOf("_rankingScore", "_rankingScoreDetails", "_formatted", "_matchesPosition")

        private val ReadSuffixes = listOf("/search", "/multi-search", "/documents/fetch", "/facet-search", "/similar")

        /** GET reads; a POST reads only to search and fetch endpoints. */
        internal fun isReadRequest(statement: String): Boolean {
            val request = runCatching { HttpStatement.parse(statement) }.getOrNull() ?: return false
            if (request.method == "GET" || request.method == "HEAD") return true
            return request.method == "POST" && ReadSuffixes.any { request.endpoint.trimEnd('/').endsWith(it) }
        }

        internal fun errorReason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val message = json?.get("message")?.jsonPrimitive?.contentOrNull
            return "${message ?: response.body.take(200).ifEmpty { "HTTP ${response.status}" }} (${response.status})"
        }

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("The reply has no {0}", name))
    }
}

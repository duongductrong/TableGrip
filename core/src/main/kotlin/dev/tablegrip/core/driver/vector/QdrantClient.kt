package dev.tablegrip.core.driver.vector

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.HttpConsole
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.driver.http.RestResponse
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
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
 * Qdrant through its REST API (port 6333); the password is the API key, when the server has one. Collections hold
 * points: a record is a point's payload, its id as meta. The query field takes a Qdrant filter. Editing a point replaces
 * its payload once it's checked unchanged since it was read; a new point needs an `id` and a `vector` beside its payload.
 */
class QdrantClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Qdrant", config, host, port)
        val headers = password?.takeIf { it.isNotEmpty() }?.let { mapOf("api-key" to it) } ?: emptyMap()
        return Connection(config, rest, headers).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient, private val headers: Map<String, String>) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
            val response = rest.send(method, path, body?.toString(), headers = headers)
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Qdrant", reason(response)))
            if (!response.ok) throw IOException("Qdrant: ${reason(response)}")
            return response.json().jsonObject["result"] ?: JsonNull
        }

        internal fun version(): String {
            val response = rest.send("GET", "/", headers = headers)
            val version = runCatching { response.json().jsonObject["version"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: throw IOException(tr("{0} doesn't look like Qdrant: {1}", rest.base, response.body.take(120)))
            call("GET", "/collections")
            return "Qdrant $version"
        }

        override suspend fun server(): String = io { version() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                call("GET", "/collections").jsonObject["collections"]?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }.sorted().map { name ->
                    val count = runCatching { call("GET", "/collections/${segment(name)}").jsonObject["points_count"]?.jsonPrimitive?.longOrNull }.getOrNull()
                    RecordContainer(listOf(name), "collection", count)
                }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(call("GET", "/collections/${segment(container.single())}")) }

        override val queryLanguage =
            RecordLanguage(
                tr("Filter"),
                tr("A Qdrant filter, e.g. {\"must\": [{\"key\": \"city\", \"match\": {\"value\": \"Hanoi\"}}]}"),
                tr("A Qdrant filter as JSON, or a whole scroll request with filter and order_by."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val text = query.trim()
                val request =
                    if (text.isEmpty()) JsonObject(emptyMap())
                    else runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                        .let { if (it.keys.any { k -> k in ScrollKeys }) it else buildJsonObject { put("filter", it) } }
                val body = JsonObject(request + mapOf("limit" to JsonPrimitive(limit), "with_payload" to JsonPrimitive(true), "with_vector" to JsonPrimitive(false)) +
                    (cursor?.let { mapOf("offset" to Json.parseToJsonElement(it)) } ?: emptyMap()))
                val result = call("POST", "/collections/${segment(container.single())}/points/scroll", body).jsonObject
                val points = result["points"]?.jsonArray.orEmpty().map { it.jsonObject }
                val next = result["next_page_offset"]?.takeIf { it !is JsonNull }?.toString()
                RecordPage(points.map(::record), next)
            }

        private fun record(point: JsonObject): Record {
            val payload = point["payload"] as? JsonObject ?: JsonObject(emptyMap())
            return Record(buildJsonObject { put("id", point["id"]!!); put(VersionField, JsonTables.version(payload)) }, payload, buildJsonObject { put("id", point["id"]!!) })
        }

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("id", JsonNull); put("vector", JsonArray(emptyList())) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val collection = segment(container.single())
                if (key == null) {
                    val id = document["id"]?.takeIf { it !is JsonNull } ?: throw IOException(tr("A new point needs its {0}.", "id"))
                    val vector = document["vector"]?.takeIf { it !is JsonNull } ?: throw IOException(tr("A new point needs its {0}.", "vector"))
                    if (current(collection, id) != null) throw IOException(tr("A point with this id already exists."))
                    val payload = JsonObject(document - "id" - "vector")
                    call("PUT", "/collections/$collection/points?wait=true", buildJsonObject {
                        put("points", JsonArray(listOf(buildJsonObject { put("id", id); put("vector", vector); put("payload", payload) })))
                    })
                    return@io buildJsonObject { put("id", id); put(VersionField, JsonTables.version(payload)) }
                }
                val id = key["id"]!!
                val current = current(collection, id) ?: throw IOException(tr("The point was deleted by someone else since it was read; reload and try again."))
                if (key[VersionField]?.jsonPrimitive?.content != JsonTables.version(current)) throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", id.toString()))
                call("PUT", "/collections/$collection/points/payload?wait=true", buildJsonObject { put("payload", document); put("points", JsonArray(listOf(id))) })
                buildJsonObject { put("id", id); put(VersionField, JsonTables.version(document)) }
            }

        /** The point's payload as it is now, or null once deleted. */
        private fun current(collection: String, id: JsonElement): JsonObject? {
            val response = rest.send("GET", "/collections/$collection/points/${segment((id as? JsonPrimitive)?.content ?: id.toString())}", headers = headers)
            if (response.status == 404) return null
            if (!response.ok) throw IOException("Qdrant: ${reason(response)}")
            return response.json().jsonObject["result"]?.jsonObject?.get("payload") as? JsonObject ?: JsonObject(emptyMap())
        }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val collection = segment(container.single())
                val doomed = keys.filter { key ->
                    val current = current(collection, key["id"]!!) ?: return@filter false
                    if (key[VersionField] != null && key[VersionField]!!.jsonPrimitive.content != JsonTables.version(current)) {
                        throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", key["id"].toString()))
                    }
                    true
                }
                if (doomed.isNotEmpty()) call("POST", "/collections/$collection/points/delete?wait=true", buildJsonObject { put("points", JsonArray(doomed.map { it["id"]!! })) })
                doomed.size.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "POST /collections/docs/points/scroll\n{\n  \"limit\": 10,\n  \"with_payload\": true\n}",
                tr("Requests to the Qdrant API: a method and a path, then a JSON body. Points and lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "POST /collections/$it/points/scroll\n{\n  \"limit\": 10,\n  \"with_payload\": true\n}" } ?: "GET /collections"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val request = dev.tablegrip.core.driver.http.HttpStatement.parse(statement)
                val started = System.currentTimeMillis()
                val response = rest.send(request.method, request.path, request.body, headers = headers)
                val elapsed = System.currentTimeMillis() - started
                if (!response.ok) throw IOException("${response.status}: " + runCatching { JsonTables.pretty(response.json()) }.getOrDefault(response.body))
                val json = runCatching { response.json() }.getOrNull() ?: return@io ConsoleReply.Text(response.body, elapsed)
                val rows = HttpConsole.firstList((json as? JsonObject)?.get("result") ?: json)?.map { point ->
                    // Points show their payload's fields beside their id and score.
                    val payload = point["payload"] as? JsonObject
                    if (payload != null) JsonObject(point.filterKeys { it != "payload" && it != "vector" } + payload) else point
                }
                if (rows.isNullOrEmpty()) ConsoleReply.Text(JsonTables.pretty(json), elapsed) else ConsoleReply.Table(JsonTables.rows(rows, leading = listOf("id", "score"), elapsedMillis = elapsed))
            }

        override fun isRead(statement: String): Boolean =
            HttpConsole.isRead(statement, listOf("/points/scroll", "/points/search", "/points/search/batch", "/points/query", "/points/query/batch", "/points/count", "/points", "/points/recommend", "/points/discover", "/facet"))

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"
        private val ScrollKeys = setOf("filter", "order_by", "with_vector", "offset")

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        internal fun reason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val message = (json?.get("status") as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
            return "${message ?: response.body.take(200)} (${response.status})"
        }
    }
}

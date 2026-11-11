package dev.tablegrip.core.driver.document

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Apache CouchDB through its HTTP API, which TableGrip speaks itself. A user and password are HTTP basic auth (listing
 * databases needs an admin on CouchDB 3). Databases are the containers. The browser pages through `_all_docs` by id, or
 * through a Mango query (`_find`, with its bookmark) when the query is a JSON selector; plain text is an `_id` prefix.
 * Every write carries the `_rev` it was read at, so CouchDB itself refuses one made from a stale page.
 */
class CouchClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("CouchDB", config, host, port)
        if (config.user.isNotBlank()) rest.authorization = RestClient.basic(config.user.trim(), password)
        return Connection(config, rest).also { it.welcome() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val showSystem = config.properties["showSystem"]?.toBoolean() == true

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement = checked(rest.send(method, path, body?.toString())).json()

        private fun checked(response: RestResponse): RestResponse {
            if (response.ok) return response
            throw IOException("CouchDB: ${errorReason(response)}")
        }

        internal fun welcome(): String {
            val response = rest.send("GET", "/")
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "CouchDB", errorReason(response)))
            val json = runCatching { checked(response).json().jsonObject }.getOrNull()
            val version = json?.get("version")?.jsonPrimitive?.contentOrNull?.takeIf { json["couchdb"] != null }
                ?: throw IOException(tr("{0} doesn't look like CouchDB: {1}", rest.base, response.body.take(120)))
            // An anonymous welcome doesn't prove the credentials work; the session does.
            if (rest.authorization != null) {
                val session = rest.send("GET", "/_session")
                if (session.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "CouchDB", errorReason(session)))
            }
            return "CouchDB $version"
        }

        override suspend fun server(): String = io { welcome() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val names = call("GET", "/_all_dbs").jsonArray.map { it.jsonPrimitive.content }.filter { showSystem || !it.startsWith("_") }
                val counts = runCatching {
                    names.chunked(100).flatMap { chunk ->
                        call("POST", "/_dbs_info", buildJsonObject { put("keys", JsonArray(chunk.map(::JsonPrimitive))) }).jsonArray.mapNotNull { entry ->
                            val info = entry.jsonObject["info"] as? JsonObject ?: return@mapNotNull null
                            entry.jsonObject.string("key") to info["doc_count"]?.jsonPrimitive?.longOrNull
                        }
                    }.toMap()
                }.getOrDefault(emptyMap())
                names.sorted().map { RecordContainer(listOf(it), "database", counts[it]) }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val db = "/" + segment(container.single())
                JsonTables.pretty(buildJsonObject {
                    put("info", call("GET", db))
                    put("indexes", runCatching { call("GET", "$db/_index").jsonObject["indexes"] ?: JsonArray(emptyList()) }.getOrDefault(JsonArray(emptyList())))
                    put("design", JsonArray(call("GET", "$db/_design_docs?include_docs=true").jsonObject["rows"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["doc"] }))
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("Selector"),
                tr("A Mango selector, e.g. {\"status\": \"open\", \"year\": 2024}, or an _id prefix"),
                tr("JSON is a Mango query: a selector, or a whole body with selector, sort and fields. Plain text lists the documents whose _id starts with it."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val db = "/" + segment(container.single())
                val text = query.trim()
                if (text.startsWith("{")) {
                    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                    val body = JsonObject((if ("selector" in json) json else mapOf("selector" to json)) + mapOf("limit" to JsonPrimitive(limit)) + (cursor?.let { mapOf("bookmark" to JsonPrimitive(it)) } ?: emptyMap()))
                    val reply = call("POST", "$db/_find", body).jsonObject
                    val docs = reply["docs"]?.jsonArray.orEmpty().map { it.jsonObject }
                    val bookmark = reply["bookmark"]?.jsonPrimitive?.contentOrNull
                    return@io RecordPage(docs.map(::record), bookmark?.takeIf { docs.size == limit && it != "nil" })
                }
                // _all_docs by id: one more than a page tells whether another follows, and where it starts.
                val start = cursor ?: text.takeIf { it.isNotEmpty() }
                val parameters = buildList {
                    add("include_docs=true")
                    add("limit=${limit + 1}")
                    start?.let { add("startkey=" + encode(JsonPrimitive(it).toString())) }
                    if (text.isNotEmpty()) add("endkey=" + encode(JsonPrimitive(text + "￰").toString()))
                }
                val reply = call("GET", "$db/_all_docs?" + parameters.joinToString("&")).jsonObject
                val rows = reply["rows"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["doc"] as? JsonObject }
                val total = reply["total_rows"]?.jsonPrimitive?.longOrNull?.takeIf { text.isEmpty() }
                RecordPage(rows.take(limit).map(::record), rows.getOrNull(limit)?.string("_id"), total)
            }

        private fun record(doc: JsonObject) =
            Record(
                buildJsonObject { put("_id", doc.string("_id")); doc["_rev"]?.let { put("_rev", it) } },
                JsonObject(doc - "_rev"),
                buildJsonObject { doc["_rev"]?.let { put("_rev", it) } },
            )

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("_id", "") }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val db = "/" + segment(container.single())
                val id = (document["_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                val response =
                    if (key == null) {
                        // An empty _id lets CouchDB pick one.
                        rest.send("POST", db, JsonObject(if (id == null) document - "_id" - "_rev" else document - "_rev").toString())
                    } else {
                        val existing = key.string("_id")
                        if (id != null && id != existing) throw IOException(tr("A document's _id can't change; add a new document instead."))
                        val body = JsonObject(document - "_rev" + ("_id" to JsonPrimitive(existing)) + (key["_rev"]?.let { mapOf("_rev" to it) } ?: emptyMap()))
                        rest.send("PUT", "$db/${segment(existing)}", body.toString())
                    }
                if (response.status == 409) {
                    throw IOException(
                        if (key == null) tr("A document with this _id already exists.")
                        else tr("The document was changed or deleted by someone else since it was read; reload and try again."),
                    )
                }
                val reply = checked(response).json().jsonObject
                buildJsonObject { put("_id", reply.string("id")); put("_rev", reply.string("rev")) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val db = "/" + segment(container.single())
                keys.count { key ->
                    val path = "$db/${segment(key.string("_id"))}?rev=${encode(key.string("_rev"))}"
                    val response = rest.send("DELETE", path)
                    when {
                        response.status == 404 -> false
                        // A stale revision of a document deleted since is simply gone.
                        response.status == 409 && rest.send("HEAD", "$db/${segment(key.string("_id"))}").status == 404 -> false
                        response.status == 409 -> throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", key.string("_id")))
                        else -> checked(response).ok
                    }
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "POST /orders/_find\n{\n  \"selector\": { \"status\": \"open\" },\n  \"limit\": 20\n}",
                tr("Requests to the CouchDB API: a method and a path, then a JSON body. Documents and view rows show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "POST /$it/_find\n{\n  \"selector\": {},\n  \"limit\": 20\n}" } ?: "GET /_all_dbs"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val request = HttpStatement.parse(statement)
                val started = System.currentTimeMillis()
                val response = rest.send(request.method, request.path, request.body)
                val elapsed = System.currentTimeMillis() - started
                if (!response.ok) throw IOException("${response.status}: " + runCatching { JsonTables.pretty(response.json()) }.getOrDefault(response.body))
                val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull() ?: return@io ConsoleReply.Text(response.body.ifEmpty { "${response.status}" }, elapsed)
                tableOf(json, elapsed) ?: ConsoleReply.Text(JsonTables.pretty(json), elapsed)
            }

        /** `_find`'s docs, a view's or `_all_docs`' rows (their documents, when included) and lists of objects as rows. */
        private fun tableOf(json: JsonElement, elapsed: Long): ConsoleReply.Table? {
            val objects =
                when {
                    json is JsonArray -> json
                    json is JsonObject && json["docs"] is JsonArray -> json["docs"]!!.jsonArray
                    json is JsonObject && json["rows"] is JsonArray ->
                        JsonArray(json["rows"]!!.jsonArray.map { row -> (row as? JsonObject)?.get("doc") as? JsonObject ?: row })
                    else -> return null
                }
            if (objects.isEmpty() || objects.any { it !is JsonObject }) return null
            return ConsoleReply.Table(JsonTables.rows(objects.map { it.jsonObject }, leading = listOf("_id", "id", "key", "value"), elapsedMillis = elapsed))
        }

        override fun isRead(statement: String): Boolean = isReadRequest(statement)

        override fun close() = Unit
    }

    companion object {
        private val ReadEndpoints = setOf("_find", "_explain", "_all_docs", "_bulk_get", "_dbs_info", "_changes", "_design_docs", "_local_docs", "_search")

        /** GET and HEAD read; a POST reads to queries: `_find`, `_all_docs`, a view, `_changes`… */
        internal fun isReadRequest(statement: String): Boolean {
            val request = runCatching { HttpStatement.parse(statement) }.getOrNull() ?: return false
            if (request.method == "GET" || request.method == "HEAD") return true
            if (request.method != "POST") return false
            val segments = request.endpoint.split('/').filter { it.isNotEmpty() }
            if (segments.lastOrNull() in ReadEndpoints) return true
            // A view query: /db/_design/ddoc/_view/name, its queries endpoint included.
            return segments.size >= 5 && segments[1] == "_design" && segments[3] == "_view"
        }

        internal fun errorReason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val reason = listOfNotNull(json?.get("error")?.jsonPrimitive?.contentOrNull, json?.get("reason")?.jsonPrimitive?.contentOrNull).joinToString(": ")
            return "${reason.ifEmpty { response.body.take(200).ifEmpty { "HTTP ${response.status}" } }} (${response.status})"
        }

        private fun segment(value: String): String =
            // Design documents keep their slash: /db/_design/name.
            if (value.startsWith("_design/")) "_design/" + segment(value.removePrefix("_design/"))
            else URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("The reply has no {0}", name))
    }
}

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
 * Chroma through its v2 HTTP API (port 8000). The database field picks `tenant/database` (Chroma's defaults when
 * blank); the password is a token, sent as `x-chroma-token`. Collections hold records with an id, a document and
 * metadata. The query field takes a `where` filter on metadata. Edits update the document and metadata once they're
 * checked unchanged since read; a new record needs its id and its `embedding`, since embeddings are made client-side.
 */
class ChromaClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Chroma", config, host, port)
        val headers = password?.takeIf { it.isNotEmpty() }?.let { mapOf("x-chroma-token" to it) } ?: emptyMap()
        return Connection(config, rest, headers).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient, private val headers: Map<String, String>) : RecordConnection {
        private val tenant = config.database.trim().substringBefore('/').ifEmpty { "default_tenant" }
        private val database = config.database.trim().substringAfter('/', "").ifEmpty { "default_database" }
        private val base = "/api/v2/tenants/${segment(tenant)}/databases/${segment(database)}/collections"
        private val ids = ConcurrentHashMap<String, String>()

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
            val response = rest.send(method, path, body?.toString(), headers = headers)
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Chroma", reason(response)))
            if (!response.ok) throw IOException("Chroma: ${reason(response)}")
            return runCatching { response.json() }.getOrDefault(JsonNull)
        }

        internal fun version(): String {
            val version = runCatching { call("GET", "/api/v2/version").jsonPrimitive.contentOrNull }.getOrElse { if (it.message?.contains("credentials") == true) throw it else null }
                ?: throw IOException(tr("{0} doesn't look like Chroma: {1}", rest.base, "/api/v2/version"))
            call("GET", base)
            return "Chroma $version ($tenant/$database)"
        }

        override suspend fun server(): String = io { version() }

        private fun id(name: String): String = ids[name] ?: call("GET", "$base/${segment(name)}").jsonObject["id"]!!.jsonPrimitive.content.also { ids[name] = it }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                call("GET", base).jsonArray.map { it.jsonObject }.map { c ->
                    val name = c["name"]!!.jsonPrimitive.content
                    ids[name] = c["id"]!!.jsonPrimitive.content
                    RecordContainer(listOf(name), "collection", runCatching { call("GET", "$base/${ids[name]}/count").jsonPrimitive.longOrNull }.getOrNull())
                }.sortedBy { it.name }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(call("GET", "$base/${segment(container.single())}")) }

        override val queryLanguage =
            RecordLanguage(
                tr("where"),
                tr("A metadata filter, e.g. {\"city\": \"Hanoi\"}"),
                tr("A Chroma where filter on metadata, or a whole get request with where and where_document."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val text = query.trim()
                val request =
                    if (text.isEmpty()) JsonObject(emptyMap())
                    else runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                        .let { if ("where" in it || "where_document" in it) it else buildJsonObject { put("where", it) } }
                val offset = cursor?.toIntOrNull() ?: 0
                val reply = call("POST", "$base/${id(container.single())}/get", JsonObject(request + mapOf(
                    "limit" to JsonPrimitive(limit + 1), "offset" to JsonPrimitive(offset),
                    "include" to JsonArray(listOf(JsonPrimitive("documents"), JsonPrimitive("metadatas"))),
                ))).jsonObject
                val records = rows(reply)
                RecordPage(records.take(limit), (offset + limit).takeIf { records.size > limit }?.toString())
            }

        private fun rows(reply: JsonObject): List<Record> {
            val idList = reply["ids"]?.jsonArray.orEmpty()
            val documents = reply["documents"] as? JsonArray
            val metadatas = reply["metadatas"] as? JsonArray
            return idList.indices.map { i ->
                val document = buildJsonObject {
                    put("document", documents?.getOrNull(i) ?: JsonNull)
                    put("metadata", metadatas?.getOrNull(i) ?: JsonNull)
                }
                Record(buildJsonObject { put("id", idList[i]); put(VersionField, JsonTables.version(document)) }, document, buildJsonObject { put("id", idList[i]) })
            }
        }

        private fun current(collection: String, id: JsonElement): JsonObject? =
            rows(call("POST", "$base/$collection/get", buildJsonObject {
                put("ids", JsonArray(listOf(id)))
                put("include", JsonArray(listOf(JsonPrimitive("documents"), JsonPrimitive("metadatas"))))
            }).jsonObject).firstOrNull()?.document

        override suspend fun template(container: List<String>): JsonObject =
            buildJsonObject { put("id", ""); put("document", ""); put("metadata", JsonObject(emptyMap())); put("embedding", JsonArray(emptyList())) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val collection = id(container.single())
                val metadata = document["metadata"]?.takeIf { it !is JsonNull && (it as? JsonObject)?.isNotEmpty() != false }
                if (key == null) {
                    val id = document["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: throw IOException(tr("A new record needs its {0}.", "id"))
                    val embedding = document["embedding"]?.takeIf { it is JsonArray && it.isNotEmpty() } ?: throw IOException(tr("A new record needs its {0}.", "embedding"))
                    if (current(collection, JsonPrimitive(id)) != null) throw IOException(tr("A record with this id already exists."))
                    call("POST", "$base/$collection/add", buildJsonObject {
                        put("ids", JsonArray(listOf(JsonPrimitive(id))))
                        put("embeddings", JsonArray(listOf(embedding)))
                        document["document"]?.takeIf { it !is JsonNull }?.let { put("documents", JsonArray(listOf(it))) }
                        metadata?.let { put("metadatas", JsonArray(listOf(it))) }
                    })
                    val stored = current(collection, JsonPrimitive(id)) ?: document
                    return@io buildJsonObject { put("id", id); put(VersionField, JsonTables.version(stored)) }
                }
                val id = key["id"]!!
                val now = current(collection, id) ?: throw IOException(tr("The record was deleted by someone else since it was read; reload and try again."))
                if (key[VersionField]?.jsonPrimitive?.content != JsonTables.version(now)) throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", id.jsonPrimitive.content))
                call("POST", "$base/$collection/update", buildJsonObject {
                    put("ids", JsonArray(listOf(id)))
                    put("documents", JsonArray(listOf(document["document"] ?: JsonNull)))
                    put("metadatas", JsonArray(listOf(metadata ?: JsonNull)))
                })
                buildJsonObject { put("id", id); put(VersionField, JsonTables.version(current(collection, id) ?: document)) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val collection = id(container.single())
                val doomed = keys.filter { key ->
                    val now = current(collection, key["id"]!!) ?: return@filter false
                    if (key[VersionField] != null && key[VersionField]!!.jsonPrimitive.content != JsonTables.version(now)) {
                        throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", key["id"]!!.jsonPrimitive.content))
                    }
                    true
                }
                if (doomed.isNotEmpty()) call("POST", "$base/$collection/delete", buildJsonObject { put("ids", JsonArray(doomed.map { it["id"]!! })) })
                doomed.size.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "GET /api/v2/tenants/default_tenant/databases/default_database/collections",
                tr("Requests to the Chroma API: a method and a path, then a JSON body. Lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String = "GET $base"

        override suspend fun execute(statement: String): ConsoleReply = io { HttpConsole.run(rest, statement, headers) }

        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, listOf("/get", "/query", "/count"))

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        internal fun reason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            return "${(json?.get("message") ?: json?.get("error"))?.jsonPrimitive?.contentOrNull ?: response.body.take(200)} (${response.status})"
        }
    }
}

package dev.tablegrip.core.driver.search

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Apache Solr through its HTTP API, standalone (cores) or SolrCloud (collections). A user and password are basic auth.
 * Documents are read with the JSON request API, sorted by the unique key unless the query sorts; writes carry the
 * `_version_` they were read at, so Solr refuses one made from a stale page, and commit at once.
 */
class SolrClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Solr", config, host, port)
        if (config.user.isNotBlank()) rest.authorization = RestClient.basic(config.user.trim(), password)
        return Connection(config, rest).also { it.system() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val keys = ConcurrentHashMap<String, String>()

        @Volatile private var cloud = false

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonObject {
            val response = rest.send(method, path, body?.toString())
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Solr", reason(response)))
            if (!response.ok) throw IOException("Solr: ${reason(response)}")
            return response.json().jsonObject
        }

        internal fun system(): String {
            val info = runCatching { call("GET", "/solr/admin/info/system?wt=json") }.getOrElse {
                if (it.message?.contains("credentials") == true) throw it
                throw IOException(tr("{0} doesn't look like Solr: {1}", rest.base, it.message))
            }
            cloud = info["mode"]?.jsonPrimitive?.contentOrNull == "solrcloud"
            val version = info["lucene"]?.jsonObject?.get("solr-spec-version")?.jsonPrimitive?.contentOrNull.orEmpty()
            return "Apache Solr $version" + if (cloud) " (SolrCloud)" else ""
        }

        override suspend fun server(): String = io { system() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                if (cloud) {
                    call("GET", "/solr/admin/collections?action=LIST&wt=json")["collections"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.sorted()
                        .map { RecordContainer(listOf(it), "collection") }
                } else {
                    call("GET", "/solr/admin/cores?action=STATUS&wt=json")["status"]?.jsonObject.orEmpty().map { (name, status) ->
                        RecordContainer(listOf(name), "core", (status as? JsonObject)?.get("index")?.jsonObject?.get("numDocs")?.jsonPrimitive?.longOrNull)
                    }.sortedBy { it.name }
                }
            }

        private fun uniqueKey(collection: String): String =
            keys[collection] ?: call("GET", "/solr/${segment(collection)}/schema/uniquekey?wt=json")["uniqueKey"]!!.jsonPrimitive.content.also { keys[collection] = it }

        override suspend fun definition(container: List<String>): String =
            io {
                val c = segment(container.single())
                JsonTables.pretty(buildJsonObject {
                    put("uniqueKey", uniqueKey(container.single()))
                    put("fields", call("GET", "/solr/$c/schema/fields?wt=json")["fields"] ?: JsonArray(emptyList()))
                    put("dynamicFields", call("GET", "/solr/$c/schema/dynamicfields?wt=json")["dynamicFields"] ?: JsonArray(emptyList()))
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("q"),
                tr("category:shoes AND price:[10 TO 50]   or a JSON request, e.g. {\"query\": \"*:*\", \"filter\": \"in_stock:true\"}"),
                tr("A Solr query (standard syntax), or a JSON request API body with query, filter, sort and fields."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val collection = container.single()
                val key = uniqueKey(collection)
                val offset = cursor?.toIntOrNull() ?: 0
                val text = query.trim()
                val request =
                    if (text.startsWith("{")) runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                    else buildJsonObject { put("query", text.ifEmpty { "*:*" }) }
                val body = JsonObject(request + mapOf("offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(limit)) +
                    (if ("sort" in request) emptyMap() else mapOf("sort" to JsonPrimitive("$key asc"))) +
                    (if ("fields" in request) emptyMap() else mapOf("fields" to JsonPrimitive("*,_version_"))))
                val response = call("POST", "/solr/${segment(collection)}/select", body)["response"]?.jsonObject
                val docs = response?.get("docs")?.jsonArray.orEmpty().map { it.jsonObject }
                val total = response?.get("numFound")?.jsonPrimitive?.longOrNull
                RecordPage(docs.map { record(it, key) }, (offset + docs.size).takeIf { docs.size == limit && (total == null || it < total) }?.toString(), total)
            }

        private fun record(doc: JsonObject, key: String): Record {
            val document = JsonObject(doc - "_version_")
            val recordKey = doc[key]?.let { id -> buildJsonObject { put(key, id); doc["_version_"]?.let { put("_version_", it) } } }
            return Record(recordKey, document)
        }

        override suspend fun template(container: List<String>): JsonObject = io { buildJsonObject { put(uniqueKey(container.single()), JsonNull) } }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val collection = container.single()
                val unique = uniqueKey(collection)
                val id = document[unique]?.takeIf { it !is JsonNull } ?: throw IOException(tr("A new document needs its {0}.", unique))
                if (key != null && key[unique] != id) throw IOException(tr("A document's {0} can't change; add a new document instead.", unique))
                // -1: the document must not exist yet; otherwise the version it was read at.
                val version = key?.get("_version_") ?: JsonPrimitive(-1)
                val response = rest.send("POST", "/solr/${segment(collection)}/update?commit=true&versions=true&wt=json", JsonArray(listOf(JsonObject(document - "_version_" + ("_version_" to version)))).toString())
                if (response.status == 409) {
                    throw IOException(if (key == null) tr("A document with this {0} already exists.", unique) else tr("The document was changed or deleted by someone else since it was read; reload and try again."))
                }
                if (!response.ok) throw IOException("Solr: ${reason(response)}")
                val written = response.json().jsonObject["adds"]?.jsonArray?.getOrNull(1)
                buildJsonObject { put(unique, id); written?.let { put("_version_", it) } }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val collection = container.single()
                val unique = uniqueKey(collection)
                keys.count { key ->
                    val id = key[unique]!!
                    val exists = call("POST", "/solr/${segment(collection)}/select", buildJsonObject {
                        put("query", "{!term f=$unique}${(id as? JsonPrimitive)?.content}")
                        put("limit", 0)
                    })["response"]?.jsonObject?.get("numFound")?.jsonPrimitive?.longOrNull ?: 0
                    if (exists == 0L) return@count false
                    val command = buildJsonObject { put("delete", buildJsonObject { put("id", id); key["_version_"]?.let { put("_version_", it) } }) }
                    val response = rest.send("POST", "/solr/${segment(collection)}/update?commit=true&wt=json", command.toString())
                    if (response.status == 409) throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", (id as? JsonPrimitive)?.content ?: id.toString()))
                    if (!response.ok) throw IOException("Solr: ${reason(response)}")
                    true
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "POST /solr/products/select\n{\n  \"query\": \"*:*\",\n  \"limit\": 20\n}",
                tr("Requests to the Solr API: a method and a path, then a JSON body. Documents and lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "POST /solr/$it/select\n{\n  \"query\": \"*:*\",\n  \"limit\": 20\n}" } ?: "GET /solr/admin/cores?action=STATUS"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                HttpConsole.run(rest, statement) { json ->
                    ((json as? JsonObject)?.get("response") as? JsonObject)?.get("docs")?.jsonArray?.map { it.jsonObject } ?: HttpConsole.firstList(json)
                }
            }

        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, listOf("/select", "/query", "/export", "/terms", "/suggest", "/spell", "/get", "/sql"))

        override fun close() = Unit
    }

    companion object {
        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        internal fun reason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val message = (json?.get("error") as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull
            return "${message ?: response.body.take(200)} (${response.status})"
        }
    }
}

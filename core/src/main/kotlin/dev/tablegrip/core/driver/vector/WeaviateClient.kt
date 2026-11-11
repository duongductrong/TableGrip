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
 * Weaviate through its REST and GraphQL APIs (port 8080); the password is an API key, when the server has one.
 * Collections (classes) hold objects: a record is an object's properties, its id as meta. Without a query objects page
 * with the REST cursor; a `where` filter (JSON, as Weaviate documents it) goes through GraphQL. Editing merges the
 * properties (a PATCH, which keeps the vector) once they're checked unchanged since read; the console takes GraphQL.
 */
class WeaviateClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Weaviate", config, host, port)
        password?.takeIf { it.isNotEmpty() }?.let { rest.authorization = "Bearer $it" }
        return Connection(config, rest).also { it.meta() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): RestResponse {
            val response = rest.send(method, path, body?.toString())
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Weaviate", reason(response)))
            return response
        }

        private fun json(method: String, path: String, body: JsonElement? = null): JsonElement {
            val response = call(method, path, body)
            if (!response.ok) throw IOException("Weaviate: ${reason(response)}")
            return response.json()
        }

        internal fun meta(): String {
            val version = runCatching { json("GET", "/v1/meta").jsonObject["version"]?.jsonPrimitive?.contentOrNull }.getOrElse { if (it.message?.contains("credentials") == true) throw it else null }
                ?: throw IOException(tr("{0} doesn't look like Weaviate: {1}", rest.base, "/v1/meta"))
            json("GET", "/v1/schema")
            return "Weaviate $version"
        }

        override suspend fun server(): String = io { meta() }

        private fun graphql(query: String): JsonObject {
            val reply = json("POST", "/v1/graphql", buildJsonObject { put("query", query) }).jsonObject
            reply["errors"]?.jsonArray?.firstOrNull()?.let { throw IOException("Weaviate: " + (it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString())) }
            return reply["data"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val classes = json("GET", "/v1/schema").jsonObject["classes"]?.jsonArray.orEmpty().map { it.jsonObject["class"]!!.jsonPrimitive.content }.sorted()
                val counts = if (classes.isEmpty()) emptyMap() else runCatching {
                    graphql("{ Aggregate { " + classes.joinToString(" ") { "$it { meta { count } }" } + " } }")["Aggregate"]!!.jsonObject.mapValues { (_, v) ->
                        v.jsonArray.firstOrNull()?.jsonObject?.get("meta")?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
                    }
                }.getOrDefault(emptyMap())
                classes.map { RecordContainer(listOf(it), "collection", counts[it]) }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(json("GET", "/v1/schema/${segment(container.single())}")) }

        override val queryLanguage =
            RecordLanguage(
                tr("where"),
                tr("A where filter, e.g. {\"path\": [\"city\"], \"operator\": \"Equal\", \"valueText\": \"Hanoi\"}"),
                tr("A Weaviate where filter as JSON (path, operator and a value), which runs through GraphQL; nothing pages through every object."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val collection = container.single()
                val text = query.trim()
                if (text.isEmpty()) {
                    val reply = json("GET", "/v1/objects?class=${segment(collection)}&limit=$limit" + (cursor?.let { "&after=${segment(it)}" } ?: "")).jsonObject
                    val objects = reply["objects"]?.jsonArray.orEmpty().map { it.jsonObject }
                    return@io RecordPage(
                        objects.map { record(it["id"]!!, it["properties"] as? JsonObject ?: JsonObject(emptyMap())) },
                        objects.lastOrNull()?.get("id")?.jsonPrimitive?.content?.takeIf { objects.size == limit },
                    )
                }
                val where = runCatching { Json.parseToJsonElement(text) }.getOrElse { throw IOException(tr("The query isn't valid JSON: {0}", it.message)) }
                val properties = json("GET", "/v1/schema/${segment(collection)}").jsonObject["properties"]?.jsonArray.orEmpty().map { it.jsonObject }
                    .filter { p -> p["dataType"]?.jsonArray?.all { it.jsonPrimitive.content.first().isLowerCase() } == true }
                    .map { it["name"]!!.jsonPrimitive.content }
                val offset = cursor?.toIntOrNull() ?: 0
                val rows = graphql("{ Get { $collection(where: ${graphQl(where)}, limit: ${limit + 1}, offset: $offset) { ${properties.joinToString(" ")} _additional { id } } } }")["Get"]!!
                    .jsonObject[collection]?.jsonArray.orEmpty().map { it.jsonObject }
                RecordPage(rows.take(limit).map { row -> record(row["_additional"]!!.jsonObject["id"]!!, JsonObject(row - "_additional")) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        private fun record(id: JsonElement, properties: JsonObject) =
            Record(buildJsonObject { put("id", id); put(VersionField, JsonTables.version(properties)) }, properties, buildJsonObject { put("id", id) })

        override suspend fun template(container: List<String>): JsonObject =
            io {
                JsonObject(json("GET", "/v1/schema/${segment(container.single())}").jsonObject["properties"]?.jsonArray.orEmpty().associate { it.jsonObject["name"]!!.jsonPrimitive.content to JsonNull })
            }

        private fun current(collection: String, id: String): JsonObject? {
            val response = call("GET", "/v1/objects/${segment(collection)}/${segment(id)}")
            if (response.status == 404) return null
            if (!response.ok) throw IOException("Weaviate: ${reason(response)}")
            return response.json().jsonObject["properties"] as? JsonObject ?: JsonObject(emptyMap())
        }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val collection = container.single()
                if (key == null) {
                    // An id or a vector can come with the properties; Weaviate makes the rest.
                    val body = buildJsonObject {
                        put("class", collection)
                        document["id"]?.takeIf { it !is JsonNull }?.let { put("id", it) }
                        document["vector"]?.takeIf { it !is JsonNull }?.let { put("vector", it) }
                        put("properties", JsonObject(document - "id" - "vector"))
                    }
                    val created = json("POST", "/v1/objects", body).jsonObject
                    return@io buildJsonObject { put("id", created["id"]!!); put(VersionField, JsonTables.version(JsonObject(document - "id" - "vector"))) }
                }
                val id = key["id"]!!.jsonPrimitive.content
                val current = current(collection, id) ?: throw IOException(tr("The object was deleted by someone else since it was read; reload and try again."))
                if (key[VersionField]?.jsonPrimitive?.content != JsonTables.version(current)) throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", id))
                // A merge: properties taken out of the document are set to null.
                val properties = JsonObject(current.keys.filter { it !in document }.associateWith { JsonNull } + document)
                val response = call("PATCH", "/v1/objects/${segment(collection)}/${segment(id)}", buildJsonObject { put("class", collection); put("properties", properties) })
                if (!response.ok) throw IOException("Weaviate: ${reason(response)}")
                buildJsonObject { put("id", id); put(VersionField, JsonTables.version(current(collection, id) ?: document)) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val collection = container.single()
                keys.count { key ->
                    val id = key["id"]!!.jsonPrimitive.content
                    val current = current(collection, id) ?: return@count false
                    if (key[VersionField] != null && key[VersionField]!!.jsonPrimitive.content != JsonTables.version(current)) {
                        throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", id))
                    }
                    call("DELETE", "/v1/objects/${segment(collection)}/${segment(id)}").ok
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("GraphQL"),
                "{\n  Get {\n    Article(limit: 10) { title _additional { id } }\n  }\n}",
                tr("A GraphQL query (Get, Aggregate, Explore), or a REST request: a method and a path, then a JSON body."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "{\n  Get {\n    $it(limit: 10) { _additional { id } }\n  }\n}" } ?: "GET /v1/schema"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val text = statement.trim()
                if (HttpStatement.Methods.any { text.startsWith("$it ", ignoreCase = true) }) return@io HttpConsole.run(rest, text)
                val started = System.currentTimeMillis()
                val data = graphql(text)
                val elapsed = System.currentTimeMillis() - started
                // The first list under Get or Aggregate, its _additional fields flattened.
                val rows = data.values.filterIsInstance<JsonObject>().flatMap { it.values }.filterIsInstance<JsonArray>().firstOrNull()?.map { row ->
                    val obj = row.jsonObject
                    JsonObject((obj["_additional"] as? JsonObject).orEmpty() + (obj - "_additional"))
                }
                if (rows.isNullOrEmpty()) ConsoleReply.Text(JsonTables.pretty(data), elapsed) else ConsoleReply.Table(JsonTables.rows(rows, leading = listOf("id"), elapsedMillis = elapsed))
            }

        /** GraphQL only reads in Weaviate; REST requests read with GET or a POST to /v1/graphql. */
        override fun isRead(statement: String): Boolean {
            val text = statement.trim()
            return if (HttpStatement.Methods.any { text.startsWith("$it ", ignoreCase = true) }) HttpConsole.isRead(text, listOf("/v1/graphql")) else text.isNotEmpty()
        }

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"

        /** Keys whose values are GraphQL enums, written without quotes. */
        private val Enums = setOf("operator")

        /** A JSON filter as a GraphQL input value: keys bare, enum values bare, everything else as JSON. */
        internal fun graphQl(value: JsonElement, key: String? = null): String =
            when (value) {
                is JsonObject -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${graphQl(v, k)}" }
                is JsonArray -> value.joinToString(", ", "[", "]") { graphQl(it, key) }
                is JsonPrimitive -> if (key in Enums && value.isString) value.content else value.toString()
                JsonNull -> "null"
            }

        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        internal fun reason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val message = json?.get("error")?.let { e -> (e as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull }
                ?: json?.get("message")?.jsonPrimitive?.contentOrNull
            return "${message ?: response.body.take(200)} (${response.status})"
        }
    }
}

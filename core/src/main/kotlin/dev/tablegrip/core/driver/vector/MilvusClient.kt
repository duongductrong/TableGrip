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
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Milvus through its RESTful API v2 (the gRPC port, 19530). A user and password sign in as `user:password`, a password
 * alone as a token; the database field picks the database (`default` when blank). Collections hold entities: a record is
 * an entity's scalar fields, its primary key as the key; vectors stay out of the grid but are kept on edits. The query
 * field is a Milvus boolean expression. Edits upsert the entity once its fields are checked unchanged since read.
 * Reads have Milvus' default bounded consistency: a write shows up in the browser within a few seconds.
 */
class MilvusClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Milvus", config, host, port)
        val token = if (config.user.isNotBlank()) "${config.user.trim()}:${password.orEmpty()}" else password?.takeIf { it.isNotEmpty() }
        token?.let { rest.authorization = "Bearer $it" }
        return Connection(config, rest).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val database = config.database.trim().ifEmpty { "default" }

        /** A collection's primary key and its vector fields. */
        private data class Schema(val primaryKey: String, val vectors: Set<String>, val fields: List<String>)

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** One API call; Milvus answers 200 with a code, which is 0 on success. */
        private fun call(path: String, body: JsonObject = JsonObject(emptyMap())): JsonElement {
            val response = rest.send("POST", "/v2/vectordb$path", JsonObject(mapOf("dbName" to JsonPrimitive(database)) + body).toString())
            val json = runCatching { response.json().jsonObject }.getOrNull() ?: throw IOException(tr("{0} doesn't look like Milvus: {1}", rest.base, response.body.take(120)))
            val code = json["code"]?.jsonPrimitive?.intOrNull ?: 0
            if (code == 1800 || code == 2 && json["message"]?.jsonPrimitive?.contentOrNull?.contains("auth", ignoreCase = true) == true) {
                throw IOException(tr("{0} refused the credentials: {1}", "Milvus", json["message"]?.jsonPrimitive?.contentOrNull.orEmpty()))
            }
            if (code != 0) throw IOException("Milvus: ${json["message"]?.jsonPrimitive?.contentOrNull ?: json} ($code)")
            return json["data"] ?: JsonNull
        }

        internal fun version(): String {
            call("/collections/list")
            return "Milvus ($database)"
        }

        override suspend fun server(): String = io { version() }

        private fun schema(collection: String): Schema {
            val fields = call("/collections/describe", buildJsonObject { put("collectionName", collection) }).jsonObject["fields"]?.jsonArray.orEmpty().map { it.jsonObject }
            val primary = fields.firstOrNull { it["primaryKey"]?.jsonPrimitive?.contentOrNull == "true" }?.get("name")?.jsonPrimitive?.content ?: "id"
            val vectors = fields.filter { it["type"]?.jsonPrimitive?.contentOrNull?.endsWith("Vector") == true }.map { it["name"]!!.jsonPrimitive.content }.toSet()
            return Schema(primary, vectors, fields.map { it["name"]!!.jsonPrimitive.content })
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                call("/collections/list").jsonArray.map { it.jsonPrimitive.content }.sorted().map { name ->
                    val count = runCatching { call("/collections/get_stats", buildJsonObject { put("collectionName", name) }).jsonObject["rowCount"]?.jsonPrimitive?.longOrNull }.getOrNull()
                    RecordContainer(listOf(name), "collection", count)
                }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(call("/collections/describe", buildJsonObject { put("collectionName", container.single()) })) }

        override val queryLanguage =
            RecordLanguage(tr("Filter"), tr("city == \"Hanoi\" and year > 2020"), tr("A Milvus boolean expression on scalar fields; nothing lists every entity."))

        private fun query(collection: String, schema: Schema, filter: String, limit: Int, offset: Int, withVectors: Boolean = false): List<JsonObject> =
            call("/entities/query", buildJsonObject {
                put("collectionName", collection)
                put("filter", filter)
                put("limit", limit)
                put("offset", offset)
                // Everything, dynamic fields included; vectors are dropped here unless wanted.
                put("outputFields", JsonArray(listOf(JsonPrimitive("*"))))
            }).jsonArray.map { it.jsonObject }.map { if (withVectors) it else JsonObject(it.filterKeys { k -> k !in schema.vectors }) }

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val collection = container.single()
                val schema = schema(collection)
                val offset = cursor?.toIntOrNull() ?: 0
                // Milvus needs a filter or a limit; an always-true one keeps ordering by key stable.
                val rows = query(collection, schema, query.trim(), limit + 1, offset)
                RecordPage(rows.take(limit).map { record(it, schema) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        private fun record(entity: JsonObject, schema: Schema): Record {
            val document = JsonObject(entity.filterKeys { it !in schema.vectors })
            return Record(buildJsonObject { put(schema.primaryKey, entity[schema.primaryKey] ?: JsonNull); put(VersionField, JsonTables.version(document)) }, document)
        }

        private fun filterFor(schema: Schema, id: JsonElement) = "${schema.primaryKey} in [$id]"

        private fun current(collection: String, schema: Schema, id: JsonElement): JsonObject? = query(collection, schema, filterFor(schema, id), 1, 0, withVectors = true).firstOrNull()

        override suspend fun template(container: List<String>): JsonObject = io { JsonObject(schema(container.single()).fields.associateWith { JsonNull }) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val collection = container.single()
                val schema = schema(collection)
                val id = document[schema.primaryKey]?.takeIf { it !is JsonNull }
                if (key == null) {
                    if (id != null && current(collection, schema, id) != null) throw IOException(tr("An entity with this {0} already exists.", schema.primaryKey))
                    schema.vectors.firstOrNull { document[it] == null || document[it] is JsonNull }?.let { throw IOException(tr("A new entity needs its {0}.", it)) }
                    val inserted = call("/entities/insert", buildJsonObject { put("collectionName", collection); put("data", JsonArray(listOf(document))) }).jsonObject
                    val newId = id ?: inserted["insertIds"]?.jsonArray?.firstOrNull() ?: JsonNull
                    return@io buildJsonObject { put(schema.primaryKey, newId) }
                }
                val keyId = key[schema.primaryKey]!!
                if (id != keyId) throw IOException(tr("An entity's {0} can't change; add a new one instead.", schema.primaryKey))
                val now = current(collection, schema, keyId) ?: throw IOException(tr("The entity was deleted by someone else since it was read; reload and try again."))
                if (key[VersionField]?.jsonPrimitive?.content != JsonTables.version(JsonObject(now.filterKeys { it !in schema.vectors }))) {
                    throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", keyId.toString()))
                }
                // An upsert replaces the whole entity: its vectors come from the stored one.
                val entity = JsonObject(now.filterKeys { it in schema.vectors } + document)
                call("/entities/upsert", buildJsonObject { put("collectionName", collection); put("data", JsonArray(listOf(entity))) })
                buildJsonObject { put(schema.primaryKey, keyId); put(VersionField, JsonTables.version(JsonObject(document))) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val collection = container.single()
                val schema = schema(collection)
                keys.count { key ->
                    val id = key[schema.primaryKey]!!
                    val now = current(collection, schema, id) ?: return@count false
                    if (key[VersionField] != null && key[VersionField]!!.jsonPrimitive.content != JsonTables.version(JsonObject(now.filterKeys { it !in schema.vectors }))) {
                        throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", id.toString()))
                    }
                    call("/entities/delete", buildJsonObject { put("collectionName", collection); put("filter", filterFor(schema, id)) })
                    true
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "POST /v2/vectordb/entities/query\n{\n  \"collectionName\": \"docs\",\n  \"filter\": \"id > 0\",\n  \"limit\": 10\n}",
                tr("Requests to the Milvus RESTful API: a method and a path, then a JSON body. Lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "POST /v2/vectordb/entities/query\n{\n  \"collectionName\": \"$it\",\n  \"filter\": \"\",\n  \"limit\": 10,\n  \"outputFields\": [\"*\"]\n}" }
                ?: "POST /v2/vectordb/collections/list\n{}"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val reply = HttpConsole.run(rest, statement) { json -> HttpConsole.firstList((json as? JsonObject)?.get("data") ?: json) }
                if (reply is ConsoleReply.Text) {
                    // Milvus reports errors in a 200 reply.
                    val code = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(reply.text).jsonObject["code"]?.jsonPrimitive?.intOrNull }.getOrNull()
                    if (code != null && code != 0) throw IOException("Milvus: ${reply.text}")
                }
                reply
            }

        override fun isRead(statement: String): Boolean =
            HttpConsole.isRead(statement, listOf("/list", "/describe", "/get_stats", "/get_load_state", "/has", "/entities/query", "/entities/get", "/entities/search", "/entities/hybrid_search"))

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"
    }
}

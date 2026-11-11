package dev.tablegrip.core.driver.widecolumn

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
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.put

/**
 * Apache HBase through its REST server (port 8080). A user and password are basic auth, when a proxy asks for them.
 * Tables open on their rows, in row key order: a record is a row's key and its `family:qualifier` cells as text. The
 * query field takes a row key prefix. Edits put the changed cells and delete the removed ones, once the row is checked
 * unchanged since read; HBase has no transactions, so the check and the write are two steps.
 */
class HbaseClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("HBase", config, host, port)
        if (config.user.isNotBlank()) rest.authorization = RestClient.basic(config.user.trim(), password)
        return Connection(config, rest).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun json(method: String, path: String, body: JsonElement? = null): JsonElement? {
            val response = rest.send(method, path, body?.toString())
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "HBase", response.body.take(200)))
            if (response.status == 404) return null
            if (!response.ok) throw IOException("HBase: ${response.body.take(300).ifEmpty { "HTTP ${response.status}" }}")
            return if (response.body.isBlank()) JsonNull else response.json()
        }

        internal fun version(): String {
            val version = runCatching { json("GET", "/version/cluster")?.jsonObject?.get("Version")?.jsonPrimitive?.contentOrNull }.getOrElse { if (it.message?.contains("credentials") == true) throw it else null }
                ?: throw IOException(tr("{0} doesn't look like the HBase REST server: {1}", rest.base, "/version/cluster"))
            return "Apache HBase $version"
        }

        override suspend fun server(): String = io { version() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                json("GET", "/")?.jsonObject?.get("table")?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }.sorted().map { RecordContainer(listOf(it), "table") }
            }

        override suspend fun definition(container: List<String>): String = io { JsonTables.pretty(json("GET", "/${segment(container.single())}/schema") ?: JsonNull) }

        override val queryLanguage = RecordLanguage(tr("Row key"), tr("A row key prefix, e.g. user#42"), tr("Lists the rows whose key starts with the text, in key order."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val table = segment(container.single())
                val prefix = query.trim()
                // A prefix is the range from itself to itself with its last character raised.
                val parameters = buildList {
                    add("limit=${limit + 1}")
                    (cursor ?: prefix.ifEmpty { null })?.let { add("startrow=${segment(it)}") }
                    if (prefix.isNotEmpty()) add("endrow=${segment(prefix.dropLast(1) + (prefix.last() + 1))}")
                }.joinToString("&")
                val path = "/$table/*?$parameters"
                val rows = json("GET", path)?.jsonObject?.get("Row")?.jsonArray.orEmpty().map { it.jsonObject }.map(::row)
                // One more row than a page: the next page starts at it.
                RecordPage(rows.take(limit).map(::record), rows.getOrNull(limit)?.get("row")?.jsonPrimitive?.content)
            }

        /** A REST row as one object: its key as `row`, then each `family:qualifier` cell. */
        private fun row(row: JsonObject): JsonObject =
            JsonObject(mapOf("row" to JsonPrimitive(decode(row["key"]!!.jsonPrimitive.content))) +
                row["Cell"]?.jsonArray.orEmpty().map { it.jsonObject }.associate { cell ->
                    decode(cell["column"]!!.jsonPrimitive.content) to JsonPrimitive(decode(cell["$"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                })

        private fun record(row: JsonObject) = Record(buildJsonObject { put("row", row["row"]!!); put(VersionField, JsonTables.version(row)) }, row)

        private fun current(table: String, key: String): JsonObject? =
            json("GET", "/$table/${segment(key)}")?.jsonObject?.get("Row")?.jsonArray?.firstOrNull()?.jsonObject?.let(::row)

        override suspend fun template(container: List<String>): JsonObject =
            io {
                val families = json("GET", "/${segment(container.single())}/schema")?.jsonObject?.get("ColumnSchema")?.jsonArray.orEmpty().map { it.jsonObject["name"]!!.jsonPrimitive.content }
                JsonObject(mapOf("row" to JsonPrimitive("")) + families.associate { "$it:" to JsonPrimitive("") })
            }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val table = segment(container.single())
                val rowKey = document["row"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: throw IOException(tr("A row needs its key."))
                val cells = document.filterKeys { it != "row" }.filterValues { it !is JsonNull }
                cells.keys.firstOrNull { ':' !in it }?.let { throw IOException(tr("{0} isn't a family:qualifier column.", it)) }
                val now = current(table, rowKey)
                if (key == null) {
                    if (now != null) throw IOException(tr("The row {0} already exists.", rowKey))
                } else {
                    if (key["row"]?.jsonPrimitive?.content != rowKey) throw IOException(tr("A row's key can't change; add a new row instead."))
                    if (now == null) throw IOException(tr("The row was deleted by someone else since it was read; reload and try again."))
                    if (key[VersionField]?.jsonPrimitive?.content != JsonTables.version(now)) throw IOException(tr("The row was changed by someone else since it was read; reload and try again."))
                }
                val changed = cells.filter { (column, value) -> now?.get(column) != value }
                if (changed.isNotEmpty()) {
                    json("PUT", "/$table/${segment(rowKey)}", buildJsonObject {
                        put("Row", JsonArray(listOf(buildJsonObject {
                            put("key", encode(rowKey))
                            put("Cell", JsonArray(changed.map { (column, value) ->
                                buildJsonObject { put("column", encode(column)); put("$", encode((value as? JsonPrimitive)?.contentOrNull ?: value.toString())) }
                            }))
                        })))
                    })
                }
                now?.keys?.filter { it != "row" && it !in cells }?.forEach { column -> json("DELETE", "/$table/${segment(rowKey)}/${segment(column)}") }
                val written = current(table, rowKey) ?: document
                buildJsonObject { put("row", rowKey); put(VersionField, JsonTables.version(written)) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val table = segment(container.single())
                keys.count { key ->
                    val rowKey = key["row"]!!.jsonPrimitive.content
                    val now = current(table, rowKey) ?: return@count false
                    if (key[VersionField] != null && key[VersionField]!!.jsonPrimitive.content != JsonTables.version(now)) {
                        throw IOException(tr("The row was changed by someone else since it was read; reload and try again."))
                    }
                    json("DELETE", "/$table/${segment(rowKey)}")
                    true
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(tr("HTTP"), "GET /orders/schema", tr("Requests to the HBase REST server: a method and a path, then a JSON body. Keys and values in its replies are base64."))

        override fun sampleStatement(container: List<String>): String = container.firstOrNull()?.let { "GET /$it/*?limit=10" } ?: "GET /"

        override suspend fun execute(statement: String): ConsoleReply = io { HttpConsole.run(rest, statement) }

        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, emptyList())

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"

        private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        private fun decode(base64: String): String = String(Base64.getDecoder().decode(base64))
    }
}

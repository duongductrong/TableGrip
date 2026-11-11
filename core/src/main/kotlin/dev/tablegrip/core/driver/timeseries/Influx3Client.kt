package dev.tablegrip.core.driver.timeseries

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * InfluxDB 3 (Core and Enterprise) through its HTTP API and SQL. The password is a token, when the server asks for
 * one. Databases hold tables, which open on their newest rows. The query field is a WHERE clause or a whole SELECT;
 * the console runs SQL, or InfluxQL after an `influxql:` prefix. Read-only: points are written by line protocol.
 */
class Influx3Client : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("InfluxDB", config, host, port)
        password?.takeIf { it.isNotEmpty() }?.let { rest.authorization = "Bearer $it" }
        return Connection(config, rest).also { it.ping() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        internal fun ping(): String {
            val response = rest.send("GET", "/ping")
            if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "InfluxDB", response.body.take(200)))
            val json = runCatching { response.json().jsonObject }.getOrNull()
            val version = json?.get("version")?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("{0} doesn't look like InfluxDB 3: {1}", rest.base, response.body.take(120)))
            // The ping may be open to anyone; listing databases proves the token.
            databases()
            return "${json["product_name"]?.jsonPrimitive?.contentOrNull ?: "InfluxDB 3"} $version"
        }

        override suspend fun server(): String = io { ping() }

        private fun databases(): List<String> {
            val response = rest.send("GET", "/api/v3/configure/database?format=json")
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "InfluxDB", response.body.take(200)))
            if (!response.ok) throw IOException("InfluxDB: ${response.body.take(200)}")
            return response.json().jsonArray.mapNotNull { it.jsonObject.values.firstOrNull()?.jsonPrimitive?.contentOrNull }
        }

        /** Runs a query in [database] and returns its rows; [language] is `sql` or `influxql`. */
        internal fun query(database: String, text: String, language: String = "sql"): List<JsonObject> {
            val response = rest.send("GET", "/api/v3/query_$language?db=${encode(database)}&format=json&q=${encode(text)}")
            if (!response.ok) throw IOException("InfluxDB: " + (runCatching { response.json().jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: response.body.take(300)))
            if (response.body.isBlank()) return emptyList()
            return response.json().jsonArray.map { it.jsonObject }
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                when (parent.size) {
                    0 -> databases().filter { !it.startsWith("_") }.sorted().map { RecordContainer(listOf(it), "database", browsable = false, hasChildren = true) }
                    1 -> query(parent[0], "SHOW TABLES").filter { it["table_schema"]?.jsonPrimitive?.contentOrNull == "iox" }
                        .map { it["table_name"]!!.jsonPrimitive.content }.sorted().map { RecordContainer(parent + it, "table") }
                    else -> emptyList()
                }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val (database, table) = container
                JsonTables.pretty(JsonArray(query(database, "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'iox' AND table_name = '${table.replace("'", "''")}'")))
            }

        override val queryLanguage =
            RecordLanguage(
                tr("WHERE"),
                tr("room = 'kitchen' AND time > now() - interval '1 hour'   or a whole SELECT"),
                tr("A SQL WHERE clause on the table, or a whole SELECT. Rows come newest first."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val (database, table) = container
                val offset = cursor?.toLongOrNull() ?: 0
                val text = query.trim().removeSuffix(";").trim()
                val sql =
                    if (Regex("^select\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) "SELECT * FROM ($text) LIMIT ${limit + 1} OFFSET $offset"
                    else "SELECT * FROM \"${table.replace("\"", "\"\"")}\"${if (text.isEmpty()) "" else " WHERE $text"} ORDER BY time DESC LIMIT ${limit + 1} OFFSET $offset"
                val rows = query(database, sql).map { row -> JsonObject(row.filterKeys { it == "time" } + row.filterKeys { it != "time" }) }
                RecordPage(rows.take(limit).map { Record(null, it) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        override fun canWrite(container: List<String>): Boolean = false

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject = throw IOException(tr("{0} is read-only here.", "InfluxDB"))

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("{0} is read-only here.", "InfluxDB"))

        override val consoleLanguage =
            RecordLanguage(
                tr("SQL"),
                "USE sensors;\nSELECT * FROM home ORDER BY time DESC LIMIT 20",
                tr("SQL queries, after a first line USE <database>; an influxql: prefix runs InfluxQL instead."),
            )

        override fun sampleStatement(container: List<String>): String =
            when (container.size) {
                0 -> "USE _internal;\nSHOW DATABASES"
                1 -> "USE ${container[0]};\nSHOW TABLES"
                else -> "USE ${container[0]};\nSELECT * FROM \"${container[1]}\" ORDER BY time DESC LIMIT 100"
            }

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val use = Regex("^\\s*use\\s+(\\S+?)\\s*;?\\s*\\n([\\s\\S]*)$", RegexOption.IGNORE_CASE).matchEntire(statement)
                    ?: throw IOException(tr("Start with USE <database>; on its own line."))
                var text = use.groupValues[2].trim().removeSuffix(";")
                val language = if (text.startsWith("influxql:", ignoreCase = true)) "influxql".also { text = text.substringAfter(':').trim() } else "sql"
                val started = System.currentTimeMillis()
                val rows = query(use.groupValues[1], text, language)
                val elapsed = System.currentTimeMillis() - started
                if (rows.isEmpty()) ConsoleReply.Text(tr("No rows."), elapsed) else ConsoleReply.Table(JsonTables.rows(rows.take(5_000), leading = listOf("time"), elapsedMillis = elapsed))
            }

        /** The query API only reads. */
        override fun isRead(statement: String): Boolean = true

        override fun close() = Unit
    }

    companion object {
        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}

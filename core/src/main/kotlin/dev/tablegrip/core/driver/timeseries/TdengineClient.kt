package dev.tablegrip.core.driver.timeseries

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.sql.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * TDengine through its REST API (taosAdapter, port 6041). A user and password are basic auth (`root`/`taosdata` by
 * default). Databases hold supertables and normal tables, which open on their newest rows; the query field is a WHERE
 * clause or a whole SELECT. Read-only in the browser; the console runs any SQL, writes when the profile allows them.
 */
class TdengineClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("TDengine", config, host, port)
        rest.authorization = RestClient.basic(config.user.trim().ifEmpty { "root" }, password ?: if (config.user.isBlank()) "taosdata" else "")
        return Connection(config, rest).also { it.version() }
    }

    internal data class Result(val columns: List<Pair<String, String>>, val rows: List<JsonArray>, val affected: Long)

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        internal fun sql(text: String): Result {
            val response = rest.send("POST", "/rest/sql", text, "text/plain")
            if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "TDengine", response.body.take(200)))
            val json = runCatching { response.json().jsonObject }.getOrNull() ?: throw IOException(tr("{0} doesn't look like TDengine: {1}", rest.base, response.body.take(120)))
            val code = json["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code != 0) {
                val message = json["desc"]?.jsonPrimitive?.contentOrNull ?: response.body.take(200)
                if (code == 3 || message.contains("Authentication", ignoreCase = true)) throw IOException(tr("{0} refused the credentials: {1}", "TDengine", message))
                throw IOException("TDengine: $message")
            }
            val columns = json["column_meta"]?.jsonArray.orEmpty().map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
            val rows = json["data"]?.jsonArray.orEmpty().map { it.jsonArray }
            val affected = if (columns.singleOrNull()?.first == "affected_rows") rows.firstOrNull()?.firstOrNull()?.jsonPrimitive?.longOrNull ?: 0 else 0
            return Result(columns, rows, affected)
        }

        internal fun version(): String = "TDengine " + (sql("SELECT server_version()").rows.firstOrNull()?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "")

        override suspend fun server(): String = io { version() }

        private fun objects(result: Result) = result.rows.map { row -> JsonObject(result.columns.map { it.first }.zip(row).toMap()) }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                when (parent.size) {
                    0 -> objects(sql("SELECT name FROM information_schema.ins_databases")).map { it["name"]!!.jsonPrimitive.content }
                        .filter { it !in SystemDatabases }.sorted().map { RecordContainer(listOf(it), "database", browsable = false, hasChildren = true) }
                    1 -> {
                        val db = parent[0].replace("'", "''")
                        val stables = objects(sql("SELECT stable_name FROM information_schema.ins_stables WHERE db_name = '$db'")).map {
                            RecordContainer(parent + it["stable_name"]!!.jsonPrimitive.content, "supertable")
                        }
                        val tables = objects(sql("SELECT table_name FROM information_schema.ins_tables WHERE db_name = '$db' AND type = 'NORMAL_TABLE'")).map {
                            RecordContainer(parent + it["table_name"]!!.jsonPrimitive.content, "table")
                        }
                        (stables + tables).sortedBy { it.name }
                    }
                    else -> emptyList()
                }
            }

        override suspend fun definition(container: List<String>): String =
            io { sql("SHOW CREATE ${if (isSuper(container)) "STABLE" else "TABLE"} ${name(container)}").rows.firstOrNull()?.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty() }

        private fun isSuper(container: List<String>) =
            sql("SELECT count(*) FROM information_schema.ins_stables WHERE db_name = '${container[0].replace("'", "''")}' AND stable_name = '${container[1].replace("'", "''")}'")
                .rows.single()[0].jsonPrimitive.longOrNull == 1L

        override val queryLanguage =
            RecordLanguage(tr("WHERE"), tr("location = 'Hanoi' AND ts > now - 1h   or a whole SELECT"), tr("A SQL WHERE clause on the table, or a whole SELECT. Rows come newest first."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val offset = cursor?.toLongOrNull() ?: 0
                val text = query.trim().removeSuffix(";").trim()
                val select =
                    if (Regex("^select\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) text
                    else "SELECT * FROM ${name(container)}${if (text.isEmpty()) "" else " WHERE $text"} ORDER BY _c0 DESC"
                val rows = objects(sql("$select LIMIT ${limit + 1} OFFSET $offset"))
                RecordPage(rows.take(limit).map { Record(null, it) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        override fun canWrite(container: List<String>): Boolean = false

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject = throw IOException(tr("{0} is read-only here.", "TDengine"))

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("{0} is read-only here.", "TDengine"))

        override val consoleLanguage =
            RecordLanguage(tr("SQL"), "SELECT * FROM power.meters ORDER BY ts DESC LIMIT 20", tr("TDengine SQL, one statement at a time; name tables with their database (db.table)."))

        override fun sampleStatement(container: List<String>): String =
            when (container.size) {
                0 -> "SHOW DATABASES"
                1 -> "SHOW ${container[0]}.STABLES"
                else -> "SELECT * FROM ${name(container)} ORDER BY _c0 DESC LIMIT 100"
            }

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val started = System.currentTimeMillis()
                val result = sql(statement.trim().removeSuffix(";"))
                val elapsed = System.currentTimeMillis() - started
                if (result.columns.singleOrNull()?.first == "affected_rows") return@io ConsoleReply.Text(tr("{0} row(s) changed.", result.affected), elapsed)
                ConsoleReply.Table(
                    QueryResult.Rows(
                        result.columns.map { (name, type) -> ResultColumn(name, type.lowercase(), if (type.uppercase() in Numeric) Types.NUMERIC else Types.VARCHAR) },
                        result.rows.take(5_000).map { row -> row.map { JsonTables.cell(it) } },
                        truncated = result.rows.size > 5_000,
                        elapsedMillis = elapsed,
                    ),
                )
            }

        override fun isRead(statement: String): Boolean = isReadSql(statement)

        override fun close() = Unit
    }

    companion object {
        private val SystemDatabases = setOf("information_schema", "performance_schema", "log", "audit")
        private val Numeric = setOf("INT", "BIGINT", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "INT UNSIGNED", "BIGINT UNSIGNED", "SMALLINT UNSIGNED", "TINYINT UNSIGNED", "DECIMAL")

        internal fun name(container: List<String>) = container.joinToString(".") { "`" + it.replace("`", "``") + "`" }

        internal fun isReadSql(statement: String): Boolean {
            val first = statement.trim().split(Regex("\\s+"), limit = 2).first().uppercase()
            return first in setOf("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN") && ';' !in statement.trim().removeSuffix(";")
        }
    }
}

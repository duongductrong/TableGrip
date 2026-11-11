package dev.tablegrip.core.driver.sqlite

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

/** A statement's result as an HTTP SQLite server returns it: columns and JSON values, or the rows a write changed. */
data class SqlResult(val columns: List<String>, val rows: List<List<JsonElement>>, val changed: Long = 0)

/** How one HTTP SQLite server runs statements: rqlite's API, or libSQL's Hrana over HTTP. */
interface SqlTransport {
    /** Describes the server, failing when it isn't the one expected or refuses the credentials. */
    fun describe(): String

    /** Runs [statements] (SQL and positional arguments) in one transaction; one result each. */
    fun run(statements: List<Pair<String, List<JsonElement>>>): List<SqlResult>
}

/**
 * SQLite served over HTTP (rqlite, libSQL / Turso), browsed as records: tables, rows by WHERE clause, a SQL console.
 * A row's key is its primary key (its rowid when it has none) plus a hash of the row as read; a change or delete re-reads
 * the row and refuses when it changed since. Values are JSON numbers, text and nulls; blobs show as base64.
 */
abstract class SqliteHttpClient : RecordClient {
    abstract fun transport(config: ConnectionConfig, rest: RestClient, password: String?): SqlTransport

    abstract val engine: String

    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val transport = transport(config, RestClient(engine, config, host, port), password)
        val description = transport.describe()
        return Connection(config, transport, description)
    }

    internal class Connection(override val config: ConnectionConfig, private val sql: SqlTransport, private val description: String) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun query(text: String, vararg args: JsonElement): SqlResult = sql.run(listOf(text to args.toList())).single()

        override suspend fun server(): String = io { sql.describe() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                query("SELECT name, type FROM sqlite_schema WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND name NOT LIKE '\\_%' ESCAPE '\\' ORDER BY name").rows.map { row ->
                    val name = row[0].jsonPrimitive.content
                    val kind = row[1].jsonPrimitive.content
                    val count = if (kind == "table") runCatching { query("SELECT count(*) FROM ${quote(name)}").rows.single()[0].jsonPrimitive.longOrNull }.getOrNull() else null
                    RecordContainer(listOf(name), kind, count)
                }
            }

        override suspend fun definition(container: List<String>): String =
            io { query("SELECT sql FROM sqlite_schema WHERE name = ?", JsonPrimitive(container.single())).rows.joinToString("\n") { it[0].jsonPrimitive.contentOrNull.orEmpty() } }

        /** The columns that name a row: the primary key, or the rowid. */
        private fun keyColumns(table: String): List<String> {
            val info = query("SELECT name, pk FROM pragma_table_info(?) ORDER BY pk", JsonPrimitive(table)).rows
            val primary = info.filter { (it[1].jsonPrimitive.longOrNull ?: 0) > 0 }.map { it[0].jsonPrimitive.content }
            return primary.ifEmpty { listOf("rowid") }
        }

        override val queryLanguage =
            RecordLanguage(tr("WHERE"), tr("status = 'paid' AND total > 10   or a whole SELECT"), tr("A SQL WHERE clause on the table, optionally with ORDER BY; or a whole SELECT."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val table = container.single()
                val offset = cursor?.toLongOrNull() ?: 0
                val text = query.trim().removeSuffix(";").trim()
                val view = definitionKind(table) == "view"
                val keys = if (view) emptyList() else keyColumns(table)
                val select =
                    when {
                        Regex("^(select|with)\\s", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "SELECT * FROM ($text)"
                        else -> {
                            val rowid = if (keys == listOf("rowid")) "rowid AS rowid, " else ""
                            val clause = if (text.isEmpty()) "" else if (Regex("^order\\s+by\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) " $text" else " WHERE $text"
                            val order = if (" order by " in " ${clause.lowercase()} " || keys.isEmpty()) "" else " ORDER BY " + keys.joinToString { quote(it) }
                            "SELECT $rowid* FROM ${quote(table)}$clause$order"
                        }
                    }
                val result = query("$select LIMIT ${limit + 1} OFFSET $offset")
                val rows = result.rows.map { JsonObject(result.columns.zip(it).toMap()) }
                RecordPage(rows.take(limit).map { record(it, keys) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        private fun definitionKind(table: String): String? = query("SELECT type FROM sqlite_schema WHERE name = ?", JsonPrimitive(table)).rows.firstOrNull()?.get(0)?.jsonPrimitive?.contentOrNull

        private fun record(row: JsonObject, keys: List<String>): Record {
            val document = JsonObject(row - "rowid")
            val key = if (keys.isNotEmpty() && keys.all { it in row }) JsonObject(keys.associateWith { row.getValue(it) } + (VersionField to JsonPrimitive(JsonTables.version(document)))) else null
            return Record(key, document, if ("rowid" in row && keys == listOf("rowid")) buildJsonObject { put("rowid", row.getValue("rowid")) } else JsonObject(emptyMap()))
        }

        override fun canWrite(container: List<String>): Boolean = runCatching { definitionKind(container.single()) == "table" }.getOrDefault(false)

        override suspend fun template(container: List<String>): JsonObject =
            io { JsonObject(query("SELECT name FROM pragma_table_info(?) ORDER BY cid", JsonPrimitive(container.single())).rows.associate { it[0].jsonPrimitive.content to JsonNull }) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val table = container.single()
                val keys = keyColumns(table)
                if (key == null) {
                    val values = document.filterValues { it !is JsonNull }
                    if (values.isEmpty()) throw IOException(tr("Fill in at least one field."))
                    sql.run(listOf("INSERT INTO ${quote(table)} (${values.keys.joinToString { quote(it) }}) VALUES (${values.keys.joinToString { "?" }})" to values.values.map(::value)))
                    return@io JsonObject(keys.filter { it in document }.associateWith { document.getValue(it) })
                }
                if (keys != listOf("rowid")) keys.firstOrNull { document[it] != key[it] }?.let { throw IOException(tr("A row's {0} can't change; add a new row instead.", it)) }
                checkUnchanged(table, keys, key)
                val columns = document.keys.toList()
                sql.run(listOf("UPDATE ${quote(table)} SET ${columns.joinToString { "${quote(it)} = ?" }} WHERE ${where(keys)}" to columns.map { value(document.getValue(it)) } + keys.map { value(key.getValue(it)) }))
                JsonObject(keys.associateWith { key.getValue(it) } + (VersionField to JsonPrimitive(JsonTables.version(document))))
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val table = container.single()
                val columns = keyColumns(table)
                keys.count { key ->
                    val current = current(table, columns, key) ?: return@count false
                    if (key[VersionField] != null && JsonTables.version(current) != key[VersionField]!!.jsonPrimitive.content) throw changed()
                    sql.run(listOf("DELETE FROM ${quote(table)} WHERE ${where(columns)}" to columns.map { value(key.getValue(it)) })).single().changed > 0
                }.toLong()
            }

        private fun current(table: String, keys: List<String>, key: JsonObject): JsonObject? {
            val result = sql.run(listOf("SELECT * FROM ${quote(table)} WHERE ${where(keys)}" to keys.map { value(key[it] ?: throw IOException(tr("The key has no {0}.", it))) })).single()
            return result.rows.firstOrNull()?.let { JsonObject(result.columns.zip(it).toMap()) }
        }

        private fun checkUnchanged(table: String, keys: List<String>, key: JsonObject) {
            val current = current(table, keys, key) ?: throw IOException(tr("The row was deleted by someone else since it was read; reload and try again."))
            if (key[VersionField] != null && JsonTables.version(current) != key[VersionField]!!.jsonPrimitive.content) throw changed()
        }

        private fun changed() = IOException(tr("The row was changed by someone else since it was read; reload and try again."))

        private fun where(keys: List<String>) = keys.joinToString(" AND ") { "${quote(it)} = ?" }

        override val consoleLanguage =
            RecordLanguage(tr("SQL"), "SELECT * FROM orders LIMIT 20;", tr("SQLite statements, separated by semicolons and run in one transaction; the last one's rows show below."))

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let { "SELECT * FROM ${quote(it)} LIMIT 100;" } ?: "SELECT name, type FROM sqlite_schema;"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val statements = splitStatements(statement)
                if (statements.isEmpty()) throw IOException(tr("Nothing to run"))
                val started = System.currentTimeMillis()
                val results = sql.run(statements.map { it to emptyList() })
                val elapsed = System.currentTimeMillis() - started
                val last = results.last()
                if (last.columns.isEmpty()) return@io ConsoleReply.Text(tr("{0} row(s) changed.", results.sumOf { it.changed }), elapsed)
                ConsoleReply.Table(
                    QueryResult.Rows(
                        last.columns.mapIndexed { i, name ->
                            val label = JsonTables.typeLabel(last.rows.mapNotNull { it.getOrNull(i) })
                            ResultColumn(name, label, if (label == "number") Types.NUMERIC else Types.VARCHAR)
                        },
                        last.rows.take(5_000).map { row -> row.map(JsonTables::cell) },
                        truncated = last.rows.size > 5_000,
                        elapsedMillis = elapsed,
                    ),
                )
            }

        override fun isRead(statement: String): Boolean = isReadSql(statement)

        override fun close() = Unit
    }

    companion object {
        internal const val VersionField = "_version"

        internal fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

        /** A JSON value as a statement argument: numbers, text and null as themselves, objects and arrays as JSON text. */
        internal fun value(element: JsonElement): JsonElement = if (element is JsonPrimitive || element is JsonNull) element else JsonPrimitive(element.toString())

        /** Statements that only read: SELECT, WITH … SELECT, EXPLAIN, and PRAGMAs that don't set anything. */
        internal fun isReadSql(text: String): Boolean {
            val statements = splitStatements(text)
            return statements.isNotEmpty() && statements.all { s ->
                val first = s.trimStart().split(Regex("\\s+"), limit = 2).first().uppercase()
                when (first) {
                    "SELECT", "EXPLAIN", "VALUES" -> true
                    // A PRAGMA sets something with = or an argument, except the ones that describe a table or index.
                    "PRAGMA" -> '=' !in s && ('(' !in s || Regex("^pragma\\s+(table_x?info|index_list|index_info|index_xinfo|foreign_key_list)\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(s.trim()))
                    "WITH" -> !Regex("\\b(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)
                    else -> false
                }
            }
        }

        /** Splits SQL on semicolons outside quotes and comments. */
        internal fun splitStatements(text: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            var quote: Char? = null
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == quote) quote = null
                    }
                    c == '\'' || c == '"' || c == '`' -> {
                        quote = c
                        current.append(c)
                    }
                    c == '-' && text.getOrNull(i + 1) == '-' -> {
                        while (i < text.length && text[i] != '\n') i++
                        continue
                    }
                    c == ';' -> {
                        current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
            return statements
        }
    }
}

/** rqlite through its HTTP API (port 4001): a user and password are basic auth; reads ask for strong consistency. */
class RqliteClient : SqliteHttpClient() {
    override val engine = "rqlite"

    override fun transport(config: ConnectionConfig, rest: RestClient, password: String?): SqlTransport {
        if (config.user.isNotBlank()) rest.authorization = RestClient.basic(config.user.trim(), password)
        return object : SqlTransport {
            override fun describe(): String {
                val response = rest.send("GET", "/status")
                if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "rqlite", response.body.take(200)))
                val build = runCatching { response.json().jsonObject["build"]?.jsonObject }.getOrNull()
                    ?: throw IOException(tr("{0} doesn't look like rqlite: {1}", rest.base, response.body.take(120)))
                return "rqlite ${build["version"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
            }

            override fun run(statements: List<Pair<String, List<JsonElement>>>): List<SqlResult> {
                val body = JsonArray(statements.map { (text, args) -> JsonArray(listOf(JsonPrimitive(text)) + args) })
                // /db/request takes reads and writes alike; one transaction keeps a batch all-or-nothing.
                val response = rest.send("POST", "/db/request?transaction&level=strong", body.toString())
                if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "rqlite", response.body.take(200)))
                if (!response.ok) throw IOException("rqlite: ${response.body.take(300)}")
                val results = response.json().jsonObject["results"]?.jsonArray.orEmpty().map { it.jsonObject }
                results.firstNotNullOfOrNull { it["error"]?.jsonPrimitive?.contentOrNull }?.let { throw IOException("rqlite: $it") }
                return results.map { result ->
                    SqlResult(
                        result["columns"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                        result["values"]?.jsonArray?.map { it.jsonArray.toList() }.orEmpty(),
                        result["rows_affected"]?.jsonPrimitive?.longOrNull ?: 0,
                    )
                }
            }
        }
    }
}

/** libSQL server (sqld) and Turso, through Hrana over HTTP: the password is an auth token, when the server asks for one. */
class LibsqlClient : SqliteHttpClient() {
    override val engine = "libSQL"

    override fun transport(config: ConnectionConfig, rest: RestClient, password: String?): SqlTransport {
        password?.takeIf { it.isNotEmpty() }?.let { rest.authorization = "Bearer $it" }
        return object : SqlTransport {
            override fun describe(): String {
                val version = rest.send("GET", "/version").takeIf { it.ok }?.body?.trim()
                run(listOf("SELECT 1" to emptyList()))
                return "libSQL" + (version?.takeIf { it.isNotEmpty() && it.length < 40 }?.let { " $it" } ?: "")
            }

            override fun run(statements: List<Pair<String, List<JsonElement>>>): List<SqlResult> {
                // One batch: BEGIN, each statement only if the one before succeeded, COMMIT, and ROLLBACK if COMMIT didn't run.
                val n = statements.size
                fun ok(step: Int) = buildJsonObject { put("type", "ok"); put("step", step) }
                val steps = buildJsonArray {
                    add(buildJsonObject { put("stmt", buildJsonObject { put("sql", "BEGIN") }) })
                    statements.forEachIndexed { i, (text, args) ->
                        add(buildJsonObject { put("condition", ok(i)); put("stmt", buildJsonObject { put("sql", text); put("args", JsonArray(args.map(::hrana))) }) })
                    }
                    add(buildJsonObject { put("condition", ok(n)); put("stmt", buildJsonObject { put("sql", "COMMIT") }) })
                    add(buildJsonObject {
                        put("condition", buildJsonObject { put("type", "not"); put("cond", ok(n + 1)) })
                        put("stmt", buildJsonObject { put("sql", "ROLLBACK") })
                    })
                }
                val body = buildJsonObject {
                    put("requests", buildJsonArray {
                        add(buildJsonObject { put("type", "batch"); put("batch", buildJsonObject { put("steps", steps) }) })
                        add(buildJsonObject { put("type", "close") })
                    })
                }
                val response = rest.send("POST", "/v2/pipeline", body.toString())
                if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "libSQL", response.body.take(200)))
                if (!response.ok) throw IOException(tr("{0} doesn't look like libSQL: {1}", rest.base, response.body.take(200)))
                val first = response.json().jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw IOException("libSQL: " + response.body.take(200))
                if (first["type"]?.jsonPrimitive?.contentOrNull == "error") throw IOException("libSQL: " + (first["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: first.toString()))
                val batch = first["response"]!!.jsonObject["result"]!!.jsonObject
                batch["step_errors"]?.jsonArray?.firstOrNull { it !is JsonNull }?.let { throw IOException("libSQL: " + (it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString())) }
                return batch["step_results"]!!.jsonArray.subList(1, n + 1).map { it.jsonObject }.map { result ->
                    SqlResult(
                        result["cols"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty() }.orEmpty(),
                        result["rows"]?.jsonArray?.map { row -> row.jsonArray.map { plain(it.jsonObject) } }.orEmpty(),
                        result["affected_row_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    )
                }
            }
        }
    }

    companion object {
        /** A JSON argument as a Hrana value. */
        internal fun hrana(value: JsonElement): JsonObject =
            when {
                value is JsonNull -> buildJsonObject { put("type", "null") }
                value is JsonPrimitive && value.isString -> buildJsonObject { put("type", "text"); put("value", value.content) }
                value is JsonPrimitive && (value.content == "true" || value.content == "false") -> buildJsonObject { put("type", "integer"); put("value", if (value.content == "true") "1" else "0") }
                value is JsonPrimitive && value.content.toLongOrNull() != null -> buildJsonObject { put("type", "integer"); put("value", value.content) }
                value is JsonPrimitive -> buildJsonObject { put("type", "float"); put("value", value.content.toDouble()) }
                else -> buildJsonObject { put("type", "text"); put("value", value.toString()) }
            }

        /** A Hrana value as JSON: integers and floats as numbers, blobs as base64 objects. */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        internal fun plain(value: JsonObject): JsonElement =
            when (value["type"]?.jsonPrimitive?.contentOrNull) {
                "null" -> JsonNull
                "integer" -> kotlinx.serialization.json.JsonUnquotedLiteral(value["value"]!!.jsonPrimitive.content)
                "float" -> value["value"]!!
                "blob" -> buildJsonObject { put("base64", value["base64"] ?: JsonNull) }
                else -> value["value"] ?: JsonNull
            }
    }
}

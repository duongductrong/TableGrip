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
import dev.tablegrip.core.driver.http.RestResponse
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * VictoriaMetrics through its Prometheus-compatible query API (Prometheus answers the same requests). A user and password
 * are HTTP basic auth; the `bearerToken` property sends a token instead. Metric names are the containers. Queries are
 * PromQL (MetricsQL on VictoriaMetrics) evaluated now: an instant vector gives a row per series, a range vector such as
 * `http_requests_total[1h]` a row per raw sample. A metric opens on its samples of the last hour. Read-only: samples are
 * written by the systems that collect them.
 */
class PromClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient(config.type.displayName, config, host, port)
        val token = config.properties["bearerToken"]?.trim()?.takeIf { it.isNotEmpty() }
        rest.authorization =
            when {
                token != null -> "Bearer $token"
                config.user.isNotBlank() -> RestClient.basic(config.user.trim(), password)
                else -> null
            }
        return Connection(config, rest).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val engine = config.type.displayName

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** A query API call, failing with the server's error; returns `data`. */
        private fun call(path: String): JsonElement {
            val response = rest.send("GET", path)
            val json = runCatching { response.json().jsonObject }.getOrNull()
            if (!response.ok || json?.get("status")?.jsonPrimitive?.contentOrNull == "error") {
                if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", engine, errorReason(response)))
                throw IOException("$engine: ${errorReason(response)}")
            }
            return json?.get("data") ?: throw IOException(tr("{0} doesn't look like a Prometheus API: {1}", rest.base, response.body.take(120)))
        }

        internal fun version(): String {
            call("/api/v1/labels?limit=1")
            val build = runCatching { (call("/api/v1/status/buildinfo") as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull }.getOrNull()
            return "$engine" + (build?.let { " ($it)" } ?: "")
        }

        override suspend fun server(): String = io { version() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                call("/api/v1/label/__name__/values").jsonArray.map { it.jsonPrimitive.content }.sorted().map { RecordContainer(listOf(it), "metric") }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val metric = container.single()
                val now = Instant.now().epochSecond
                val series = call("/api/v1/series?match[]=${encode(selector(metric))}&start=${now - 86_400}&end=$now").jsonArray.map { it.jsonObject }
                val labels = sortedMapOf<String, MutableSet<String>>()
                series.forEach { s -> s.forEach { (name, value) -> if (name != "__name__") labels.getOrPut(name) { sortedSetOf() } += value.jsonPrimitive.content } }
                val metadata = runCatching { call("/api/v1/metadata?metric=${encode(metric)}") }.getOrNull()
                JsonTables.pretty(buildJsonObject {
                    put("metric", metric)
                    put("seriesInTheLastDay", series.size)
                    put("labels", JsonObject(labels.mapValues { (_, values) -> JsonArray(values.take(50).map(::JsonPrimitive)) }))
                    metadata?.let { put("metadata", it) }
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("PromQL"),
                tr("rate(http_requests_total{job=\"api\"}[5m])   or   http_requests_total[1h] for raw samples"),
                tr("A PromQL (MetricsQL) expression evaluated now: an instant vector lists one row per series, a range vector each raw sample. Nothing shows the metric's samples of the last hour."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val expression = query.trim().ifEmpty { selector(container.single()) + "[1h]" }
                val rows = evaluate(expression)
                val offset = cursor?.toIntOrNull() ?: 0
                val page = rows.drop(offset).take(limit)
                RecordPage(page.map { Record(null, it) }, (offset + limit).takeIf { it < rows.size }?.toString(), rows.size.toLong())
            }

        /** Evaluates [expression] now and flattens the result: a row per series, or per sample of a range vector. */
        private fun evaluate(expression: String): List<JsonObject> {
            val data = call("/api/v1/query?query=${encode(expression)}").jsonObject
            val result = data["result"]
            return when (data["resultType"]?.jsonPrimitive?.contentOrNull) {
                "vector" -> result!!.jsonArray.map { it.jsonObject }.map { series -> row(series["metric"]!!.jsonObject, series["value"]!!.jsonArray) }
                "matrix" -> result!!.jsonArray.map { it.jsonObject }.flatMap { series ->
                    // Newest first, as a browser of recent data reads best.
                    series["values"]!!.jsonArray.reversed().map { row(series["metric"]!!.jsonObject, it.jsonArray) }
                }
                "scalar", "string" -> listOf(row(JsonObject(emptyMap()), result!!.jsonArray))
                else -> emptyList()
            }
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private fun row(labels: JsonObject, sample: JsonArray): JsonObject {
            val seconds = sample[0].jsonPrimitive.content.toBigDecimal()
            val time = Instant.ofEpochMilli(seconds.movePointRight(3).toLong()).toString()
            val raw = sample[1].jsonPrimitive.content
            val value = raw.toBigDecimalOrNull()?.let { JsonUnquotedLiteral(raw) } ?: JsonPrimitive(raw)
            return JsonObject(mapOf("time" to JsonPrimitive(time)) + labels.filterKeys { it == "__name__" } + labels.filterKeys { it != "__name__" }.toSortedMap() + ("value" to value))
        }

        override fun canWrite(container: List<String>): Boolean = false

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject = throw IOException(tr("{0} is read-only here.", engine))

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("{0} is read-only here.", engine))

        override val consoleLanguage =
            RecordLanguage(
                tr("PromQL"),
                "sum by (job) (rate(http_requests_total[5m]))",
                tr("A PromQL (MetricsQL) expression, evaluated now: one row per series, or per raw sample for a range vector."),
            )

        override fun sampleStatement(container: List<String>): String = container.firstOrNull()?.let { selector(it) + "[5m]" } ?: "up"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val started = System.currentTimeMillis()
                val rows = evaluate(statement.trim().removeSuffix(";"))
                val elapsed = System.currentTimeMillis() - started
                if (rows.isEmpty()) ConsoleReply.Text(tr("No series."), elapsed)
                else ConsoleReply.Table(JsonTables.rows(rows.take(MaxConsoleRows), leading = listOf("time", "__name__"), elapsedMillis = elapsed), note = if (rows.size > MaxConsoleRows) tr("First {0} of {1} rows", MaxConsoleRows, rows.size) else null)
            }

        /** Queries only read. */
        override fun isRead(statement: String): Boolean = true

        override fun close() = Unit
    }

    companion object {
        private const val MaxConsoleRows = 5_000

        /** A selector for [metric], quoted when its name isn't a plain identifier. */
        internal fun selector(metric: String): String =
            if (Regex("^[a-zA-Z_:][a-zA-Z0-9_:]*$").matches(metric)) metric else "{__name__=\"${metric.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

        internal fun errorReason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val reason = json?.get("error")?.jsonPrimitive?.contentOrNull
            return "${reason ?: response.body.take(200).ifEmpty { "HTTP ${response.status}" }} (${response.status})"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}

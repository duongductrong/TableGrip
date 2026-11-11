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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * InfluxDB 2 through its HTTP API and Flux. The password is an API token; the database field is the organization.
 * Buckets hold measurements, which open on their points of the last 30 days, newest first, a row per timestamp with a
 * column per tag and field (pivoted). The query field adds Flux steps (`|> filter(fn: (r) => r.host == "a")`), or takes a
 * whole query. Read-only in the browser: points are written by line protocol, which the console doesn't send either.
 */
class InfluxClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("InfluxDB", config, host, port)
        password?.takeIf { it.isNotEmpty() }?.let { rest.authorization = "Token $it" }
        return Connection(config, rest).also { it.check() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val org = config.database.trim()
        private val showSystem = config.properties["showSystem"]?.toBoolean() == true

        @Volatile private var description: String? = null

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun checked(response: RestResponse): RestResponse {
            if (response.ok) return response
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "InfluxDB", errorReason(response)))
            throw IOException("InfluxDB: ${errorReason(response)}")
        }

        internal fun check(): String {
            val health = runCatching { rest.send("GET", "/health").json().jsonObject }.getOrNull()
            val version = health?.get("version")?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("{0} doesn't look like InfluxDB 2: {1}", rest.base, "/health"))
            if (org.isEmpty()) throw IOException(tr("Put the organization in the database field."))
            // The health check is open to anyone; listing buckets proves the token.
            checked(rest.send("GET", "/api/v2/buckets?limit=1&org=${encode(org)}"))
            return "InfluxDB ${version.removePrefix("v")} ($org)".also { description = it }
        }

        override suspend fun server(): String = io { description ?: check() }

        /** Runs a Flux query and reads its CSV: one object per row, every result's rows together. */
        internal fun flux(query: String): List<JsonObject> {
            val body = buildJsonObject {
                put("query", query)
                put("type", "flux")
                put("dialect", buildJsonObject { put("header", true); put("annotations", buildJsonArray { add(JsonPrimitive("datatype")) }) })
            }
            val response = checked(rest.send("POST", "/api/v2/query?org=${encode(org)}", body.toString(), headers = mapOf("Accept" to "application/csv")))
            return FluxCsv.parse(response.body)
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                when (parent.size) {
                    0 -> {
                        val buckets = mutableListOf<String>()
                        var offset = 0
                        do {
                            val page = checked(rest.send("GET", "/api/v2/buckets?limit=100&offset=$offset&org=${encode(org)}")).json().jsonObject["buckets"]?.jsonArray.orEmpty()
                            buckets += page.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                            offset += page.size
                        } while (page.size == 100)
                        buckets.filter { showSystem || !it.startsWith("_") }.sorted().map { RecordContainer(listOf(it), "bucket", browsable = false, hasChildren = true) }
                    }
                    1 -> flux("import \"influxdata/influxdb/schema\"\nschema.measurements(bucket: ${quote(parent[0])}, start: -$Window)")
                        .mapNotNull { it["_value"]?.jsonPrimitive?.contentOrNull }.sorted().map { RecordContainer(parent + it, "measurement") }
                    else -> emptyList()
                }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                if (container.size == 1) {
                    val buckets = checked(rest.send("GET", "/api/v2/buckets?name=${encode(container[0])}&org=${encode(org)}")).json().jsonObject["buckets"]
                    return@io JsonTables.pretty(buckets ?: JsonArray(emptyList()))
                }
                val (bucket, measurement) = container
                val schema = "import \"influxdata/influxdb/schema\"\n"
                val tags = flux(schema + "schema.measurementTagKeys(bucket: ${quote(bucket)}, measurement: ${quote(measurement)}, start: -$Window)").mapNotNull { it["_value"] }
                val fields = flux(schema + "schema.measurementFieldKeys(bucket: ${quote(bucket)}, measurement: ${quote(measurement)}, start: -$Window)").mapNotNull { it["_value"] }
                JsonTables.pretty(buildJsonObject {
                    put("bucket", bucket)
                    put("measurement", measurement)
                    put("tags", JsonArray(tags.filter { (it as? JsonPrimitive)?.contentOrNull?.startsWith("_") == false }))
                    put("fields", JsonArray(fields))
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("Flux"),
                tr("|> filter(fn: (r) => r.host == \"web-1\")   or a whole query starting with from("),
                tr("Flux steps applied to the measurement's points of the last 30 days, before they are pivoted to a row per time; or a whole Flux query."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val (bucket, measurement) = container
                val offset = cursor?.toIntOrNull() ?: 0
                val text = query.trim()
                val source =
                    if (text.startsWith("from(") || text.startsWith("import")) text
                    else "from(bucket: ${quote(bucket)})\n  |> range(start: -$Window)\n  |> filter(fn: (r) => r._measurement == ${quote(measurement)})\n  $text\n" +
                        "  |> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n  |> group()\n  |> sort(columns: [\"_time\"], desc: true)"
                // One more than a page tells whether another follows.
                val rows = flux("$source\n  |> limit(n: ${limit + 1}, offset: $offset)").map(::point)
                RecordPage(rows.take(limit).map { Record(null, it) }, (offset + limit).takeIf { rows.size > limit }?.toString())
            }

        /** A row without Flux's bookkeeping columns, time first. */
        private fun point(row: JsonObject): JsonObject {
            val kept = row.filterKeys { it !in setOf("result", "table", "_start", "_stop", "_measurement") }
            return JsonObject(kept.filterKeys { it == "_time" } + kept.filterKeys { it != "_time" })
        }

        override fun canWrite(container: List<String>): Boolean = false

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject = throw IOException(tr("{0} is read-only here.", "InfluxDB"))

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("{0} is read-only here.", "InfluxDB"))

        override val consoleLanguage =
            RecordLanguage(
                tr("Flux"),
                "from(bucket: \"metrics\")\n  |> range(start: -1h)\n  |> filter(fn: (r) => r._measurement == \"cpu\")",
                tr("Flux queries; every table of the result shows as rows. Queries that write (to(), experimental.to()…) need a profile that isn't read-only."),
            )

        override fun sampleStatement(container: List<String>): String =
            when (container.size) {
                0 -> "buckets()"
                1 -> "import \"influxdata/influxdb/schema\"\nschema.measurements(bucket: ${quote(container[0])})"
                else -> "from(bucket: ${quote(container[0])})\n  |> range(start: -1h)\n  |> filter(fn: (r) => r._measurement == ${quote(container[1])})\n  |> limit(n: 100)"
            }

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val started = System.currentTimeMillis()
                val rows = flux(statement)
                val elapsed = System.currentTimeMillis() - started
                if (rows.isEmpty()) ConsoleReply.Text(tr("No rows."), elapsed)
                else ConsoleReply.Table(JsonTables.rows(rows.take(MaxConsoleRows), leading = listOf("_time", "_measurement", "_field", "_value"), elapsedMillis = elapsed))
            }

        override fun isRead(statement: String): Boolean = isReadFlux(statement)

        override fun close() = Unit
    }

    /** Flux's annotated CSV: tables separated by blank lines, each with its `#datatype` row and header. */
    internal object FluxCsv {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        fun parse(text: String): List<JsonObject> {
            val rows = mutableListOf<JsonObject>()
            var types: List<String> = emptyList()
            var header: List<String>? = null
            for (line in records(text)) {
                if (line.size == 1 && line[0].isEmpty()) {
                    header = null
                    continue
                }
                when {
                    line.firstOrNull() == "#datatype" -> {
                        types = line
                        header = null
                    }
                    line.firstOrNull()?.startsWith("#") == true -> Unit
                    header == null -> header = line
                    else -> rows += JsonObject(header.indices.filter { it > 0 && header[it].isNotEmpty() }.associate { i -> header[i] to value(line.getOrElse(i) { "" }, types.getOrElse(i) { "string" }) })
                }
            }
            return rows
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private fun value(text: String, type: String): JsonElement =
            when {
                text.isEmpty() && type != "string" -> JsonNull
                type in setOf("long", "unsignedLong", "double") && text.toBigDecimalOrNull() != null -> JsonUnquotedLiteral(text)
                type == "boolean" -> JsonPrimitive(text == "true")
                else -> JsonPrimitive(text)
            }

        /** CSV records, quotes and doubled quotes handled; a blank line is a record of one empty value. */
        private fun records(text: String): List<List<String>> {
            val records = mutableListOf<List<String>>()
            var fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c == ',' -> {
                        fields += field.toString()
                        field.clear()
                    }
                    !quoted && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                        fields += field.toString()
                        field.clear()
                        records += fields
                        fields = mutableListOf()
                    }
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || fields.isNotEmpty()) {
                fields += field.toString()
                records += fields
            }
            return records
        }
    }

    companion object {
        /** How far back measurements are listed and opened. */
        private const val Window = "30d"
        private const val MaxConsoleRows = 5_000

        private val WriteCalls = Regex("""(^|[^\w.])(to|wideTo)\s*\(|experimental\s*\.\s*to\s*\(|sql\s*\.\s*to\s*\(|\.\s*write\s*\(""")

        /** Flux only writes through to(), wideTo(), experimental.to() and the like; anything with those is a write. */
        internal fun isReadFlux(query: String): Boolean = query.isNotBlank() && !WriteCalls.containsMatchIn(query)

        internal fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        internal fun errorReason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            val message = json?.get("message")?.jsonPrimitive?.contentOrNull
            return "${message ?: response.body.take(200).ifEmpty { "HTTP ${response.status}" }} (${response.status})"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}

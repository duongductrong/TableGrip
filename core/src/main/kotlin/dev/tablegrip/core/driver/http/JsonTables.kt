package dev.tablegrip.core.driver.http

import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.i18n.tr
import java.sql.Types
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One request typed in a console the way Kibana's Dev Tools take it: `GET /orders/_search` and a JSON body below. */
data class HttpStatement(val method: String, val path: String, val body: String?) {
    companion object {
        val Methods = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")

        /**
         * Reads `METHOD /path` from the first line and the rest as the body. The path may leave out its leading `/`;
         * lines starting with `#` or `//` before the request are comments.
         */
        fun parse(statement: String): HttpStatement {
            val lines = statement.trim().lines().dropWhile { it.isBlank() || it.trimStart().startsWith("#") || it.trimStart().startsWith("//") }
            val first = lines.firstOrNull()?.trim() ?: throw IllegalArgumentException(tr("Nothing to run"))
            val parts = first.split(Regex("\\s+"), limit = 2)
            val method = parts[0].uppercase()
            if (method !in Methods || parts.size < 2) throw IllegalArgumentException(tr("Start with a method and a path, e.g. GET /_cat/indices"))
            val path = parts[1].trim().let { if (it.startsWith("/")) it else "/$it" }
            val body = lines.drop(1).joinToString("\n").trim().ifEmpty { null }
            return HttpStatement(method, path, body)
        }
    }

    /** The path without its query string. */
    val endpoint: String
        get() = path.substringBefore('?')
}

/** JSON replies as grid rows, and JSON values as the grid shows them. */
object JsonTables {
    private val Pretty = Json { prettyPrint = true }

    fun pretty(element: JsonElement): String = Pretty.encodeToString(JsonElement.serializer(), element)

    /** A value as a cell: strings without quotes, objects and arrays as compact JSON, null as NULL. */
    fun cell(value: JsonElement?): String? =
        when (value) {
            null, is JsonNull -> null
            is JsonPrimitive -> value.content
            else -> value.toString()
        }

    /** The JSON type of a column's values: `string`, `number`, `bool`, `object`, `array`, or `mixed`. */
    fun typeLabel(values: List<JsonElement>): String {
        val types =
            values.filter { it !is JsonNull }.map { value ->
                when (value) {
                    is JsonPrimitive -> if (value.isString) "string" else if (value.content == "true" || value.content == "false") "bool" else "number"
                    is JsonArray -> "array"
                    is JsonObject -> "object"
                }
            }.toSet()
        return types.singleOrNull() ?: if (types.isEmpty()) "null" else "mixed"
    }

    private fun sqlType(label: String) = if (label == "number") Types.NUMERIC else Types.VARCHAR

    /** Objects as rows, a column per top-level field in the order they first appear; [leading] columns come first. */
    fun rows(objects: List<JsonObject>, leading: List<String> = emptyList(), elapsedMillis: Long = 0): QueryResult.Rows {
        val fields = LinkedHashSet<String>().apply {
            leading.filter { name -> objects.any { name in it } }.forEach(::add)
            objects.forEach { addAll(it.keys) }
        }.toList()
        val columns = fields.map { field ->
            val label = typeLabel(objects.mapNotNull { it[field] })
            ResultColumn(field, label, sqlType(label))
        }
        return QueryResult.Rows(columns, objects.map { o -> fields.map { cell(o[it]) } }, truncated = false, elapsedMillis = elapsedMillis)
    }

    /** Records as rows: their [Record.meta] columns, then the document's. */
    fun records(records: List<Record>, elapsedMillis: Long = 0): QueryResult.Rows {
        val metaFields = LinkedHashSet<String>().apply { records.forEach { addAll(it.meta.keys) } }.toList()
        return rows(records.map { JsonObject(it.meta + it.document) }, metaFields, elapsedMillis)
    }

    /** Named columns and positional rows (SQL endpoints' replies) as rows. */
    fun columnar(names: List<String>, types: List<String>, values: List<JsonArray>, elapsedMillis: Long = 0): QueryResult.Rows =
        QueryResult.Rows(
            names.mapIndexed { i, name -> ResultColumn(name, types.getOrElse(i) { "" }, if (types.getOrNull(i)?.lowercase() in NumericNames) Types.NUMERIC else Types.VARCHAR) },
            values.map { row -> names.indices.map { cell(row.getOrNull(it)) } },
            truncated = false,
            elapsedMillis = elapsedMillis,
        )

    /**
     * A short hash of [value] with object keys sorted: a record's version, for engines without one of their own, so a
     * write can tell whether the record changed since it was read.
     */
    fun version(value: JsonElement): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): String =
        when (value) {
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            is JsonPrimitive -> if (value.isString) value.toString() else value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.content
            JsonNull -> "null"
        }

    private val NumericNames = setOf("long", "integer", "short", "byte", "double", "float", "half_float", "scaled_float", "unsigned_long", "number", "int", "bigint", "decimal")
}

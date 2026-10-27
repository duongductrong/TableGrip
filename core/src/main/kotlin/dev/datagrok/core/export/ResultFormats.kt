package dev.datagrok.core.export

import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.ResultColumn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CopyFormat(val label: String) {
    Tsv("Tab-separated"),
    Csv("CSV"),
    Json("JSON"),
    Markdown("Markdown"),
    SqlInsert("SQL INSERT"),
    SqlInList("SQL IN list"),
}

/** Renders a block of cells as text for the clipboard or a file. */
object ResultFormats {

    fun format(
        format: CopyFormat,
        columns: List<ResultColumn>,
        rows: List<List<String?>>,
        includeHeader: Boolean = true,
        dialect: SqlDialect? = null,
        tableName: String? = null,
    ): String =
        when (format) {
            CopyFormat.Tsv -> delimited(columns, rows, includeHeader, '\t')
            CopyFormat.Csv -> delimited(columns, rows, includeHeader, ',')
            CopyFormat.Json -> json(columns, rows)
            CopyFormat.Markdown -> markdown(columns, rows)
            CopyFormat.SqlInsert -> sqlInsert(columns, rows, dialect ?: SqlDialect.PostgreSQL, tableName ?: "table_name")
            CopyFormat.SqlInList -> sqlInList(columns, rows, dialect ?: SqlDialect.PostgreSQL)
        }

    /** TSV keeps cells verbatim (like spreadsheets expect) unless they contain tabs or newlines; CSV follows RFC 4180. */
    private fun delimited(columns: List<ResultColumn>, rows: List<List<String?>>, includeHeader: Boolean, separator: Char): String {
        fun cell(value: String?): String {
            val text = value.orEmpty()
            val needsQuotes = text.any { it == separator || it == '"' || it == '\n' || it == '\r' }
            return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
        val lines = buildList {
            if (includeHeader) add(columns.joinToString(separator.toString()) { cell(it.name) })
            rows.forEach { row -> add(row.joinToString(separator.toString(), transform = ::cell)) }
        }
        return lines.joinToString("\n")
    }

    private fun json(columns: List<ResultColumn>, rows: List<List<String?>>): String {
        val array =
            JsonArray(
                rows.map { row ->
                    JsonObject(columns.mapIndexed { i, column -> column.name to jsonValue(row[i], column) }.toMap())
                }
            )
        return PrettyJson.encodeToString(JsonElement.serializer(), array)
    }

    private fun jsonValue(value: String?, column: ResultColumn): JsonElement =
        when {
            value == null -> JsonNull
            column.isNumeric -> value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            else -> JsonPrimitive(value)
        }

    private fun markdown(columns: List<ResultColumn>, rows: List<List<String?>>): String {
        fun cell(value: String?) = (value ?: "NULL").replace("|", "\\|").replace("\n", " ")
        return buildString {
            append("| ").append(columns.joinToString(" | ") { cell(it.name) }).append(" |\n")
            append("|").append(columns.joinToString("|") { if (it.isNumeric) " ---: " else " --- " }).append("|\n")
            rows.forEach { row -> append("| ").append(row.joinToString(" | ", transform = ::cell)).append(" |\n") }
        }.trimEnd()
    }

    private fun sqlInsert(columns: List<ResultColumn>, rows: List<List<String?>>, dialect: SqlDialect, tableName: String): String {
        val names = columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        return rows.joinToString("\n") { row ->
            val values = row.mapIndexed { i, value -> dialect.literal(value, columns[i].isNumeric) }.joinToString(", ")
            "INSERT INTO $tableName ($names) VALUES ($values);"
        }
    }

    /** Values of the first column, e.g. for pasting into `WHERE id IN (...)`. */
    private fun sqlInList(columns: List<ResultColumn>, rows: List<List<String?>>, dialect: SqlDialect): String =
        rows.map { dialect.literal(it.first(), columns.first().isNumeric) }.distinct().joinToString(", ", "(", ")")

    private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
}

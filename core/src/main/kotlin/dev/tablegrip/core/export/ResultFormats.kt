package dev.tablegrip.core.export

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ResultColumn
import java.io.StringWriter
import java.io.Writer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CopyFormat(val label: String, val extension: String) {
    Tsv("Tab-separated", "tsv"),
    Csv("CSV", "csv"),
    Json("JSON", "json"),
    Markdown("Markdown", "md"),
    SqlInsert("SQL INSERT", "sql"),
    SqlInList("SQL IN list", "sql"),
}

/**
 * Streams rows as text in a [CopyFormat], so exports of large tables never hold them all in memory. Call [write] per
 * row, then [finish].
 */
class ResultWriter(
    private val format: CopyFormat,
    private val columns: List<ResultColumn>,
    private val out: Writer,
    includeHeader: Boolean = true,
    private val dialect: SqlDialect = SqlDialect.PostgreSQL,
    private val tableName: String = "table_name",
) {
    private var rows = 0L
    private val seen = HashSet<String>()
    private val insertPrefix by lazy {
        "INSERT INTO $tableName (${columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }}) VALUES ("
    }

    val rowCount: Long
        get() = rows

    init {
        when (format) {
            CopyFormat.Tsv,
            CopyFormat.Csv -> if (includeHeader) line(columns.map { it.name })
            CopyFormat.Json -> out.write("[")
            CopyFormat.Markdown -> {
                out.write("| " + columns.joinToString(" | ") { markdownCell(it.name) } + " |\n")
                out.write("|" + columns.joinToString("|") { if (it.isNumeric) " ---: " else " --- " } + "|")
            }
            CopyFormat.SqlInsert -> Unit
            CopyFormat.SqlInList -> out.write("(")
        }
    }

    fun write(row: List<String?>) {
        when (format) {
            CopyFormat.Tsv,
            CopyFormat.Csv -> line(row)
            CopyFormat.Json -> {
                if (rows > 0) out.write(",")
                val element = JsonObject(columns.mapIndexed { i, column -> column.name to jsonValue(row[i], column) }.toMap())
                out.write("\n  ")
                out.write(PrettyJson.encodeToString(JsonElement.serializer(), element).replace("\n", "\n  "))
            }
            CopyFormat.Markdown -> out.write("\n| " + row.joinToString(" | ") { markdownCell(it ?: "NULL") } + " |")
            CopyFormat.SqlInsert -> {
                if (rows > 0) out.write("\n")
                out.write(insertPrefix)
                out.write(row.mapIndexed { i, value -> dialect.literal(value, columns[i].isNumeric) }.joinToString(", "))
                out.write(");")
            }
            CopyFormat.SqlInList -> {
                val literal = dialect.literal(row.first(), columns.first().isNumeric)
                if (!seen.add(literal)) return
                if (seen.size > 1) out.write(", ")
                out.write(literal)
            }
        }
        rows++
    }

    fun finish() {
        when (format) {
            CopyFormat.Json -> out.write(if (rows == 0L) "]" else "\n]")
            CopyFormat.SqlInList -> out.write(")")
            else -> Unit
        }
        out.flush()
    }

    /** TSV keeps cells verbatim (as spreadsheets expect) unless they contain separators or newlines; CSV follows RFC 4180. */
    private fun line(cells: List<String?>) {
        val separator = if (format == CopyFormat.Tsv) '\t' else ','
        if (lineCount++ > 0) out.write("\n")
        out.write(cells.joinToString(separator.toString()) { cell ->
            val text = cell.orEmpty()
            if (text.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        })
    }

    private var lineCount = 0

    private fun markdownCell(value: String) = value.replace("|", "\\|").replace("\n", " ")

    private fun jsonValue(value: String?, column: ResultColumn): JsonElement =
        when {
            value == null -> JsonNull
            column.isNumeric -> value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            else -> JsonPrimitive(value)
        }

    private companion object {
        val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
    }
}

/** Renders a block of cells as text, e.g. for the clipboard. */
object ResultFormats {
    fun format(
        format: CopyFormat,
        columns: List<ResultColumn>,
        rows: List<List<String?>>,
        includeHeader: Boolean = true,
        dialect: SqlDialect? = null,
        tableName: String? = null,
    ): String {
        val out = StringWriter()
        val writer = ResultWriter(format, columns, out, includeHeader, dialect ?: SqlDialect.PostgreSQL, tableName ?: "table_name")
        rows.forEach(writer::write)
        writer.finish()
        return out.toString()
    }
}

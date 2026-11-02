package dev.tablegrip.app.grid

import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A line of a JSON tree: [path] identifies it for expanding, [label] is its key or index. */
data class JsonLine(val path: String, val depth: Int, val label: String?, val element: JsonElement) {
    val isContainer: Boolean
        get() = element is JsonObject || element is JsonArray

    /** `{3}` / `[5]` for containers, the value itself for primitives. */
    val summary: String
        get() =
            when (element) {
                is JsonObject -> "{${element.size}}"
                is JsonArray -> "[${element.size}]"
                is JsonNull -> "null"
                is JsonPrimitive -> if (element.isString) "\"${shorten(element.content)}\"" else element.content
            }
}

enum class JsonTokenType { Key, String, Number, Literal }

/** A coloured span of a JSON line: [start] inclusive, [end] exclusive. */
data class JsonToken(val type: JsonTokenType, val start: Int, val end: Int)

/**
 * Tokens of one line of pretty-printed JSON, for colouring. Lines may be cut mid-value for display, so an unterminated
 * string runs to the end of the line; a string followed by `:` is a key.
 */
fun jsonTokens(line: String): List<JsonToken> = buildList {
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                var end = i + 1
                while (end < line.length && line[end] != '"') end += if (line[end] == '\\') 2 else 1
                end = minOf(end + 1, line.length)
                var next = end
                while (next < line.length && line[next] == ' ') next++
                add(JsonToken(if (next < line.length && line[next] == ':') JsonTokenType.Key else JsonTokenType.String, i, end))
                i = end
            }
            c == '-' || c.isDigit() -> {
                var end = i + 1
                while (end < line.length && (line[end].isDigit() || line[end] in ".eE+-")) end++
                add(JsonToken(JsonTokenType.Number, i, end))
                i = end
            }
            c.isLetter() -> {
                var end = i + 1
                while (end < line.length && line[end].isLetter()) end++
                if (line.substring(i, end) in JsonLiterals) add(JsonToken(JsonTokenType.Literal, i, end))
                i = end
            }
            else -> i++
        }
    }
}

private val JsonLiterals = setOf("true", "false", "null")

/** Keeps tree rows to one short line; the full value is in the text view and on the clipboard. */
private fun shorten(value: String, maxChars: Int = 200): String =
    (if (value.length > maxChars) value.take(maxChars) + "…" else value).replace("\r\n", "⏎").replace('\n', '⏎')

/** The lines of [root] shown when the containers in [expanded] (by path) are open; the root is always open. */
fun jsonLines(root: JsonElement, expanded: Set<String>): List<JsonLine> = buildList {
    fun visit(element: JsonElement, path: String, depth: Int, label: String?) {
        add(JsonLine(path, depth, label, element))
        if (path != "$" && path !in expanded) return
        when (element) {
            is JsonObject -> element.forEach { (key, child) -> visit(child, "$path.$key", depth + 1, key) }
            is JsonArray -> element.forEachIndexed { index, child -> visit(child, "$path[$index]", depth + 1, index.toString()) }
            else -> Unit
        }
    }
    visit(root, "$", 0, null)
}

/** Paths of every container in [root], to expand all. */
fun jsonContainerPaths(root: JsonElement): Set<String> = buildSet {
    fun visit(element: JsonElement, path: String) {
        when (element) {
            is JsonObject -> add(path).also { element.forEach { (key, child) -> visit(child, "$path.$key") } }
            is JsonArray -> add(path).also { element.forEachIndexed { index, child -> visit(child, "$path[$index]") } }
            else -> Unit
        }
    }
    visit(root, "$")
}

/** The grid's rows and columns at one moment, so [rowsJson] can run off the UI thread. */
data class RowsSnapshot(val columns: List<ResultColumn>, val rows: List<List<String?>>)

/** Captures the rows of [model] in the display [columns], leaving out rows marked for deletion. */
fun rowsSnapshot(model: GridModel, columns: List<Int>): RowsSnapshot =
    RowsSnapshot(
        columns.map { model.columns[it] },
        (0 until model.rowCount).filter { model.rowStatus(it) != RowStatus.Deleted }.map { row -> columns.map { model.value(row, it) } },
    )

/**
 * The rows as a JSON array of objects keyed by column name. Numbers and booleans keep their type, and `json`/`jsonb`
 * values are embedded rather than quoted.
 */
fun rowsJson(snapshot: RowsSnapshot): JsonArray =
    JsonArray(snapshot.rows.map { row -> JsonObject(snapshot.columns.zip(row).associate { (column, value) -> column.name to cellJson(value, column) }) })

fun rowsJson(model: GridModel, columns: List<Int>): JsonArray = rowsJson(rowsSnapshot(model, columns))

/**
 * Splits [text] into lines for display, cutting each at [maxChars]: a long value (an HTML document, say) is one line
 * of pretty JSON, and laying it out in full is what makes a text view crawl.
 */
fun displayLines(text: String, maxChars: Int = 1000): List<String> =
    text.lineSequence().map { if (it.length > maxChars) it.take(maxChars) + "… (+${it.length - maxChars} chars)" else it }.toList()

internal fun cellJson(value: String?, column: ResultColumn): JsonElement =
    when {
        value == null -> JsonNull
        column.isNumeric -> value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        column.jdbcType == Types.BOOLEAN || column.jdbcType == Types.BIT ->
            when (value.lowercase()) {
                "true", "t", "1" -> JsonPrimitive(true)
                "false", "f", "0" -> JsonPrimitive(false)
                else -> JsonPrimitive(value)
            }
        "json" in column.typeName.lowercase() -> runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
        else -> JsonPrimitive(value)
    }

/** `offset  hex bytes  ascii`, 16 bytes per line, like `xxd`. */
fun hexDump(bytes: ByteArray, limit: Int = 4096): String = buildString {
    val shown = minOf(bytes.size, limit)
    for (start in 0 until shown step 16) {
        val end = minOf(start + 16, shown)
        append("%08x  ".format(start))
        for (i in start until start + 16) {
            if (i < end) append("%02x ".format(bytes[i].toInt() and 0xFF)) else append("   ")
            if (i == start + 7) append(' ')
        }
        append(' ')
        for (i in start until end) {
            val c = bytes[i].toInt() and 0xFF
            append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        append('\n')
    }
}

/** What well-known file signatures say [bytes] are, e.g. `PNG image`; null when unknown. */
fun binaryKind(bytes: ByteArray): String? {
    fun starts(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { (bytes[it].toInt() and 0xFF) == signature[it] }
    return when {
        starts(0x89, 0x50, 0x4E, 0x47) -> "PNG image"
        starts(0xFF, 0xD8, 0xFF) -> "JPEG image"
        starts(0x47, 0x49, 0x46, 0x38) -> "GIF image"
        starts(0x42, 0x4D) -> "BMP image"
        starts(0x25, 0x50, 0x44, 0x46) -> "PDF document"
        starts(0x50, 0x4B, 0x03, 0x04) -> "ZIP archive"
        starts(0x1F, 0x8B) -> "gzip data"
        else -> null
    }
}

/** A date-time value read the way databases print them, with or without an offset. */
data class TimestampValue(val instant: Instant, val hadOffset: Boolean)

private val DatabaseDateTime: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd")
        .optionalStart().appendLiteral('T').optionalEnd()
        .optionalStart().appendLiteral(' ').optionalEnd()
        .appendPattern("HH:mm[:ss]")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .optionalStart().appendLiteral(' ').optionalEnd()
        .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
        .optionalStart().appendOffset("+HHmm", "Z").optionalEnd()
        .optionalStart().appendOffset("+HH", "Z").optionalEnd()
        .toFormatter()

/**
 * Parses `2026-10-28 13:45:00.123+07`, `2026-10-28T06:45:00Z` or `2026-10-28 13:45:00`; values without an offset are
 * taken as UTC, which is how most servers store them.
 */
fun parseTimestamp(text: String): TimestampValue? {
    val parsed = runCatching { DatabaseDateTime.parseBest(text.trim(), OffsetDateTime::from, LocalDateTime::from) }.getOrNull() ?: return null
    return when (parsed) {
        is OffsetDateTime -> TimestampValue(parsed.toInstant(), hadOffset = true)
        is LocalDateTime -> TimestampValue(parsed.toInstant(ZoneOffset.UTC), hadOffset = false)
        else -> null
    }
}

private val Readable = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** The same instant in UTC, the local zone and as Unix time, for the value panel. */
fun timestampConversions(value: TimestampValue, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> =
    listOf(
        "UTC" to Readable.format(value.instant.atZone(ZoneOffset.UTC)),
        "Local (${zone.id})" to Readable.format(value.instant.atZone(zone)),
        "Unix" to "${value.instant.epochSecond}",
    )

/** How grid cells show values, from Settings → Data Editor. Only the display changes: copies and edits see the raw value. */
data class CellFormat(
    val nullText: String = "<null>",
    /** Blank leaves date-times as the database returned them. */
    val dateTimePattern: String = "",
    /** Where date-times that carry an offset are shown; those without one keep their wall-clock time. */
    val zone: ZoneId = ZoneId.systemDefault(),
    val numberGrouping: Boolean = false,
) {
    private val dateTime: DateTimeFormatter? = dateTimePattern.takeIf { it.isNotBlank() }?.let { runCatching { DateTimeFormatter.ofPattern(it) }.getOrNull() }

    fun display(value: String, numeric: Boolean): String =
        when {
            numeric && numberGrouping -> groupDigits(value)
            dateTime != null && value.length >= 16 && value[4] == '-' && value[0].isDigit() ->
                parseTimestamp(value)?.let { dateTime.format(it.instant.atZone(if (it.hadOffset) zone else ZoneOffset.UTC)) } ?: value
            else -> value
        }

    companion object {
        /** Whether [pattern] is blank or a pattern `java.time` accepts. */
        fun isValidPattern(pattern: String): Boolean = pattern.isBlank() || runCatching { DateTimeFormatter.ofPattern(pattern) }.isSuccess
    }
}

/** `1234567.89` → `1,234,567.89`; anything that isn't a plain decimal number is returned as it is. */
internal fun groupDigits(value: String): String {
    val match = PlainNumber.matchEntire(value) ?: return value
    val (sign, whole, rest) = match.destructured
    if (whole.length <= 3) return value
    return sign + whole.reversed().chunked(3).joinToString(",").reversed() + rest
}

private val PlainNumber = Regex("""(-?)(\d+)((?:\.\d+)?)""")

package dev.datagrok.app.grid

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
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
                is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
            }
}

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

package dev.tablegrip.app.keyvalue

import dev.tablegrip.core.driver.KeyInfo
import dev.tablegrip.core.driver.KeyType
import dev.tablegrip.core.driver.KeyValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** e.g. "45s", "12m 5s", "3h 20m", "2d 4h"; a zero second part is left out: "1h", "1d". */
internal fun formatTtl(millis: Long): String {
    val seconds = millis / 1_000
    fun two(major: Long, majorUnit: String, minor: Long, minorUnit: String) = if (minor == 0L) "$major$majorUnit" else "$major$majorUnit $minor$minorUnit"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> two(seconds / 60, "m", seconds % 60, "s")
        seconds < 86_400 -> two(seconds / 3_600, "h", seconds % 3_600 / 60, "m")
        else -> two(seconds / 86_400, "d", seconds % 86_400 / 3_600, "h")
    }
}

/**
 * A remaining TTL as the editor offers it: a key read at 3599s left was most likely set to an hour, so anything past a
 * minute rounds to the nearest minute before picking the unit.
 */
internal fun ttlForEditing(seconds: Long): Pair<Long, TtlUnit> {
    val rounded = if (seconds >= 60) (seconds + 30) / 60 * 60 else seconds
    val unit = TtlUnit.fitting(rounded)
    return rounded / unit.seconds to unit
}

/** `a=1, b=2` → [(a, 1), (b, 2)], or null if a part has no `=`. */
internal fun parseFields(text: String): List<Pair<String, String>>? {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.any { '=' !in it }) return null
    return parts.map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

internal fun formatScore(score: Double): String = if (score == score.toLong().toDouble()) score.toLong().toString() else score.toString()

/** "512 B", "1.4 KB", "3.0 MB". */
internal fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

/** The units the TTL editor offers, largest last. */
internal enum class TtlUnit(val label: String, val seconds: Long) {
    Seconds("seconds", 1),
    Minutes("minutes", 60),
    Hours("hours", 3_600),
    Days("days", 86_400),
    ;

    companion object {
        /** The largest unit that divides [seconds] evenly, so a stored TTL reads back as "2 hours" rather than "7200 seconds". */
        fun fitting(seconds: Long): TtlUnit = entries.reversed().firstOrNull { seconds >= it.seconds && seconds % it.seconds == 0L } ?: Seconds
    }
}

/** [amount] of [unit] in seconds, or null if [amount] isn't a positive whole number. */
internal fun ttlSeconds(amount: String, unit: TtlUnit): Long? = amount.trim().toLongOrNull()?.takeIf { it > 0 }?.let { it * unit.seconds }

/** One-click expiries in the TTL editor. */
internal val TtlPresets = listOf("1 min" to 60L, "1 hour" to 3_600L, "1 day" to 86_400L, "1 week" to 604_800L)

/** What one element of each type is called, for counts and the add row: "2 fields", "Add member". */
internal fun elementNoun(type: KeyType, count: Long): String {
    val (one, many) =
        when (type) {
            KeyType.Hash -> "field" to "fields"
            KeyType.List -> "item" to "items"
            KeyType.Set, KeyType.SortedSet -> "member" to "members"
            KeyType.Stream -> "entry" to "entries"
            else -> "element" to "elements"
        }
    return "%,d %s".format(count, if (count == 1L) one else many)
}

/** One line on what each type is for, under the type picker of the new-key form. */
internal fun typeDescription(type: KeyType): String =
    when (type) {
        KeyType.Text -> "A single value: text, a number, or serialized JSON."
        KeyType.Hash -> "Fields mapped to values, like an object or a row."
        KeyType.List -> "An ordered sequence; good for queues and recent items."
        KeyType.Set -> "Unique members in no particular order."
        KeyType.SortedSet -> "Unique members ordered by a score; good for leaderboards."
        KeyType.Stream -> "An append-only log of entries, each a set of fields."
        else -> ""
    }

internal val KeyValue.type: KeyType
    get() =
        when (this) {
            is KeyValue.Text -> KeyType.Text
            is KeyValue.Hash -> KeyType.Hash
            is KeyValue.ListValue -> KeyType.List
            is KeyValue.SetValue -> KeyType.Set
            is KeyValue.SortedSet -> KeyType.SortedSet
            is KeyValue.Stream -> KeyType.Stream
            is KeyValue.Other -> KeyType.Other
        }

/** The element count for collections, null for strings and unknown types. */
internal val KeyValue.size: Long?
    get() =
        when (this) {
            is KeyValue.Hash -> total
            is KeyValue.ListValue -> total
            is KeyValue.SetValue -> total
            is KeyValue.SortedSet -> total
            is KeyValue.Stream -> total
            else -> null
        }

private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** [text] parsed as a JSON object or array, or null: bare strings and numbers aren't worth a JSON view. */
internal fun parseJsonDocument(text: String): JsonElement? {
    val trimmed = text.trim()
    if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) return null
    return runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
}

internal fun prettyJson(element: JsonElement): String = PrettyJson.encodeToString(JsonElement.serializer(), element)

/** A row of the key list's tree: a namespace folder or a key, at its depth. */
internal sealed interface KeyRow {
    val depth: Int

    /** [path] is the prefix it stands for, delimiter included, e.g. `user:` or `/dg/services/`. */
    data class Group(val path: String, val label: String, override val depth: Int, val keyCount: Int, val expanded: Boolean) : KeyRow

    data class Key(val info: KeyInfo, val label: String, override val depth: Int) : KeyRow
}

/**
 * [keys] as a tree split on [delimiter] (`user:1:profile` → `user` › `1` › `profile`), with groups before keys and both
 * sorted; the children of [collapsed] groups are left out. A leading delimiter, as in etcd's `/services/api`, stays
 * part of the first group's path but not its label.
 */
internal fun keyTree(keys: List<KeyInfo>, delimiter: String, collapsed: Set<String>): List<KeyRow> {
    class Node(val path: String, val label: String) {
        val groups = sortedMapOf<String, Node>()
        val keys = mutableListOf<Pair<String, KeyInfo>>()
        var count = 0
    }
    val root = Node("", "")
    for (key in keys) {
        val lead = if (key.name.startsWith(delimiter)) delimiter else ""
        val segments = key.name.removePrefix(lead).split(delimiter)
        var node = root
        node.count++
        var path = lead
        for (segment in segments.dropLast(1)) {
            path += segment + delimiter
            val parentPath = path
            node = node.groups.getOrPut(segment) { Node(parentPath, segment.ifEmpty { "(empty)" }) }
            node.count++
        }
        node.keys += segments.last().ifEmpty { "(empty)" } to key
    }
    return buildList {
        fun visit(node: Node, depth: Int) {
            for (group in node.groups.values) {
                val expanded = group.path !in collapsed
                add(KeyRow.Group(group.path, group.label, depth, group.count, expanded))
                if (expanded) visit(group, depth + 1)
            }
            node.keys.sortedBy { it.first }.forEach { (label, info) -> add(KeyRow.Key(info, label, depth)) }
        }
        visit(root, 0)
    }
}

/** Every group path in [keys], for "collapse all". */
internal fun keyGroups(keys: List<KeyInfo>, delimiter: String): Set<String> =
    keyTree(keys, delimiter, emptySet()).filterIsInstance<KeyRow.Group>().map { it.path }.toSet()

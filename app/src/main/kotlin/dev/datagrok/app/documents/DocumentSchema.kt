package dev.datagrok.app.documents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.core.completion.DocumentField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The fields a collection's documents have, learnt from the documents the browser has seen: every page loaded plus a
 * random sample. Embedded documents add dotted paths (`address.city`), and documents inside arrays their fields under
 * the array's name (`items.sku`), as filters address them. [version] bumps when something new is learnt.
 */
internal class DocumentSchema {
    var version by mutableIntStateOf(0)
        private set

    private val types = LinkedHashMap<String, LinkedHashSet<String>>()
    private val values = HashMap<String, LinkedHashMap<String, Int>>()

    fun add(documents: List<JsonObject>) {
        if (documents.isEmpty()) return
        documents.forEach { walk("", it, 0) }
        version++
    }

    fun fields(): List<DocumentField> =
        types.map { (path, kinds) ->
            DocumentField(path, kinds.toList(), values[path].orEmpty().entries.sortedByDescending { it.value }.take(MaxValues).map { it.key })
        }

    private fun walk(prefix: String, document: JsonObject, depth: Int) {
        for ((name, value) in document) {
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            record(path, value, depth)
        }
    }

    private fun record(path: String, value: JsonElement, depth: Int) {
        types.getOrPut(path) { LinkedHashSet() } += BsonKind.of(value).label
        when {
            value is JsonArray ->
                value.take(MaxElements).forEach { element ->
                    when {
                        element is JsonObject && BsonKind.of(element) == BsonKind.Object -> if (depth < MaxDepth) walk(path, element, depth + 1)
                        element !is JsonArray -> literal(element)?.let { count(path, it) }
                    }
                }
            value is JsonObject && BsonKind.of(value) == BsonKind.Object -> if (depth < MaxDepth) walk(path, value, depth + 1)
            else -> literal(value)?.let { count(path, it) }
        }
    }

    private fun count(path: String, literal: String) {
        val seen = values.getOrPut(path) { LinkedHashMap() }
        if (literal in seen || seen.size < MaxDistinct) seen[literal] = (seen[literal] ?: 0) + 1
    }

    companion object {
        private const val MaxDepth = 4
        private const val MaxElements = 20
        private const val MaxDistinct = 200
        private const val MaxValues = 30
        private const val MaxLength = 80

        /** A value as the shell writes it in a filter: `'A'`, `30`, `true`, `ObjectId('…')`, `ISODate('…')`. */
        fun literal(value: JsonElement): String? =
            when (value) {
                is JsonNull -> "null"
                is JsonPrimitive ->
                    if (value.isString) value.content.takeIf { it.length <= MaxLength && '\n' !in it }?.let { "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'" }
                    else value.content
                is JsonObject -> {
                    val inner = (value.values.singleOrNull() as? JsonPrimitive)?.content
                    when (BsonKind.of(value)) {
                        BsonKind.ObjectId -> inner?.let { "ObjectId('$it')" }
                        BsonKind.Date -> inner?.let { "ISODate('$it')" }
                        BsonKind.Int64, BsonKind.Int32, BsonKind.Double -> inner
                        BsonKind.Decimal -> inner?.let { "NumberDecimal('$it')" }
                        else -> null
                    }
                }
                is JsonArray -> null
            }
    }
}

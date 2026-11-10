package dev.tablegrip.app.grid

import dev.tablegrip.core.i18n.tr
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** One step into a JSON value: an object's key or an array's index. */
sealed interface JsonStep {
    data class Key(val name: String) : JsonStep

    data class Index(val index: Int) : JsonStep
}

typealias JsonPath = List<JsonStep>

/** The kinds of value a tree node can be switched to. */
enum class JsonValueType(val label: String) {
    String(tr("String")),
    Number(tr("Number")),
    Boolean(tr("Boolean")),
    Null(tr("Null")),
    Object(tr("Object")),
    Array(tr("Array"));

    companion object {
        fun of(element: JsonElement): JsonValueType =
            when (element) {
                is JsonObject -> Object
                is JsonArray -> Array
                is JsonNull -> Null
                is JsonPrimitive -> if (element.isString) String else if (element.content == "true" || element.content == "false") Boolean else Number
            }
    }
}

/**
 * How the original text was laid out, so an edited value is written back the same way: [indent] null is one line;
 * [lead] and [trail] are the whitespace around the value, kept as it was.
 */
data class JsonLayout(val indent: String?, val lead: String = "", val trail: String = "")

/** A cell's text as the tree editor sees it: a value it can edit losslessly, or why it can't. */
sealed interface JsonTreeSource {
    data class Ok(val root: JsonElement, val layout: JsonLayout) : JsonTreeSource

    data class Unavailable(val reason: String) : JsonTreeSource
}

/**
 * Opens [text] for the tree editor only when writing the tree back can't change anything the user didn't touch: it
 * must be strict JSON with an object or array at the top, and no object may repeat a key (parsing keeps only the last
 * one, so the tree would silently drop the others).
 */
fun jsonTreeSource(text: String): JsonTreeSource {
    if (text.isBlank()) return JsonTreeSource.Unavailable(tr("The value is empty"))
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return JsonTreeSource.Unavailable(tr("The text isn't valid JSON"))
    if (root !is JsonObject && root !is JsonArray) return JsonTreeSource.Unavailable(tr("Only objects and arrays open as a tree"))
    if (!strictLiterals(root)) return JsonTreeSource.Unavailable(tr("The text isn't valid JSON"))
    if (hasLoneSurrogate(root)) return JsonTreeSource.Unavailable(tr("The text has an incomplete Unicode escape; edit it as text"))
    firstRepeatedKey(text)?.let { return JsonTreeSource.Unavailable(tr("The key \"{0}\" appears twice in one object; edit it as text", it)) }
    return JsonTreeSource.Ok(root, jsonLayout(text))
}

/** Text cells longer than this open as text even when they hold JSON, so a huge value can't stall the editor. */
const val MaxTextTreeLength = 1_000_000

/**
 * Whether a text cell's [text] opens in the tree: a JSON object or array the tree can write back losslessly. The cell
 * stays text: the tree only changes what it holds, never how it is checked or saved. [sources] caches the parse.
 */
fun textOpensAsTree(text: String, sources: JsonTreeSources? = null): Boolean {
    if (text.length > MaxTextTreeLength) return false
    val first = text.firstOrNull { !it.isWhitespace() }
    if (first != '{' && first != '[') return false
    return (sources?.of(text) ?: jsonTreeSource(text)) is JsonTreeSource.Ok
}

/** Remembers the last text opened for the tree, so asking again for the same text doesn't parse it again. */
class JsonTreeSources {
    private var text: String? = null
    private var source: JsonTreeSource? = null

    /** Records that [text] is [source]: the tree's own edits, which it already holds parsed. */
    fun put(text: String, source: JsonTreeSource) {
        this.text = text
        this.source = source
    }

    fun of(text: String): JsonTreeSource {
        if (text == this.text) return source!!
        return jsonTreeSource(text).also {
            this.text = text
            source = it
        }
    }
}

/** [root] as text in [layout], every number written with the digits it was read or typed with. */
@OptIn(ExperimentalSerializationApi::class)
fun encodeJson(root: JsonElement, layout: JsonLayout): String {
    val json =
        if (layout.indent == null) Json
        else
            Json {
                prettyPrint = true
                prettyPrintIndent = layout.indent
            }
    return layout.lead + json.encodeToString(JsonElement.serializer(), exactNumbers(root)) + layout.trail
}

/** One line; or pretty, indented like the first indented line (two spaces when none is). */
fun jsonLayout(text: String): JsonLayout {
    val trimmed = text.trim()
    val lead = text.takeWhile { it.isWhitespace() }
    val trail = if (trimmed.isEmpty()) "" else text.takeLastWhile { it.isWhitespace() }
    if ('\n' !in trimmed) return JsonLayout(null, lead, trail)
    val indent = trimmed.lineSequence().drop(1).map { line -> line.takeWhile { it == ' ' || it == '\t' } }.firstOrNull { it.isNotEmpty() }
    return JsonLayout(indent ?: "  ", lead, trail)
}

private val JsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

fun isJsonNumber(text: String): Boolean = JsonNumber.matches(text)

/** kotlinx reads bare words as literals; only `true`, `false`, `null` and real numbers are JSON. */
private fun strictLiterals(element: JsonElement): Boolean =
    when (element) {
        is JsonObject -> element.values.all(::strictLiterals)
        is JsonArray -> element.all(::strictLiterals)
        is JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" || isJsonNumber(element.content)
    }

/**
 * Whether a key or string holds half of a surrogate pair (`"\ud800"`): it decodes, but written back raw it is no
 * valid character, and a database encoding it as UTF-8 would replace it.
 */
private fun hasLoneSurrogate(element: JsonElement): Boolean =
    when (element) {
        is JsonObject -> element.any { (key, value) -> brokenUnicode(key) || hasLoneSurrogate(value) }
        is JsonArray -> element.any(::hasLoneSurrogate)
        is JsonPrimitive -> element.isString && brokenUnicode(element.content)
    }

private fun brokenUnicode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() -> if (i + 1 < text.length && text[i + 1].isLowSurrogate()) i++ else return true
            c.isLowSurrogate() -> return true
        }
        i++
    }
    return false
}

/** The first key some object in [text] (valid JSON) repeats, compared after unescaping; null when none does. */
internal fun firstRepeatedKey(text: String): String? {
    // One entry per open container: the keys seen so far for an object, null for an array.
    val open = ArrayDeque<MutableSet<String>?>()
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{' -> open.addLast(mutableSetOf())
            '[' -> open.addLast(null)
            '}', ']' -> open.removeLastOrNull()
            '"' -> {
                var end = i + 1
                while (end < text.length && text[end] != '"') end += if (text[end] == '\\') 2 else 1
                val keys = open.lastOrNull()
                var next = end + 1
                while (next < text.length && text[next].isWhitespace()) next++
                if (keys != null && next < text.length && text[next] == ':') {
                    // Only keys with escapes need decoding to compare; most are the text between the quotes.
                    val raw = text.substring(i + 1, end)
                    val key = if ('\\' in raw) Json.decodeFromString<String>(text.substring(i, end + 1)) else raw
                    if (!keys.add(key)) return key
                }
                i = end
            }
        }
        i++
    }
    return null
}

fun JsonElement.at(path: JsonPath): JsonElement? =
    path.fold(this as JsonElement?) { element, step ->
        when (step) {
            is JsonStep.Key -> (element as? JsonObject)?.get(step.name)
            is JsonStep.Index -> (element as? JsonArray)?.getOrNull(step.index)
        }
    }

/** This value with the node at [path] passed through [change]; everything else, key order included, stays as it was. */
private fun JsonElement.updated(path: JsonPath, change: (JsonElement) -> JsonElement): JsonElement {
    if (path.isEmpty()) return change(this)
    val step = path.first()
    val rest = path.drop(1)
    return when {
        step is JsonStep.Key && this is JsonObject -> {
            require(step.name in this) { tr("No key {0}", step.name) }
            JsonObject(mapValues { (key, value) -> if (key == step.name) value.updated(rest, change) else value })
        }
        step is JsonStep.Index && this is JsonArray -> {
            require(step.index in indices) { tr("No index {0}", step.index) }
            JsonArray(mapIndexed { index, value -> if (index == step.index) value.updated(rest, change) else value })
        }
        else -> throw IllegalArgumentException(tr("No such node"))
    }
}

fun JsonElement.withValue(path: JsonPath, value: JsonElement): JsonElement = updated(path) { value }

/** Renames the key at [path] in place, keeping its position; null when its object already has [newName]. */
fun JsonElement.withKeyRenamed(path: JsonPath, newName: String): JsonElement? {
    val step = path.lastOrNull() as? JsonStep.Key ?: return null
    if (step.name == newName) return this
    var clash = false
    val result =
        updated(path.dropLast(1)) { parent ->
            parent as JsonObject
            if (newName in parent) clash = true
            JsonObject(parent.entries.associate { (key, value) -> (if (key == step.name) newName else key) to value })
        }
    return if (clash) null else result
}

fun JsonElement.without(path: JsonPath): JsonElement {
    val step = path.last()
    return updated(path.dropLast(1)) { parent ->
        when {
            step is JsonStep.Key && parent is JsonObject -> JsonObject(parent - step.name)
            step is JsonStep.Index && parent is JsonArray -> JsonArray(parent.filterIndexed { index, _ -> index != step.index })
            else -> throw IllegalArgumentException(tr("No such node"))
        }
    }
}

/**
 * Adds an empty string to the container at [path]: at the end of an array, or under a fresh key (`key`, `key2`…) in an
 * object. Returns the new value and the added node's path.
 */
fun JsonElement.withChildAdded(path: JsonPath): Pair<JsonElement, JsonPath> {
    var added: JsonStep? = null
    val result =
        updated(path) { container ->
            when (container) {
                is JsonObject -> {
                    val key = generateSequence(1) { it + 1 }.map { if (it == 1) "key" else "key$it" }.first { it !in container }
                    added = JsonStep.Key(key)
                    JsonObject(container + (key to JsonPrimitive("")))
                }
                is JsonArray -> {
                    added = JsonStep.Index(container.size)
                    JsonArray(container + JsonPrimitive(""))
                }
                else -> throw IllegalArgumentException(tr("Not a container"))
            }
        }
    return result to path + added!!
}

/**
 * The value a leaf of [type] holds when the user types [input]: strings take it as typed (quotes and backslashes are
 * escaped on save), numbers must be JSON numbers and keep their exact digits. Null when [input] isn't one.
 */
fun leafValue(type: JsonValueType, input: String): JsonElement? =
    when (type) {
        JsonValueType.String -> JsonPrimitive(input)
        JsonValueType.Number -> input.trim().takeIf(::isJsonNumber)?.let { exactNumber(it) }
        JsonValueType.Boolean -> input.trim().toBooleanStrictOrNull()?.let(::JsonPrimitive)
        JsonValueType.Null -> JsonNull
        JsonValueType.Object -> JsonObject(emptyMap())
        JsonValueType.Array -> JsonArray(emptyList())
    }

/** [element] switched to [type], keeping what carries over: a number's digits as a string, a numeric string as a number. */
fun converted(element: JsonElement, type: JsonValueType): JsonElement {
    if (JsonValueType.of(element) == type) return element
    val text = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    return when (type) {
        JsonValueType.String -> JsonPrimitive(text ?: if (element is JsonNull) "" else encodeJson(element, JsonLayout(null)))
        JsonValueType.Number -> text?.trim()?.takeIf(::isJsonNumber)?.let { exactNumber(it) } ?: exactNumber("0")
        JsonValueType.Boolean -> JsonPrimitive(text?.trim() == "true")
        JsonValueType.Null -> JsonNull
        JsonValueType.Object -> JsonObject(emptyMap())
        JsonValueType.Array -> JsonArray(emptyList())
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun exactNumber(digits: String): JsonElement = JsonUnquotedLiteral(digits)

/** A row of the tree: the node at [path], [depth] levels in. */
data class JsonTreeRow(val path: JsonPath, val element: JsonElement) {
    val depth: Int
        get() = path.size

    val isContainer: Boolean
        get() = element is JsonObject || element is JsonArray
}

/** The rows of [root] with the containers in [collapsed] closed; the root itself isn't a row. */
fun jsonTreeRows(root: JsonElement, collapsed: Set<JsonPath>): List<JsonTreeRow> = buildList {
    fun visit(element: JsonElement, path: JsonPath) {
        if (path.isNotEmpty()) add(JsonTreeRow(path, element))
        if (path.isNotEmpty() && path in collapsed) return
        when (element) {
            is JsonObject -> element.forEach { (key, child) -> visit(child, path + JsonStep.Key(key)) }
            is JsonArray -> element.forEachIndexed { index, child -> visit(child, path + JsonStep.Index(index)) }
            else -> Unit
        }
    }
    visit(root, emptyList())
}

/** Containers [depth] or more levels in, to start a big value folded. */
fun jsonContainersFrom(root: JsonElement, depth: Int): Set<JsonPath> = buildSet {
    fun visit(element: JsonElement, path: JsonPath) {
        if (path.size >= depth && (element is JsonObject || element is JsonArray)) add(path)
        when (element) {
            is JsonObject -> element.forEach { (key, child) -> visit(child, path + JsonStep.Key(key)) }
            is JsonArray -> element.forEachIndexed { index, child -> visit(child, path + JsonStep.Index(index)) }
            else -> Unit
        }
    }
    visit(root, emptyList())
}

/** `$.address.city`, `$.tags[0]`; keys that aren't plain words are quoted: `$["first name"]`. */
fun jsonPathText(path: JsonPath): String =
    "$" +
        path.joinToString("") { step ->
            when (step) {
                is JsonStep.Index -> "[${step.index}]"
                is JsonStep.Key -> if (PlainKey.matches(step.name)) ".${step.name}" else "[${Json.encodeToString(String.serializer(), step.name)}]"
            }
        }

private val PlainKey = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

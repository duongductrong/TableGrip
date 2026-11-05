package dev.tablegrip.app.documents

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.core.completion.DocumentCompleter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** A condition's comparison, as the builder offers it; [symbol] is how it reads in the list. */
internal enum class FilterOperator(val symbol: String, val takesValue: Boolean = true) {
    Eq("="),
    Ne("≠"),
    Gt(">"),
    Gte("≥"),
    Lt("<"),
    Lte("≤"),
    In("in"),
    Nin("not in"),
    Contains("contains"),
    StartsWith("starts with"),
    EndsWith("ends with"),
    Regex("matches regex"),
    Exists("exists", takesValue = false),
    Missing("doesn't exist", takesValue = false),
    Size("array size");

    /** The operator `find()` writes it with; the text matches share `$regex`. */
    val key: String
        get() =
            when (this) {
                Eq -> "\$eq"
                Ne -> "\$ne"
                Gt -> "\$gt"
                Gte -> "\$gte"
                Lt -> "\$lt"
                Lte -> "\$lte"
                In -> "\$in"
                Nin -> "\$nin"
                Contains, StartsWith, EndsWith, Regex -> "\$regex"
                Exists, Missing -> "\$exists"
                Size -> "\$size"
            }

    /** Whether the value is a list, typed comma-separated. */
    val isList: Boolean
        get() = this == In || this == Nin

    /** Whether the value is text matched within the field, case-insensitively. */
    val isText: Boolean
        get() = this == Contains || this == StartsWith || this == EndsWith
}

/** One row of the builder's filter: [field] [operator] [value], left out of the query while not [enabled]. */
internal class FilterCondition(field: String, operator: FilterOperator = FilterOperator.Eq, value: String = "", enabled: Boolean = true) {
    var field by mutableStateOf(field)
    var operator by mutableStateOf(operator)
    val value = TextFieldState(value)
    var enabled by mutableStateOf(enabled)
}

internal class SortField(val field: String, ascending: Boolean = true) {
    var ascending by mutableStateOf(ascending)
}

internal class ProjectedField(val field: String, include: Boolean = true) {
    var include by mutableStateOf(include)
}

/**
 * A `find()` query put together from rows and chips rather than typed: conditions on fields, matched all or any, then
 * the fields to sort by and to show. [render] writes it as the shell-style text the filter bar's fields hold; [load]
 * reads such text back, as far as it fits the builder. Values are typed plainly and converted to their field's BSON
 * type ([kinds]), like a grid cell: `A` for a string field, `30` for a number, `65a…` for an ObjectId.
 */
internal class QueryBuilder {
    var matchAny by mutableStateOf(false)
    val conditions = mutableStateListOf<FilterCondition>()
    val sort = mutableStateListOf<SortField>()
    val projection = mutableStateListOf<ProjectedField>()

    /** The last query the builder and the text fields agreed on, so neither overwrites the other with the same thing. */
    var synced: Rendered? = null

    /** A condition just added, whose value field takes the focus. */
    var focus by mutableStateOf<FilterCondition?>(null)

    /** The text fields held something the builder can't show; the builder replaces it once it's changed. */
    var unreadable by mutableStateOf(false)

    data class Rendered(val filter: String, val sort: String, val projection: String, val problems: List<String> = emptyList()) {
        val query: Triple<String, String, String>
            get() = Triple(filter, sort, projection)
    }

    fun addCondition(field: String = "", operator: FilterOperator = FilterOperator.Eq): FilterCondition =
        FilterCondition(field, operator).also { conditions += it }

    /** Sorts by [field] too, or flips its direction when it already sorts. */
    fun addSort(field: String) {
        val existing = sort.firstOrNull { it.field == field }
        if (existing != null) existing.ascending = !existing.ascending else sort += SortField(field)
    }

    /** Shows [field] too; excluding it instead when the projection already excludes fields. */
    fun addProjection(field: String) {
        if (projection.any { it.field == field }) return
        val excluding = projection.isNotEmpty() && projection.filter { it.field != "_id" }.all { !it.include }
        projection += ProjectedField(field, include = !excluding)
    }

    fun clear() {
        matchAny = false
        conditions.clear()
        sort.clear()
        projection.clear()
    }

    // region Writing

    fun render(kinds: (String) -> BsonKind? = { null }): Rendered {
        val problems = mutableListOf<String>()
        val clauses =
            conditions.filter { it.enabled && it.field.isNotBlank() }.mapNotNull { condition ->
                expression(condition, kinds(condition.field)).fold(
                    onSuccess = { condition.field to it },
                    onFailure = {
                        problems += "${condition.field}: ${it.message}"
                        null
                    },
                )
            }
        val sortText = if (sort.isEmpty()) "{}" else sort.joinToString(", ", "{", "}") { "${key(it.field)}: ${if (it.ascending) 1 else -1}" }
        val projectionText = if (projection.isEmpty()) "{}" else projection.joinToString(", ", "{", "}") { "${key(it.field)}: ${if (it.include) 1 else 0}" }
        val shown = projection.filter { it.field != "_id" }
        if (shown.any { it.include } && shown.any { !it.include }) problems += "A projection either shows fields or hides them; only _id can be hidden among shown fields."
        return Rendered(filter(clauses), sortText, projectionText, problems)
    }

    /** Conditions on distinct fields join into one document; with any, or a field taken twice, they're spelled out. */
    private fun filter(clauses: List<Pair<String, Expression>>): String {
        if (clauses.isEmpty()) return "{}"
        if (clauses.size == 1) return "{${clause(clauses.single())}}"
        if (matchAny) return clauses.joinToString(", ", "{\$or: [", "]}") { "{${clause(it)}}" }
        val byField = clauses.groupBy({ it.first }, { it.second })
        val merged =
            byField.map { (field, expressions) ->
                if (expressions.size == 1) return@map "${key(field)}: ${expressions.single().text}"
                if (expressions.any { it.operators == null }) return@map null
                val operators = expressions.map { it.operators!! }
                if (operators.flatMap { it.keys }.let { it.size != it.toSet().size }) return@map null
                "${key(field)}: " + operators.flatMap { it.entries }.joinToString(", ", "{", "}") { (k, v) -> "$k: $v" }
            }
        return if (merged.all { it != null }) merged.joinToString(", ", "{", "}")
        else clauses.joinToString(", ", "{\$and: [", "]}") { "{${clause(it)}}" }
    }

    private fun clause(clause: Pair<String, Expression>) = "${key(clause.first)}: ${clause.second.text}"

    /** A field's condition: a plain value for equality, or its [operators] in a document. */
    private class Expression(val text: String, val operators: Map<String, String>?)

    private fun operators(vararg entries: Pair<String, String>) = Expression(entries.joinToString(", ", "{", "}") { (k, v) -> "$k: $v" }, mapOf(*entries))

    private fun expression(condition: FilterCondition, kind: BsonKind?): Result<Expression> = runCatching {
        val operator = condition.operator
        val text = condition.value.text.toString()
        when {
            operator == FilterOperator.Exists -> operators("\$exists" to "true")
            operator == FilterOperator.Missing -> operators("\$exists" to "false")
            operator.isText -> {
                if (text.isEmpty()) throw IllegalArgumentException("type the text to look for")
                val escaped = RegexSpecial.replace(text) { "\\" + it.value }
                val pattern = when (operator) {
                    FilterOperator.StartsWith -> "^$escaped"
                    FilterOperator.EndsWith -> "$escaped\$"
                    else -> escaped
                }
                operators("\$regex" to shellString(pattern), "\$options" to "'i'")
            }
            operator == FilterOperator.Regex -> {
                if (text.isEmpty()) throw IllegalArgumentException("type a pattern")
                runCatching { Regex(text) }.onFailure { throw IllegalArgumentException("isn't a valid pattern") }
                operators("\$regex" to shellString(text))
            }
            operator == FilterOperator.Size -> {
                val size = text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("an array size is a whole number")
                operators("\$size" to size.toString())
            }
            operator.isList -> {
                // An array field matches its elements, so its values are typed as they come.
                val elementKind = kind?.takeIf { it != BsonKind.Array }
                operators(operator.key to splitList(text).joinToString(", ", "[", "]") { literal(elementKind, it) })
            }
            operator == FilterOperator.Eq -> literal(kind?.takeIf { it != BsonKind.Array }, text).let { Expression(it, null) }
            else -> operators(operator.key to literal(kind?.takeIf { it != BsonKind.Array }, text))
        }
    }

    // endregion

    // region Reading

    /**
     * Shows a query in the builder; false, leaving it empty and [unreadable], when it's more than the builder can show:
     * nested `$or`s, `$elemMatch`, `$expr`, sort by `$meta`…
     */
    fun load(filter: String, sort: String, projection: String, kinds: (String) -> BsonKind? = { null }): Boolean {
        clear()
        val ok = runCatching { loadFilter(Reader(filter).document(), kinds) && loadSort(Reader(sort).document()) && loadProjection(Reader(projection).document()) }.getOrDefault(false)
        if (!ok) clear()
        unreadable = !ok
        // Even an empty builder agrees with what it couldn't read until it's changed, so that isn't overwritten yet.
        synced = render(kinds)
        return ok
    }

    private fun loadFilter(document: Node.Document, kinds: (String) -> BsonKind?): Boolean {
        val entries = document.entries
        val single = entries.singleOrNull()
        if (single != null && (single.first == "\$and" || single.first == "\$or")) {
            val list = single.second as? Node.Items ?: return false
            matchAny = single.first == "\$or"
            for (element in list.items) {
                val clause = element as? Node.Document ?: return false
                // Under $or, each element is one alternative: several fields in it would have to all match.
                if (matchAny && clause.entries.size != 1) return false
                if (!loadClauses(clause.entries, kinds)) return false
            }
            return true
        }
        return loadClauses(entries, kinds)
    }

    private fun loadClauses(entries: List<Pair<String, Node>>, kinds: (String) -> BsonKind?): Boolean {
        for ((field, value) in entries) {
            if (field.startsWith("$")) return false
            val kind = kinds(field)
            val operators = (value as? Node.Document)?.takeIf { it.entries.isNotEmpty() && it.entries.all { (k, _) -> k.startsWith("$") } && !it.isWrapped }
            if (operators == null) {
                conditions += FilterCondition(field, FilterOperator.Eq, plain(value, kind) ?: return false)
                continue
            }
            val map = operators.entries.toMap()
            if ("\$regex" in map) {
                val pattern = (map["\$regex"] as? Node.Value)?.string ?: return false
                val options = map["\$options"]?.let { (it as? Node.Value)?.string ?: return false }.orEmpty()
                if (map.keys.any { it != "\$regex" && it != "\$options" }) return false
                conditions += textCondition(field, pattern, options) ?: return false
                continue
            }
            for ((key, operand) in operators.entries) {
                val condition =
                    when (key) {
                        "\$exists" -> FilterCondition(field, if ((operand as? Node.Value)?.raw == "false") FilterOperator.Missing else FilterOperator.Exists)
                        "\$size" -> FilterCondition(field, FilterOperator.Size, (operand as? Node.Value)?.raw ?: return false)
                        "\$in", "\$nin" -> {
                            val items = (operand as? Node.Items)?.items ?: return false
                            val elementKind = kind?.takeIf { it != BsonKind.Array }
                            val values = items.map { plain(it, elementKind, inList = true) ?: return false }
                            FilterCondition(field, if (key == "\$in") FilterOperator.In else FilterOperator.Nin, values.joinToString(", "))
                        }
                        else -> {
                            val operator = FilterOperator.entries.firstOrNull { it.key == key && !it.isText && it != FilterOperator.Regex } ?: return false
                            FilterCondition(field, operator, plain(operand, kind?.takeIf { it != BsonKind.Array }) ?: return false)
                        }
                    }
                conditions += condition
            }
        }
        return true
    }

    /** `$regex` as a text match when it is one (escaped text, case-insensitive), else as a pattern. */
    private fun textCondition(field: String, pattern: String, options: String): FilterCondition? {
        if (options == "i") {
            val anchoredStart = pattern.startsWith("^")
            val anchoredEnd = pattern.endsWith("$") && !pattern.endsWith("\\$")
            val body = pattern.removePrefix("^").let { if (anchoredEnd) it.dropLast(1) else it }
            val text = unescapeRegex(body)
            if (text != null && !(anchoredStart && anchoredEnd)) {
                val operator = when {
                    anchoredStart -> FilterOperator.StartsWith
                    anchoredEnd -> FilterOperator.EndsWith
                    else -> FilterOperator.Contains
                }
                return FilterCondition(field, operator, text)
            }
            return null
        }
        return if (options.isEmpty()) FilterCondition(field, FilterOperator.Regex, pattern) else null
    }

    private fun loadSort(document: Node.Document): Boolean {
        for ((field, value) in document.entries) {
            val direction = (value as? Node.Value)?.raw ?: return false
            sort += SortField(field, ascending = when (direction) { "1" -> true; "-1" -> false; else -> return false })
        }
        return true
    }

    private fun loadProjection(document: Node.Document): Boolean {
        for ((field, value) in document.entries) {
            val raw = (value as? Node.Value)?.raw ?: return false
            projection += ProjectedField(field, include = when (raw) { "1", "true" -> true; "0", "false" -> false; else -> return false })
        }
        return true
    }

    /**
     * A value as the builder's field shows it: strings unquoted (kept quoted where unquoted text would read as another
     * type), ObjectIds and dates as their shell constructors or ISO text; null when it isn't a plain value.
     */
    private fun plain(node: Node, kind: BsonKind?, inList: Boolean = false): String? {
        val text =
            when (node) {
                is Node.Value -> {
                    val string = node.string
                    when {
                        string == null -> node.raw
                        kind == BsonKind.String && !(inList && (',' in string || string.trim() != string)) -> string
                        else -> shellString(string)
                    }
                }
                is Node.Document -> {
                    val (key, inner) = node.entries.singleOrNull() ?: return null
                    val value = (inner as? Node.Value)?.string ?: (inner as? Node.Value)?.raw ?: return null
                    when (key) {
                        "\$oid" -> if (kind == BsonKind.ObjectId) value else "ObjectId('$value')"
                        "\$date" -> if (kind == BsonKind.Date) value else "ISODate('$value')"
                        "\$numberLong", "\$numberInt", "\$numberDouble", "\$numberDecimal" -> value
                        else -> return null
                    }
                }
                is Node.Items -> return null
            }
        return text
    }

    // endregion

    companion object {
        private val RegexSpecial = Regex("""[\\^$.|?*+()\[\]{}/]""")

        private fun key(field: String) = DocumentCompleter.quoteKey(field)

        internal fun shellString(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

        /** Text a regex matches literally, or null when it uses any of a pattern's special characters. */
        private fun unescapeRegex(pattern: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' && i + 1 < pattern.length && RegexSpecial.matches(pattern[i + 1].toString()) -> {
                        out.append(pattern[i + 1])
                        i += 2
                    }
                    RegexSpecial.matches(c.toString()) -> return null
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** Typed text as a literal of [kind], as a grid cell converts it; text that doesn't fit stops the query. */
        internal fun literal(kind: BsonKind?, text: String): String {
            val trimmed = text.trim()
            // Shell values typed as such go as they are.
            if (trimmed == "null" || ShellValue.matches(trimmed)) return trimmed
            val effective = kind?.takeIf { it != BsonKind.Null && it != BsonKind.Object && it != BsonKind.Other }
            return when (val converted = convert(effective, text)) {
                is Converted.Ok -> shell(converted.literal)
                is Converted.Invalid -> throw IllegalArgumentException(converted.message)
            }
        }

        /** Extended JSON as the shell writes it, the way the filter bar reads best: `'A'`, `ObjectId('…')`, `ISODate('…')`. */
        private fun shell(literal: String): String {
            val element = runCatching { Json.parseToJsonElement(literal) }.getOrNull() ?: return literal
            if (element is JsonPrimitive) return if (element.isString) shellString(element.content) else literal
            val (key, inner) = (element as? JsonObject)?.entries?.singleOrNull() ?: return literal
            val value = (inner as? JsonPrimitive)?.content ?: return literal
            return when (key) {
                "\$oid" -> "ObjectId('$value')"
                "\$date" -> "ISODate('$value')"
                "\$numberLong" -> "NumberLong('$value')"
                "\$numberDecimal" -> "NumberDecimal('$value')"
                else -> literal
            }
        }

        private val ShellValue = Regex("""^(?:'.*'|".*"|(?:ObjectId|ISODate|NumberLong|NumberInt|NumberDecimal|new Date)\(.*\)|/.*/[a-z]*)$""")

        /** A comma-separated list, where commas inside quotes or brackets don't split. */
        internal fun splitList(text: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var depth = 0
            for (c in text) {
                when {
                    quote != null -> {
                        if (c == quote) quote = null
                        current.append(c)
                    }
                    c == '\'' || c == '"' -> {
                        quote = c
                        current.append(c)
                    }
                    c in "([{" -> {
                        depth++
                        current.append(c)
                    }
                    c in ")]}" -> {
                        depth--
                        current.append(c)
                    }
                    c == ',' && depth == 0 -> {
                        items += current.toString().trim()
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotBlank()) items += current.toString().trim()
            return items.filter { it.isNotEmpty() }
        }
    }
}

/** Shell-style Extended JSON, read just far enough to tell documents, lists and values apart. */
private sealed interface Node {
    class Document(val entries: List<Pair<String, Node>>) : Node {
        /** An Extended JSON value such as `{$oid: …}`, not a condition. */
        val isWrapped: Boolean
            get() = entries.singleOrNull()?.first in Wrappers
    }

    class Items(val items: List<Node>) : Node

    /** A value as written: `'A'`, `30`, `true`, `ObjectId('…')`. */
    class Value(val raw: String) : Node {
        /** The string, when it's a quoted one. */
        val string: String?
            get() =
                if (raw.length >= 2 && (raw.first() == '\'' || raw.first() == '"') && raw.last() == raw.first()) {
                    if (raw.first() == '"') runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
                    else unescapeSingle(raw.substring(1, raw.length - 1))
                } else null
    }

    companion object {
        val Wrappers = setOf("\$oid", "\$date", "\$numberLong", "\$numberInt", "\$numberDouble", "\$numberDecimal")

        private fun unescapeSingle(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                if (text[i] == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1])
                    i += 2
                } else out.append(text[i++])
            }
            return out.toString()
        }
    }
}

private class Reader(private val text: String) {
    private var i = 0

    /** The whole text as one document; blank is `{}`, and braces may be left out, as in the filter bar. */
    fun document(): Node.Document {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Node.Document(emptyList())
        if (!trimmed.startsWith("{")) return Reader("{$trimmed}").document()
        val node = value() as? Node.Document ?: fail()
        space()
        if (i < text.length) fail()
        return node
    }

    private fun fail(): Nothing = throw IllegalArgumentException("Can't read the query at ${text.drop(i).take(20)}")

    private fun space() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun value(): Node {
        space()
        if (i >= text.length) fail()
        return when (text[i]) {
            '{' -> {
                i++
                val entries = mutableListOf<Pair<String, Node>>()
                space()
                while (i < text.length && text[i] != '}') {
                    val key = key()
                    space()
                    if (i >= text.length || text[i] != ':') fail()
                    i++
                    entries += key to value()
                    space()
                    if (i < text.length && text[i] == ',') {
                        i++
                        space()
                    } else break
                }
                if (i >= text.length || text[i] != '}') fail()
                i++
                Node.Document(entries)
            }
            '[' -> {
                i++
                val items = mutableListOf<Node>()
                space()
                while (i < text.length && text[i] != ']') {
                    items += value()
                    space()
                    if (i < text.length && text[i] == ',') i++ else break
                    space()
                }
                if (i >= text.length || text[i] != ']') fail()
                i++
                Node.Items(items)
            }
            else -> Node.Value(scalar())
        }
    }

    private fun key(): String {
        space()
        if (i < text.length && (text[i] == '\'' || text[i] == '"')) {
            return (Node.Value(quoted()).string ?: fail())
        }
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_$.")) i++
        if (start == i) fail()
        return text.substring(start, i)
    }

    private fun quoted(): String {
        val quote = text[i]
        val start = i
        i++
        while (i < text.length && text[i] != quote) i += if (text[i] == '\\') 2 else 1
        if (i >= text.length) fail()
        i++
        return text.substring(start, i)
    }

    /** A value up to the next comma or closing bracket outside quotes and parentheses. */
    private fun scalar(): String {
        if (text[i] == '\'' || text[i] == '"') return quoted()
        val start = i
        var parens = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' || c == '"' -> {
                    quoted()
                    continue
                }
                c == '(' -> parens++
                c == ')' -> parens--
                parens == 0 && (c == ',' || c == '}' || c == ']') -> break
            }
            i++
        }
        return text.substring(start, i).trim().ifEmpty { fail() }
    }
}

/** The BSON type a field holds, from the types seen in it, when there's just one besides null. */
internal fun kindOf(types: List<String>): BsonKind? =
    types.filter { it != BsonKind.Null.label }.singleOrNull()?.let { label -> BsonKind.entries.firstOrNull { it.label == label } }

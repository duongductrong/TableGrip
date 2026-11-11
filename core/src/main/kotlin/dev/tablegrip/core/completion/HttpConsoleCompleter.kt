package dev.tablegrip.core.completion

/**
 * The shape of a JSON body, for completing its keys and values. [keys] are the keys an object takes; [fieldKeys], when
 * set, says the object's keys may also be the container's fields, each with that value (a `match` query, a document);
 * [anyKey] that they're names the user makes up (aggregations), each with that value. An array's elements are [element],
 * or when that's null the node itself (`"must": [{…}]` takes queries just as `"must": {…}` does). [values] are literals
 * the value may be, and [fieldValues] that it may be a field's name. [shape] is what a key inserts after its colon.
 */
class JsonNode(
    val shape: Shape = Shape.Scalar,
    val keys: MutableMap<String, JsonNode> = LinkedHashMap(),
    var fieldKeys: JsonNode? = null,
    var anyKey: JsonNode? = null,
    val element: JsonNode? = null,
    val values: List<String> = emptyList(),
    val fieldValues: Boolean = false,
    val containerValues: Boolean = false,
) {
    enum class Shape { Scalar, Object, Array }

    fun child(key: String): JsonNode? = keys[key] ?: fieldKeys ?: anyKey

    val elementNode: JsonNode
        get() = element ?: this
}

/**
 * One endpoint an HTTP console knows: its [methods] and [path], whose `{placeholders}` are free, except that the ones in
 * [HttpEndpoints.containerPlaceholders] take container names. [body] describes its JSON body, if it takes one.
 */
data class Endpoint(val methods: List<String>, val path: String, val body: JsonNode? = null) {
    val segments: List<String> = path.trim('/').split('/').filter { it.isNotEmpty() }
}

/**
 * Completion for the request consoles (Elasticsearch's Dev Tools and the like): the method, then the path segment by
 * segment from the engine's known endpoints (with index, database or collection names where a path takes one), then the
 * body's keys and values where the endpoint's body is known: the query DSL, a Mango selector, a search's parameters.
 */
class HttpConsoleCompleter(private val endpoints: HttpEndpoints) : StatementCompleter {
    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val request = requestLine(text, at)
        if (request == null || endpoints.other != null && text.trimStart().startsWith("{")) {
            // Text that isn't a request: what the engine takes besides (Weaviate's GraphQL), or a method being typed.
            endpoints.other?.let { other -> return other(text, at, catalog) }
            return CompletionResult.empty(at)
        }
        val line = text.substring(request, text.indexOf('\n', request).let { if (it < 0) text.length else it })
        if (request == lineStart) {
            val methodEnd = request + line.indexOfFirst { it.isWhitespace() }.let { if (it < 0) line.length else it }
            if (at <= methodEnd) return methods(text, request, at, methodEnd, isRead)
            return path(text, at, line.substring(0, methodEnd - request).uppercase(), methodEnd, line, request, catalog, isRead)
        }
        val method = line.substringBefore(' ').uppercase()
        val path = line.substringAfter(' ', "").trim().substringBefore('?').let { if (it.startsWith('/')) it else "/$it" }
        val (endpoint, values) = endpoints.match(method, path) ?: return CompletionResult.empty(at)
        val body = endpoint.body ?: return CompletionResult.empty(at)
        val container = endpoints.containerPlaceholders.firstNotNullOfOrNull { values[it] }
        val bodyStart = request + line.length + 1
        return JsonBodyCompleter.complete(text, bodyStart, at, body, container?.let { catalog.fields(listOf(it)) }.orEmpty(), catalog.containers(emptyList()).orEmpty())
    }

    /** The start of the request line the caret's request begins with: the nearest line at or above it that starts with a word. */
    private fun requestLine(text: String, caret: Int): Int? {
        var start = text.lastIndexOf('\n', caret - 1) + 1
        // A method being typed on a line of its own, outside any body.
        val end = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        if (text.substring(start, end).all { it.isLetter() } && depth(text, start) == 0) return start
        while (true) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            val word = line.takeWhile { it.isLetter() }
            if (word.isNotEmpty() && line.first().isLetter() && word.lowercase() !in JsonWords && (word.length == line.length || line[word.length].isWhitespace())) return start
            if (start == 0) return null
            start = text.lastIndexOf('\n', start - 2) + 1
        }
    }

    /** How many JSON objects and arrays are open at [end], strings skipped. */
    private fun depth(text: String, end: Int): Int {
        var depth = 0
        var i = 0
        while (i < end) {
            when (text[i]) {
                '"' -> {
                    i++
                    while (i < end && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                }
                '{', '[' -> depth++
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
            i++
        }
        return depth
    }

    private fun methods(text: String, start: Int, caret: Int, end: Int, isRead: ((String) -> Boolean)?): CompletionResult {
        val prefix = text.substring(start, caret)
        val methods = endpoints.methods.filter { m -> isRead == null || endpoints.list.any { it.methods.contains(m) && isRead("$m ${concrete(it.path)}") } }
        return CompletionResult(start, end, prefix, Completions.rank(Completions.keywords(methods), prefix))
    }

    private fun path(text: String, caret: Int, method: String, methodEnd: Int, line: String, lineStart: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val pathStart = (methodEnd until lineStart + line.length).firstOrNull { !text[it].isWhitespace() } ?: caret
        if (caret < pathStart) return CompletionResult.empty(caret)
        val typed = text.substring(pathStart, caret)
        if ('?' in typed || ' ' in typed) return CompletionResult.empty(caret)
        val slash = typed.lastIndexOf('/')
        val segmentStart = pathStart + slash + 1
        val segmentEnd = (segmentStart until lineStart + line.length).firstOrNull { text[it] == '/' || text[it] == '?' || text[it].isWhitespace() } ?: (lineStart + line.length)
        val done = typed.substring(0, slash.coerceAtLeast(0)).split('/').filter { it.isNotEmpty() }
        val prefix = typed.substring(slash + 1)
        val lead = if (slash < 0) "/" else ""
        val knownMethod = method in endpoints.methods
        val candidates =
            endpoints.list.filter { e ->
                e.segments.size > done.size &&
                    done.indices.all { i -> e.segments[i].let { it == done[i] || isPlaceholder(it) } } &&
                    (!knownMethod || method in e.methods) &&
                    (isRead == null || (if (knownMethod) listOf(method) else e.methods).any { isRead("$it ${concrete(e.path)}") })
            }
        val items = ArrayList<CompletionItem>()
        for ((segment, group) in candidates.groupBy { it.segments[done.size] }) {
            val continues = group.all { it.segments.size > done.size + 1 }
            val slashAfter = if (continues) "/" else ""
            if (isPlaceholder(segment)) {
                if (segment.trim('{', '}') !in endpoints.containerPlaceholders) continue
                catalog.containers(emptyList()).orEmpty().forEach { name ->
                    items += CompletionItem(name, lead + encode(name) + slashAfter, CompletionKind.Table, segment.trim('{', '}'))
                }
            } else {
                items += CompletionItem(segment, lead + segment + slashAfter, CompletionKind.Keyword, group.first().let { it.methods.joinToString("/") + " " + it.path })
            }
        }
        // Literal segments first: `_search` before the indices when both fit.
        val sorted = items.sortedBy { if (it.kind == CompletionKind.Keyword) 0 else 1 }
        return CompletionResult(segmentStart, segmentEnd, prefix, Completions.rank(sorted, prefix))
    }

    companion object {
        private val JsonWords = setOf("true", "false", "null")

        internal fun isPlaceholder(segment: String) = segment.startsWith("{") && segment.endsWith("}")

        /** [path] with each placeholder filled in, for asking the engine whether a request to it reads. */
        internal fun concrete(path: String): String = path.split('/').joinToString("/") { if (isPlaceholder(it)) "x" else it }

        /** A name as one path segment. */
        internal fun encode(name: String): String =
            buildString { name.forEach { c -> if (c.isLetterOrDigit() || c in "-._~*,:+@!$") append(c) else c.toString().toByteArray().forEach { append("%%%02X".format(it)) } } }
    }
}

/** Completes a request's JSON body by the [JsonNode] it takes. */
internal object JsonBodyCompleter {
    private class Frame(val isObject: Boolean, val node: JsonNode?) {
        var key: String? = null
        var expectingKey = isObject
    }

    fun complete(text: String, bodyStart: Int, caret: Int, root: JsonNode, fields: List<String>, containers: List<String>): CompletionResult {
        val stack = ArrayList<Frame>()
        var i = bodyStart.coerceAtMost(caret)
        var token: Int? = null // where a key or value being typed starts
        fun childNode(): JsonNode? {
            val top = stack.lastOrNull() ?: return root
            val node = top.node ?: return null
            return if (top.isObject) top.key?.let { node.child(it) } else node.elementNode
        }
        while (i < caret) {
            val c = text[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < caret && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    if (j >= caret) {
                        token = i
                        break
                    }
                    val top = stack.lastOrNull()
                    if (top != null && top.isObject && top.expectingKey) top.key = text.substring(i + 1, j)
                    i = j + 1
                    continue
                }
                c == '{' || c == '[' -> stack += Frame(c == '{', childNode())
                c == '}' || c == ']' -> stack.removeLastOrNull()
                c == ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = true.also { _ -> it.key = null } }
                c == ':' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = false }
                Completions.isWordChar(c) || c == '-' || c == '.' -> {
                    val end = Completions.wordEnd(text, i) { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
                    if (end >= caret) {
                        token = i
                        break
                    }
                    i = end
                    continue
                }
            }
            i++
        }
        val top = stack.lastOrNull() ?: return CompletionResult.empty(caret)
        val start = token ?: caret
        val quoted = token != null && text[start] == '"'
        var end = Completions.wordEnd(text, caret) { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '$' }
        if (quoted && text.getOrNull(end) == '"') end++
        val prefix = text.substring(start, caret)
        val typed = prefix.trimStart('"')
        if (top.isObject && top.expectingKey) {
            val node = top.node ?: return CompletionResult.empty(caret)
            val hasColon = text.substring(end).trimStart(' ', '\t').startsWith(":")
            val items =
                node.keys.map { (k, v) -> key(k, v, hasColon, CompletionKind.Keyword) } +
                    (node.fieldKeys?.let { v -> fields.map { key(it, v, hasColon, CompletionKind.Column) } } ?: emptyList())
            return CompletionResult(start, end, prefix, Completions.rank(items, typed))
        }
        val node = (if (top.isObject) top.key?.let { top.node?.child(it) } else top.node?.elementNode) ?: return CompletionResult.empty(caret)
        val items =
            node.values.map { CompletionItem(it.trim('"'), it, CompletionKind.Value) } +
                (if (node.fieldValues) fields.map { CompletionItem(it, "\"$it\"", CompletionKind.Column) } else emptyList()) +
                (if (node.containerValues) containers.map { CompletionItem(it, "\"$it\"", CompletionKind.Table) } else emptyList())
        return CompletionResult(start, end, prefix, Completions.rank(items, typed))
    }

    private fun key(name: String, value: JsonNode, hasColon: Boolean, kind: CompletionKind): CompletionItem {
        val quoted = "\"" + name.replace("\"", "\\\"") + "\""
        if (hasColon) return CompletionItem(name, quoted, kind)
        return when (value.shape) {
            JsonNode.Shape.Object -> CompletionItem(name, "$quoted: {}", kind, "{…}", quoted.length + 3)
            JsonNode.Shape.Array -> CompletionItem(name, "$quoted: []", kind, "[…]", quoted.length + 3)
            JsonNode.Shape.Scalar -> CompletionItem(name, "$quoted: ", kind)
        }
    }
}

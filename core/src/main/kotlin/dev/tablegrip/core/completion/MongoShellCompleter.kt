package dev.tablegrip.core.completion

/**
 * What the Mongo shell completer knows of the server. A null list isn't loaded yet: the host loads it and completes
 * again when it arrives.
 */
interface MongoCatalog {
    fun databases(): List<String>?

    /** The current database's collections. */
    fun collections(): List<String>?

    /** The fields seen in [collection]'s documents. */
    fun fields(collection: String): List<DocumentField>?
}

/**
 * Completion for the Mongo shell console (`db.orders.find({status: 'A'}).sort({total: -1})`): `show`/`use` targets,
 * collections and database methods after `db.`, the methods a collection or a cursor has, and inside a method's
 * arguments what [DocumentCompleter] offers for that argument — a filter, a projection, an update, a pipeline, a
 * document to insert, a database command. With [readOnly], methods that write aren't offered.
 */
object MongoShellCompleter {
    fun complete(text: String, caret: Int, catalog: MongoCatalog, readOnly: Boolean = false): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val before = text.substring(0, at)
        val lead = before.length - before.trimStart().length
        val line = before.substring(lead)

        Regex("""^show\s+(\w*)$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { match ->
            val typed = match.groupValues[1]
            return words(text, at, typed, ShowTargets)
        }
        Regex("""^use\s+(\S*)$""", RegexOption.IGNORE_CASE).matchEntire(line)?.let { match ->
            val typed = match.groupValues[1]
            val items = catalog.databases().orEmpty().map { CompletionItem(it, it, CompletionKind.Schema, "database") }
            return words(text, at, typed, items)
        }
        Regex("""^[A-Za-z]*$""").matchEntire(line)?.let {
            return words(text, at, line, TopLevel)
        }
        if (!Regex("""^db\s*\.""").containsMatchIn(line)) return CompletionResult.empty(at)
        return chain(text, at, lead + 2, catalog, readOnly)
    }

    /**
     * The signature of what the caret is in or right after — `find(filter, projection)` — with a short summary, for a
     * hint next to the console; null when there's nothing to say.
     */
    fun hint(text: String, caret: Int): String? {
        val at = caret.coerceIn(0, text.length)
        val before = text.substring(0, at)
        val lead = before.length - before.trimStart().length
        if (!Regex("""^db\s*\.""").containsMatchIn(before.substring(lead))) return null
        val parsed = parse(text, at, lead + 2) ?: return null
        if (parsed.argument == null) return null
        val method = parsed.segments.last()
        val owner = owner(text, parsed.segments.dropLast(1))
        val spec = methodsFor(owner).firstOrNull { it.name == method.name } ?: return null
        return "${spec.name}(${spec.parameters}) — ${spec.summary}"
    }

    // region The chain

    private class Call(val open: Int, val close: Int?)

    private class Segment(val name: String, val start: Int, val end: Int, val call: Call?)

    /** The argument the caret is in: its method is the last segment. */
    private class Argument(val index: Int, val start: Int, val end: Int)

    private class Parsed(val segments: List<Segment>, val argument: Argument?, val member: Segment?)

    /** Reads `.name(args)` segments after `db` up to the caret. */
    private fun parse(text: String, caret: Int, from: Int): Parsed? {
        val segments = mutableListOf<Segment>()
        var i = from
        while (true) {
            while (i < caret && text[i].isWhitespace()) i++
            if (i >= caret) return null
            if (text[i] != '.') return null
            i++
            while (i < caret && text[i].isWhitespace()) i++
            val nameStart = i
            var j = i
            while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
            if (caret <= j) {
                // The caret is in (or right after) this name: a member is being typed.
                return Parsed(segments, null, Segment(text.substring(nameStart, caret), nameStart, j, null))
            }
            val name = text.substring(nameStart, j)
            if (name.isEmpty()) return null
            var k = j
            while (k < caret && text[k].isWhitespace()) k++
            if (k < text.length && text[k] == '(' && k < caret) {
                val close = closing(text, k)
                if (close == null || caret <= close) {
                    segments += Segment(name, nameStart, j, Call(k, close))
                    return Parsed(segments, argument(text, k, close, caret), null)
                }
                segments += Segment(name, nameStart, j, Call(k, close))
                i = close + 1
            } else {
                segments += Segment(name, nameStart, j, null)
                i = k
            }
        }
    }

    private fun chain(text: String, caret: Int, from: Int, catalog: MongoCatalog, readOnly: Boolean): CompletionResult {
        val parsed = parse(text, caret, from) ?: return CompletionResult.empty(caret)
        parsed.member?.let { member -> return member(text, caret, parsed.segments, member, catalog, readOnly) }
        val argument = parsed.argument ?: return CompletionResult.empty(caret)
        val method = parsed.segments.last()
        val owner = owner(text, parsed.segments.dropLast(1))
        return argument(text, caret, owner, method.name, argument, catalog)
    }

    /** Whose method the next segment is. */
    private sealed interface Owner {
        data object Database : Owner

        data class Collection(val name: String) : Owner

        /** After `find()`: `.sort()`, `.limit()`… */
        data class Cursor(val collection: String, val used: Set<String>) : Owner

        data object Unknown : Owner
    }

    private fun owner(segments: List<Segment>): Owner {
        if (segments.isEmpty()) return Owner.Database
        val first = segments.first()
        val collection =
            when {
                first.name == "getCollection" && first.call != null -> null // resolved below
                first.call == null -> first.name
                else -> return Owner.Unknown
            }
        val name = collection ?: return Owner.Unknown
        val rest = segments.drop(1)
        if (rest.isEmpty()) return Owner.Collection(name)
        return if (rest.first().name == "find" && rest.all { it.call != null }) Owner.Cursor(name, rest.drop(1).map { it.name }.toSet()) else Owner.Unknown
    }

    /** [owner], with `db.getCollection('name')` resolved from the text. */
    private fun owner(text: String, segments: List<Segment>): Owner {
        val first = segments.firstOrNull() ?: return Owner.Database
        if (first.name != "getCollection" || first.call?.close == null) return owner(segments)
        val name = unquote(text.substring(first.call.open + 1, first.call.close).trim()) ?: return Owner.Unknown
        val renamed = listOf(Segment(name, first.start, first.end, null)) + segments.drop(1)
        return owner(renamed)
    }

    private fun member(text: String, caret: Int, segments: List<Segment>, member: Segment, catalog: MongoCatalog, readOnly: Boolean): CompletionResult {
        val typed = member.name
        val hasCall = text.substring(member.end).trimStart().startsWith("(")
        val items =
            when (val owner = owner(text, segments)) {
                Owner.Database -> {
                    val collections =
                        catalog.collections().orEmpty().map { name ->
                            if (Identifier.matches(name) && DatabaseMethods.none { it.name == name }) CompletionItem(name, name, CompletionKind.Table, "collection")
                            else {
                                val call = "getCollection('${name.replace("\\", "\\\\").replace("'", "\\'")}')"
                                CompletionItem(name, call, CompletionKind.Table, "collection")
                            }
                        }
                    rank(collections, typed) + rank(methodItems(DatabaseMethods, readOnly, hasCall), typed)
                }
                is Owner.Collection -> rank(methodItems(CollectionMethods, readOnly, hasCall), typed)
                is Owner.Cursor -> rank(methodItems(CursorMethods.filter { it.name !in owner.used }, readOnly, hasCall), typed)
                Owner.Unknown -> emptyList()
            }
        return CompletionResult(member.start, member.end, typed, items)
    }

    private fun argument(text: String, caret: Int, owner: Owner, method: String, argument: Argument, catalog: MongoCatalog): CompletionResult {
        val resolved = owner
        val collection =
            when (resolved) {
                is Owner.Collection -> resolved.name
                is Owner.Cursor -> resolved.collection
                else -> null
            }
        val fields = collection?.let { catalog.fields(it) }.orEmpty()
        val spec = methodsFor(resolved).firstOrNull { it.name == method } ?: return CompletionResult.empty(caret)
        val kind = spec.arguments.getOrNull(argument.index) ?: return CompletionResult.empty(caret)
        val argText = text.substring(argument.start, argument.end)
        val local = caret - argument.start
        return when (kind) {
            ArgumentKind.FieldName -> strings(text, caret, argument, fields.map { CompletionItem(it.path, quote(it.path), CompletionKind.Column, it.types.joinToString(" | ").ifEmpty { null }) })
            ArgumentKind.CollectionName ->
                strings(text, caret, argument, catalog.collections().orEmpty().map { CompletionItem(it, quote(it), CompletionKind.Table, "collection") })
            is ArgumentKind.Json -> {
                val result = DocumentCompleter.complete(argText, local, kind.query, fields)
                val shifted = result.copy(replaceStart = result.replaceStart + argument.start, replaceEnd = result.replaceEnd + argument.start)
                // `find(|)`: what's offered comes with the brackets the shell needs around it.
                if (argText.isNotBlank()) shifted
                else {
                    val (open, close) = if (kind.query == DocumentQuery.Pipeline || kind.query == DocumentQuery.Documents) "[" to "]" else "{" to "}"
                    shifted.copy(items = shifted.items.map { it.copy(insertText = open + it.insertText + close, caretOffset = it.caretOffset + 1) })
                }
            }
            ArgumentKind.None -> CompletionResult.empty(caret)
        }
    }

    /** A string argument: the items are quoted strings that replace the whole literal, quotes included. */
    private fun strings(text: String, caret: Int, argument: Argument, items: List<CompletionItem>): CompletionResult {
        val raw = text.substring(argument.start, caret)
        val start = argument.start + (raw.length - raw.trimStart().length)
        val token = text.substring(start, caret)
        if (token.isNotEmpty() && token[0] != '\'' && token[0] != '"') return CompletionResult.empty(caret)
        var end = caret
        val quote = token.firstOrNull()
        while (end < text.length && text[end] != quote && text[end] != ',' && text[end] != ')') end++
        if (quote != null && end < text.length && text[end] == quote) end++
        val typed = token.drop(1)
        return CompletionResult(start, end.coerceAtLeast(caret), token, rank(items, typed))
    }

    private fun argument(text: String, open: Int, close: Int?, caret: Int): Argument {
        var index = 0
        var start = open + 1
        var depth = 0
        var i = open + 1
        val limit = close ?: text.length
        var end = limit
        while (i < limit) {
            val c = text[i]
            when {
                c == '\'' || c == '"' -> i = (closingQuote(text, i) ?: (text.length - 1))
                c == '{' || c == '[' || c == '(' -> depth++
                c == '}' || c == ']' || c == ')' -> depth--
                c == ',' && depth == 0 -> {
                    if (i < caret) {
                        index++
                        start = i + 1
                    } else {
                        end = i
                        break
                    }
                }
            }
            i++
        }
        return Argument(index, start, end.coerceAtLeast(caret))
    }

    // endregion

    // region Methods

    private sealed interface ArgumentKind {
        data class Json(val query: DocumentQuery) : ArgumentKind

        data object FieldName : ArgumentKind

        data object CollectionName : ArgumentKind

        data object None : ArgumentKind
    }

    private class Method(
        val name: String,
        val parameters: String,
        val summary: String,
        /** What's inserted after the name; the caret goes [caret] characters into it. */
        val template: String,
        val caret: Int,
        val writes: Boolean = false,
        val arguments: List<ArgumentKind> = emptyList(),
    )

    private fun methodItems(methods: List<Method>, readOnly: Boolean, hasCall: Boolean): List<CompletionItem> =
        methods.filter { !readOnly || !it.writes }.map { method ->
            if (hasCall) CompletionItem(method.name, method.name, CompletionKind.Function, "(${method.parameters})")
            else CompletionItem(method.name, method.name + method.template, CompletionKind.Function, "(${method.parameters})", method.name.length + method.caret)
        }

    private fun methodsFor(owner: Owner): List<Method> =
        when (owner) {
            Owner.Database -> DatabaseMethods
            is Owner.Collection -> CollectionMethods
            is Owner.Cursor -> CursorMethods
            Owner.Unknown -> emptyList()
        }

    private val Filter = ArgumentKind.Json(DocumentQuery.Filter)

    private val DatabaseMethods =
        listOf(
            Method("getCollection", "name", "a collection by name", "('')", 2, arguments = listOf(ArgumentKind.CollectionName)),
            Method("getCollectionNames", "", "the collections' names", "()", 2),
            Method("runCommand", "command", "run a database command", "({})", 2, arguments = listOf(ArgumentKind.Json(DocumentQuery.Command))),
            Method("adminCommand", "command", "run a command on admin", "({})", 2, arguments = listOf(ArgumentKind.Json(DocumentQuery.Command))),
            Method("stats", "", "database size and counts", "()", 2),
            Method("createCollection", "name", "create a collection", "('')", 2, writes = true),
            Method("dropDatabase", "", "drop this database", "()", 2, writes = true),
        )

    private val CollectionMethods =
        listOf(
            Method("find", "filter, projection", "documents matching the filter", "({})", 2, arguments = listOf(Filter, ArgumentKind.Json(DocumentQuery.Projection))),
            Method("findOne", "filter, projection", "the first matching document", "({})", 2, arguments = listOf(Filter, ArgumentKind.Json(DocumentQuery.Projection))),
            Method("aggregate", "pipeline", "run an aggregation pipeline", "([])", 2, arguments = listOf(ArgumentKind.Json(DocumentQuery.Pipeline))),
            Method("countDocuments", "filter", "count matching documents", "({})", 2, arguments = listOf(Filter)),
            Method("estimatedDocumentCount", "", "fast count from metadata", "()", 2),
            Method("distinct", "field, filter", "distinct values of a field", "('')", 2, arguments = listOf(ArgumentKind.FieldName, Filter)),
            Method("insertOne", "document", "insert a document", "({})", 2, writes = true, arguments = listOf(ArgumentKind.Json(DocumentQuery.Document))),
            Method("insertMany", "documents", "insert documents", "([{}])", 3, writes = true, arguments = listOf(ArgumentKind.Json(DocumentQuery.Documents))),
            Method("updateOne", "filter, update", "update the first match", "({}, {\$set: {}})", 2, writes = true, arguments = listOf(Filter, ArgumentKind.Json(DocumentQuery.Update))),
            Method("updateMany", "filter, update", "update every match", "({}, {\$set: {}})", 2, writes = true, arguments = listOf(Filter, ArgumentKind.Json(DocumentQuery.Update))),
            Method("replaceOne", "filter, document", "replace the first match", "({}, {})", 2, writes = true, arguments = listOf(Filter, ArgumentKind.Json(DocumentQuery.Document))),
            Method("deleteOne", "filter", "delete the first match", "({})", 2, writes = true, arguments = listOf(Filter)),
            Method("deleteMany", "filter", "delete every match", "({})", 2, writes = true, arguments = listOf(Filter)),
            Method("getIndexes", "", "the collection's indexes", "()", 2),
            Method("createIndex", "keys, options", "create an index", "({})", 2, writes = true, arguments = listOf(ArgumentKind.Json(DocumentQuery.Sort))),
            Method("dropIndex", "name", "drop an index", "('')", 2, writes = true),
            Method("stats", "", "collection size and counts", "()", 2),
            Method("drop", "", "drop the collection", "()", 2, writes = true),
        )

    private val CursorMethods =
        listOf(
            Method("sort", "sort", "order the documents", "({})", 2, arguments = listOf(ArgumentKind.Json(DocumentQuery.Sort))),
            Method("limit", "n", "at most n documents", "()", 1),
            Method("skip", "n", "skip the first n documents", "()", 1),
            Method("projection", "projection", "fields to include or exclude", "({})", 2, arguments = listOf(ArgumentKind.Json(DocumentQuery.Projection))),
        )

    private val TopLevel =
        listOf(
            CompletionItem("db", "db.", CompletionKind.Keyword, "the current database"),
            CompletionItem("show", "show ", CompletionKind.Keyword, "dbs, collections"),
            CompletionItem("use", "use ", CompletionKind.Keyword, "switch database"),
            CompletionItem("clear", "clear", CompletionKind.Keyword, "empty this view"),
        )

    private val ShowTargets =
        listOf(
            CompletionItem("dbs", "dbs", CompletionKind.Keyword, "databases"),
            CompletionItem("collections", "collections", CompletionKind.Keyword, "collections of this database"),
            CompletionItem("databases", "databases", CompletionKind.Keyword, "databases"),
            CompletionItem("tables", "tables", CompletionKind.Keyword, "collections of this database"),
        )

    // endregion

    // region Helpers

    private val Identifier = Regex("""[A-Za-z_$][\w$]*""")

    /** A word at the caret, replaced up to its end. */
    private fun words(text: String, caret: Int, typed: String, items: List<CompletionItem>): CompletionResult {
        var end = caret
        while (end < text.length && !text[end].isWhitespace()) end++
        return CompletionResult(caret - typed.length, end, typed, rank(items, typed))
    }

    /** Prefix matches first, then names containing what's typed. */
    private fun rank(items: List<CompletionItem>, typed: String): List<CompletionItem> {
        if (typed.isEmpty()) return items
        val wanted = typed.lowercase()
        return items
            .mapNotNull { item ->
                val label = item.label.lowercase()
                when {
                    label.startsWith(wanted) -> 0
                    label.contains(wanted) -> 1
                    else -> null
                }?.let { it to item }
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun quote(name: String) = "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun unquote(literal: String): String? {
        if (literal.length < 2) return null
        val q = literal.first()
        if ((q != '\'' && q != '"') || literal.last() != q) return null
        return literal.substring(1, literal.length - 1).replace(Regex("""\\(.)"""), "$1")
    }

    /** The parenthesis closing the one at [open], skipping strings and nested brackets; null while unclosed. */
    private fun closing(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\'', '"' -> i = closingQuote(text, i) ?: return null
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    depth--
                    if (depth == 0) return if (text[i] == ')') i else null
                }
            }
            i++
        }
        return null
    }

    private fun closingQuote(text: String, open: Int): Int? {
        val quote = text[open]
        var j = open + 1
        while (j < text.length) {
            when (text[j]) {
                '\\' -> j += 2
                quote -> return j
                else -> j++
            }
        }
        return null
    }

    // endregion
}

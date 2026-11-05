package dev.tablegrip.core.completion

/**
 * A field of a collection's documents, as completion offers it: [path] is dotted for fields of embedded documents
 * (`address.city`), [types] are the BSON types seen in it, and [values] some of its values as shell literals (`'A'`,
 * `30`, `ObjectId('…')`), most frequent first. For arrays the values are the elements', which is what a filter matches.
 */
data class DocumentField(val path: String, val types: List<String>, val values: List<String> = emptyList())

/** Which `find()` argument is being completed. */
enum class DocumentQuery { Filter, Sort, Projection }

/**
 * Completion for a document browser's filter and sort, written in the shell's relaxed Extended JSON
 * (`{status: 'A', total: {$gt: 10}}`). It reads the text up to the caret as nested objects and arrays to tell what the
 * caret is in: the keys of the filter (fields, `$and`…), of a field's condition (`$gt`, `$in`…), of `$elemMatch` (the
 * array's element fields), `$expr` or `$text`; or a value: a field's values, `true`/`false` for `$exists`, BSON type
 * names for `$type`, `1`/`-1` for a sort, `1`/`0` for a projection. The outer braces may be left out.
 */
object DocumentCompleter {
    fun complete(text: String, caret: Int, query: DocumentQuery, fields: List<DocumentField>): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val scan = Scanner(text, at, query).run() ?: return CompletionResult.empty(at)
        val frame = scan.frame
        val token = scan.token
        val start = token?.start ?: at
        val end = tokenEnd(text, at, token?.quote)
        val prefix = token?.text.orEmpty()
        val typed = prefix.trimStart('\'', '"')
        val items =
            when {
                frame.isObject && frame.expectingKey -> keys(frame.role, typed, fields, hasColon = text.substring(end).trimStart().startsWith(":"))
                frame.isObject -> values(frame.role, frame.key ?: return CompletionResult.empty(at), typed, fields)
                else -> elements(frame.role, typed, fields)
            }
        return CompletionResult(start, end, prefix, items)
    }

    // region Suggestions

    private fun keys(role: Role, typed: String, fields: List<DocumentField>, hasColon: Boolean): List<CompletionItem> {
        fun field(name: String, field: DocumentField) =
            CompletionItem(name, keyText(name, hasColon), CompletionKind.Column, field.types.joinToString(" | ").ifEmpty { null })

        fun operators(list: List<Operator>) = list.map { it.item(hasColon) }
        val all =
            when (role) {
                is Role.Query ->
                    under(role.base, fields).map { (name, f) -> field(name, f) } +
                        operators(QueryOperators) + if (role.elements) operators(FieldOperators) else emptyList()
                is Role.Operators -> operators(FieldOperators) + under(role.field, fields).map { (name, f) -> field(name, f) }
                Role.Sort, Role.Projection -> under("", fields).map { (name, f) -> field(name, f) }
                Role.Text -> operators(TextOperators)
                Role.Expression -> operators(ExpressionOperators)
                else -> emptyList()
            }
        return rank(all, typed)
    }

    private fun values(role: Role, key: String, typed: String, fields: List<DocumentField>): List<CompletionItem> =
        when (role) {
            is Role.Query ->
                when (key) {
                    "\$and", "\$or", "\$nor" -> listOf(snippet("[{}]", "conditions, all/any/none of which match", 2))
                    "\$expr" -> listOf(snippet("{}", "an aggregation expression", 1))
                    "\$text" -> listOf(snippet("{\$search: ''}", "text index search", 10))
                    "\$comment" -> emptyList()
                    else -> fieldValues(join(role.base, key), typed, fields) + if (typed.isEmpty()) ConditionSnippets else emptyList()
                }
            is Role.Operators ->
                when (key) {
                    "\$exists" -> rank(Booleans, typed)
                    "\$type" -> rank(BsonTypes, typed)
                    "\$in", "\$nin", "\$all" -> listOf(snippet("[]", "a list of values", 1))
                    "\$elemMatch", "\$not" -> listOf(snippet("{}", if (key == "\$not") "a condition that must not match" else "conditions on one element", 1))
                    "\$options" -> rank(RegexOptions, typed)
                    "\$regex", "\$size", "\$mod" -> emptyList()
                    else -> if (key.startsWith("$")) fieldValues(role.field, typed, fields) else fieldValues(join(role.field, key), typed, fields)
                }
            Role.Sort -> rank(SortDirections, typed)
            Role.Projection -> rank(Inclusions, typed)
            Role.Text -> if (key == "\$caseSensitive" || key == "\$diacriticSensitive") rank(Booleans, typed) else emptyList()
            else -> emptyList()
        }

    private fun elements(role: Role, typed: String, fields: List<DocumentField>): List<CompletionItem> =
        when (role) {
            is Role.Values -> fieldValues(role.field, typed, fields)
            else -> emptyList()
        }

    private fun fieldValues(path: String, typed: String, fields: List<DocumentField>): List<CompletionItem> {
        val field = fields.firstOrNull { it.path == path } ?: return emptyList()
        val wanted = typed.lowercase()
        return field.values
            .map { it to plain(it).lowercase() }
            .filter { (_, plain) -> wanted.isEmpty() || plain.contains(wanted) }
            .sortedBy { (_, plain) -> if (plain.startsWith(wanted)) 0 else 1 }
            .map { (literal, _) -> CompletionItem(literal, literal, CompletionKind.Value, "$path value") }
    }

    /** Fields under [base] (all for ""), named relative to it: `city` for `address.city` under `address`. */
    private fun under(base: String, fields: List<DocumentField>): List<Pair<String, DocumentField>> =
        if (base.isEmpty()) fields.map { it.path to it }
        else fields.filter { it.path.startsWith("$base.") }.map { it.path.removePrefix("$base.") to it }

    /** Prefix matches first, shallow fields before nested ones; then fields merely containing what's typed. */
    private fun rank(items: List<CompletionItem>, typed: String): List<CompletionItem> {
        if (typed.isEmpty()) return items
        val wanted = typed.lowercase()
        val bare = wanted.removePrefix("$")
        return items
            .mapNotNull { item ->
                val label = item.label.lowercase()
                when {
                    label.startsWith(wanted) -> 0
                    label.removePrefix("$").startsWith(bare) -> 1
                    label.split('.').any { it.startsWith(bare) } -> 2
                    item.kind == CompletionKind.Column && label.contains(bare) -> 3
                    else -> null
                }?.let { it to item }
            }
            .sortedWith(compareBy({ it.first }, { it.second.label.count { c -> c == '.' } }))
            .map { it.second }
    }

    private fun keyText(name: String, hasColon: Boolean): String = quoteKey(name) + if (hasColon) "" else ": "

    /** Names that aren't identifiers, dotted paths included, need quotes as keys. */
    fun quoteKey(name: String): String =
        if (Identifier.matches(name)) name else "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** A literal's text without quotes and wrappers, to match what's typed: `ObjectId('65f…')` → `65f…`. */
    private fun plain(literal: String): String =
        literal.substringAfter('(', literal).substringBeforeLast(')', literal.substringAfter('(', literal)).trim('\'', '"')

    private fun join(base: String, key: String) = if (base.isEmpty()) key else "$base.$key"

    private fun snippet(text: String, detail: String, caret: Int) = CompletionItem(text, text, CompletionKind.Keyword, detail, caret)

    private class Operator(val name: String, val detail: String, val value: String? = null, val caret: Int? = null) {
        /** `$in: []` with the caret inside the brackets, or `$gt: ` ready for the value. */
        fun item(hasColon: Boolean): CompletionItem {
            if (hasColon || value == null) return CompletionItem(name, name + if (hasColon) "" else ": ", CompletionKind.Operator, detail)
            val text = "$name: $value"
            return CompletionItem(name, text, CompletionKind.Operator, detail, "$name: ".length + (caret ?: value.length))
        }
    }

    private val Identifier = Regex("""[A-Za-z_$][\w$]*""")

    private val QueryOperators =
        listOf(
            Operator("\$and", "all of the conditions", "[{}]", 2),
            Operator("\$or", "any of the conditions", "[{}]", 2),
            Operator("\$nor", "none of the conditions", "[{}]", 2),
            Operator("\$expr", "aggregation expression", "{}", 1),
            Operator("\$text", "text index search", "{\$search: ''}", 10),
            Operator("\$jsonSchema", "matches a JSON schema", "{}", 1),
            Operator("\$comment", "comment for the profiler"),
        )

    private val FieldOperators =
        listOf(
            Operator("\$eq", "equal to"),
            Operator("\$ne", "not equal to"),
            Operator("\$gt", "greater than"),
            Operator("\$gte", "greater than or equal to"),
            Operator("\$lt", "less than"),
            Operator("\$lte", "less than or equal to"),
            Operator("\$in", "any of the values", "[]", 1),
            Operator("\$nin", "none of the values", "[]", 1),
            Operator("\$exists", "has the field", "true"),
            Operator("\$type", "BSON type is"),
            Operator("\$regex", "matches a regular expression", "''", 1),
            Operator("\$options", "regex flags: i m x s"),
            Operator("\$not", "doesn't match the condition", "{}", 1),
            Operator("\$all", "array has all the values", "[]", 1),
            Operator("\$elemMatch", "an array element matches all conditions", "{}", 1),
            Operator("\$size", "array has this many elements"),
            Operator("\$mod", "divisor and remainder", "[]", 1),
        )

    private val TextOperators =
        listOf(
            Operator("\$search", "words to search for", "''", 1),
            Operator("\$language", "stemming language", "''", 1),
            Operator("\$caseSensitive", "match case"),
            Operator("\$diacriticSensitive", "match diacritics"),
        )

    private val ExpressionTakesArray =
        setOf("\$eq", "\$ne", "\$gt", "\$gte", "\$lt", "\$lte", "\$and", "\$or", "\$not", "\$in", "\$cmp", "\$add", "\$subtract", "\$multiply", "\$divide", "\$mod", "\$ifNull", "\$concat")

    private val ExpressionOperators =
        listOf(
            "\$eq" to "equal", "\$ne" to "not equal", "\$gt" to "greater than", "\$gte" to "greater or equal", "\$lt" to "less than",
            "\$lte" to "less or equal", "\$and" to "all true", "\$or" to "any true", "\$not" to "negation", "\$in" to "value in array",
            "\$cmp" to "compare", "\$add" to "sum", "\$subtract" to "difference", "\$multiply" to "product", "\$divide" to "quotient",
            "\$mod" to "remainder", "\$abs" to "absolute value", "\$size" to "array length", "\$ifNull" to "first non-null",
            "\$cond" to "if-then-else", "\$concat" to "join strings", "\$toLower" to "lower case", "\$toUpper" to "upper case",
            "\$strLenCP" to "string length", "\$regexMatch" to "matches a regex", "\$toString" to "convert to string",
            "\$toInt" to "convert to int", "\$toDouble" to "convert to double", "\$toDate" to "convert to date", "\$type" to "BSON type",
            "\$year" to "year of a date", "\$month" to "month of a date", "\$dayOfMonth" to "day of a date", "\$dateToString" to "format a date",
        ).map { (name, detail) -> Operator(name, detail, if (name in ExpressionTakesArray) "[]" else null, 1) }

    private val Booleans = listOf("true", "false").map { CompletionItem(it, it, CompletionKind.Value) }
    private val SortDirections = listOf(CompletionItem("1", "1", CompletionKind.Value, "ascending"), CompletionItem("-1", "-1", CompletionKind.Value, "descending"))
    private val Inclusions = listOf(CompletionItem("1", "1", CompletionKind.Value, "include"), CompletionItem("0", "0", CompletionKind.Value, "exclude"))
    private val RegexOptions =
        listOf("'i'" to "case-insensitive", "'m'" to "multi-line anchors", "'x'" to "ignore whitespace", "'s'" to "dot matches newlines")
            .map { (value, detail) -> CompletionItem(value, value, CompletionKind.Value, detail) }
    private val BsonTypes =
        listOf("string", "number", "int", "long", "double", "decimal", "bool", "date", "objectId", "object", "array", "null", "binData", "regex", "timestamp")
            .map { CompletionItem("'$it'", "'$it'", CompletionKind.Value, "BSON type") }
    private val ConditionSnippets =
        listOf(
            snippet("{\$gt: }", "a comparison", 6),
            snippet("{\$in: []}", "any of these values", 6),
            snippet("{\$exists: true}", "has the field", 15),
            snippet("{\$regex: '', \$options: 'i'}", "matches a pattern", 10),
        )

    // endregion

    // region Reading the text

    /** What the caret is in: [role] says what its keys or elements are; [key] is the key whose value is being typed. */
    private class Frame(val isObject: Boolean, val role: Role) {
        var key: String? = null
        var expectingKey = isObject
        /** The last complete token, a key once a colon follows it. */
        var last: String? = null
    }

    private sealed interface Role {
        /** A filter's keys: fields under [base], `$and`…; with [elements], `$elemMatch`'s, which also takes operators. */
        data class Query(val base: String, val elements: Boolean = false) : Role

        /** `{field: {|}}`: a condition's operators, or the fields of an embedded document. */
        data class Operators(val field: String) : Role

        /** `$in: [|]` and other lists of a field's values. */
        data class Values(val field: String) : Role

        /** `$and: [|]`: each element is a filter. */
        data class Clauses(val base: String) : Role

        data object Sort : Role

        /** A projection's keys are fields; their values include or exclude them. */
        data object Projection : Role

        data object Text : Role

        data object Expression : Role

        /** Anything else, e.g. a document value: nothing to suggest. */
        data object Literal : Role
    }

    private class Token(val start: Int, val text: String, val quote: Char?)

    private class Scan(val frame: Frame, val token: Token?)

    private class Scanner(private val text: String, private val caret: Int, private val query: DocumentQuery) {
        private val stack = ArrayDeque<Frame>()
        private var parens = 0

        fun run(): Scan? {
            val rootRole =
                when (query) {
                    DocumentQuery.Sort -> Role.Sort
                    DocumentQuery.Projection -> Role.Projection
                    DocumentQuery.Filter -> Role.Query("")
                }
            // Without outer braces, the text is the root document's content.
            if (!text.substring(0, caret).trimStart().startsWith("{")) stack.addLast(Frame(true, rootRole))
            var i = 0
            var token: Token? = null
            while (i < caret) {
                val c = text[i]
                token = null
                when {
                    c.isWhitespace() -> i++
                    c == '\'' || c == '"' -> {
                        val close = closingQuote(i, c)
                        if (close == null || close >= caret) {
                            // The caret is inside this string.
                            if (parens > 0) return null
                            return stack.lastOrNull()?.let { Scan(it, Token(i, text.substring(i, caret), c)) }
                        }
                        complete(unescape(text.substring(i + 1, close)))
                        i = close + 1
                    }
                    c == '/' && stack.lastOrNull()?.let { !it.expectingKey } == true -> {
                        // A regular expression literal.
                        var j = i + 1
                        while (j < text.length && text[j] != '/') j += if (text[j] == '\\') 2 else 1
                        if (j >= caret) return null
                        complete(text.substring(i, j + 1))
                        i = j + 1
                    }
                    c == '{' || c == '[' -> {
                        val isObject = c == '{'
                        val parent = stack.lastOrNull()
                        val role =
                            when {
                                parent == null -> if (isObject) rootRole else Role.Literal
                                parens > 0 -> Role.Literal
                                else -> child(parent, isObject)
                            }
                        stack.addLast(Frame(isObject, role))
                        i++
                    }
                    c == '}' || c == ']' -> {
                        stack.removeLastOrNull()
                        // The parent now holds a complete value (or, unbalanced, nothing more to complete).
                        stack.lastOrNull()?.last = text[i].toString()
                        i++
                    }
                    c == '(' -> {
                        parens++
                        i++
                    }
                    c == ')' -> {
                        parens = (parens - 1).coerceAtLeast(0)
                        i++
                    }
                    c == ':' -> {
                        stack.lastOrNull()?.takeIf { it.isObject && parens == 0 }?.let { frame ->
                            frame.key = frame.last
                            frame.last = null
                            frame.expectingKey = false
                        }
                        i++
                    }
                    c == ',' -> {
                        stack.lastOrNull()?.takeIf { it.isObject && parens == 0 }?.let { frame ->
                            frame.key = null
                            frame.last = null
                            frame.expectingKey = true
                        }
                        i++
                    }
                    isWordChar(c) -> {
                        var j = i
                        while (j < text.length && isWordChar(text[j])) j++
                        if (j >= caret) token = Token(i, text.substring(i, caret), null)
                        else complete(text.substring(i, j))
                        i = j
                    }
                    else -> i++
                }
            }
            if (parens > 0) return null
            val frame = stack.lastOrNull() ?: return null
            // A word right before the caret is what's being typed; after a complete value, nothing is expected.
            if (token == null && frame.isObject && !frame.expectingKey && frame.last != null) return null
            if (token == null && frame.isObject && frame.expectingKey && frame.last != null) return null
            return Scan(frame, token)
        }

        private fun complete(word: String) {
            val frame = stack.lastOrNull() ?: return
            if (parens == 0) frame.last = word
        }

        private fun child(parent: Frame, isObject: Boolean): Role {
            if (!parent.isObject) {
                return when (val role = parent.role) {
                    is Role.Clauses -> if (isObject) Role.Query(role.base) else Role.Literal
                    Role.Expression -> Role.Expression
                    else -> Role.Literal
                }
            }
            val key = parent.key ?: return Role.Literal
            return when (val role = parent.role) {
                is Role.Query ->
                    when {
                        key == "\$and" || key == "\$or" || key == "\$nor" -> if (isObject) Role.Literal else Role.Clauses(role.base)
                        key == "\$expr" -> Role.Expression
                        key == "\$text" -> if (isObject) Role.Text else Role.Literal
                        key.startsWith("$") -> Role.Literal
                        isObject -> Role.Operators(join(role.base, key))
                        else -> Role.Values(join(role.base, key))
                    }
                is Role.Operators ->
                    when {
                        key == "\$in" || key == "\$nin" || key == "\$all" -> if (isObject) Role.Literal else Role.Values(role.field)
                        key == "\$not" -> if (isObject) Role.Operators(role.field) else Role.Literal
                        key == "\$elemMatch" -> if (isObject) Role.Query(role.field, elements = true) else Role.Literal
                        key.startsWith("$") -> Role.Literal
                        isObject -> Role.Operators(join(role.field, key))
                        else -> Role.Values(join(role.field, key))
                    }
                Role.Expression -> Role.Expression
                else -> Role.Literal
            }
        }

        private fun closingQuote(open: Int, quote: Char): Int? {
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

        private fun unescape(s: String) = s.replace(Regex("""\\(.)"""), "$1")
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' || c == '-' || c == '+'

    /** Where a completed item ends: the rest of the word at the caret, and the closing quote of a string. */
    private fun tokenEnd(text: String, caret: Int, quote: Char?): Int {
        var end = caret
        while (end < text.length && isWordChar(text[end])) end++
        if (quote != null && end < text.length && text[end] == quote) end++
        return end
    }

    // endregion
}

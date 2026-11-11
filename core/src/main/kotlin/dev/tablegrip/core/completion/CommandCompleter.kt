package dev.tablegrip.core.completion

/**
 * A console command in redis-cli's notation: [syntax] lists its arguments after the [name] (`key value [NX|XX]
 * [EX seconds|PX milliseconds]`). Lower-case words are arguments, upper-case words and `--flags` are typed as they are,
 * `a|b` is a choice, `[…]` is optional and `…` repeats. Two-word names (`CONFIG GET`) are a command and a subcommand.
 */
class CommandSpec(val name: String, val syntax: String, val summary: String, val reads: Boolean = true) {
    internal val words: List<String> = name.split(' ')
    internal val grammar: Grammar by lazy { Grammar.parse(syntax) }
}

/**
 * The commands of one console and how its arguments are completed: [keyParameters] are argument names that take an
 * existing key, [values] the fixed values some arguments take, [upperCase] whether commands are upper-case by default,
 * and with [flagsAnywhere] options (`--prefix`) may come before, between or after the arguments, as etcdctl's do.
 */
class CommandCatalog(
    val commands: List<CommandSpec>,
    val upperCase: Boolean,
    val keyParameters: Set<String>,
    val patternParameters: Set<String> = emptySet(),
    val values: Map<String, List<Pair<String, String>>> = emptyMap(),
    val flagsAnywhere: Boolean = false,
)

/** One positional argument: a named value, or a choice of literal words (some of which may be values themselves). */
internal sealed interface Positional {
    data class Parameter(val name: String) : Positional

    data class Choice(val words: List<String>, val parameters: List<String>) : Positional
}

/** An option word, the arguments-so-far count it's allowed at, and what it takes after it. */
internal class OptionSpec(val word: String, val position: Int, val parameter: Positional?)

internal class Grammar(val positional: List<Positional>, val repeat: List<Positional>, val options: Map<String, OptionSpec>) {
    companion object {
        private val Token = Regex("""\.\.\.|[\[\]|]|[^\s\[\]|]+""")

        private fun isLiteral(word: String) = word.startsWith("-") || word.none { it.isLowerCase() }

        fun parse(syntax: String): Grammar {
            val tokens = Token.findAll(syntax).map { it.value }.toList()
            val positional = mutableListOf<Positional>()
            var repeat: List<Positional>? = null
            val options = LinkedHashMap<String, OptionSpec>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token == "[" -> {
                        // Up to the matching bracket.
                        var depth = 0
                        var j = i
                        while (j < tokens.size) {
                            if (tokens[j] == "[") depth++
                            if (tokens[j] == "]") depth--
                            if (depth == 0) break
                            j++
                        }
                        val group = tokens.subList(i + 1, j.coerceAtMost(tokens.size))
                        if (group.lastOrNull() == "..." || (group.contains("...") && group.firstOrNull()?.let { !isLiteral(it) } == true)) {
                            if (repeat == null) repeat = group.filter { it != "[" && it != "]" && it != "..." && it != "|" && !isLiteral(it) }.distinct().map { Positional.Parameter(it) }
                        } else {
                            readOptions(group, positional.size, options)
                        }
                        i = j + 1
                    }
                    token == "]" || token == "|" || token == "..." -> i++
                    else -> {
                        // A positional argument, or a choice `a|b|c`.
                        val choice = mutableListOf(token)
                        var j = i + 1
                        while (j + 1 < tokens.size && tokens[j] == "|") {
                            choice += tokens[j + 1]
                            j += 2
                        }
                        positional +=
                            if (choice.size == 1 && !isLiteral(token)) Positional.Parameter(token)
                            else Positional.Choice(choice.filter(::isLiteral), choice.filterNot(::isLiteral))
                        // `key [key ...]` written as `key ...`.
                        if (j < tokens.size && tokens[j] == "...") {
                            if (repeat == null) repeat = listOf(positional.removeAt(positional.lastIndex))
                            j++
                        }
                        i = j
                    }
                }
            }
            return Grammar(positional, repeat.orEmpty(), options)
        }

        private fun readOptions(group: List<String>, position: Int, options: MutableMap<String, OptionSpec>) {
            var k = 0
            var current: String? = null
            var choice: MutableList<String>? = null
            fun flush() {
                val word = current ?: return
                val existing = options[word]
                if (choice != null && existing != null) options[word] = OptionSpec(word, existing.position, Positional.Choice(choice!!.toList(), emptyList()))
                choice = null
            }
            while (k < group.size) {
                val token = group[k]
                val afterBar = k > 0 && group[k - 1] == "|"
                when {
                    token == "[" || token == "]" || token == "..." || token == "|" -> Unit
                    // `--limit=N`: a flag and its value in one word.
                    token.startsWith("--") && '=' in token -> {
                        flush()
                        val word = token.substringBefore('=') + "="
                        options.putIfAbsent(word, OptionSpec(word, position, Positional.Parameter(token.substringAfter('=').lowercase())))
                        current = null
                    }
                    isLiteral(token) -> {
                        when {
                            choice != null && afterBar -> choice!! += token
                            current != null && !afterBar && options[current]?.parameter == null -> choice = mutableListOf(token)
                            else -> {
                                flush()
                                options.putIfAbsent(token, OptionSpec(token, position, null))
                                current = token
                            }
                        }
                    }
                    else -> {
                        // A value: the current option's argument, once.
                        flush()
                        val word = current
                        if (word != null && options[word]?.parameter == null && !afterBar) options[word] = OptionSpec(word, options[word]!!.position, Positional.Parameter(token))
                        current = null
                    }
                }
                k++
            }
            flush()
        }
    }
}

/**
 * Completion for a command console (redis-cli, etcdctl): command names, subcommands, options in the places the
 * command's syntax allows them, the values some arguments take (`SCAN … TYPE hash`, `INFO memory`), and existing keys
 * where a key goes, from [keys] (which returns what's known for a prefix and may load more). With [readOnly], commands
 * that write aren't offered.
 */
object CommandCompleter {
    fun complete(text: String, caret: Int, catalog: CommandCatalog, keys: (String) -> List<String>, readOnly: Boolean = false): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val tokens = tokenize(text.substring(0, at))
        val current = tokens.lastOrNull()?.takeIf { it.end == at } ?: Token(at, at, "", "", null)
        val done = if (current.end == at && tokens.lastOrNull() === current) tokens.dropLast(1) else tokens
        val end = tokenEnd(text, at, current.quote)
        val typed = current.value
        val quoted = current.raw.firstOrNull().let { it == '"' || it == '\'' }
        val items = suggest(done.map { it.value }, typed, catalog, keys, readOnly, quoted)
        return CompletionResult(current.start, end, current.raw, items)
    }

    /** The command's syntax and summary, once its name is typed: `SET key value [NX|XX] … — Set a string value`. */
    fun hint(text: String, caret: Int, catalog: CommandCatalog): String? {
        val words = tokenize(text.substring(0, caret.coerceIn(0, text.length))).map { it.value }
        val spec = lookup(words, catalog) ?: return null
        val name = if (catalog.upperCase) spec.name else spec.name.lowercase()
        return "$name ${spec.syntax}".trim() + " — " + spec.summary
    }

    private fun suggest(done: List<String>, typed: String, catalog: CommandCatalog, keys: (String) -> List<String>, readOnly: Boolean, quoted: Boolean): List<CompletionItem> {
        val commands = catalog.commands.filter { !readOnly || it.reads }
        val lower = typed.isNotEmpty() && typed.none { it.isUpperCase() } && typed.any { it.isLowerCase() } || !catalog.upperCase
        fun cased(word: String) = if (word.startsWith("-") || word.none { it.isLetter() }) word else if (lower) word.lowercase() else word.uppercase()

        if (done.isEmpty()) {
            if (quoted) return emptyList()
            // Command names, a container (CONFIG, CLIENT…) once for all its subcommands.
            val byWord = commands.groupBy { it.words.first().uppercase() }
            val items =
                byWord.map { (word, specs) ->
                    val standalone = specs.firstOrNull { it.words.size == 1 }
                    val detail =
                        standalone?.summary
                            ?: specs.joinToString("|", prefix = "", postfix = "") { it.words[1].uppercase() }.let { if (it.length > 48) it.take(47) + "…" else it }
                    CompletionItem(cased(word), cased(word) + " ", CompletionKind.Keyword, detail)
                }
            return rank(items, typed)
        }
        val first = done.first().uppercase()
        val subcommands = commands.filter { it.words.size == 2 && it.words[0].uppercase() == first }
        if (done.size == 1 && subcommands.isNotEmpty()) {
            if (quoted) return emptyList()
            val items = subcommands.map { CompletionItem(cased(it.words[1]), cased(it.words[1]) + " ", CompletionKind.Keyword, it.summary) }
            return rank(items, typed)
        }
        val spec = lookup(done, catalog) ?: return emptyList()
        if (readOnly && !spec.reads) return emptyList()
        val args = done.drop(spec.words.size)
        val grammar = spec.grammar
        val expected = expect(grammar, args, catalog)
        val items = mutableListOf<CompletionItem>()
        when (expected) {
            is Expected.Value -> items += valuesFor(expected.positional, typed, catalog, keys, quoted, ::cased)
            is Expected.Free -> {
                expected.positional?.let { items += valuesFor(it, typed, catalog, keys, quoted, ::cased) }
                if (!quoted && (!catalog.flagsAnywhere || typed.startsWith("-") || items.isEmpty())) {
                    items +=
                        rank(
                            expected.options.map { option ->
                                val word = cased(option.word)
                                val detail =
                                    when (val p = option.parameter) {
                                        is Positional.Parameter -> p.name
                                        is Positional.Choice -> p.words.joinToString("|")
                                        null -> "option"
                                    }
                                CompletionItem(word, if (word.endsWith("=")) word else "$word ", CompletionKind.Keyword, detail)
                            },
                            typed,
                        )
                }
            }
            Expected.Nothing -> Unit
        }
        return items
    }

    private sealed interface Expected {
        /** Exactly this argument (an option's value, or a required argument). */
        data class Value(val positional: Positional) : Expected

        /** An optional argument and/or options. */
        data class Free(val positional: Positional?, val options: List<OptionSpec>) : Expected

        data object Nothing : Expected
    }

    /** Walks the typed arguments through the grammar to what the next one can be. */
    private fun expect(grammar: Grammar, args: List<String>, catalog: CommandCatalog): Expected {
        var index = 0
        var repeated = 0
        var pending: Positional? = null
        val used = mutableSetOf<String>()
        fun optionFor(arg: String): OptionSpec? {
            val word = if (arg.startsWith("--") && '=' in arg) arg.substringBefore('=') + "=" else arg.uppercase().takeUnless { arg.startsWith("--") } ?: arg
            val option = grammar.options[word] ?: grammar.options[arg] ?: return null
            if (catalog.flagsAnywhere) return option.takeIf { arg.startsWith("-") }
            // A key named like an option is still a key; options go where the syntax has them.
            val next = grammar.positional.getOrNull(index)
            if (next is Positional.Parameter && next.name in catalog.keyParameters) return null
            return option.takeIf { index >= it.position }
        }
        for (arg in args) {
            if (pending != null) {
                pending = null
                continue
            }
            val option = optionFor(arg)
            if (option != null) {
                used += option.word
                if (!option.word.endsWith("=")) pending = option.parameter
                continue
            }
            if (index < grammar.positional.size) index++ else repeated++
        }
        pending?.let { return Expected.Value(it) }
        val next = grammar.positional.getOrNull(index)
        val options = grammar.options.values.filter { it.word !in used && (catalog.flagsAnywhere || it.position == index || (it.position >= grammar.positional.size && index >= grammar.positional.size)) }
        return when {
            next != null && options.isEmpty() -> Expected.Value(next)
            next != null -> Expected.Free(next, options)
            grammar.repeat.isNotEmpty() -> Expected.Free(grammar.repeat[repeated % grammar.repeat.size], options)
            options.isNotEmpty() -> Expected.Free(null, options)
            else -> Expected.Nothing
        }
    }

    private fun valuesFor(
        positional: Positional,
        typed: String,
        catalog: CommandCatalog,
        keys: (String) -> List<String>,
        quoted: Boolean,
        cased: (String) -> String,
    ): List<CompletionItem> =
        when (positional) {
            is Positional.Choice ->
                if (quoted) emptyList() else rank(positional.words.map { CompletionItem(cased(it), cased(it) + " ", CompletionKind.Keyword, positional.parameters.firstOrNull()) }, typed)
            is Positional.Parameter -> {
                val name = positional.name
                when {
                    name in catalog.keyParameters -> keyItems(keys(typed), typed)
                    name in catalog.patternParameters -> patternItems(keys(typed.substringBefore('*')), typed)
                    else -> catalog.values[name]?.let { values -> rank(values.map { (value, detail) -> CompletionItem(value, "$value ", CompletionKind.Value, detail) }, typed) }.orEmpty()
                }
            }
        }

    /** Keys starting with what's typed, the exact one first. */
    private fun keyItems(keys: List<String>, typed: String): List<CompletionItem> =
        (keys.filter { it == typed } + keys.asSequence().filter { it.startsWith(typed) && it != typed }.take(MaxKeys))
            .map { CompletionItem(it, quoteArgument(it) + " ", CompletionKind.Value, "key") }

    /** Glob patterns from the keys' namespaces: `user:*`, `session:*`. */
    private fun patternItems(keys: List<String>, typed: String): List<CompletionItem> {
        val base = typed.substringBefore('*')
        val patterns = LinkedHashSet<String>()
        for (key in keys) {
            if (!key.startsWith(base)) continue
            val rest = key.substring(base.length)
            val cut = rest.indexOfFirst { it in Separators }
            patterns += if (cut >= 0) base + rest.substring(0, cut + 1) + "*" else "$base*"
            if (patterns.size >= MaxKeys) break
        }
        if (patterns.isEmpty()) patterns += "$base*"
        return patterns.map { CompletionItem(it, quoteArgument(it) + " ", CompletionKind.Value, "pattern") }
    }

    private fun lookup(words: List<String>, catalog: CommandCatalog): CommandSpec? {
        val first = words.firstOrNull()?.uppercase() ?: return null
        val second = words.getOrNull(1)?.uppercase()
        return catalog.commands.firstOrNull { it.words.size == 2 && it.words[0].uppercase() == first && it.words[1].uppercase() == second && words.size >= 2 }
            ?: catalog.commands.firstOrNull { it.words.size == 1 && it.words[0].uppercase() == first }
    }

    /** Prefix matches first (case-insensitive), then words containing what's typed. */
    private fun rank(items: List<CompletionItem>, typed: String): List<CompletionItem> {
        if (typed.isEmpty()) return items
        val wanted = typed.lowercase()
        return items
            .mapNotNull { item ->
                val label = item.label.lowercase()
                when {
                    label == wanted -> 0
                    label.startsWith(wanted) -> 1
                    wanted.length >= 2 && label.contains(wanted) -> 2
                    else -> null
                }?.let { it to item }
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** As the console splits arguments: quoted when it has spaces, quotes or nothing at all. */
    fun quoteArgument(value: String): String =
        if (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' }) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // region Reading the line

    private class Token(val start: Int, val end: Int, val raw: String, val value: String, val quote: Char?)

    /** The arguments as the console splits them, with where each starts; an unclosed quote runs to the end. */
    private fun tokenize(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val start = i
            val value = StringBuilder()
            var quote: Char? = null
            while (i < line.length) {
                val c = line[i]
                when {
                    quote == null && c.isWhitespace() -> break
                    quote == null && (c == '"' || c == '\'') -> quote = c
                    quote != null && c == quote -> quote = null
                    quote == '"' && c == '\\' && i + 1 < line.length -> {
                        i++
                        value.append(line[i])
                    }
                    else -> value.append(c)
                }
                i++
            }
            // [Token.quote] is the quote still open at the end, which the caret is inside.
            tokens += Token(start, i, line.substring(start, i), value.toString(), quote)
        }
        return tokens
    }

    private fun tokenEnd(text: String, caret: Int, quote: Char?): Int {
        var end = caret
        if (quote != null) {
            while (end < text.length && text[end] != quote) end++
            return if (end < text.length) end + 1 else end
        }
        while (end < text.length && !text[end].isWhitespace()) end++
        return end
    }

    // endregion

    private const val MaxKeys = 200
    private val Separators = setOf(':', '.', '/', '|', '#')
}

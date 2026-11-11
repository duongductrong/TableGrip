package dev.tablegrip.core.completion

import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.dialect.SqlDialect

/**
 * What a record store's console completes from, loaded by the host as it's asked for: null means "not loaded yet" (ask
 * again when it arrives), an empty list that there is nothing.
 */
interface ConsoleCatalog {
    /** The names of the containers under [parent]: keyspaces, then their tables; indices; buckets, then measurements. */
    fun containers(parent: List<String>): List<String>?

    /** The fields of [container]: a table's columns, an index's mapped fields, a class's properties. */
    fun fields(container: List<String>): List<String>?

    /** A graph's names: labels, relationship types or property keys. */
    fun graph(kind: GraphNames): List<String>? = null

    object Empty : ConsoleCatalog {
        override fun containers(parent: List<String>) = emptyList<String>()

        override fun fields(container: List<String>) = emptyList<String>()

        override fun graph(kind: GraphNames) = emptyList<String>()
    }
}

enum class GraphNames { Labels, RelationshipTypes, PropertyKeys }

/**
 * Completion for one console language. [isRead] is set for a read-only profile, and is the engine's own test of whether
 * a statement only reads: suggestions that would start a write are left out.
 */
fun interface StatementCompleter {
    fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult

    companion object {
        /** The completer for an engine's console, by its family; null for the engines whose console has none. */
        fun of(family: EngineFamily): StatementCompleter? =
            when (family) {
                EngineFamily.Cassandra -> CqlCompleter
                EngineFamily.Neo4j -> CypherCompleter
                EngineFamily.InfluxDB -> FluxCompleter
                EngineFamily.Prometheus -> PromQlCompleter
                EngineFamily.TDengine, EngineFamily.InfluxDB3 -> SqlStatementCompleter(SqlDialect.Generic, nested = true)
                EngineFamily.SqliteHttp -> SqlStatementCompleter(SqlDialect.SQLite, nested = false)
                EngineFamily.DynamoDB -> SqlStatementCompleter(SqlDialect.Generic, nested = false)
                else -> HttpEndpoints.of(family)?.let(::HttpConsoleCompleter)
            }
    }
}

/**
 * SQL consoles of record stores (TDengine, InfluxDB 3, SQLite over HTTP, DynamoDB's PartiQL): the SQL editor's completer,
 * over the store's containers: databases and their tables when [nested], else tables at the top.
 */
class SqlStatementCompleter(dialect: SqlDialect, private val nested: Boolean) : StatementCompleter {
    private val sql = SqlCompleter(dialect)

    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val result = sql.complete(text, caret, Schema(catalog, nested))
        if (isRead == null) return result
        // Of the statements, only the ones that read.
        return result.copy(items = result.items.filter { it.kind != CompletionKind.Keyword || it.label.substringBefore(' ') !in StatementVerbs || isRead(it.label) })
    }

    private companion object {
        val StatementVerbs = setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE", "BEGIN", "COMMIT", "ROLLBACK", "EXPLAIN", "SHOW", "DESCRIBE")
    }

    private class Schema(private val catalog: ConsoleCatalog, private val nested: Boolean) : SchemaProvider {
        override val defaultNamespace: String? = null

        override fun namespaces(): List<String> = if (nested) catalog.containers(emptyList()).orEmpty() else emptyList()

        override fun tables(namespace: String?): List<TableEntry>? =
            when {
                namespace == null -> if (nested) emptyList() else catalog.containers(emptyList())?.map { TableEntry(it) }
                nested -> catalog.containers(listOf(namespace))?.map { TableEntry(it) }
                else -> emptyList()
            }

        override fun columns(namespace: String?, table: String): List<ColumnEntry>? =
            catalog.fields(listOfNotNull(namespace, table))?.map { ColumnEntry(it, "") }

        override fun relations(namespace: String?, table: String): List<Relation> = emptyList()
    }
}

/** What the statement completers share: finding the word at the caret, and ranking. */
internal object Completions {
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** The start of the word the caret is in or at the end of. */
    fun wordStart(text: String, caret: Int, word: (Char) -> Boolean = ::isWordChar): Int {
        var i = caret
        while (i > 0 && word(text[i - 1])) i--
        return i
    }

    fun wordEnd(text: String, caret: Int, word: (Char) -> Boolean = ::isWordChar): Int {
        var i = caret
        while (i < text.length && word(text[i])) i++
        return i
    }

    /** Keywords insert a trailing space, so the next word can follow straight away. */
    fun keywords(words: Collection<String>, detail: String? = null): List<CompletionItem> = words.map { CompletionItem(it, "$it ", CompletionKind.Keyword, detail) }

    fun functions(names: Collection<String>, detail: String = "function"): List<CompletionItem> =
        names.map { CompletionItem("$it()", "$it()", CompletionKind.Function, detail, caretOffset = it.length + 1) }

    /**
     * Prefix matches first, then word-initial matches (`uid` → user_id), then substrings; the given order breaks ties.
     * Typing keywords in lower case keeps them lower case.
     */
    fun rank(items: List<CompletionItem>, prefix: String, max: Int = 80): List<CompletionItem> {
        val distinct = items.distinctBy { it.kind to it.label }
        if (prefix.isEmpty()) return distinct.take(max)
        val lower = prefix.lowercase()
        val lowerKeywords = prefix.any { it.isLetter() } && prefix == lower
        return distinct
            .mapIndexedNotNull { index, item ->
                val label = item.label.lowercase().trimStart('$', ':', '"', '`', '/')
                val score =
                    when {
                        label == lower -> 0
                        label.startsWith(lower) -> 1
                        matchesWordStarts(label, lower) -> 2
                        label.split('_', ' ', '.', '-').any { it.startsWith(lower) } -> 3
                        lower.length >= 2 && lower in label -> 4
                        else -> return@mapIndexedNotNull null
                    }
                val shown = if (item.kind == CompletionKind.Keyword && lowerKeywords && item.label.none { it.isLowerCase() }) item.copy(insertText = item.insertText.lowercase()) else item
                Triple(score, index, shown)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .take(max)
    }

    private fun matchesWordStarts(label: String, query: String): Boolean {
        val words = label.split('_', ' ', '.', '-').filter { it.isNotEmpty() }
        fun match(word: Int, offset: Int, q: Int): Boolean {
            if (q == query.length) return true
            if (word >= words.size) return false
            val current = words[word]
            if (offset < current.length && current[offset] == query[q] && match(word, offset + 1, q + 1)) return true
            return (word + 1 until words.size).any { next -> words[next].first() == query[q] && match(next, 1, q + 1) }
        }
        return words.isNotEmpty() && words[0].first() == query[0] && match(0, 1, 1)
    }
}

/** A lexical token of CQL or Cypher, enough to tell where the caret is. */
internal data class Lexeme(val start: Int, val end: Int, val type: Type, val text: String) {
    enum class Type { Word, Quoted, String, Number, Punct, Comment }

    val upper: String
        get() = if (type == Type.Word) text.uppercase() else text
}

/**
 * Splits [text] into tokens. Single quotes delimit strings; double quotes too when [doubleQuotedStrings] (Cypher), else
 * they quote names as backticks do. Comments are `--` and `//` to the end of the line, and `/* … */`. An unclosed string
 * or comment runs to the end.
 */
internal fun lex(text: String, doubleQuotedStrings: Boolean): List<Lexeme> {
    val out = ArrayList<Lexeme>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val start = i
        when {
            c.isWhitespace() -> {
                i++
                continue
            }
            c == '-' && text.startsWith("--", i) || c == '/' && text.startsWith("//", i) -> {
                i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                out += Lexeme(start, i, Lexeme.Type.Comment, text.substring(start, i))
            }
            c == '/' && text.startsWith("/*", i) -> {
                i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                out += Lexeme(start, i, Lexeme.Type.Comment, text.substring(start, i))
            }
            c == '\'' || c == '"' || c == '`' -> {
                i++
                while (i < text.length && text[i] != c) {
                    if (text[i] == '\\' && c != '`') i++
                    i++
                }
                i = (i + 1).coerceAtMost(text.length)
                val type = if (c == '`' || c == '"' && !doubleQuotedStrings) Lexeme.Type.Quoted else Lexeme.Type.String
                out += Lexeme(start, i, type, text.substring(start, i))
            }
            c.isDigit() -> {
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                out += Lexeme(start, i, Lexeme.Type.Number, text.substring(start, i))
            }
            Completions.isWordChar(c) || c == '$' -> {
                i++
                while (i < text.length && Completions.isWordChar(text[i])) i++
                out += Lexeme(start, i, Lexeme.Type.Word, text.substring(start, i))
            }
            else -> {
                i++
                out += Lexeme(start, i, Lexeme.Type.Punct, c.toString())
            }
        }
    }
    return out
}

/** Whether [token] is a string, quoted name or comment the caret sits inside (not just after its closing quote). */
internal fun Lexeme.encloses(caret: Int, text: String): Boolean {
    if (caret <= start) return false
    return when (type) {
        Lexeme.Type.Comment -> caret < end || end == text.length && (this.text.startsWith("--") || this.text.startsWith("//") || !this.text.endsWith("*/"))
        Lexeme.Type.String, Lexeme.Type.Quoted -> caret < end || end - start < 2 || text[end - 1] != text[start]
        else -> false
    }
}

/** The bounds of the `;`-separated statement around [caret], judged by [tokens] so that `;` in strings don't count. */
internal fun statementBounds(text: String, tokens: List<Lexeme>, caret: Int): IntRange {
    var start = 0
    var end = text.length
    for (t in tokens) {
        if (t.type != Lexeme.Type.Punct || t.text != ";") continue
        if (t.end <= caret) start = t.end
        else if (t.start >= caret) {
            end = t.start
            break
        }
    }
    return start until end
}

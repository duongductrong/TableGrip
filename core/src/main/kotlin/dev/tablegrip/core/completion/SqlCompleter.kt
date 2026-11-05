package dev.tablegrip.core.completion

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.sql.SqlKeywords
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.Token
import dev.tablegrip.core.sql.TokenType

enum class CompletionKind { Keyword, Schema, Table, View, Column, Alias, Function, Join, Value, Operator }

/**
 * One suggestion. [insertText] replaces the typed prefix; the caret ends [caretOffset] characters into it (inside the
 * parentheses of a function, for example).
 */
data class CompletionItem(
    val label: String,
    val insertText: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val caretOffset: Int = insertText.length,
)

/** A spot where a literal value is expected, e.g. `status = 'pa|`, so the host can suggest values from the data. */
data class ValueContext(val namespace: String?, val table: String, val column: String, val prefix: String, val quoted: Boolean)

/**
 * Suggestions for the caret. Insert over [replaceStart, replaceEnd). [pendingTables] lists tables whose columns were
 * needed but aren't loaded yet; load them and complete again.
 */
data class CompletionResult(
    val replaceStart: Int,
    val replaceEnd: Int,
    val prefix: String,
    val items: List<CompletionItem>,
    val value: ValueContext? = null,
    val pendingTables: Set<Pair<String?, String>> = emptySet(),
) {
    companion object {
        fun empty(caret: Int) = CompletionResult(caret, caret, "", emptyList())
    }
}

data class TableEntry(val name: String, val isView: Boolean = false)

data class ColumnEntry(val name: String, val type: String, val primaryKey: Boolean = false)

/** A foreign key: [fromTable].[fromColumns] references [toTable].[toColumns]. */
data class Relation(val fromTable: String, val fromColumns: List<String>, val toTable: String, val toColumns: List<String>)

/** Read-only view of a database's schema for completion; null means "not loaded yet". */
interface SchemaProvider {
    /** The namespace unqualified names resolve to (PostgreSQL's `public`, the MySQL database, SQLite's `main`). */
    val defaultNamespace: String?

    fun namespaces(): List<String>

    fun tables(namespace: String?): List<TableEntry>?

    fun columns(namespace: String?, table: String): List<ColumnEntry>?

    /** Foreign keys from and to [table]. */
    fun relations(namespace: String?, table: String): List<Relation>?

    object Empty : SchemaProvider {
        override val defaultNamespace: String? = null

        override fun namespaces() = emptyList<String>()

        override fun tables(namespace: String?) = emptyList<TableEntry>()

        override fun columns(namespace: String?, table: String): List<ColumnEntry>? = null

        override fun relations(namespace: String?, table: String): List<Relation>? = null
    }
}

/**
 * Context-aware SQL completion: tables after FROM/JOIN (with join conditions from foreign keys), columns of the tables
 * and aliases in scope, `alias.` and `schema.` members, functions and keywords, ranked by how well they match.
 */
class SqlCompleter(private val dialect: SqlDialect) {
    private val lexer = SqlLexer(dialect.syntax)
    private val splitter = SqlSplitter(dialect.syntax)

    /** The tables and views one statement reads or writes, as (namespace, name); CTEs are left out. */
    fun tablesIn(statement: String): List<Pair<String?, String>> {
        val scope = Scope.parse(statement, lexer.tokenize(statement), dialect, SchemaProvider.Empty)
        return scope.tables
            .filter { table -> table.cteColumns == null && scope.ctes.none { it.equals(table.name, ignoreCase = true) } }
            .map { it.namespace to it.name }
            .distinctBy { (namespace, name) -> "${namespace?.lowercase()}.${name.lowercase()}" }
    }

    fun complete(text: String, caret: Int, schema: SchemaProvider, maxItems: Int = 60): CompletionResult {
        val (scriptStart, scriptEnd) = statementBounds(text, caret)
        val script = text.substring(scriptStart, scriptEnd)
        val scriptTokens = lexer.tokenize(script)
        // Statements without a delimiter, separated by a blank line, are still separate statements.
        val (subStart, subEnd) = blankLineBounds(script, scriptTokens, caret - scriptStart)
        val start = scriptStart + subStart
        val statement = text.substring(start, scriptStart + subEnd)
        val local = caret - start
        val tokens = if (subStart == 0 && subEnd == script.length) scriptTokens else lexer.tokenize(statement)

        // No completion inside comments or parameters (string literals are value contexts, handled below).
        val atToken = tokens.firstOrNull { local > it.start && local < it.end || local == it.end && it.end == statement.length && it.type == TokenType.Comment }
        if (atToken?.type == TokenType.Comment) return CompletionResult.empty(caret)
        if (tokens.any { it.type == TokenType.Parameter && local > it.start && local <= it.end }) return CompletionResult.empty(caret)

        // The word at the caret — also when the caret sits inside it (`u.na|me`): the typed part filters, the whole word is
        // replaced.
        val word = tokens.lastOrNull { local > it.start && local <= it.end && it.type in WordTokens }
        val inString = tokens.firstOrNull { it.type == TokenType.String && local > it.start && (local < it.end || !isClosedString(statement, it)) }
        val prefixStart = inString?.start ?: word?.start ?: local
        val replaceEnd = if (inString == null && word != null && (word.type != TokenType.QuotedIdentifier || isClosedString(statement, word))) word.end else local
        val rawPrefix = statement.substring(prefixStart, local)
        val prefix = rawPrefix.trimStart('"', '`', '\'', '[')
        val before = tokens.filter { it.end <= prefixStart && it.type != TokenType.Whitespace && it.type != TokenType.Comment }
        // Only the tables of this (sub)query and the queries around it are in scope at the caret.
        val scope = Scope.parse(statement, tokens, dialect, schema).visibleAt(statement, tokens, prefixStart)
        val pending = mutableSetOf<Pair<String?, String>>()

        fun result(items: List<CompletionItem>, value: ValueContext? = null) =
            CompletionResult(start + prefixStart, start + replaceEnd, rawPrefix, rank(items, prefix).take(maxItems), value, pending + scope.pending)

        // Values: `col = 'x|`, `col IN ('a', |`, `col LIKE |`. Inside a string only values make sense; outside, the
        // right-hand side may just as well be another column (`o.user_id = u.id`), so both are offered.
        val value = valueContext(statement, before, scope, inString != null, prefix)
        if (inString != null) return if (value != null) result(emptyList(), value) else CompletionResult.empty(caret)

        // Qualified: `alias.|`, `schema.|`, `schema.table.|`.
        val qualifier = qualifierBefore(statement, before, prefixStart)
        if (qualifier.isNotEmpty()) return result(memberItems(qualifier, scope, schema, pending))

        val context = contextOf(statement, before)
        val items =
            when (context) {
                Context.StatementStart -> keywords(StatementStarts)
                Context.Table -> tableItems(schema, scope)
                Context.Join -> joinItems(schema, scope, pending) + tableItems(schema, scope)
                Context.AfterTable -> keywords(AfterTableKeywords)
                Context.InsertColumns -> scope.tables.firstOrNull()?.let { columnItems(it, schema, pending, qualify = false) }.orEmpty()
                Context.Expression ->
                    scope.tables.flatMap { columnItems(it, schema, pending, qualify = scope.tables.size > 1 && it.alias != null) } +
                        scope.tables.map { CompletionItem(it.alias ?: it.name, it.alias ?: quote(it.name), CompletionKind.Alias, it.name.takeIf { _ -> it.alias != null }) } +
                        functions() + keywords(ExpressionKeywords)
                Context.AfterExpression ->
                    Operators.map { CompletionItem(it, "$it ", CompletionKind.Operator) } + keywords(OperatorKeywords + ClauseKeywords)
                Context.AfterPredicate -> keywords(PredicateKeywords + ClauseKeywords)
                Context.Nothing -> emptyList()
                Context.Other -> keywords(SqlKeywords.all.toList())
                is Context.Keywords -> keywords(context.words)
            }
        // A word typed at the start of a line after a finished clause may just as well begin the next statement.
        val lineStart = statement.lastIndexOf('\n', prefixStart - 1) + 1
        val startsLine = before.isNotEmpty() && statement.substring(lineStart, prefixStart).isBlank()
        val more = if (startsLine && context !is Context.Keywords && context != Context.Nothing && context != Context.StatementStart) keywords(StatementStarts) else emptyList()
        return result((items + more).distinctBy { it.kind to it.insertText }, value)
    }

    // region Context

    private sealed interface Context {
        object StatementStart : Context

        object Table : Context

        object Join : Context

        object AfterTable : Context

        object InsertColumns : Context

        object Expression : Context

        object AfterExpression : Context

        /** After a complete condition or sort key (`IS NULL`, `DESC`): only connectives and clauses. */
        object AfterPredicate : Context

        /** A name is being made up (`AS |`, `LIMIT |`); nothing sensible to suggest. */
        object Nothing : Context

        object Other : Context

        class Keywords(val words: List<String>) : Context
    }

    private fun contextOf(sql: String, before: List<Token>): Context {
        if (before.isEmpty()) return Context.StatementStart
        val last = before.last()
        val lastWord = last.upper(sql)
        val previous = before.getOrNull(before.size - 2)?.upper(sql)

        if (last.type == TokenType.Keyword) {
            return when (lastWord) {
                "FROM", "INTO", "UPDATE", "DESCRIBE", "TRUNCATE" -> Context.Table
                "TABLE" -> if (before.first().upper(sql) == "CREATE") Context.Keywords(listOf("IF NOT EXISTS")) else Context.Table
                "JOIN" -> Context.Join
                "SELECT", "WHERE", "AND", "OR", "ON", "BY", "HAVING", "SET", "WHEN", "THEN", "ELSE", "DISTINCT", "CASE", "RETURNING", "LIKE", "ILIKE", "BETWEEN" ->
                    Context.Expression
                "NOT" -> if (previous == "IS") Context.Keywords(listOf("NULL", "TRUE", "FALSE", "DISTINCT FROM")) else Context.Expression
                "IS" -> Context.Keywords(listOf("NULL", "NOT NULL", "TRUE", "FALSE", "NOT", "DISTINCT FROM"))
                "ORDER", "GROUP", "PARTITION" -> Context.Keywords(listOf("BY"))
                "LEFT", "RIGHT", "FULL" -> Context.Keywords(listOf("JOIN", "OUTER JOIN"))
                "INNER", "CROSS", "OUTER", "NATURAL" -> Context.Keywords(listOf("JOIN"))
                "UNION", "INTERSECT", "EXCEPT" -> Context.Keywords(listOf("SELECT", "ALL"))
                "ALL" -> if (previous in setOf("UNION", "INTERSECT", "EXCEPT")) Context.Keywords(listOf("SELECT")) else Context.Expression
                "INSERT" -> Context.Keywords(listOf("INTO"))
                "DELETE" -> Context.Keywords(listOf("FROM"))
                "CREATE" -> Context.Keywords(listOf("TABLE", "VIEW", "INDEX", "UNIQUE INDEX", "SCHEMA", "OR REPLACE VIEW", "MATERIALIZED VIEW", "TRIGGER", "FUNCTION"))
                "ALTER", "DROP" -> Context.Keywords(listOf("TABLE", "VIEW", "INDEX", "SCHEMA", "MATERIALIZED VIEW", "TRIGGER", "FUNCTION"))
                "WITH" -> Context.Keywords(listOf("RECURSIVE"))
                "NULL", "TRUE", "FALSE", "ASC", "DESC" -> Context.AfterPredicate
                "END" -> Context.AfterExpression
                "AS", "LIMIT", "OFFSET", "IN", "EXISTS", "VALUES", "RECURSIVE" -> Context.Nothing
                else -> Context.Other
            }
        }
        if (last.type == TokenType.Operator) {
            when (last.text(sql)) {
                "(" -> {
                    // INSERT INTO t (|
                    if (isInsertColumnList(sql, before, before.lastIndex)) return Context.InsertColumns
                    return Context.Expression
                }
                "," -> {
                    // Continue the clause the comma belongs to, or the parenthesized list it sits in.
                    val clause = enclosingClause(sql, before.dropLast(1))
                    return when {
                        clause is Int -> if (isInsertColumnList(sql, before, clause)) Context.InsertColumns else Context.Expression
                        clause == "FROM" -> Context.Table
                        else -> Context.Expression
                    }
                }
                ")" -> {
                    // INSERT INTO t (a, b) |
                    val open = matchingOpen(sql, before, before.lastIndex)
                    if (open != null && isInsertColumnList(sql, before, open)) return Context.Keywords(listOf("VALUES", "SELECT"))
                    return Context.AfterExpression
                }
                // `SELECT * |`, `t.* |`: a finished select item, not a multiplication.
                "*" -> if (previous == "SELECT" || previous == "," || previous == ".") return Context.AfterExpression
            }
            return Context.Expression
        }
        // After a name, number or literal: "FROM users |" or "WHERE id = 1 |".
        return when (val clause = enclosingClause(sql, before)) {
            "TABLE" ->
                if (before.first().upper(sql) == "ALTER") Context.Keywords(listOf("ADD COLUMN", "DROP COLUMN", "ALTER COLUMN", "RENAME COLUMN", "RENAME TO", "ADD CONSTRAINT"))
                else Context.AfterTable
            "FROM", "JOIN", "UPDATE" -> Context.AfterTable
            "INTO" -> Context.Keywords(listOf("VALUES", "SELECT", "DEFAULT VALUES"))
            "LIMIT", "OFFSET" -> Context.Keywords(listOf("OFFSET", "UNION", "UNION ALL"))
            null, is Int -> if (before.size == 1) Context.Other else Context.AfterExpression
            else -> Context.AfterExpression
        }
    }

    /**
     * The clause keyword the end of [before] belongs to, skipping parenthesized groups: a [String] such as "FROM", the
     * index of the unclosed `(` it sits in as an [Int], or null.
     */
    private fun enclosingClause(sql: String, before: List<Token>): Any? {
        var depth = 0
        for (k in before.indices.reversed()) {
            val t = before[k]
            when (t.text(sql)) {
                ")" -> depth++
                "(" -> if (depth == 0) return k else depth--
                else -> if (depth == 0 && t.type == TokenType.Keyword && t.upper(sql) in ClauseWords) return t.upper(sql)
            }
        }
        return null
    }

    /** Index of the `(` matching the `)` at [close]. */
    private fun matchingOpen(sql: String, before: List<Token>, close: Int): Int? {
        var depth = 0
        for (k in close downTo 0) {
            when (before[k].text(sql)) {
                ")" -> depth++
                "(" -> if (--depth == 0) return k
            }
        }
        return null
    }

    /** Whether the `(` at [open] starts the column list of `INSERT INTO t (`. */
    private fun isInsertColumnList(sql: String, before: List<Token>, open: Int): Boolean {
        var k = open - 1
        if (before.getOrNull(k)?.type.let { it != TokenType.Identifier && it != TokenType.QuotedIdentifier }) return false
        // schema.table
        while (before.getOrNull(k - 1)?.text(sql) == "." && before.getOrNull(k - 2) != null) k -= 2
        return before.getOrNull(k - 1)?.upper(sql) == "INTO"
    }

    /** `a.b.|` → ["a", "b"]: the dotted names directly before the prefix; empty when it isn't qualified. */
    private fun qualifierBefore(sql: String, before: List<Token>, prefixStart: Int): List<String> {
        val parts = mutableListOf<String>()
        var end = prefixStart
        var i = before.lastIndex
        while (i >= 1) {
            val dot = before[i]
            val name = before[i - 1]
            val adjacent = dot.end == end && name.end == dot.start
            val isName = name.type == TokenType.Identifier || name.type == TokenType.QuotedIdentifier || name.type == TokenType.Keyword
            if (dot.text(sql) != "." || !adjacent || !isName) break
            parts.add(0, unquote(name.text(sql)))
            end = name.start
            i -= 2
        }
        return parts
    }

    private fun valueContext(sql: String, before: List<Token>, scope: Scope, inString: Boolean, prefix: String): ValueContext? {
        // Walk back over an `IN ('a', ` list to the IN keyword; other lists (`SET a = 'x', |`) aren't values.
        var i = before.lastIndex
        var inList = false
        while (i >= 0) {
            val t = before[i]
            val text = t.text(sql)
            if (text == "(" && before.getOrNull(i - 1)?.upper(sql) == "IN") {
                i -= 1
                inList = true
                break
            }
            if (t.type == TokenType.String || t.type == TokenType.Number || text == ",") {
                i--
                continue
            }
            break
        }
        if (!inList && i != before.lastIndex) return null
        val op = before.getOrNull(i) ?: return null
        val opText = op.upper(sql)
        if (opText !in setOf("=", "<>", "!=", "IN", "LIKE", "ILIKE", ">", "<", ">=", "<=")) return null
        val columnToken = before.getOrNull(i - 1)?.takeIf { it.type == TokenType.Identifier || it.type == TokenType.QuotedIdentifier } ?: return null
        val column = unquote(columnToken.text(sql))
        val qualifier = before.getOrNull(i - 2)?.takeIf { it.text(sql) == "." }?.let { before.getOrNull(i - 3)?.text(sql)?.let(::unquote) }
        val table = (if (qualifier != null) scope.resolve(qualifier) else scope.tables.singleOrNull() ?: scope.tables.firstOrNull { scope.hasColumn(it, column) })
            ?: return null
        if (table.cteColumns != null) return null
        return ValueContext(table.namespace, table.name, column, prefix, quoted = inString)
    }

    /**
     * The statement being edited. One without a delimiter runs on to the next statement (or the end), so trailing
     * whitespace after `FROM ` still belongs to it; after a `;` a new statement begins.
     */
    private fun statementBounds(text: String, caret: Int): Pair<Int, Int> {
        val statements = splitter.split(text)
        statements.forEachIndexed { index, statement ->
            val terminated = statement.delimiterEnd > statement.end
            val end = if (terminated) statement.end else statements.getOrNull(index + 1)?.start ?: text.length
            if (caret in statement.start..end) return statement.start to end
        }
        val start = statements.lastOrNull { it.delimiterEnd <= caret }?.delimiterEnd ?: 0
        val end = statements.firstOrNull { it.start > caret }?.start ?: text.length
        return start to maxOf(end, caret)
    }

    /**
     * Narrows [script] to the statement around [caret] when several are written one after another without `;`, the
     * way consoles are often used: a statement keyword (SELECT, UPDATE, …) starting a line after a blank line begins a
     * new one, unless the text before it clearly continues (`UNION`, `AS (...)`, an operator, a CREATE ... BEGIN body).
     */
    private fun blankLineBounds(script: String, tokens: List<Token>, caret: Int): Pair<Int, Int> {
        var start = 0
        var depth = 0
        var blocks = 0
        var previous: Token? = null
        var isCreate = false
        for (t in tokens) {
            if (t.type == TokenType.Whitespace || t.type == TokenType.Comment) continue
            val word = t.upper(script)
            val prev = previous
            if (prev != null && depth == 0 && blocks == 0 && t.type == TokenType.Keyword && word in StatementKeywords) {
                val blank = BlankLine.find(script.substring(prev.end, t.start))
                val prevWord = prev.upper(script)
                val continues =
                    prev.type == TokenType.Operator && prevWord != ")" || prevWord in ContinuingWords || prevWord == ")" && (word == "SELECT" || word == "WITH")
                if (blank != null && !continues) {
                    val boundary = prev.end + blank.range.last + 1
                    if (caret < boundary) return start to boundary
                    start = boundary
                    isCreate = word == "CREATE"
                }
            }
            if (prev == null) isCreate = word == "CREATE"
            when {
                word == "(" -> depth++
                word == ")" -> if (depth > 0) depth--
                isCreate && (word == "BEGIN" || word == "CASE") -> blocks++
                isCreate && word == "END" -> if (blocks > 0) blocks--
            }
            previous = t
        }
        // The caret after a blank line that ends the text: a statement of its own is starting there.
        val tail = previous?.let { BlankLine.findAll(script.substring(it.end, caret.coerceIn(it.end, script.length))).lastOrNull() }
        if (tail != null && previous!!.end <= caret && blocks == 0 && depth == 0 && !tokens.any { it.start >= caret && it.type != TokenType.Whitespace && it.type != TokenType.Comment }) {
            return previous.end + tail.range.last + 1 to script.length
        }
        return start to script.length
    }

    private fun isClosedString(sql: String, token: Token): Boolean {
        val text = token.text(sql)
        return text.length >= 2 && text.last() == closingQuote(text.first())
    }

    private fun closingQuote(open: Char) = if (open == '[') ']' else open

    // endregion

    // region Items

    private fun tableItems(schema: SchemaProvider, scope: Scope): List<CompletionItem> {
        val default = schema.defaultNamespace
        val local = schema.tables(default).orEmpty().map { table(it, qualified = null) }
        val namespaces = schema.namespaces().filter { it != default }.map { CompletionItem(it, quote(it) + ".", CompletionKind.Schema, "schema") }
        val ctes = scope.ctes.map { CompletionItem(it, it, CompletionKind.Table, "WITH") }
        return ctes + local + namespaces
    }

    private fun table(entry: TableEntry, qualified: String?): CompletionItem =
        CompletionItem(
            entry.name,
            (qualified?.let { quote(it) + "." } ?: "") + quote(entry.name),
            if (entry.isView) CompletionKind.View else CompletionKind.Table,
            qualified ?: if (entry.isView) "view" else "table",
        )

    private fun memberItems(qualifier: List<String>, scope: Scope, schema: SchemaProvider, pending: MutableSet<Pair<String?, String>>): List<CompletionItem> {
        if (qualifier.size == 1) {
            val name = qualifier.single()
            // An explicit alias wins, then a schema, then a table name.
            val aliased = scope.tables.firstOrNull { it.alias.equals(name, ignoreCase = true) }
            val namespace = schema.namespaces().firstOrNull { it.equals(name, ignoreCase = true) }
            if (aliased == null && namespace != null) return schema.tables(namespace).orEmpty().map { table(it, qualified = null) }
            (aliased ?: scope.resolve(name))?.let { return columnItems(it, schema, pending, qualify = false) + CompletionItem("*", "*", CompletionKind.Column, "all columns") }
            // A table not in FROM yet: still offer its columns.
            schema.tables(schema.defaultNamespace)?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                return columnItems(ScopeTable(schema.defaultNamespace, it.name, null), schema, pending, qualify = false)
            }
            return emptyList()
        }
        val (ns, tableName) = qualifier.takeLast(2)
        return columnItems(ScopeTable(ns, tableName, null), schema, pending, qualify = false)
    }

    private fun columnItems(table: ScopeTable, schema: SchemaProvider, pending: MutableSet<Pair<String?, String>>, qualify: Boolean): List<CompletionItem> {
        table.cteColumns?.let { cols -> return cols.map { CompletionItem(it, quote(it), CompletionKind.Column, table.name) } }
        val columns = schema.columns(table.namespace, table.name)
        if (columns == null) {
            pending += table.namespace to table.name
            return emptyList()
        }
        val owner = table.alias ?: table.name
        return columns.map { column ->
            CompletionItem(
                label = column.name,
                insertText = (if (qualify) "${quote(owner)}." else "") + quote(column.name),
                kind = CompletionKind.Column,
                detail = "${column.type.lowercase()}${if (column.primaryKey) " · PK" else ""} · $owner",
            )
        }
    }

    /** `JOIN orders o ON o.user_id = u.id` for every foreign key between an in-scope table and another one. */
    private fun joinItems(schema: SchemaProvider, scope: Scope, pending: MutableSet<Pair<String?, String>>): List<CompletionItem> {
        val usedAliases = scope.tables.mapNotNull { it.alias }.toMutableSet()
        return scope.tables.flatMap { inScope ->
            val relations = schema.relations(inScope.namespace, inScope.name)
            if (relations == null) {
                pending += inScope.namespace to inScope.name
                return@flatMap emptyList()
            }
            relations.mapNotNull { relation ->
                val (other, otherColumns, ownColumns) =
                    when {
                        relation.fromTable.equals(inScope.name, true) -> Triple(relation.toTable, relation.toColumns, relation.fromColumns)
                        relation.toTable.equals(inScope.name, true) -> Triple(relation.fromTable, relation.fromColumns, relation.toColumns)
                        else -> return@mapNotNull null
                    }
                if (scope.tables.any { it.name.equals(other, true) }) return@mapNotNull null
                val alias = if (inScope.alias != null) aliasFor(other, usedAliases) else null
                val otherRef = alias ?: other
                val ownRef = inScope.alias ?: inScope.name
                val condition = otherColumns.zip(ownColumns).joinToString(" AND ") { (o, s) -> "${quote(otherRef)}.${quote(o)} = ${quote(ownRef)}.${quote(s)}" }
                val text = quote(other) + (alias?.let { " $it" } ?: "") + " ON " + condition
                CompletionItem(text, text, CompletionKind.Join, "foreign key")
            }
        }
    }

    /** First letters of the words in a table name, e.g. order_items → oi; numbered if taken. */
    private fun aliasFor(table: String, used: MutableSet<String>): String {
        val base = table.split('_', '-').filter { it.isNotEmpty() }.joinToString("") { it.first().lowercase() }.ifEmpty { "t" }
        var alias = base
        var n = 1
        while (alias in used || alias.uppercase() in SqlKeywords.all) alias = base + n++
        used += alias
        return alias
    }

    private fun functions(): List<CompletionItem> =
        (CommonFunctions + dialect.functions).map { CompletionItem("$it()", "$it()", CompletionKind.Function, "function", caretOffset = it.length + 1) }

    /** Keywords insert a trailing space, so the next word can follow straight away. */
    private fun keywords(words: Collection<String>): List<CompletionItem> = words.map { CompletionItem(it, "$it ", CompletionKind.Keyword) }

    // endregion

    // region Ranking

    /** Prefix matches first, then word-initial matches (`uid` → user_id), then substrings; context order breaks ties. */
    private fun rank(items: List<CompletionItem>, prefix: String): List<CompletionItem> {
        if (prefix.isEmpty()) return items
        val lower = prefix.lowercase()
        // Typing lower-case keywords keeps them lower-case.
        val lowerKeywords = prefix.any { it.isLetter() } && prefix == prefix.lowercase()
        return items
            .mapIndexedNotNull { index, item ->
                val label = item.label.lowercase()
                val score =
                    when {
                        label == lower -> 0
                        label.startsWith(lower) -> 1
                        matchesWordStarts(label, lower) -> 2
                        label.split('_', ' ', '.').any { it.startsWith(lower) } -> 3
                        lower.length >= 2 && lower in label -> 4
                        else -> return@mapIndexedNotNull null
                    }
                Triple(score, index, if (item.kind == CompletionKind.Keyword && lowerKeywords) item.copy(insertText = item.insertText.lowercase()) else item)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
    }

    /**
     * IntelliJ-style fuzzy match: each typed character continues the current word or starts a later one, so `uid`
     * matches `user_id` and `oi` matches `order_items`. The first character must start the name.
     */
    private fun matchesWordStarts(label: String, query: String): Boolean {
        val words = label.split('_', ' ', '.').filter { it.isNotEmpty() }
        fun match(word: Int, offset: Int, q: Int): Boolean {
            if (q == query.length) return true
            if (word >= words.size) return false
            val current = words[word]
            if (offset < current.length && current[offset] == query[q] && match(word, offset + 1, q + 1)) return true
            return (word + 1 until words.size).any { next -> words[next].first() == query[q] && match(next, 1, q + 1) }
        }
        return words.isNotEmpty() && words[0].first() == query[0] && match(0, 1, 1)
    }

    // endregion

    // region Names

    /** Quotes names that wouldn't survive unquoted: reserved words, odd characters, and (PostgreSQL) upper case. */
    fun quote(name: String): String {
        return if (dialect.plainIdentifier.matches(name) && name.uppercase() !in ReservedWords) name else dialect.quoteIdentifier(name)
    }

    private fun unquote(name: String): String =
        if (name.length >= 2 && (name.first() == '"' || name.first() == '`') && name.last() == name.first()) name.substring(1, name.length - 1) else name

    // endregion

    private companion object {
        val StatementStarts =
            listOf("SELECT", "WITH", "INSERT INTO", "UPDATE", "DELETE FROM", "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "EXPLAIN", "BEGIN", "COMMIT", "ROLLBACK")
        val ClauseWords = setOf("SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "SET", "ON", "RETURNING", "VALUES", "UPDATE", "INTO", "JOIN", "LIMIT", "OFFSET", "TABLE")
        val WordTokens = setOf(TokenType.Identifier, TokenType.Keyword, TokenType.QuotedIdentifier)
        /** Keywords that begin a statement, for telling statements apart that aren't separated by `;`. */
        val StatementKeywords =
            setOf("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE", "EXPLAIN", "SHOW", "DESCRIBE", "PRAGMA", "VACUUM", "ANALYZE", "GRANT", "REVOKE", "CALL", "USE", "BEGIN", "COMMIT", "ROLLBACK")
        /** A statement keyword after one of these continues the statement (`UNION\n\nSELECT`, `AS\n\nSELECT`). */
        val ContinuingWords = setOf("UNION", "ALL", "DISTINCT", "INTERSECT", "EXCEPT", "AS", "AND", "OR", "NOT", "IN", "EXISTS", "THEN", "ELSE", "BEGIN", "EXPLAIN", "ANALYZE", "INTO", "TABLE", "VIEW")
        val BlankLine = Regex("\n[ \t\r]*\n")
        val PredicateKeywords = listOf("AND", "OR")
        val AfterTableKeywords =
            listOf("WHERE", "JOIN", "LEFT JOIN", "INNER JOIN", "RIGHT JOIN", "CROSS JOIN", "ON", "AS", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "SET", "VALUES")
        val Operators = listOf("=", "<>", ">", "<", ">=", "<=")
        val ExpressionKeywords = listOf("NULL", "NOT", "EXISTS", "CASE", "TRUE", "FALSE", "DISTINCT")
        val OperatorKeywords = listOf("AND", "OR", "IN", "IS NULL", "IS NOT NULL", "LIKE", "BETWEEN", "AS", "ASC", "DESC")
        val ClauseKeywords = listOf("FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "UNION", "JOIN")
        val CommonFunctions = listOf("count", "sum", "avg", "min", "max", "coalesce", "lower", "upper", "length", "substring", "trim", "round", "abs", "cast", "nullif")
        val ReservedWords = setOf("SELECT", "FROM", "WHERE", "ORDER", "GROUP", "BY", "TABLE", "USER", "TO", "AND", "OR", "NOT", "NULL", "AS", "ON", "IN", "IS", "JOIN", "LIMIT", "CHECK", "DEFAULT", "PRIMARY", "KEY", "INDEX", "DESC", "ASC", "CASE", "WHEN", "END")
    }
}

private fun Token.upper(sql: String) = text(sql).uppercase()

/** A table referenced by the statement, possibly with an alias; CTEs carry their own column names. */
internal data class ScopeTable(val namespace: String?, val name: String, val alias: String?, val cteColumns: List<String>? = null, val group: Int = -1, val branch: Int = -1)

/** Tables, aliases and CTEs of one statement. */
internal class Scope(val tables: List<ScopeTable>, val ctes: List<String>, private val schema: SchemaProvider) {
    val pending = mutableSetOf<Pair<String?, String>>()

    /**
     * Only the tables a query at [offset] can see: its own and those of the queries around it, not those inside other
     * subqueries or CTE bodies.
     */
    fun visibleAt(sql: String, all: List<Token>, offset: Int): Scope {
        // (group, branch) of every query around the offset: parenthesized groups, and UNION branches within each.
        val path = ArrayDeque(listOf(-1 to -1))
        for (t in all) {
            if (t.end > offset) break
            when (t.upper(sql)) {
                "(" -> path.addLast(t.start to -1)
                ")" -> if (path.size > 1) path.removeLast()
                in SetOperators -> path.addLast(path.removeLast().first to t.start)
            }
        }
        return Scope(tables.filter { (it.group to it.branch) in path }.distinctBy { (it.alias ?: it.name).lowercase() }, ctes, schema)
    }

    fun resolve(name: String): ScopeTable? =
        tables.firstOrNull { it.alias.equals(name, ignoreCase = true) } ?: tables.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun hasColumn(table: ScopeTable, column: String): Boolean =
        table.cteColumns?.any { it.equals(column, true) } ?: schema.columns(table.namespace, table.name)?.any { it.name.equals(column, true) } ?: false

    companion object {
        private val SetOperators = setOf("UNION", "INTERSECT", "EXCEPT")
        private val TableIntroducers = setOf("FROM", "JOIN", "UPDATE", "INTO", "TABLE")
        private val NotAliases =
            setOf("WHERE", "ON", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "GROUP", "ORDER", "HAVING", "LIMIT", "SET", "VALUES", "USING", "UNION", "AS", "NATURAL", "OFFSET", "RETURNING", "WINDOW", "SELECT")

        fun parse(sql: String, all: List<Token>, dialect: SqlDialect, schema: SchemaProvider): Scope {
            val tokens = all.filter { it.type != TokenType.Whitespace && it.type != TokenType.Comment }
            val tables = mutableListOf<ScopeTable>()
            val ctes = mutableListOf<String>()
            val cteColumns = mutableMapOf<String, List<String>>()
            fun name(t: Token) = t.text(sql).let { if (it.length >= 2 && (it[0] == '"' || it[0] == '`')) it.substring(1, it.length - 1) else it }
            fun isName(t: Token?) = t != null && (t.type == TokenType.Identifier || t.type == TokenType.QuotedIdentifier)

            // Token indices of the open parentheses around each token, and the UNION branch within each; tables remember
            // the innermost ones (as offsets).
            val open = ArrayDeque<Int>()
            val branches = ArrayDeque(listOf(-1))
            fun group() = open.lastOrNull()?.let { tokens[it].start } ?: -1
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val word = t.upper(sql)
                if (word == "(") {
                    open.addLast(i)
                    branches.addLast(-1)
                }
                if (word in SetOperators) {
                    branches.removeLast()
                    branches.addLast(t.start)
                }
                if (word == ")") {
                    val start = open.removeLastOrNull()
                    if (branches.size > 1) branches.removeLast()
                    // A derived table: FROM (SELECT a, b ...) t
                    if (start != null && tokens.getOrNull(start + 1)?.upper(sql) == "SELECT" &&
                        (tokens.getOrNull(start - 1)?.upper(sql).let { it == "FROM" || it == "JOIN" || it == "LATERAL" } || tokens.getOrNull(start - 1)?.text(sql) == "," && precededByFrom(sql, tokens, start - 1))
                    ) {
                        val next = tokens.getOrNull(i + 1)
                        val alias =
                            when {
                                next?.upper(sql) == "AS" && isName(tokens.getOrNull(i + 2)) -> name(tokens[i + 2])
                                isName(next) && next!!.upper(sql) !in NotAliases -> name(next)
                                else -> null
                            }
                        if (alias != null) tables += ScopeTable(null, alias, alias, selectListNames(sql, tokens, start + 1), group(), branches.last())
                    }
                }
                // WITH name [(cols)] AS (SELECT a, b ...)
                if ((word == "WITH" || (word == "," && ctes.isNotEmpty())) && isName(tokens.getOrNull(i + 1))) {
                    val cte = name(tokens[i + 1])
                    var j = i + 2
                    if (tokens.getOrNull(j)?.upper(sql) == "AS" && tokens.getOrNull(j + 1)?.text(sql) == "(") {
                        ctes += cte
                        cteColumns[cte] = selectListNames(sql, tokens, j + 2)
                    }
                }
                if (t.type == TokenType.Keyword && word in TableIntroducers || word == "," && precededByFrom(sql, tokens, i)) {
                    var j = i + 1
                    if (!isName(tokens.getOrNull(j)) && tokens.getOrNull(j)?.type != TokenType.Keyword) {
                        i++
                        continue
                    }
                    val first = tokens.getOrNull(j) ?: break
                    if (first.type == TokenType.Keyword && !isName(first) && first.upper(sql) !in setOf("USER")) {
                        i++
                        continue
                    }
                    var namespace: String? = null
                    var tableName = name(first)
                    if (tokens.getOrNull(j + 1)?.text(sql) == ".") {
                        // `schema.` still being typed: not a table yet.
                        if (!isName(tokens.getOrNull(j + 2))) {
                            i++
                            continue
                        }
                        namespace = tableName
                        tableName = name(tokens[j + 2])
                        j += 2
                    }
                    var alias: String? = null
                    val next = tokens.getOrNull(j + 1)
                    if (next?.upper(sql) == "AS" && isName(tokens.getOrNull(j + 2))) alias = name(tokens[j + 2])
                    else if (isName(next) && next!!.upper(sql) !in NotAliases) alias = name(next)
                    tables += ScopeTable(namespace, tableName, alias, cteColumns[tableName], group(), branches.last())
                }
                i++
            }
            return Scope(tables.distinctBy { Triple((it.alias ?: it.name).lowercase(), it.group, it.branch) }, ctes, schema)
        }

        private fun precededByFrom(sql: String, tokens: List<Token>, index: Int): Boolean {
            var depth = 0
            for (k in index - 1 downTo 0) {
                val text = tokens[k].text(sql)
                when {
                    text == ")" -> depth++
                    text == "(" -> if (depth-- == 0) return false
                    depth == 0 && tokens[k].type == TokenType.Keyword -> return tokens[k].upper(sql) == "FROM"
                }
            }
            return false
        }

        /** Output names of `SELECT a, b AS c, t.d` starting at [from]: a, c, d. */
        private fun selectListNames(sql: String, tokens: List<Token>, from: Int): List<String> {
            if (tokens.getOrNull(from)?.upper(sql) != "SELECT") return emptyList()
            val names = mutableListOf<String>()
            var depth = 0
            var last: Token? = null
            var i = from + 1
            while (i < tokens.size) {
                val t = tokens[i]
                val text = t.text(sql)
                when {
                    text == "(" -> depth++
                    text == ")" -> if (depth-- == 0) break
                    depth == 0 && (text == "," || t.upper(sql) == "FROM") -> {
                        last?.let { names += it.text(sql).trim('"', '`') }
                        if (t.upper(sql) == "FROM") return names
                    }
                    depth == 0 && (t.type == TokenType.Identifier || t.type == TokenType.QuotedIdentifier) -> last = t
                }
                i++
            }
            last?.let { names += it.text(sql).trim('"', '`') }
            return names
        }
    }
}

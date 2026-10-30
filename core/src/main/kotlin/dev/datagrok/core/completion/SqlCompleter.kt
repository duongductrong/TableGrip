package dev.datagrok.core.completion

import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.sql.SqlKeywords
import dev.datagrok.core.sql.SqlLexer
import dev.datagrok.core.sql.SqlSplitter
import dev.datagrok.core.sql.Token
import dev.datagrok.core.sql.TokenType

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
            .filter { table -> scope.ctes.none { it.equals(table.name, ignoreCase = true) } }
            .map { it.namespace to it.name }
            .distinctBy { (namespace, name) -> "${namespace?.lowercase()}.${name.lowercase()}" }
    }

    fun complete(text: String, caret: Int, schema: SchemaProvider, maxItems: Int = 60): CompletionResult {
        val (start, end) = statementBounds(text, caret)
        val statement = text.substring(start, end)
        val local = caret - start
        val tokens = lexer.tokenize(statement)

        // No completion inside comments or string literals (value contexts are handled separately below).
        val atToken = tokens.firstOrNull { local > it.start && local < it.end || local == it.end && it.end == statement.length && it.type == TokenType.Comment }
        if (atToken?.type == TokenType.Comment) return CompletionResult.empty(caret)

        val prefixToken = tokens.lastOrNull { it.end == local && (it.type == TokenType.Identifier || it.type == TokenType.Keyword || it.type == TokenType.QuotedIdentifier) }
        val inString = tokens.firstOrNull { it.type == TokenType.String && local > it.start && (local < it.end || !isClosedString(statement, it)) }
        val prefixStart = inString?.start ?: prefixToken?.start ?: local
        val rawPrefix = statement.substring(prefixStart, local)
        val prefix = rawPrefix.trimStart('"', '`', '\'')
        val before = tokens.filter { it.end <= prefixStart && it.type != TokenType.Whitespace && it.type != TokenType.Comment }
        val scope = Scope.parse(statement, tokens, dialect, schema)
        val pending = mutableSetOf<Pair<String?, String>>()

        fun result(items: List<CompletionItem>, value: ValueContext? = null) =
            CompletionResult(start + prefixStart, start + local, rawPrefix, rank(items, prefix).take(maxItems), value, pending + scope.pending)

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
                Context.Other -> keywords(SqlKeywords.all.toList())
            }
        return result(items.distinctBy { it.kind to it.insertText }, value)
    }

    // region Context

    private enum class Context { StatementStart, Table, Join, AfterTable, InsertColumns, Expression, AfterExpression, Other }

    private fun contextOf(sql: String, before: List<Token>): Context {
        if (before.isEmpty()) return Context.StatementStart
        val last = before.last()
        val lastWord = last.upper(sql)
        val previous = before.getOrNull(before.size - 2)

        if (last.type == TokenType.Operator && last.text(sql) == "(" && previous != null) {
            // INSERT INTO t (|
            val beforeParen = before.dropLast(1)
            val tableIndex = beforeParen.indexOfLast { it.type == TokenType.Identifier || it.type == TokenType.QuotedIdentifier }
            if (tableIndex >= 1 && beforeParen.getOrNull(tableIndex - 1)?.upper(sql) == "INTO" && tableIndex == beforeParen.lastIndex) return Context.InsertColumns
        }
        if (last.type == TokenType.Keyword) {
            return when (lastWord) {
                "FROM", "INTO", "UPDATE", "TABLE", "DESCRIBE", "TRUNCATE" -> Context.Table
                "JOIN" -> Context.Join
                "SELECT", "WHERE", "AND", "OR", "ON", "BY", "HAVING", "SET", "NOT", "WHEN", "THEN", "ELSE", "DISTINCT", "CASE", "RETURNING" -> Context.Expression
                else -> Context.Other
            }
        }
        if (last.type == TokenType.Operator) {
            val op = last.text(sql)
            if (op == ",") {
                // Continue the clause the comma belongs to.
                val clause = before.dropLast(1).lastOrNull { it.type == TokenType.Keyword && it.upper(sql) in ClauseWords }?.upper(sql)
                return if (clause == "FROM") Context.Table else Context.Expression
            }
            if (op == ")") return Context.AfterExpression
            return Context.Expression
        }
        // After an identifier: "FROM users |" or "WHERE id |".
        val clause = before.lastOrNull { it.type == TokenType.Keyword && it.upper(sql) in ClauseWords + "JOIN" }?.upper(sql)
        return when (clause) {
            "FROM", "JOIN", "UPDATE", "INTO" -> Context.AfterTable
            null -> if (before.size == 1) Context.Other else Context.AfterExpression
            else -> Context.AfterExpression
        }
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
        // Walk back over `IN (`, `'a', ` lists to the comparison operator.
        var i = before.lastIndex
        while (i >= 0) {
            val t = before[i]
            val text = t.text(sql)
            if (t.type == TokenType.String || t.type == TokenType.Number || text == "," || text == "(" && before.getOrNull(i - 1)?.upper(sql) == "IN") {
                if (text == "(") {
                    i -= 1 // the IN keyword
                    break
                }
                i--
                continue
            }
            break
        }
        val op = before.getOrNull(i) ?: return null
        val opText = op.upper(sql)
        if (opText !in setOf("=", "<>", "!=", "IN", "LIKE", "ILIKE", ">", "<", ">=", "<=")) return null
        if (!inString && before.lastOrNull()?.let { it.text(sql) == "," || it.text(sql) == "(" || it == op } != true) return null
        val columnToken = before.getOrNull(i - 1)?.takeIf { it.type == TokenType.Identifier || it.type == TokenType.QuotedIdentifier } ?: return null
        val column = unquote(columnToken.text(sql))
        val qualifier = before.getOrNull(i - 2)?.takeIf { it.text(sql) == "." }?.let { before.getOrNull(i - 3)?.text(sql)?.let(::unquote) }
        val table = (if (qualifier != null) scope.resolve(qualifier) else scope.tables.singleOrNull() ?: scope.tables.firstOrNull { scope.hasColumn(it, column) })
            ?: return null
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

    private fun isClosedString(sql: String, token: Token): Boolean {
        val text = token.text(sql)
        return text.length >= 2 && text.last() == text.first()
    }

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

    private fun keywords(words: Collection<String>): List<CompletionItem> = words.map { CompletionItem(it, it, CompletionKind.Keyword) }

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
        val ClauseWords = setOf("SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "SET", "ON", "RETURNING", "VALUES", "UPDATE", "INTO", "JOIN")
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
internal data class ScopeTable(val namespace: String?, val name: String, val alias: String?, val cteColumns: List<String>? = null)

/** Tables, aliases and CTEs of one statement. */
internal class Scope(val tables: List<ScopeTable>, val ctes: List<String>, private val schema: SchemaProvider) {
    val pending = mutableSetOf<Pair<String?, String>>()

    fun resolve(name: String): ScopeTable? =
        tables.firstOrNull { it.alias.equals(name, ignoreCase = true) } ?: tables.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun hasColumn(table: ScopeTable, column: String): Boolean =
        table.cteColumns?.any { it.equals(column, true) } ?: schema.columns(table.namespace, table.name)?.any { it.name.equals(column, true) } ?: false

    companion object {
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

            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val word = t.upper(sql)
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
                    tables += ScopeTable(namespace, tableName, alias, cteColumns[tableName])
                }
                i++
            }
            return Scope(tables.distinctBy { (it.alias ?: it.name).lowercase() }, ctes, schema)
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

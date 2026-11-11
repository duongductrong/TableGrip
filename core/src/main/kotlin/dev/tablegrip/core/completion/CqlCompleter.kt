package dev.tablegrip.core.completion

import dev.tablegrip.core.completion.Completions.functions
import dev.tablegrip.core.completion.Completions.keywords

/**
 * CQL completion for Cassandra and ScyllaDB: statements and their clauses, keyspaces after USE, tables after FROM, INTO,
 * UPDATE (and `keyspace.` members), and the columns of the statement's table where a column goes. Unqualified tables
 * resolve to the keyspace of the last `USE` above the statement.
 */
object CqlCompleter : StatementCompleter {
    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val all = lex(text, doubleQuotedStrings = false)
        if (all.any { it.encloses(at, text) && it.type != Lexeme.Type.Quoted }) return CompletionResult.empty(at)
        val bounds = statementBounds(text, all, at)
        val tokens = all.filter { it.start >= bounds.first && it.end <= bounds.last + 1 && it.type != Lexeme.Type.Comment }

        // The word at the caret, a "quoted name" included.
        val quoted = tokens.firstOrNull { it.type == Lexeme.Type.Quoted && at > it.start && at <= it.end }
        val start = quoted?.start ?: Completions.wordStart(text, at)
        val end = quoted?.end ?: Completions.wordEnd(text, at)
        val prefix = text.substring(start, at)
        val typed = prefix.trimStart('"')
        val before = tokens.filter { it.end <= start }
        val keyspace = currentKeyspace(all.filter { it.end <= bounds.first })

        fun result(items: List<CompletionItem>) = CompletionResult(start, end, prefix, Completions.rank(items, typed))

        // keyspace.|
        if (before.lastOrNull()?.text == "." && before.size >= 2 && before[before.size - 2].type.isName) {
            val ks = name(before[before.size - 2])
            return result(tables(catalog, ks))
        }
        val table = statementTable(tokens, keyspace)
        val columns = table?.let { catalog.fields(it) }.orEmpty().map { CompletionItem(quote(it), quote(it), CompletionKind.Column, table?.last()) }
        val items = itemsFor(before, catalog, keyspace, columns, isRead)
        return result(items)
    }

    private val Lexeme.Type.isName
        get() = this == Lexeme.Type.Word || this == Lexeme.Type.Quoted

    private fun itemsFor(before: List<Lexeme>, catalog: ConsoleCatalog, keyspace: String?, columns: List<CompletionItem>, isRead: ((String) -> Boolean)?): List<CompletionItem> {
        val last = before.lastOrNull() ?: return statements(isRead)
        val previous = before.getOrNull(before.size - 2)?.upper
        val first = before.first().upper
        val tablesHere = { tables(catalog, keyspace) + keyspaces(catalog, dotted = true) }
        val keyspaceStatement = before.any { it.upper == "KEYSPACE" || it.upper == "SCHEMA" }
        val values = { functions(ValueFunctions) + keywords(listOf("NULL", "true", "false")) }

        if (last.type == Lexeme.Type.Word && last.upper in Keywords) {
            return when (last.upper) {
                "FROM", "INTO", "UPDATE", "TRUNCATE" -> tablesHere()
                "TABLE", "VIEW", "FAMILY" ->
                    if (first == "CREATE") keywords(listOf("IF NOT EXISTS")) + keyspaces(catalog, dotted = true) else tablesHere() + keywords(listOf("IF EXISTS"))
                "KEYSPACE", "SCHEMA" -> if (first == "CREATE") keywords(listOf("IF NOT EXISTS")) else keyspaces(catalog, dotted = false) + keywords(listOf("IF EXISTS"))
                "USE" -> keyspaces(catalog, dotted = false)
                "EXISTS" ->
                    when {
                        first == "CREATE" -> if (keyspaceStatement) emptyList() else keyspaces(catalog, dotted = true)
                        first == "DROP" || first == "ALTER" -> if (keyspaceStatement) keyspaces(catalog, dotted = false) else tablesHere()
                        else -> emptyList()
                    }
                "ON" -> tablesHere()
                "SELECT" -> columns + keywords(listOf("*", "DISTINCT", "JSON")) + functions(SelectFunctions)
                "DISTINCT" -> columns
                "JSON" -> if (first == "SELECT") columns + keywords(listOf("DISTINCT")) else emptyList()
                "WHERE", "AND", "SET" -> columns + if (last.upper != "SET") functions(listOf("token")) else emptyList()
                "BY" -> columns
                "IF" -> keywords(listOf("EXISTS", "NOT EXISTS")) + columns
                "NOT" -> if (previous == "IF") keywords(listOf("EXISTS")) else emptyList()
                "ORDER", "GROUP" -> keywords(listOf("BY"))
                "PER" -> keywords(listOf("PARTITION LIMIT"))
                "PARTITION" -> keywords(listOf("LIMIT"))
                "ALLOW" -> keywords(listOf("FILTERING"))
                "INSERT" -> keywords(listOf("INTO"))
                "DELETE" -> columns + keywords(listOf("FROM"))
                "BEGIN" -> keywords(listOf("BATCH", "UNLOGGED BATCH", "COUNTER BATCH"))
                "APPLY" -> keywords(listOf("BATCH"))
                "BATCH" -> if (first == "BEGIN") statements(isRead).filter { it.label in BatchStatements } + keywords(listOf("USING TIMESTAMP")) else emptyList()
                "USING" -> keywords(listOf("TTL", "TIMESTAMP"))
                "CREATE" -> keywords(CreateObjects)
                "ALTER", "DROP" -> keywords(AlterObjects)
                "DESCRIBE", "DESC" -> keywords(DescribeTargets) + tablesHere()
                "LIST" -> keywords(listOf("ROLES", "USERS", "ALL PERMISSIONS"))
                "MATERIALIZED" -> keywords(listOf("VIEW"))
                "CLUSTERING" -> keywords(listOf("ORDER BY"))
                "PRIMARY" -> keywords(listOf("KEY"))
                "WITH" ->
                    if (keyspaceStatement) keywords(listOf("replication = {'class': 'SimpleStrategy', 'replication_factor': 1}", "durable_writes ="))
                    else keywords(TableOptions)
                "IN", "CONTAINS", "KEY", "TTL", "TIMESTAMP", "LIMIT", "AS", "VALUES", "NULL" -> emptyList()
                else -> emptyList()
            }
        }
        if (last.type == Lexeme.Type.Punct) {
            return when (last.text) {
                "*" -> if (previous == "SELECT" || previous == ",") keywords(listOf("FROM")) else columns
                "," ->
                    when (clauseOf(before.dropLast(1))) {
                        "SELECT" -> columns + functions(SelectFunctions)
                        "INTO", "SET", "BY", "WHERE" -> columns
                        else -> values()
                    }
                "(" -> {
                    val opener = before.getOrNull(before.size - 2)
                    when {
                        // INSERT INTO t (|, or a key in PRIMARY KEY (|, or token(|
                        clauseOf(before.dropLast(1)) == "INTO" && opener?.upper != "VALUES" -> columns
                        opener?.upper == "KEY" || opener?.upper == "TOKEN" -> columns
                        opener?.upper == "VALUES" -> values()
                        opener?.type == Lexeme.Type.Word && opener.upper !in Keywords -> columns + values()
                        else -> columns
                    }
                }
                "=", "<", ">", "!" -> values()
                ")" ->
                    when (clauseOf(before)) {
                        "INTO" -> keywords(listOf("VALUES"))
                        "VALUES" -> keywords(listOf("IF NOT EXISTS", "USING TTL", "USING TIMESTAMP"))
                        "SELECT" -> keywords(listOf("AS", "FROM"))
                        else -> afterValue(before)
                    }
                else -> emptyList()
            }
        }
        // After a name, number or string.
        return when (clauseOf(before)) {
            "SELECT" -> keywords(listOf("FROM", "AS"))
            "FROM" -> if (first == "DELETE") keywords(listOf("WHERE", "USING TIMESTAMP")) else keywords(listOf("WHERE", "LIMIT", "PER PARTITION LIMIT", "ORDER BY", "GROUP BY", "ALLOW FILTERING"))
            "UPDATE" -> keywords(listOf("SET", "USING TTL", "USING TIMESTAMP"))
            "INTO" -> keywords(listOf("JSON")) + listOf(CompletionItem("(", "(", CompletionKind.Operator))
            "WHERE", "AND", "SET", "IF" -> if (isOperand(before)) afterValue(before) else Operators.map { CompletionItem(it, "$it ", CompletionKind.Operator) } + keywords(listOf("IN", "CONTAINS", "CONTAINS KEY"))
            "BY" -> if (first == "SELECT") keywords(listOf("ASC", "DESC", "LIMIT", "ALLOW FILTERING")) else emptyList()
            "LIMIT" -> keywords(listOf("ALLOW FILTERING"))
            "USING", "TTL", "TIMESTAMP" -> keywords(listOf("SET", "AND", "WHERE"))
            "BATCH" -> keywords(listOf("APPLY BATCH"))
            else -> emptyList()
        }
    }

    /** After a condition or assignment: what may follow it. */
    private fun afterValue(before: List<Lexeme>): List<CompletionItem> =
        when (val first = before.first().upper) {
            "SELECT" -> keywords(listOf("AND", "LIMIT", "PER PARTITION LIMIT", "ORDER BY", "GROUP BY", "ALLOW FILTERING"))
            "UPDATE" -> if (before.any { it.upper == "WHERE" }) keywords(listOf("AND", "IF", "IF EXISTS")) else keywords(listOf("WHERE"))
            "DELETE" -> keywords(listOf("AND", "IF", "IF EXISTS"))
            else -> if (first == "BEGIN") keywords(listOf("APPLY BATCH")) else emptyList()
        }

    /** Whether the name or value ending [before] is the right-hand side of a comparison or assignment. */
    private fun isOperand(before: List<Lexeme>): Boolean {
        val previous = before.getOrNull(before.size - 2) ?: return false
        return previous.type == Lexeme.Type.Punct && previous.text in setOf("=", "<", ">") || previous.upper == "CONTAINS" || previous.upper == "KEY"
    }

    /** The last clause keyword before the caret, skipping parenthesized groups. */
    private fun clauseOf(before: List<Lexeme>): String? {
        var depth = 0
        for (t in before.asReversed()) {
            when {
                t.text == ")" -> depth++
                // Inside an unclosed group: the clause it belongs to.
                t.text == "(" -> if (depth == 0) return clauseOf(before.subList(0, before.indexOf(t))) else depth--
                depth == 0 && t.type == Lexeme.Type.Word && t.upper in Clauses -> return t.upper
            }
        }
        return null
    }

    /** The table the statement reads or writes: after FROM, INTO, UPDATE, TABLE or ON, anywhere in it. */
    private fun statementTable(tokens: List<Lexeme>, keyspace: String?): List<String>? {
        val at = tokens.indexOfFirst { it.type == Lexeme.Type.Word && it.upper in TableWords && tokens.getOrNull(tokens.indexOf(it) + 1)?.type?.isName == true }
        if (at < 0) return null
        var i = at + 1
        if (tokens[i].upper == "IF") return null
        val first = name(tokens[i])
        return if (tokens.getOrNull(i + 1)?.text == "." && tokens.getOrNull(i + 2)?.type?.isName == true) {
            i += 2
            listOf(first, name(tokens[i]))
        } else keyspace?.let { listOf(it, first) }
    }

    /** The keyspace of the last `USE` in the statements above. */
    private fun currentKeyspace(tokens: List<Lexeme>): String? {
        val code = tokens.filter { it.type != Lexeme.Type.Comment }
        val use = code.indices.lastOrNull { code[it].upper == "USE" && code.getOrNull(it + 1)?.type?.isName == true && (it == 0 || code[it - 1].text == ";") } ?: return null
        return name(code[use + 1])
    }

    private fun tables(catalog: ConsoleCatalog, keyspace: String?): List<CompletionItem> {
        keyspace ?: return emptyList()
        return catalog.containers(listOf(keyspace)).orEmpty().map { CompletionItem(quote(it), quote(it), CompletionKind.Table, keyspace) }
    }

    private fun keyspaces(catalog: ConsoleCatalog, dotted: Boolean): List<CompletionItem> =
        catalog.containers(emptyList()).orEmpty().map { CompletionItem(quote(it), quote(it) + if (dotted) "." else "", CompletionKind.Schema, "keyspace") }

    private fun statements(isRead: ((String) -> Boolean)?): List<CompletionItem> =
        keywords(Statements).filter { isRead == null || isRead(it.label) }

    private fun name(token: Lexeme): String = if (token.type == Lexeme.Type.Quoted) token.text.removeSurrounding("\"").replace("\"\"", "\"") else token.text.lowercase()

    /** Names that are lower case and plain go as they are; the rest (mixed case, reserved, odd characters) in double quotes. */
    fun quote(name: String): String = if (Regex("[a-z_][a-z0-9_]*").matches(name) && name.uppercase() !in Reserved) name else "\"" + name.replace("\"", "\"\"") + "\""

    private val Statements =
        listOf(
            "SELECT", "INSERT INTO", "UPDATE", "DELETE FROM", "BEGIN BATCH", "USE", "DESCRIBE", "CREATE TABLE", "CREATE KEYSPACE", "CREATE INDEX",
            "CREATE MATERIALIZED VIEW", "CREATE TYPE", "ALTER TABLE", "ALTER KEYSPACE", "DROP TABLE", "DROP KEYSPACE", "DROP INDEX", "TRUNCATE", "LIST ROLES",
            "GRANT", "REVOKE",
        )
    private val BatchStatements = setOf("INSERT INTO", "UPDATE", "DELETE FROM")
    private val CreateObjects = listOf("TABLE", "KEYSPACE", "INDEX", "CUSTOM INDEX", "MATERIALIZED VIEW", "TYPE", "FUNCTION", "AGGREGATE", "ROLE", "USER", "OR REPLACE FUNCTION")
    private val AlterObjects = listOf("TABLE", "KEYSPACE", "MATERIALIZED VIEW", "TYPE", "INDEX", "FUNCTION", "AGGREGATE", "ROLE", "USER")
    private val DescribeTargets = listOf("KEYSPACES", "KEYSPACE", "TABLES", "TABLE", "TYPES", "FUNCTIONS", "CLUSTER", "SCHEMA", "FULL SCHEMA", "MATERIALIZED VIEW", "INDEX")
    private val TableOptions =
        listOf("CLUSTERING ORDER BY", "comment =", "default_time_to_live =", "gc_grace_seconds =", "compaction =", "compression =", "caching =", "bloom_filter_fp_chance =")
    private val Operators = listOf("=", ">", ">=", "<", "<=", "!=")
    private val SelectFunctions = listOf("count", "writetime", "ttl", "token", "toJson", "toDate", "toTimestamp", "toUnixTimestamp", "min", "max", "sum", "avg", "cast", "dateOf", "unixTimestampOf")
    private val ValueFunctions = listOf("now", "uuid", "currentTimestamp", "currentDate", "toTimestamp", "minTimeuuid", "maxTimeuuid", "fromJson", "token")
    private val Clauses = setOf("SELECT", "FROM", "WHERE", "AND", "SET", "BY", "IF", "INTO", "VALUES", "UPDATE", "LIMIT", "USING", "TTL", "TIMESTAMP", "USE", "TABLE", "KEYSPACE", "TRUNCATE", "DESCRIBE", "DESC", "BATCH")
    private val TableWords = setOf("FROM", "INTO", "UPDATE", "TABLE", "ON", "TRUNCATE")
    private val Keywords =
        setOf(
            "SELECT", "FROM", "WHERE", "AND", "SET", "BY", "IF", "NOT", "EXISTS", "INTO", "VALUES", "UPDATE", "INSERT", "DELETE", "LIMIT", "USING", "TTL",
            "TIMESTAMP", "USE", "TABLE", "VIEW", "FAMILY", "KEYSPACE", "SCHEMA", "TRUNCATE", "DESCRIBE", "DESC", "ORDER", "GROUP", "PER", "PARTITION", "ALLOW",
            "BEGIN", "APPLY", "BATCH", "CREATE", "ALTER", "DROP", "LIST", "MATERIALIZED", "CLUSTERING", "PRIMARY", "KEY", "WITH", "IN", "CONTAINS", "AS",
            "DISTINCT", "JSON", "ON", "NULL",
        )
    private val Reserved = Keywords + setOf("ADD", "ASC", "AUTHORIZE", "GRANT", "INDEX", "INFINITY", "MODIFY", "NAN", "NORECURSIVE", "OF", "OR", "RENAME", "REVOKE", "TO", "TOKEN", "UNLOGGED")
}

package dev.tablegrip.core.completion

import dev.tablegrip.core.completion.Completions.functions
import dev.tablegrip.core.completion.Completions.keywords

/**
 * Cypher completion for Neo4j: clauses, labels after `:` in a node pattern (or `n:` in WHERE), relationship types after
 * `:` in a relationship pattern, property keys after `n.` and in a pattern's `{…}`, the statement's variables and
 * functions where an expression goes, and procedures after CALL. A read-only profile isn't offered the clauses that write.
 */
object CypherCompleter : StatementCompleter {
    override fun complete(text: String, caret: Int, catalog: ConsoleCatalog, isRead: ((String) -> Boolean)?): CompletionResult {
        val at = caret.coerceIn(0, text.length)
        val all = lex(text, doubleQuotedStrings = true)
        if (all.any { it.encloses(at, text) && it.type != Lexeme.Type.Quoted }) return CompletionResult.empty(at)
        val bounds = statementBounds(text, all, at)
        val tokens = all.filter { it.start >= bounds.first && it.end <= bounds.last + 1 && it.type != Lexeme.Type.Comment }

        val quoted = tokens.firstOrNull { it.type == Lexeme.Type.Quoted && at > it.start && at <= it.end }
        val start = quoted?.start ?: Completions.wordStart(text, at)
        val end = quoted?.end ?: Completions.wordEnd(text, at)
        val prefix = text.substring(start, at)
        val typed = prefix.trimStart('`')
        val before = tokens.filter { it.end <= start }
        val readOnly = isRead != null

        fun result(items: List<CompletionItem>) = CompletionResult(start, end, prefix, Completions.rank(items, typed))

        val last = before.lastOrNull() ?: return result(clauses(readOnly, StatementStarts))
        val previous = before.getOrNull(before.size - 2)

        // :Label, :TYPE, and :A|B
        if (last.text == ":" || last.text == "|" && openPatternGroup(before)?.text in setOf("(", "[")) {
            if (last.text == ":" && insideMap(before)) return result(emptyList())
            return result(if (inRelationship(before)) names(catalog, GraphNames.RelationshipTypes, CompletionKind.Join, "relationship type") else names(catalog, GraphNames.Labels, CompletionKind.Table, "label"))
        }
        // n.property, and db.labels() after CALL.
        if (last.text == "." && previous != null && (previous.type == Lexeme.Type.Word || previous.type == Lexeme.Type.Quoted)) {
            val chain = dottedName(before.dropLast(1))
            if (chain.isNotEmpty() && before.getOrNull(before.size - 1 - chain.size * 2)?.upper == "CALL") {
                val qualifier = chain.joinToString(".") + "."
                return result(procedures(readOnly).filter { it.label.startsWith(qualifier) }.map { it.copy(insertText = it.insertText.removePrefix(qualifier), caretOffset = it.caretOffset - qualifier.length) })
            }
            return result(properties(catalog))
        }
        // {name: 'x', |} in a pattern, or a map's key.
        if ((last.text == "{" || last.text == ",") && insideMap(before) && expectingKey(before)) return result(properties(catalog).map { it.copy(insertText = it.insertText + ": ", caretOffset = it.insertText.length + 2) })
        if (last.text == "$") return result(emptyList())

        val variables = variables(tokens, start)
        val expression = { variables + functions(Functions) + keywords(ExpressionWords) }
        if (last.type == Lexeme.Type.Word && last.upper in Keywords) {
            return result(
                when (last.upper) {
                    "MATCH", "MERGE", "CREATE" -> if (last.upper == "CREATE" && previous?.upper == "ON") keywords(listOf("SET")) else listOf(CompletionItem("()", "()", CompletionKind.Operator, "pattern", 1)) + variables
                    "OPTIONAL" -> keywords(listOf("MATCH"))
                    "DETACH" -> keywords(listOf("DELETE"))
                    "ORDER" -> keywords(listOf("BY"))
                    "ON" -> keywords(listOf("CREATE SET", "MATCH SET"))
                    "STARTS", "ENDS" -> keywords(listOf("WITH"))
                    "IS" -> keywords(listOf("NULL", "NOT NULL"))
                    "CALL" -> procedures(readOnly) + listOf(CompletionItem("{}", "{ }", CompletionKind.Operator, "subquery", 2))
                    "SHOW" -> keywords(ShowTargets)
                    "EXPLAIN", "PROFILE" -> clauses(readOnly, StatementStarts)
                    "UNION" -> keywords(listOf("ALL")) + clauses(readOnly, StatementStarts)
                    "YIELD", "AS", "LIMIT", "SKIP", "UNWIND" -> if (last.upper == "UNWIND") expression() else emptyList()
                    "DELETE", "REMOVE", "SET" -> variables
                    "RETURN", "WITH" -> if (last.upper == "WITH" && previous?.upper in setOf("STARTS", "ENDS")) emptyList() else keywords(listOf("DISTINCT", "*")) + expression()
                    else -> expression()
                },
            )
        }
        if (last.type == Lexeme.Type.Punct) {
            return result(
                when (last.text) {
                    "(" -> if (isPattern(before)) variables else expression()
                    "[" -> if (isPattern(before)) variables else expression()
                    ",", "=", "<", ">", "+", "-", "*", "/", "%" -> if (isPattern(before) && last.text in setOf("-", "<", ">", ",")) emptyList() else expression()
                    ")", "]", "}" -> if (isPattern(before)) next(before, readOnly) else operators() + next(before, readOnly)
                    else -> emptyList()
                },
            )
        }
        // After a name or value: what may follow it.
        val inPattern = isPattern(before) && openPatternGroup(before) != null
        return result(if (inPattern) emptyList() else operators() + next(before, readOnly))
    }

    /** What may come after a finished expression or pattern: the clause's continuations, then the next clauses. */
    private fun next(before: List<Lexeme>, readOnly: Boolean): List<CompletionItem> {
        val clause = before.lastOrNull { it.type == Lexeme.Type.Word && it.upper in ClauseWords }?.upper
        val more =
            when (clause) {
                "RETURN", "WITH" -> keywords(listOf("AS", "ORDER BY", "SKIP", "LIMIT")) + if (clause == "WITH") keywords(listOf("WHERE")) else keywords(listOf("UNION"))
                "BY" -> keywords(listOf("ASC", "DESC", "SKIP", "LIMIT"))
                "MATCH" -> keywords(listOf("WHERE"))
                "UNWIND" -> keywords(listOf("AS"))
                "CALL" -> keywords(listOf("YIELD"))
                else -> emptyList()
            }
        return more + clauses(readOnly, NextClauses)
    }

    private fun operators(): List<CompletionItem> = keywords(listOf("AND", "OR", "XOR", "IS NULL", "IS NOT NULL", "IN", "STARTS WITH", "ENDS WITH", "CONTAINS"))

    private fun clauses(readOnly: Boolean, words: List<String>): List<CompletionItem> = keywords(if (readOnly) words.filter { it !in WriteClauses } else words)

    private fun names(catalog: ConsoleCatalog, kind: GraphNames, completion: CompletionKind, detail: String): List<CompletionItem> =
        catalog.graph(kind).orEmpty().map { CompletionItem(it, quote(it), completion, detail) }

    private fun properties(catalog: ConsoleCatalog): List<CompletionItem> = names(catalog, GraphNames.PropertyKeys, CompletionKind.Column, "property")

    private fun procedures(readOnly: Boolean): List<CompletionItem> =
        (if (readOnly) ReadProcedures else ReadProcedures + WriteProcedures).map { CompletionItem("$it()", "$it()", CompletionKind.Function, "procedure", it.length + 1) }

    /** The names of `a.b.c` ending [before], first to last. */
    private fun dottedName(before: List<Lexeme>): List<String> {
        val out = ArrayList<String>()
        var i = before.lastIndex
        while (i >= 0 && before[i].type == Lexeme.Type.Word) {
            out.add(0, before[i].text)
            if (before.getOrNull(i - 1)?.text != ".") break
            i -= 2
        }
        return out
    }

    /** The variables the statement binds: `(n`, `[r`, `p =`, `AS x`, and procedures' YIELD names; not the one being typed. */
    private fun variables(tokens: List<Lexeme>, typing: Int): List<CompletionItem> {
        val names = LinkedHashSet<String>()
        tokens.forEachIndexed { i, t ->
            if (t.type != Lexeme.Type.Word || t.start == typing || t.upper in Keywords) return@forEachIndexed
            val prev = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            val bound =
                prev?.text == "(" && (next?.text == ":" || next?.text == ")" || next?.text == "{") && isPattern(tokens.subList(0, i)) ||
                    prev?.text == "[" && (next?.text == ":" || next?.text == "]" || next?.text == "{" || next?.text == "*") ||
                    prev?.upper == "AS" || prev?.upper == "YIELD" || prev?.text == "," && tokens.subList(0, i).lastOrNull { it.upper in ClauseWords }?.upper == "YIELD" ||
                    next?.text == "=" && tokens.getOrNull(i + 2)?.text == "(" && prev?.upper in setOf("MATCH", "MERGE", "CREATE", ",")
            if (bound) names += t.text
        }
        return names.map { CompletionItem(it, it, CompletionKind.Alias, "variable") }
    }

    /** The innermost unclosed `(` or `[`, if any. */
    private fun openPatternGroup(before: List<Lexeme>): Lexeme? {
        var depth = 0
        for (t in before.asReversed()) {
            when (t.text) {
                ")", "]", "}" -> depth++
                "(", "[", "{" -> if (depth == 0) return t else depth--
            }
        }
        return null
    }

    /** Whether the end of [before] is in a pattern: after MATCH, MERGE or CREATE, and not in a WHERE or a function call. */
    private fun isPattern(before: List<Lexeme>): Boolean {
        val clause = before.lastOrNull { it.type == Lexeme.Type.Word && it.upper in ClauseWords } ?: return false
        if (clause.upper !in PatternClauses) return false
        // `count(` is a call; `(` after a clause, `-`, `>`, `,` or `)` opens a node.
        val open = openPatternGroup(before) ?: return true
        val opener = before.getOrNull(before.indexOf(open) - 1) ?: return true
        return opener.type != Lexeme.Type.Word || opener.upper in Keywords
    }

    /** Whether the end of [before] is in a relationship pattern's `[…]`. */
    private fun inRelationship(before: List<Lexeme>): Boolean = openPatternGroup(before)?.text == "["

    private fun insideMap(before: List<Lexeme>): Boolean = openPatternGroup(before)?.text == "{"

    /** In `{…}`, whether a key comes next: after `{` or after a `,` that ends a value. */
    private fun expectingKey(before: List<Lexeme>): Boolean = before.last().text == "{" || before.last().text == ","

    private fun quote(name: String): String = if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) name else "`" + name.replace("`", "``") + "`"

    private val StatementStarts = listOf("MATCH", "OPTIONAL MATCH", "CREATE", "MERGE", "UNWIND", "WITH", "RETURN", "CALL", "SHOW", "EXPLAIN", "PROFILE", "LOAD CSV FROM", "FOREACH")
    private val NextClauses =
        listOf("WHERE", "RETURN", "WITH", "MATCH", "OPTIONAL MATCH", "UNWIND", "CALL", "CREATE", "MERGE", "SET", "DELETE", "DETACH DELETE", "REMOVE", "ON CREATE SET", "ON MATCH SET", "FOREACH", "UNION")
    private val WriteClauses = setOf("CREATE", "MERGE", "SET", "DELETE", "DETACH DELETE", "REMOVE", "ON CREATE SET", "ON MATCH SET", "LOAD CSV FROM", "FOREACH")
    private val ClauseWords = setOf("MATCH", "MERGE", "CREATE", "WHERE", "RETURN", "WITH", "UNWIND", "SET", "DELETE", "REMOVE", "CALL", "YIELD", "BY", "SKIP", "LIMIT", "ORDER")
    private val PatternClauses = setOf("MATCH", "MERGE", "CREATE")
    private val ShowTargets = listOf("INDEXES", "CONSTRAINTS", "DATABASES", "DATABASE", "PROCEDURES", "FUNCTIONS", "TRANSACTIONS", "USERS", "ROLES", "SETTINGS")
    private val ExpressionWords = listOf("NOT", "NULL", "TRUE", "FALSE", "CASE", "EXISTS", "COUNT")
    private val Functions =
        listOf(
            "count", "collect", "sum", "avg", "min", "max", "id", "elementId", "labels", "type", "keys", "properties", "nodes", "relationships", "size", "length",
            "coalesce", "head", "last", "tail", "range", "toString", "toInteger", "toFloat", "toBoolean", "toLower", "toUpper", "trim", "split", "replace",
            "substring", "startNode", "endNode", "exists", "shortestPath", "allShortestPaths", "timestamp", "date", "datetime", "duration", "abs", "round",
            "rand", "distinct", "percentileCont", "stDev", "reverse", "any", "all", "none", "single",
        )
    private val ReadProcedures =
        listOf(
            "db.labels", "db.relationshipTypes", "db.propertyKeys", "db.schema.visualization", "db.schema.nodeTypeProperties", "db.schema.relTypeProperties",
            "db.indexes", "db.constraints", "db.info", "db.ping", "dbms.components", "dbms.procedures", "dbms.functions", "dbms.showCurrentUser", "dbms.info",
        )
    private val WriteProcedures = listOf("db.createLabel", "db.createRelationshipType", "db.createProperty", "db.awaitIndexes", "db.clearQueryCaches")
    private val Keywords =
        setOf(
            "MATCH", "OPTIONAL", "MERGE", "CREATE", "WHERE", "RETURN", "WITH", "UNWIND", "SET", "DELETE", "DETACH", "REMOVE", "CALL", "YIELD", "ORDER", "BY",
            "SKIP", "LIMIT", "AS", "AND", "OR", "XOR", "NOT", "IS", "NULL", "IN", "STARTS", "ENDS", "CONTAINS", "DISTINCT", "UNION", "ALL", "ON", "SHOW",
            "EXPLAIN", "PROFILE", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC", "TRUE", "FALSE", "FOREACH", "LOAD", "CSV", "FROM", "EXISTS",
        )
}

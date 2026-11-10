package dev.tablegrip.core.assist

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.completion.SchemaProvider
import dev.tablegrip.core.completion.SqlCompleter
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.QueryError
import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.SqlSyntax
import dev.tablegrip.core.sql.StatementKind
import dev.tablegrip.core.sql.TokenType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A corrected statement, or only [explanation] when there is no fix (e.g. the table really doesn't exist). */
data class SqlFix(val sql: String?, val explanation: String)

/** A table or column the engine says doesn't exist, read from its error message. */
data class MissingName(val kind: Kind, val name: String) {
    enum class Kind { Column, Table }
}

object SqlErrors {
    /** Each engine's wording for an unknown column or table; the name is group 1. */
    private val Patterns =
        listOf(
            // PostgreSQL, CockroachDB: column "usr_id" does not exist · column u.usr_id does not exist · relation "userz" does not exist
            MissingName.Kind.Column to Regex("""column "?([^"\s]+)"? does not exist""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""relation "?([^"\s]+)"? does not exist""", RegexOption.IGNORE_CASE),
            // MySQL, MariaDB
            MissingName.Kind.Column to Regex("""Unknown column '([^']+)'""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""Table '([^']+)' doesn't exist""", RegexOption.IGNORE_CASE),
            // SQLite
            MissingName.Kind.Column to Regex("""no such column: ([^\s)]+)""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""no such table: ([^\s)]+)""", RegexOption.IGNORE_CASE),
            // SQL Server
            MissingName.Kind.Column to Regex("""Invalid column name '([^']+)'""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""Invalid object name '([^']+)'""", RegexOption.IGNORE_CASE),
            // DuckDB
            MissingName.Kind.Column to Regex("""Referenced column "([^"]+)" not found""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""Table with name ([^\s!]+) does not exist""", RegexOption.IGNORE_CASE),
            // H2
            MissingName.Kind.Column to Regex("""Column "([^"]+)" not found""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""Table "([^"]+)" not found""", RegexOption.IGNORE_CASE),
            // ClickHouse
            MissingName.Kind.Column to Regex("""Missing columns: '([^']+)'""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Column to Regex("""Unknown expression identifier [`']([^`']+)[`']""", RegexOption.IGNORE_CASE),
            MissingName.Kind.Table to Regex("""Unknown table expression identifier '([^']+)'""", RegexOption.IGNORE_CASE),
        )

    /** The unknown name in [error], unqualified (`u.usr_id` → `usr_id`, `shop.userz` → `userz`); null for other errors. */
    fun missingName(error: QueryError): MissingName? {
        for ((kind, pattern) in Patterns) {
            val raw = pattern.find(error.message)?.groupValues?.get(1) ?: continue
            val name = raw.substringAfterLast('.').trim('"', '`', '\'', '[', ']')
            if (name.isNotEmpty()) return MissingName(kind, name)
        }
        return null
    }
}

/**
 * Fixes without an agent: a misspelt table or column replaced by the closest real one. Only offered when one name is
 * clearly closest; the schema comes from completion's cache, so nothing is fetched and nothing leaves the machine.
 */
class SqlNameFixer(private val dialect: SqlDialect) {
    private val lexer = SqlLexer(dialect.syntax)
    private val completer = SqlCompleter(dialect)

    /** Real names close to [missing], closest first; empty while the schema isn't loaded. */
    fun nearMisses(statement: String, missing: MissingName, schema: SchemaProvider, limit: Int = 3): List<String> =
        ranked(missing.name, candidates(statement, missing, schema)).take(limit).map { it.first }

    /** The statement with [error]'s unknown name replaced, when exactly one real name is closest. */
    fun fix(statement: String, error: QueryError, schema: SchemaProvider): SqlFix? {
        val missing = SqlErrors.missingName(error) ?: return null
        val ranked = ranked(missing.name, candidates(statement, missing, schema))
        val best = ranked.firstOrNull() ?: return null
        // Two names equally close (`user_id`, `users_id`): a guess, so leave it to the user or an agent.
        if (ranked.getOrNull(1)?.second == best.second) return null
        val fixed = replaceName(statement, missing.name, best.first) ?: return null
        val what = if (missing.kind == MissingName.Kind.Column) "column" else "table"
        return SqlFix(fixed, tr("There is no {0} {1}; the closest is {2}.", what, missing.name, best.first))
    }

    /** Columns of the statement's tables, or the tables of the namespace the unknown one was looked for in. */
    private fun candidates(statement: String, missing: MissingName, schema: SchemaProvider): List<String> {
        val tables = completer.tablesIn(statement)
        return when (missing.kind) {
            MissingName.Kind.Column -> tables.flatMap { (namespace, table) -> schema.columns(namespace, table).orEmpty().map { it.name } }
            MissingName.Kind.Table -> schema.tables(tables.firstOrNull { it.second.equals(missing.name, ignoreCase = true) }?.first).orEmpty().map { it.name }
        }.distinct()
    }

    /** Replaces every identifier spelled [from] (ignoring case and quotes) by [to]; null when there is none. */
    fun replaceName(statement: String, from: String, to: String): String? {
        val tokens =
            lexer.tokenize(statement).filter { token ->
                (token.type == TokenType.Identifier || token.type == TokenType.QuotedIdentifier || token.type == TokenType.Keyword) &&
                    unquote(token.text(statement)).equals(from, ignoreCase = true)
            }
        if (tokens.isEmpty()) return null
        val result = StringBuilder(statement)
        for (token in tokens.asReversed()) {
            val replacement = if (token.type == TokenType.QuotedIdentifier || needsQuotes(to)) dialect.quoteIdentifier(to) else to
            result.replace(token.start, token.end, replacement)
        }
        return result.toString()
    }

    private fun needsQuotes(name: String): Boolean =
        !PlainName.matches(name) || dialect.syntax == SqlSyntax.PostgreSQL && name != name.lowercase()

    private fun unquote(text: String): String =
        if (text.length >= 2 && text.first() in "\"`[") text.substring(1, text.length - 1) else text

    companion object {
        private val PlainName = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Candidates within a few edits of [name], closest first, as (candidate, distance). */
        internal fun ranked(name: String, candidates: List<String>): List<Pair<String, Int>> {
            val target = name.lowercase()
            return candidates
                .filter { !it.equals(name, ignoreCase = true) }
                .map { candidate ->
                    val lower = candidate.lowercase()
                    candidate to minOf(distance(target, lower), distance(target.replace("_", ""), lower.replace("_", "")))
                }
                .filter { (candidate, distance) -> distance <= maxOf(1, minOf(target.length, candidate.length) / 3) }
                .sortedWith(compareBy({ it.second }, { it.first.length }))
        }

        /** Damerau-Levenshtein (optimal string alignment): a swapped pair of letters is one typo, not two. */
        internal fun distance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
            return d[a.length][b.length]
        }
    }
}

/** What a reviewer should notice about a proposed fix before applying it, worst first; empty when it only corrects. */
fun reviewFix(original: String, fixed: String, syntax: SqlSyntax): List<String> {
    val classifier = SqlClassifier(syntax)
    val before = classifier.classify(original)
    val after = classifier.classify(fixed)
    return buildList {
        after.danger?.let(::add)
        if (!before.changesData && after.changesData) add(tr("The fix changes data or schema ({0}); the original only read.", after.verb))
        else if (after.kind != StatementKind.Read && before.verb != after.verb) add(tr("The fix runs {0} instead of {1}.", after.verb, before.verb))
    }
}

/**
 * Asks an agent to correct a statement that failed. It gets the engine, the statement, the error and the schema (never
 * data), and must answer with one statement in the same dialect, or explain why there is no fix.
 */
class SqlFixTask(
    private val dialect: SqlDialect,
    private val engine: String,
    private val statement: String,
    private val error: QueryError,
    private val schema: String?,
    private val nearMisses: List<String> = emptyList(),
) : AssistTask<SqlFix> {
    override val name = "fix-sql"

    override fun system() =
        """
        You fix SQL statements that failed in TableGrip, a desktop database client.

        # Rules
        - Write ${engine} SQL. Return one statement, without a trailing semicolon.
        - Change as little as needed to fix the error, keeping what the statement was meant to do. Don't add filters, limits, joins or columns it didn't ask for.
        - Never turn a statement that only reads into one that changes data or schema.
        - Use only tables and columns listed under Schema; never invent a name. If the statement can't work against this schema, return "sql": null and say why.
        - Don't run anything: you can't, and TableGrip only applies the fix after the user reviews it.
        """.trimIndent()

    override fun prompt(): String = buildString {
        append("This statement failed:\n```sql\n${statement.trim()}\n```\n\n")
        append("Error: ${error.message.trim()}\n")
        error.sqlState?.let { append("SQLSTATE: $it\n") }
        error.vendorCode?.let { append("Vendor error code: $it\n") }
        error.position?.takeIf { it in 0..statement.length }?.let { at ->
            val line = statement.substring(0, at).count { it == '\n' } + 1
            append("The engine points at line $line, near: ${statement.substring(at).take(40).lineSequence().first()}\n")
        }
        if (nearMisses.isNotEmpty()) append("Existing names close to the unknown one: ${nearMisses.joinToString()}\n")
        append("\n# Schema\n")
        append(schema ?: "Not shared. Fix only what the error and the statement themselves show.")
        append("\n\nReply with only this JSON object:\n")
        append("```json\n{\"sql\": \"the corrected statement, or null if it can't be fixed\", \"explanation\": \"one or two short sentences: what was wrong and what changed\"}\n```")
    }

    override fun parse(json: JsonObject): SqlFix {
        val explanation = json.requireText("explanation")
        if (json["sql"] == null || json["sql"] is JsonNull) return SqlFix(null, explanation)
        val sql = json.requireText("sql").trim().removeSuffix(";").trim()
        val statements = SqlSplitter(dialect.syntax).split(sql)
        if (statements.size != 1) throw AssistInvalid("\"sql\" must be exactly one statement; it has ${statements.size}.")
        if (normalize(sql) == normalize(statement)) throw AssistInvalid("\"sql\" is the same statement that failed; change what the error points at, or return null.")
        return SqlFix(sql, explanation)
    }

    private fun normalize(sql: String) = sql.trim().removeSuffix(";").replace(Whitespace, " ").trim()

    private companion object {
        val Whitespace = Regex("\\s+")
    }
}

package dev.datagrok.core.dialect

import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.filter.FilterValues
import dev.datagrok.core.filter.SqlParam
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.sql.SqlSyntax
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.math.BigDecimal
import java.sql.Types
import java.time.temporal.Temporal
import java.util.UUID

enum class SortDirection { Asc, Desc }

data class SortColumn(val column: String, val direction: SortDirection)

/** Everything that differs between database engines: SQL syntax, quoting, and introspection quirks. */
sealed class SqlDialect(val syntax: SqlSyntax) {
    /** JDBC table types shown in the explorer, mapped to whether they are views. */
    open val tableTypes: Map<String, Boolean> = mapOf("TABLE" to false, "VIEW" to true)

    abstract fun quoteIdentifier(name: String): String

    /** A SQL literal for display or copy-as-SQL; execution always binds parameters instead. */
    open fun literal(value: String?, numeric: Boolean): String =
        when {
            value == null -> "NULL"
            numeric && value.toBigDecimalOrNull() != null -> value
            else -> "'" + escapeString(value) + "'"
        }

    protected open fun escapeString(value: String): String = value.replace("'", "''")

    /**
     * Binds a value read as text back into a statement. Most drivers coerce strings to the column type; PostgreSQL is
     * strict about `varchar = integer`, so it sends untyped literals and lets the server infer the type.
     */
    open fun bind(statement: PreparedStatement, index: Int, value: String?, jdbcType: Int) {
        if (value == null) statement.setNull(index, jdbcType) else statement.setString(index, value)
    }

    /** Binds a typed filter value. */
    open fun bind(statement: PreparedStatement, index: Int, param: SqlParam) {
        when (val value = param.value) {
            is String -> statement.setString(index, value)
            is Long -> statement.setLong(index, value)
            is BigDecimal -> statement.setBigDecimal(index, value)
            is Boolean -> statement.setBoolean(index, value)
            is UUID -> statement.setString(index, value.toString())
            else -> statement.setObject(index, value)
        }
    }

    /** A typed filter value as a literal, for previews. */
    open fun literal(param: SqlParam): String =
        when (val value = param.value) {
            is Long, is BigDecimal -> FilterValues.format(value)
            is Boolean -> if (value) "TRUE" else "FALSE"
            else -> literal(FilterValues.format(value), numeric = false)
        }

    /**
     * The SQL around a bound LIKE pattern (`!` escapes wildcards) that [expression] must match, as the text before and
     * after the `?`. Case-insensitive unless [caseSensitive].
     */
    open fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> {
        val not = if (negated) "NOT " else ""
        return if (caseSensitive) "$expression ${not}LIKE " to " ESCAPE '!'" else "LOWER($expression) ${not}LIKE LOWER(" to ") ESCAPE '!'"
    }

    /** The value to bind for [textMatch], from a LIKE pattern. */
    open fun textPattern(likePattern: String, caseSensitive: Boolean): String = likePattern

    /** Whether filters can match regular expressions. */
    open val supportsRegex: Boolean = true

    /** Whether [regexMatch] honours case sensitivity; otherwise the column's collation decides. */
    open val regexHonoursCase: Boolean = false

    /** Like [textMatch] for a regular expression, or null if the engine has none. */
    open fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String>? =
        "$expression ${if (negated) "NOT " else ""}REGEXP " to ""

    abstract fun listNamespaces(meta: DatabaseMetaData): List<Namespace>

    open fun qualifiedName(table: TableRef): String =
        listOfNotNull(table.namespace.catalog, table.namespace.schema, table.name).joinToString(".", transform = ::quoteIdentifier)

    open fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long): String =
        selectAll(table, filter, orderBy) + " LIMIT $limit OFFSET $offset"

    open fun selectAll(table: TableRef, filter: String?, orderBy: List<SortColumn>): String =
        buildString {
            append("SELECT * FROM ").append(qualifiedName(table))
            filter?.takeIf { it.isNotBlank() }?.let { append(" WHERE ").append(it) }
            if (orderBy.isNotEmpty()) {
                append(" ORDER BY ")
                append(orderBy.joinToString { "${quoteIdentifier(it.column)} ${it.direction.name.uppercase()}" })
            }
        }

    /** [expression] as text, for prefix matching any column type. */
    open fun castToText(expression: String): String = "CAST($expression AS TEXT)"

    /**
     * Up to [limit] distinct non-null values of [column] starting with a bound `?` prefix, scanning at most [scan] rows so
     * suggestions stay cheap on large tables.
     */
    fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
        val col = quoteIdentifier(column)
        return "SELECT DISTINCT v FROM (SELECT $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
            "${castToText(col)} LIKE ? ESCAPE '!' LIMIT $scan) s ORDER BY v LIMIT $limit"
    }

    open fun countRows(table: TableRef, filter: String?): String =
        "SELECT COUNT(*) FROM ${qualifiedName(table)}" + (filter?.takeIf { it.isNotBlank() }?.let { " WHERE $it" } ?: "")

    data object PostgreSQL : SqlDialect(SqlSyntax.PostgreSQL) {
        private val systemSchemas = setOf("pg_catalog", "information_schema")

        override val tableTypes =
            mapOf("TABLE" to false, "PARTITIONED TABLE" to false, "VIEW" to true, "MATERIALIZED VIEW" to true)

        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        override fun bind(statement: PreparedStatement, index: Int, value: String?, jdbcType: Int) {
            if (value == null) statement.setNull(index, Types.NULL) else statement.setObject(index, value, Types.OTHER)
        }

        /**
         * Text goes untyped, so the server reads it as the column's type: enums and domains report as varchar but have
         * no `= varchar` operator.
         */
        override fun bind(statement: PreparedStatement, index: Int, param: SqlParam) {
            when (val value = param.value) {
                is String -> statement.setObject(index, value, Types.OTHER)
                is UUID -> statement.setObject(index, value)
                else -> super.bind(statement, index, param)
            }
        }

        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to " ESCAPE '!'"

        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "!" else ""}~${if (caseSensitive) "" else "*"} " to ""

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
            meta.schemas.use { rs ->
                buildList { while (rs.next()) add(rs.getString("TABLE_SCHEM")) }
            }
                .filterNot { it in systemSchemas || it.startsWith("pg_toast") || it.startsWith("pg_temp") }
                .sorted()
                .map { Namespace(catalog = null, schema = it) }
    }

    data object MySQL : SqlDialect(SqlSyntax.MySQL) {
        private val systemDatabases = setOf("information_schema", "performance_schema", "mysql", "sys")

        override fun quoteIdentifier(name: String) = "`${name.replace("`", "``")}`"

        override fun castToText(expression: String) = "CAST($expression AS CHAR)"

        override fun escapeString(value: String) = value.replace("\\", "\\\\").replace("'", "''")

        /** Comparing as binary strings is the collation-independent way to match case exactly. */
        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            if (caseSensitive) "CAST($expression AS BINARY) ${if (negated) "NOT " else ""}LIKE CAST(" to " AS BINARY) ESCAPE '!'"
            else super.textMatch(expression, caseSensitive = false, negated)

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
            meta.catalogs.use { rs ->
                buildList { while (rs.next()) add(rs.getString("TABLE_CAT")) }
            }
                .filterNot { it in systemDatabases }
                .sorted()
                .map { Namespace(catalog = it, schema = null) }
    }

    data object SQLite : SqlDialect(SqlSyntax.SQLite) {
        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        /** SQLite keeps dates as text, so they bind as the text they're usually stored as; it has no exact decimals. */
        override fun bind(statement: PreparedStatement, index: Int, param: SqlParam) {
            when (val value = param.value) {
                is Temporal -> statement.setString(index, FilterValues.format(value))
                is BigDecimal -> statement.setDouble(index, value.toDouble())
                else -> super.bind(statement, index, param)
            }
        }

        /** LIKE ignores (ASCII) case in SQLite; GLOB is its case-sensitive counterpart. */
        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> {
            val not = if (negated) "NOT " else ""
            return if (caseSensitive) "$expression ${not}GLOB " to "" else "$expression ${not}LIKE " to " ESCAPE '!'"
        }

        override fun textPattern(likePattern: String, caseSensitive: Boolean): String {
            if (!caseSensitive) return likePattern
            val glob = StringBuilder()
            var i = 0
            fun literal(c: Char) = glob.append(if (c in "*?[") "[$c]" else c.toString())
            while (i < likePattern.length) {
                when (val c = likePattern[i]) {
                    '!' -> likePattern.getOrNull(i + 1)?.let { literal(it); i++ }
                    '%' -> glob.append('*')
                    '_' -> glob.append('?')
                    else -> literal(c)
                }
                i++
            }
            return glob.toString()
        }

        override val supportsRegex = false

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String>? = null

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> = listOf(Namespace(catalog = null, schema = null))
    }

    companion object {
        fun of(type: DatabaseType): SqlDialect =
            when (type) {
                DatabaseType.PostgreSQL -> PostgreSQL
                DatabaseType.MySQL,
                DatabaseType.MariaDB -> MySQL
                DatabaseType.SQLite -> SQLite
            }
    }
}

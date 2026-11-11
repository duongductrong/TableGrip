package dev.tablegrip.core.dialect

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.filter.FilterValues
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.SqlParam
import dev.tablegrip.core.filter.SqlProjection
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.sql.SqlSyntax
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.math.BigDecimal
import java.sql.Types
import java.time.temporal.Temporal
import java.util.UUID

enum class SortDirection { Asc, Desc }

data class SortColumn(val column: String, val direction: SortDirection)

/**
 * Everything that differs between database engines: SQL syntax, quoting, and introspection quirks. One subclass per
 * [EngineFamily]; an engine that strays from its family's SQL subclasses that family's dialect and overrides the
 * difference.
 */
abstract class SqlDialect(val syntax: SqlSyntax) {
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

    /** The namespace unqualified names resolve to, among [names], for a connection to [database]. */
    open fun defaultNamespace(names: List<String>, database: String): String? = names.firstOrNull()

    /** Statements that show [sql]'s plan instead of running it. */
    open fun explain(sql: String): List<String> = listOf("EXPLAIN $sql")

    /** Functions completion offers besides the standard ones. */
    open val functions: List<String> = emptyList()

    /** Names that need no quoting, reserved words aside. */
    open val plainIdentifier: Regex = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    open fun qualifiedName(table: TableRef): String =
        listOfNotNull(table.namespace.catalog, table.namespace.schema, table.name).joinToString(".", transform = ::quoteIdentifier)

    open fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection? = null): String =
        selectAll(table, filter, orderBy, projection) + " LIMIT $limit OFFSET $offset"

    /** The table's rows, as [projection] picks their columns (all of them when null). */
    open fun selectAll(table: TableRef, filter: String?, orderBy: List<SortColumn>, projection: SqlProjection? = null): String =
        buildString {
            append("SELECT ").append(projection?.clause ?: "*").append(" FROM ").append(qualifiedName(table))
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
    /** The value bound to [sampleValues]' `?` to match values starting with [prefix] literally. */
    open fun samplePattern(prefix: String): String =
        // '!' escapes LIKE wildcards: unlike '\\' it means the same in every dialect's string literals.
        prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

    open fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    /** [sampleValues] with LIMIT and LIKE's ESCAPE clause. */
    protected fun sampleValuesWithLimit(table: TableRef, column: String, limit: Int, scan: Int): String {
        val col = quoteIdentifier(column)
        return "SELECT DISTINCT v FROM (SELECT $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
            "${castToText(col)} LIKE ? ESCAPE '!' LIMIT $scan) s ORDER BY v LIMIT $limit"
    }

    /** [selectPage] with LIMIT and OFFSET, for engines that page that way outside their family's own syntax. */
    protected fun limitOffset(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        selectAll(table, filter, orderBy, projection) + " LIMIT $limit OFFSET $offset"

    /** For engines whose LIKE has no ESCAPE clause: a [textMatch] pattern's `!` escapes as backslashes. */
    protected fun backslashEscapes(likePattern: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < likePattern.length) {
            val c = likePattern[i]
            if (c == '!' && i + 1 < likePattern.length) {
                out.append('\\').append(likePattern[i + 1])
                i += 2
            } else {
                if (c == '\\') out.append('\\')
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** [samplePattern] for engines whose LIKE has no ESCAPE clause. */
    protected fun backslashSamplePattern(prefix: String): String = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** [sampleValues] for engines whose LIKE has no ESCAPE clause. */
    protected fun sampleValuesWithoutEscape(table: TableRef, column: String, limit: Int, scan: Int): String {
        val col = quoteIdentifier(column)
        return "SELECT DISTINCT v FROM (SELECT $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
            "${castToText(col)} LIKE ? LIMIT $scan) s ORDER BY v LIMIT $limit"
    }

    /** Rows [selectAll] returns; with DISTINCT, the distinct ones. */
    open fun countRows(table: TableRef, filter: String?, projection: SqlProjection? = null): String {
        val where = filter?.takeIf { it.isNotBlank() }?.let { " WHERE $it" } ?: ""
        if (projection?.distinct == true) return "SELECT COUNT(*) FROM (SELECT ${projection.clause} FROM ${qualifiedName(table)}$where) d"
        return "SELECT COUNT(*) FROM ${qualifiedName(table)}$where"
    }

    /**
     * [function] applied to [expression] in a select list, with its [arguments] already SQL (a [TimeBucket] name for
     * [SelectFunction.Truncate]); null when the engine has no such function.
     */
    open fun selectFunction(function: SelectFunction, expression: String, arguments: List<String> = emptyList()): String? =
        when (function) {
            SelectFunction.None -> expression
            SelectFunction.Upper -> "UPPER($expression)"
            SelectFunction.Lower -> "LOWER($expression)"
            SelectFunction.Trim -> "TRIM($expression)"
            SelectFunction.Length -> "LENGTH($expression)"
            SelectFunction.Substring -> "SUBSTRING($expression, ${arguments.joinToString()})"
            SelectFunction.Round -> "ROUND($expression, ${arguments.single()})"
            SelectFunction.Abs -> "ABS($expression)"
            SelectFunction.Floor -> "FLOOR($expression)"
            SelectFunction.Ceil -> "CEILING($expression)"
            SelectFunction.Truncate -> TimeBucket.of(arguments.single())?.let { timeBucket(expression, it) }
            SelectFunction.Coalesce -> "COALESCE($expression, ${arguments.single()})"
            SelectFunction.AsText -> castToText(expression)
        }

    /**
     * [expression], a date or timestamp, rounded down to the start of its [bucket] (weeks start on Monday), for charts
     * grouped on the server; null when the engine's date functions aren't known.
     */
    open fun timeBucket(expression: String, bucket: TimeBucket): String? = null

    open class PostgreSQLDialect : SqlDialect(SqlSyntax.PostgreSQL) {
        override fun toString() = "PostgreSQL"

        override fun timeBucket(expression: String, bucket: TimeBucket) = "date_trunc('${bucket.name.lowercase()}', $expression)"

        /** `round(double precision, int)` doesn't exist; only numeric rounds to a number of decimals. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            if (function == SelectFunction.Round) "ROUND(CAST($expression AS NUMERIC), ${arguments.single()})"
            else super.selectFunction(function, expression, arguments)

        override fun defaultNamespace(names: List<String>, database: String): String? = "public".takeIf { it in names } ?: names.firstOrNull()

        override val functions = listOf("now", "date_trunc", "to_char", "string_agg", "array_agg", "jsonb_build_object", "generate_series", "gen_random_uuid")

        /** Unquoted names fold to lower case, so anything else needs quotes. */
        override val plainIdentifier = Regex("[a-z_][a-z0-9_$]*")

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

        /**
         * Whether the server has PostgreSQL's own catalog and tooling: `pg_stat_activity`, `pg_blocking_pids`, the
         * `pg_get_*def` functions and a catalog `pg_dump` can read. Engines that only speak the wire protocol don't.
         */
        open val hasPostgresCatalog: Boolean = true

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
            meta.schemas.use { rs ->
                buildList { while (rs.next()) add(rs.getString("TABLE_SCHEM")) }
            }
                .filterNot { it in systemSchemas || isSystemSchema(it) || it.startsWith("pg_toast") || it.startsWith("pg_temp") }
                .sorted()
                .map { Namespace(catalog = null, schema = it) }

        protected open fun isSystemSchema(name: String): Boolean = false
    }

    /**
     * Amazon Redshift: PostgreSQL 8's SQL and wire protocol over a catalog of its own. No `gen_random_uuid`, and its
     * system schemas (`pg_internal`, `pg_automv`…) aren't the user's.
     */
    open class RedshiftDialect : PostgreSQLDialect() {
        override fun toString() = "Redshift"

        override val hasPostgresCatalog = false

        override val functions =
            listOf("getdate", "sysdate", "date_trunc", "dateadd", "datediff", "listagg", "json_extract_path_text", "approximate", "nvl")

        override fun isSystemSchema(name: String) = name.startsWith("pg_")
    }

    /**
     * QuestDB through its PostgreSQL wire endpoint: time-series SQL with no schemas beyond `public`, no keys and no
     * transactions. `date_trunc` and ILIKE work as in PostgreSQL.
     */
    open class QuestDBDialect : PostgreSQLDialect() {
        override fun toString() = "QuestDB"

        override val hasPostgresCatalog = false

        override val tableTypes = mapOf("TABLE" to false, "VIEW" to true, "MATERIALIZED VIEW" to true)

        override val functions = listOf("now", "systimestamp", "date_trunc", "timestamp_floor", "dateadd", "datediff", "to_str", "count_distinct")

        /** LIKE has no ESCAPE clause here, as on ClickHouse: a backslash escapes wildcards. */
        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to ""

        override fun textPattern(likePattern: String, caseSensitive: Boolean): String = backslashEscapes(likePattern)

        override fun samplePattern(prefix: String): String = backslashSamplePattern(prefix)

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithoutEscape(table, column, limit, scan)

        /** `~` and `!~` take Java regexes, but there's no `~*`: case is folded with an inline flag. */
        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "!" else ""}~ ${if (caseSensitive) "" else "concat('(?i)', "}" to (if (caseSensitive) "" else ")")

        /** No OFFSET: `LIMIT lo, hi` returns rows lo+1 to hi. */
        override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
            selectAll(table, filter, orderBy, projection) + " LIMIT $offset, ${offset + limit}"

        /** `round(double, int)` is native here; there's no NUMERIC type to cast to. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            if (function == SelectFunction.Round) "round($expression, ${arguments.single()})"
            else super.selectFunction(function, expression, arguments)

        override fun castToText(expression: String) = "CAST($expression AS STRING)"
    }

    /** CockroachDB speaks PostgreSQL's SQL and catalog, mostly; what differs is in its structure reader. */
    open class CockroachDBDialect : PostgreSQLDialect() {
        override fun toString() = "CockroachDB"

        override val hasPostgresCatalog = false

        override val functions = super.functions + listOf("unique_rowid", "gen_random_ulid", "crdb_internal.cluster_id")
    }

    open class MySQLDialect : SqlDialect(SqlSyntax.MySQL) {
        override fun toString() = "MySQL"

        override fun timeBucket(expression: String, bucket: TimeBucket) =
            when (bucket) {
                TimeBucket.Hour -> "STR_TO_DATE(DATE_FORMAT($expression, '%Y-%m-%d %H:00:00'), '%Y-%m-%d %H:%i:%s')"
                TimeBucket.Day -> "DATE($expression)"
                TimeBucket.Week -> "DATE(DATE_SUB($expression, INTERVAL WEEKDAY($expression) DAY))"
                TimeBucket.Month -> "DATE(DATE_FORMAT($expression, '%Y-%m-01'))"
                TimeBucket.Quarter -> "MAKEDATE(YEAR($expression), 1) + INTERVAL (QUARTER($expression) - 1) QUARTER"
                TimeBucket.Year -> "MAKEDATE(YEAR($expression), 1)"
            }

        /** A namespace is a database here; the one connected to comes first. */
        override fun defaultNamespace(names: List<String>, database: String): String? = database.takeIf { it in names } ?: names.firstOrNull()

        override val functions = listOf("now", "date_format", "group_concat", "ifnull", "json_object", "json_extract", "uuid")

        private val systemDatabases = setOf("information_schema", "performance_schema", "mysql", "sys")

        override fun quoteIdentifier(name: String) = "`${name.replace("`", "``")}`"

        override fun castToText(expression: String) = "CAST($expression AS CHAR)"

        /** LENGTH counts bytes here; CHAR_LENGTH counts characters, as everywhere else. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            if (function == SelectFunction.Length) "CHAR_LENGTH($expression)" else super.selectFunction(function, expression, arguments)

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

    /**
     * Doris and StarRocks: MySQL's SQL and protocol, but LIKE takes no ESCAPE clause (a backslash escapes, as MySQL's
     * default) and BINARY casts don't exist, so case-sensitive matching relies on LIKE, which is case-sensitive here, as
     * is REGEXP.
     */
    open class MySQLAnalyticsDialect : MySQLDialect() {
        override fun toString() = "MySQL analytics"

        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> {
            val not = if (negated) "NOT " else ""
            return if (caseSensitive) "$expression ${not}LIKE " to "" else "LOWER($expression) ${not}LIKE LOWER(" to ")"
        }

        override fun textPattern(likePattern: String, caseSensitive: Boolean): String = backslashEscapes(likePattern)

        override fun samplePattern(prefix: String): String = backslashSamplePattern(prefix)

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithoutEscape(table, column, limit, scan)

        /** `INTERVAL n QUARTER` takes only a constant here, so the quarter's first day is spelled out. */
        override fun timeBucket(expression: String, bucket: TimeBucket) =
            if (bucket == TimeBucket.Quarter) "STR_TO_DATE(CONCAT(YEAR($expression), '-', (QUARTER($expression) - 1) * 3 + 1, '-01'), '%Y-%c-%d')"
            else super.timeBucket(expression, bucket)

        /** REGEXP is case-sensitive whatever the collation; an inline flag folds case. */
        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "NOT " else ""}REGEXP ${if (caseSensitive) "" else "concat('(?i)', "}" to (if (caseSensitive) "" else ")")
    }

    open class SQLiteDialect : SqlDialect(SqlSyntax.SQLite) {
        override fun toString() = "SQLite"

        /** SQLite has no date type: dates are text, and these return text in the same ISO form. */
        override fun timeBucket(expression: String, bucket: TimeBucket) =
            when (bucket) {
                TimeBucket.Hour -> "strftime('%Y-%m-%d %H:00:00', $expression)"
                TimeBucket.Day -> "date($expression)"
                // To the next Sunday (or the same day), then back to that week's Monday.
                TimeBucket.Week -> "date($expression, 'weekday 0', '-6 days')"
                TimeBucket.Month -> "strftime('%Y-%m-01', $expression)"
                TimeBucket.Quarter -> "printf('%s-%02d-01', strftime('%Y', $expression), (CAST(strftime('%m', $expression) AS INTEGER) - 1) / 3 * 3 + 1)"
                TimeBucket.Year -> "strftime('%Y-01-01', $expression)"
            }

        /** Plain EXPLAIN shows bytecode; this is the readable plan. */
        override fun explain(sql: String): List<String> = listOf("EXPLAIN QUERY PLAN $sql")

        override val functions = listOf("datetime", "strftime", "group_concat", "ifnull", "json_extract", "random")

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

        /** SUBSTRING only arrived in 3.34; substr is in every version. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            if (function == SelectFunction.Substring) "substr($expression, ${arguments.joinToString()})" else super.selectFunction(function, expression, arguments)

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> = listOf(Namespace(catalog = null, schema = null))
    }

    /**
     * Standard SQL, with namespaces and keys from JDBC metadata: H2, HSQLDB, Derby, and drivers the user brings. Engines
     * that do better override what they do differently.
     */
    open class GenericDialect(syntax: SqlSyntax = SqlSyntax.Standard) : SqlDialect(syntax) {
        override fun toString() = "Generic"

        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        /** SQL:2008 paging, which every engine in this family speaks (LIMIT is a common but non-standard extension). */
        override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
            selectAll(table, filter, orderBy, projection) + " OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
            val col = quoteIdentifier(column)
            return "SELECT DISTINCT v FROM (SELECT $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
                "${castToText(col)} LIKE ? ESCAPE '!' FETCH FIRST $scan ROWS ONLY) s ORDER BY v FETCH FIRST $limit ROWS ONLY"
        }

        override fun castToText(expression: String) = "CAST($expression AS VARCHAR(4000))"

        override val supportsRegex = false

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String>? = null

        override fun defaultNamespace(names: List<String>, database: String): String? =
            names.firstOrNull { it.equals("PUBLIC", ignoreCase = true) } ?: names.firstOrNull { it.equals("APP", ignoreCase = true) } ?: names.firstOrNull()

        /** Schemas where the engine has them, else catalogs, else the one unnamed namespace; system ones left out. */
        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
            fun ResultSet.names(column: String) = use { rs -> buildList { while (rs.next()) rs.getString(column)?.let(::add) } }
            val schemas = runCatching { meta.schemas.names("TABLE_SCHEM") }.getOrDefault(emptyList()).filterNot(::isSystem)
            if (schemas.isNotEmpty()) return schemas.sorted().map { Namespace(catalog = null, schema = it) }
            val catalogs = runCatching { meta.catalogs.names("TABLE_CAT") }.getOrDefault(emptyList()).filterNot(::isSystem)
            if (catalogs.isNotEmpty()) return catalogs.sorted().map { Namespace(catalog = it, schema = null) }
            return listOf(Namespace(catalog = null, schema = null))
        }

        protected open fun isSystem(name: String): Boolean =
            name.equals("INFORMATION_SCHEMA", ignoreCase = true) || name.uppercase() in SystemSchemas || name.startsWith("SYS", ignoreCase = true)

        private companion object {
            val SystemSchemas = setOf("NULLID", "SQLJ", "SYSTEM_LOBS")
        }
    }

    /** T-SQL: bracket quoting, OFFSET/FETCH paging (which needs an ORDER BY), schemas inside one database. */
    open class SqlServerDialect : SqlDialect(SqlSyntax.TSql) {
        override fun toString() = "SQL Server"

        /** Without DATETRUNC, which needs SQL Server 2022. */
        override fun timeBucket(expression: String, bucket: TimeBucket) =
            when (bucket) {
                TimeBucket.Hour -> "DATEADD(hour, DATEDIFF(hour, 0, $expression), 0)"
                TimeBucket.Day -> "CAST($expression AS date)"
                // Independent of SET DATEFIRST: days since a known Monday (1900-01-01), modulo 7.
                TimeBucket.Week -> "DATEADD(day, -(DATEDIFF(day, 0, $expression) % 7), CAST($expression AS date))"
                TimeBucket.Month -> "DATEFROMPARTS(YEAR($expression), MONTH($expression), 1)"
                TimeBucket.Quarter -> "DATEFROMPARTS(YEAR($expression), (DATEPART(quarter, $expression) - 1) * 3 + 1, 1)"
                TimeBucket.Year -> "DATEFROMPARTS(YEAR($expression), 1, 1)"
            }

        override fun quoteIdentifier(name: String) = "[${name.replace("]", "]]")}]"

        /** With DISTINCT, ORDER BY may only name what's selected, so the stand-in order is the first column. */
        override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String {
            val order = if (orderBy.isNotEmpty()) "" else if (projection?.distinct == true) " ORDER BY 1" else " ORDER BY (SELECT NULL)"
            return selectAll(table, filter, orderBy, projection) + order + " OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"
        }

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
            val col = quoteIdentifier(column)
            return "SELECT DISTINCT TOP $limit v FROM (SELECT TOP $scan $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
                "${castToText(col)} LIKE ? ESCAPE '!') s ORDER BY v"
        }

        override fun castToText(expression: String) = "CAST($expression AS NVARCHAR(4000))"

        /** LEN, and LTRIM/RTRIM for servers before 2017's TRIM. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            when (function) {
                SelectFunction.Length -> "LEN($expression)"
                SelectFunction.Trim -> "LTRIM(RTRIM($expression))"
                else -> super.selectFunction(function, expression, arguments)
            }

        /** T-SQL has no boolean literals; BIT columns take 1 and 0. */
        override fun literal(param: SqlParam): String = (param.value as? Boolean)?.let { if (it) "1" else "0" } ?: super.literal(param)

        /** A case-sensitive collation on the column decides, whatever the database's default. */
        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            if (caseSensitive) "$expression COLLATE Latin1_General_CS_AS ${if (negated) "NOT " else ""}LIKE " to " ESCAPE '!'"
            else super.textMatch(expression, caseSensitive = false, negated)

        override val supportsRegex = false

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String>? = null

        /** Plans come back as rows while SHOWPLAN is on, which has to be a batch of its own. */
        override fun explain(sql: String): List<String> = listOf("SET SHOWPLAN_TEXT ON", sql, "SET SHOWPLAN_TEXT OFF")

        override val functions = listOf("getdate", "sysdatetime", "dateadd", "datediff", "format", "isnull", "string_agg", "newid", "convert", "iif")

        override fun defaultNamespace(names: List<String>, database: String): String? = "dbo".takeIf { it in names } ?: names.firstOrNull()

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
            meta.schemas.use { rs -> buildList { while (rs.next()) add(rs.getString("TABLE_SCHEM")) } }
                .filterNot { it in SystemSchemas || it.startsWith("db_") }
                .sorted()
                .map { Namespace(catalog = null, schema = it) }

        private companion object {
            val SystemSchemas = setOf("sys", "INFORMATION_SCHEMA", "guest")
        }
    }

    /** ClickHouse: databases as namespaces, ILIKE and RE2 regexes, no transactions. */
    open class ClickHouseDialect : SqlDialect(SqlSyntax.ClickHouse) {
        override fun toString() = "ClickHouse"

        override fun timeBucket(expression: String, bucket: TimeBucket) =
            when (bucket) {
                TimeBucket.Hour -> "toStartOfHour($expression)"
                TimeBucket.Day -> "toDate($expression)"
                TimeBucket.Week -> "toMonday($expression)"
                TimeBucket.Month -> "toStartOfMonth($expression)"
                TimeBucket.Quarter -> "toStartOfQuarter($expression)"
                TimeBucket.Year -> "toStartOfYear($expression)"
            }

        override fun quoteIdentifier(name: String) = "`${name.replace("\\", "\\\\").replace("`", "\\`")}`"

        override fun escapeString(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

        override fun castToText(expression: String) = "toString($expression)"

        /** Character-wise (UTF-8) text functions, and the ones under ClickHouse's own names. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            when (function) {
                SelectFunction.Upper -> "upperUTF8($expression)"
                SelectFunction.Lower -> "lowerUTF8($expression)"
                SelectFunction.Trim -> "trimBoth($expression)"
                SelectFunction.Length -> "lengthUTF8($expression)"
                SelectFunction.Substring -> "substringUTF8($expression, ${arguments.joinToString()})"
                SelectFunction.Ceil -> "ceil($expression)"
                else -> super.selectFunction(function, expression, arguments)
            }

        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to ""

        /** ClickHouse's LIKE has no ESCAPE clause: wildcards are escaped with a backslash. */
        override fun textPattern(likePattern: String, caseSensitive: Boolean): String = backslashEscapes(likePattern)

        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "${if (negated) "NOT " else ""}match($expression, ${if (caseSensitive) "" else "concat('(?i)', "}" to (if (caseSensitive) ")" else "))")

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithoutEscape(table, column, limit, scan)

        override fun samplePattern(prefix: String): String = backslashSamplePattern(prefix)

        override val functions = listOf("now", "today", "toDate", "toStartOfDay", "formatDateTime", "uniq", "groupArray", "arrayJoin", "JSONExtractString", "countIf")

        override fun defaultNamespace(names: List<String>, database: String): String? = database.takeIf { it in names } ?: "default".takeIf { it in names } ?: names.firstOrNull()

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
            fun ResultSet.names(column: String) = use { rs -> buildList { while (rs.next()) rs.getString(column)?.let(::add) } }
            val schemas = meta.schemas.names("TABLE_SCHEM").filterNot { it in SystemDatabases }
            if (schemas.isNotEmpty()) return schemas.sorted().map { Namespace(catalog = null, schema = it) }
            return meta.catalogs.names("TABLE_CAT").filterNot { it in SystemDatabases }.sorted().map { Namespace(catalog = it, schema = null) }
        }

        private companion object {
            val SystemDatabases = setOf("system", "INFORMATION_SCHEMA", "information_schema")
        }
    }

    /** DuckDB: PostgreSQL-like SQL in an embedded engine; namespaces are schemas, `main` by default. */
    open class DuckDBDialect : SqlDialect(SqlSyntax.PostgreSQL) {
        override fun toString() = "DuckDB"

        override fun timeBucket(expression: String, bucket: TimeBucket) = "date_trunc('${bucket.name.lowercase()}', $expression)"

        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        override fun castToText(expression: String) = "CAST($expression AS VARCHAR)"

        override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to " ESCAPE '!'"

        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "${if (negated) "NOT " else ""}regexp_matches($expression, " to (if (caseSensitive) ")" else ", 'i')")

        override val functions = listOf("now", "date_trunc", "strftime", "string_agg", "list", "unnest", "read_parquet", "read_csv_auto", "regexp_matches")

        override fun defaultNamespace(names: List<String>, database: String): String? = "main".takeIf { it in names } ?: names.firstOrNull()

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
            meta.schemas.use { rs -> buildList { while (rs.next()) add(rs.getString("TABLE_SCHEM")) } }
                .filterNot { it in setOf("information_schema", "pg_catalog") }
                .distinct()
                .sorted()
                .map { Namespace(catalog = null, schema = it) }
    }


    /**
     * Oracle 12c and later: schemas are users, unquoted names fold to upper case, OFFSET/FETCH paging, and no LIMIT. The
     * connected user's schema is listed first, as that's where unqualified names resolve.
     */
    open class OracleDialect : SqlDialect(SqlSyntax.Standard) {
        override fun toString() = "Oracle"

        override val plainIdentifier = Regex("[A-Z][A-Z0-9_$#]*")

        override val tableTypes = mapOf("TABLE" to false, "VIEW" to true, "MATERIALIZED VIEW" to true)

        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        override fun timeBucket(expression: String, bucket: TimeBucket) =
            when (bucket) {
                TimeBucket.Hour -> "TRUNC($expression, 'HH24')"
                TimeBucket.Day -> "TRUNC($expression)"
                // IW: the ISO week, which starts on Monday whatever NLS_TERRITORY says.
                TimeBucket.Week -> "TRUNC($expression, 'IW')"
                TimeBucket.Month -> "TRUNC($expression, 'MM')"
                TimeBucket.Quarter -> "TRUNC($expression, 'Q')"
                TimeBucket.Year -> "TRUNC($expression, 'YYYY')"
            }

        override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
            selectAll(table, filter, orderBy, projection) + " OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"

        override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
            val col = quoteIdentifier(column)
            return "SELECT DISTINCT v FROM (SELECT $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
                "${castToText(col)} LIKE ? ESCAPE '!' FETCH FIRST $scan ROWS ONLY) s ORDER BY v FETCH FIRST $limit ROWS ONLY"
        }

        /** TO_CHAR takes numbers, dates and CLOBs alike, where CAST to VARCHAR2 fails on long CLOBs. */
        override fun castToText(expression: String) = "TO_CHAR($expression)"

        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            when (function) {
                SelectFunction.Substring -> "SUBSTR($expression, ${arguments.joinToString()})"
                SelectFunction.Ceil -> "CEIL($expression)"
                else -> super.selectFunction(function, expression, arguments)
            }

        /** BOOLEAN only arrived in 23ai; NUMBER(1) flags take 1 and 0 everywhere. */
        override fun literal(param: SqlParam): String = (param.value as? Boolean)?.let { if (it) "1" else "0" } ?: super.literal(param)

        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "${if (negated) "NOT " else ""}REGEXP_LIKE($expression, " to ", '${if (caseSensitive) "c" else "i"}')"

        /** The plan goes to PLAN_TABLE; DBMS_XPLAN reads it back as rows. */
        override fun explain(sql: String): List<String> = listOf("EXPLAIN PLAN FOR $sql", "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())")

        override val functions = listOf("sysdate", "systimestamp", "to_char", "to_date", "nvl", "nvl2", "decode", "listagg", "regexp_like", "sys_guid", "trunc")

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
            val connection = meta.connection
            val current = runCatching { connection.scalar("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL") }.getOrNull()
            // ORACLE_MAINTAINED (12c+) is what tells SYS, MDSYS and friends apart from the application's schemas.
            val schemas =
                runCatching {
                    connection.prepareStatement("SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'N' ORDER BY USERNAME").use { s ->
                        s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                }.getOrElse { meta.schemas.use { rs -> buildList { while (rs.next()) add(rs.getString("TABLE_SCHEM")) } }.filterNot { it in SystemSchemas } }
            return (listOfNotNull(current) + schemas.sorted()).distinct().map { Namespace(catalog = null, schema = it) }
        }

        private fun java.sql.Connection.scalar(sql: String): String? =
            createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

        private companion object {
            val SystemSchemas =
                setOf(
                    "SYS", "SYSTEM", "XDB", "MDSYS", "CTXSYS", "ORDSYS", "ORDDATA", "OLAPSYS", "WMSYS", "LBACSYS", "DVSYS", "AUDSYS", "GSMADMIN_INTERNAL",
                    "OUTLN", "DBSNMP", "APPQOSSYS", "OJVMSYS", "DBSFWUSER", "GGSYS", "ANONYMOUS", "XS\$NULL", "REMOTE_SCHEDULER_AGENT", "SYSBACKUP", "SYSDG",
                    "SYSKM", "SYSRAC", "SYS\$UMF", "DIP", "GSMCATUSER", "GSMUSER", "GSMROOTUSER", "ORACLE_OCM", "MDDATA", "DGPDB_INT", "VECSYS",
                )
        }
    }

    /**
     * Firebird 3 and later: standard SQL with OFFSET/FETCH, one unnamed namespace (schemas only arrive in Firebird 6),
     * and SIMILAR TO instead of POSIX regular expressions, which filters don't offer.
     */
    open class FirebirdDialect : GenericDialect() {
        override fun toString() = "Firebird"

        override val plainIdentifier = Regex("[A-Z][A-Z0-9_$]*")

        /** Without DATE_TRUNC: dates rebuilt from their parts with DATEADD. EXTRACT(WEEKDAY) counts from Sunday = 0,
         * YEARDAY from 1 January = 0. */
        override fun timeBucket(expression: String, bucket: TimeBucket): String? {
            val date = "CAST($expression AS DATE)"
            return when (bucket) {
                TimeBucket.Hour -> null
                TimeBucket.Day -> date
                TimeBucket.Week -> "DATEADD(-MOD(EXTRACT(WEEKDAY FROM $expression) + 6, 7) DAY TO $date)"
                TimeBucket.Month -> "DATEADD(1 - EXTRACT(DAY FROM $expression) DAY TO $date)"
                TimeBucket.Quarter -> "DATEADD(-MOD(EXTRACT(MONTH FROM $expression) - 1, 3) MONTH TO DATEADD(1 - EXTRACT(DAY FROM $expression) DAY TO $date))"
                TimeBucket.Year -> "DATEADD(-EXTRACT(YEARDAY FROM $expression) DAY TO $date)"
            }
        }

        /** LENGTH is CHAR_LENGTH here, SUBSTRING takes FROM/FOR, and CEILING is CEIL. */
        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            when (function) {
                SelectFunction.Length -> "CHAR_LENGTH($expression)"
                SelectFunction.Substring -> "SUBSTRING($expression FROM ${arguments[0]}" + (arguments.getOrNull(1)?.let { " FOR $it" } ?: "") + ")"
                SelectFunction.Ceil -> "CEIL($expression)"
                else -> super.selectFunction(function, expression, arguments)
            }

        /** Plans come from Jaybird's statement API, not from SQL. */
        override fun explain(sql: String): List<String> = throw UnsupportedOperationException(tr("Firebird has no EXPLAIN statement"))

        override val functions = listOf("current_timestamp", "dateadd", "datediff", "list", "coalesce", "iif", "gen_uuid", "uuid_to_char", "gen_id")

        override fun isSystem(name: String): Boolean = super.isSystem(name) || name.startsWith("RDB$", ignoreCase = true) || name.startsWith("MON$", ignoreCase = true)
    }

    /**
     * Trino: one catalog per profile (the database field's `catalog`), its schemas as namespaces. Tables have no keys and,
     * through most connectors, no transactions.
     */
    open class TrinoDialect : SqlDialect(SqlSyntax.Standard) {
        override fun toString() = "Trino"

        override val plainIdentifier = Regex("[a-z_][a-z0-9_]*")

        override fun quoteIdentifier(name: String) = "\"${name.replace("\"", "\"\"")}\""

        override fun timeBucket(expression: String, bucket: TimeBucket) = "date_trunc('${bucket.name.lowercase()}', $expression)"

        override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
            selectAll(table, filter, orderBy, projection) + " OFFSET $offset LIMIT $limit"

        override fun castToText(expression: String) = "CAST($expression AS VARCHAR)"

        override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
            when (function) {
                SelectFunction.Substring -> "substr($expression, ${arguments.joinToString()})"
                SelectFunction.Ceil -> "ceil($expression)"
                else -> super.selectFunction(function, expression, arguments)
            }

        override val regexHonoursCase = true

        override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
            "${if (negated) "NOT " else ""}regexp_like($expression, ${if (caseSensitive) "" else "concat('(?i)', "}" to (if (caseSensitive) ")" else "))")

        override val functions = listOf("now", "current_timestamp", "date_trunc", "date_format", "approx_distinct", "array_agg", "json_extract_scalar", "try_cast", "regexp_like")

        override fun defaultNamespace(names: List<String>, database: String): String? =
            database.substringAfter('/', "").trim().takeIf { it in names } ?: "default".takeIf { it in names } ?: "public".takeIf { it in names } ?: names.firstOrNull()

        override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
            val catalog = meta.connection.catalog?.takeIf { it.isNotEmpty() }
            return meta.getSchemas(catalog, null).use { rs ->
                buildList { while (rs.next()) add(rs.getString("TABLE_CATALOG") to rs.getString("TABLE_SCHEM")) }
            }
                .filter { (cat, schema) -> cat != "system" && schema != "information_schema" }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .map { (cat, schema) -> Namespace(catalog = cat, schema = schema) }
        }
    }

    companion object {
        val PostgreSQL: PostgreSQLDialect = PostgreSQLDialect()
        val MySQL: MySQLDialect = MySQLDialect()
        val SQLite: SQLiteDialect = SQLiteDialect()
        val Generic: GenericDialect = GenericDialect()
        val SqlServer: SqlServerDialect = SqlServerDialect()
        val ClickHouse: ClickHouseDialect = ClickHouseDialect()
        val DuckDB: DuckDBDialect = DuckDBDialect()
        val CockroachDB: CockroachDBDialect = CockroachDBDialect()
        val Redshift: RedshiftDialect = RedshiftDialect()
        val QuestDB: QuestDBDialect = QuestDBDialect()
        val MySQLAnalytics: MySQLAnalyticsDialect = MySQLAnalyticsDialect()
        val Oracle: OracleDialect = OracleDialect()
        val Firebird: FirebirdDialect = FirebirdDialect()
        val Trino: TrinoDialect = TrinoDialect()
        val Snowflake: SnowflakeDialect = SnowflakeDialect()
        val SparkSql: SparkSqlDialect = SparkSqlDialect()
        val Hive: HiveDialect = HiveDialect()
        val Impala: ImpalaDialect = ImpalaDialect()
        val BigQuery: BigQueryDialect = BigQueryDialect()
        val Spanner: SpannerDialect = SpannerDialect()
        val Db2: Db2Dialect = Db2Dialect()
        val Hana: HanaDialect = HanaDialect()
        val Vertica: VerticaDialect = VerticaDialect()
        val Exasol: ExasolDialect = ExasolDialect()
        val Informix: InformixDialect = InformixDialect()
        val Iris: IrisDialect = IrisDialect()
        val Access: AccessDialect = AccessDialect()

        fun of(family: EngineFamily): SqlDialect? =
            when (family) {
                EngineFamily.PostgreSQL -> PostgreSQL
                EngineFamily.MySQL -> MySQL
                EngineFamily.SQLite -> SQLite
                EngineFamily.Generic -> Generic
                EngineFamily.SqlServer -> SqlServer
                EngineFamily.ClickHouse -> ClickHouse
                EngineFamily.DuckDB -> DuckDB
                EngineFamily.Oracle -> Oracle
                EngineFamily.Firebird -> Firebird
                EngineFamily.Trino -> Trino
                EngineFamily.Hive -> Hive
                EngineFamily.Redis,
                EngineFamily.MongoDB,
                EngineFamily.Etcd,
                EngineFamily.Elasticsearch,
                EngineFamily.Meilisearch,
                EngineFamily.Cassandra,
                EngineFamily.CouchDB,
                EngineFamily.DynamoDB,
                EngineFamily.InfluxDB,
                EngineFamily.Prometheus,
                EngineFamily.Neo4j,
                EngineFamily.InfluxDB3,
                EngineFamily.Solr,
                EngineFamily.Qdrant,
                EngineFamily.RabbitMQ,
                EngineFamily.Kafka,
                EngineFamily.ZooKeeper,
                EngineFamily.Consul,
                EngineFamily.SqliteHttp,
                EngineFamily.Weaviate,
                EngineFamily.Chroma,
                EngineFamily.Milvus -> null
            }

        fun of(type: DatabaseType): SqlDialect = type.dialect ?: throw IllegalArgumentException(tr("{0} has no SQL dialect", type.displayName))
    }
}

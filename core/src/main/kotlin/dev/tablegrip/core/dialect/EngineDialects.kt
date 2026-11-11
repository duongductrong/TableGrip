package dev.tablegrip.core.dialect

import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.SqlParam
import dev.tablegrip.core.filter.SqlProjection
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.sql.SqlSyntax
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

private val IntegerTypes = setOf(Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT)

// Dialects of engines that reach TableGrip through a JDBC driver of their own, each in the Generic family (or, for
// HiveServer2's engines, the Hive one). They start from standard SQL and override what their engine does differently.

/** The first column of the first row, or null if the query fails or returns nothing. */
private fun Connection.scalar(sql: String): String? =
    runCatching { createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } } }.getOrNull()

private fun ResultSet.strings(column: String): List<String> = use { rs -> buildList { while (rs.next()) rs.getString(column)?.let(::add) } }

/** Schemas with their catalogs, for engines whose JDBC metadata spans catalogs (Snowflake databases, Unity catalogs). */
private fun DatabaseMetaData.schemasWithCatalogs(catalog: String?): List<Pair<String?, String>> =
    getSchemas(catalog, null).use { rs ->
        buildList { while (rs.next()) add(rs.getString("TABLE_CATALOG")?.takeIf { it.isNotBlank() } to rs.getString("TABLE_SCHEM")) }
    }

/** For engines that run EXPLAIN only through their own tools or tables: the UI doesn't offer it ([dev.tablegrip.core.connection.Capability.Explain]). */
private fun noExplain(engine: String): Nothing = throw UnsupportedOperationException(tr("{0} has no EXPLAIN statement TableGrip can read", engine))

/**
 * Snowflake: names fold to upper case, a database per profile (the database field's `DATABASE/SCHEMA`) with its schemas
 * as namespaces, ILIKE, and regular expressions that must be searched for rather than matched whole.
 */
open class SnowflakeDialect : SqlDialect.GenericDialect() {
    override fun toString() = "Snowflake"

    override val plainIdentifier = Regex("[A-Z_][A-Z0-9_$]*")

    override val tableTypes = mapOf("TABLE" to false, "VIEW" to true, "MATERIALIZED_VIEW" to true)

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "TO_VARCHAR($expression)"

    override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to " ESCAPE '!'"

    override fun timeBucket(expression: String, bucket: TimeBucket) = "DATE_TRUNC('${bucket.name.lowercase()}', $expression)"

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        if (function == SelectFunction.Ceil) "CEIL($expression)" else super.selectFunction(function, expression, arguments)

    override val supportsRegex = true

    override val regexHonoursCase = true

    /** REGEXP_LIKE matches the whole value; REGEXP_INSTR finds the pattern anywhere, as the other engines do. */
    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}REGEXP_INSTR($expression, " to ", 1, 1, 0, '${if (caseSensitive) "c" else "i"}') > 0"

    override val functions = listOf("current_timestamp", "date_trunc", "dateadd", "datediff", "to_varchar", "iff", "listagg", "array_agg", "parse_json", "flatten", "uuid_string")

    override fun defaultNamespace(names: List<String>, database: String): String? =
        database.substringAfter('/', "").trim().takeIf { it in names } ?: "PUBLIC".takeIf { it in names } ?: names.firstOrNull()

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
        meta.schemasWithCatalogs(meta.connection.catalog?.takeIf { it.isNotEmpty() })
            .filter { (_, schema) -> schema != "INFORMATION_SCHEMA" }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (catalog, schema) -> Namespace(catalog, schema) }
}

/**
 * Spark SQL, for Databricks, Spark's Thrift server and Kyuubi: backtick names, backslash escapes, Java regular
 * expressions with RLIKE, LIMIT and OFFSET (Spark 3.4 and later). Namespaces are databases, inside a catalog where the
 * server has catalogs (Unity Catalog).
 */
open class SparkSqlDialect : SqlDialect.GenericDialect(SqlSyntax.SparkSql) {
    override fun toString() = "Spark SQL"

    override val plainIdentifier = Regex("[a-z_][a-z0-9_]*")

    override fun quoteIdentifier(name: String) = "`${name.replace("`", "``")}`"

    override fun escapeString(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "CAST($expression AS STRING)"

    override fun timeBucket(expression: String, bucket: TimeBucket): String? = "date_trunc('${bucket.name.uppercase()}', $expression)"

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Ceil -> "ceil($expression)"
            SelectFunction.Substring -> "substr($expression, ${arguments.joinToString()})"
            else -> super.selectFunction(function, expression, arguments)
        }

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}$expression RLIKE ${if (caseSensitive) "" else "concat('(?i)', "}" to (if (caseSensitive) "" else ")")

    override fun explain(sql: String): List<String> = listOf("EXPLAIN $sql")

    override val functions = listOf("current_timestamp", "date_trunc", "date_format", "collect_list", "collect_set", "explode", "from_json", "get_json_object", "regexp_extract", "nvl")

    override fun defaultNamespace(names: List<String>, database: String): String? =
        database.substringAfterLast('/').trim().takeIf { it in names } ?: "default".takeIf { it in names } ?: names.firstOrNull()

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
        meta.schemasWithCatalogs(null)
            .filter { (catalog, schema) -> schema != "information_schema" && catalog != "system" && catalog != "samples" }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (catalog, schema) -> Namespace(catalog, schema) }

    /** A catalog of Unity Catalog's, before its schema; HiveServer2's own engines report none. */
    override fun qualifiedName(table: TableRef): String =
        listOfNotNull(table.namespace.catalog?.takeIf { it.isNotBlank() }, table.namespace.schema, table.name).joinToString(".", transform = ::quoteIdentifier)
}

/**
 * Hive: Spark's SQL, mostly, but paging is `LIMIT offset, rows`, LIKE has no ESCAPE clause (a backslash escapes) and
 * dates round down with `trunc` and friends rather than `date_trunc`.
 */
open class HiveDialect : SparkSqlDialect() {
    override fun toString() = "Hive"

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        selectAll(table, filter, orderBy, projection) + " LIMIT $offset, $limit"

    override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> {
        val not = if (negated) "NOT " else ""
        return if (caseSensitive) "$expression ${not}LIKE " to "" else "lower($expression) ${not}LIKE lower(" to ")"
    }

    override fun textPattern(likePattern: String, caseSensitive: Boolean): String = backslashEscapes(likePattern)

    override fun samplePattern(prefix: String): String = backslashSamplePattern(prefix)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithoutEscape(table, column, limit, scan)

    /** 1900-01-01 was a Monday, which weeks are counted from. */
    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        when (bucket) {
            TimeBucket.Hour -> "date_format($expression, 'yyyy-MM-dd HH:00:00')"
            TimeBucket.Day -> "to_date($expression)"
            TimeBucket.Week -> "date_sub(to_date($expression), pmod(datediff(to_date($expression), '1900-01-01'), 7))"
            TimeBucket.Month -> "trunc($expression, 'MM')"
            TimeBucket.Quarter -> "trunc($expression, 'Q')"
            TimeBucket.Year -> "trunc($expression, 'YYYY')"
        }

    override val functions = listOf("current_timestamp", "date_format", "from_unixtime", "unix_timestamp", "collect_list", "collect_set", "explode", "get_json_object", "regexp_extract", "nvl")
}

/**
 * Impala through HiveServer2's protocol: OFFSET needs an ORDER BY, LIKE escapes with a backslash, and ILIKE and
 * `date_trunc` exist.
 */
open class ImpalaDialect : HiveDialect() {
    override fun toString() = "Impala"

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        selectAll(table, filter, orderBy, projection) + (if (orderBy.isEmpty()) " ORDER BY 1" else "") + " LIMIT $limit OFFSET $offset"

    override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to ""

    override fun timeBucket(expression: String, bucket: TimeBucket): String = "date_trunc('${bucket.name.lowercase()}', $expression)"

    override val functions = listOf("now", "date_trunc", "trunc", "from_unixtime", "unix_timestamp", "group_concat", "ndv", "regexp_extract", "nvl", "uuid")
}

/**
 * GoogleSQL, for BigQuery and Spanner: backtick names, backslash escapes, LIKE without an ESCAPE clause, RE2 regular
 * expressions, and LIMIT/OFFSET.
 */
abstract class GoogleSqlDialect : SqlDialect.GenericDialect(SqlSyntax.GoogleSql) {
    override val plainIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    override fun quoteIdentifier(name: String) = "`${name.replace("\\", "\\\\").replace("`", "\\`")}`"

    override fun escapeString(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> {
        val not = if (negated) "NOT " else ""
        return if (caseSensitive) "$expression ${not}LIKE " to "" else "LOWER($expression) ${not}LIKE LOWER(" to ")"
    }

    override fun textPattern(likePattern: String, caseSensitive: Boolean): String = backslashEscapes(likePattern)

    override fun samplePattern(prefix: String): String = backslashSamplePattern(prefix)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithoutEscape(table, column, limit, scan)

    override fun castToText(expression: String) = "CAST($expression AS STRING)"

    /** Strings never coerce to numbers here, so values read as text are bound as their column's type. */
    override fun bind(statement: PreparedStatement, index: Int, value: String?, jdbcType: Int) {
        when {
            value == null -> statement.setNull(index, jdbcType)
            jdbcType in IntegerTypes -> statement.setLong(index, value.toLong())
            jdbcType == Types.DOUBLE || jdbcType == Types.FLOAT || jdbcType == Types.REAL -> statement.setDouble(index, value.toDouble())
            jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL -> statement.setBigDecimal(index, value.toBigDecimal())
            jdbcType == Types.BOOLEAN || jdbcType == Types.BIT -> statement.setBoolean(index, value.toBoolean())
            else -> statement.setString(index, value)
        }
    }

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Ceil -> "CEIL($expression)"
            SelectFunction.Substring -> "SUBSTR($expression, ${arguments.joinToString()})"
            else -> super.selectFunction(function, expression, arguments)
        }

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}REGEXP_CONTAINS($expression, ${if (caseSensitive) "" else "CONCAT('(?i)', "}" to (if (caseSensitive) ")" else "))")

    override fun qualifiedName(table: TableRef): String =
        listOfNotNull(table.namespace.catalog, table.namespace.schema, table.name).filter { it.isNotEmpty() }.joinToString(".", transform = ::quoteIdentifier)
}

/** BigQuery: datasets as namespaces, inside the profile's project; no EXPLAIN statement. */
open class BigQueryDialect : GoogleSqlDialect() {
    override fun toString() = "BigQuery"

    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        "DATE_TRUNC($expression, ${if (bucket == TimeBucket.Week) "WEEK(MONDAY)" else bucket.name.uppercase()})"

    override fun explain(sql: String): List<String> = noExplain("BigQuery")

    override val functions = listOf("CURRENT_TIMESTAMP", "DATE_TRUNC", "FORMAT_TIMESTAMP", "SAFE_CAST", "ARRAY_AGG", "STRING_AGG", "JSON_VALUE", "UNNEST", "GENERATE_UUID", "IFNULL")

    override fun defaultNamespace(names: List<String>, database: String): String? = database.substringAfter('/', "").trim().takeIf { it in names } ?: names.firstOrNull()

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
        meta.schemasWithCatalogs(meta.connection.catalog?.takeIf { it.isNotEmpty() })
            .filter { (_, schema) -> !schema.equals("INFORMATION_SCHEMA", ignoreCase = true) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (catalog, schema) -> Namespace(catalog, schema) }
}

/**
 * Cloud Spanner's GoogleSQL: tables live in the unnamed default schema (shown as `main`), keys are enforced, and
 * timestamps round in UTC rather than Spanner's default America/Los_Angeles. Weeks are ISO weeks: Spanner takes no
 * `WEEK(MONDAY)`.
 */
open class SpannerDialect : GoogleSqlDialect() {
    override fun toString() = "Spanner"

    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        "TIMESTAMP_TRUNC($expression, ${if (bucket == TimeBucket.Week) "ISOWEEK" else bucket.name.uppercase()}, 'UTC')"

    /** ROUND takes FLOAT64 or NUMERIC; an INT64 column doesn't widen by itself. */
    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        if (function == SelectFunction.Round) "ROUND(CAST($expression AS FLOAT64), ${arguments.single()})" else super.selectFunction(function, expression, arguments)

    override fun explain(sql: String): List<String> = noExplain("Spanner")

    override val functions = listOf("CURRENT_TIMESTAMP", "TIMESTAMP_TRUNC", "FORMAT_TIMESTAMP", "SAFE_CAST", "ARRAY_AGG", "STRING_AGG", "JSON_VALUE", "UNNEST", "GENERATE_UUID", "PENDING_COMMIT_TIMESTAMP")

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> =
        meta.schemas.strings("TABLE_SCHEM")
            .filterNot { it == "INFORMATION_SCHEMA" || it == "SPANNER_SYS" || it == "pg_catalog" || it == "information_schema" }
            .sorted()
            .map { Namespace(catalog = null, schema = it) }
}

/** Standard SQL engines whose unquoted names fold to upper case and whose own schema comes first, as on Oracle. */
abstract class CurrentSchemaFirstDialect : SqlDialect.GenericDialect() {
    override val plainIdentifier = Regex("[A-Z][A-Z0-9_$#]*")

    /** The statement that returns the session's current schema. */
    protected abstract val currentSchemaQuery: String

    protected open fun isEngineSystem(name: String): Boolean = false

    override fun defaultNamespace(names: List<String>, database: String): String? = names.firstOrNull()

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
        val current = meta.connection.scalar(currentSchemaQuery)?.trim()
        val schemas = runCatching { meta.schemas.strings("TABLE_SCHEM") }.getOrDefault(emptyList()).filterNot { isSystem(it) || isEngineSystem(it) }
        return (listOfNotNull(current) + schemas.sorted()).distinct().map { Namespace(catalog = null, schema = it) }
    }
}

/**
 * IBM Db2 for Linux, Unix and Windows (11.1 and later): OFFSET/FETCH paging, REGEXP_LIKE, TRUNC_TIMESTAMP with
 * Oracle's format names, and lengths counted in characters rather than bytes.
 */
open class Db2Dialect : CurrentSchemaFirstDialect() {
    override fun toString() = "Db2"

    override val currentSchemaQuery = "SELECT CURRENT SCHEMA FROM SYSIBM.SYSDUMMY1"

    override fun isEngineSystem(name: String) = name == "NULLID" || name == "SQLJ" || name.startsWith("SYS")

    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        "TRUNC_TIMESTAMP($expression, '${when (bucket) {
            TimeBucket.Hour -> "HH24"
            TimeBucket.Day -> "DD"
            TimeBucket.Week -> "IW"
            TimeBucket.Month -> "MM"
            TimeBucket.Quarter -> "Q"
            TimeBucket.Year -> "YYYY"
        }}')"

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Length -> "CHARACTER_LENGTH($expression, CODEUNITS32)"
            SelectFunction.Substring -> "SUBSTRING($expression, ${arguments.joinToString()}, CODEUNITS32)"
            SelectFunction.Ceil -> "CEIL($expression)"
            else -> super.selectFunction(function, expression, arguments)
        }

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}REGEXP_LIKE($expression, " to ", '${if (caseSensitive) "c" else "i"}')"

    /** Plans go to explain tables the user has to create first. */
    override fun explain(sql: String): List<String> = noExplain("Db2")

    override val functions = listOf("current timestamp", "trunc_timestamp", "varchar_format", "timestamp_format", "listagg", "coalesce", "nullif", "regexp_like", "generate_unique", "days")
}

/** SAP HANA: LIMIT/OFFSET, LIKE_REGEXPR, and dates rebuilt from their parts (there's no DATE_TRUNC). */
open class HanaDialect : CurrentSchemaFirstDialect() {
    override fun toString() = "SAP HANA"

    override val currentSchemaQuery = "SELECT CURRENT_SCHEMA FROM DUMMY"

    override fun isEngineSystem(name: String) =
        name.startsWith("_SYS") || name.startsWith("SYS") || name.startsWith("SAP_") || name.startsWith("HANA_") || name == "UIS" || name.startsWith("XSSQLCC_")

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "TO_NVARCHAR($expression)"

    /** WEEKDAY counts from Monday = 0. */
    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        when (bucket) {
            TimeBucket.Hour -> "TO_TIMESTAMP(TO_VARCHAR($expression, 'YYYY-MM-DD HH24'), 'YYYY-MM-DD HH24')"
            TimeBucket.Day -> "TO_DATE($expression)"
            TimeBucket.Week -> "ADD_DAYS(TO_DATE($expression), -WEEKDAY($expression))"
            TimeBucket.Month -> "TO_DATE(TO_VARCHAR($expression, 'YYYY-MM') || '-01')"
            TimeBucket.Quarter -> "ADD_MONTHS(TO_DATE(TO_VARCHAR($expression, 'YYYY') || '-01-01'), MONTH($expression) - 1 - MOD(MONTH($expression) - 1, 3))"
            TimeBucket.Year -> "TO_DATE(TO_VARCHAR($expression, 'YYYY') || '-01-01')"
        }

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        if (function == SelectFunction.Ceil) "CEIL($expression)" else super.selectFunction(function, expression, arguments)

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "$expression ${if (negated) "NOT " else ""}LIKE_REGEXPR " to (if (caseSensitive) "" else " FLAG 'i'")

    /** Plans go to EXPLAIN_PLAN_TABLE, read back by statement name. */
    override fun explain(sql: String): List<String> = noExplain("SAP HANA")

    override val functions = listOf("current_timestamp", "add_days", "add_months", "to_varchar", "to_date", "string_agg", "ifnull", "map", "sysuuid", "series_generate_integer")
}

/** Vertica: PostgreSQL's SQL, nearly: LIMIT/OFFSET, ILIKE, DATE_TRUNC and REGEXP_LIKE with flags. */
open class VerticaDialect : SqlDialect.GenericDialect() {
    override fun toString() = "Vertica"

    override val tableTypes = mapOf("TABLE" to false, "VIEW" to true, "SYSTEM TABLE" to false)

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "CAST($expression AS VARCHAR)"

    override fun textMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "$expression ${if (negated) "NOT " else ""}${if (caseSensitive) "LIKE" else "ILIKE"} " to " ESCAPE '!'"

    override fun timeBucket(expression: String, bucket: TimeBucket) = "DATE_TRUNC('${bucket.name.lowercase()}', $expression)"

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        if (function == SelectFunction.Ceil) "CEIL($expression)" else super.selectFunction(function, expression, arguments)

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}REGEXP_LIKE($expression, " to ", '${if (caseSensitive) "c" else "i"}')"

    override val functions = listOf("now", "date_trunc", "to_char", "listagg", "time_slice", "approximate_count_distinct", "maptojson", "regexp_like", "uuid_generate")

    override fun defaultNamespace(names: List<String>, database: String): String? = "public".takeIf { it in names } ?: names.firstOrNull()

    override fun isSystem(name: String) = super.isSystem(name) || name.startsWith("v_")
}

/**
 * Exasol: names fold to upper case, LIMIT/OFFSET, Oracle's TRUNC formats, and PCRE regular expressions, which
 * REGEXP_LIKE matches against the whole value (so REGEXP_INSTR searches instead).
 */
open class ExasolDialect : SqlDialect.GenericDialect() {
    override fun toString() = "Exasol"

    override val plainIdentifier = Regex("[A-Z][A-Z0-9_]*")

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "TO_CHAR($expression)"

    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        "TRUNC($expression, '${when (bucket) {
            TimeBucket.Hour -> "HH"
            TimeBucket.Day -> "DD"
            TimeBucket.Week -> "IW"
            TimeBucket.Month -> "MM"
            TimeBucket.Quarter -> "Q"
            TimeBucket.Year -> "YYYY"
        }}')"

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Ceil -> "CEIL($expression)"
            SelectFunction.Substring -> "SUBSTR($expression, ${arguments.joinToString()})"
            else -> super.selectFunction(function, expression, arguments)
        }

    override val supportsRegex = true

    override val regexHonoursCase = true

    override fun regexMatch(expression: String, caseSensitive: Boolean, negated: Boolean): Pair<String, String> =
        "${if (negated) "NOT " else ""}REGEXP_INSTR($expression, ${if (caseSensitive) "" else "'(?i)' || "}" to ") > 0"

    /** Exasol profiles statements instead; there's no EXPLAIN. */
    override fun explain(sql: String): List<String> = noExplain("Exasol")

    override val functions = listOf("current_timestamp", "systimestamp", "trunc", "to_char", "to_date", "add_days", "group_concat", "listagg", "regexp_substr", "hash_md5")

    override fun defaultNamespace(names: List<String>, database: String): String? = database.trim().uppercase().takeIf { it in names } ?: names.firstOrNull()

    override fun isSystem(name: String) = super.isSystem(name) || name.startsWith("EXA_")
}

/**
 * IBM Informix: `SELECT SKIP n FIRST m` paging, double quotes as identifiers (the driver's DELIMIDENT is on), and dates
 * rebuilt with MDY and EXTEND. No regular expressions without a DataBlade.
 */
open class InformixDialect : SqlDialect.GenericDialect() {
    override fun toString() = "Informix"

    override val plainIdentifier = Regex("[a-z_][a-z0-9_$]*")

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        "SELECT SKIP $offset FIRST $limit " + selectAll(table, filter, orderBy, projection).removePrefix("SELECT ")

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
        val col = quoteIdentifier(column)
        return "SELECT FIRST $limit DISTINCT v FROM (SELECT FIRST $scan $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
            "${castToText(col)} LIKE ? ESCAPE '!') s ORDER BY v"
    }

    override fun castToText(expression: String) = "CAST($expression AS LVARCHAR)"

    /** WEEKDAY counts from Sunday = 0. */
    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        when (bucket) {
            TimeBucket.Hour -> "EXTEND(EXTEND($expression, YEAR TO HOUR), YEAR TO SECOND)"
            TimeBucket.Day -> "DATE($expression)"
            TimeBucket.Week -> "DATE($expression) - MOD(WEEKDAY($expression) + 6, 7)"
            TimeBucket.Month -> "MDY(MONTH($expression), 1, YEAR($expression))"
            TimeBucket.Quarter -> "MDY(MONTH($expression) - MOD(MONTH($expression) - 1, 3), 1, YEAR($expression))"
            TimeBucket.Year -> "MDY(1, 1, YEAR($expression))"
        }

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Length -> "CHAR_LENGTH($expression)"
            SelectFunction.Substring -> "SUBSTR($expression, ${arguments.joinToString()})"
            SelectFunction.Ceil -> "CEIL($expression)"
            else -> super.selectFunction(function, expression, arguments)
        }

    /** SET EXPLAIN writes the plan to a file on the server. */
    override fun explain(sql: String): List<String> = noExplain("Informix")

    override val functions = listOf("current", "today", "extend", "mdy", "to_char", "to_date", "nvl", "decode", "trunc", "dbinfo")

    /** Schemas are owners; the connected user's comes first, as that's where unqualified names resolve. */
    override fun defaultNamespace(names: List<String>, database: String): String? = names.firstOrNull()

    override fun listNamespaces(meta: DatabaseMetaData): List<Namespace> {
        val current = meta.connection.scalar("SELECT FIRST 1 USER FROM systables")?.trim()
        val owners = runCatching { meta.schemas.strings("TABLE_SCHEM") }.getOrDefault(emptyList()).map { it.trim() }.filterNot(::isSystem)
        return (listOfNotNull(current) + owners.sorted()).distinct().map { Namespace(catalog = null, schema = it) }
    }
}

/**
 * InterSystems IRIS: schemas hold the tables (SQLUser by default; `%` ones are the system's), TOP and LIMIT/OFFSET
 * paging, and T-SQL's date functions. Its SQL has no regular-expression predicate, so filters don't offer one.
 */
open class IrisDialect : SqlDialect.GenericDialect() {
    override fun toString() = "InterSystems IRIS"

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String {
        val col = quoteIdentifier(column)
        // DISTINCT compares, and returns, values in their collation (upper case) unless %EXACT says otherwise.
        return "SELECT DISTINCT TOP $limit %EXACT(v) AS v FROM (SELECT TOP $scan $col AS v FROM ${qualifiedName(table)} WHERE $col IS NOT NULL AND " +
            "${castToText(col)} LIKE ? ESCAPE '!') s ORDER BY v"
    }

    /** DATEPART's weekday counts from Sunday = 1. */
    override fun timeBucket(expression: String, bucket: TimeBucket): String {
        val day = "CAST($expression AS DATE)"
        return when (bucket) {
            TimeBucket.Hour -> "DATEADD('hh', DATEPART('hh', $expression), CAST($day AS TIMESTAMP))"
            TimeBucket.Day -> day
            TimeBucket.Week -> "DATEADD('dd', -MOD(DATEPART('dw', $expression) + 5, 7), $day)"
            TimeBucket.Month -> "DATEADD('dd', 1 - DATEPART('dd', $expression), $day)"
            TimeBucket.Quarter -> "DATEADD('mm', -MOD(DATEPART('mm', $expression) - 1, 3), DATEADD('dd', 1 - DATEPART('dd', $expression), $day))"
            TimeBucket.Year -> "DATEADD('dd', 1 - DATEPART('dy', $expression), $day)"
        }
    }

    override fun explain(sql: String): List<String> = noExplain("InterSystems IRIS")

    override val functions = listOf("current_timestamp", "getdate", "dateadd", "datediff", "datepart", "to_char", "list", "ifnull", "nvl", "\$extract")

    override fun defaultNamespace(names: List<String>, database: String): String? = "SQLUser".takeIf { it in names } ?: names.firstOrNull()

    override fun isSystem(name: String) =
        super.isSystem(name) || name.startsWith("%") || name.startsWith("Ens") || name.startsWith("HS_") || name.startsWith("INFORMATION_SCHEMA") || name == "IRIS_Shard"
}

/**
 * Microsoft Access through UCanAccess, which runs Access SQL on HSQLDB: `[bracketed]` names, LIMIT/OFFSET, and Access's
 * own date functions.
 */
open class AccessDialect : SqlDialect.GenericDialect(SqlSyntax.Access) {
    override fun toString() = "Access"

    override fun quoteIdentifier(name: String) = "[${name.replace("]", "")}]"

    override fun selectPage(table: TableRef, filter: String?, orderBy: List<SortColumn>, limit: Int, offset: Long, projection: SqlProjection?): String =
        limitOffset(table, filter, orderBy, limit, offset, projection)

    override fun sampleValues(table: TableRef, column: String, limit: Int, scan: Int): String = sampleValuesWithLimit(table, column, limit, scan)

    override fun castToText(expression: String) = "CSTR($expression)"

    /** Weekday(…, 2) counts from Monday = 1. */
    override fun timeBucket(expression: String, bucket: TimeBucket): String =
        when (bucket) {
            TimeBucket.Hour -> "DateAdd('h', Hour($expression), DateSerial(Year($expression), Month($expression), Day($expression)))"
            TimeBucket.Day -> "DateSerial(Year($expression), Month($expression), Day($expression))"
            TimeBucket.Week -> "DateSerial(Year($expression), Month($expression), Day($expression) + 1 - Weekday($expression, 2))"
            TimeBucket.Month -> "DateSerial(Year($expression), Month($expression), 1)"
            TimeBucket.Quarter -> "DateSerial(Year($expression), 3 * Int((Month($expression) - 1) / 3) + 1, 1)"
            TimeBucket.Year -> "DateSerial(Year($expression), 1, 1)"
        }

    override fun selectFunction(function: SelectFunction, expression: String, arguments: List<String>): String? =
        when (function) {
            SelectFunction.Length -> "LEN($expression)"
            SelectFunction.Substring -> "MID($expression, ${arguments.joinToString()})"
            SelectFunction.Ceil -> "CEILING($expression)"
            else -> super.selectFunction(function, expression, arguments)
        }

    /** Yes/No columns take True and False. */
    override fun literal(param: SqlParam): String = (param.value as? Boolean)?.let { if (it) "TRUE" else "FALSE" } ?: super.literal(param)

    override fun explain(sql: String): List<String> = noExplain("Access")

    override val functions = listOf("Now", "Date", "DateAdd", "DateDiff", "DateSerial", "Format", "IIf", "Nz", "Len", "Mid")

    override fun defaultNamespace(names: List<String>, database: String): String? = "PUBLIC".takeIf { it in names } ?: names.firstOrNull()
}

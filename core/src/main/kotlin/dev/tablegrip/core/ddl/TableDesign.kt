package dev.tablegrip.core.ddl

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.ConstraintInfo
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.ForeignKeyInfo
import dev.tablegrip.core.metadata.IndexInfo
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.metadata.TriggerInfo

/**
 * A column as designed. [type] is the declared type as it would appear in `CREATE TABLE` (`varchar(255)`,
 * `numeric(10,2)`), [defaultValue] a SQL expression (`'draft'`, `now()`), and [originalName] ties the column to the one
 * it was read as, so a changed name is a rename rather than a drop and an add. Null for a new column.
 */
data class DesignColumn(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    val autoIncrement: Boolean = false,
    val originalName: String? = null,
)

/**
 * A table as designed: what the table designer edits and schema compare migrates to. Column names in [primaryKey],
 * [indexes] and [foreignKeys] are the designed (current) names.
 *
 * [uniqueConstraints], [checks] and [triggers] aren't edited here but are kept, so an engine that has to rebuild the table
 * to change it (SQLite) brings them back.
 */
data class TableDesign(
    val table: TableRef,
    val columns: List<DesignColumn>,
    val primaryKey: List<String> = emptyList(),
    val indexes: List<IndexInfo> = emptyList(),
    val foreignKeys: List<ForeignKeyInfo> = emptyList(),
    val primaryKeyName: String? = null,
    val uniqueConstraints: List<IndexInfo> = emptyList(),
    /** Full `CHECK (…)` clauses. */
    val checks: List<String> = emptyList(),
    /** Full `CREATE TRIGGER` statements. */
    val triggers: List<String> = emptyList(),
) {
    fun column(name: String): DesignColumn? = columns.firstOrNull { it.name == name }

    companion object {
        /**
         * The design of an existing table. With [constraints] (from the structure view), indexes that back a UNIQUE
         * constraint are kept apart from plain indexes, since engines drop them differently.
         */
        fun from(
            details: TableDetails,
            dialect: SqlDialect,
            constraints: List<ConstraintInfo> = emptyList(),
            triggers: List<TriggerInfo> = emptyList(),
        ): TableDesign {
            val primaryKey = details.primaryKey
            val uniqueConstraintNames = constraints.filter { it.kind == ConstraintKind.Unique }.map { it.name }.toSet()
            val (uniqueConstraints, indexes) =
                details.indexes
                    .filterNot { isPrimaryKeyIndex(it, primaryKey, details.primaryKeyName) }
                    .partition { it.name in uniqueConstraintNames || it.name.startsWith(SqliteAutoIndex) }
            return TableDesign(
                table = details.table,
                columns = details.columns.map { column ->
                    val (type, identity) = ColumnTypes.declaredType(column, dialect)
                    DesignColumn(
                        name = column.name,
                        type = type,
                        // SQLite reports INTEGER PRIMARY KEY as nullable; a key column never is.
                        nullable = column.nullable && column.name !in primaryKey,
                        defaultValue = ColumnTypes.defaultExpression(column, dialect),
                        autoIncrement = column.autoIncrement || identity,
                        originalName = column.name,
                    )
                },
                primaryKey = primaryKey,
                indexes = indexes,
                foreignKeys = details.foreignKeys,
                primaryKeyName = details.primaryKeyName,
                uniqueConstraints = uniqueConstraints,
                checks = constraints.filter { it.kind == ConstraintKind.Check }.map { it.definition },
                triggers = triggers.map { it.definition },
            )
        }

        /** The index the engine made for the primary key: dropping or recreating it is the primary key's business. */
        private fun isPrimaryKeyIndex(index: IndexInfo, primaryKey: List<String>, primaryKeyName: String?): Boolean {
            if (primaryKeyName != null && index.name == primaryKeyName) return true
            if (!index.unique || primaryKey.isEmpty() || index.columns != primaryKey) return false
            val name = index.name
            return name == "PRIMARY" || name.endsWith("_pkey") || name.startsWith("sqlite_autoindex_") || name.startsWith("PK__") || name.startsWith("PRIMARY_KEY") ||
                name.startsWith("SYS_IDX_") || name.startsWith("SQL") && name.length > 12
        }

        private const val SqliteAutoIndex = "sqlite_autoindex_"
    }
}

/**
 * How a foreign key is matched between two versions of a table: by name, or for unnamed ones (SQLite's) by what it
 * links, so two unnamed keys don't collapse into one.
 */
val ForeignKeyInfo.identity: String
    get() = name.ifBlank { "${columns.joinToString(",")}->${referencedTable.name}(${referencedColumns.joinToString(",")})" }

/** One table's change: [original] null creates the table, [target] null drops it, both alters [original] into [target]. */
data class TableChange(val original: TableDesign?, val target: TableDesign?) {
    init {
        require(original != null || target != null) { "A change needs a table before or after" }
    }

    val table: TableRef
        get() = (target ?: original)!!.table
}

/** Declared types and default expressions as `CREATE TABLE` would spell them, from what JDBC metadata reports. */
object ColumnTypes {
    /** Types whose length is part of the declared type; for others (int, date…) the driver's size is noise. */
    private val LengthTypes =
        setOf(
            "varchar", "char", "character", "character varying", "nvarchar", "nchar", "varbinary", "binary", "bit", "varbit",
            "bit varying", "bpchar", "varchar2", "nvarchar2", "raw",
        )
    private val ExactNumericTypes = setOf("numeric", "decimal", "number", "dec")

    /** Above this, a length is the driver's way of saying "unbounded" (PostgreSQL's 2147483647, SQL Server's max). */
    private const val UnboundedLength = 10_485_760

    /** The type as declared, and whether it carried an identity marker (`int identity` on SQL Server). */
    fun declaredType(column: ColumnInfo, dialect: SqlDialect): Pair<String, Boolean> {
        var name = column.typeName.trim()
        val identity = name.endsWith(" identity", ignoreCase = true)
        if (identity) name = name.dropLast(" identity".length).trim()
        val base = name.lowercase()
        val size = column.size
        val declared =
            when {
                // Already spelled with its arguments, e.g. ClickHouse `Decimal(10, 2)` or `Nullable(String)`.
                '(' in name -> name
                size == null || size <= 0 -> name
                base in LengthTypes ->
                    when {
                        size <= UnboundedLength -> "$name($size)"
                        // SQL Server reports `(max)` as a huge length; PostgreSQL's unbounded varchar has no length at all.
                        dialect is SqlDialect.SqlServerDialect -> "$name(max)"
                        else -> name
                    }
                base in ExactNumericTypes && size in 1..1000 -> if ((column.scale ?: 0) > 0) "$name($size,${column.scale})" else "$name($size)"
                else -> name
            }
        return declared to identity
    }

    /**
     * The column default as a SQL expression. MySQL reports text defaults bare (`draft` for `DEFAULT 'draft'`), so
     * those get their quotes back; everyone else already reports an expression.
     */
    fun defaultExpression(column: ColumnInfo, dialect: SqlDialect): String? {
        val raw = column.defaultValue ?: return null
        if (dialect !is SqlDialect.MySQLDialect) return raw.trim()
        val value = raw.trim()
        val bareKeyword = value.uppercase() in SqlDefaultKeywords || value.uppercase().startsWith("CURRENT_TIMESTAMP")
        return when {
            value.equals("NULL", ignoreCase = true) -> null
            value.startsWith("'") || value.startsWith("(") || bareKeyword || '(' in value && value.endsWith(")") -> value
            value.toBigDecimalOrNull() != null && column.jdbcType !in TextTypes -> value
            value.startsWith("b'") -> value
            else -> dialect.literal(raw, numeric = false)
        }
    }

    /**
     * Comparable form of a declared type: lower case, no spaces after commas, and each engine's aliases folded together,
     * so `int4` and `integer` or `character varying(20)` and `varchar(20)` aren't reported as differences.
     */
    fun normalize(type: String): String {
        val lower = type.trim().lowercase().replace(Regex("\\s*([(),])\\s*"), "$1").replace(Regex("\\s+"), " ")
        val base = lower.substringBefore('(').trim()
        val arguments = lower.substring(base.length).trim()
        return (TypeAliases[base] ?: base) + arguments
    }

    /** Comparable form of a default expression: no PostgreSQL casts or wrapping parentheses, case folded outside quotes. */
    fun normalizeDefault(expression: String?): String? {
        var value = expression?.trim() ?: return null
        if (value.equals("NULL", ignoreCase = true)) return null
        while (value.startsWith("(") && value.endsWith(")") && balanced(value.substring(1, value.length - 1))) value = value.substring(1, value.length - 1).trim()
        value = value.replace(Regex("::[a-zA-Z_ ]+(\\[\\])?(\\(\\d+(,\\d+)?\\))?"), "")
        return if (value.startsWith("'")) value else value.lowercase()
    }

    private fun balanced(text: String): Boolean {
        var depth = 0
        for (c in text) {
            if (c == '(') depth++ else if (c == ')') if (--depth < 0) return false
        }
        return depth == 0
    }

    /** Types each engine offers in the designer's type field, most used first. */
    fun commonTypes(dialect: SqlDialect): List<String> =
        when (dialect) {
            is SqlDialect.PostgreSQLDialect ->
                listOf(
                    "integer", "bigint", "smallint", "serial", "bigserial", "numeric(10,2)", "real", "double precision", "boolean", "text",
                    "varchar(255)", "char(1)", "uuid", "date", "timestamp", "timestamptz", "time", "interval", "jsonb", "json", "bytea", "inet",
                    "text[]", "integer[]",
                )
            is SqlDialect.MySQLDialect ->
                listOf(
                    "INT", "BIGINT", "SMALLINT", "TINYINT", "DECIMAL(10,2)", "DOUBLE", "FLOAT", "BOOLEAN", "VARCHAR(255)", "CHAR(36)", "TEXT",
                    "MEDIUMTEXT", "LONGTEXT", "DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR", "JSON", "BLOB", "ENUM('a','b')",
                )
            is SqlDialect.SQLiteDialect -> listOf("INTEGER", "TEXT", "REAL", "NUMERIC", "BLOB", "BOOLEAN", "DATE", "DATETIME", "VARCHAR(255)")
            is SqlDialect.SqlServerDialect ->
                listOf(
                    "int", "bigint", "smallint", "tinyint", "bit", "decimal(10,2)", "float", "nvarchar(255)", "nvarchar(max)", "varchar(255)",
                    "char(1)", "uniqueidentifier", "date", "datetime2", "datetimeoffset", "time", "varbinary(max)",
                )
            is SqlDialect.ClickHouseDialect ->
                listOf("Int32", "Int64", "UInt32", "UInt64", "Float64", "Decimal(18, 2)", "String", "Bool", "UUID", "Date", "DateTime", "DateTime64(3)", "LowCardinality(String)")
            is SqlDialect.DuckDBDialect ->
                listOf("INTEGER", "BIGINT", "SMALLINT", "DECIMAL(10,2)", "DOUBLE", "BOOLEAN", "VARCHAR", "UUID", "DATE", "TIMESTAMP", "TIMESTAMPTZ", "TIME", "JSON", "BLOB")
            else -> listOf("INTEGER", "BIGINT", "SMALLINT", "DECIMAL(10,2)", "DOUBLE", "BOOLEAN", "VARCHAR(255)", "CHAR(1)", "CLOB", "DATE", "TIMESTAMP", "TIME", "BLOB")
        }

    private val SqlDefaultKeywords = setOf("CURRENT_DATE", "CURRENT_TIME", "LOCALTIME", "LOCALTIMESTAMP", "NOW()", "TRUE", "FALSE", "UUID()")
    private val TextTypes =
        setOf(java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR, java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR, java.sql.Types.CLOB)

    private val TypeAliases =
        mapOf(
            "int" to "integer", "int4" to "integer", "int8" to "bigint", "int2" to "smallint", "serial4" to "serial", "serial8" to "bigserial",
            "float8" to "double precision", "float4" to "real", "bool" to "boolean", "character varying" to "varchar", "character" to "char",
            "bpchar" to "char", "decimal" to "numeric", "dec" to "numeric", "timestamp without time zone" to "timestamp",
            "timestamp with time zone" to "timestamptz", "time without time zone" to "time", "time with time zone" to "timetz",
            "double" to "double precision",
        )
}

package dev.datagrok.core.dialect

import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.sql.SqlSyntax
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.Types

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

package dev.datagrok.core.assist

import dev.datagrok.core.driver.RelationalConnection
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableInfo

/**
 * A schema as an agent reads it: tables and columns in a compact `table(column type, …)` form, within a character
 * budget. Only names and types, never data, so it may go wherever [dev.datagrok.core.connection.AiAccess.sharesSchema].
 */
object SchemaDigest {
    /**
     * The default namespace of [database]. [focus] tables (e.g. those a statement uses) are listed first, so they keep
     * their columns when the budget runs out.
     */
    suspend fun read(database: RelationalConnection, configuredDatabase: String, budget: Int, focus: Collection<String> = emptyList()): String {
        val namespaces = database.namespaces()
        val names = namespaces.map { it.displayName }
        val defaultName = database.dialect.defaultNamespace(names, configuredDatabase)
        val namespace = namespaces.firstOrNull { it.displayName == defaultName } ?: return "The database has no schemas."
        return describe(namespace.displayName, names.filter { it != namespace.displayName }, database.tables(namespace), database.columnsOf(namespace), budget, focus)
    }

    /** One table in full: every column with its type and nullability, then its keys and indexes. */
    fun table(details: TableDetails): String = buildString {
        append("Columns:\n")
        for (column in details.columns) {
            append("  ${column.name} ${column.typeName.lowercase()}")
            if (column.primaryKey) append(" pk")
            if (!column.nullable) append(" not null")
            if (column.autoIncrement) append(" auto increment")
            column.defaultValue?.takeIf { it.isNotBlank() }?.let { append(" default ").append(it.take(60)) }
            append('\n')
        }
        if (details.primaryKey.isNotEmpty()) append("Primary key: ${details.primaryKey.joinToString()}\n")
        for (key in details.foreignKeys) {
            append("Foreign key: (${key.columns.joinToString()}) -> ${key.referencedTable.name}(${key.referencedColumns.joinToString()})\n")
        }
        for (index in details.indexes) {
            append("${if (index.unique) "Unique index" else "Index"} ${index.name}: (${index.columns.joinToString()})\n")
        }
    }.trimEnd()

    fun describe(
        namespace: String,
        others: List<String>,
        tables: List<TableInfo>,
        columns: Map<String, List<ColumnInfo>>,
        budget: Int,
        focus: Collection<String> = emptyList(),
    ): String = buildString {
        append("Default schema: $namespace")
        if (others.isNotEmpty()) append(" (others, not described: ${others.take(40).joinToString()})")
        append(". These are all of its ${tables.size} tables and views, with every column:\n")
        val wanted = focus.map { it.lowercase() }.toSet()
        val ordered = tables.sortedBy { if (it.ref.name.lowercase() in wanted) 0 else 1 }
        val skipped = mutableListOf<String>()
        for (table in ordered) {
            val name = table.ref.name
            val line =
                buildString {
                    append(name)
                    if (table.isView) append(" [view]")
                    append('(')
                    append(columns[name].orEmpty().joinToString { "${it.name} ${it.typeName.lowercase()}${if (it.primaryKey) " pk" else ""}" })
                    append(')')
                    table.remarks?.takeIf { it.isNotBlank() }?.let { append(" -- ").append(it.take(120)) }
                }
            if (length + line.length > budget) skipped += name else append(line).append('\n')
        }
        if (skipped.isNotEmpty()) append("Also these tables, columns not listed (request a lookup query when you need them): ${skipped.joinToString()}\n")
    }.trimEnd()
}

package dev.datagrok.core.metadata

import dev.datagrok.core.dialect.SqlDialect
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/** Reads schema information through JDBC metadata. Callers own threading and the connection. */
internal class Introspector(private val dialect: SqlDialect) {

    fun namespaces(meta: DatabaseMetaData): List<Namespace> = dialect.listNamespaces(meta)

    fun tables(meta: DatabaseMetaData, namespace: Namespace): List<TableInfo> =
        meta.getTables(namespace.catalog, namespace.schema?.let { meta.escape(it) }, "%", dialect.tableTypes.keys.toTypedArray())
            .collect {
                TableInfo(
                    ref = TableRef(namespace, getString("TABLE_NAME")),
                    isView = dialect.tableTypes[getString("TABLE_TYPE")] == true,
                    remarks = getString("REMARKS")?.takeIf { it.isNotBlank() },
                )
            }
            .sortedBy { it.ref.name.lowercase() }

    fun details(meta: DatabaseMetaData, table: TableRef): TableDetails {
        val catalog = table.namespace.catalog
        val schema = table.namespace.schema
        val primaryKey = meta.getPrimaryKeys(catalog, schema, table.name).collect { getString("COLUMN_NAME") }.toSet()

        val columns =
            meta.getColumns(catalog, schema?.let { meta.escape(it) }, meta.escape(table.name), "%")
                .collect {
                    val name = getString("COLUMN_NAME")
                    getInt("ORDINAL_POSITION") to
                        ColumnInfo(
                            name = name,
                            typeName = getString("TYPE_NAME"),
                            size = getInt("COLUMN_SIZE").takeUnless { wasNull() },
                            nullable = getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            defaultValue = getString("COLUMN_DEF"),
                            autoIncrement = runCatching { getString("IS_AUTOINCREMENT") == "YES" }.getOrDefault(false),
                            primaryKey = name in primaryKey,
                        )
                }
                .sortedBy { it.first }
                .map { it.second }

        val indexes =
            meta.getIndexInfo(catalog, schema, table.name, false, true)
                .collect {
                    val name = getString("INDEX_NAME") ?: return@collect null
                    Triple(name, getShort("ORDINAL_POSITION") to getString("COLUMN_NAME"), !getBoolean("NON_UNIQUE"))
                }
                .filterNotNull()
                .groupBy { it.first }
                .map { (name, rows) ->
                    IndexInfo(name, rows.sortedBy { it.second.first }.mapNotNull { it.second.second }, rows.first().third)
                }

        val foreignKeys =
            meta.getImportedKeys(catalog, schema, table.name)
                .collect {
                    val target =
                        TableRef(Namespace(getString("PKTABLE_CAT").takeIf { catalog != null }, getString("PKTABLE_SCHEM")), getString("PKTABLE_NAME"))
                    ForeignKeyRow(
                        name = getString("FK_NAME") ?: "fk_${table.name}_${getString("PKTABLE_NAME")}",
                        sequence = getShort("KEY_SEQ"),
                        column = getString("FKCOLUMN_NAME"),
                        target = target,
                        targetColumn = getString("PKCOLUMN_NAME"),
                    )
                }
                .groupBy { it.name }
                .map { (name, rows) ->
                    val sorted = rows.sortedBy { it.sequence }
                    ForeignKeyInfo(name, sorted.map { it.column }, sorted.first().target, sorted.map { it.targetColumn })
                }

        return TableDetails(table, columns, indexes, foreignKeys)
    }

    /** Every column of every table in [namespace], in one metadata call. */
    fun columnsOf(meta: DatabaseMetaData, namespace: Namespace): Map<String, List<ColumnInfo>> =
        meta.getColumns(namespace.catalog, namespace.schema?.let { meta.escape(it) }, "%", "%")
            .collect {
                Triple(
                    getString("TABLE_NAME"),
                    getInt("ORDINAL_POSITION"),
                    ColumnInfo(
                        name = getString("COLUMN_NAME"),
                        typeName = getString("TYPE_NAME"),
                        size = getInt("COLUMN_SIZE").takeUnless { wasNull() },
                        nullable = getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        defaultValue = getString("COLUMN_DEF"),
                        autoIncrement = runCatching { getString("IS_AUTOINCREMENT") == "YES" }.getOrDefault(false),
                        primaryKey = false,
                    ),
                )
            }
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, columns) -> columns.sortedBy { it.first }.map { it.second } }

    /** Foreign keys from [table] to others and from others to [table]. */
    fun relations(meta: DatabaseMetaData, table: TableRef): List<TableRelation> {
        val catalog = table.namespace.catalog
        val schema = table.namespace.schema
        fun ResultSet.relations(): List<TableRelation> =
            collect {
                RelationRow(
                    name = getString("FK_NAME") ?: "${getString("FKTABLE_NAME")}->${getString("PKTABLE_NAME")}",
                    sequence = getShort("KEY_SEQ"),
                    from = TableRef(Namespace(getString("FKTABLE_CAT").takeIf { catalog != null }, getString("FKTABLE_SCHEM")), getString("FKTABLE_NAME")),
                    fromColumn = getString("FKCOLUMN_NAME"),
                    to = TableRef(Namespace(getString("PKTABLE_CAT").takeIf { catalog != null }, getString("PKTABLE_SCHEM")), getString("PKTABLE_NAME")),
                    toColumn = getString("PKCOLUMN_NAME"),
                )
            }
                .groupBy { it.from to it.name }
                .map { (_, rows) ->
                    val sorted = rows.sortedBy { it.sequence }
                    TableRelation(sorted.first().from, sorted.map { it.fromColumn }, sorted.first().to, sorted.map { it.toColumn })
                }
        return (meta.getImportedKeys(catalog, schema, table.name).relations() + meta.getExportedKeys(catalog, schema, table.name).relations()).distinct()
    }

    private data class RelationRow(val name: String, val sequence: Short, val from: TableRef, val fromColumn: String, val to: TableRef, val toColumn: String)

    private data class ForeignKeyRow(val name: String, val sequence: Short, val column: String, val target: TableRef, val targetColumn: String)

    /** Metadata name arguments are LIKE patterns: escape `_` and `%` so `user_x` doesn't match `userAx`. */
    private fun DatabaseMetaData.escape(name: String): String {
        val escape = searchStringEscape.takeUnless { it.isNullOrEmpty() } ?: return name
        return name.replace(escape, escape + escape).replace("_", "${escape}_").replace("%", "$escape%")
    }

    private fun <T> ResultSet.collect(map: ResultSet.() -> T): List<T> = use { buildList { while (next()) add(map()) } }
}

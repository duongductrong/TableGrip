package dev.datagrok.core.metadata

/** A schema (PostgreSQL), a database (MySQL), or the single `main` database (SQLite). */
data class Namespace(val catalog: String?, val schema: String?) {
    val displayName: String
        get() = schema ?: catalog ?: "main"
}

data class TableRef(val namespace: Namespace, val name: String)

data class TableInfo(val ref: TableRef, val isView: Boolean, val remarks: String?)

data class ColumnInfo(
    val name: String,
    val typeName: String,
    val size: Int?,
    val nullable: Boolean,
    val defaultValue: String?,
    val autoIncrement: Boolean,
    val primaryKey: Boolean,
)

data class IndexInfo(val name: String, val columns: List<String>, val unique: Boolean)

data class ForeignKeyInfo(val name: String, val columns: List<String>, val referencedTable: TableRef, val referencedColumns: List<String>)

data class TableDetails(
    val table: TableRef,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    val foreignKeys: List<ForeignKeyInfo>,
) {
    val primaryKey: List<String>
        get() = columns.filter { it.primaryKey }.map { it.name }
}

/** A foreign key: [fromTable].[fromColumns] references [toTable].[toColumns]. */
data class TableRelation(val fromTable: TableRef, val fromColumns: List<String>, val toTable: TableRef, val toColumns: List<String>)

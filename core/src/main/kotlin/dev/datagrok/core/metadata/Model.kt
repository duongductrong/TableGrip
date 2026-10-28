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

/** [onUpdate] and [onDelete] are referential actions such as `CASCADE`, or null for the default (no action). */
data class ForeignKeyInfo(
    val name: String,
    val columns: List<String>,
    val referencedTable: TableRef,
    val referencedColumns: List<String>,
    val onUpdate: String? = null,
    val onDelete: String? = null,
)

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

enum class ConstraintKind(val label: String) {
    PrimaryKey("PRIMARY KEY"),
    Unique("UNIQUE"),
    ForeignKey("FOREIGN KEY"),
    Check("CHECK"),
    Exclusion("EXCLUDE"),
}

/** A table constraint; [definition] is its SQL as it would follow `CONSTRAINT name`. [name] is empty if unnamed. */
data class ConstraintInfo(val name: String, val kind: ConstraintKind, val columns: List<String>, val definition: String)

/** [timing] is e.g. `BEFORE`, [events] e.g. `INSERT OR UPDATE`; [definition] is the full `CREATE TRIGGER` statement. */
data class TriggerInfo(val name: String, val timing: String, val events: String, val enabled: Boolean, val definition: String)

/** Everything the structure view shows about a table or view. */
data class TableStructure(
    val details: TableDetails,
    val constraints: List<ConstraintInfo>,
    val triggers: List<TriggerInfo>,
    val ddl: String,
)

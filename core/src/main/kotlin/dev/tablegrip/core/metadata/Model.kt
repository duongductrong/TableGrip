package dev.tablegrip.core.metadata

import java.sql.Types

/** A schema (PostgreSQL), a database (MySQL), or the single `main` database (SQLite). */
data class Namespace(val catalog: String?, val schema: String?) {
    val displayName: String
        // Spanner's default schema has the empty name.
        get() = schema?.ifEmpty { null } ?: catalog ?: "main"
}

data class TableRef(val namespace: Namespace, val name: String)

/** [materialized] views are views ([isView]) whose rows are stored, refreshed on demand (PostgreSQL, Oracle). */
data class TableInfo(val ref: TableRef, val isView: Boolean, val remarks: String?, val materialized: Boolean = false)

data class ColumnInfo(
    val name: String,
    val typeName: String,
    val size: Int?,
    val nullable: Boolean,
    val defaultValue: String?,
    val autoIncrement: Boolean,
    val primaryKey: Boolean,
    /** A [java.sql.Types] constant, as the driver reports it. */
    val jdbcType: Int = Types.OTHER,
    /** Digits after the decimal point for exact numerics (`numeric(10,2)` has 2), else null. */
    val scale: Int? = null,
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
    /** The primary key constraint's name where the engine names it (`users_pkey`, `PRIMARY`), else null. */
    val primaryKeyName: String? = null,
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

/** Schema objects besides tables and views that the explorer lists and opens. */
enum class SchemaObjectKind { Function, Procedure, Sequence }

/**
 * A function, procedure or sequence. [id] tells overloads apart where an engine allows them (PostgreSQL's
 * `name(integer, text)` identity arguments); otherwise it is the name.
 */
data class SchemaObjectRef(val namespace: Namespace, val name: String, val kind: SchemaObjectKind, val id: String = name)

/** [detail] is what the explorer shows beside the name, e.g. a function's arguments and result type. */
data class SchemaObjectInfo(val ref: SchemaObjectRef, val detail: String? = null)

/**
 * What opening a schema object shows: [properties] as label and value (a sequence's current value, increment…), and
 * its [source], the DDL as the server keeps it. [replaceSql] recreates the object over the existing one where the
 * engine can (`CREATE OR REPLACE`, `CREATE OR ALTER`); otherwise it is the plain source.
 */
data class SchemaObjectDefinition(
    val ref: SchemaObjectRef,
    val properties: List<Pair<String, String?>>,
    val source: String,
    val replaceSql: String = source,
)

package dev.tablegrip.core.schema

import dev.tablegrip.core.ddl.ColumnTypes
import dev.tablegrip.core.ddl.DdlGenerator
import dev.tablegrip.core.ddl.DdlScript
import dev.tablegrip.core.ddl.DesignColumn
import dev.tablegrip.core.ddl.TableChange
import dev.tablegrip.core.ddl.TableDesign
import dev.tablegrip.core.ddl.identity
import dev.tablegrip.core.metadata.ForeignKeyInfo
import dev.tablegrip.core.metadata.IndexInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails

/** Every table of a namespace with its columns, keys and indexes; views are only named. */
data class SchemaSnapshot(val namespace: Namespace, val tables: List<TableDetails>, val views: List<String> = emptyList())

/** One difference inside a table that exists on both sides. [id] is stable, for remembering what the user selected. */
sealed interface DiffItem {
    val id: String
    val label: String

    data class ColumnAdded(val column: DesignColumn) : DiffItem {
        override val id = "column:${column.name}"
        override val label = "Add column ${column.name} ${column.type}"
    }

    data class ColumnRemoved(val column: DesignColumn) : DiffItem {
        override val id = "column:${column.name}"
        override val label = "Drop column ${column.name}"
    }

    /** [changes] say what differs, e.g. `type varchar(50) → varchar(100)`. */
    data class ColumnChanged(val from: DesignColumn, val to: DesignColumn, val changes: List<String>) : DiffItem {
        override val id = "column:${from.name}"
        override val label = "Change column ${from.name}: ${changes.joinToString(", ")}"
    }

    data class IndexAdded(val index: IndexInfo) : DiffItem {
        override val id = "index:${index.name}"
        override val label = "Create ${if (index.unique) "unique " else ""}index ${index.name} (${index.columns.joinToString(", ")})"
    }

    data class IndexRemoved(val index: IndexInfo) : DiffItem {
        override val id = "index:${index.name}"
        override val label = "Drop index ${index.name}"
    }

    data class IndexChanged(val from: IndexInfo, val to: IndexInfo) : DiffItem {
        override val id = "index:${from.name}"
        override val label = "Recreate index ${to.name} (${to.columns.joinToString(", ")})${if (to.unique) " unique" else ""}"
    }

    data class ForeignKeyAdded(val foreignKey: ForeignKeyInfo) : DiffItem {
        override val id = "fk:${foreignKey.identity}"
        override val label = "Add foreign key ${foreignKey.name.ifBlank { foreignKey.columns.joinToString(", ") }} → ${foreignKey.referencedTable.name}(${foreignKey.referencedColumns.joinToString(", ")})"
    }

    data class ForeignKeyRemoved(val foreignKey: ForeignKeyInfo) : DiffItem {
        override val id = "fk:${foreignKey.identity}"
        override val label = "Drop foreign key ${foreignKey.name.ifBlank { foreignKey.columns.joinToString(", ") + " → " + foreignKey.referencedTable.name }}"
    }

    data class ForeignKeyChanged(val from: ForeignKeyInfo, val to: ForeignKeyInfo) : DiffItem {
        override val id = "fk:${from.identity}"
        override val label = "Recreate foreign key ${to.name.ifBlank { to.columns.joinToString(", ") }} → ${to.referencedTable.name}(${to.referencedColumns.joinToString(", ")})"
    }

    data class PrimaryKeyChanged(val from: List<String>, val to: List<String>) : DiffItem {
        override val id = "pk"
        override val label =
            when {
                to.isEmpty() -> "Drop primary key (${from.joinToString(", ")})"
                from.isEmpty() -> "Add primary key (${to.joinToString(", ")})"
                else -> "Change primary key (${from.joinToString(", ")}) → (${to.joinToString(", ")})"
            }
    }

    /** Drops lose data or structure, so they start unselected. */
    val destructive: Boolean
        get() = this is ColumnRemoved || this is IndexRemoved || this is ForeignKeyRemoved
}

/** In the order differences are listed: new tables first, drops last. */
enum class TableDiffKind { Added, Changed, Removed }

/**
 * A table that differs: only in the source ([TableDiffKind.Added] to the target), only in the target
 * ([TableDiffKind.Removed]), or in both with [items] differing.
 */
data class TableDiff(val name: String, val kind: TableDiffKind, val source: TableDesign?, val target: TableDesign?, val items: List<DiffItem>) {
    val id: String
        get() = "table:$name"

    fun itemKey(item: DiffItem): String = "$name/${item.id}"
}

data class SchemaComparison(val diffs: List<TableDiff>, val identical: Int, val targetNamespace: Namespace) {
    val isEmpty: Boolean
        get() = diffs.isEmpty()

    /** What starts selected: everything except drops. */
    fun defaultSelection(): Set<String> =
        buildSet {
            for (diff in diffs) {
                when (diff.kind) {
                    TableDiffKind.Added -> add(diff.id)
                    TableDiffKind.Removed -> Unit
                    TableDiffKind.Changed -> diff.items.filterNot { it.destructive }.forEach { add(diff.itemKey(it)) }
                }
            }
        }
}

/**
 * Compares two schemas and writes the script that makes the target look like the source. The script is in the target's
 * dialect; types are carried over as the source spells them, which is right between engines of a kind and needs a look
 * between different ones.
 */
object SchemaDiff {
    fun compare(source: List<TableDesign>, target: List<TableDesign>, targetNamespace: Namespace): SchemaComparison {
        val targetByName = byName(target) { it.table.name }
        val sourceByName = byName(source) { it.table.name }
        val diffs = mutableListOf<TableDiff>()
        var identical = 0
        for (table in source) {
            val other = targetByName.find(table.table.name)
            if (other == null) {
                diffs += TableDiff(table.table.name, TableDiffKind.Added, table, null, emptyList())
                continue
            }
            val items = compareTable(table, other)
            if (items.isEmpty()) identical++ else diffs += TableDiff(other.table.name, TableDiffKind.Changed, table, other, items)
        }
        for (table in target) {
            if (sourceByName.find(table.table.name) == null) diffs += TableDiff(table.table.name, TableDiffKind.Removed, null, table, emptyList())
        }
        return SchemaComparison(diffs.sortedWith(compareBy({ it.kind }, { it.name.lowercase() })), identical, targetNamespace)
    }

    private fun compareTable(source: TableDesign, target: TableDesign): List<DiffItem> {
        val items = mutableListOf<DiffItem>()
        val targetColumns = byName(target.columns) { it.name }
        val sourceColumns = byName(source.columns) { it.name }
        for (column in source.columns) {
            val other = targetColumns.find(column.name)
            if (other == null) {
                items += DiffItem.ColumnAdded(column)
                continue
            }
            val changes = columnChanges(column, other)
            if (changes.isNotEmpty()) items += DiffItem.ColumnChanged(other, column.copy(name = other.name, originalName = other.name), changes)
        }
        target.columns.filter { sourceColumns.find(it.name) == null }.forEach { items += DiffItem.ColumnRemoved(it) }

        if (!sameNames(source.primaryKey, target.primaryKey)) items += DiffItem.PrimaryKeyChanged(target.primaryKey, source.primaryKey)

        val targetIndexes = byName(target.indexes) { it.name }
        val sourceIndexes = byName(source.indexes) { it.name }
        for (index in source.indexes) {
            val other = targetIndexes.find(index.name)
            when {
                other == null -> items += DiffItem.IndexAdded(index)
                !sameNames(index.columns, other.columns) || index.unique != other.unique -> items += DiffItem.IndexChanged(other, index.copy(name = other.name))
            }
        }
        target.indexes.filter { sourceIndexes.find(it.name) == null }.forEach { items += DiffItem.IndexRemoved(it) }

        val targetForeignKeys = byName(target.foreignKeys) { it.identity }
        val sourceForeignKeys = byName(source.foreignKeys) { it.identity }
        for (fk in source.foreignKeys) {
            val other = targetForeignKeys.find(fk.identity)
            when {
                other == null -> items += DiffItem.ForeignKeyAdded(fk)
                !sameForeignKey(fk, other) -> items += DiffItem.ForeignKeyChanged(other, fk.copy(name = other.name))
            }
        }
        target.foreignKeys.filter { sourceForeignKeys.find(it.identity) == null }.forEach { items += DiffItem.ForeignKeyRemoved(it) }
        return items
    }

    private fun columnChanges(source: DesignColumn, target: DesignColumn): List<String> =
        buildList {
            if (ColumnTypes.normalize(source.type) != ColumnTypes.normalize(target.type)) add("type ${target.type} → ${source.type}")
            if (source.nullable != target.nullable) add(if (source.nullable) "allow NULL" else "NOT NULL")
            val sourceDefault = ColumnTypes.normalizeDefault(source.defaultValue)
            val targetDefault = ColumnTypes.normalizeDefault(target.defaultValue)
            // Sequence defaults name each database's own sequence; they're the auto-increment, not a real difference.
            val sequences = sourceDefault?.startsWith("nextval(") == true && targetDefault?.startsWith("nextval(") == true
            if (sourceDefault != targetDefault && !sequences) add("default ${target.defaultValue ?: "none"} → ${source.defaultValue ?: "none"}")
        }

    private fun sameForeignKey(a: ForeignKeyInfo, b: ForeignKeyInfo): Boolean =
        sameNames(a.columns, b.columns) && a.referencedTable.name.equals(b.referencedTable.name, ignoreCase = true) &&
            sameNames(a.referencedColumns, b.referencedColumns) &&
            (a.onUpdate ?: "NO ACTION") == (b.onUpdate ?: "NO ACTION") && (a.onDelete ?: "NO ACTION") == (b.onDelete ?: "NO ACTION")

    private fun sameNames(a: List<String>, b: List<String>) = a.size == b.size && a.zip(b).all { (x, y) -> x.equals(y, ignoreCase = true) }

    /**
     * The script for the [selected] differences (keys from [SchemaComparison.defaultSelection], [TableDiff.id] and
     * [TableDiff.itemKey]), written by [generator] for the target.
     */
    fun migration(comparison: SchemaComparison, selected: Set<String>, generator: DdlGenerator): DdlScript {
        val namespace = comparison.targetNamespace
        val changes =
            comparison.diffs.mapNotNull { diff ->
                when (diff.kind) {
                    TableDiffKind.Added -> if (diff.id in selected) TableChange(null, retarget(diff.source!!, namespace)) else null
                    TableDiffKind.Removed -> if (diff.id in selected) TableChange(diff.target!!, null) else null
                    TableDiffKind.Changed -> {
                        val chosen = diff.items.filter { diff.itemKey(it) in selected }
                        if (chosen.isEmpty()) null else TableChange(diff.target!!, apply(diff.target, chosen, diff.source!!, namespace))
                    }
                }
            }
        return generator.script(changes)
    }

    /** [target] with [items] applied; new columns go where the source has them, as far as the target allows. */
    private fun apply(target: TableDesign, items: List<DiffItem>, source: TableDesign, namespace: Namespace): TableDesign {
        var columns = target.columns
        var primaryKey = target.primaryKey
        var indexes = target.indexes
        var foreignKeys = target.foreignKeys
        for (item in items) {
            when (item) {
                is DiffItem.ColumnAdded -> {
                    val position = source.columns.indexOf(item.column).coerceAtMost(columns.size)
                    columns = columns.toMutableList().apply { add(position, item.column.copy(originalName = null)) }
                }
                is DiffItem.ColumnRemoved -> columns = columns.filterNot { it.name == item.column.name }
                is DiffItem.ColumnChanged -> columns = columns.map { if (it.name == item.from.name) item.to else it }
                is DiffItem.PrimaryKeyChanged -> primaryKey = item.to
                is DiffItem.IndexAdded -> indexes = indexes + item.index
                is DiffItem.IndexRemoved -> indexes = indexes.filterNot { it.name == item.index.name }
                is DiffItem.IndexChanged -> indexes = indexes.map { if (it.name == item.from.name) item.to else it }
                is DiffItem.ForeignKeyAdded -> foreignKeys = foreignKeys + retarget(item.foreignKey, source, namespace)
                is DiffItem.ForeignKeyRemoved -> foreignKeys = foreignKeys.filterNot { it.identity == item.foreignKey.identity }
                is DiffItem.ForeignKeyChanged -> foreignKeys = foreignKeys.map { if (it.identity == item.from.identity) retarget(item.to, source, namespace) else it }
            }
        }
        return target.copy(columns = columns, primaryKey = primaryKey, indexes = indexes, foreignKeys = foreignKeys)
    }

    /** A source table as it will be created in the target namespace, its foreign keys pointing there too. */
    private fun retarget(design: TableDesign, namespace: Namespace): TableDesign =
        design.copy(
            table = design.table.copy(namespace = namespace),
            columns = design.columns.map { it.copy(originalName = null) },
            foreignKeys = design.foreignKeys.map { retarget(it, design, namespace) },
            // Engine-specific: the target names its own primary key and doesn't get the source's triggers.
            primaryKeyName = null,
            triggers = emptyList(),
        )

    private fun retarget(fk: ForeignKeyInfo, source: TableDesign, namespace: Namespace): ForeignKeyInfo =
        if (fk.referencedTable.namespace == source.table.namespace || fk.referencedTable.namespace.schema == null) fk.copy(referencedTable = fk.referencedTable.copy(namespace = namespace))
        else fk

    /** Exact names first, then case-insensitively: engines fold unquoted names differently. */
    private class NameLookup<T>(private val exact: Map<String, T>, private val folded: Map<String, T>) {
        fun find(name: String): T? = exact[name] ?: folded[name.lowercase()]
    }

    private fun <T> byName(items: List<T>, name: (T) -> String) = NameLookup(items.associateBy(name), items.associateBy { name(it).lowercase() })
}

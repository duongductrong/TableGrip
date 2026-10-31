package dev.datagrok.app.table

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.datagrok.app.grid.GridEditor
import dev.datagrok.app.grid.GridModel
import dev.datagrok.app.grid.RowStatus
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.edit.BoundValue
import dev.datagrok.core.edit.ColumnEdit
import dev.datagrok.core.edit.EditCheck
import dev.datagrok.core.edit.RowChange
import dev.datagrok.core.metadata.TableDetails

/**
 * One page of table rows plus pending, unsaved edits: changed cells, rows marked for deletion and new rows (appended
 * after the page). Updates and deletes need a primary key to identify rows; without one the page is insert-only.
 * [details] tells editors what each column takes (NOT NULL, defaults, limits); without it every column is nullable.
 */
class TableEditModel(
    private val result: QueryResult.Rows,
    primaryKey: List<String>,
    /** For read-only connections: the grid offers no editing at all. */
    private val readOnly: Boolean = false,
    details: TableDetails? = null,
    dialect: SqlDialect? = null,
) : GridModel, GridEditor {
    override val columns: List<ResultColumn> = result.columns

    private val columnEdits: List<ColumnEdit> =
        columns.map { column -> ColumnEdit.of(column, details?.columns?.firstOrNull { it.name == column.name }, dialect) }

    private val keyColumns: List<Int>? =
        primaryKey.map { name -> columns.indexOfFirst { it.name == name } }.takeIf { it.isNotEmpty() && it.none { i -> i < 0 } }

    /** Whether existing rows can be changed or deleted. */
    val hasKey: Boolean
        get() = keyColumns != null

    private val updates = mutableStateMapOf<Pair<Int, Int>, String?>()
    /** Cells of existing rows set back to their column's default. */
    private val defaults = mutableStateMapOf<Pair<Int, Int>, Unit>()
    private val deleted = mutableStateMapOf<Int, Unit>()
    private val inserted = mutableStateListOf<SnapshotStateMap<Int, String?>>()

    private val baseRows: Int
        get() = result.rows.size

    override val rowCount: Int
        get() = baseRows + inserted.size

    override val editor: GridEditor?
        get() = if (readOnly) null else this

    val changeCount: Int
        get() = (updates.keys + defaults.keys).map { it.first }.filter { it !in deleted }.distinct().size + deleted.size + inserted.size

    val hasChanges: Boolean
        get() = changeCount > 0

    override fun columnEdit(column: Int): ColumnEdit = columnEdits[column]

    override fun value(row: Int, column: Int): String? {
        if (row >= baseRows) return inserted[row - baseRows][column]
        val key = row to column
        return when (key) {
            in defaults -> null
            in updates -> updates[key]
            else -> result.rows[row][column]
        }
    }

    override fun rowStatus(row: Int): RowStatus =
        when {
            row >= baseRows -> RowStatus.Inserted
            row in deleted -> RowStatus.Deleted
            else -> RowStatus.Normal
        }

    override fun isModified(row: Int, column: Int): Boolean =
        if (row >= baseRows) column in inserted[row - baseRows] else (row to column).let { it in updates || it in defaults }

    override fun placeholder(row: Int, column: Int): String? =
        when {
            row >= baseRows && column !in inserted[row - baseRows] -> columnEdits[column].placeholder
            row < baseRows && (row to column) in defaults -> "<default>"
            else -> null
        }

    override fun problem(row: Int, column: Int): String? {
        if (row < baseRows && (row in deleted || (row to column) !in updates)) return null
        val edit = columnEdits[column]
        val newRow = row >= baseRows
        if (newRow && column !in inserted[row - baseRows]) return if (edit.hasDefault) null else "${columns[column].name} is required: it is NOT NULL and has no default"
        val value = value(row, column) ?: return if (edit.nullable) null else "${columns[column].name} can't be NULL"
        return when (val check = edit.check(value)) {
            is EditCheck.Invalid -> check.message
            // Blank text kept as typed in a column that isn't text: the server can't read it.
            EditCheck.Empty -> "Empty isn't a ${edit.typeName.lowercase()}; enter a value or set NULL"
            is EditCheck.Ok -> null
        }
    }

    /** The first edit that can't be saved, as "row N, column: why", for refusing to save. */
    fun firstProblem(): String? {
        val rows = (updates.keys.map { it.first }.filter { it !in deleted } + (baseRows until rowCount)).distinct().sorted()
        for (row in rows) {
            for (column in columns.indices) {
                problem(row, column)?.let { return "Row ${row + 1}, ${columns[column].name}: $it" }
            }
        }
        return null
    }

    override fun canEdit(row: Int, column: Int): Boolean =
        !readOnly && !columns[column].isBinary && (row >= baseRows || (hasKey && row !in deleted))

    override fun canSetNull(row: Int, column: Int): Boolean = canEdit(row, column) && columnEdits[column].nullable

    override fun canSetDefault(row: Int, column: Int): Boolean {
        if (!canEdit(row, column)) return false
        val edit = columnEdits[column]
        return if (row >= baseRows) column in inserted[row - baseRows] && edit.hasDefault
        else edit.supportsDefault && (edit.defaultValue != null || edit.generated) && (row to column) !in defaults
    }

    override fun setValue(row: Int, column: Int, value: String?) {
        if (!canEdit(row, column)) return
        if (row >= baseRows) {
            inserted[row - baseRows][column] = value
            return
        }
        defaults.remove(row to column)
        if (columnEdits[column].sameValue(value, result.rows[row][column])) updates.remove(row to column) else updates[row to column] = value
    }

    override fun setDefault(row: Int, column: Int) {
        if (!canSetDefault(row, column)) return
        if (row >= baseRows) {
            inserted[row - baseRows].remove(column)
            return
        }
        updates.remove(row to column)
        defaults[row to column] = Unit
    }

    override fun addRow(): Int {
        inserted += mutableStateMapOf()
        return rowCount - 1
    }

    override fun deleteRows(rows: Collection<Int>) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        if (hasKey) rows.filter { it < baseRows }.forEach { deleted[it] = Unit }
    }

    override fun revertRows(rows: Collection<Int>) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        rows.filter { it < baseRows }.forEach { row ->
            deleted.remove(row)
            updates.keys.filter { it.first == row }.forEach(updates::remove)
            defaults.keys.filter { it.first == row }.forEach(defaults::remove)
        }
    }

    fun revertAll() {
        updates.clear()
        defaults.clear()
        deleted.clear()
        inserted.clear()
    }

    fun toChanges(): List<RowChange> {
        fun bound(column: Int, value: String?) = columns[column].let { BoundValue(value, it.jdbcType, it.isNumeric) }
        fun key(row: Int) = keyColumns!!.associate { columns[it].name to bound(it, result.rows[row][it]) }

        val deletes = deleted.keys.sorted().map { RowChange.Delete(key(it)) }
        val cells =
            updates.entries.map { it.key to bound(it.key.second, it.value) } +
                defaults.keys.map { it to bound(it.second, null).copy(isDefault = true) }
        val changes =
            cells
                .filter { it.first.first !in deleted }
                .groupBy({ it.first.first }, { it.first.second to it.second })
                .toSortedMap()
                .map { (row, values) -> RowChange.Update(key(row), values.sortedBy { it.first }.associate { (c, v) -> columns[c].name to v }) }
        val inserts = inserted.map { row -> RowChange.Insert(row.entries.sortedBy { it.key }.associate { (c, v) -> columns[c].name to bound(c, v) }) }
        return deletes + changes + inserts
    }
}

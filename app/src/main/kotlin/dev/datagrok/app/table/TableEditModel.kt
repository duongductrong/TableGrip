package dev.datagrok.app.table

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.datagrok.app.grid.GridEditor
import dev.datagrok.app.grid.GridModel
import dev.datagrok.app.grid.RowStatus
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.edit.BoundValue
import dev.datagrok.core.edit.RowChange

/**
 * One page of table rows plus pending, unsaved edits: changed cells, rows marked for deletion and new rows (appended
 * after the page). Updates and deletes need a primary key to identify rows; without one the page is insert-only.
 */
class TableEditModel(
    private val result: QueryResult.Rows,
    primaryKey: List<String>,
    /** For read-only connections: the grid offers no editing at all. */
    private val readOnly: Boolean = false,
) : GridModel, GridEditor {
    override val columns: List<ResultColumn> = result.columns

    private val keyColumns: List<Int>? =
        primaryKey.map { name -> columns.indexOfFirst { it.name == name } }.takeIf { it.isNotEmpty() && it.none { i -> i < 0 } }

    /** Whether existing rows can be changed or deleted. */
    val hasKey: Boolean
        get() = keyColumns != null

    private val updates = mutableStateMapOf<Pair<Int, Int>, String?>()
    private val deleted = mutableStateMapOf<Int, Unit>()
    private val inserted = mutableStateListOf<SnapshotStateMap<Int, String?>>()

    private val baseRows: Int
        get() = result.rows.size

    override val rowCount: Int
        get() = baseRows + inserted.size

    override val editor: GridEditor?
        get() = if (readOnly) null else this

    val changeCount: Int
        get() = updates.keys.map { it.first }.filter { it !in deleted }.distinct().size + deleted.size + inserted.size

    val hasChanges: Boolean
        get() = changeCount > 0

    override fun value(row: Int, column: Int): String? {
        if (row >= baseRows) return inserted[row - baseRows][column]
        val key = row to column
        return if (key in updates) updates[key] else result.rows[row][column]
    }

    override fun rowStatus(row: Int): RowStatus =
        when {
            row >= baseRows -> RowStatus.Inserted
            row in deleted -> RowStatus.Deleted
            else -> RowStatus.Normal
        }

    override fun isModified(row: Int, column: Int): Boolean =
        if (row >= baseRows) column in inserted[row - baseRows] else (row to column) in updates

    override fun placeholder(row: Int, column: Int): String? =
        if (row >= baseRows && column !in inserted[row - baseRows]) "<default>" else null

    override fun canEdit(row: Int, column: Int): Boolean =
        !readOnly && !columns[column].isBinary && (row >= baseRows || (hasKey && row !in deleted))

    override fun setValue(row: Int, column: Int, value: String?) {
        if (!canEdit(row, column)) return
        if (row >= baseRows) {
            inserted[row - baseRows][column] = value
            return
        }
        if (value == result.rows[row][column]) updates.remove(row to column) else updates[row to column] = value
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
        }
    }

    fun revertAll() {
        updates.clear()
        deleted.clear()
        inserted.clear()
    }

    fun toChanges(): List<RowChange> {
        fun bound(column: Int, value: String?) = columns[column].let { BoundValue(value, it.jdbcType, it.isNumeric) }
        fun key(row: Int) = keyColumns!!.associate { columns[it].name to bound(it, result.rows[row][it]) }

        val deletes = deleted.keys.sorted().map { RowChange.Delete(key(it)) }
        val changes =
            updates.entries
                .filter { it.key.first !in deleted }
                .groupBy({ it.key.first }, { it.key.second to it.value })
                .toSortedMap()
                .map { (row, cells) -> RowChange.Update(key(row), cells.associate { (c, v) -> columns[c].name to bound(c, v) }) }
        val inserts = inserted.map { row -> RowChange.Insert(row.entries.sortedBy { it.key }.associate { (c, v) -> columns[c].name to bound(c, v) }) }
        return deletes + changes + inserts
    }
}

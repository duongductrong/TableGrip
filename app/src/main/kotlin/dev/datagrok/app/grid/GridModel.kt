package dev.datagrok.app.grid

import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn

enum class RowStatus { Normal, Inserted, Deleted }

/** What a [DataGrid] displays. Column indexes are model indexes, independent of display order. */
interface GridModel {
    val columns: List<ResultColumn>
    val rowCount: Int

    fun value(row: Int, column: Int): String?

    fun rowStatus(row: Int): RowStatus = RowStatus.Normal

    fun isModified(row: Int, column: Int): Boolean = false

    /** Text shown instead of NULL for cells with no value yet, e.g. `<default>` in a new row. */
    fun placeholder(row: Int, column: Int): String? = null

    /** Null for read-only grids. */
    val editor: GridEditor?
        get() = null
}

interface GridEditor {
    fun canEdit(row: Int, column: Int): Boolean

    fun setValue(row: Int, column: Int, value: String?)

    /** Appends a new row and returns its index. */
    fun addRow(): Int

    /** Marks existing rows for deletion; drops rows that were only inserted. */
    fun deleteRows(rows: Collection<Int>)

    /** Undoes pending edits, insertions and deletions on [rows]. */
    fun revertRows(rows: Collection<Int>)
}

class ResultGridModel(private val result: QueryResult.Rows) : GridModel {
    override val columns: List<ResultColumn> = result.columns
    override val rowCount: Int = result.rows.size

    override fun value(row: Int, column: Int): String? = result.rows[row][column]
}

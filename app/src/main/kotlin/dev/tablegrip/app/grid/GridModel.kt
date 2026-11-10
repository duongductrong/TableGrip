package dev.tablegrip.app.grid

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.edit.ColumnEdit
import dev.tablegrip.core.edit.EditCheck
import kotlinx.serialization.json.JsonArray

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

    /** Why a pending edit can't be saved as it is, e.g. a date that doesn't parse; null when it's fine. */
    fun problem(row: Int, column: Int): String? = null

    /** Null for read-only grids. */
    val editor: GridEditor?
        get() = null

    /**
     * The rows as the JSON view shows them, limited to [columns] in that order, when the model holds richer values than
     * its cells' text (a document's own fields and types); null builds the JSON from the cells. Read on the UI thread.
     */
    fun json(columns: List<Int>): JsonArray? = null

    /** Edits whole rows as JSON; null when the grid is read-only. */
    val records: RecordEditor?
        get() = null
}

interface GridEditor {
    fun canEdit(row: Int, column: Int): Boolean

    /** What input [column] takes, so the grid can offer a fitting editor and check what's typed. */
    fun columnEdit(column: Int): ColumnEdit

    /**
     * What input one cell takes. A table's cells take their column's; a document's fields can hold a different type in
     * every row.
     */
    fun cellEdit(row: Int, column: Int): ColumnEdit = columnEdit(column)

    /** Checks [text] typed into a cell: what would be stored, or why it can't be. */
    fun check(row: Int, column: Int, text: String): EditCheck = cellEdit(row, column).check(text)

    /** The cell menu's name for [setDefault]. */
    val setDefaultLabel: String
        get() = tr("Set DEFAULT")

    fun setValue(row: Int, column: Int, value: String?)

    fun canSetNull(row: Int, column: Int): Boolean = canEdit(row, column)

    /** Whether [setDefault] applies: a new row's left-out column, or an update to a column that has a default. */
    fun canSetDefault(row: Int, column: Int): Boolean = false

    /** Leaves a new row's column out, or sets an existing row's column back to its default. */
    fun setDefault(row: Int, column: Int) {}

    /** Drops the pending edit of one cell, so it's back as read (in a new row: left out). */
    fun revertCell(row: Int, column: Int)

    /** Appends a new row and returns its index. */
    fun addRow(): Int

    /** Marks existing rows for deletion; drops rows that were only inserted. */
    fun deleteRows(rows: Collection<Int>)

    /** Undoes pending edits, insertions and deletions on [rows]. */
    fun revertRows(rows: Collection<Int>)

    /** Undo and redo of the pending edits, every change recorded as it's made; null when the editor keeps none. */
    val history: EditHistory?
        get() = null
}

/** Runs [block] as one undo step of [GridEditor.history], when there is one. */
fun <T> GridEditor.undoable(label: String, row: Int? = null, column: Int? = null, block: () -> T): T =
    history?.change(label, row, column, block) ?: block()

/** [result]'s rows from [offset], at most [limit] of them, so a large result can be shown a page at a time. */
class ResultGridModel(private val result: QueryResult.Rows, private val offset: Int = 0, limit: Int = Int.MAX_VALUE) : GridModel {
    override val columns: List<ResultColumn> = result.columns
    override val rowCount: Int = (result.rows.size - offset).coerceIn(0, limit)

    override fun value(row: Int, column: Int): String? = result.rows[offset + row][column]
}

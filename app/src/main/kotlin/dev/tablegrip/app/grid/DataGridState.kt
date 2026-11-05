package dev.tablegrip.app.grid

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.driver.ResultColumn

/** What floats over the cell being edited, besides its inline text field. */
enum class EditPopup { None, Expanded, Picker }

/** A note shown over the grid; each one is new, so the same text twice still shows twice. */
class GridNotice(val text: String)

/** A cell in display coordinates: [column] indexes [DataGridState.visibleColumns]. */
data class GridCell(val row: Int, val column: Int)

/**
 * A rectangular selection between [anchor] and [focus]. With [wholeRows] it spans every column (row header clicks);
 * the focus cell is where keyboard navigation and editing happen.
 */
data class GridSelection(val anchor: GridCell, val focus: GridCell, val wholeRows: Boolean = false) {
    val rows: IntRange
        get() = minOf(anchor.row, focus.row)..maxOf(anchor.row, focus.row)

    fun columns(columnCount: Int): IntRange =
        if (wholeRows) 0 until columnCount else minOf(anchor.column, focus.column)..maxOf(anchor.column, focus.column)

    fun contains(row: Int, column: Int): Boolean =
        row in rows && (wholeRows || column in minOf(anchor.column, focus.column)..maxOf(anchor.column, focus.column))
}

/**
 * Layout and interaction state of a [DataGrid]: column order, visibility and widths, selection, the cell being edited,
 * and scroll positions. Survives reloads of the same columns, e.g. paging or re-sorting a table.
 */
class DataGridState {
    val verticalScroll = LazyListState()
    val horizontalScroll = ScrollState(0)

    private var columnNames: List<String> = emptyList()

    /** Model column indexes in display order, including hidden ones. */
    val order = mutableStateListOf<Int>()
    var hidden by mutableStateOf(emptySet<Int>())
    /** Widths the user dragged or auto-fitted, by model column; others follow the layout. */
    val userWidths = mutableStateMapOf<Int, Dp>()

    var selection by mutableStateOf<GridSelection?>(null)
    /** Display-coordinate cell being edited inline, and its text. */
    var editing by mutableStateOf<GridCell?>(null)
        private set
    val editText = TextFieldState()
    /** The text editing started from, to tell real edits from Enter-then-Escape. */
    var editInitial: String = ""
        private set
    var editPopup by mutableStateOf(EditPopup.None)
    /** Bumped when a commit is refused because the text doesn't fit the column, so the editor can say so. */
    var editRejections by mutableIntStateOf(0)

    /** A short note over the grid, e.g. what a paste did; it fades after a few seconds. */
    var notice by mutableStateOf<GridNotice?>(null)

    /** Bumped by [requestFocus] so the grid takes keyboard focus, e.g. on Esc from the filter bar. */
    var focusRequests by mutableIntStateOf(0)
        private set

    fun requestFocus() {
        focusRequests++
    }

    /** A cell to scroll into view on the next frame; set via [reveal] by code outside the grid. */
    var pendingReveal by mutableStateOf<GridCell?>(null)

    /** Selects [cell], scrolls it into view and takes keyboard focus, e.g. for a row an Add button just appended. */
    fun reveal(cell: GridCell) {
        select(cell)
        pendingReveal = cell
        requestFocus()
    }

    /** The record open in the JSON record editor, from the grid's menu or the JSON view; null when it's closed. */
    var recordEdit by mutableStateOf<RecordEditRequest?>(null)

    /** Opens [row] of [model] in the JSON record editor, if it can be edited that way. */
    fun editRecord(model: GridModel, row: Int) {
        val records = model.records ?: return
        if (records.canEditRecord(row)) recordEdit = RecordEditRequest(model, row, records.recordJson(row))
    }

    /** Opens the JSON record editor on a new record of [model]. */
    fun newRecord(model: GridModel) {
        val records = model.records?.takeIf { it.canAddRecord } ?: return
        val template = records.newRecord()
        recordEdit = RecordEditRequest(model, null, template.json, template.hint)
    }

    /** Undoes [model]'s last change to its pending edits and shows where it was; false when there's nothing to undo. */
    fun undo(model: GridModel): Boolean = travel(model, back = true)

    /** Makes [model]'s last undone change again and shows where it was; false when there's nothing to redo. */
    fun redo(model: GridModel): Boolean = travel(model, back = false)

    private fun travel(model: GridModel, back: Boolean): Boolean {
        val history = model.editor?.history ?: return false
        if (editing != null) stopEditing()
        val step = (if (back) history.undo() else history.redo()) ?: return false
        if (model.rowCount == 0) selection = null
        else step.row?.let { row ->
            val column = step.column?.let { visibleColumns.indexOf(it).takeIf { index -> index >= 0 } } ?: focus?.column ?: 0
            reveal(GridCell(row.coerceIn(0, model.rowCount - 1), column))
        } ?: clamp(model.rowCount)
        val next = if (back) Shortcuts.Redo else Shortcuts.Undo
        notice = GridNotice("${if (back) "Undid" else "Redid"} ${step.label} · $next ${if (back) "redoes" else "undoes"}")
        return true
    }

    /** [reveal]s [row] at its first column that takes an edit, so typing and paste land where a value can go. */
    fun revealEditable(model: GridModel, row: Int) {
        val column = visibleColumns.indices.firstOrNull { model.editor?.canEdit(row, visibleColumns[it]) == true } ?: 0
        reveal(GridCell(row, column))
    }

    val visibleColumns: List<Int>
        get() = order.filter { it !in hidden }

    val focus: GridCell?
        get() = selection?.focus

    /** Resets the layout when the column set changes; keeps it when only the rows did. */
    fun sync(columns: List<ResultColumn>) {
        val names = columns.map { it.name }
        if (names == columnNames) return
        columnNames = names
        order.clear()
        order.addAll(columns.indices)
        hidden = emptySet()
        userWidths.clear()
        selection = null
        editing = null
    }

    fun modelColumn(displayColumn: Int): Int = visibleColumns[displayColumn]

    fun select(cell: GridCell, extend: Boolean = false, wholeRows: Boolean = false) {
        val current = selection
        selection =
            if (extend && current != null) current.copy(focus = cell, wholeRows = current.wholeRows || wholeRows)
            else GridSelection(cell, cell, wholeRows)
    }

    fun selectAll(rowCount: Int) {
        if (rowCount == 0 || visibleColumns.isEmpty()) return
        selection = GridSelection(GridCell(0, 0), GridCell(rowCount - 1, visibleColumns.lastIndex))
    }

    /** Keeps the selection inside the grid after rows or columns disappear. */
    fun clamp(rowCount: Int) {
        val current = selection ?: return
        val columnCount = visibleColumns.size
        if (rowCount == 0 || columnCount == 0) {
            selection = null
            return
        }
        fun GridCell.clamped() = GridCell(row.coerceIn(0, rowCount - 1), column.coerceIn(0, columnCount - 1))
        selection = current.copy(anchor = current.anchor.clamped(), focus = current.focus.clamped())
    }

    fun startEditing(cell: GridCell, text: String, initial: String = text, popup: EditPopup = EditPopup.None) {
        editing = cell
        editInitial = initial
        editPopup = popup
        editText.edit {
            replace(0, length, text)
            selection = androidx.compose.ui.text.TextRange(length)
        }
        // One text field serves every cell: undo in it must stop at this cell's text, not go back to the last one's.
        editText.undoState.clearHistory()
    }

    fun stopEditing() {
        editing = null
        editPopup = EditPopup.None
    }

    fun hide(modelColumn: Int) {
        if (visibleColumns.size > 1) hidden = hidden + modelColumn
        selection = null
    }

    fun showAll() {
        hidden = emptySet()
    }

    /** Moves the column at display index [from] so it ends up at display index [to]. */
    fun move(from: Int, to: Int) {
        val visible = visibleColumns
        if (from == to || from !in visible.indices) return
        val moving = visible[from]
        val target = visible.getOrNull(to.coerceIn(0, visible.lastIndex)) ?: return
        order.remove(moving)
        val targetIndex = order.indexOf(target)
        order.add(if (to > from) targetIndex + 1 else targetIndex, moving)
        selection = null
    }
}

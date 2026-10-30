package dev.datagrok.app.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.Dp
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.agent.Mention
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.export.CopyFormat
import dev.datagrok.core.export.ResultFormats
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Optional hooks a [DataGrid] host can provide. */
data class GridActions(
    /** Sets [column]'s sort: null direction removes it; additive keeps other sorted columns. */
    val onSort: ((column: String, direction: SortDirection?, additive: Boolean) -> Unit)? = null,
    /** Filters the rows to [value] in [column], or to everything but it when excluding. */
    val onFilterByValue: ((column: String, value: String?, exclude: Boolean) -> Unit)? = null,
    /** ⌘/Ctrl+Enter. */
    val onSubmit: (() -> Unit)? = null,
    /** Used for "Copy as SQL INSERT". */
    val dialect: SqlDialect? = null,
    val tableName: String? = null,
    /** Columns whose values point at rows of another table (foreign keys); their cells get a link arrow. */
    val linkedColumns: Set<String> = emptySet(),
    /** Opens the row [column]'s value in [row] points at, like DataGrip's Go to Referenced Data. */
    val onFollowLink: ((row: Int, column: String) -> Unit)? = null,
    /** Extra cell menu entries for [row], e.g. the rows of other tables that reference it. */
    val relatedRows: ((row: Int) -> List<Pair<String, () -> Unit>>)? = null,
    /** Mentions the selected cells in the agent chat (⌘L). */
    val onMention: ((GridMention) -> Unit)? = null,
)

/** Cells selected for the agent chat: their [columns], model [rows], and the values of the first [Mention.CellRows] rows. */
data class GridMention(val columns: List<ResultColumn>, val rows: IntRange, val values: List<List<String?>>)

internal val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * Everything a [DataGrid] does in response to input. Recreated fields ([model], [geometry], [actions]) are refreshed
 * on every composition, so the long-lived pointer and key handlers always see current values.
 */
internal class GridController(val state: DataGridState, private val scope: CoroutineScope, val gridFocus: FocusRequester) {
    lateinit var model: GridModel
    lateinit var geometry: GridGeometry
    var actions: GridActions = GridActions()
    var sort: List<SortColumn> = emptyList()
    var naturalWidths: List<Dp> = emptyList()
    var density: Float = 1f

    /** Last right-clicked header column, so the context menu knows what to offer. */
    var menuHeaderColumn: Int? = null
        private set
    var hoverRow by mutableStateOf(-1)
        private set

    private var drag: Drag? = null
    private var lastPress: Pair<Long, Any>? = null

    private sealed interface Drag {
        data class Resize(val column: Int, val startX: Float, val startWidth: Float) : Drag

        data class HeaderPress(val column: Int, val startX: Float, var reordering: Boolean = false) : Drag

        data object Cells : Drag

        data object Rows : Drag
    }

    /** Drop position shown while reordering a column, as a display index. */
    var dropIndicator by mutableStateOf<Int?>(null)
        private set

    private val editor: GridEditor?
        get() = model.editor

    private val columnCount: Int
        get() = state.visibleColumns.size

    // region Pointer

    fun hitTest(position: Offset): GridHit? {
        val geometry = geometry
        val contentX = position.x + state.horizontalScroll.value
        if (position.isInHeader(geometry)) {
            if (position.x < geometry.rowNumberWidth) return GridHit.Corner
            return GridHit.Header(geometry.columnAt(contentX).takeIf { it >= 0 }, geometry.borderAt(contentX))
        }
        val row = rowAt(position.y) ?: return null
        if (position.x < geometry.rowNumberWidth) return GridHit.RowNumber(row)
        val column = geometry.columnAt(contentX)
        return if (column < 0) null else GridHit.Cell(row, column)
    }

    /** Row under viewport y, clamped to the rows on screen (for drags past the edges). */
    private fun rowAt(y: Float): Int? {
        val items = state.verticalScroll.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
        if (items.isEmpty()) return null
        items.firstOrNull { y >= it.offset && y < it.offset + it.size }?.let { return it.index - 1 }
        return if (y < items.first().offset) items.first().index - 1 else items.last().index - 1
    }

    fun onPress(position: Offset, primary: Boolean, shift: Boolean, time: Long) {
        val hit = hitTest(position)
        // Presses inside the open editor belong to its text field (caret placement, text selection); taking focus
        // back to the grid would commit the edit.
        if (hit is GridHit.Cell && state.editing == GridCell(hit.row, hit.column)) return
        gridFocus.requestFocus()
        lastPointerContentX = position.x + state.horizontalScroll.value
        val isDouble = lastPress?.let { (t, target) -> time - t < 400 && target == hit } == true
        lastPress = time to (hit ?: Unit)
        menuHeaderColumn = null

        when (hit) {
            null -> commitEdit()
            GridHit.Corner -> if (primary) state.selectAll(model.rowCount)
            is GridHit.Header -> {
                commitEdit()
                if (!primary) {
                    menuHeaderColumn = hit.column
                    return
                }
                val border = hit.border
                when {
                    border != null && isDouble -> autoFit(border)
                    border != null -> drag = Drag.Resize(border, position.x, geometry.widths[border])
                    hit.column != null -> drag = Drag.HeaderPress(hit.column, position.x)
                }
            }
            is GridHit.RowNumber -> {
                commitEdit()
                if (!primary && state.selection?.rows?.contains(hit.row) == true) return
                state.select(GridCell(hit.row, 0), extend = shift && primary, wholeRows = true)
                if (primary) drag = Drag.Rows
            }
            is GridHit.Cell -> {
                val cell = GridCell(hit.row, hit.column)
                if (state.editing != null && state.editing != cell) commitEdit()
                if (!primary) {
                    if (state.selection?.contains(cell.row, cell.column) != true) state.select(cell)
                    return
                }
                if (isDouble) {
                    startEditing(cell)
                    return
                }
                state.select(cell, extend = shift)
                drag = Drag.Cells
            }
        }
    }

    /** Handles pointer movement; returns whether the resize cursor should show. */
    fun onMove(position: Offset, pressed: Boolean): Boolean {
        lastPointerContentX = position.x + state.horizontalScroll.value
        val current = drag
        if (!pressed || current == null) {
            val hit = hitTest(position)
            hoverRow = (hit as? GridHit.Cell)?.row ?: (hit as? GridHit.RowNumber)?.row ?: -1
            return (hit as? GridHit.Header)?.border != null
        }
        when (current) {
            is Drag.Resize -> {
                val width = (current.startWidth + position.x - current.startX).coerceAtLeast(MinColumnWidth.value * density)
                state.userWidths[state.modelColumn(current.column)] = Dp(width / density)
            }
            is Drag.HeaderPress -> {
                if (!current.reordering && kotlin.math.abs(position.x - current.startX) > 6 * density) current.reordering = true
                if (current.reordering) dropIndicator = geometry.dropIndex(position.x + state.horizontalScroll.value)
            }
            Drag.Cells -> {
                autoScroll(position)
                (hitTest(position) as? GridHit.Cell)?.let { state.select(GridCell(it.row, it.column), extend = true) }
            }
            Drag.Rows -> {
                autoScroll(position)
                val row = (hitTest(position) as? GridHit.RowNumber)?.row ?: (hitTest(position) as? GridHit.Cell)?.row
                if (row != null) state.select(GridCell(row, columnCount - 1), extend = true, wholeRows = true)
            }
        }
        return current is Drag.Resize
    }

    fun onExit() {
        hoverRow = -1
    }

    fun onRelease(shift: Boolean) {
        val current = drag
        drag = null
        dropIndicator = null
        if (current !is Drag.HeaderPress) return
        if (current.reordering) {
            val insertBefore = geometry.dropIndex(lastPointerContentX)
            // Removing the column first shifts everything after it left by one.
            state.move(current.column, if (insertBefore > current.column) insertBefore - 1 else insertBefore)
        } else {
            toggleSort(current.column, additive = shift)
        }
    }

    /** Tracks the pointer for the drop calculation on release. */
    var lastPointerContentX: Float = 0f

    private fun autoScroll(position: Offset) {
        val step = geometry.rowHeight
        when {
            position.y > geometry.viewportHeight - step / 2 -> scope.launch { state.verticalScroll.scrollBy(step) }
            position.y < geometry.headerHeight + step / 2 -> scope.launch { state.verticalScroll.scrollBy(-step) }
        }
    }

    // endregion

    // region Columns and sorting

    fun autoFit(column: Int) {
        val model = state.modelColumn(column)
        naturalWidths.getOrNull(model)?.let { state.userWidths[model] = it }
    }

    fun sortDirection(column: Int): SortDirection? = sort.firstOrNull { it.column == columnName(column) }?.direction

    /** 1-based position among sorted columns when more than one is sorted. */
    fun sortOrder(column: Int): Int? =
        if (sort.size < 2) null else sort.indexOfFirst { it.column == columnName(column) }.takeIf { it >= 0 }?.plus(1)

    fun toggleSort(column: Int, additive: Boolean) {
        val onSort = actions.onSort ?: return
        val next =
            when (sortDirection(column)) {
                null -> SortDirection.Asc
                SortDirection.Asc -> SortDirection.Desc
                SortDirection.Desc -> null
            }
        onSort(columnName(column), next, additive)
    }

    fun columnName(column: Int): String = model.columns[state.modelColumn(column)].name

    // endregion

    // region Keyboard

    /** Follows the focused cell's foreign key, if it has one and a value. */
    fun followFocusedLink(): Boolean {
        val focus = state.focus ?: return false
        val follow = actions.onFollowLink ?: return false
        val column = columnName(focus.column)
        if (column !in actions.linkedColumns || model.value(focus.row, state.modelColumn(focus.column)) == null) return false
        follow(focus.row, column)
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || state.editing != null) return false
        val primary = if (isMac) event.isMetaPressed else event.isCtrlPressed
        val shift = event.isShiftPressed
        val rowCount = model.rowCount
        if (rowCount == 0 && event.key != Key.N) return false
        val focus = state.focus

        return when {
            primary && event.key == Key.A -> state.selectAll(rowCount).let { true }
            primary && event.key == Key.C -> copy(CopyFormat.Tsv, includeHeader = shift).let { true }
            Shortcuts.AddToChat.matches(event) -> mentionSelection()
            primary && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> actions.onSubmit?.invoke().let { actions.onSubmit != null }
            primary && event.isAltPressed && event.key == Key.N -> setNull().let { true }
            primary && event.key == Key.N -> addRow().let { editor != null }
            Shortcuts.GoToReferenced.matches(event) -> followFocusedLink()
            Shortcuts.FilterByValue.matches(event) -> filterByFocusedValue(exclude = false)
            Shortcuts.ExcludeValue.matches(event) -> filterByFocusedValue(exclude = true)
            // ⇧ is left to the table's Clear Filter.
            primary && !shift && (event.key == Key.Backspace || event.key == Key.Delete) -> deleteSelectedRows().let { editor != null }
            primary && event.key == Key.Z -> revertSelectedRows().let { editor != null }
            primary && event.key == Key.DirectionUp -> moveTo(GridCell(0, focus?.column ?: 0), shift)
            primary && event.key == Key.DirectionDown -> moveTo(GridCell(rowCount - 1, focus?.column ?: 0), shift)
            primary && event.key == Key.MoveHome -> moveTo(GridCell(0, 0), shift)
            primary && event.key == Key.MoveEnd -> moveTo(GridCell(rowCount - 1, columnCount - 1), shift)
            primary || event.isCtrlPressed -> false
            event.key == Key.DirectionUp -> moveBy(-1, 0, shift)
            event.key == Key.DirectionDown -> moveBy(1, 0, shift)
            event.key == Key.DirectionLeft -> moveBy(0, -1, shift)
            event.key == Key.DirectionRight -> moveBy(0, 1, shift)
            event.key == Key.Tab -> moveBy(0, if (shift) -1 else 1, extend = false)
            event.key == Key.MoveHome -> moveTo(GridCell(focus?.row ?: 0, 0), shift)
            event.key == Key.MoveEnd -> moveTo(GridCell(focus?.row ?: 0, columnCount - 1), shift)
            event.key == Key.PageUp -> moveBy(-pageRows(), 0, shift)
            event.key == Key.PageDown -> moveBy(pageRows(), 0, shift)
            event.key == Key.Escape -> focus?.let { state.select(it) }.let { focus != null }
            event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.F2 -> focus?.let { startEditing(it) } == true
            event.key == Key.Backspace || event.key == Key.Delete -> focus?.let { startEditing(it, replaceWith = "") } == true
            else -> {
                val text = typedText(event.utf16CodePoint)
                if (text != null && !event.isAltPressed) focus?.let { startEditing(it, replaceWith = text) } == true
                else false
            }
        }
    }

    private fun filterByFocusedValue(exclude: Boolean): Boolean {
        val filter = actions.onFilterByValue ?: return false
        val focus = state.focus ?: return false
        filter(columnName(focus.column), model.value(focus.row, state.modelColumn(focus.column)), exclude)
        return true
    }

    private fun pageRows(): Int = ((geometry.viewportHeight - geometry.headerHeight) / geometry.rowHeight).toInt().coerceAtLeast(1) - 1

    private fun moveBy(rows: Int, columns: Int, extend: Boolean): Boolean {
        val focus = state.focus ?: return moveTo(GridCell(0, 0), false)
        return moveTo(GridCell(focus.row + rows, focus.column + columns), extend)
    }

    private fun moveTo(cell: GridCell, extend: Boolean): Boolean {
        if (model.rowCount == 0 || columnCount == 0) return false
        val clamped = GridCell(cell.row.coerceIn(0, model.rowCount - 1), cell.column.coerceIn(0, columnCount - 1))
        state.select(clamped, extend)
        scrollIntoView(clamped)
        return true
    }

    fun scrollIntoView(cell: GridCell) {
        val geometry = geometry
        scope.launch {
            val list = state.verticalScroll
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == cell.row + 1 }
            val bottom = info.viewportEndOffset - ScrollbarAllowance.value * density
            when {
                // The sticky header is item 0: scrolling to row N puts row N-1 under it and row N just below.
                item == null -> list.scrollToItem(cell.row)
                item.offset < geometry.headerHeight -> list.scrollBy(item.offset - geometry.headerHeight)
                item.offset + item.size > bottom -> list.scrollBy(item.offset + item.size - bottom)
            }
        }
        scope.launch {
            val scroll = state.horizontalScroll
            val left = geometry.offsets[cell.column] - geometry.rowNumberWidth
            val right = geometry.right(cell.column) - geometry.viewportWidth + ScrollbarAllowance.value * density
            when {
                left < scroll.value -> scroll.scrollTo(left.toInt())
                right > scroll.value -> scroll.scrollTo(right.toInt().coerceAtMost(left.toInt()))
            }
        }
    }

    // endregion

    // region Editing

    fun canEdit(cell: GridCell): Boolean = editor?.canEdit(cell.row, state.modelColumn(cell.column)) == true

    /** Starts inline editing; [replaceWith] replaces the value (typing over a cell) instead of editing it. */
    fun startEditing(cell: GridCell, replaceWith: String? = null): Boolean {
        if (!canEdit(cell)) return false
        state.select(cell)
        scrollIntoView(cell)
        val current = model.value(cell.row, state.modelColumn(cell.column)).orEmpty()
        state.startEditing(cell, replaceWith ?: current, initial = current)
        return true
    }

    fun commitEdit(moveDown: Boolean = false, moveRight: Int = 0) {
        val cell = state.editing ?: return
        val text = state.editText.text.toString()
        state.stopEditing()
        if (text != state.editInitial) editor?.setValue(cell.row, state.modelColumn(cell.column), text)
        gridFocus.requestFocus()
        if (moveDown) moveBy(1, 0, false)
        if (moveRight != 0) moveBy(0, moveRight, false)
    }

    fun cancelEdit() {
        state.stopEditing()
        gridFocus.requestFocus()
    }

    fun setNull() {
        val editor = editor ?: return
        forEachSelectedCell { row, column -> if (editor.canEdit(row, column)) editor.setValue(row, column, null) }
    }

    fun addRow() {
        val row = editor?.addRow() ?: return
        moveTo(GridCell(row, 0), extend = false)
    }

    fun deleteSelectedRows() {
        val rows = state.selection?.rows ?: return
        editor?.deleteRows(rows.toList())
        state.clamp(model.rowCount)
    }

    fun revertSelectedRows() {
        val rows = state.selection?.rows ?: return
        editor?.revertRows(rows.toList())
        state.clamp(model.rowCount)
    }

    private inline fun forEachSelectedCell(action: (row: Int, modelColumn: Int) -> Unit) {
        val selection = state.selection ?: return
        val columns = selection.columns(columnCount)
        for (row in selection.rows) for (column in columns) action(row, state.modelColumn(column))
    }

    // endregion

    // region Copy

    fun selectedBlock(): Pair<List<ResultColumn>, List<List<String?>>> {
        val selection = state.selection ?: return emptyList<ResultColumn>() to emptyList()
        val columns = selection.columns(columnCount).map(state::modelColumn)
        val rows = selection.rows.filter { it < model.rowCount }.map { row -> columns.map { model.value(row, it) } }
        return columns.map { model.columns[it] } to rows
    }

    /**
     * Hands the selected cells to [GridActions.onMention]; false when there is no handler or nothing selected. Only the
     * rows a mention keeps are read, so ⌘A ⌘L on a large result doesn't copy all of it.
     */
    fun mentionSelection(): Boolean {
        val mention = actions.onMention ?: return false
        val selection = state.selection ?: return false
        val rows = selection.rows.first..minOf(selection.rows.last, model.rowCount - 1)
        val columns = selection.columns(columnCount).map(state::modelColumn)
        if (rows.isEmpty() || columns.isEmpty()) return false
        val values = rows.take(Mention.CellRows).map { row -> columns.map { model.value(row, it) } }
        mention(GridMention(columns.map { model.columns[it] }, rows, values))
        return true
    }

    fun copy(format: CopyFormat, includeHeader: Boolean = false) {
        val (columns, rows) = selectedBlock()
        if (rows.isEmpty()) return
        copyToClipboard(ResultFormats.format(format, columns, rows, includeHeader, actions.dialect, actions.tableName))
    }

    // endregion
}

/**
 * The text a key press types, or null when it types nothing. AWT reports keys without a character
 * (Shift, Caps Lock, Fn, F1…) as [java.awt.event.KeyEvent.CHAR_UNDEFINED] (U+FFFF), not 0.
 */
internal fun typedText(codePoint: Int): String? {
    if (codePoint <= 0 || codePoint > Char.MAX_VALUE.code) return null
    val char = codePoint.toChar()
    if (char == java.awt.event.KeyEvent.CHAR_UNDEFINED || Character.isISOControl(char) || char.isSurrogate() || !Character.isDefined(char)) return null
    return char.toString()
}

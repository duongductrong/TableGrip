package dev.tablegrip.app.grid

import dev.tablegrip.app.ui.TextClipboard
import dev.tablegrip.core.export.ClipboardTable

/** Cells on the clipboard, by row; [header] names the column each position goes to, when the block came with one. */
internal class ClipboardBlock(val rows: List<List<String?>>, val header: List<String>? = null)

/** What a grid last copied as TSV: the [text] it put on the clipboard, and the cells' own values with their NULLs. */
internal class CopiedBlock(val text: String, val header: List<String>?, val values: List<List<String?>>)

/** The grids' side of the system clipboard, shared so values copied in one grid keep their NULLs when pasted in another. */
internal object GridClipboard {
    var last: CopiedBlock? = null

    /** The clipboard's cells: a grid's own copy while the clipboard still holds it, else its text split into cells. */
    fun read(clipboard: TextClipboard = TextClipboard.System): ClipboardBlock? {
        val text = clipboard.get() ?: return null
        last?.takeIf { it.text == text }?.let { return ClipboardBlock(it.values, it.header) }
        return ClipboardTable.parse(text).takeIf { it.isNotEmpty() }?.let(::ClipboardBlock)
    }
}

/**
 * Where pasted values go, in display cells. Rows from [firstNewRow] on don't exist yet; [newRows] of them are added
 * first. [cutColumns] and [cutRows] count clipboard columns and rows with nowhere to go; [range] is what to select.
 */
internal data class PastePlan(
    val writes: List<Pair<GridCell, String?>>,
    val newRows: Int,
    val cutColumns: Int,
    val cutRows: Int,
    val range: GridSelection,
)

/**
 * Lays [block] out over the grid, like a spreadsheet:
 * - with a header (copied with one, or a first row mostly naming the grid's columns), values go to the columns of those
 *   names and the rest are cut off;
 * - one value fills every selected cell, and a block repeats over a selection that's a whole number of its size;
 * - otherwise the block starts at the selection's top-left cell.
 * Rows past the end are added when [canAddRows]; columns past the last one are cut off.
 */
internal fun planPaste(block: ClipboardBlock, selection: GridSelection, columnNames: List<String>, rowCount: Int, canAddRows: Boolean): PastePlan? {
    val columnCount = columnNames.size
    var rows = block.rows
    var header = block.header
    if (header == null && rows.size >= 2) {
        val first = rows.first()
        // Distinct names, most of them the grid's: another table's header row, maybe with columns this grid lacks.
        if (first.none { it.isNullOrEmpty() } && first.distinct().size == first.size && first.count { it in columnNames } * 2 > first.size) {
            header = first.map { it!! }
            rows = rows.drop(1)
        }
    }
    if (rows.isEmpty() || columnCount == 0) return null

    val top = selection.rows.first
    val writes = mutableListOf<Pair<GridCell, String?>>()
    var cutColumns = 0
    if (header != null) {
        val targets = header.map { columnNames.indexOf(it) }
        cutColumns = targets.count { it < 0 }
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, value -> targets.getOrElse(c) { -1 }.takeIf { it >= 0 }?.let { writes += GridCell(top + r, it) to value } }
        }
    } else {
        val columns = selection.columns(columnCount)
        val left = columns.first
        val height = rows.size
        val width = rows.maxOf { it.size }
        val single = selection.rows.first == selection.rows.last && columns.first == columns.last
        if (!single && selection.rows.count() % height == 0 && columns.count() % width == 0) {
            for (row in selection.rows) for (column in columns) {
                val cells = rows[(row - top) % height]
                val index = (column - left) % width
                if (index < cells.size) writes += GridCell(row, column) to cells[index]
            }
        } else {
            rows.forEachIndexed { r, cells -> cells.forEachIndexed { c, value -> if (left + c < columnCount) writes += GridCell(top + r, left + c) to value } }
            cutColumns = (left + width - columnCount).coerceAtLeast(0)
        }
    }

    val lastRow = writes.maxOfOrNull { it.first.row } ?: return null
    val kept = if (canAddRows) writes else writes.filter { it.first.row < rowCount }
    if (kept.isEmpty()) return null
    val cutRows = if (canAddRows) 0 else (lastRow + 1 - rowCount).coerceAtLeast(0)
    val range =
        GridSelection(
            GridCell(kept.minOf { it.first.row }, kept.minOf { it.first.column }),
            GridCell(kept.maxOf { it.first.row }, kept.maxOf { it.first.column }),
        )
    return PastePlan(kept, newRows = if (canAddRows) (lastRow + 1 - rowCount).coerceAtLeast(0) else 0, cutColumns, cutRows, range)
}

/** A cell as it was before a paste wrote [after] into it, so the paste can be undone. */
internal class PastedCell(val row: Int, val column: Int, val before: String?, val modified: Boolean, val placeholder: String?, var after: String? = null)

/** The last paste into a grid: undone while [range] is still selected and its cells still hold what it wrote. */
internal class PasteUndo(val model: GridModel, val range: GridSelection, val cells: List<PastedCell>, val firstNewRow: Int, val newRows: Int)

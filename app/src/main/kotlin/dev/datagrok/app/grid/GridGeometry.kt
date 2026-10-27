package dev.datagrok.app.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val RowHeight = 26.dp
internal val HeaderHeight = 30.dp
internal val MinColumnWidth = 48.dp
internal val MaxNaturalWidth = 420.dp
internal val ScrollbarAllowance = 12.dp

/**
 * Column positions for one layout pass, in pixels of the scrollable content. Index `i` is a display column;
 * [offsets] already include the pinned row-number column.
 */
internal class GridGeometry(
    val rowNumberWidth: Float,
    val offsets: FloatArray,
    val widths: FloatArray,
    val headerHeight: Float,
    val rowHeight: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val resizeSlop: Float,
) {
    val contentWidth: Float
        get() = if (offsets.isEmpty()) rowNumberWidth else offsets.last() + widths.last()

    val columnCount: Int
        get() = offsets.size

    fun right(column: Int) = offsets[column] + widths[column]

    /** Display columns intersecting the viewport when scrolled to [scrollX], plus one either side. */
    fun visibleRange(scrollX: Float): IntRange {
        if (columnCount == 0) return IntRange.EMPTY
        val left = scrollX + rowNumberWidth
        val right = scrollX + viewportWidth
        var first = 0
        while (first < columnCount - 1 && right(first) <= left) first++
        var last = first
        while (last < columnCount - 1 && offsets[last + 1] < right) last++
        return (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost(columnCount - 1)
    }

    /** The display column under content x, clamped to the first/last column. */
    fun columnAt(contentX: Float): Int {
        if (columnCount == 0) return -1
        for (i in 0 until columnCount) if (contentX < right(i)) return i
        return columnCount - 1
    }

    /** The column whose right border is within reach of content x, for resizing. */
    fun borderAt(contentX: Float): Int? =
        (0 until columnCount).firstOrNull { kotlin.math.abs(right(it) - contentX) <= resizeSlop }

    /** Insertion point for a dragged column, in `0..columnCount`: it goes before the column returned. */
    fun dropIndex(contentX: Float): Int {
        for (i in 0 until columnCount) if (contentX < offsets[i] + widths[i] / 2) return i
        return columnCount
    }

    /** X of the insertion marker for [dropIndex]. */
    fun dropX(index: Int): Float = if (index >= columnCount) contentWidth else offsets[index]
}

internal sealed interface GridHit {
    data class Header(val column: Int?, val border: Int?) : GridHit

    data object Corner : GridHit

    data class RowNumber(val row: Int) : GridHit

    data class Cell(val row: Int, val column: Int) : GridHit
}

/** Distributes spare viewport width over columns the user hasn't sized, so the grid always fills its space. */
internal fun fitWidths(base: List<Dp>, flexible: Set<Int>, available: Dp): List<Dp> {
    val total = base.fold(0.dp) { acc, w -> acc + w }
    if (total >= available || flexible.isEmpty()) return base
    val flexTotal = flexible.fold(0.dp) { acc, i -> acc + base[i] }
    val extra = available - total
    return base.mapIndexed { i, w -> if (i in flexible) w + extra * (w / flexTotal) else w }
}

internal fun Offset.isInHeader(geometry: GridGeometry) = y < geometry.headerHeight

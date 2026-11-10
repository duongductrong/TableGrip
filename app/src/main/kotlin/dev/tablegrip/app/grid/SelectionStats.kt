package dev.tablegrip.app.grid

import dev.tablegrip.core.i18n.tr
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Excel-style aggregates of a grid selection: [count] counts non-NULL cells; the numeric figures cover cells that parse
 * as numbers and are null when there are none.
 */
data class SelectionStats(
    val cells: Int,
    val count: Int,
    val numbers: Int,
    val sum: BigDecimal?,
    val min: BigDecimal?,
    val max: BigDecimal?,
) {
    val average: BigDecimal?
        get() = sum?.takeIf { numbers > 0 }?.divide(BigDecimal(numbers), MathContext.DECIMAL64)

    /** E.g. `Count: 12 · Sum: 1 234.5 · Avg: 102.88 · Min: 1 · Max: 500`. */
    val summary: String
        get() =
            buildList {
                add(tr("Count: {0}", count))
                if (numbers > 0) {
                    add(tr("Sum: {0}", sum.format()))
                    add(tr("Avg: {0}", average.format()))
                    add(tr("Min: {0}", min.format()))
                    add(tr("Max: {0}", max.format()))
                }
            }.joinToString(" · ")

    companion object {
        /** Selections bigger than this aren't aggregated, so a ⌘A on a huge result doesn't stall the UI. */
        const val MaxCells = 200_000

        /** Null unless more than one cell is selected. */
        fun of(model: GridModel, state: DataGridState): SelectionStats? {
            val selection = state.selection ?: return null
            val visible = state.visibleColumns
            val rows = selection.rows.first.coerceAtLeast(0)..selection.rows.last.coerceAtMost(model.rowCount - 1)
            val columns = selection.columns(visible.size).let { it.first.coerceAtLeast(0)..it.last.coerceAtMost(visible.lastIndex) }
            val cells = rows.count() * columns.count()
            if (cells < 2 || cells > MaxCells) return null
            var count = 0
            var numbers = 0
            var sum = BigDecimal.ZERO
            var min: BigDecimal? = null
            var max: BigDecimal? = null
            for (row in rows) {
                for (column in columns) {
                    val value = model.value(row, visible[column]) ?: continue
                    count++
                    val number = value.trim().toBigDecimalOrNull() ?: continue
                    numbers++
                    sum += number
                    if (min == null || number < min) min = number
                    if (max == null || number > max) max = number
                }
            }
            return SelectionStats(cells, count, numbers, sum.takeIf { numbers > 0 }, min, max)
        }

        private fun BigDecimal?.format(): String {
            if (this == null) return "—"
            val rounded = if (scale() > 4) setScale(4, RoundingMode.HALF_UP) else this
            return rounded.stripTrailingZeros().toPlainString()
        }
    }
}

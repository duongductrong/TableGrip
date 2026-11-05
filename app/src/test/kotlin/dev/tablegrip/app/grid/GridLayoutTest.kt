package dev.tablegrip.app.grid

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class GridLayoutTest {
    @Test
    fun `spare width goes to columns the user has not sized, proportionally`() {
        val widths = fitWidths(listOf(100.dp, 200.dp, 50.dp), flexible = setOf(0, 1), available = 650.dp)
        assertEquals(listOf(200.dp, 400.dp, 50.dp), widths)
    }

    @Test
    fun `columns keep their natural widths when they overflow`() {
        val base = listOf(400.dp, 400.dp)
        assertEquals(base, fitWidths(base, flexible = setOf(0, 1), available = 600.dp))
    }

    @Test
    fun `moving a column lands it where it was dropped`() {
        val state = DataGridState()
        state.sync((0 until 4).map { dev.tablegrip.core.driver.ResultColumn("c$it", "text") })
        state.move(from = 0, to = 2)
        assertEquals(listOf(1, 2, 0, 3), state.order.toList())
        state.hide(1)
        // Display indexes skip hidden columns: [2, 0, 3] -> move "3" to the front.
        state.move(from = 2, to = 0)
        assertEquals(listOf(3, 2, 0), state.visibleColumns)
    }

    @Test
    fun `only columns near the viewport are visible`() {
        val geometry =
            GridGeometry(
                rowNumberWidth = 40f,
                offsets = FloatArray(20) { 40f + it * 100f },
                widths = FloatArray(20) { 100f },
                headerHeight = 30f,
                rowHeight = 26f,
                viewportWidth = 340f,
                viewportHeight = 300f,
                resizeSlop = 4f,
            )
        assertEquals(0..3, geometry.visibleRange(0f))
        // Scrolled 1000px: columns 10..12 are on screen, plus one either side.
        assertEquals(9..13, geometry.visibleRange(1000f))
        assertEquals(2, geometry.columnAt(40f + 250f))
        assertEquals(1, geometry.borderAt(40f + 202f))
        assertEquals(3, geometry.dropIndex(40f + 330f)) // left of column 3's midpoint
        assertEquals(4, geometry.dropIndex(40f + 360f))
        assertEquals(20, geometry.dropIndex(5_000f)) // past the last column
    }
}

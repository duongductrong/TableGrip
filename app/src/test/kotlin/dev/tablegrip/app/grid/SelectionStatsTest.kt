package dev.tablegrip.app.grid

import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionStatsTest {
    private val model =
        ResultGridModel(
            QueryResult.Rows(
                columns = listOf(ResultColumn("id", "integer", Types.INTEGER), ResultColumn("name", "text"), ResultColumn("price", "numeric", Types.NUMERIC)),
                rows = listOf(listOf("1", "a", "10.5"), listOf("2", "b", null), listOf("3", "c", "4.5")),
                truncated = false,
                elapsedMillis = 1,
            )
        )

    private fun stateSelecting(anchor: GridCell, focus: GridCell, wholeRows: Boolean = false) =
        DataGridState().apply {
            sync(model.columns)
            selection = GridSelection(anchor, focus, wholeRows)
        }

    @Test
    fun `a single cell has no stats`() {
        assertNull(SelectionStats.of(model, stateSelecting(GridCell(0, 0), GridCell(0, 0))))
    }

    @Test
    fun `numbers are summed and nulls are not counted`() {
        val stats = SelectionStats.of(model, stateSelecting(GridCell(0, 2), GridCell(2, 2)))!!
        assertEquals(3, stats.cells)
        assertEquals(2, stats.count)
        assertEquals("Count: 2 · Sum: 15 · Avg: 7.5 · Min: 4.5 · Max: 10.5", stats.summary)
    }

    @Test
    fun `text only shows the count`() {
        val stats = SelectionStats.of(model, stateSelecting(GridCell(0, 1), GridCell(2, 1)))!!
        assertEquals("Count: 3", stats.summary)
    }

    @Test
    fun `whole rows span every visible column`() {
        val state = stateSelecting(GridCell(0, 0), GridCell(1, 0), wholeRows = true)
        state.hide(1)
        state.selection = GridSelection(GridCell(0, 0), GridCell(1, 0), wholeRows = true)
        val stats = SelectionStats.of(model, state)!!
        assertEquals(4, stats.cells)
        assertEquals("Count: 3 · Sum: 13.5 · Avg: 4.5 · Min: 1 · Max: 10.5", stats.summary)
    }
}

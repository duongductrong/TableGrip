package dev.tablegrip.app.chart

import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.driver.ChartRows
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ChartStateTest {
    private val columns = listOf(ResultColumn("region", "text", Types.VARCHAR), ResultColumn("total", "numeric", Types.NUMERIC))
    private val fetched = listOf(listOf("north", "1"), listOf("south", "2"), listOf("north", "3"))
    private val bySum = ChartSpec(ChartType.Bar, "region", listOf(Measure("total", Aggregate.Sum)))

    /** What the server would return for [bySum] over every row, not just the ones fetched. */
    private val everyRow =
        ChartRows(
            QueryResult.Rows(listOf(ResultColumn("region", "text", Types.VARCHAR), ResultColumn("sum(total)", "numeric", Types.NUMERIC)), listOf(listOf("north", "40"), listOf("south", "60")), false, 0),
            ChartSpec(ChartType.Bar, "region", listOf(Measure("sum(total)"))),
        )

    private fun sums(chart: ChartState) = chart.frame!!.points.associate { it.x.toString() to it.y }

    @Test
    fun `groups on the server only when the rows fetched are not all of them`() = runBlocking {
        var asked = 0
        val complete = ChartState(QueryResult.Rows(columns, fetched, truncated = false, elapsedMillis = 0)) { asked++; everyRow }
        complete.prepare()
        complete.show(bySum)
        complete.rebuild()
        assertEquals(0, asked)
        assertFalse(complete.onServer)
        assertEquals(mapOf("north" to 4.0, "south" to 2.0), sums(complete))

        val partial = ChartState(QueryResult.Rows(columns, fetched, truncated = true, elapsedMillis = 0)) { asked++; everyRow }
        partial.prepare()
        partial.show(bySum)
        partial.rebuild()
        assertEquals(1, asked)
        assertTrue(partial.onServer)
        assertFalse(partial.partial)
        assertEquals(mapOf("north" to 40.0, "south" to 60.0), sums(partial))
    }

    @Test
    fun `falls back to the rows fetched when the server can't group`() = runBlocking {
        val chart = ChartState(QueryResult.Rows(columns, fetched, truncated = true, elapsedMillis = 0)) { error("timed out") }
        chart.prepare()
        chart.show(bySum)
        chart.rebuild()
        assertFalse(chart.onServer)
        assertTrue(chart.partial)
        assertEquals("timed out", chart.serverError)
        assertEquals(mapOf("north" to 4.0, "south" to 2.0), sums(chart))

        val unsupported = ChartState(QueryResult.Rows(columns, fetched, truncated = true, elapsedMillis = 0)) { null }
        unsupported.prepare()
        unsupported.show(bySum)
        unsupported.rebuild()
        assertFalse(unsupported.onServer)
        assertNull(unsupported.serverError)
    }
}

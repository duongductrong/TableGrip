package dev.tablegrip.core.assist

import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.ColumnProfiler
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChartSpecTaskTest {
    private val profiles =
        ColumnProfiler.profile(
            listOf(ResultColumn("created_at", "timestamp", Types.TIMESTAMP), ResultColumn("region", "text", Types.VARCHAR), ResultColumn("total", "numeric", Types.NUMERIC)),
            listOf(listOf("2024-01-01 10:00", "north", "10"), listOf("2024-02-01 10:00", "south", "20")),
        )

    private fun task(samples: Map<String, List<String>> = emptyMap()) =
        ChartSpecTask("revenue by month per region", "PostgreSQL", "SELECT * FROM orders", profiles, samples, truncated = true)

    private fun parse(json: String) = task().parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `the prompt describes columns, and values only when given`() {
        val prompt = task().prompt()
        assertTrue("- created_at: temporal (timestamp), 2 distinct values" in prompt)
        assertTrue("the first ones only" in prompt)
        assertFalse("north" in prompt)
        assertTrue("e.g. \"north\", \"south\"" in task(mapOf("region" to listOf("north", "south"))).prompt())
    }

    @Test
    fun `reads a spec, treating null and none as absent`() {
        val proposal =
            parse(
                """{"type": "Line", "x": "created_at", "y": [{"column": "total", "aggregate": "sum"}], "series": "region", "bucket": "month",
                   "horizontal": null, "explanation": "Monthly revenue.", "assumptions": ["total is revenue"]}"""
            )
        assertEquals(ChartType.Line, proposal.spec.type)
        assertEquals(listOf(Measure("total", Aggregate.Sum)), proposal.spec.y)
        assertEquals(TimeBucket.Month, proposal.spec.bucket)
        assertEquals(listOf("total is revenue"), proposal.assumptions)
        val bars = parse("""{"type": "bar", "x": "region", "y": [{"column": "total", "aggregate": "none"}], "series": "null", "bucket": null, "explanation": ""}""")
        assertEquals(null, bars.spec.series)
        assertEquals(null, bars.spec.bucket)
    }

    @Test
    fun `invented columns and wrong shapes are sent back with reasons`() {
        val invented = assertFailsWith<AssistInvalid> { parse("""{"type": "bar", "x": "country", "y": [{"column": "revenue", "aggregate": "sum"}]}""") }
        assertEquals(2, invented.reasons.size)
        assertTrue(invented.reasons.all { "created_at, region, total" in it })
        val shape = assertFailsWith<AssistInvalid> { parse("""{"type": "donut", "x": "region", "y": "total", "bucket": "fortnight"}""") }
        assertEquals(3, shape.reasons.size)
    }
}

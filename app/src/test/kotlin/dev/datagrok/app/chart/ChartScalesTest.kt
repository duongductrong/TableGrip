package dev.datagrok.app.chart

import dev.datagrok.core.chart.ChartValues
import dev.datagrok.core.chart.TimeBucket
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartScalesTest {
    private fun millis(text: String) = ChartValues.epochMillis(text)!!

    @Test
    fun `number axes step by 1, 2 or 5 and cover the data`() {
        val axis = ChartScales.numeric(3.0, 97.0, count = 5)
        assertEquals(listOf("0", "20", "40", "60", "80", "100"), axis.ticks.map { it.second })
        assertEquals(0.0 to 100.0, axis.min to axis.max)
        assertEquals(listOf("0.0", "0.5", "1.0", "1.5"), ChartScales.numeric(0.1, 1.4, count = 3).ticks.map { it.second })
        // Bars start at zero; one value gets room around it.
        assertEquals(0.0, ChartScales.numeric(40.0, 90.0, zero = true).min)
        assertEquals(true, ChartScales.numeric(5.0, 5.0).let { it.min < 5 && it.max > 5 })
        assertEquals("12,000", ChartScales.numeric(0.0, 12_000.0).ticks.last().second)
    }

    @Test
    fun `date axes tick on calendar units, naming the year once`() {
        val year = ChartScales.temporal(millis("2024-01-01"), millis("2024-12-31"), count = 6)
        assertEquals(listOf("Jan 2024", "Apr", "Jul", "Oct"), year.ticks.map { it.second })
        val days = ChartScales.temporal(millis("2024-03-30"), millis("2024-04-03"), count = 6)
        assertEquals(listOf("Mar 30, 2024", "Mar 31", "Apr 1", "Apr 2", "Apr 3"), days.ticks.map { it.second })
        val hours = ChartScales.temporal(millis("2024-03-05 00:00"), millis("2024-03-05 18:00"), count = 4)
        assertEquals(listOf("Mar 5 00:00", "06:00", "12:00", "18:00"), hours.ticks.map { it.second })
    }

    @Test
    fun `tooltips show values grouped and dates as precise as their bucket`() {
        assertEquals("1,234.57", ChartScales.value(1234.5678))
        assertEquals("-3", ChartScales.value(-3.0))
        val time = ChartValues.epochMillis(LocalDateTime.of(2024, 8, 12, 0, 0))
        assertEquals("Q3 2024", ChartScales.date(time, TimeBucket.Quarter))
        assertEquals("Week of 2024-08-12", ChartScales.date(time, TimeBucket.Week))
        assertEquals("2024-08-12", ChartScales.date(time, null))
    }

    @Test
    fun `labels are cut before layout and kept on one line`() {
        val json = "{\"answer\":\"" + "x".repeat(4000) + "\"}"
        assertEquals(81, ChartPlot.shorten(json, 80).length)
        assertEquals("a b", ChartPlot.shorten("a\r\nb", 80))
        assertEquals("short", ChartPlot.shorten("short", 80))
    }
}

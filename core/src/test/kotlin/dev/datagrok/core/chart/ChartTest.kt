package dev.datagrok.core.chart

import dev.datagrok.core.driver.ResultColumn
import java.sql.Types
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartTest {
    private val text = Types.VARCHAR

    private fun profile(vararg columns: Pair<String, Int>, rows: List<List<String?>>) =
        ColumnProfiler.profile(columns.map { (name, type) -> ResultColumn(name, "", type) }, rows)

    @Test
    fun `reads dates in the forms drivers write them`() {
        val noon = LocalDateTime.of(2024, 3, 5, 12, 0)
        assertEquals(noon.toLocalDate().atStartOfDay(), ChartValues.dateTime("2024-03-05"))
        assertEquals(noon, ChartValues.dateTime("2024-03-05 12:00:00"))
        assertEquals(noon, ChartValues.dateTime("2024-03-05T12:00"))
        assertEquals(noon.withNano(250_000_000), ChartValues.dateTime("2024-03-05 12:00:00.25"))
        // Offsets are dropped: the wall-clock time is what the database shows.
        assertEquals(noon, ChartValues.dateTime("2024-03-05 12:00:00+07"))
        assertEquals(noon, ChartValues.dateTime("2024-03-05T12:00:00Z"))
        assertNull(ChartValues.dateTime("12:00:00"))
        assertNull(ChartValues.dateTime("hello world"))
        assertNull(ChartValues.number("NaN"))
    }

    @Test
    fun `text columns holding numbers or dates are profiled as such`() {
        val rows = listOf(listOf("1.5", "2024-01-01", "a"), listOf("2", "2024-01-02", "b"), listOf(null, "2024-01-02", "a"))
        val (number, date, category) = profile("n" to text, "d" to text, "c" to text, rows = rows)
        assertEquals(ColumnKind.Numeric, number.kind)
        assertEquals(1.5, number.min)
        assertEquals(1, number.nulls)
        assertEquals(ColumnKind.Temporal, date.kind)
        assertEquals(2, date.distinct)
        assertEquals(ColumnKind.Category, category.kind)
        assertTrue(number.unique)
        assertTrue(!date.unique)
    }

    @Test
    fun `dates and numbers suggest a line, grouped when dates repeat`() {
        val rows = (1..60).map { listOf("2024-01-${(it % 28 + 1).toString().padStart(2, '0')}", "$it") }
        val profiles = profile("day" to Types.DATE, "total" to Types.NUMERIC, rows = rows)
        val spec = ChartSuggest.suggest(profiles).first()
        assertEquals(ChartType.Line, spec.type)
        assertEquals(TimeBucket.Day, spec.bucket)
        assertEquals(listOf(Measure("total", Aggregate.Sum)), spec.y)
        assertEquals(emptyList(), spec.validate(profiles))
    }

    @Test
    fun `a few categories and a number suggest sorted bars and a pie`() {
        val rows = listOf(listOf("north", "10"), listOf("south", "30"), listOf("east", "20"), listOf("north", "5"))
        val profiles = profile("region" to text, "sales" to Types.INTEGER, rows = rows)
        val suggestions = ChartSuggest.suggest(profiles)
        assertEquals(listOf(ChartType.Bar, ChartType.Pie), suggestions.map { it.type })
        val frame = ChartData.build(rows, profiles, suggestions.first())
        assertEquals(listOf("south", "east", "north"), frame.categories)
        assertEquals(listOf(30.0, 20.0, 15.0), frame.points.map { it.y })
    }

    @Test
    fun `nothing is suggested when no column fits an axis`() {
        val rows = listOf(listOf("a b c"), listOf("d e f"))
        assertEquals(emptyList(), ChartSuggest.suggest(profile("blob" to Types.BLOB, rows = rows)))
    }

    @Test
    fun `buckets round dates down, weeks to Monday`() {
        val time = LocalDateTime.of(2024, 8, 15, 13, 45) // a Thursday
        assertEquals(LocalDateTime.of(2024, 8, 15, 13, 0), ChartData.truncate(time, TimeBucket.Hour))
        assertEquals(LocalDateTime.of(2024, 8, 12, 0, 0), ChartData.truncate(time, TimeBucket.Week))
        assertEquals(LocalDateTime.of(2024, 7, 1, 0, 0), ChartData.truncate(time, TimeBucket.Quarter))
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), ChartData.truncate(time, TimeBucket.Year))
    }

    @Test
    fun `series split the points, aggregates combine them, unusable rows are counted`() {
        val rows =
            listOf(
                listOf("2024-01-01 10:00", "north", "1"),
                listOf("2024-01-01 18:00", "north", "2"),
                listOf("2024-01-01 09:00", "south", "5"),
                listOf("2024-02-03 09:00", "south", "7"),
                listOf("not a date", "south", "7"),
                listOf("2024-02-03 09:00", "south", null),
            )
        val profiles = profile("at" to Types.TIMESTAMP, "region" to text, "amount" to Types.INTEGER, rows = rows)
        val spec = ChartSpec(ChartType.Line, "at", listOf(Measure("amount", Aggregate.Avg)), series = "region", bucket = TimeBucket.Month)
        val frame = ChartData.build(rows, profiles, spec)
        val january = ChartValues.epochMillis(LocalDateTime.of(2024, 1, 1, 0, 0))
        val february = ChartValues.epochMillis(LocalDateTime.of(2024, 2, 1, 0, 0))
        assertEquals(
            setOf(ChartPoint(january, "north", 1.5), ChartPoint(january, "south", 5.0), ChartPoint(february, "south", 7.0)),
            frame.points.toSet(),
        )
        assertEquals(listOf("north", "south"), frame.groups)
        assertEquals(2, frame.skippedRows)
    }

    @Test
    fun `several measures become one group each, and count needs no numbers`() {
        val rows = listOf(listOf("a", "1", "x"), listOf("a", "2", null), listOf("b", "3", "y"))
        val profiles = profile("k" to text, "v" to Types.INTEGER, "note" to text, rows = rows)
        val spec = ChartSpec(ChartType.Bar, "k", listOf(Measure("v", Aggregate.Sum), Measure("note", Aggregate.Count)))
        assertEquals(emptyList(), spec.validate(profiles))
        val frame = ChartData.build(rows, profiles, spec)
        assertEquals(listOf("sum(v)", "count(note)"), frame.groups)
        assertEquals(1.0, frame.points.single { it.x == "a" && it.group == "count(note)" }.y)
        assertEquals(3.0, frame.points.single { it.x == "a" && it.group == "sum(v)" }.y)
    }

    @Test
    fun `series beyond the largest ones are merged into Other`() {
        val rows = (1..15).map { listOf("$it", "s$it", "$it") }
        val profiles = profile("x" to Types.INTEGER, "s" to text, "y" to Types.INTEGER, rows = rows)
        val frame = ChartData.build(rows, profiles, ChartSpec(ChartType.Scatter, "x", listOf(Measure("y")), series = "s"))
        assertEquals(ChartSuggest.MaxSeries, frame.groups.size)
        assertEquals(ChartData.Other, frame.groups.last())
        assertEquals(6, frame.mergedSeries)
    }

    @Test
    fun `invalid specs say what to correct`() {
        val rows = listOf(listOf("a", "1", "2024-01-01"))
        val profiles = profile("name" to text, "n" to Types.INTEGER, "d" to Types.DATE, rows = rows)
        assertEquals(1, ChartSpec(ChartType.Bar, "missing", listOf(Measure("n"))).validate(profiles).size)
        assertTrue(ChartSpec(ChartType.Bar, "name", listOf(Measure("name"))).validate(profiles).single().contains("isn't numeric"))
        assertTrue(ChartSpec(ChartType.Line, "n", listOf(Measure("n", Aggregate.Sum)), bucket = TimeBucket.Day).validate(profiles).single().contains("Only dates"))
        assertTrue(ChartSpec(ChartType.Line, "d", listOf(Measure("n")), bucket = TimeBucket.Day).validate(profiles).single().contains("needs an aggregate"))
        assertTrue(ChartSpec(ChartType.Pie, "d", listOf(Measure("n"))).validate(profiles).single().contains("categories"))
    }

    @Test
    fun `long lines are downsampled keeping the ends and the peak`() {
        val points = (0 until 10_000).map { ChartPoint(it.toDouble(), "g", if (it == 5_000) 1_000.0 else 0.0) }
        val sampled = ChartData.lttb(points, 100)
        assertEquals(100, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
        assertTrue(sampled.any { it.y == 1_000.0 })
    }

    @Test
    fun `long reads stop at the caller's checkpoint`() {
        val rows = (0 until 20_000).map { listOf("$it", "$it") }
        val columns = listOf(ResultColumn("x", "", Types.INTEGER), ResultColumn("y", "", Types.INTEGER))
        var calls = 0
        val stop = { if (++calls > 2) throw IllegalStateException("cancelled") }
        kotlin.test.assertFailsWith<IllegalStateException> { ColumnProfiler.profile(columns, rows, stop) }
        val profiles = ColumnProfiler.profile(columns, rows)
        calls = 0
        kotlin.test.assertFailsWith<IllegalStateException> { ChartData.build(rows, profiles, ChartSpec(ChartType.Scatter, "x", listOf(Measure("y"))), stop) }
        assertEquals(3, calls)
    }
}

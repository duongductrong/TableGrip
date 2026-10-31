package dev.datagrok.core.chart

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * One drawn value. [x] is a [Double] for numbers, epoch milliseconds (UTC wall-clock, see [ChartValues.epochMillis]) for
 * dates, or a [String] for categories; [group] is the series, or the measure when there are several and no series.
 */
data class ChartPoint(val x: Any, val group: String, val y: Double)

/**
 * A spec applied to rows: the points to draw, and what was left out on the way, so the chart can say so rather than
 * look complete.
 */
data class ChartFrame(
    val spec: ChartSpec,
    val xKind: ColumnKind,
    val points: List<ChartPoint>,
    /** Groups in legend order. */
    val groups: List<String>,
    /** Category x values in drawing order; empty for numbers and dates. */
    val categories: List<String>,
    /** Rows with no usable x or y value. */
    val skippedRows: Int,
    /** Categories or series beyond the limits, folded away or merged into [ChartData.Other]. */
    val omittedCategories: Int,
    val mergedSeries: Int,
    /** Points dropped to keep drawing fast; the shape is kept (see [ChartData.lttb]). */
    val downsampled: Boolean,
) {
    val xLabel: String
        get() = spec.bucket?.let { "${spec.x} (${it.label.lowercase()})" } ?: spec.x

    val yLabel: String
        get() = spec.y.singleOrNull()?.label ?: spec.y.joinToString { it.label }
}

/** Turns rows into a [ChartFrame]: parses values, groups by bucket and series, aggregates, orders and thins out. */
object ChartData {
    /** Name of the series all the smaller ones are merged into. */
    const val Other = "Other"
    const val NullLabel = "(null)"

    /** Categories on one axis before the smallest are left out. */
    const val MaxCategories = 50

    /** Points per line before it is downsampled. */
    const val MaxLinePoints = 2000

    /** Points of a scatter plot before it is thinned out. */
    const val MaxScatterPoints = 20_000

    /** [checkpoint] is called every [ColumnProfiler.CheckpointRows] rows, so a caller can stop a long build. */
    fun build(rows: List<List<String?>>, profiles: List<ColumnProfile>, spec: ChartSpec, checkpoint: () -> Unit = {}): ChartFrame {
        val byName = profiles.associateBy { it.name }
        val x = byName.getValue(spec.x)
        val series = spec.series?.let(byName::getValue)
        val measures = spec.y.map { it to byName.getValue(it.column) }

        // Series past the largest few are merged, by the first measure's total.
        val keptSeries = series?.let { keptSeries(rows, it, measures.first().second) }

        var skipped = 0
        val raw = ArrayList<ChartPoint>(rows.size * measures.size)
        for ((i, row) in rows.withIndex()) {
            if (i % ColumnProfiler.CheckpointRows == 0) checkpoint()
            val xValue = xValue(row[x.index], x.kind, spec.bucket)
            if (xValue == null) {
                skipped++
                continue
            }
            val seriesName = series?.let { (row[it.index] ?: NullLabel).let { name -> if (keptSeries == null || name in keptSeries) name else Other } }
            var usable = false
            for ((measure, column) in measures) {
                val value = row[column.index]
                val y =
                    if (measure.aggregate == Aggregate.Count) (if (value == null) 0.0 else 1.0)
                    else ChartValues.number(value) ?: continue
                usable = true
                raw += ChartPoint(xValue, seriesName ?: measure.label, y)
            }
            if (!usable) skipped++
        }
        val mergedSeries = if (keptSeries == null) 0 else rows.mapTo(HashSet()) { it[series.index] ?: NullLabel }.size - keptSeries.size

        val aggregated = if (spec.aggregates) aggregate(raw, measures.map { it.first }, series != null) else raw
        val ordered = order(aggregated, x.kind, spec)
        val (kept, omitted) = limitCategories(ordered, x.kind, spec)
        val groups = kept.map { it.group }.distinct().let { names -> if (Other in names) names - Other + Other else names }
        val (thinned, downsampled) = thin(kept, x.kind, spec.type)
        return ChartFrame(
            spec = spec,
            xKind = x.kind,
            points = thinned,
            groups = groups,
            categories = if (x.kind == ColumnKind.Category) thinned.map { it.x as String }.distinct() else emptyList(),
            skippedRows = skipped,
            omittedCategories = omitted,
            mergedSeries = mergedSeries,
            downsampled = downsampled,
        )
    }

    private fun xValue(text: String?, kind: ColumnKind, bucket: TimeBucket?): Any? =
        when (kind) {
            ColumnKind.Numeric -> ChartValues.number(text)
            ColumnKind.Temporal -> ChartValues.dateTime(text)?.let { ChartValues.epochMillis(if (bucket == null) it else truncate(it, bucket)) }
            ColumnKind.Category -> text ?: NullLabel
            ColumnKind.Other -> null
        }

    fun truncate(time: LocalDateTime, bucket: TimeBucket): LocalDateTime =
        when (bucket) {
            TimeBucket.Hour -> time.truncatedTo(ChronoUnit.HOURS)
            TimeBucket.Day -> time.truncatedTo(ChronoUnit.DAYS)
            TimeBucket.Week -> time.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TimeBucket.Month -> time.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
            TimeBucket.Quarter -> time.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).withMonth((time.monthValue - 1) / 3 * 3 + 1)
            TimeBucket.Year -> time.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1)
        }

    /** The [ChartSuggest.MaxSeries] series with the largest absolute totals, or null when there are no more than that. */
    private fun keptSeries(rows: List<List<String?>>, series: ColumnProfile, measure: ColumnProfile): Set<String>? {
        val totals = HashMap<String, Double>()
        for (row in rows) {
            val name = row[series.index] ?: NullLabel
            totals[name] = (totals[name] ?: 0.0) + abs(ChartValues.number(row[measure.index]) ?: 1.0)
        }
        if (totals.size <= ChartSuggest.MaxSeries) return null
        return totals.entries.sortedByDescending { it.value }.take(ChartSuggest.MaxSeries - 1).mapTo(HashSet()) { it.key }
    }

    /** One point per x and group. Each group belongs to one measure, so the measure's aggregate applies to it. */
    private fun aggregate(points: List<ChartPoint>, measures: List<Measure>, bySeries: Boolean): List<ChartPoint> {
        val aggregateOf: (String) -> Aggregate =
            if (bySeries) { _ -> measures.first().aggregate } else { group -> measures.first { it.label == group }.aggregate }
        val cells = LinkedHashMap<Pair<Any, String>, Accumulator>()
        for (point in points) cells.getOrPut(point.x to point.group) { Accumulator() }.add(point.y)
        return cells.map { (key, cell) -> ChartPoint(key.first, key.second, cell.result(aggregateOf(key.second))) }
    }

    private class Accumulator {
        var count = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        var first = 0.0

        fun add(value: Double) {
            if (count == 0) first = value
            count++
            sum += value
            if (value < min) min = value
            if (value > max) max = value
        }

        fun result(aggregate: Aggregate): Double =
            when (aggregate) {
                // Several rows at one x without an aggregate: the first one's value, as a line can only have one.
                Aggregate.None -> first
                Aggregate.Sum, Aggregate.Count -> sum
                Aggregate.Avg -> sum / count
                Aggregate.Min -> min
                Aggregate.Max -> max
            }
    }

    /** Numbers and dates ascending; bar and pie categories largest first, other categories as they came. */
    private fun order(points: List<ChartPoint>, kind: ColumnKind, spec: ChartSpec): List<ChartPoint> =
        when (kind) {
            ColumnKind.Numeric, ColumnKind.Temporal -> points.sortedBy { it.x as Double }
            else ->
                if (spec.type == ChartType.Bar || spec.type == ChartType.Pie) {
                    val totals = HashMap<Any, Double>()
                    points.forEach { totals[it.x] = (totals[it.x] ?: 0.0) + it.y }
                    points.sortedByDescending { totals[it.x] }
                } else {
                    points
                }
        }

    /** Keeps the first [MaxCategories] categories, which [order] put largest first for bars and pies. */
    private fun limitCategories(points: List<ChartPoint>, kind: ColumnKind, spec: ChartSpec): Pair<List<ChartPoint>, Int> {
        if (kind != ColumnKind.Category) return points to 0
        val categories = points.map { it.x }.distinct()
        val limit = if (spec.type == ChartType.Pie) ChartSuggest.MaxSlices else MaxCategories
        if (categories.size <= limit) return points to 0
        val kept = categories.take(limit).toHashSet()
        return points.filter { it.x in kept } to categories.size - limit
    }

    private fun thin(points: List<ChartPoint>, kind: ColumnKind, type: ChartType): Pair<List<ChartPoint>, Boolean> {
        if (kind == ColumnKind.Category) return points to false
        return when (type) {
            ChartType.Line, ChartType.Area -> {
                val groups = points.groupBy { it.group }
                if (groups.values.none { it.size > MaxLinePoints }) points to false
                else groups.values.flatMap { lttb(it, MaxLinePoints) } to true
            }
            ChartType.Scatter ->
                if (points.size <= MaxScatterPoints) points to false
                else {
                    val step = points.size.toDouble() / MaxScatterPoints
                    List(MaxScatterPoints) { points[(it * step).toInt()] } to true
                }
            else -> points to false
        }
    }

    /**
     * Largest-Triangle-Three-Buckets: keeps [threshold] of [points] (sorted by numeric x) that best preserve the line's
     * shape, always including the first and last.
     */
    fun lttb(points: List<ChartPoint>, threshold: Int): List<ChartPoint> {
        if (threshold >= points.size || threshold < 3) return points
        val sampled = ArrayList<ChartPoint>(threshold)
        val every = (points.size - 2).toDouble() / (threshold - 2)
        var a = 0
        sampled += points[0]
        for (i in 0 until threshold - 2) {
            val nextStart = ((i + 1) * every).toInt() + 1
            val nextEnd = minOf(((i + 2) * every).toInt() + 1, points.size)
            var avgX = 0.0
            var avgY = 0.0
            for (j in nextStart until nextEnd) {
                avgX += points[j].x as Double
                avgY += points[j].y
            }
            val count = (nextEnd - nextStart).coerceAtLeast(1)
            avgX /= count
            avgY /= count

            val start = (i * every).toInt() + 1
            val end = ((i + 1) * every).toInt() + 1
            val ax = points[a].x as Double
            val ay = points[a].y
            var maxArea = -1.0
            var chosen = start
            for (j in start until end) {
                val area = abs((ax - avgX) * (points[j].y - ay) - (ax - (points[j].x as Double)) * (avgY - ay))
                if (area > maxArea) {
                    maxArea = area
                    chosen = j
                }
            }
            sampled += points[chosen]
            a = chosen
        }
        sampled += points.last()
        return sampled
    }
}

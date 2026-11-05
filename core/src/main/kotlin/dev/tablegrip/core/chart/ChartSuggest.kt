package dev.tablegrip.core.chart

/**
 * Picks charts for a result from its columns' kinds alone, without an agent: dates and numbers make a line, a few
 * categories and a number make bars, two numbers a scatter. Names are never read, only kinds and value counts.
 */
object ChartSuggest {
    /** More categories than this make unreadable bars. */
    const val MaxCategories = 30

    /** Few enough slices for a pie. */
    const val MaxSlices = 8

    /** Few enough values to split into series. */
    const val MaxSeries = 10

    /** Points on a line before dates are grouped rather than drawn one per row. */
    private const val MaxLinePoints = 1000

    /** Charts that fit [profiles], the most fitting first; empty when none does. */
    fun suggest(profiles: List<ColumnProfile>): List<ChartSpec> {
        val temporal = profiles.filter { it.kind == ColumnKind.Temporal && it.distinct > 0 }
        val numeric = profiles.filter { it.kind == ColumnKind.Numeric && it.distinct > 0 }
        val categories = profiles.filter { it.kind == ColumnKind.Category && it.distinct in 1..MaxCategories }
        return buildList {
            temporal.firstOrNull()?.let { time ->
                val measures = numeric.take(3)
                if (measures.isNotEmpty()) add(overTime(time, measures, categories.firstOrNull { it.distinct <= MaxSeries }))
            }
            categories.firstOrNull()?.let { category ->
                val measure = numeric.firstOrNull()
                val series = categories.drop(1).firstOrNull { it.distinct <= MaxSeries }
                if (measure != null) {
                    val aggregate = if (category.unique && series == null) Aggregate.None else Aggregate.Sum
                    add(ChartSpec(ChartType.Bar, category.name, listOf(Measure(measure.name, aggregate)), series = series?.name, horizontal = true))
                    if (category.distinct <= MaxSlices && (measure.min ?: -1.0) >= 0.0) {
                        add(ChartSpec(ChartType.Pie, category.name, listOf(Measure(measure.name, aggregate))))
                    }
                } else {
                    add(ChartSpec(ChartType.Bar, category.name, listOf(Measure(category.name, Aggregate.Count)), series = series?.name, horizontal = true))
                }
            }
            if (numeric.size >= 2) {
                val series = categories.firstOrNull { it.distinct <= MaxSeries }
                add(ChartSpec(ChartType.Scatter, numeric[0].name, listOf(Measure(numeric[1].name)), series = series?.name))
            }
        }
    }

    /**
     * A line over [time]; dates that repeat, or too many of them, are grouped into buckets sized to the span so the
     * line keeps a few dozen to a few hundred points.
     */
    private fun overTime(time: ColumnProfile, measures: List<ColumnProfile>, category: ColumnProfile?): ChartSpec {
        val series = category?.takeIf { measures.size == 1 }
        val grouped = !time.unique || series != null || time.distinct >= MaxLinePoints
        val bucket = if (grouped) bucketFor(time) else null
        val aggregate = if (grouped) Aggregate.Sum else Aggregate.None
        return ChartSpec(ChartType.Line, time.name, measures.map { Measure(it.name, aggregate) }, series = series?.name, bucket = bucket)
    }

    /** The bucket giving roughly 20–400 points over the column's span. */
    fun bucketFor(time: ColumnProfile): TimeBucket {
        val span = (time.max ?: 0.0) - (time.min ?: 0.0)
        val days = span / DayMillis
        return when {
            days <= 3 -> TimeBucket.Hour
            days <= 180 -> TimeBucket.Day
            days <= 730 -> TimeBucket.Week
            days <= 365 * 15 -> TimeBucket.Month
            else -> TimeBucket.Year
        }
    }

    private const val DayMillis = 86_400_000.0
}

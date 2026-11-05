package dev.tablegrip.core.chart

enum class ChartType(val label: String) {
    Line("Line"),
    Area("Area"),
    Bar("Bar"),
    Scatter("Scatter"),
    Pie("Pie");

    companion object {
        /** By [name] or [label], ignoring case; null for anything else. */
        fun of(text: String): ChartType? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true) }
    }
}

/** How a measure's values are combined per x (and series). [None] plots each row's value as it is. */
enum class Aggregate(val label: String, val sql: String?) {
    None("Value", null),
    Sum("Sum", "SUM"),
    Avg("Average", "AVG"),
    Min("Min", "MIN"),
    Max("Max", "MAX"),
    Count("Count", "COUNT");

    companion object {
        fun of(text: String): Aggregate? =
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) || it.label.equals(text, ignoreCase = true) } ?: if (text.equals("mean", ignoreCase = true)) Avg else null
    }
}

/** Where dates on the x axis are rounded down to before they are grouped. Weeks start on Monday. */
enum class TimeBucket(val label: String) {
    Hour("Hour"),
    Day("Day"),
    Week("Week"),
    Month("Month"),
    Quarter("Quarter"),
    Year("Year");

    companion object {
        fun of(text: String): TimeBucket? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

data class Measure(val column: String, val aggregate: Aggregate = Aggregate.None) {
    /** How the measure is named in legends and in server-side aggregates: `total`, `sum(total)`. */
    val label: String
        get() = if (aggregate == Aggregate.None) column else "${aggregate.name.lowercase()}($column)"
}

/**
 * What to draw from a result: columns by name, so a spec outlives the result it was made for and applies again when the
 * statement is re-run. Made by [ChartSuggest], edited by the user, or proposed by an agent; always [validate]d first.
 */
data class ChartSpec(
    val type: ChartType,
    val x: String,
    val y: List<Measure>,
    val series: String? = null,
    val bucket: TimeBucket? = null,
    /** Bars along the y axis, which keeps long category names readable. */
    val horizontal: Boolean = false,
) {
    /** Whether rows are grouped before drawing, rather than drawn one point per row. */
    val aggregates: Boolean
        get() = bucket != null || y.any { it.aggregate != Aggregate.None }

    /** Every column the spec reads. */
    val columns: Set<String>
        get() = buildSet {
            add(x)
            y.forEach { add(it.column) }
            series?.let(::add)
        }

    /**
     * What makes this spec unusable with [profiles], each specific enough to correct (they are also sent back to an
     * agent that proposed the spec); empty when it can be drawn.
     */
    fun validate(profiles: List<ColumnProfile>): List<String> = buildList {
        val byName = profiles.associateBy { it.name }
        fun known(name: String, role: String): ColumnProfile? =
            byName[name] ?: null.also { add("There is no column \"$name\" for $role; the columns are: ${profiles.joinToString { it.name }}.") }

        val xColumn = known(x, "x")
        if (y.isEmpty()) add("Choose at least one measure for y.")
        if (y.size > MaxMeasures) add("At most $MaxMeasures measures; there are ${y.size}.")
        for (measure in y) {
            val column = known(measure.column, "y") ?: continue
            if (measure.aggregate != Aggregate.Count && column.kind != ColumnKind.Numeric) {
                add("\"${measure.column}\" isn't numeric (${column.kind.name.lowercase()}); count it or pick a numeric column.")
            }
        }
        series?.let { name ->
            val column = known(name, "series") ?: return@let
            if (name == x) add("The series column can't also be x.")
            if (column.kind == ColumnKind.Other) add("\"$name\" can't split series: its values can't be compared.")
            if (y.size > 1) add("Split by series with one measure only.")
        }
        if (xColumn != null) {
            if (xColumn.kind == ColumnKind.Other) add("\"$x\" can't be plotted on x: its values are neither numbers, dates nor categories.")
            if (bucket != null && xColumn.kind != ColumnKind.Temporal) add("Only dates can be grouped by ${bucket.label.lowercase()}; \"$x\" is ${xColumn.kind.name.lowercase()}.")
            if (bucket != null && y.any { it.aggregate == Aggregate.None }) add("Grouping by ${bucket.label.lowercase()} needs an aggregate (sum, average, count…) for every measure.")
            when (type) {
                ChartType.Scatter -> {
                    if (xColumn.kind != ColumnKind.Numeric && xColumn.kind != ColumnKind.Temporal) add("A scatter plot needs numbers or dates on x.")
                }
                ChartType.Pie -> {
                    if (xColumn.kind != ColumnKind.Category) add("A pie needs categories for its slices; \"$x\" is ${xColumn.kind.name.lowercase()}.")
                    if (y.size > 1 || series != null) add("A pie shows one measure, without series.")
                }
                ChartType.Line, ChartType.Area, ChartType.Bar -> Unit
            }
        }
    }

    /**
     * This spec after one field was edited, with the others brought in line where there is one obvious way: a bucket
     * only for dates and only with aggregates, a pie with one measure and no series. What can't be fixed that way is
     * left for [validate] to report.
     */
    fun adjusted(profiles: List<ColumnProfile>): ChartSpec {
        val xKind = profiles.firstOrNull { it.name == x }?.kind
        val bucket = bucket.takeIf { xKind == ColumnKind.Temporal && type != ChartType.Scatter }
        var y = if (bucket != null) y.map { if (it.aggregate == Aggregate.None) it.copy(aggregate = Aggregate.Sum) else it } else y
        var series = series.takeIf { it != x }
        if (type == ChartType.Pie) {
            y = y.take(1)
            series = null
        } else if (series != null) {
            y = y.take(1)
        }
        return copy(y = y, series = series, bucket = bucket)
    }

    companion object {
        const val MaxMeasures = 5
    }
}

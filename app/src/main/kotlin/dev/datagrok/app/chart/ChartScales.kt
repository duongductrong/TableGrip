package dev.datagrok.app.chart

import dev.datagrok.core.chart.TimeBucket
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** A continuous axis: its domain, widened to round ticks, and those ticks with their labels. */
data class Axis(val min: Double, val max: Double, val ticks: List<Pair<Double, String>>) {
    fun fraction(value: Double): Double = if (max == min) 0.5 else (value - min) / (max - min)
}

/** Round axis ticks for numbers (steps of 1, 2 or 5 × 10ⁿ) and for dates (whole hours, days, months, years). */
object ChartScales {
    /** [min]..[max] covered by about [count] round ticks; [zero] makes the axis start (or end) at 0, as bars and areas need. */
    fun numeric(min: Double, max: Double, count: Int = 5, zero: Boolean = false): Axis {
        var low = if (zero) minOf(min, 0.0) else min
        var high = if (zero) maxOf(max, 0.0) else max
        if (low == high) {
            val pad = if (low == 0.0) 1.0 else abs(low) * 0.1
            low -= pad
            high += pad
            if (zero && min >= 0) low = 0.0
        }
        val step = niceStep((high - low) / count.coerceAtLeast(1))
        val first = floor(low / step) * step
        val last = ceil(high / step) * step
        val decimals = max(0, -floor(log10(step)).toInt())
        val ticks = generateSequence(first) { it + step }.takeWhile { it <= last + step / 2 }.map { value -> value to number(value, decimals) }.toList()
        return Axis(first, last, ticks)
    }

    fun niceStep(rough: Double): Double {
        if (rough <= 0 || !rough.isFinite()) return 1.0
        val magnitude = 10.0.pow(floor(log10(rough)))
        val fraction = rough / magnitude
        val nice = when {
            // The nearest of 1, 2, 5, 10 (geometrically), so the tick count stays close to what was asked.
            fraction < 1.5 -> 1.0
            fraction < 3 -> 2.0
            fraction < 7 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /**
     * Ticks for epoch milliseconds [min]..[max] (UTC wall-clock, see `ChartValues.epochMillis`): the coarsest calendar
     * unit giving at least a few ticks, labelled at that unit. The domain stays the data's own: dates aren't padded.
     */
    fun temporal(min: Double, max: Double, count: Int = 6): Axis {
        val start = time(min)
        val end = time(max)
        val span = max - min
        val unit = TimeUnits.firstOrNull { span / it.approxMillis <= count } ?: TimeUnits.last()
        val ticks = mutableListOf<Pair<Double, String>>()
        var tick = unit.floor(start)
        if (tick.isBefore(start)) tick = unit.next(tick)
        var previous: LocalDateTime? = null
        while (!tick.isAfter(end) && ticks.size < 100) {
            ticks += millis(tick) to unit.label(tick, previous)
            previous = tick
            tick = unit.next(tick)
        }
        val (low, high) = if (min == max) (min - DayMillis) to (max + DayMillis) else min to max
        return Axis(low, high, ticks)
    }

    private class TimeUnit(
        val approxMillis: Double,
        val floor: (LocalDateTime) -> LocalDateTime,
        val next: (LocalDateTime) -> LocalDateTime,
        /** The label, shorter when [previous] tick shares the larger unit (the year, the day). */
        val label: (LocalDateTime, LocalDateTime?) -> String,
    )

    private val Hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val MonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val MonthDayHm = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)
    private val Month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    private val MonthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    private fun hours(step: Int) =
        TimeUnit(step * HourMillis, { it.truncatedTo(ChronoUnit.HOURS).withHour(it.hour / step * step) }, { it.plusHours(step.toLong()) }) { t, p ->
            if (p == null || p.toLocalDate() != t.toLocalDate()) MonthDayHm.format(t) else Hm.format(t)
        }

    /** Steps of a week or more start on Mondays, like the week bucket. */
    private fun days(step: Int) =
        TimeUnit(step * DayMillis, { t ->
            val day = t.truncatedTo(ChronoUnit.DAYS)
            if (step >= 7) day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else day
        }, { it.plusDays(step.toLong()) }) { t, p -> if (p == null || p.year != t.year) "${MonthDay.format(t)}, ${t.year}" else MonthDay.format(t) }

    private fun months(step: Int) =
        TimeUnit(step * 30.4 * DayMillis, { it.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).withMonth((it.monthValue - 1) / step * step + 1) }, { it.plusMonths(step.toLong()) }) { t, p ->
            if (p == null || p.year != t.year) MonthYear.format(t) else Month.format(t)
        }

    private fun years(step: Int) =
        TimeUnit(step * 365.25 * DayMillis, { it.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1).withYear(it.year / step * step) }, { it.plusYears(step.toLong()) }) { t, _ -> "${t.year}" }

    private val TimeUnits =
        listOf(hours(1), hours(3), hours(6), hours(12), days(1), days(2), days(7), days(14), months(1), months(3), months(6), years(1), years(2), years(5), years(10), years(25), years(50), years(100))

    /** A value for tooltips: grouped thousands, up to two decimals, no trailing zeros. */
    fun value(value: Double): String = if (abs(value) >= 1e15 || (value != 0.0 && abs(value) < 1e-4)) "%.3g".format(Locale.ENGLISH, value) else ValueFormat.get().format(value)

    /** A date for tooltips, as precise as [bucket] (or as the value, without one). */
    fun date(millis: Double, bucket: TimeBucket?): String {
        val time = time(millis)
        return when (bucket) {
            TimeBucket.Year -> "${time.year}"
            TimeBucket.Quarter -> "Q${(time.monthValue - 1) / 3 + 1} ${time.year}"
            TimeBucket.Month -> MonthYear.format(time)
            TimeBucket.Week -> "Week of ${IsoDate.format(time)}"
            TimeBucket.Day -> IsoDate.format(time)
            TimeBucket.Hour, null -> if (time.toLocalTime() == LocalTime.MIDNIGHT) IsoDate.format(time) else IsoMinute.format(time)
        }
    }

    private val IsoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val IsoMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun number(value: Double, decimals: Int): String {
        val clean = if (abs(value) < 1e-12) 0.0 else value
        if (abs(clean) >= 1e15) return "%.2g".format(Locale.ENGLISH, clean)
        return DecimalFormat("#,##0" + (if (decimals > 0) "." + "0".repeat(decimals.coerceAtMost(10)) else ""), DecimalFormatSymbols(Locale.ENGLISH)).format(clean)
    }

    private val ValueFormat = ThreadLocal.withInitial { DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.ENGLISH)) }

    private fun time(millis: Double): LocalDateTime = LocalDateTime.ofEpochSecond(Math.floorDiv(millis.toLong(), 1000L), (Math.floorMod(millis.toLong(), 1000L) * 1_000_000).toInt(), ZoneOffset.UTC)

    private fun millis(time: LocalDateTime): Double = time.toInstant(ZoneOffset.UTC).toEpochMilli().toDouble()

    private const val HourMillis = 3_600_000.0
    private const val DayMillis = 86_400_000.0
}

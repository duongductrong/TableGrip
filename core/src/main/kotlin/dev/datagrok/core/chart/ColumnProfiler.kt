package dev.datagrok.core.chart

import dev.datagrok.core.driver.ResultColumn
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class ColumnKind {
    Numeric,
    /** Dates and timestamps; times of day alone are [Other]. */
    Temporal,
    /** Text and booleans, compared for equality only. */
    Category,
    /** Binary, and whatever else can't be put on an axis. */
    Other,
}

/**
 * One column of a result as the chart sees it. [distinct] stops counting past [ColumnProfiler.DistinctCap]; [min] and
 * [max] are numbers, or epoch milliseconds for dates, and null for other kinds or when no value parses.
 */
data class ColumnProfile(
    val index: Int,
    val name: String,
    val typeName: String,
    val kind: ColumnKind,
    val distinct: Int,
    val nulls: Int,
    val rows: Int,
    val min: Double? = null,
    val max: Double? = null,
) {
    /** Every non-null value differs, so there is nothing to group. */
    val unique: Boolean
        get() = distinct == rows - nulls && distinct < ColumnProfiler.DistinctCap
}

/** Reads result values, which drivers hand over as text, as numbers and dates. */
object ChartValues {
    fun number(text: String?): Double? = text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * A date or timestamp as its wall-clock time, ignoring any offset: a chart shows times as the database wrote them.
     * Accepts ISO forms with a space or `T`, fractions, and offsets like `Z`, `+07` or `+07:00`.
     */
    fun dateTime(text: String?): LocalDateTime? {
        val value = text?.trim()?.takeIf { it.length >= 10 && it[4] == '-' } ?: return null
        if (value.length == 10) return runCatching { LocalDate.parse(value).atStartOfDay() }.getOrNull()
        val iso = value.replaceFirst(' ', 'T').replace(" ", "")
        runCatching { return LocalDateTime.parse(iso) }
        // PostgreSQL writes offsets as +07, which java.time only reads as +07:00.
        val offset = if (ShortOffset.containsMatchIn(iso)) "$iso:00" else iso
        return runCatching { OffsetDateTime.parse(offset).toLocalDateTime() }.getOrNull()
    }

    /** [dateTime] as epoch milliseconds of the same wall-clock time in UTC, which is how the chart lays dates out. */
    fun epochMillis(text: String?): Double? = dateTime(text)?.let { it.toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() }

    fun epochMillis(time: LocalDateTime): Double = time.toInstant(ZoneOffset.UTC).toEpochMilli().toDouble()

    private val ShortOffset = Regex("[+-]\\d{2}$")
}

/** Works out what each column of a result holds, from its JDBC type first and, for text, from its values. */
object ColumnProfiler {
    /** Distinct values are counted up to here: more than this is "many" for every purpose a chart has. */
    const val DistinctCap = 1000

    /** Text columns are sampled to tell numbers and dates stored as text. */
    private const val Sample = 200

    /** Share of sampled values that must parse for text to count as numbers or dates. */
    private const val Parses = 0.95

    /** [checkpoint] is called every [CheckpointRows] rows, so a caller can stop a long read (e.g. by throwing on cancel). */
    fun profile(columns: List<ResultColumn>, rows: List<List<String?>>, checkpoint: () -> Unit = {}): List<ColumnProfile> =
        columns.mapIndexed { index, column -> profile(index, column, rows, checkpoint) }

    /** How often long loops over rows call their checkpoint. */
    const val CheckpointRows = 4096

    private fun profile(index: Int, column: ResultColumn, rows: List<List<String?>>, checkpoint: () -> Unit): ColumnProfile {
        val kind = kindOf(column) ?: guess(rows.asSequence().mapNotNull { it[index] }.take(Sample).toList())
        val distinct = HashSet<String>()
        var nulls = 0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for ((i, row) in rows.withIndex()) {
            if (i % CheckpointRows == 0) checkpoint()
            val value = row[index]
            if (value == null) {
                nulls++
                continue
            }
            if (distinct.size < DistinctCap) distinct += value
            val number =
                when (kind) {
                    ColumnKind.Numeric -> ChartValues.number(value)
                    ColumnKind.Temporal -> ChartValues.epochMillis(value)
                    else -> null
                } ?: continue
            if (number < min) min = number
            if (number > max) max = number
        }
        return ColumnProfile(
            index = index,
            name = column.name,
            typeName = column.typeName,
            kind = kind,
            distinct = distinct.size,
            nulls = nulls,
            rows = rows.size,
            min = min.takeIf { it.isFinite() },
            max = max.takeIf { it.isFinite() },
        )
    }

    /** The kind the JDBC type settles; null for text, which [guess] decides from the values. */
    private fun kindOf(column: ResultColumn): ColumnKind? =
        when {
            column.isNumeric -> ColumnKind.Numeric
            column.isBinary -> ColumnKind.Other
            else ->
                when (column.jdbcType) {
                    Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ColumnKind.Temporal
                    Types.BIT, Types.BOOLEAN -> ColumnKind.Category
                    Types.TIME, Types.TIME_WITH_TIMEZONE, Types.ARRAY, Types.STRUCT, Types.JAVA_OBJECT, Types.CLOB, Types.NCLOB, Types.SQLXML -> ColumnKind.Other
                    else -> null
                }
        }

    private fun guess(sample: List<String>): ColumnKind {
        if (sample.isEmpty()) return ColumnKind.Category
        val needed = (sample.size * Parses).toInt().coerceAtLeast(1)
        return when {
            sample.count { ChartValues.number(it) != null } >= needed -> ColumnKind.Numeric
            sample.count { ChartValues.dateTime(it) != null } >= needed -> ColumnKind.Temporal
            else -> ColumnKind.Category
        }
    }
}

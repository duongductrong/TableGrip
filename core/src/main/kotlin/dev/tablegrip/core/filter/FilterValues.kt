package dev.tablegrip.core.filter

import dev.tablegrip.core.i18n.tr
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A value the user typed that can't be used for its column; [message] says why, in words for the UI. */
class FilterValueException(message: String) : IllegalArgumentException(message)

/** A point in time typed into a filter: a whole day, an exact moment, or a time of day. */
sealed interface TemporalValue {
    data class Day(val date: LocalDate) : TemporalValue

    data class Moment(val dateTime: LocalDateTime) : TemporalValue

    data class TimeOfDay(val time: LocalTime) : TemporalValue
}

/** Parses what users type into filter value fields. */
object FilterValues {
    /**
     * Splits a list typed as `a, b, "c, d"` (commas or new lines; single or double quotes keep separators and spaces).
     * An unquoted `NULL` comes back as null.
     */
    fun parseList(text: String): List<String?> {
        val items = mutableListOf<String?>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++
            if (i >= text.length) break
            val quote = text[i]
            if (quote == '"' || quote == '\'') {
                val value = StringBuilder()
                i++
                while (i < text.length) {
                    if (text[i] == quote) {
                        if (text.getOrNull(i + 1) == quote) value.append(quote).also { i += 2 } else break
                    } else {
                        value.append(text[i++])
                    }
                }
                i++ // closing quote
                items += value.toString()
                while (i < text.length && text[i] != ',' && text[i] != '\n') i++
            } else {
                val start = i
                while (i < text.length && text[i] != ',' && text[i] != '\n') i++
                val value = text.substring(start, i).trim()
                items += if (value.equals("null", ignoreCase = true)) null else value
            }
        }
        return items
    }

    fun number(text: String): BigDecimal {
        val cleaned = text.trim().replace("_", "").replace(" ", "")
        return cleaned.toBigDecimalOrNull() ?: throw FilterValueException(if (cleaned.isEmpty()) tr("Enter a number") else tr("“{0}” isn't a number", text))
    }

    private val UuidPattern = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")

    fun uuid(text: String): UUID {
        val trimmed = text.trim().removePrefix("{").removeSuffix("}")
        if (!UuidPattern.matches(trimmed)) throw FilterValueException(tr("“{0}” isn't a UUID", text))
        val hex = trimmed.replace("-", "")
        return UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }

    /** `true`/`false` as the grid shows them on any engine: `t`, `1`, `yes`… */
    fun boolean(text: String): Boolean? =
        when (text.trim().lowercase()) {
            "true", "t", "1", "yes", "y", "on" -> true
            "false", "f", "0", "no", "n", "off" -> false
            else -> null
        }

    private val Offset = Regex("""([+-])\s*(\d+)\s*([a-z]+)""")
    private val Ago = Regex("""(\d+)\s*([a-z]+)\s+ago""")
    private val Anchor = Regex("""^(now|today|yesterday|tomorrow)""")
    private val TrailingOffset = Regex("""(?:z|[+-]\d{2}(?::?\d{2})?)$""", RegexOption.IGNORE_CASE)

    /**
     * Parses a date, date-time or time: ISO forms as the grid shows them (`2026-10-28`, `2026-10-28 13:45:00.5+07`,
     * `13:45`), or relative to [now]: `today`, `yesterday`, `now`, `today - 7d`, `now-2h`, `-1w`, `3 months ago`.
     * Units: s, min, h, d, w, mo, y (or their names).
     */
    fun temporal(text: String, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): TemporalValue {
        val input = text.trim().lowercase()
        if (input.isEmpty()) throw FilterValueException(tr("Enter a date"))
        relative(input, now)?.let { return it }
        return absolute(input, zone) ?: throw FilterValueException(tr("“{0}” isn't a date or time; try 2026-10-28, 13:45 or today-7d", text.trim()))
    }

    private fun relative(input: String, now: LocalDateTime): TemporalValue? {
        Ago.matchEntire(input)?.let { match ->
            val unit = unit(match.groupValues[2])
            val amount = match.groupValues[1].toLong()
            return shift(if (unit.isDateBased) TemporalValue.Day(now.toLocalDate()) else TemporalValue.Moment(now), -amount, unit)
        }
        val anchor = Anchor.find(input)?.value
        var rest = input.removePrefix(anchor.orEmpty()).trim()
        if (anchor == null && !rest.startsWith("+") && !rest.startsWith("-")) return null
        val offsets = mutableListOf<Pair<Long, ChronoUnit>>()
        while (rest.isNotEmpty()) {
            val match = Offset.matchAt(rest, 0) ?: return null
            val amount = match.groupValues[2].toLong()
            offsets += (if (match.groupValues[1] == "-") -amount else amount) to unit(match.groupValues[3])
            rest = rest.substring(match.range.last + 1).trim()
        }
        val today = now.toLocalDate()
        var value: TemporalValue =
            when (anchor) {
                "now" -> TemporalValue.Moment(now)
                "yesterday" -> TemporalValue.Day(today.minusDays(1))
                "tomorrow" -> TemporalValue.Day(today.plusDays(1))
                "today" -> TemporalValue.Day(today)
                // A bare offset counts from now when it's finer than a day, from today otherwise.
                else -> if (offsets.all { it.second.isDateBased }) TemporalValue.Day(today) else TemporalValue.Moment(now)
            }
        for ((amount, unit) in offsets) value = shift(value, amount, unit)
        return value
    }

    private fun shift(value: TemporalValue, amount: Long, unit: ChronoUnit): TemporalValue =
        when (value) {
            is TemporalValue.Day ->
                if (unit.isDateBased) TemporalValue.Day(value.date.plus(amount, unit))
                else TemporalValue.Moment(value.date.atStartOfDay().plus(amount, unit))
            is TemporalValue.Moment -> TemporalValue.Moment(value.dateTime.plus(amount, unit))
            is TemporalValue.TimeOfDay -> TemporalValue.TimeOfDay(value.time.plus(amount, unit))
        }

    private fun unit(name: String): ChronoUnit =
        when (name) {
            "s", "sec", "secs", "second", "seconds" -> ChronoUnit.SECONDS
            "min", "mins", "minute", "minutes" -> ChronoUnit.MINUTES
            "h", "hr", "hrs", "hour", "hours" -> ChronoUnit.HOURS
            "d", "day", "days" -> ChronoUnit.DAYS
            "w", "wk", "week", "weeks" -> ChronoUnit.WEEKS
            "mo", "mon", "month", "months" -> ChronoUnit.MONTHS
            "y", "yr", "year", "years" -> ChronoUnit.YEARS
            else -> throw FilterValueException(tr("Unknown unit “{0}”; use s, min, h, d, w, mo or y", name))
        }

    private fun absolute(input: String, zone: ZoneId): TemporalValue? {
        val normalized = input.replace('/', '-').replace('t', ' ').trim()
        val offset = TrailingOffset.find(normalized)?.takeIf { normalized.length > 10 || ':' in normalized }
        val local = offset?.let { normalized.substring(0, it.range.first).trim() } ?: normalized
        val zoneOffset = offset?.let { runCatching { ZoneOffset.of(it.value.uppercase().let { v -> if (v.length == 5 && ':' !in v) v.substring(0, 3) + ":" + v.substring(3) else v }) }.getOrNull() }
        return try {
            when {
                local.length <= 10 && '-' in local -> TemporalValue.Day(LocalDate.parse(local, DateTimeFormatter.ISO_LOCAL_DATE))
                '-' in local -> {
                    val dateTime = LocalDateTime.parse(local.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    TemporalValue.Moment(
                        if (zoneOffset == null) dateTime else OffsetDateTime.of(dateTime, zoneOffset).atZoneSameInstant(zone).toLocalDateTime()
                    )
                }
                else -> TemporalValue.TimeOfDay(LocalTime.parse(local, DateTimeFormatter.ISO_LOCAL_TIME))
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val DateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * A value as SQL text: dates as `yyyy-MM-dd`, date-times with a space rather than ISO's `T` so they compare
     * correctly with text stored by SQLite, plus fractional seconds when there are any.
     */
    fun format(value: Any): String =
        when (value) {
            is LocalDate -> value.toString()
            is LocalDateTime -> DateTimeFormat.format(value) + fraction(value.nano)
            is LocalTime -> TimeFormat.format(value) + fraction(value.nano)
            is BigDecimal -> value.toPlainString()
            else -> value.toString()
        }

    private fun fraction(nanos: Int): String = if (nanos == 0) "" else "." + "%09d".format(nanos).trimEnd('0')
}

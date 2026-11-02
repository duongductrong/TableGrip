package dev.tablegrip.core.edit

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.filter.FilterValueException
import dev.tablegrip.core.filter.FilterValues
import dev.tablegrip.core.filter.TemporalValue
import dev.tablegrip.core.filter.ValueKind
import dev.tablegrip.core.metadata.ColumnInfo
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What kind of input a column takes when its cells are edited; finer than [ValueKind] for numbers. */
enum class EditKind {
    Text,
    Integer,
    Decimal,
    Float,
    Boolean,
    Date,
    Time,
    DateTime,
    Uuid,
    Json,
    Binary,
    /** Enums, arrays, network addresses, geometry…: sent as typed, the server parses them. */
    Other;

    val isTemporal: kotlin.Boolean
        get() = this == Date || this == Time || this == DateTime

    val isNumeric: kotlin.Boolean
        get() = this == Integer || this == Decimal || this == Float
}

/** How an engine spells booleans it reads back: `true`/`false` or `1`/`0`. */
enum class BooleanStyle(val yes: String, val no: String) {
    Words("true", "false"),
    Digits("1", "0"),
}

/** Outcome of checking what a user typed into a cell. */
sealed interface EditCheck {
    /** [value] is what to store; [note] is shown next to the editor, as a warning when [warning]. */
    data class Ok(val value: String, val note: String? = null, val warning: Boolean = false) : EditCheck

    /** Nothing typed into a column that isn't text: NULL, or the default in a new row. */
    data object Empty : EditCheck

    data class Invalid(val message: String) : EditCheck
}

/**
 * Everything cell editors need to know about a column: the input it takes, its limits, whether it may be NULL and what
 * a new row gets when it's left out. [check] turns typed text into the text the server parses for the column.
 */
data class ColumnEdit(
    val kind: EditKind,
    val typeName: String,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    /** Auto-increment or identity: new rows get a value from the database. */
    val generated: Boolean = false,
    /** Characters a `varchar(n)` or `char(n)` takes. */
    val maxLength: Int? = null,
    /** Whether the engine holds values to their declared type; SQLite only declares lengths and integer sizes. */
    val strictTypes: Boolean = true,
    /** Total digits of an exact numeric. */
    val precision: Int? = null,
    /** Digits after the decimal point of an exact numeric, or of fractional seconds. */
    val scale: Int? = null,
    val unsigned: Boolean = false,
    val withTimeZone: Boolean = false,
    val booleanStyle: BooleanStyle = BooleanStyle.Words,
    /** Whether `UPDATE … SET column = DEFAULT` works on this engine. */
    val supportsDefault: Boolean = true,
) {
    /** Whether a new row may leave the column out. */
    val hasDefault: Boolean
        get() = generated || defaultValue != null || nullable

    /** What a new row shows for a column it leaves out. */
    val placeholder: String
        get() =
            when {
                generated -> "<auto>"
                defaultValue != null -> "<default: ${defaultValue.take(40)}${if (defaultValue.length > 40) "…" else ""}>"
                !nullable -> "<required>"
                else -> "<default>"
            }

    /** Checks [text] typed into a cell; relative dates like `today-7d` resolve against [now]. */
    fun check(text: String, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): EditCheck {
        if (kind == EditKind.Text || kind == EditKind.Other || kind == EditKind.Binary) return checkText(text)
        val input = text.trim()
        if (input.isEmpty()) return EditCheck.Empty
        return try {
            when (kind) {
                EditKind.Integer -> integer(input)
                EditKind.Decimal -> decimal(input)
                EditKind.Float -> float(input)
                EditKind.Boolean ->
                    FilterValues.boolean(input)?.let { EditCheck.Ok(if (it) booleanStyle.yes else booleanStyle.no) }
                        ?: EditCheck.Invalid("“$input” isn't true or false")
                EditKind.Date, EditKind.Time, EditKind.DateTime -> temporal(input, now, zone)
                EditKind.Uuid -> EditCheck.Ok(FilterValues.uuid(input).toString())
                EditKind.Json -> json(text)
                else -> EditCheck.Ok(text)
            }
        } catch (e: FilterValueException) {
            EditCheck.Invalid(e.message.orEmpty())
        }
    }

    /** Whether two stored values mean the same, e.g. `t` and `true`, or `1.50` and `1.5`. */
    fun sameValue(a: String?, b: String?): kotlin.Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        if (kind == EditKind.Text || kind == EditKind.Other || kind == EditKind.Json || kind == EditKind.Binary) return false
        val fixed = LocalDateTime.of(2000, 1, 1, 0, 0)
        val x = check(a, fixed) as? EditCheck.Ok ?: return false
        val y = check(b, fixed) as? EditCheck.Ok ?: return false
        if (kind.isNumeric) return runCatching { BigDecimal(x.value).compareTo(BigDecimal(y.value)) == 0 }.getOrDefault(x.value == y.value)
        return x.value == y.value
    }

    /** The value of a stored [text] as a boolean, or null when it isn't one. */
    fun booleanOf(text: String?): kotlin.Boolean? = text?.let(FilterValues::boolean)

    fun booleanText(value: kotlin.Boolean): String = if (value) booleanStyle.yes else booleanStyle.no

    private fun checkText(text: String): EditCheck {
        val limit = maxLength ?: return EditCheck.Ok(text)
        val length = text.length
        return when {
            length > limit && strictTypes -> EditCheck.Invalid("$length characters; the column takes at most $limit")
            length > limit -> EditCheck.Ok(text, "$length/$limit characters: longer than declared", warning = true)
            length >= limit * 0.8 -> EditCheck.Ok(text, "$length/$limit")
            else -> EditCheck.Ok(text)
        }
    }

    private fun cleanNumber(input: String): String = input.replace("_", "").replace(" ", "").replace(" ", "")

    private fun integer(input: String): EditCheck {
        val cleaned = cleanNumber(input)
        val value =
            cleaned.toBigIntegerOrNull()
                ?: cleaned.toBigDecimalOrNull()?.let { decimal ->
                    runCatching { decimal.toBigIntegerExact() }.getOrNull() ?: return EditCheck.Invalid("“$input” isn't a whole number")
                }
                ?: return EditCheck.Invalid("“$input” isn't a number")
        val range = integerRange()
        if (range != null && (value < range.first || value > range.second)) {
            return EditCheck.Invalid("Out of range for ${typeName.lowercase()}: ${range.first} to ${range.second}")
        }
        return EditCheck.Ok(value.toString())
    }

    private fun integerRange(): Pair<BigInteger, BigInteger>? {
        val name = typeName.lowercase().substringBefore('(').removeSuffix(" unsigned").trim()
        val bits =
            when (name) {
                "tinyint", "int1" -> 8
                "smallint", "int2", "smallserial", "serial2" -> 16
                "mediumint", "int3" -> 24
                "int", "integer", "int4", "serial", "serial4" -> if (strictTypes) 32 else 64
                "bigint", "int8", "bigserial", "serial8" -> 64
                else -> return null
            }
        return if (unsigned) BigInteger.ZERO to BigInteger.TWO.pow(bits).minus(BigInteger.ONE)
        else BigInteger.TWO.pow(bits - 1).negate() to BigInteger.TWO.pow(bits - 1).minus(BigInteger.ONE)
    }

    private fun decimal(input: String): EditCheck {
        val value = FilterValues.number(cleanNumber(input))
        if (unsigned && value.signum() < 0) return EditCheck.Invalid("The column is unsigned")
        val scale = scale
        val precision = precision
        if (precision != null && scale != null && precision in 1..1000) {
            val wholeDigits = (value.precision() - value.scale()).coerceAtLeast(0)
            if (value.signum() != 0 && wholeDigits > precision - scale) {
                return EditCheck.Invalid("Too large for ${typeName.lowercase()}($precision,$scale): at most ${precision - scale} digits before the point")
            }
        }
        if (scale != null && value.scale() > scale) {
            return EditCheck.Ok(value.toPlainString(), "Rounds to $scale decimal place${if (scale == 1) "" else "s"}", warning = true)
        }
        return EditCheck.Ok(value.toPlainString())
    }

    private fun float(input: String): EditCheck {
        val cleaned = cleanNumber(input)
        when (cleaned.lowercase()) {
            "nan" -> return EditCheck.Ok("NaN")
            "inf", "infinity", "+inf", "+infinity" -> return EditCheck.Ok("Infinity")
            "-inf", "-infinity" -> return EditCheck.Ok("-Infinity")
        }
        cleaned.toBigDecimalOrNull() ?: return EditCheck.Invalid("“$input” isn't a number")
        return EditCheck.Ok(cleaned)
    }

    private fun temporal(input: String, now: LocalDateTime, zone: ZoneId): EditCheck {
        val parsed = FilterValues.temporal(input, now, zone)
        val relative = input.any { it.isLetter() && it.lowercaseChar() !in "tz" } || input.startsWith("+") || input.startsWith("-")
        return when (kind) {
            EditKind.Date ->
                when (parsed) {
                    is TemporalValue.Day -> parsed.date
                    is TemporalValue.Moment -> parsed.dateTime.toLocalDate()
                    is TemporalValue.TimeOfDay -> return EditCheck.Invalid("Enter a date, e.g. 2026-10-31 or today")
                }.let { EditCheck.Ok(FilterValues.format(it), DayFormat.format(it).takeIf { relative }) }
            EditKind.Time ->
                when (parsed) {
                    is TemporalValue.TimeOfDay -> parsed.time
                    is TemporalValue.Moment -> parsed.dateTime.toLocalTime()
                    is TemporalValue.Day -> return EditCheck.Invalid("Enter a time, e.g. 13:45")
                }.let { time ->
                    val (kept, cut) = truncateNanos(time.nano)
                    EditCheck.Ok(FilterValues.format(time.withNano(kept)), cut)
                }
            else -> {
                val dateTime =
                    when (parsed) {
                        is TemporalValue.Day -> parsed.date.atStartOfDay()
                        is TemporalValue.Moment -> parsed.dateTime
                        is TemporalValue.TimeOfDay -> LocalDate.from(now).atTime(parsed.time)
                    }
                val (kept, cut) = truncateNanos(dateTime.nano)
                val value = dateTime.withNano(kept)
                val offset = if (withTimeZone) value.atZone(zone).offset.id.let { if (it == "Z") "+00:00" else it } else ""
                EditCheck.Ok(FilterValues.format(value) + offset, cut ?: MomentFormat.format(value).takeIf { relative })
            }
        }
    }

    /** Nanoseconds kept for the column's fractional-second [scale], and a note when some were cut. */
    private fun truncateNanos(nanos: Int): Pair<Int, String?> {
        val digits = scale?.takeIf { it in 0..9 } ?: return nanos to null
        val unit = Math.pow(10.0, (9 - digits).toDouble()).toInt()
        val kept = nanos / unit * unit
        return kept to (if (kept != nanos) "Seconds keep $digits decimal place${if (digits == 1) "" else "s"}" else null)
    }

    private fun json(text: String): EditCheck =
        try {
            Json.parseToJsonElement(text)
            EditCheck.Ok(text)
        } catch (e: SerializationException) {
            EditCheck.Invalid("Invalid JSON: " + e.message.orEmpty().lineSequence().first().removePrefix("Unexpected JSON token at offset "))
        }

    companion object {
        private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        private val MomentFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss")

        /** Describes [column] for editing; [info] adds what only table metadata knows (nullability, defaults, limits). */
        fun of(column: ResultColumn, info: ColumnInfo? = null, dialect: SqlDialect? = null): ColumnEdit {
            val typeName = info?.typeName ?: column.typeName
            val jdbcType = info?.jdbcType?.takeIf { it != Types.OTHER } ?: column.jdbcType
            val lower = typeName.lowercase()
            val digitBooleans = dialect is SqlDialect.MySQLDialect || dialect is SqlDialect.SQLiteDialect || dialect is SqlDialect.SqlServerDialect
            val kind =
                when (ValueKind.of(typeName, jdbcType, info?.size)) {
                    ValueKind.Text -> EditKind.Text
                    ValueKind.Number ->
                        when {
                            lower.startsWith("money") -> EditKind.Other
                            jdbcType in setOf(Types.REAL, Types.FLOAT, Types.DOUBLE) || lower.startsWith("float") || lower.startsWith("double") || lower == "real" -> EditKind.Float
                            jdbcType in setOf(Types.NUMERIC, Types.DECIMAL) || lower.startsWith("numeric") || lower.startsWith("decimal") || lower == "number" -> EditKind.Decimal
                            else -> EditKind.Integer
                        }
                    ValueKind.Boolean -> EditKind.Boolean
                    ValueKind.Date -> EditKind.Date
                    ValueKind.Time -> EditKind.Time
                    ValueKind.DateTime -> EditKind.DateTime
                    ValueKind.Uuid -> EditKind.Uuid
                    ValueKind.Json -> EditKind.Json
                    ValueKind.Binary -> EditKind.Binary
                    ValueKind.Other -> EditKind.Other
                }
            val sized = kind == EditKind.Text && jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR) &&
                (lower.contains("char") || lower.contains("varchar"))
            return ColumnEdit(
                kind = kind,
                typeName = typeName,
                nullable = info?.nullable ?: true,
                defaultValue = info?.defaultValue?.takeUnless { it.equals("null", ignoreCase = true) || it.startsWith("NULL::") },
                generated = info?.autoIncrement == true,
                maxLength = info?.size?.takeIf { sized && it in 1 until 1_000_000 },
                strictTypes = dialect !is SqlDialect.SQLiteDialect,
                precision = info?.size?.takeIf { kind == EditKind.Decimal },
                scale = info?.scale?.takeIf { kind == EditKind.Decimal || kind == EditKind.DateTime || kind == EditKind.Time },
                unsigned = lower.contains("unsigned"),
                withTimeZone = jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || lower == "timestamptz" || lower.contains("with time zone") || lower == "datetimeoffset",
                booleanStyle = if (digitBooleans) BooleanStyle.Digits else BooleanStyle.Words,
                supportsDefault = dialect !is SqlDialect.SQLiteDialect && dialect !is SqlDialect.ClickHouseDialect,
            )
        }
    }
}

/** Random UUIDs for the editor's Generate actions. */
object Uuids {
    private val random = SecureRandom()

    fun v4(): UUID = UUID.randomUUID()

    /** RFC 9562 version 7: a millisecond timestamp first, so new keys sort by creation time. */
    fun v7(millis: Long = System.currentTimeMillis()): UUID {
        val bytes = ByteArray(16).also(random::nextBytes)
        for (i in 0 until 6) bytes[i] = (millis ushr (40 - 8 * i)).toByte()
        bytes[6] = (bytes[6].toInt() and 0x0F or 0x70).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

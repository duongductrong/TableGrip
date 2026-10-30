package dev.datagrok.core.importer

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What a column's values look like, for creating a new table. [maxLength] counts code points of the longest value. */
data class InferredType(val kind: Kind, val maxLength: Int, val nullable: Boolean) {
    enum class Kind { Integer, BigInteger, Decimal, Boolean, Date, Timestamp, Text, Json }
}

/**
 * Guesses a column type from sample values. Strict on purpose: a value is only numeric if storing it as a number gives it
 * back unchanged, so `007`, `+84…` and `1,234` stay [InferredType.Kind.Text]. Null and blank values don't vote but make the
 * column nullable; a column with no values at all is nullable Text.
 */
object TypeInference {
    fun infer(values: Iterable<String?>): InferredType {
        var seen = 0
        var nullable = false
        var maxLength = 0
        var integer = true // fits a Long
        var bigInteger = true
        var decimal = true
        var boolean = true
        var booleanWord = false // a true/yes/t… rather than only 0 and 1
        var date = true
        var temporal = true // Date or Timestamp
        var json = true
        for (value in values) {
            if (value == null) {
                nullable = true
                continue
            }
            maxLength = maxOf(maxLength, value.codePointCount(0, value.length))
            if (value.isBlank()) {
                nullable = true
                continue
            }
            seen++
            val isInteger = bigInteger && isIntegerText(value)
            if (integer && !(isInteger && value.toLongOrNull() != null)) integer = false
            if (!isInteger) bigInteger = false
            if (decimal && !isInteger && !isDecimalText(value)) decimal = false
            if (boolean) {
                when (value.lowercase()) {
                    in BooleanWords -> booleanWord = true
                    "0", "1" -> Unit
                    else -> boolean = false
                }
            }
            if (temporal) {
                val isDate = isDate(value)
                if (!isDate) date = false
                if (!isDate && !isTimestamp(value)) temporal = false
            }
            if (json) json = isJson(value)
        }
        val kind =
            when {
                seen == 0 -> InferredType.Kind.Text
                boolean && booleanWord -> InferredType.Kind.Boolean
                integer -> InferredType.Kind.Integer
                bigInteger -> InferredType.Kind.BigInteger
                decimal -> InferredType.Kind.Decimal
                date -> InferredType.Kind.Date
                temporal -> InferredType.Kind.Timestamp
                json -> InferredType.Kind.Json
                else -> InferredType.Kind.Text
            }
        return InferredType(kind, maxLength, nullable)
    }

    private val BooleanWords = setOf("true", "false", "yes", "no", "t", "f")

    /** `-?(0|[1-9][0-9]*)`: no plus sign and no leading zeros, which would be lost when stored as a number. */
    internal fun isIntegerText(text: String): Boolean {
        val start = if (text.startsWith('-')) 1 else 0
        val end = digitsEnd(text, start)
        return end == text.length && validIntegerPart(text, start, end)
    }

    /** `-?(0|[1-9][0-9]*)?(\.[0-9]+)?([eE][+-]?[0-9]+)?` with at least one mantissa digit. */
    internal fun isDecimalText(text: String): Boolean {
        var i = if (text.startsWith('-')) 1 else 0
        val intStart = i
        i = digitsEnd(text, i)
        if (i > intStart && !validIntegerPart(text, intStart, i)) return false
        var hasDigits = i > intStart
        if (i < text.length && text[i] == '.') {
            val fracStart = ++i
            i = digitsEnd(text, i)
            if (i == fracStart) return false
            hasDigits = true
        }
        if (!hasDigits) return false
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            val expStart = i
            i = digitsEnd(text, i)
            if (i == expStart) return false
        }
        return i == text.length
    }

    private fun digitsEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    private fun validIntegerPart(text: String, start: Int, end: Int) = end > start && (text[start] != '0' || end - start == 1)

    private val DatePattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val TimestampPattern = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|z|[+-]\d{2}(?::?\d{2})?)?""")

    internal fun isDate(text: String): Boolean {
        val match = DatePattern.matchEntire(text) ?: return false
        val (y, m, d) = match.destructured
        return validDate(y, m, d)
    }

    internal fun isTimestamp(text: String): Boolean {
        val groups = TimestampPattern.matchEntire(text)?.groupValues ?: return false
        if (!validDate(groups[1], groups[2], groups[3])) return false
        val offset = groups[8]
        if (offset.length > 1 && (offset.substring(1, 3).toInt() > 18 || offset.takeLast(2).toInt() > 59)) return false
        return runCatching { LocalTime.of(groups[4].toInt(), groups[5].toInt(), groups[6].ifEmpty { "0" }.toInt()) }.isSuccess
    }

    private fun validDate(y: String, m: String, d: String) = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.isSuccess

    internal fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) && !(trimmed.startsWith('[') && trimmed.endsWith(']'))) return false
        return try {
            Json.parseToJsonElement(trimmed)
            true
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

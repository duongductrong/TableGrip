package dev.tablegrip.app.grid

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

/**
 * Edits one row of a grid as a JSON object, the way the JSON view shows it. Nothing is written: applying a record stages
 * the same pending edits typing into its cells would, so preview, revert and submit work as they do for the grid.
 *
 * Only what the user changed is staged. A record is compared with the JSON it was opened with ([plan]'s `base`), not
 * with the row as it is now, so a cell edited in the grid while the record was open is left alone unless the record
 * changes that same field too.
 */
interface RecordEditor {
    /** What one record is called: "row" or "document". */
    val recordNoun: String

    /** Why [row] can't be edited as JSON (deleted, no primary key…); null when it can. */
    fun whyNotEditable(row: Int): String?

    fun canEditRecord(row: Int): Boolean = whyNotEditable(row) == null

    val canAddRecord: Boolean
        get() = true

    /** Which record [row] is, for the editor's title: its key where it has one. */
    fun describeRecord(row: Int): String = "${recordNoun.replaceFirstChar(Char::uppercase)} ${row + 1} on this page"

    /** The whole record as the editor opens it: every column or field, hidden or not, with pending edits applied. */
    fun recordJson(row: Int): JsonObject

    /** What a new record starts as. */
    fun newRecord(): RecordTemplate

    /** What applying [edited] would change, against [base], the JSON the record was opened with; [row] null for a new one. */
    fun plan(row: Int?, base: JsonObject, edited: JsonObject): RecordPlan

    /** Stages [edited]'s changes as pending edits and returns the row they're in; does nothing and returns null if [plan] has problems. */
    fun apply(row: Int?, base: JsonObject, edited: JsonObject): Int?
}

/** A new record's starting JSON, and a [hint] about what it leaves out. */
data class RecordTemplate(val json: JsonObject, val hint: String? = null)

/** One top-level field a record changes. */
data class FieldChange(val name: String, val kind: Kind) {
    enum class Kind { Changed, Added, Removed }
}

/** What applying a record would do: the fields it changes, what stops it ([problems]) and what's worth knowing ([notes]). */
data class RecordPlan(val changes: List<FieldChange>, val problems: List<String> = emptyList(), val notes: List<String> = emptyList(), val isNew: Boolean = false) {
    /** A new record may be empty (all defaults); an existing one needs something to change. */
    val canApply: Boolean
        get() = problems.isEmpty() && (isNew || changes.isNotEmpty())
}

/** A top-level difference between two versions of a record. */
sealed interface FieldDiff {
    val name: String

    data class Set(override val name: String, val value: JsonElement, val added: Boolean) : FieldDiff

    data class Removed(override val name: String) : FieldDiff
}

/**
 * The top-level fields that differ from [base] to [edited], in [edited]'s order, then the ones removed. By default
 * numbers are compared by value, so `19.9` for `19.90` isn't a change; where `5` and `5.0` are different types (BSON),
 * pass exact equality as [same].
 */
fun diffRecord(base: JsonObject, edited: JsonObject, same: (JsonElement, JsonElement) -> Boolean = ::sameJson): List<FieldDiff> =
    edited.mapNotNull { (name, value) -> base[name].let { was -> if (was != null && same(was, value)) null else FieldDiff.Set(name, value, added = was == null) } } +
        base.keys.filter { it !in edited }.map { FieldDiff.Removed(it) }

/** Whether two JSON values are equal, numbers by value. */
fun sameJson(a: JsonElement, b: JsonElement): Boolean =
    when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> sameJson(value, b.getValue(key)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && a !is JsonNull && b !is JsonNull && !a.isString && !b.isString -> {
            val x = a.content.toBigDecimalOrNull()
            val y = b.content.toBigDecimalOrNull()
            if (x != null && y != null) x.compareTo(y) == 0 else a.content == b.content
        }
        else -> a == b
    }

/**
 * [element] with every number as its exact digits. kotlinx's encoder writes numbers it can't fit in a `Long` through a
 * `Double`, which turns `19.90` into `19.9` and rounds big `numeric`s and `decimal128`s; shown and saved back, that would
 * change data nobody touched.
 */
@OptIn(ExperimentalSerializationApi::class)
fun exactNumbers(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { exactNumbers(it.value) })
        is JsonArray -> JsonArray(element.map(::exactNumbers))
        is JsonNull -> element
        is JsonPrimitive -> if (element.isString || element.booleanOrNull != null) element else JsonUnquotedLiteral(element.content)
    }

fun FieldDiff.change(): FieldChange =
    FieldChange(
        name,
        when (this) {
            is FieldDiff.Removed -> FieldChange.Kind.Removed
            is FieldDiff.Set -> if (added) FieldChange.Kind.Added else FieldChange.Kind.Changed
        },
    )

/** Text typed into the record editor: a JSON object, or where and why it isn't one. */
sealed interface ParsedRecord {
    data class Ok(val json: JsonObject) : ParsedRecord

    /** [line] and [column] are 1-based; null when the parser didn't say where. */
    data class Invalid(val message: String, val line: Int? = null, val column: Int? = null, val offset: Int? = null) : ParsedRecord {
        val text: String
            get() = if (line != null && column != null) "Line $line, column $column: $message" else message
    }
}

/** Forgiving about what people leave in hand-edited JSON: comments and trailing commas. */
@OptIn(ExperimentalSerializationApi::class)
private val RecordJson = Json {
    allowTrailingComma = true
    allowComments = true
}

private val ErrorOffset = Regex("""offset (\d+)""")

/** Parses the editor's text as one JSON object, with errors pointed at a line and column. */
fun parseRecord(text: String, noun: String = "row"): ParsedRecord {
    if (text.isBlank()) return ParsedRecord.Invalid("Enter the $noun as a JSON object: { \"name\": value, … }")
    val element =
        try {
            RecordJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return invalidAt(text, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            return invalidAt(text, e.message.orEmpty())
        }
    return when (element) {
        is JsonObject -> ParsedRecord.Ok(element)
        else -> ParsedRecord.Invalid("A $noun is one JSON object, in { }")
    }
}

private fun invalidAt(text: String, raw: String): ParsedRecord.Invalid {
    val offset = ErrorOffset.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, text.length)
    val message = friendlyParseError(raw)
    if (offset == null) return ParsedRecord.Invalid(message)
    val before = text.substring(0, offset)
    val line = before.count { it == '\n' } + 1
    val column = offset - (before.lastIndexOf('\n') + 1) + 1
    return ParsedRecord.Invalid(message, line, column, offset)
}

/** kotlinx's messages, minus the offset (shown as line and column), the path and the echoed input. */
private fun friendlyParseError(raw: String): String {
    val first = raw.lineSequence().firstOrNull().orEmpty()
    val reason =
        first.substringAfter(": ", first)
            .substringBefore(" at path")
            .removePrefix("Unexpected JSON token at offset ")
            .trim()
            .trimEnd('.')
    return when {
        reason.isEmpty() -> "Not valid JSON"
        "EOF" in reason -> "The JSON ends too early; check for a missing } or ]"
        reason.startsWith("Expected quotation mark") || "unquoted" in reason.lowercase() -> "Names and text values need double quotes"
        reason.startsWith("Expected end of the object or comma") -> "Expected a comma or }"
        reason.startsWith("Expected end of the array or comma") -> "Expected a comma or ]"
        else -> reason.replaceFirstChar { it.uppercase() }
    }
}

/** The record [RecordEditorDialog] is open for; [row] null for a new one. Tied to the [model] it was opened on. */
class RecordEditRequest(val model: GridModel, val row: Int?, val base: JsonObject, val hint: String? = null)

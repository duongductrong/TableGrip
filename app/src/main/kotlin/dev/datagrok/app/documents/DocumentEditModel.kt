package dev.datagrok.app.documents

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.datagrok.app.grid.GridEditor
import dev.datagrok.app.grid.GridModel
import dev.datagrok.app.grid.RowStatus
import dev.datagrok.core.driver.ResultColumn
import dev.datagrok.core.edit.ColumnEdit
import dev.datagrok.core.edit.EditCheck
import dev.datagrok.core.edit.EditKind
import java.math.BigInteger
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The BSON type of a field's value, as relaxed Extended JSON shows it. */
internal enum class BsonKind(val label: String) {
    String("string"),
    Int32("int"),
    Int64("long"),
    Double("double"),
    Decimal("decimal"),
    Bool("bool"),
    Date("date"),
    ObjectId("objectId"),
    Object("object"),
    Array("array"),
    Null("null"),
    /** Binary, regex, timestamp…: shown, but edited only as part of the whole document. */
    Other("other");

    companion object {
        fun of(value: JsonElement): BsonKind =
            when (value) {
                is JsonNull -> Null
                is JsonPrimitive ->
                    when {
                        value.isString -> String
                        value.content == "true" || value.content == "false" -> Bool
                        value.content.any { it == '.' || it == 'e' || it == 'E' } -> Double
                        else -> Int32
                    }
                is JsonArray -> Array
                is JsonObject ->
                    when (value.keys.singleOrNull()) {
                        "\$oid" -> ObjectId
                        "\$date" -> Date
                        "\$numberLong" -> Int64
                        "\$numberInt" -> Int32
                        "\$numberDouble" -> Double
                        "\$numberDecimal" -> Decimal
                        else -> if (value.keys.size == 1 && value.keys.single().startsWith("$")) Other else Object
                    }
            }
    }
}

/**
 * One page of documents as an editable grid, a column per top-level field, plus pending edits: changed fields, removed
 * fields, documents marked for deletion and new documents. Nothing is written until [toChanges] is applied, and then
 * only what changed, field by field:
 * - a value keeps its BSON type (an `int` stays an `int`, a `long` a `long`, a date a date), and what was typed is
 *   checked against that type before anything is sent;
 * - each update names the values the fields had when the page was read, so it doesn't overwrite a newer change;
 * - fields that weren't edited are never sent, so nothing the grid can't show (binary, nested types) can be lost.
 * Documents without an `_id` can't be found again, so they stay read-only; so do `_id`s and fields whose names hold dots
 * or start with `$`.
 */
internal class DocumentEditModel(
    val documents: List<JsonObject>,
    private val readOnly: Boolean = false,
    /** Fields no document of the page has, added by the user (or carried over with pending edits). */
    extraFields: List<String> = emptyList(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : GridModel, GridEditor {
    private val base = DocumentBrowserTab.rows(documents)
    private val added = mutableStateListOf<String>().apply { addAll(extraFields.filter { it !in base.columns.map(ResultColumn::name) }) }

    override val columns: List<ResultColumn>
        get() = base.columns + added.map { ResultColumn(it, "new field", Types.VARCHAR) }

    val fields: List<String>
        get() = columns.map { it.name }

    /** A pending change to one field of an existing document. */
    sealed interface Edit {
        /** The field set to what was typed, or to null. */
        data class Value(val text: String?) : Edit

        /** The field removed from the document (`$unset`). */
        data object Removed : Edit
    }

    private val edits = mutableStateMapOf<Pair<Int, Int>, Edit>()
    private val deleted = mutableStateMapOf<Int, Unit>()
    private val inserted = mutableStateListOf<SnapshotStateMap<Int, String?>>()

    private val baseRows: Int
        get() = documents.size

    override val rowCount: Int
        get() = baseRows + inserted.size

    override val editor: GridEditor?
        get() = if (readOnly) null else this

    override val setDefaultLabel: String
        get() = "Remove Field"

    val changeCount: Int
        get() = edits.keys.map { it.first }.filter { it !in deleted }.distinct().size + deleted.size + inserted.size

    val hasChanges: Boolean
        get() = changeCount > 0

    /** Whether document [row] has edits waiting, which the whole-document editor mustn't save over. */
    fun hasEdits(row: Int): Boolean = row >= baseRows || row in deleted || edits.keys.any { it.first == row }

    private fun field(column: Int) = columns[column].name

    /** The value as read, or null when the document doesn't have the field. */
    private fun original(row: Int, column: Int): JsonElement? = documents[row][field(column)]

    private fun hasId(row: Int) = "_id" in documents[row]

    override fun value(row: Int, column: Int): String? {
        if (row >= baseRows) return inserted[row - baseRows][column]
        return when (val edit = edits[row to column]) {
            is Edit.Value -> edit.text
            Edit.Removed -> null
            null -> if (column < base.columns.size) base.rows[row][column] else ""
        }
    }

    override fun rowStatus(row: Int): RowStatus =
        when {
            row >= baseRows -> RowStatus.Inserted
            row in deleted -> RowStatus.Deleted
            else -> RowStatus.Normal
        }

    override fun isModified(row: Int, column: Int): Boolean = if (row >= baseRows) column in inserted[row - baseRows] else (row to column) in edits

    override fun placeholder(row: Int, column: Int): String? =
        when {
            row >= baseRows && column !in inserted[row - baseRows] -> if (field(column) == "_id") "<auto>" else ""
            row < baseRows && edits[row to column] == Edit.Removed -> "<removed>"
            else -> null
        }

    /** The documents themselves, as relaxed Extended JSON with pending edits applied, rather than the cells' text. */
    override fun json(columns: List<Int>): JsonArray {
        val names = columns.map(::field)
        fun element(row: Int, column: Int, text: String?): JsonElement {
            if (text == null) return JsonNull
            val literal = (convert(kind(row, column), text, zone) as? Converted.Ok)?.literal ?: return JsonPrimitive(text)
            return runCatching { Lenient.parseToJsonElement(literal) }.getOrElse { JsonPrimitive(text) }
        }
        val rows =
            (0 until rowCount).filter { rowStatus(it) != RowStatus.Deleted }.map { row ->
                val fields = LinkedHashMap<String, JsonElement>()
                if (row < baseRows) {
                    for ((name, value) in documents[row]) if (name in names) fields[name] = value
                    edits.filterKeys { it.first == row }.forEach { (key, edit) ->
                        val name = field(key.second)
                        if (name !in names) return@forEach
                        if (edit is Edit.Value) fields[name] = element(row, key.second, edit.text) else fields.remove(name)
                    }
                } else {
                    inserted[row - baseRows].entries.sortedBy { it.key }.forEach { (column, text) -> if (field(column) in names) fields[field(column)] = element(row, column, text) }
                }
                // In the grid's column order.
                JsonObject(names.filter { it in fields }.associateWith { fields.getValue(it) } + fields.filterKeys { it !in names })
            }
        return JsonArray(rows)
    }

    // region Types

    /** The type a column mostly holds, for cells with nothing to go by (missing, null, new). */
    private val columnKinds: List<BsonKind?> by lazy {
        base.columns.map { column -> documents.mapNotNull { it[column.name] }.map(BsonKind::of).filter { it != BsonKind.Null }.toSet().singleOrNull() }
    }

    /** The type what's typed into a cell becomes: the value's own, else the column's; null to infer it from the text. */
    fun kind(row: Int, column: Int): BsonKind? {
        val own = if (row < baseRows) original(row, column)?.let(BsonKind::of)?.takeIf { it != BsonKind.Null } else null
        return own ?: columnKinds.getOrNull(column)?.takeIf { it != BsonKind.Other }
    }

    override fun columnEdit(column: Int): ColumnEdit = editFor(columnKinds.getOrNull(column))

    override fun cellEdit(row: Int, column: Int): ColumnEdit = editFor(kind(row, column))

    private fun editFor(kind: BsonKind?): ColumnEdit =
        ColumnEdit(
            kind =
                when (kind) {
                    BsonKind.String -> EditKind.Text
                    BsonKind.Int32, BsonKind.Int64 -> EditKind.Integer
                    BsonKind.Double -> EditKind.Float
                    BsonKind.Decimal -> EditKind.Decimal
                    BsonKind.Bool -> EditKind.Boolean
                    BsonKind.Date -> EditKind.DateTime
                    BsonKind.Object, BsonKind.Array -> EditKind.Json
                    else -> EditKind.Other
                },
            typeName = kind?.label ?: "any",
            supportsDefault = false,
        )

    override fun check(row: Int, column: Int, text: String): EditCheck {
        val kind = kind(row, column)
        if (text.isBlank() && kind != BsonKind.String) return EditCheck.Empty
        return when (val value = convert(kind, text, zone)) {
            is Converted.Invalid -> EditCheck.Invalid(value.message)
            is Converted.Ok -> EditCheck.Ok(value.text, value.note)
        }
    }

    // endregion

    // region Editing

    override fun canEdit(row: Int, column: Int): Boolean {
        if (readOnly || !editableName(field(column))) return false
        if (row >= baseRows) return true
        if (row in deleted || !hasId(row) || field(column) == "_id") return false
        return original(row, column)?.let(BsonKind::of) != BsonKind.Other
    }

    override fun canSetDefault(row: Int, column: Int): Boolean {
        if (!canEdit(row, column)) return false
        if (row >= baseRows) return column in inserted[row - baseRows]
        return when (edits[row to column]) {
            Edit.Removed -> false
            is Edit.Value -> true
            null -> original(row, column) != null
        }
    }

    /** Removes the field from the document; a new document just leaves it out. */
    override fun setDefault(row: Int, column: Int) {
        if (!canSetDefault(row, column)) return
        if (row >= baseRows) {
            inserted[row - baseRows].remove(column)
            return
        }
        if (original(row, column) == null) edits.remove(row to column) else edits[row to column] = Edit.Removed
    }

    override fun setValue(row: Int, column: Int, value: String?) {
        if (!canEdit(row, column)) return
        if (row >= baseRows) {
            inserted[row - baseRows][column] = value
            return
        }
        val original = original(row, column)
        val unchanged =
            when {
                original == null -> false
                value == null -> original is JsonNull
                else -> original !is JsonNull && value == base.rows[row][column]
            }
        if (unchanged) edits.remove(row to column) else edits[row to column] = Edit.Value(value)
    }

    override fun addRow(): Int {
        inserted += mutableStateMapOf()
        return rowCount - 1
    }

    override fun deleteRows(rows: Collection<Int>) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        rows.filter { it < baseRows && hasId(it) && !readOnly }.forEach { deleted[it] = Unit }
    }

    override fun revertRows(rows: Collection<Int>) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        rows.filter { it < baseRows }.forEach { row ->
            deleted.remove(row)
            edits.keys.filter { it.first == row }.forEach(edits::remove)
        }
    }

    fun revertAll() {
        edits.clear()
        deleted.clear()
        inserted.clear()
    }

    /** Adds an empty column for a field no document has yet; null when it's added, else why not. */
    fun addField(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Enter a field name"
            !editableName(trimmed) -> "Field names can't contain dots or start with \$"
            trimmed in fields -> "“$trimmed” is already a column"
            else -> {
                added += trimmed
                null
            }
        }
    }

    override fun problem(row: Int, column: Int): String? {
        val text =
            when {
                row >= baseRows -> if (column in inserted[row - baseRows]) inserted[row - baseRows][column] else return null
                row in deleted -> return null
                else -> (edits[row to column] as? Edit.Value ?: return null).text
            } ?: return null
        return (convert(kind(row, column), text, zone) as? Converted.Invalid)?.message
    }

    /** The first edit that can't be saved, as "row N, field: why", for refusing to save. */
    fun firstProblem(): String? {
        val rows = (edits.keys.map { it.first }.filter { it !in deleted } + (baseRows until rowCount)).distinct().sorted()
        for (row in rows) {
            for (column in columns.indices) problem(row, column)?.let { return "Row ${row + 1}, ${field(column)}: $it" }
        }
        return null
    }

    // endregion

    // region Saving

    /** Writes to make, in this order: deletions, updates, insertions. [row] is the grid row each comes from. */
    sealed interface Change {
        val row: Int

        data class Delete(override val row: Int, val id: String) : Change

        data class Update(override val row: Int, val id: String, val set: Map<String, String>, val unset: Set<String>, val expected: Map<String, String?>) : Change

        data class Insert(override val row: Int, val document: String) : Change
    }

    fun toChanges(): List<Change> {
        val deletes = deleted.keys.sorted().map { Change.Delete(it, DocumentBrowserTab.idOf(documents[it])) }
        val updates =
            edits.entries.filter { it.key.first !in deleted }.groupBy { it.key.first }.toSortedMap().map { (row, cells) ->
                val set = linkedMapOf<String, String>()
                val unset = linkedSetOf<String>()
                cells.sortedBy { it.key.second }.forEach { (key, edit) ->
                    val name = field(key.second)
                    when (edit) {
                        Edit.Removed -> unset += name
                        is Edit.Value -> set[name] = literal(row, key.second, edit.text)
                    }
                }
                val expected = (set.keys + unset).associateWith { name -> documents[row][name]?.toString() }
                Change.Update(row, DocumentBrowserTab.idOf(documents[row]), set, unset, expected)
            }
        val inserts =
            inserted.mapIndexed { index, cells ->
                val row = baseRows + index
                val body = cells.entries.sortedBy { it.key }.joinToString(", ") { (column, text) -> JsonPrimitive(field(column)).toString() + ": " + literal(row, column, text) }
                Change.Insert(row, "{$body}")
            }
        return deletes + updates + inserts
    }

    private fun literal(row: Int, column: Int, text: String?): String {
        if (text == null) return "null"
        return when (val value = convert(kind(row, column), text, zone)) {
            is Converted.Ok -> value.literal
            is Converted.Invalid -> throw IllegalStateException("Row ${row + 1}, ${field(column)}: ${value.message}")
        }
    }

    /** The shell commands [toChanges] amounts to, for showing before they run. */
    fun preview(collection: String): String {
        val target = "db.getCollection(${JsonPrimitive(collection)})"
        return toChanges().joinToString("\n") { change ->
            when (change) {
                is Change.Delete -> "$target.deleteOne({_id: ${change.id}})"
                is Change.Insert -> "$target.insertOne(${change.document})"
                is Change.Update -> {
                    val filter = (listOf("_id: ${change.id}") + change.expected.map { (field, value) -> "${JsonPrimitive(field)}: ${value ?: "{\$exists: false}"}" }).joinToString(", ")
                    val update =
                        listOfNotNull(
                            change.set.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ", "\$set: {", "}") { "${JsonPrimitive(it.key)}: ${it.value}" },
                            change.unset.takeIf { it.isNotEmpty() }?.joinToString(", ", "\$unset: {", "}") { "${JsonPrimitive(it)}: ''" },
                        ).joinToString(", ")
                    "$target.updateOne({$filter}, {$update})"
                }
            }
        }
    }

    /** Edits of [rows] keyed by `_id` and field name, to put back onto a reloaded page. */
    fun pending(rows: Set<Int>): Pending =
        Pending(
            edits =
                edits.entries.filter { it.key.first in rows && it.key.first !in deleted }.groupBy { DocumentBrowserTab.idOf(documents[it.key.first]) }
                    .mapValues { (_, cells) -> cells.associate { field(it.key.second) to it.value } },
            inserts = inserted.withIndex().filter { baseRows + it.index in rows }.map { (_, cells) -> cells.mapKeys { field(it.key) } },
            deletes = deleted.keys.filter { it in rows }.map { DocumentBrowserTab.idOf(documents[it]) }.toSet(),
        )

    /** Puts back edits from [pending] whose documents are on this page; returns how many documents couldn't be found. */
    fun restore(pending: Pending): Int {
        val rowsById = documents.indices.filter(::hasId).associateBy { DocumentBrowserTab.idOf(documents[it]) }
        var missing = 0
        pending.edits.forEach { (id, cells) ->
            val row = rowsById[id]
            if (row == null) {
                missing++
                return@forEach
            }
            cells.forEach { (name, edit) ->
                val column = fields.indexOf(name).takeIf { it >= 0 } ?: return@forEach
                when (edit) {
                    Edit.Removed -> if (original(row, column) != null) edits[row to column] = edit
                    is Edit.Value -> setValue(row, column, edit.text)
                }
            }
        }
        pending.inserts.forEach { cells -> inserted += mutableStateMapOf<Int, String?>().apply { cells.forEach { (name, text) -> fields.indexOf(name).takeIf { it >= 0 }?.let { put(it, text) } } } }
        pending.deletes.forEach { id ->
            val row = rowsById[id]
            if (row == null) missing++ else deleted[row] = Unit
        }
        return missing
    }

    // endregion

    companion object {
        fun editableName(name: String) = name.isNotEmpty() && '.' !in name && !name.startsWith("$")
    }
}

/** Unsaved edits carried across a reload, by `_id` and field name rather than grid position. */
internal data class Pending(
    val edits: Map<String, Map<String, DocumentEditModel.Edit>> = emptyMap(),
    val inserts: List<Map<String, String?>> = emptyList(),
    val deletes: Set<String> = emptySet(),
) {
    val fields: List<String>
        get() = (edits.values.flatMap { it.keys } + inserts.flatMap { it.keys }).distinct()

    val isEmpty: Boolean
        get() = edits.isEmpty() && inserts.isEmpty() && deletes.isEmpty()
}

/** What typed text becomes: the [text] to show, and the Extended JSON [literal] to write. */
internal sealed interface Converted {
    data class Ok(val text: String, val literal: String, val note: String? = null) : Converted

    data class Invalid(val message: String) : Converted
}

private val IntRange32 = BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong())
private val IntRange64 = BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE)
private val ObjectIdText = Regex("""^(?:ObjectId\(\s*['"]?)?([0-9a-fA-F]{24})(?:['"]?\s*\))?$""")
private val HasZone = Regex("""(?:[Zz]|[+-]\d\d:?\d\d)$""")
private val IsoDateText = Regex("""^(?:ISODate|new Date)\(\s*['"](.*)['"]\s*\)$""")
private val Lenient = Json { isLenient = true }

private fun wrapped(key: String, value: String) = JsonObject(mapOf(key to JsonPrimitive(value))).toString()

/**
 * Converts [text] typed into a cell to a value of [kind], or, for a cell with no type to go by, of the type the text
 * looks like (`42`, `true`, `ObjectId('…')`, `{…}`; quotes make anything a string).
 */
internal fun convert(kind: BsonKind?, text: String, zone: ZoneId = ZoneId.systemDefault()): Converted {
    val input = text.trim()
    fun invalid(message: String) = Converted.Invalid(message)
    return when (kind) {
        BsonKind.String -> Converted.Ok(text, JsonPrimitive(text).toString())
        BsonKind.Int32, BsonKind.Int64 -> {
            val number = wholeNumber(input) ?: return invalid("“$input” isn't a whole number; the field holds ${kind.label}s")
            when {
                kind == BsonKind.Int32 && number in IntRange32 -> Converted.Ok(number.toString(), number.toString())
                number in IntRange64 ->
                    Converted.Ok(number.toString(), wrapped("\$numberLong", number.toString()), "Stored as a 64-bit integer".takeIf { kind == BsonKind.Int32 })
                else -> invalid("Too large for a 64-bit integer")
            }
        }
        BsonKind.Double ->
            when (input.lowercase()) {
                "nan" -> Converted.Ok("NaN", wrapped("\$numberDouble", "NaN"))
                "infinity", "inf", "+infinity" -> Converted.Ok("Infinity", wrapped("\$numberDouble", "Infinity"))
                "-infinity", "-inf" -> Converted.Ok("-Infinity", wrapped("\$numberDouble", "-Infinity"))
                else -> {
                    val number = input.replace("_", "").toBigDecimalOrNull() ?: return invalid("“$input” isn't a number")
                    val double = number.toDouble()
                    if (double.isInfinite()) return invalid("Too large for a double")
                    Converted.Ok(input, double.toString())
                }
            }
        BsonKind.Decimal -> {
            val number = input.replace("_", "").toBigDecimalOrNull() ?: return invalid("“$input” isn't a number")
            Converted.Ok(number.toPlainString(), wrapped("\$numberDecimal", number.toPlainString()))
        }
        BsonKind.Bool ->
            when (input.lowercase()) {
                "true", "1", "yes", "t" -> Converted.Ok("true", "true")
                "false", "0", "no", "f" -> Converted.Ok("false", "false")
                else -> invalid("“$input” isn't true or false")
            }
        BsonKind.Date -> {
            val instant = instant(input, zone) ?: return invalid("“$input” isn't a date; e.g. 2026-10-31T09:30:00Z or 2026-10-31 09:30")
            val iso = instant.toString()
            Converted.Ok(iso, wrapped("\$date", iso), "Local time, stored as $iso".takeIf { !HasZone.containsMatchIn(input) })
        }
        BsonKind.ObjectId -> {
            val hex = ObjectIdText.matchEntire(input)?.groupValues?.get(1)?.lowercase() ?: return invalid("An ObjectId is 24 hex digits")
            Converted.Ok("ObjectId($hex)", wrapped("\$oid", hex))
        }
        BsonKind.Object, BsonKind.Array -> {
            val (open, name) = if (kind == BsonKind.Object) '{' to "a document: {…}" else '[' to "an array: […]"
            if (!input.startsWith(open)) return invalid("Enter $name")
            if (!balanced(input)) return invalid("Unbalanced brackets or quotes")
            // Strict or lenient JSON is checked here; shell syntax like ObjectId('…') is the server's to read.
            val note = if (runCatching { Lenient.parseToJsonElement(input) }.isFailure) "Shell syntax: checked by the server on save" else null
            Converted.Ok(input, input, note)
        }
        else -> infer(input, text, zone)
    }
}

/** A value for a cell with no type yet: what the text looks like. */
private fun infer(input: String, text: String, zone: ZoneId): Converted {
    if (input.length >= 2 && (input.first() == '\'' || input.first() == '"') && input.last() == input.first()) {
        return Converted.Ok(input, JsonPrimitive(input.substring(1, input.length - 1)).toString(), "String")
    }
    val note: (BsonKind) -> String = { "Stored as ${if (it == BsonKind.Int32) "an int" else "a" + (if (it.label.first() in "aeiou") "n " else " ") + it.label}; quote it for a string" }
    wholeNumber(input)?.takeIf { input.none { c -> c == '.' || c == 'e' || c == 'E' } }?.let { number ->
        val kind = if (number in IntRange32) BsonKind.Int32 else BsonKind.Int64
        return (convert(kind, input) as? Converted.Ok)?.copy(text = input, note = note(kind)) ?: convert(kind, input)
    }
    if (input.toBigDecimalOrNull() != null) return (convert(BsonKind.Double, input) as Converted.Ok).copy(note = note(BsonKind.Double))
    if (input == "true" || input == "false") return Converted.Ok(input, input, note(BsonKind.Bool))
    if (input.startsWith("ObjectId(")) return (convert(BsonKind.ObjectId, input) as? Converted.Ok)?.copy(text = input, note = note(BsonKind.ObjectId)) ?: convert(BsonKind.ObjectId, input)
    IsoDateText.matchEntire(input)?.let { match ->
        val date = convert(BsonKind.Date, match.groupValues[1], zone)
        return (date as? Converted.Ok)?.copy(text = input, note = note(BsonKind.Date)) ?: date
    }
    if (input.startsWith("{")) return (convert(BsonKind.Object, input) as? Converted.Ok)?.let { it.copy(note = it.note ?: note(BsonKind.Object)) } ?: convert(BsonKind.Object, input)
    if (input.startsWith("[")) return (convert(BsonKind.Array, input) as? Converted.Ok)?.let { it.copy(note = it.note ?: note(BsonKind.Array)) } ?: convert(BsonKind.Array, input)
    return Converted.Ok(text, JsonPrimitive(text).toString())
}

private fun wholeNumber(input: String): BigInteger? {
    val cleaned = input.replace("_", "")
    return cleaned.toBigIntegerOrNull() ?: cleaned.toBigDecimalOrNull()?.let { runCatching { it.toBigIntegerExact() }.getOrNull() }
}

private val LocalFormats =
    listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").map { DateTimeFormatter.ofPattern(it) }

/** A moment typed as ISO-8601 with or without zone, or as `yyyy-MM-dd[ HH:mm[:ss]]` in [zone]. */
internal fun instant(input: String, zone: ZoneId): Instant? {
    val text = input.trim()
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text.replace(' ', 'T')).toInstant() }
    runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant() }
    for (format in LocalFormats) {
        try {
            return LocalDateTime.parse(text, format).atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
        }
    }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone).toInstant() }
    return null
}

/** Whether brackets pair up outside quoted strings. */
private fun balanced(text: String): Boolean {
    val stack = ArrayDeque<Char>()
    var quote: Char? = null
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (quote != null) {
            if (c == '\\') i++ else if (c == quote) quote = null
        } else when (c) {
            '\'', '"' -> quote = c
            '{', '[', '(' -> stack.addLast(c)
            '}' -> if (stack.removeLastOrNull() != '{') return false
            ']' -> if (stack.removeLastOrNull() != '[') return false
            ')' -> if (stack.removeLastOrNull() != '(') return false
        }
        i++
    }
    return quote == null && stack.isEmpty()
}

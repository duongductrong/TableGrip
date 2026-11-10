package dev.tablegrip.app.table

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.tablegrip.app.grid.EditHistory
import dev.tablegrip.app.grid.FieldChange
import dev.tablegrip.app.grid.FieldDiff
import dev.tablegrip.app.grid.GridEditor
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.RecordEditor
import dev.tablegrip.app.grid.RecordPlan
import dev.tablegrip.app.grid.RecordTemplate
import dev.tablegrip.app.grid.RowStatus
import dev.tablegrip.app.grid.cellJson
import dev.tablegrip.app.grid.change
import dev.tablegrip.app.grid.diffRecord
import dev.tablegrip.app.grid.undoable
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.edit.BoundValue
import dev.tablegrip.core.edit.ColumnEdit
import dev.tablegrip.core.edit.EditCheck
import dev.tablegrip.core.edit.EditKind
import dev.tablegrip.core.edit.RowChange
import dev.tablegrip.core.metadata.TableDetails
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One page of table rows plus pending, unsaved edits: changed cells, rows marked for deletion and new rows (appended
 * after the page). Updates and deletes need a primary key to identify rows; without one the page is insert-only.
 * [details] tells editors what each column takes (NOT NULL, defaults, limits); without it every column is nullable.
 */
class TableEditModel(
    private val result: QueryResult.Rows,
    primaryKey: List<String>,
    /** For read-only connections: the grid offers no editing at all. */
    private val readOnly: Boolean = false,
    details: TableDetails? = null,
    dialect: SqlDialect? = null,
) : GridModel, GridEditor, RecordEditor {
    override val columns: List<ResultColumn> = result.columns

    private val columnEdits: List<ColumnEdit> =
        columns.map { column -> ColumnEdit.of(column, details?.columns?.firstOrNull { it.name == column.name }, dialect) }

    private val keyColumns: List<Int>? =
        primaryKey.map { name -> columns.indexOfFirst { it.name == name } }.takeIf { it.isNotEmpty() && it.none { i -> i < 0 } }

    /** Whether existing rows can be changed or deleted. */
    val hasKey: Boolean
        get() = keyColumns != null

    private val updates = mutableStateMapOf<Pair<Int, Int>, String?>()
    /** Cells of existing rows set back to their column's default. */
    private val defaults = mutableStateMapOf<Pair<Int, Int>, Unit>()
    private val deleted = mutableStateMapOf<Int, Unit>()
    private val inserted = mutableStateListOf<SnapshotStateMap<Int, String?>>()

    private val baseRows: Int
        get() = result.rows.size

    /** The pending edits at one moment, as plain values, for [history]. */
    private data class Pending(
        val updates: Map<Pair<Int, Int>, String?>,
        val defaults: Set<Pair<Int, Int>>,
        val deleted: Set<Int>,
        val inserted: List<Map<Int, String?>>,
    )

    override val history: EditHistory =
        EditHistory(
            capture = { Pending(updates.toMap(), defaults.keys.toSet(), deleted.keys.toSet(), inserted.map { it.toMap() }) },
            restore = { pending ->
                pending as Pending
                updates.clear()
                updates.putAll(pending.updates)
                defaults.clear()
                pending.defaults.forEach { defaults[it] = Unit }
                deleted.clear()
                pending.deleted.forEach { deleted[it] = Unit }
                inserted.clear()
                pending.inserted.forEach { inserted += mutableStateMapOf<Int, String?>().apply { putAll(it) } }
            },
        )

    override val rowCount: Int
        get() = baseRows + inserted.size

    override val editor: GridEditor?
        get() = if (readOnly) null else this

    override val records: RecordEditor?
        get() = if (readOnly) null else this

    val changeCount: Int
        get() = (updates.keys + defaults.keys).map { it.first }.filter { it !in deleted }.distinct().size + deleted.size + inserted.size

    val hasChanges: Boolean
        get() = changeCount > 0

    override fun columnEdit(column: Int): ColumnEdit = columnEdits[column]

    override fun value(row: Int, column: Int): String? {
        if (row >= baseRows) return inserted[row - baseRows][column]
        val key = row to column
        return when (key) {
            in defaults -> null
            in updates -> updates[key]
            else -> result.rows[row][column]
        }
    }

    override fun rowStatus(row: Int): RowStatus =
        when {
            row >= baseRows -> RowStatus.Inserted
            row in deleted -> RowStatus.Deleted
            else -> RowStatus.Normal
        }

    override fun isModified(row: Int, column: Int): Boolean =
        if (row >= baseRows) column in inserted[row - baseRows] else (row to column).let { it in updates || it in defaults }

    override fun placeholder(row: Int, column: Int): String? =
        when {
            row >= baseRows && column !in inserted[row - baseRows] -> columnEdits[column].placeholder
            row < baseRows && (row to column) in defaults -> tr("<default>")
            else -> null
        }

    override fun problem(row: Int, column: Int): String? {
        if (row < baseRows && (row in deleted || (row to column) !in updates)) return null
        val edit = columnEdits[column]
        val newRow = row >= baseRows
        if (newRow && column !in inserted[row - baseRows]) return if (edit.hasDefault) null else tr("{0} is required: it is NOT NULL and has no default", columns[column].name)
        val value = value(row, column) ?: return if (edit.nullable) null else tr("{0} can't be NULL", columns[column].name)
        return when (val check = edit.check(value)) {
            is EditCheck.Invalid -> check.message
            // Blank text kept as typed in a column that isn't text: the server can't read it.
            EditCheck.Empty -> tr("Empty isn't a {0}; enter a value or set NULL", edit.typeName.lowercase())
            is EditCheck.Ok -> null
        }
    }

    /** The first edit that can't be saved, as "row N, column: why", for refusing to save. */
    fun firstProblem(): String? {
        val rows = (updates.keys.map { it.first }.filter { it !in deleted } + (baseRows until rowCount)).distinct().sorted()
        for (row in rows) {
            for (column in columns.indices) {
                problem(row, column)?.let { return tr("Row {0}, {1}: {2}", row + 1, columns[column].name, it) }
            }
        }
        return null
    }

    override fun canEdit(row: Int, column: Int): Boolean =
        !readOnly && !columns[column].isBinary && (row >= baseRows || (hasKey && row !in deleted))

    override fun canSetNull(row: Int, column: Int): Boolean = canEdit(row, column) && columnEdits[column].nullable

    override fun canSetDefault(row: Int, column: Int): Boolean {
        if (!canEdit(row, column)) return false
        val edit = columnEdits[column]
        return if (row >= baseRows) column in inserted[row - baseRows] && edit.hasDefault
        else edit.supportsDefault && (edit.defaultValue != null || edit.generated) && (row to column) !in defaults
    }

    override fun setValue(row: Int, column: Int, value: String?) = undoable(tr("edit of {0}", columns[column].name), row, column) {
        if (!canEdit(row, column)) return@undoable
        if (row >= baseRows) {
            inserted[row - baseRows][column] = value
            return@undoable
        }
        defaults.remove(row to column)
        if (columnEdits[column].sameValue(value, result.rows[row][column])) updates.remove(row to column) else updates[row to column] = value
    }

    override fun setDefault(row: Int, column: Int) = undoable(tr("DEFAULT for {0}", columns[column].name), row, column) {
        if (!canSetDefault(row, column)) return@undoable
        if (row >= baseRows) {
            inserted[row - baseRows].remove(column)
            return@undoable
        }
        updates.remove(row to column)
        defaults[row to column] = Unit
    }

    override fun revertCell(row: Int, column: Int) = undoable(tr("revert of {0}", columns[column].name), row, column) {
        if (row >= baseRows) {
            inserted[row - baseRows].remove(column)
            return@undoable
        }
        updates.remove(row to column)
        defaults.remove(row to column)
    }

    override fun addRow(): Int =
        undoable(tr("new row"), baseRows + inserted.size) {
            inserted += mutableStateMapOf()
            rowCount - 1
        }

    override fun deleteRows(rows: Collection<Int>) = undoable(if (rows.size == 1) tr("row deletion") else tr("deletion of {0} rows", rows.size), rows.minOrNull()) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        if (hasKey) rows.filter { it < baseRows }.forEach { deleted[it] = Unit }
    }

    override fun revertRows(rows: Collection<Int>) = undoable(if (rows.size == 1) tr("row revert") else tr("revert of {0} rows", rows.size), rows.minOrNull()) {
        rows.filter { it >= baseRows }.sortedDescending().forEach { inserted.removeAt(it - baseRows) }
        rows.filter { it < baseRows }.forEach { row ->
            deleted.remove(row)
            updates.keys.filter { it.first == row }.forEach(updates::remove)
            defaults.keys.filter { it.first == row }.forEach(defaults::remove)
        }
    }

    /** Drops every pending edit; one undo step, so a Revert All clicked by mistake can be taken back. */
    fun revertAll() = undoable(tr("revert of all changes")) {
        updates.clear()
        defaults.clear()
        deleted.clear()
        inserted.clear()
    }

    // region Records as JSON

    override val recordNoun: String
        get() = tr("row")

    override fun whyNotEditable(row: Int): String? =
        when {
            readOnly -> tr("The connection is read-only")
            row !in 0 until rowCount -> tr("This row is no longer on the page")
            row in deleted -> tr("This row is marked for deletion; revert it first")
            row < baseRows && !hasKey -> tr("The table has no primary key, so existing rows can't be changed")
            else -> null
        }

    override fun describeRecord(row: Int): String {
        if (row >= baseRows) return tr("New row")
        val key = keyColumns ?: return tr("Row {0} on this page", row + 1)
        return key.joinToString(", ") { "${columns[it].name} = ${result.rows[row][it] ?: "NULL"}" }
    }

    /** Each column as the JSON view shows it; a new row leaves out the columns that will take their default. */
    override fun recordJson(row: Int): JsonObject =
        JsonObject(
            columns.indices
                .filter { row < baseRows || it in inserted[row - baseRows] }
                .associate { columns[it].name to cellJson(value(row, it), columns[it]) },
        )

    override fun newRecord(): RecordTemplate {
        // Nullable columns are listed as null, so the shape of the row is there to fill in; generated and defaulted ones aren't.
        val (defaulted, asked) = columns.indices.filter { !columns[it].isBinary }.partition { columnEdits[it].let { edit -> edit.generated || edit.defaultValue != null } }
        val hint =
            defaulted.takeIf { it.isNotEmpty() }?.let { list ->
                tr("Left out, so they take their defaults: ") + list.joinToString(", ") { columns[it].name } + tr(". Add one to set it.")
            }
        return RecordTemplate(JsonObject(asked.associate { columns[it].name to JsonNull }), hint)
    }

    override fun plan(row: Int?, base: JsonObject, edited: JsonObject): RecordPlan = planned(row, base, edited).first

    override fun apply(row: Int?, base: JsonObject, edited: JsonObject): Int? = undoable(if (row == null) tr("new row from JSON") else tr("row edit as JSON"), row ?: rowCount) {
        val (plan, steps) = planned(row, base, edited)
        if (!plan.canApply) return@undoable null
        val target = row ?: addRow()
        steps.forEach { step ->
            when (step) {
                is Step.Value -> setValue(target, step.column, step.text)
                is Step.Default -> revertCell(target, step.column)
            }
        }
        target
    }

    /** One cell write a record's plan comes down to. */
    private sealed interface Step {
        val column: Int

        /** The cell set to [text]; null for NULL. */
        data class Value(override val column: Int, val text: String?) : Step

        /** A new row's cell left to its default. */
        data class Default(override val column: Int) : Step
    }

    private fun planned(row: Int?, base: JsonObject, edited: JsonObject): Pair<RecordPlan, List<Step>> {
        if (row != null) whyNotEditable(row)?.let { return RecordPlan(emptyList(), listOf(it)) to emptyList() }
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val changes = mutableListOf<FieldChange>()
        val steps = mutableListOf<Step>()
        val kept = mutableListOf<String>()
        val isNew = row == null || row >= baseRows
        for (diff in diffRecord(if (row == null) JsonObject(emptyMap()) else base, edited)) {
            val name = diff.name
            val column = columns.indexOfFirst { it.name == name }
            if (column < 0) {
                problems += tr("There's no column “{0}” in this table", name)
                continue
            }
            val edit = columnEdits[column]
            when (diff) {
                is FieldDiff.Removed ->
                    if (isNew) {
                        steps += Step.Default(column)
                        changes += diff.change()
                    } else kept += name
                is FieldDiff.Set -> {
                    if (columns[column].isBinary) {
                        problems += tr("“{0}” holds binary data, which can't be edited as JSON", name)
                        continue
                    }
                    val text =
                        when (val converted = cellText(column, diff.value)) {
                            is CellText.Invalid -> {
                                problems += converted.message
                                continue
                            }
                            is CellText.Ok -> converted.text
                        }
                    if (text == null) {
                        if (edit.nullable) {
                            steps += Step.Value(column, null)
                            changes += diff.change()
                        } else problems += tr("“{0}” can't be NULL", name) + if (isNew && edit.hasDefault) tr("; leave it out to use its default") else ""
                        continue
                    }
                    when (val check = edit.check(text)) {
                        is EditCheck.Invalid -> problems += "“$name”: ${check.message}"
                        EditCheck.Empty -> problems += tr("“{0}” is empty, which isn't {1}; use null for NULL", name, article(edit.typeName))
                        is EditCheck.Ok -> {
                            if (check.warning) check.note?.let { notes += "“$name”: $it" }
                            steps += Step.Value(column, check.value)
                            changes += diff.change()
                        }
                    }
                }
            }
        }
        if (!isNew) {
            val keyNames = keyColumns.orEmpty().map { columns[it].name }.toSet()
            changes.map { it.name }.filter { it in keyNames }.takeIf { it.isNotEmpty() }?.let { keys ->
                notes += tr("Changes the primary key ({0}); rows that reference it aren't updated", keys.joinToString(", "))
            }
            val current = recordJson(row!!)
            val both = changes.map { it.name }.filter { current[it] != base[it] }
            if (both.isNotEmpty()) notes += tr("Also edited in the grid since this was opened: {0}. The JSON's values replace those edits.", both.joinToString(", "))
        }
        if (kept.isNotEmpty()) notes += tr("Left out, so they keep their values: {0}", kept.joinToString(", "))
        return RecordPlan(changes, problems, notes, isNew = row == null) to steps
    }

    private sealed interface CellText {
        data class Ok(val text: String?) : CellText

        data class Invalid(val message: String) : CellText
    }

    /** A JSON value as the text a cell of [column] holds: the inverse of [cellJson]. */
    private fun cellText(column: Int, value: JsonElement): CellText {
        val edit = columnEdits[column]
        val name = columns[column].name
        val isJson = edit.kind == EditKind.Json || "json" in columns[column].typeName.lowercase()
        return when (value) {
            is JsonNull -> CellText.Ok(null)
            is JsonPrimitive ->
                when {
                    isJson && !value.isString -> CellText.Ok(value.toString())
                    value.isString -> CellText.Ok(value.content)
                    value.booleanOrNull != null && edit.kind == EditKind.Boolean -> CellText.Ok(edit.booleanText(value.booleanOrNull!!))
                    else -> CellText.Ok(value.content)
                }
            is JsonObject, is JsonArray ->
                if (isJson) CellText.Ok(value.toString())
                else CellText.Invalid(tr("“{0}” takes {1}, not {2}", name, article(edit.typeName), if (value is JsonObject) tr("an object") else tr("an array")))
        }
    }

    private fun article(type: String): String = type.lowercase().let { if (it.isNotEmpty() && it[0] in "aeiou") tr("an {0}", it) else tr("a {0}", it) }

    // endregion

    fun toChanges(): List<RowChange> {
        fun bound(column: Int, value: String?) = columns[column].let { BoundValue(value, it.jdbcType, it.isNumeric) }
        fun key(row: Int) = keyColumns!!.associate { columns[it].name to bound(it, result.rows[row][it]) }

        val deletes = deleted.keys.sorted().map { RowChange.Delete(key(it)) }
        val cells =
            updates.entries.map { it.key to bound(it.key.second, it.value) } +
                defaults.keys.map { it to bound(it.second, null).copy(isDefault = true) }
        val changes =
            cells
                .filter { it.first.first !in deleted }
                .groupBy({ it.first.first }, { it.first.second to it.second })
                .toSortedMap()
                .map { (row, values) -> RowChange.Update(key(row), values.sortedBy { it.first }.associate { (c, v) -> columns[c].name to v }) }
        val inserts = inserted.map { row -> RowChange.Insert(row.entries.sortedBy { it.key }.associate { (c, v) -> columns[c].name to bound(c, v) }) }
        return deletes + changes + inserts
    }
}

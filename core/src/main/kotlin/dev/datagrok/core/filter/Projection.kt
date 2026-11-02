package dev.datagrok.core.filter

import dev.datagrok.core.chart.TimeBucket
import dev.datagrok.core.dialect.SqlDialect

/** What a [SelectFunction] needs besides its column. [label] reads before the field: "decimals 2". */
enum class FunctionArgument(val label: String, val placeholder: String) {
    Digits("decimals", "0"),
    Range("from, length", "1, 10"),
    Fallback("if NULL", "value"),
    Bucket("to", "day"),
}

/** A function applied to a column in the select list; [label] is what the picker shows. */
enum class SelectFunction(val label: String, val argument: FunctionArgument? = null) {
    None("value"),
    Upper("UPPER"),
    Lower("LOWER"),
    Trim("TRIM"),
    Length("LENGTH"),
    Substring("SUBSTRING", FunctionArgument.Range),
    Round("ROUND", FunctionArgument.Digits),
    Abs("ABS"),
    Floor("FLOOR"),
    Ceil("CEIL"),
    Truncate("truncate", FunctionArgument.Bucket),
    Coalesce("if NULL…", FunctionArgument.Fallback),
    AsText("as text");

    /** A short name for the column the result shows under, e.g. `upper(name)`. */
    fun outputName(column: String): String = if (this == None) column else "${name.lowercase()}($column)"

    companion object {
        /** Functions that apply to a [kind] column on [dialect], in sections the UI separates; [None] first. */
        fun forKind(kind: ValueKind, dialect: SqlDialect): List<List<SelectFunction>> {
            val any = listOf(Coalesce, AsText)
            val own =
                when (kind) {
                    ValueKind.Text -> listOf(Upper, Lower, Trim, Length, Substring)
                    ValueKind.Number -> listOf(Round, Abs, Floor, Ceil)
                    ValueKind.Date, ValueKind.DateTime -> if (dialect.timeBucket("x", TimeBucket.Day) != null) listOf(Truncate) else emptyList()
                    else -> emptyList()
                }
            val shared =
                when (kind) {
                    ValueKind.Text -> listOf(Coalesce)
                    ValueKind.Boolean -> listOf(AsText)
                    ValueKind.Binary -> emptyList()
                    else -> any
                }
            return listOf(listOf(None), own, shared).filter { it.isNotEmpty() }
        }
    }
}

/** One entry of a select list. Disabled items are kept, so a column can be hidden and shown again. */
sealed interface SelectItem {
    val id: Long
    /** The name the result shows under; blank for the default. */
    val alias: String
    val enabled: Boolean
}

/** A table column, as is or through [function] with its [argument]. */
data class SelectField(
    override val id: Long,
    val column: String,
    val function: SelectFunction = SelectFunction.None,
    val argument: String = "",
    override val alias: String = "",
    override val enabled: Boolean = true,
) : SelectItem

/** A hand-written SQL expression, used as is. */
data class SelectExpression(override val id: Long, val sql: String, override val alias: String = "", override val enabled: Boolean = true) : SelectItem

/** What the table viewer selects: the enabled [items] in order, or every column when there are none. */
data class Projection(val items: List<SelectItem> = emptyList(), val distinct: Boolean = false)

/** A compiled select list: [columns] go between `SELECT [DISTINCT]` and `FROM`. */
data class SqlProjection(val columns: String, val distinct: Boolean = false) {
    val clause: String
        get() = if (distinct) "DISTINCT $columns" else columns
}

/**
 * The outcome of compiling a [Projection]: the select list (null for `*`), per item id what's wrong (id 0 for the list
 * as a whole), and whether the rows still map one to one onto table rows with their columns' own names, so they can be
 * edited.
 */
data class CompiledProjection(val projection: SqlProjection?, val issues: Map<Long, String>, val editable: Boolean) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

/** Turns a [Projection] into a select list for [dialect]. Arguments are validated and inlined as literals, never as `?`. */
class ProjectionCompiler(private val dialect: SqlDialect, private val columns: List<FilterColumn>) {
    private val byName = columns.associateBy { it.name }

    fun compile(projection: Projection): CompiledProjection {
        val issues = mutableMapOf<Long, String>()
        val enabled = projection.items.filter { it.enabled }
        if (enabled.isEmpty()) {
            if (projection.items.isNotEmpty()) issues[0] = "Choose at least one column to show"
            return CompiledProjection(null, issues, editable = !projection.distinct && issues.isEmpty())
        }
        val parts = mutableListOf<String>()
        val names = mutableMapOf<String, Long>()
        for (item in enabled) {
            val part =
                try {
                    item(item)
                } catch (e: FilterValueException) {
                    issues[item.id] = e.message.orEmpty()
                    continue
                }
            val name = outputName(item)
            val clash = names.put(name.lowercase(), item.id)
            if (clash != null) issues[item.id] = "Another column already shows as “$name”; give this one a different name"
            parts += part
        }
        val plain = enabled.all { it is SelectField && it.function == SelectFunction.None && it.alias.isBlank() }
        // Every column, in table order and unchanged, is just `*`.
        val all = plain && !projection.distinct && enabled.map { (it as SelectField).column } == columns.map { it.name }
        val sql = SqlProjection(parts.joinToString(", "), projection.distinct).takeIf { !all }
        return CompiledProjection(sql.takeIf { issues.isEmpty() }, issues.toMap(), editable = plain && !projection.distinct && issues.isEmpty())
    }

    /** The column name the result has for [item]. */
    fun outputName(item: SelectItem): String =
        item.alias.trim().ifEmpty {
            when (item) {
                is SelectField -> item.function.outputName(item.column)
                is SelectExpression -> defaultAlias(item.sql)
            }
        }

    private fun item(item: SelectItem): String =
        when (item) {
            is SelectExpression -> {
                val sql = item.sql.trim().removeSuffix(";").trim().ifEmpty { throw FilterValueException("Enter a SQL expression") }
                "$sql AS ${dialect.quoteIdentifier(outputName(item))}"
            }
            is SelectField -> field(item)
        }

    private fun field(field: SelectField): String {
        val column =
            byName[field.column]
                ?: throw FilterValueException(if (field.column.isEmpty()) "Choose a column" else "No column “${field.column}” in this table")
        val function = field.function
        if (SelectFunction.forKind(column.kind, dialect).none { function in it }) {
            throw FilterValueException("“${function.label}” doesn't apply to ${column.kind.label} columns")
        }
        val c = dialect.quoteIdentifier(column.name)
        val sql =
            dialect.selectFunction(function, c, arguments(function, column, field.argument))
                ?: throw FilterValueException("This database has no ${function.label}")
        val alias = field.alias.trim()
        return when {
            function == SelectFunction.None && alias.isEmpty() -> c
            else -> "$sql AS ${dialect.quoteIdentifier(alias.ifEmpty { function.outputName(column.name) })}"
        }
    }

    /** [input] as SQL arguments for [function]. */
    private fun arguments(function: SelectFunction, column: FilterColumn, input: String): List<String> {
        val text = input.trim()
        return when (function.argument) {
            null -> emptyList()
            FunctionArgument.Digits -> listOf(integer(text.ifEmpty { "0" }, 0..10, "Enter how many decimals to keep, 0 to 10"))
            FunctionArgument.Range -> {
                val parts = text.split(',').map { it.trim() }
                if (parts.size != 2 || parts.any { it.isEmpty() }) throw FilterValueException("Enter where to start and how many characters, e.g. 1, 10")
                listOf(integer(parts[0], 1..Int.MAX_VALUE, "Start at character 1 or later"), integer(parts[1], 0..Int.MAX_VALUE, "Enter a length of 0 or more"))
            }
            FunctionArgument.Bucket -> {
                val bucket = if (text.isEmpty()) TimeBucket.Day else TimeBucket.of(text) ?: throw FilterValueException("Truncate to hour, day, week, month, quarter or year")
                listOf(bucket.name)
            }
            FunctionArgument.Fallback ->
                listOf(
                    when (column.kind) {
                        ValueKind.Number -> FilterValues.format(FilterValues.number(text.ifEmpty { throw FilterValueException("Enter the number to show instead of NULL") }))
                        ValueKind.Text -> dialect.literal(input, numeric = false)
                        else -> dialect.literal(text.ifEmpty { throw FilterValueException("Enter the value to show instead of NULL") }, numeric = false)
                    }
                )
        }
    }

    private fun integer(text: String, range: IntRange, message: String): String {
        val value = text.toIntOrNull()?.takeIf { it in range } ?: throw FilterValueException(message)
        return value.toString()
    }

    companion object {
        /** An expression shows under its own text, cut short to fit every engine's identifier limit. */
        fun defaultAlias(sql: String): String = sql.trim().removeSuffix(";").trim().replace(Regex("\\s+"), " ").take(60)
    }
}

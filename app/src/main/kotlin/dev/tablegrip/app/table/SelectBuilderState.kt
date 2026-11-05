package dev.tablegrip.app.table

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.Projection
import dev.tablegrip.core.filter.SelectExpression
import dev.tablegrip.core.filter.SelectField
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.SelectItem

/** Editable counterpart of a [SelectItem]: what the select editor's rows are bound to. */
sealed class SelectItemState(val id: Long) {
    var enabled by mutableStateOf(true)
    val alias = TextFieldState()

    abstract fun toItem(): SelectItem
}

class SelectFieldState(id: Long, column: String, function: SelectFunction = SelectFunction.None) : SelectItemState(id) {
    var column by mutableStateOf(column)
    var function by mutableStateOf(function)
    val argument = TextFieldState()

    override fun toItem() = SelectField(id, column, function, argument.text.toString(), alias.text.toString(), enabled)
}

class SelectExpressionState(id: Long, sql: String) : SelectItemState(id) {
    val sql = TextFieldState(sql)

    override fun toItem() = SelectExpression(id, sql.text.toString(), alias.text.toString(), enabled)
}

/** The table viewer's select list: which columns show, in what order, through which functions and under what names. */
class SelectBuilderState {
    private var nextId = 1L
    val items = mutableStateListOf<SelectItemState>()
    var distinct by mutableStateOf(false)

    val isEmpty: Boolean
        get() = items.isEmpty()

    fun toProjection(): Projection = Projection(items.map { it.toItem() }, distinct)

    /** Starts over from every column, as `SELECT *` shows them. */
    fun reset(columns: List<FilterColumn>) {
        items.clear()
        distinct = false
        columns.forEach { items += SelectFieldState(nextId++, it.name) }
    }

    /** Adds right after [after] when given (e.g. the row being edited), else at the end. */
    fun addField(column: FilterColumn?, after: SelectItemState? = null): SelectFieldState = insert(SelectFieldState(nextId++, column?.name.orEmpty()), after)

    fun addExpression(sql: String = "", after: SelectItemState? = null): SelectExpressionState = insert(SelectExpressionState(nextId++, sql), after)

    private fun <T : SelectItemState> insert(item: T, after: SelectItemState?): T {
        val at = items.indexOf(after)
        if (at < 0) items += item else items.add(at + 1, item)
        return item
    }

    /** Copies [item] right after it, e.g. to show a column both as is and through a function. */
    fun duplicate(item: SelectItemState): SelectItemState {
        val copy =
            when (item) {
                is SelectFieldState ->
                    SelectFieldState(nextId++, item.column, item.function).also { it.argument.setTextAndPlaceCursorAtEnd(item.argument.text.toString()) }
                is SelectExpressionState -> SelectExpressionState(nextId++, item.sql.text.toString())
            }
        copy.enabled = item.enabled
        return insert(copy, item)
    }

    /** Moves [item] up ([by] < 0) or down; false at either end. */
    fun move(item: SelectItemState, by: Int): Boolean {
        val from = items.indexOf(item)
        val to = from + by
        if (from < 0 || to !in items.indices) return false
        items.removeAt(from)
        items.add(to, item)
        return true
    }

    fun remove(item: SelectItemState) = items.remove(item)

    fun showAll(shown: Boolean) = items.forEach { it.enabled = shown }

    /** Picks a column, keeping the function when it still applies to the column's type. */
    fun setColumn(field: SelectFieldState, column: FilterColumn, dialect: SqlDialect) {
        field.column = column.name
        if (SelectFunction.forKind(column.kind, dialect).none { field.function in it }) setFunction(field, SelectFunction.None)
    }

    /** The argument only carries over to a function that takes the same kind of one. */
    fun setFunction(field: SelectFieldState, function: SelectFunction) {
        if (function.argument != field.function.argument) field.argument.setTextAndPlaceCursorAtEnd("")
        field.function = function
    }
}

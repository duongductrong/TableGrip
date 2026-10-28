package dev.datagrok.app.table

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.core.filter.Arity
import dev.datagrok.core.filter.Combinator
import dev.datagrok.core.filter.FilterColumn
import dev.datagrok.core.filter.FilterCondition
import dev.datagrok.core.filter.FilterGroup
import dev.datagrok.core.filter.FilterNode
import dev.datagrok.core.filter.FilterOperator
import dev.datagrok.core.filter.FilterValues
import dev.datagrok.core.filter.RawCondition
import dev.datagrok.core.filter.ValueKind

enum class FilterMode { Builder, Sql }

/** Editable counterpart of a [FilterNode]: what the builder's rows are bound to. */
sealed class FilterNodeState(val id: Long) {
    var enabled by mutableStateOf(true)

    abstract fun toNode(): FilterNode
}

class ConditionState(id: Long, column: String, operator: FilterOperator) : FilterNodeState(id) {
    var column by mutableStateOf(column)
    var operator by mutableStateOf(operator)
    val value = TextFieldState()
    val value2 = TextFieldState()
    var caseSensitive by mutableStateOf(false)

    /** Still waiting for the value its operator needs. */
    val isBlank: Boolean
        get() = operator.arity != Arity.None && value.text.isEmpty() && value2.text.isEmpty()

    override fun toNode() =
        FilterCondition(id, column, operator, value.text.toString(), value2.text.toString(), caseSensitive, enabled)
}

class GroupState(id: Long, combinator: Combinator = Combinator.All) : FilterNodeState(id) {
    var combinator by mutableStateOf(combinator)
    var negated by mutableStateOf(false)
    val children = mutableStateListOf<FilterNodeState>()

    override fun toNode(): FilterGroup = FilterGroup(id, combinator, children.map { it.toNode() }, negated, enabled)
}

class RawState(id: Long, sql: String) : FilterNodeState(id) {
    val sql = TextFieldState(sql)

    override fun toNode() = RawCondition(id, sql.text.toString(), enabled)
}

/** The structured filter of a table viewer: a tree of groups, typed conditions and SQL snippets. */
class FilterBuilderState {
    private var nextId = 1L
    val root = GroupState(0)

    val isEmpty: Boolean
        get() = root.children.isEmpty()

    fun toNode(): FilterGroup = root.toNode()

    fun addCondition(parent: GroupState, column: FilterColumn?): ConditionState =
        ConditionState(nextId++, column?.name.orEmpty(), FilterOperator.defaultFor(column?.kind ?: ValueKind.Text)).also { parent.children += it }

    /** A new group starts with one condition, so it's never an empty box. */
    fun addGroup(parent: GroupState, column: FilterColumn?): GroupState =
        GroupState(nextId++, if (parent.combinator == Combinator.All) Combinator.Any else Combinator.All).also {
            parent.children += it
            addCondition(it, column)
        }

    fun addRaw(parent: GroupState, sql: String = ""): RawState = RawState(nextId++, sql).also { parent.children += it }

    fun remove(node: FilterNodeState, from: GroupState = root): Boolean {
        if (from.children.remove(node)) return true
        return from.children.filterIsInstance<GroupState>().any { remove(node, it) }
    }

    fun clear() = root.children.clear()

    /** Picks a column, keeping the operator and values when they still apply to its type. */
    fun setColumn(condition: ConditionState, column: FilterColumn, regex: Boolean) {
        condition.column = column.name
        if (FilterOperator.forKind(column.kind, regex).none { condition.operator in it }) condition.operator = FilterOperator.defaultFor(column.kind)
    }

    fun setOperator(condition: ConditionState, operator: FilterOperator) {
        // What was typed carries over (a value becomes a one-item list); only "between" has a second bound.
        condition.operator = operator
        if (operator.arity != Arity.Two) condition.value2.setTextAndPlaceCursorAtEnd("")
    }

    /** Adds `column = value` from a grid cell, as the right kind of condition for the column's type. */
    fun addFromValue(column: FilterColumn, value: String?) {
        root.children.removeAll { it is ConditionState && it.isBlank }
        val operator =
            when {
                value == null -> FilterOperator.IsNull
                column.kind == ValueKind.Boolean ->
                    when (FilterValues.boolean(value)) {
                        true -> FilterOperator.IsTrue
                        false -> FilterOperator.IsFalse
                        null -> FilterOperator.IsNotNull
                    }
                column.kind == ValueKind.Binary -> FilterOperator.IsNotNull
                else -> FilterOperator.Equals
            }
        val condition = addCondition(root, column)
        condition.operator = operator
        if (operator.arity == Arity.One && value != null) condition.value.setTextAndPlaceCursorAtEnd(value)
    }

    /** The builder holds this SQL as one of its conditions already. */
    fun hasRaw(sql: String): Boolean = root.children.any { it is RawState && it.sql.text.toString().trim() == sql.trim() }
}

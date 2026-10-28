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

    /** Adds to [parent], right after [after] when given (e.g. the row being edited), else at the end. */
    fun addCondition(parent: GroupState, column: FilterColumn?, after: FilterNodeState? = null): ConditionState =
        insert(parent, ConditionState(nextId++, column?.name.orEmpty(), FilterOperator.defaultFor(column?.kind ?: ValueKind.Text)), after)

    /** A new group starts with one condition, so it's never an empty box. */
    fun addGroup(parent: GroupState, column: FilterColumn?, after: FilterNodeState? = null): GroupState =
        insert(parent, GroupState(nextId++, if (parent.combinator == Combinator.All) Combinator.Any else Combinator.All), after).also {
            addCondition(it, column)
        }

    fun addRaw(parent: GroupState, sql: String = "", after: FilterNodeState? = null): RawState = insert(parent, RawState(nextId++, sql), after)

    private fun <T : FilterNodeState> insert(parent: GroupState, node: T, after: FilterNodeState?): T {
        val at = parent.children.indexOf(after)
        if (at < 0) parent.children += node else parent.children.add(at + 1, node)
        return node
    }

    /** The group holding [node], or null for the root or a node that isn't in the tree. */
    fun parentOf(node: FilterNodeState, from: GroupState = root): GroupState? {
        if (node in from.children) return from
        return from.children.filterIsInstance<GroupState>().firstNotNullOfOrNull { parentOf(node, it) }
    }

    /** Conditions and SQL conditions in the order they're shown, nested groups included: what keyboard navigation steps through. */
    fun rows(from: GroupState = root): List<FilterNodeState> = from.children.flatMap { if (it is GroupState) rows(it) else listOf(it) }

    /** Copies [node] (a whole group with its contents) right after it. */
    fun duplicate(node: FilterNodeState): FilterNodeState? {
        val parent = parentOf(node) ?: return null
        return insert(parent, copy(node), node)
    }

    private fun copy(node: FilterNodeState): FilterNodeState =
        when (node) {
            is ConditionState ->
                ConditionState(nextId++, node.column, node.operator).also {
                    it.value.setTextAndPlaceCursorAtEnd(node.value.text.toString())
                    it.value2.setTextAndPlaceCursorAtEnd(node.value2.text.toString())
                    it.caseSensitive = node.caseSensitive
                }
            is RawState -> RawState(nextId++, node.sql.text.toString())
            is GroupState ->
                GroupState(nextId++, node.combinator).also { group ->
                    group.negated = node.negated
                    node.children.forEach { group.children += copy(it) }
                }
        }.also { it.enabled = node.enabled }

    /** Moves [node] up ([by] < 0) or down within its group; false at the group's edge. */
    fun move(node: FilterNodeState, by: Int): Boolean {
        val siblings = parentOf(node)?.children ?: return false
        val from = siblings.indexOf(node)
        val to = from + by
        if (to !in siblings.indices) return false
        siblings.removeAt(from)
        siblings.add(to, node)
        return true
    }

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

    /** Adds `column = value` (or `column ≠ value` to [exclude] it) from a grid cell, as the right kind of condition for the column's type. */
    fun addFromValue(column: FilterColumn, value: String?, exclude: Boolean = false) {
        root.children.removeAll { it is ConditionState && it.isBlank }
        val operator =
            when {
                value == null -> if (exclude) FilterOperator.IsNotNull else FilterOperator.IsNull
                column.kind == ValueKind.Boolean ->
                    when (FilterValues.boolean(value)) {
                        true -> if (exclude) FilterOperator.IsFalse else FilterOperator.IsTrue
                        false -> if (exclude) FilterOperator.IsTrue else FilterOperator.IsFalse
                        null -> if (exclude) FilterOperator.IsNull else FilterOperator.IsNotNull
                    }
                column.kind == ValueKind.Binary -> if (exclude) FilterOperator.IsNull else FilterOperator.IsNotNull
                else -> if (exclude) FilterOperator.NotEquals else FilterOperator.Equals
            }
        val condition = addCondition(root, column)
        condition.operator = operator
        if (operator.arity == Arity.One && value != null) condition.value.setTextAndPlaceCursorAtEnd(value)
    }

    /** The builder holds this SQL as one of its conditions already. */
    fun hasRaw(sql: String): Boolean = root.children.any { it is RawState && it.sql.text.toString().trim() == sql.trim() }
}

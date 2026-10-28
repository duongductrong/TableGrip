package dev.datagrok.app.table

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.datagrok.core.filter.Combinator
import dev.datagrok.core.filter.FilterColumn
import dev.datagrok.core.filter.FilterCondition
import dev.datagrok.core.filter.FilterGroup
import dev.datagrok.core.filter.FilterOperator
import dev.datagrok.core.filter.FilterValues
import dev.datagrok.core.filter.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterBuilderStateTest {
    private val name = FilterColumn("name", "text", ValueKind.Text)
    private val total = FilterColumn("total", "numeric", ValueKind.Number)
    private val active = FilterColumn("active", "bool", ValueKind.Boolean)

    @Test
    fun `new conditions start with an operator suited to the column`() {
        val builder = FilterBuilderState()
        assertEquals(FilterOperator.Contains, builder.addCondition(builder.root, name).operator)
        assertEquals(FilterOperator.Equals, builder.addCondition(builder.root, total).operator)
        assertEquals(FilterOperator.IsTrue, builder.addCondition(builder.root, active).operator)
    }

    @Test
    fun `changing the column keeps an operator that still applies`() {
        val builder = FilterBuilderState()
        val condition = builder.addCondition(builder.root, name)
        condition.operator = FilterOperator.Equals
        builder.setColumn(condition, total, regex = true)
        assertEquals(FilterOperator.Equals, condition.operator)
        builder.setColumn(condition, active, regex = true)
        assertEquals(FilterOperator.IsTrue, condition.operator)
    }

    @Test
    fun `groups start with a condition and combine the other way`() {
        val builder = FilterBuilderState()
        val group = builder.addGroup(builder.root, name)
        assertEquals(Combinator.Any, group.combinator)
        assertEquals(1, group.children.size)
        val tree = builder.toNode()
        assertEquals(FilterGroup::class, tree.children.single()::class)
        assertTrue(builder.remove(group.children.single()))
        assertTrue(group.children.isEmpty())
    }

    @Test
    fun `filter by value picks the operator for the column type and drops blank rows`() {
        val builder = FilterBuilderState()
        builder.addCondition(builder.root, name) // blank: no value yet
        builder.addFromValue(name, "alice")
        builder.addFromValue(total, null)
        builder.addFromValue(active, "f")
        val conditions = builder.toNode().children.map { it as FilterCondition }
        assertEquals(
            listOf(Triple("name", FilterOperator.Equals, "alice"), Triple("total", FilterOperator.IsNull, ""), Triple("active", FilterOperator.IsFalse, "")),
            conditions.map { Triple(it.column, it.operator, it.value) },
        )
    }

    @Test
    fun `excluding a value picks the opposite operator`() {
        val builder = FilterBuilderState()
        builder.addFromValue(name, "alice", exclude = true)
        builder.addFromValue(total, null, exclude = true)
        builder.addFromValue(active, "t", exclude = true)
        val conditions = builder.toNode().children.map { it as FilterCondition }
        assertEquals(
            listOf(Triple("name", FilterOperator.NotEquals, "alice"), Triple("total", FilterOperator.IsNotNull, ""), Triple("active", FilterOperator.IsFalse, "")),
            conditions.map { Triple(it.column, it.operator, it.value) },
        )
    }

    @Test
    fun `rows are added after the current one, duplicated and moved within their group`() {
        val builder = FilterBuilderState()
        val a = builder.addCondition(builder.root, name)
        val c = builder.addCondition(builder.root, total)
        val b = builder.addCondition(builder.root, active, after = a)
        assertEquals(listOf(a, b, c), builder.root.children.toList())

        a.value.setTextAndPlaceCursorAtEnd("x")
        a.caseSensitive = true
        a.enabled = false
        val copy = builder.duplicate(a) as ConditionState
        assertEquals(listOf(a, copy, b, c), builder.root.children.toList())
        assertEquals(a.toNode().copy(id = copy.id), copy.toNode())

        assertTrue(builder.move(c, -1))
        assertEquals(listOf(a, copy, c, b), builder.root.children.toList())
        assertFalse(builder.move(a, -1))
    }

    @Test
    fun `groups are duplicated deeply and rows are listed in display order`() {
        val builder = FilterBuilderState()
        val first = builder.addCondition(builder.root, name)
        val group = builder.addGroup(builder.root, total)
        val raw = builder.addRaw(group, "x > 1")
        val last = builder.addCondition(builder.root, active)
        assertEquals(listOf(first, group.children[0], raw, last), builder.rows())
        assertEquals(group, builder.parentOf(raw))

        val copy = builder.duplicate(group) as GroupState
        assertEquals(group.toNode().children.size, copy.children.size)
        assertEquals("x > 1", (copy.children[1] as RawState).sql.text.toString())
        assertTrue(copy.children.none { it in group.children })
        assertEquals(6, builder.rows().size)
    }

    @Test
    fun `list editing helpers respect quotes`() {
        assertEquals(8, lastSeparator("a, \"b,c\", d"))
        assertEquals("d", lastToken("a, \"b,c\", d"))
        assertEquals("x", lastToken("a, 'x"))
        assertEquals("\"a, b\"", quoteListItem("a, b"))
        assertEquals("\"NULL\"", quoteListItem("NULL"))
        assertEquals("plain", quoteListItem("plain"))
        assertEquals(listOf("a, b", "NULL", "plain"), FilterValues.parseList(listOf("a, b", "NULL", "plain").joinToString(", ", transform = ::quoteListItem)))
    }
}

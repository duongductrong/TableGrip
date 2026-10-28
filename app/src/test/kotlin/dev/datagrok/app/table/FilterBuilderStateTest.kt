package dev.datagrok.app.table

import dev.datagrok.core.filter.Combinator
import dev.datagrok.core.filter.FilterColumn
import dev.datagrok.core.filter.FilterCondition
import dev.datagrok.core.filter.FilterGroup
import dev.datagrok.core.filter.FilterOperator
import dev.datagrok.core.filter.FilterValues
import dev.datagrok.core.filter.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
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

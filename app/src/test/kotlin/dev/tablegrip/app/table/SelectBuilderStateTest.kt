package dev.tablegrip.app.table

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.ProjectionCompiler
import dev.tablegrip.core.filter.SelectExpression
import dev.tablegrip.core.filter.SelectField
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectBuilderStateTest {
    private val id = FilterColumn("id", "int4", ValueKind.Number)
    private val name = FilterColumn("name", "text", ValueKind.Text)
    private val total = FilterColumn("total", "numeric", ValueKind.Number)
    private val columns = listOf(id, name, total)

    private fun compile(select: SelectBuilderState) = ProjectionCompiler(SqlDialect.PostgreSQL, columns).compile(select.toProjection())

    @Test
    fun `reset lists every column, which is still SELECT star`() {
        val select = SelectBuilderState()
        select.reset(columns)
        assertEquals(listOf("id", "name", "total"), select.toProjection().items.map { (it as SelectField).column })
        assertNull(compile(select).projection)
        assertTrue(compile(select).editable)
    }

    @Test
    fun `hiding and reordering keep rows editable`() {
        val select = SelectBuilderState()
        select.reset(columns)
        select.items[1].enabled = false
        assertTrue(select.move(select.items[2], -2))
        assertFalse(select.move(select.items[0], -1))
        assertEquals("\"total\", \"id\"", compile(select).projection?.clause)
        assertTrue(compile(select).editable)
        select.showAll(false)
        assertEquals(setOf(0L), compile(select).issues.keys)
    }

    @Test
    fun `a column can show twice, as is and through a function`() {
        val select = SelectBuilderState()
        select.reset(listOf(name))
        val copy = select.duplicate(select.items[0]) as SelectFieldState
        select.setFunction(copy, SelectFunction.Upper)
        copy.alias.setTextAndPlaceCursorAtEnd("shout")
        assertEquals("\"name\", UPPER(\"name\") AS \"shout\"", compile(select).projection?.clause)
        assertFalse(compile(select).editable)
    }

    @Test
    fun `changing the column drops a function that no longer applies`() {
        val select = SelectBuilderState()
        val field = select.addField(name)
        select.setFunction(field, SelectFunction.Coalesce)
        field.argument.setTextAndPlaceCursorAtEnd("-")
        select.setColumn(field, total, SqlDialect.PostgreSQL)
        assertEquals(SelectFunction.Coalesce, field.function)
        select.setFunction(field, SelectFunction.Upper)
        select.setColumn(field, id, SqlDialect.PostgreSQL)
        assertEquals(SelectFunction.None, field.function)
    }

    @Test
    fun `an argument only carries over to a function taking the same kind`() {
        val select = SelectBuilderState()
        val field = select.addField(total)
        select.setFunction(field, SelectFunction.Round)
        field.argument.setTextAndPlaceCursorAtEnd("2")
        select.setFunction(field, SelectFunction.Abs)
        assertEquals("", field.argument.text.toString())
    }

    @Test
    fun `expressions go in after the row being edited`() {
        val select = SelectBuilderState()
        select.reset(columns)
        select.addExpression("total * 2", after = select.items[0])
        assertEquals(SelectExpression::class, select.toProjection().items[1]::class)
        select.distinct = true
        assertTrue(compile(select).projection!!.clause.startsWith("DISTINCT \"id\", total * 2 AS"))
    }
}

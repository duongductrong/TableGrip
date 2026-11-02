package dev.tablegrip.app.table

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.ValueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColumnDragStateTest {
    private val builder = FilterBuilderState()
    private val condition = builder.addCondition(builder.root, FilterColumn("id", "INTEGER", ValueKind.Number))
    private val group = builder.addGroup(builder.root, FilterColumn("id", "INTEGER", ValueKind.Number))
    private val drops = mutableListOf<Pair<String, ColumnDrop>>()
    private val drag =
        ColumnDragState { column, drop -> drops += column to drop }.apply {
            register(builder.root, ColumnDrop.Into(builder.root), Rect(0f, 0f, 1000f, 300f))
            register("panel", null, Rect(0f, 40f, 200f, 260f))
            register(condition, ColumnDrop.Onto(condition), Rect(210f, 40f, 1000f, 70f))
            register(group, ColumnDrop.Into(group), Rect(210f, 80f, 1000f, 200f))
        }

    @Test
    fun `the smallest target under the pointer takes the drop`() {
        drag.start("status", Offset(500f, 50f))
        assertEquals(ColumnDrop.Onto(condition), drag.hovered)
        drag.move("status", Offset(500f, 150f))
        assertEquals(ColumnDrop.Into(group), drag.hovered)
        drag.move("status", Offset(500f, 280f))
        drag.drop()
        assertEquals(listOf<Pair<String, ColumnDrop>>("status" to ColumnDrop.Into(builder.root)), drops)
        assertNull(drag.dragged)
    }

    @Test
    fun `dropping back on the column list or outside does nothing`() {
        drag.start("status", Offset(100f, 100f))
        assertNull(drag.hovered)
        drag.drop()
        drag.move("status", Offset(2000f, 2000f))
        drag.drop("status")
        assertEquals(emptyList<Pair<String, ColumnDrop>>(), drops)
    }
}

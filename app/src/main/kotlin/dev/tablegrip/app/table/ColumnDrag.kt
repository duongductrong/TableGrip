package dev.tablegrip.app.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.grid.ColumnDragOut
import dev.tablegrip.app.ui.LocalIslands
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What a column dropped on the filter builder does. */
internal sealed interface ColumnDrop {
    /** Adds a condition on the column to [group]. */
    data class Into(val group: GroupState) : ColumnDrop

    /** Points [condition] at the column instead, keeping its operator and value when they still apply. */
    data class Onto(val condition: ConditionState) : ColumnDrop
}

/**
 * A column being dragged from the builder's column list or out of the grid's header. Groups and conditions register
 * where they are; the smallest one under the pointer takes the drop, so a condition wins over the group around it.
 * A target registered with no drop (the column list itself) swallows it. Positions are in the window's coordinates.
 */
internal class ColumnDragState(private val onDrop: (String, ColumnDrop) -> Unit) : ColumnDragOut {
    var dragged by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    private val targets = mutableStateMapOf<Any, Pair<ColumnDrop?, Rect>>()

    val hovered: ColumnDrop?
        get() =
            if (dragged == null) null
            else targets.values.filter { it.second.contains(pointer) }.minByOrNull { it.second.width * it.second.height }?.first

    fun register(key: Any, drop: ColumnDrop?, bounds: Rect) {
        targets[key] = drop to bounds
    }

    fun unregister(key: Any) {
        targets.remove(key)
    }

    fun start(column: String, at: Offset) {
        dragged = column
        pointer = at
    }

    override fun move(column: String, at: Offset) {
        if (dragged == null) start(column, at) else pointer = at
    }

    override fun drop(column: String) = drop()

    fun drop() {
        val column = dragged
        val target = hovered
        dragged = null
        if (column != null && target != null) onDrop(column, target)
    }

    override fun cancel() {
        dragged = null
    }
}

/** The dragged column following the pointer, saying what dropping it would do. [origin] is the view's position in the window. */
@Composable
internal fun ColumnDragGhost(drag: ColumnDragState, root: GroupState, origin: Offset) {
    val column = drag.dragged ?: return
    val gap = with(LocalDensity.current) { 12.dp.toPx() }
    val at = drag.pointer - origin + Offset(gap, gap / 2)
    val action =
        when (val target = drag.hovered) {
            is ColumnDrop.Into -> if (target.group === root) "Add condition" else "Add to group"
            is ColumnDrop.Onto -> "Use in this condition"
            null -> null
        }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .shadow(6.dp, shape)
            .background(JewelTheme.globalColors.panelBackground, shape)
            .border(1.dp, if (action != null) LocalIslands.current.accent else JewelTheme.globalColors.borders.normal, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.Nodes.DataColumn, null)
        Text(column, fontWeight = FontWeight.SemiBold)
        if (action != null) {
            Icon(AllIconsKeys.General.ArrowRight, null)
            Text(action)
        } else {
            Text("Drop on a condition or group of the filter", color = JewelTheme.globalColors.text.info)
        }
    }
}

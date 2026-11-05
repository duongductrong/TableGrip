package dev.tablegrip.app.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.core.connection.ConnectionConfig
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.roundToInt

/** Where dragged connections can land. */
internal sealed interface DropTarget {
    data class Group(val name: String) : DropTarget

    data object Favorites : DropTarget

    /** Out of any group. */
    data object TopLevel : DropTarget

    /** A group that doesn't exist yet: its name is asked for on drop. */
    data object NewGroup : DropTarget
}

/**
 * Dragging connections in the explorer onto a group, a connection already in one (joining its group), Favorites, or
 * the "No group" / "New group" zones shown at the bottom while dragging. Rows register where they are; the pointer
 * decides which one it's over. Positions are in the window's coordinates.
 */
internal class ConnectionDragState(private val app: AppState) {
    var dragged by mutableStateOf<List<ConnectionConfig>>(emptyList())
        private set

    var pointer by mutableStateOf(Offset.Zero)
        private set

    private val targets = mutableStateMapOf<Any, Pair<Rect, DropTarget>>()

    val dragging: Boolean
        get() = dragged.isNotEmpty()

    /** The target under the pointer, unless dropping there would change nothing. */
    val hovered: DropTarget?
        get() {
            if (!dragging) return null
            val target = targets.values.firstOrNull { (bounds, _) -> bounds.contains(pointer) }?.second ?: return null
            return target.takeIf { changes(it) }
        }

    private fun changes(target: DropTarget): Boolean =
        when (target) {
            is DropTarget.Group -> dragged.any { it.favorite || it.group.trim() != target.name }
            DropTarget.Favorites -> dragged.any { !it.favorite }
            DropTarget.TopLevel -> dragged.any { it.favorite || it.group.isNotBlank() }
            DropTarget.NewGroup -> true
        }

    /** Rows are grown by [slack] pixels top and bottom, so the gaps between them don't drop nothing. */
    fun register(key: Any, bounds: Rect, target: DropTarget, slack: Float = 0f) {
        targets[key] = Rect(bounds.left, bounds.top - slack, bounds.right, bounds.bottom + slack) to target
    }

    fun unregister(key: Any) {
        targets.remove(key)
    }

    /** Starts dragging [config], with the other selected connections if it is one of them. */
    fun start(config: ConnectionConfig, at: Offset) {
        val selected = app.explorer.treeState.selectedKeys.filterIsInstance<ConnectionKey>().map { it.id }.toSet()
        dragged = if (config.id in selected) app.connections.profiles.filter { it.id in selected } else listOf(config)
        pointer = at
    }

    /** Moves the pointer to [to], in window coordinates. */
    fun moveTo(to: Offset) {
        pointer = to
    }

    fun drop() {
        val configs = dragged
        when (val target = hovered) {
            is DropTarget.Group -> app.connections.moveToGroup(configs, target.name)
            DropTarget.Favorites -> app.connections.addToFavorites(configs)
            DropTarget.TopLevel -> app.connections.moveToGroup(configs, "")
            DropTarget.NewGroup -> app.connections.groupRequest = dev.tablegrip.app.connections.GroupRequest(configs, newGroup = true)
            null -> Unit
        }
        cancel()
    }

    fun cancel() {
        dragged = emptyList()
    }
}

/** Makes a row a place to drop connections on while it's on screen. */
@Composable
internal fun Modifier.dropTarget(drag: ConnectionDragState, key: Any, target: DropTarget): Modifier {
    val slack = with(LocalDensity.current) { 3.dp.toPx() }
    return onGloballyPositioned { drag.register(key, it.boundsInRoot(), target, slack) }
}

@Composable
internal fun ForgetDropTarget(drag: ConnectionDragState, key: Any) {
    DisposableEffect(key) { onDispose { drag.unregister(key) } }
}

/** Lets a connection row be dragged; a click still selects and opens it, since dragging starts after a few pixels. */
@Composable
internal fun Modifier.draggableConnection(drag: ConnectionDragState, config: ConnectionConfig): Modifier {
    val origin = remember { arrayOf(Offset.Zero) }
    val current by rememberUpdatedState(config)
    return onGloballyPositioned { origin[0] = it.positionInRoot() }
        .pointerInput(config.id) {
            detectDragGestures(
                onDragStart = { drag.start(current, origin[0] + it) },
                // Absolute rather than summed deltas, which drift from the real pointer.
                onDrag = { change, _ ->
                    change.consume()
                    drag.moveTo(origin[0] + change.position)
                },
                onDragEnd = drag::drop,
                onDragCancel = drag::cancel,
            )
        }
}

/** Highlights a row the dragged connections would land in. */
@Composable
internal fun Modifier.dropHighlight(active: Boolean): Modifier =
    if (!active) this
    else background(JewelTheme.globalColors.outlines.focused.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
        .border(1.dp, JewelTheme.globalColors.outlines.focused, RoundedCornerShape(4.dp))

/** The "No group" and "New group" zones shown under the tree while dragging. */
@Composable
internal fun DropZones(drag: ConnectionDragState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropZone(drag, "zone:top", DropTarget.TopLevel, "No group", Modifier.weight(1f))
        DropZone(drag, "zone:new", DropTarget.NewGroup, "＋ New group", Modifier.weight(1f))
    }
}

@Composable
private fun DropZone(drag: ConnectionDragState, key: String, target: DropTarget, label: String, modifier: Modifier) {
    ForgetDropTarget(drag, key)
    val active = drag.hovered == target
    val color = if (active) JewelTheme.globalColors.outlines.focused else JewelTheme.globalColors.text.info.copy(alpha = 0.6f)
    Box(
        modifier
            .height(44.dp)
            .dropTarget(drag, key, target)
            .background(if (active) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
            .drawBehind {
                val stroke = 1.dp.toPx()
                val radius = 6.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = stroke, pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info)
    }
}

/** What follows the pointer: the dragged connection, or how many. [origin] is the panel's position in the window. */
@Composable
internal fun DragGhost(drag: ConnectionDragState, origin: Offset) {
    val first = drag.dragged.firstOrNull() ?: return
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.toPx() }
    val at = drag.pointer - origin + Offset(gap, gap / 2)
    Row(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground, RoundedCornerShape(6.dp))
            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(first, size = 16)
        Text(if (drag.dragged.size == 1) first.name else "${drag.dragged.size} connections")
        when (val target = drag.hovered) {
            is DropTarget.Group -> Arrow(target.name)
            DropTarget.Favorites -> Arrow("Favorites")
            DropTarget.TopLevel -> Arrow("no group")
            DropTarget.NewGroup -> Arrow("new group")
            null -> Unit
        }
    }
}

@Composable
private fun Arrow(target: String) {
    Icon(AllIconsKeys.General.ArrowRight, null)
    Text(target, color = JewelTheme.globalColors.text.info)
}

@Composable
internal fun rememberConnectionDrag(app: AppState) = remember(app) { ConnectionDragState(app) }

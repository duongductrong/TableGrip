package dev.tablegrip.app.workspace

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a lazy row of tabs: the dragged tab follows the pointer within the row, trades places with a
 * neighbour once it covers that neighbour's middle, scrolls the row while pushed against an edge, and glides into its
 * slot on release.
 */
internal class TabDragState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    density: Float,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    /** Key of the tab being dragged, or still gliding back into its slot. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    /** True while the pointer holds the tab. */
    var isDragging by mutableStateOf(false)
        private set

    /** How far the dragged tab is drawn from its slot. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** How fast the row scrolls under the dragged tab, in pixels per frame: non-zero while it is pushed against an edge. */
    var scrollSpeed by mutableFloatStateOf(0f)
        private set

    private val edge = 32 * density
    private val maxScrollPerFrame = 10 * density
    private var settle: Job? = null

    /** The index the dragged tab was just moved to, until the row has been laid out with it there. */
    private var movedTo: Int? = null

    fun start(key: Any) {
        settle?.cancel()
        if (draggedKey != key) offset = 0f
        draggedKey = key
        isDragging = true
        movedTo = null
    }

    fun drag(delta: Float) {
        offset += delta
        followPointer()
    }

    fun end() {
        isDragging = false
        scrollSpeed = 0f
        settle = scope.launch {
            Animatable(offset).animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) { offset = value }
            draggedKey = null
        }
    }

    /** Scrolls the row while the dragged tab is pushed against one of its edges; returns once it no longer is. */
    suspend fun autoScroll() {
        while (isDragging && scrollSpeed != 0f) {
            // Scrolling moves the tab's slot; shifting the offset back keeps the tab under the pointer.
            offset += listState.scrollBy(scrollSpeed)
            withFrameNanos {}
            followPointer()
        }
    }

    private fun draggedItem(): LazyListItemInfo? {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey } ?: return null
        // The layout still shows the order from before the last move: wait for the next one.
        if (movedTo != null && item.index != movedTo) return null
        movedTo = null
        return item
    }

    /** Keeps the dragged tab inside the row and moves it past the neighbour it covers the middle of. */
    private fun followPointer() {
        val item = draggedItem() ?: return
        val info = listState.layoutInfo
        val min = (info.viewportStartOffset - item.offset).toFloat()
        val max = (info.viewportEndOffset - item.size - item.offset).toFloat()
        offset = offset.coerceIn(min, max.coerceAtLeast(min))

        val start = item.offset + offset
        val end = start + item.size
        val push = when {
            start < info.viewportStartOffset + edge -> start - (info.viewportStartOffset + edge)
            end > info.viewportEndOffset - edge -> end - (info.viewportEndOffset - edge)
            else -> 0f
        }
        scrollSpeed = when {
            push < 0 && listState.canScrollBackward || push > 0 && listState.canScrollForward -> push / edge * maxScrollPerFrame
            else -> 0f
        }

        val next = info.visibleItemsInfo.firstOrNull { it.index == item.index + 1 }
        val previous = info.visibleItemsInfo.firstOrNull { it.index == item.index - 1 }
        val target = when {
            next != null && end > next.offset + next.size / 2f -> next
            previous != null && start < previous.offset + previous.size / 2f -> previous
            else -> return
        }
        // Where the dragged tab's slot lands once the two trade places.
        val slot = if (target.index > item.index) target.offset + target.size - item.size else target.offset
        // A lazy list follows its first visible item to its new index; pin the scroll position instead.
        val first = listState.firstVisibleItemIndex
        if (item.index == first || target.index == first) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        onMove(item.index, target.index)
        movedTo = target.index
        offset += item.offset - slot
    }
}

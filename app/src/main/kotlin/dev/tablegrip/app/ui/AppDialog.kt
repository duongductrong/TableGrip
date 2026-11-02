package dev.tablegrip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * A dialog whose title bar follows the app theme, like the main window's, instead of the OS's light or dark appearance.
 * The title bar drags the dialog around like a native one: double-click and snapping work too, and anything marked with
 * [DialogTitleBarScope.interactive] stays clickable instead. Falls back to the native title bar where the JetBrains
 * Runtime can't replace it (e.g. Linux), in which case [titleBar] isn't shown.
 *
 * @param state hoist it to keep the dialog's position and size across reopenings.
 * @param closeOnEscape whether Esc requests closing, once nothing inside (a popup, an editor, [onKeyEvent]) took it.
 * @param titleBar replaces the centered title, e.g. to add a search field or a help button.
 * @param inset gap between the content island and the dialog's edges; tighter than the main window's [IslandGap].
 *   Zero lays the content edge to edge, without an island.
 * @param content may use [androidx.compose.foundation.window.WindowDraggableArea] to drag from places other than the
 *   title bar.
 */
@Composable
internal fun AppDialog(
    onCloseRequest: () -> Unit,
    title: String,
    state: DialogState,
    resizable: Boolean = true,
    closeOnEscape: Boolean = true,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarHeight: Dp = DefaultTitleBarHeight,
    inset: Dp = DialogIslandGap,
    titleBar: @Composable DialogTitleBarScope.() -> Unit = { DialogTitle(title) },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        title = title,
        resizable = resizable,
        onKeyEvent = { onKeyEvent(it) || (closeOnEscape && isCloseKey(it)).also { close -> if (close) onCloseRequest() } },
    ) {
        FollowThemeAppearance(window)
        val customTitleBar = rememberCustomTitleBar(window, titleBarHeight)
        // Like the main window: the title bar sits on the frame and the content floats on it as an island.
        Column(Modifier.fillMaxSize().background(LocalIslands.current.frame)) {
            if (customTitleBar != null) DialogTitleBar(window, customTitleBar, titleBarHeight, titleBar)
            val body = Modifier.weight(1f).fillMaxWidth()
            if (inset == 0.dp) {
                Box(body.background(JewelTheme.globalColors.panelBackground)) { Zoomed { content() } }
            } else {
                // The native title bar (Linux) leaves no frame above the island, so give it the same gap there.
                val top = if (customTitleBar == null) inset else 0.dp
                // Concentric with the window's rounded corners, so the gap stays even around the curve.
                Island(body.padding(start = inset, top = top, end = inset, bottom = inset), RoundedCornerShape((DialogCornerRadius - inset).coerceAtLeast(4.dp))) { Zoomed { content() } }
            }
        }
    }
}

/** An [AppDialog] of a fixed initial [size], opening where the platform places dialogs. */
@Composable
internal fun AppDialog(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    resizable: Boolean = true,
    closeOnEscape: Boolean = true,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarHeight: Dp = DefaultTitleBarHeight,
    inset: Dp = DialogIslandGap,
    titleBar: @Composable DialogTitleBarScope.() -> Unit = { DialogTitle(title) },
    content: @Composable DialogWindowScope.() -> Unit,
) = AppDialog(onCloseRequest, title, rememberDialogState(size = size), resizable, closeOnEscape, onKeyEvent, titleBarHeight, inset, titleBar, content)

/** A bare Esc press; with modifiers it's left for shortcuts. */
internal fun isCloseKey(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
        !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed

private val DefaultTitleBarHeight = 32.dp

/** Gap between a dialog's content island and its edges. */
internal val DialogIslandGap = 4.dp

/** Roughly the OS's own corner radius for dialogs; the island's corners follow it at [DialogIslandGap] inside. */
private val DialogCornerRadius = 14.dp

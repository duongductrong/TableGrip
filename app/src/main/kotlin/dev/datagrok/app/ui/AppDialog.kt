package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
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
 * @param titleBar replaces the centered title, e.g. to add a search field or a help button.
 * @param content may use [androidx.compose.foundation.window.WindowDraggableArea] to drag from places other than the
 *   title bar.
 */
@Composable
internal fun AppDialog(
    onCloseRequest: () -> Unit,
    title: String,
    state: DialogState,
    resizable: Boolean = true,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarHeight: Dp = DefaultTitleBarHeight,
    titleBar: @Composable DialogTitleBarScope.() -> Unit = { DialogTitle(title) },
    content: @Composable DialogWindowScope.() -> Unit,
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        title = title,
        resizable = resizable,
        onKeyEvent = onKeyEvent,
    ) {
        val customTitleBar = rememberCustomTitleBar(window, titleBarHeight)
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
            if (customTitleBar != null) DialogTitleBar(window, customTitleBar, titleBarHeight, titleBar)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
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
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarHeight: Dp = DefaultTitleBarHeight,
    titleBar: @Composable DialogTitleBarScope.() -> Unit = { DialogTitle(title) },
    content: @Composable DialogWindowScope.() -> Unit,
) = AppDialog(onCloseRequest, title, rememberDialogState(size = size), resizable, onKeyEvent, titleBarHeight, titleBar, content)

private val DefaultTitleBarHeight = 32.dp

package dev.datagrok.app

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.app.ui.TitleBarView
import org.jetbrains.jewel.window.DecoratedWindow

fun main() = application {
    val appState = remember { AppState() }
    DatagrokTheme(isDark = appState.theme.isDark()) {
        DecoratedWindow(
            onCloseRequest = {
                appState.dispose()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
            title = "Datagrok",
            onKeyEvent = { handleWindowShortcut(appState, it) },
        ) {
            TitleBarView(appState)
            MainScreen(appState)
        }
    }
}

private val isMac = System.getProperty("os.name").startsWith("Mac")

/** ⌘/Ctrl+W closes the current tab. */
private fun handleWindowShortcut(app: AppState, event: KeyEvent): Boolean {
    val primary = if (isMac) event.isMetaPressed else event.isCtrlPressed
    if (event.type != KeyEventType.KeyDown || !primary) return false
    return when (event.key) {
        Key.W -> app.workspace.selected?.let { app.workspace.requestClose(it); true } ?: false
        else -> false
    }
}

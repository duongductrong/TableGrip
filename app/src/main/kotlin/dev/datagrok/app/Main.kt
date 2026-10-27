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
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.app.ui.TitleBarView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.styling.TitleBarStyle

fun main() = application {
    val appState = remember { AppState() }
    val textStyle = JewelTheme.createDefaultTextStyle()
    val editorStyle = JewelTheme.createEditorTextStyle()
    val isDark = appState.theme.isDark()

    val themeDefinition =
        if (isDark) {
            JewelTheme.darkThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        } else {
            JewelTheme.lightThemeDefinition(defaultTextStyle = textStyle, editorTextStyle = editorStyle)
        }

    val titleBarStyle =
        when {
            isDark -> TitleBarStyle.dark()
            appState.theme == AppTheme.LightWithLightHeader -> TitleBarStyle.lightWithLightHeader()
            else -> TitleBarStyle.light()
        }

    IntUiTheme(theme = themeDefinition, styling = ComponentStyling.default().decoratedWindow(titleBarStyle = titleBarStyle)) {
        DecoratedWindow(
            onCloseRequest = {
                appState.dispose()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
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

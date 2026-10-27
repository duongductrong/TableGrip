package dev.datagrok.app

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
    val appState = AppState.instance
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
        ) {
            TitleBarView(appState)
            MainScreen(appState)
        }
    }
}

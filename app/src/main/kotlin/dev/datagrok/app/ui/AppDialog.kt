package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.jetbrains.JBR
import dev.datagrok.app.grid.isMac
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.defaultTitleBarStyle

private val TitleBarHeight = 32.dp

/**
 * A dialog whose title bar follows the app theme, like the main window's, instead of the OS's light or dark appearance.
 * Falls back to the native title bar where the JetBrains Runtime can't replace it (e.g. Linux).
 */
@Composable
internal fun AppDialog(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    resizable: Boolean = true,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = size),
        title = title,
        resizable = resizable,
        onKeyEvent = onKeyEvent,
    ) {
        val custom = rememberCustomTitleBar(window)
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
            if (custom) DialogTitleBar(title)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** Hands the top of [dialog] to Compose, keeping the native window buttons; false when that isn't supported. */
@Composable
private fun rememberCustomTitleBar(dialog: ComposeDialog): Boolean {
    val dark = JewelTheme.defaultTitleBarStyle.colors.background.luminance() < 0.5f
    return remember(dialog, dark) {
        val supported = (isMac || System.getProperty("os.name").startsWith("Windows")) && JBR.isAvailable() && JBR.isWindowDecorationsSupported()
        if (supported) {
            val decorations = JBR.getWindowDecorations()
            val titleBar = decorations.createCustomTitleBar()
            titleBar.height = TitleBarHeight.value
            // Windows draws its caption buttons itself; tell it which variant fits the background.
            titleBar.putProperty("controls.dark", dark)
            decorations.setCustomTitleBar(dialog, titleBar)
        }
        supported
    }
}

@Composable
private fun DialogTitleBar(title: String) {
    val colors = JewelTheme.defaultTitleBarStyle.colors
    Box(Modifier.fillMaxWidth().height(TitleBarHeight).background(colors.background), contentAlignment = Alignment.Center) {
        // Clear of the traffic lights on macOS and the caption buttons on Windows.
        Text(title, Modifier.padding(horizontal = 80.dp), color = colors.content, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

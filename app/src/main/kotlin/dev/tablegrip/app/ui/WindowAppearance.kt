package dev.tablegrip.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import javax.swing.RootPaneContainer
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Gives the native window the app theme's light or dark appearance. The JVM opens every macOS window with the light
 * one whatever the theme, so macOS draws its light-mode rim, a bright line along the top edge, around a dark window.
 */
@Composable
internal fun FollowThemeAppearance(window: RootPaneContainer) {
    val isDark = JewelTheme.isDark
    LaunchedEffect(window, isDark) {
        window.rootPane.putClientProperty("apple.awt.windowAppearance", if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
    }
}

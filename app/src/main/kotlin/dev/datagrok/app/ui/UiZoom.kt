package dev.datagrok.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.isMac

/** Zoom of the whole interface, in the steps browsers and VS Code use; stored in Settings as a percentage. */
object UiZoom {
    const val Default = 100
    val Levels = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300)

    /** The level closest to [percent], for values typed into settings.json by hand. */
    fun snap(percent: Int): Int = Levels.minBy { kotlin.math.abs(it - percent) }

    /** The next level above (or, for a negative [direction], below) [percent], stopping at the ends. */
    fun step(percent: Int, direction: Int): Int =
        if (direction > 0) Levels.firstOrNull { it > percent } ?: Levels.last()
        else Levels.lastOrNull { it < percent } ?: Levels.first()
}

/**
 * Lays [content] out at the zoom in Settings → Appearance by scaling the density, so dp, sp and images all grow
 * together. Each window provides its own density, so this goes inside the window rather than around it.
 */
@Composable
fun Zoomed(content: @Composable () -> Unit) {
    val zoom = UiZoom.snap(LocalSettings.current.zoom)
    if (zoom == UiZoom.Default) return content()
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * zoom / 100f, base.fontScale), content = content)
}

/** ⌘+ / ⌘- / ⌘0 (the keypad's + and - too), from any window; true when [event] changed the zoom. */
fun handleZoomShortcut(app: AppState, event: KeyEvent): Boolean {
    val zoom = app.settings.zoom
    val next =
        when {
            Shortcuts.ZoomIn.matches(event) || Shortcuts.ZoomInAlt.matches(event) || isPrimaryOnly(event, Key.NumPadAdd) -> UiZoom.step(zoom, 1)
            Shortcuts.ZoomOut.matches(event) || isPrimaryOnly(event, Key.NumPadSubtract) -> UiZoom.step(zoom, -1)
            Shortcuts.ResetZoom.matches(event) -> UiZoom.Default
            else -> return false
        }
    if (next != zoom) {
        app.updateSettings { it.copy(zoom = next) }
        app.status = "Zoom $next%"
    }
    return true
}

private fun isPrimaryOnly(event: KeyEvent, key: Key): Boolean =
    event.type == KeyEventType.KeyDown && event.key == key && !event.isAltPressed &&
        (if (isMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed)

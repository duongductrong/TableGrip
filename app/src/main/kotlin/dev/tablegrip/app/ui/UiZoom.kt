package dev.tablegrip.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.isMac
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator

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
    setZoom(app, next)
    return true
}

/** Applies and saves [percent]; the status bar's [ZoomControl] shows it. */
fun setZoom(app: AppState, percent: Int) {
    if (percent != app.settings.zoom) app.updateSettings { it.copy(zoom = percent) }
}

/**
 * The zoom in the status bar, there only while it isn't 100%: a magnifier and the percentage, which open a menu of the
 * levels. Doubles as the notice that the shortcuts changed it.
 */
@Composable
fun ZoomControl(app: AppState) {
    val zoom = UiZoom.snap(app.settings.zoom)
    var open by remember { mutableStateOf(false) }
    if (zoom == UiZoom.Default && !open) return
    Box {
        Tooltip({ Text("Zoom: ${Shortcuts.ZoomIn.label} / ${Shortcuts.ZoomOut.label}, ${Shortcuts.ResetZoom.label} resets") }) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val info = JewelTheme.globalColors.text.info
                MagnifierIcon(plus = zoom >= UiZoom.Default, color = info)
                Text("$zoom%", color = info, maxLines = 1)
            }
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                UiZoom.Levels.asReversed().forEach { level ->
                    selectableItem(selected = level == zoom, onClick = {
                        open = false
                        setZoom(app, level)
                    }) { Text(if (level == UiZoom.Default) "$level% (default)" else "$level%") }
                }
                separator()
                selectableItem(selected = false, enabled = zoom != UiZoom.Default, onClick = {
                    open = false
                    setZoom(app, UiZoom.Default)
                }) { Text("Reset zoom (${Shortcuts.ResetZoom.label})") }
            }
        }
    }
}

/** A magnifier with + or − in the lens; Int UI's own zoom icons are circled signs, without the handle. */
@Composable
private fun MagnifierIcon(plus: Boolean, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val u = size.minDimension / 16f
        val stroke = 1.2f * u
        val center = Offset(6.5f * u, 6.5f * u)
        drawCircle(color, radius = 5f * u, center = center, style = Stroke(stroke))
        drawLine(color, Offset(10.2f * u, 10.2f * u), Offset(14f * u, 14f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
        drawLine(color, Offset(4.3f * u, 6.5f * u), Offset(8.7f * u, 6.5f * u), strokeWidth = stroke, cap = StrokeCap.Round)
        if (plus) drawLine(color, Offset(6.5f * u, 4.3f * u), Offset(6.5f * u, 8.7f * u), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

private fun isPrimaryOnly(event: KeyEvent, key: Key): Boolean =
    event.type == KeyEventType.KeyDown && event.key == key && !event.isAltPressed &&
        (if (isMac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed)

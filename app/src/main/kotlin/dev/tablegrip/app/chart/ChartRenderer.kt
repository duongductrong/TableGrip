package dev.tablegrip.app.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.tablegrip.core.chart.ChartFrame
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.OutputWStream
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGCanvas

/** The theme's colours a chart is drawn in, so it matches the window and an exported image matches what was shown. */
data class ChartColors(val background: Color, val text: Color, val secondaryText: Color, val grid: Color, val palette: List<Color>)

enum class ChartImageFormat(val extension: String) { Png("png"), Svg("svg") }

/** Draws [ChartFrame]s. Specs, suggestions and the agent never see how, so the drawing can be replaced on its own. */
interface ChartRenderer {
    @Composable
    fun Chart(frame: ChartFrame, colors: ChartColors, modifier: Modifier)

    /** The chart as an image file's bytes, [width] by [height] logical pixels; PNGs are drawn at twice that for sharpness. */
    fun export(frame: ChartFrame, colors: ChartColors, format: ChartImageFormat, width: Int, height: Int): ByteArray
}

/**
 * [ChartRenderer] drawing with Compose's canvas: the same [ChartPlot] draws on screen, into a PNG and into an SVG, so
 * exports match the screen. Hovering shows the values under the pointer.
 */
object CanvasChartRenderer : ChartRenderer {
    /**
     * Lays the chart out in the background ([ChartDispatcher]) and keeps showing the previous layout, dimmed, until the
     * new one is ready: a large chart only makes its own area wait, never the window. Hover marks and the tooltip live
     * on a layer of their own, so moving the pointer doesn't redraw the chart.
     */
    @Composable
    override fun Chart(frame: ChartFrame, colors: ChartColors, modifier: Modifier) {
        val density = LocalDensity.current
        var size by remember { mutableStateOf(IntSize.Zero) }
        var shown by remember { mutableStateOf<Laid?>(null) }
        val wanted = Laid.Key(frame, colors, size, density)
        LaunchedEffect(wanted) {
            if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
            // Resizing (a splitter being dragged) changes the size every frame: lay out once it settles.
            if (shown != null) delay(SettleMillis)
            val plot = withContext(ChartDispatcher) { ChartPlot(frame, colors, measurer(density), density, Size(size.width.toFloat(), size.height.toFloat())) }
            shown = Laid(wanted, plot)
        }
        val pointer = remember { mutableStateOf<Offset?>(null) }
        val current = shown
        val stale = current?.key != wanted
        Box(
            modifier.onSizeChanged { size = it }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        pointer.value = if (event.type == PointerEventType.Exit) null else event.changes.firstOrNull()?.position
                    }
                }
            }
        ) {
            if (current != null) {
                Canvas(Modifier.fillMaxSize().alpha(if (stale) 0.45f else 1f)) { current.plot.draw(this) }
                if (!stale) HoverLayer(current.plot, pointer, colors, size)
            }
            if (stale) CircularProgressIndicator(if (current == null) Modifier.align(Alignment.Center) else Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }

    /** A layout and what it was made for, to tell when it no longer matches what should be shown. */
    private class Laid(val key: Key, val plot: ChartPlot) {
        data class Key(val frame: ChartFrame, val colors: ChartColors, val size: IntSize, val density: Density)
    }

    /** Reads the pointer itself, so only this layer recomposes and redraws as it moves. */
    @Composable
    private fun HoverLayer(plot: ChartPlot, pointer: State<Offset?>, colors: ChartColors, area: IntSize) {
        val at = pointer.value
        val tooltip = remember(plot, at) { at?.let(plot::tooltipAt) } ?: return
        Canvas(Modifier.fillMaxSize()) { plot.drawHover(this, tooltip) }
        Tooltip(tooltip, colors, area)
    }

    /** Creating one costs more than a layout; its caches are synchronized, so background layouts share it. */
    private val fonts by lazy { createFontFamilyResolver() }

    /** A measurer of its own per layout (its cache isn't thread-safe): layouts run in the background, possibly two at a time. */
    private fun measurer(density: Density) = TextMeasurer(fonts, density, LayoutDirection.Ltr)

    @Composable
    private fun Tooltip(tooltip: ChartTooltip, colors: ChartColors, area: IntSize) {
        val density = LocalDensity.current
        var own by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(density) { 12.dp.roundToPx() }
        // Beside the pointer, flipped to the other side near the right or bottom edge.
        val x = (tooltip.anchor.x + gap).let { if (it + own.width > area.width) tooltip.anchor.x - gap - own.width else it }.roundToInt().coerceAtLeast(0)
        val y = (tooltip.anchor.y + gap).let { if (it + own.height > area.height) tooltip.anchor.y - gap - own.height else it }.roundToInt().coerceAtLeast(0)
        val shape = RoundedCornerShape(6.dp)
        Column(
            Modifier.offset { IntOffset(x, y) }
                .onSizeChanged { own = it }
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.grid, shape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tooltip.title, color = colors.text, fontWeight = FontWeight.SemiBold)
            tooltip.lines.take(MaxTooltipLines).forEach { (color, text) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(text, color = colors.text)
                }
            }
            if (tooltip.lines.size > MaxTooltipLines) Text("${tooltip.lines.size - MaxTooltipLines} more", color = colors.secondaryText)
        }
    }

    override fun export(frame: ChartFrame, colors: ChartColors, format: ChartImageFormat, width: Int, height: Int): ByteArray {
        val measurer = measurer(Density(1f))
        return when (format) {
            ChartImageFormat.Png -> {
                val scale = 2f
                Surface.makeRasterN32Premul((width * scale).roundToInt(), (height * scale).roundToInt()).use { surface ->
                    paint(surface.canvas, frame, colors, measurer, Density(scale), Size(width * scale, height * scale))
                    surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.bytes }
                }
            }
            ChartImageFormat.Svg -> {
                val out = ByteArrayOutputStream()
                val stream = OutputWStream(out)
                val canvas = SVGCanvas.make(Rect.makeWH(width.toFloat(), height.toFloat()), stream, convertTextToPaths = false, prettyXML = true)
                paint(canvas, frame, colors, measurer, Density(1f), Size(width.toFloat(), height.toFloat()))
                // The SVG is written out when its canvas closes.
                canvas.close()
                stream.close()
                out.toByteArray()
            }
        }
    }

    private fun paint(canvas: org.jetbrains.skia.Canvas, frame: ChartFrame, colors: ChartColors, measurer: TextMeasurer, density: Density, size: Size) {
        val plot = ChartPlot(frame, colors, measurer, density, size)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas.asComposeCanvas(), size) { plot.draw(this) }
    }

    private const val MaxTooltipLines = 12
    private const val SettleMillis = 120L
}

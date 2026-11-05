package dev.tablegrip.app.chart

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.tablegrip.core.chart.ChartFrame
import dev.tablegrip.core.chart.ChartPoint
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.ColumnKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the pointer is over: a title (the x value), a line per group with its colour, and the points to mark. */
data class ChartTooltip(val anchor: Offset, val title: String, val lines: List<Pair<Color, String>>, val marks: List<Pair<Color, Offset>> = emptyList())

/**
 * One frame laid out at one size: axes, legend and marks, in pixels. Drawing and hover read the same layout, so what
 * the pointer finds is what was drawn. [density] scales every length, which is how exports draw at twice the size.
 */
class ChartPlot(
    private val frame: ChartFrame,
    private val colors: ChartColors,
    private val measurer: TextMeasurer,
    private val density: Density,
    private val size: Size,
) {
    private val spec = frame.spec
    private fun dp(value: Float) = value * density.density
    private val textStyle = TextStyle(fontSize = 11.sp, color = colors.secondaryText)
    private val legendStyle = textStyle.copy(color = colors.text)

    /**
     * [text] laid out on one line. Cut to [MaxLabelChars] first: shaping costs the whole string even when only its start
     * fits, and values can be kilobytes of JSON.
     */
    private fun measure(text: String, style: TextStyle = textStyle, maxWidth: Float = Float.MAX_VALUE): TextLayoutResult =
        measurer.measure(
            shorten(text, MaxLabelChars),
            style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = if (maxWidth == Float.MAX_VALUE) Constraints() else Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
            density = density,
        )

    /** One line of text, which every row of labels and legend is. */
    private val textHeight = measure("Ag").size.height.toFloat()

    private val pie = spec.type == ChartType.Pie
    private val horizontal = spec.type == ChartType.Bar && spec.horizontal
    /** Bars always sit in bands, one per distinct x, whatever x holds. */
    private val banded = spec.type == ChartType.Bar || frame.xKind == ColumnKind.Category

    private val groupIndex: Map<String, Int> = frame.groups.withIndex().associate { (i, group) -> group to i }
    private val byX: Map<Any, List<ChartPoint>> = frame.points.groupBy { it.x }
    private val groupColor: Map<String, Color> = frame.groups.withIndex().associate { (i, group) -> group to colors.palette[i % colors.palette.size] }
    private val bands: List<Any> =
        when {
            frame.xKind == ColumnKind.Category -> frame.categories
            banded -> frame.points.map { it.x }.distinct().sortedBy { it as Double }
            else -> emptyList()
        }
    private val bandIndex: Map<Any, Int> = bands.withIndex().associate { (i, x) -> x to i }
    private val sliceColor: Map<Any, Color> = bands.withIndex().associate { (i, x) -> x to colors.palette[i % colors.palette.size] }

    private val legend: List<Pair<Color, String>> =
        when {
            pie -> bands.map { sliceColor.getValue(it) to label(it) }
            frame.groups.size > 1 -> frame.groups.map { groupColor.getValue(it) to it }
            else -> emptyList()
        }

    /** Legend entries laid out in rows under the plot. */
    private val legendRows: List<List<Pair<Pair<Color, String>, TextLayoutResult>>>
    private val legendHeight: Float

    private val valueAxis: Axis?
    private val xAxis: Axis?
    /** The plotting area, inside the axes. */
    val plot: Rect

    private val valueLabels: List<Pair<Double, TextLayoutResult>>
    private val xLabels: List<Pair<Float, TextLayoutResult>>
    private val xTitle: TextLayoutResult?
    private val yTitle: TextLayoutResult?

    init {
        val pad = dp(8f)
        val swatch = dp(8f)
        val gap = dp(14f)
        val lineHeight = textHeight + dp(4f)
        val rows = mutableListOf<MutableList<Pair<Pair<Color, String>, TextLayoutResult>>>()
        var rowWidth = 0f
        for (entry in legend) {
            val text = measure(entry.second, legendStyle, maxWidth = size.width / 2)
            val width = swatch + dp(5f) + text.size.width + gap
            if (rows.isEmpty() || rowWidth + width > size.width - 2 * pad) {
                rows += mutableListOf<Pair<Pair<Color, String>, TextLayoutResult>>()
                rowWidth = 0f
            }
            rows.last() += entry to text
            rowWidth += width
        }
        legendRows = rows.take(MaxLegendRows)
        legendHeight = if (legendRows.isEmpty()) 0f else legendRows.size * lineHeight + dp(6f)

        if (pie) {
            valueAxis = null
            xAxis = null
            valueLabels = emptyList()
            xLabels = emptyList()
            xTitle = null
            yTitle = null
            plot = Rect(pad, pad, size.width - pad, size.height - pad - legendHeight)
        } else {
            val values = frame.points.map { it.y }
            val zero = spec.type == ChartType.Bar || spec.type == ChartType.Area
            val valueLength = if (horizontal) size.width else size.height
            valueAxis = ChartScales.numeric(values.minOrNull() ?: 0.0, values.maxOrNull() ?: 1.0, count = (valueLength / dp(70f)).toInt().coerceIn(2, 8), zero = zero)
            xAxis =
                if (banded) null
                else {
                    val xs = frame.points.map { it.x as Double }
                    val low = xs.minOrNull() ?: 0.0
                    val high = xs.maxOrNull() ?: 1.0
                    val count = (size.width / dp(110f)).toInt().coerceIn(2, 10)
                    if (frame.xKind == ColumnKind.Temporal) ChartScales.temporal(low, high, count)
                    else ChartScales.numeric(low, high, count, zero = false)
                }
            xTitle = measure(frame.xLabel, maxWidth = size.width * 0.8f)
            yTitle = measure(frame.yLabel, maxWidth = size.height * 0.8f)
            val valueTitle = yTitle
            val categoryTitle = xTitle
            valueLabels = valueAxis.ticks.map { (value, text) -> value to measure(text) }
            val bandLabels = bands.map { measure(label(it), maxWidth = if (horizontal) size.width * 0.3f else dp(160f)) }
            // Left: the vertical axis's labels and its rotated title; bottom: the horizontal axis's labels, title and the legend.
            val leftLabels = if (horizontal) bandLabels.maxOfOrNull { it.size.width } ?: 0 else valueLabels.maxOfOrNull { it.second.size.width } ?: 0
            val left = pad + (if (horizontal) categoryTitle else valueTitle).size.height + dp(6f) + leftLabels + dp(6f)
            val tickHeight = textHeight
            val bottom = pad + legendHeight + (if (horizontal) valueTitle else categoryTitle).size.height + dp(4f) + tickHeight + dp(6f)
            val right = size.width - pad - dp(12f)
            plot = Rect(left, pad + dp(4f), right.coerceAtLeast(left + 1), (size.height - bottom).coerceAtLeast(pad + dp(5f)))

            xLabels =
                when {
                    horizontal -> bands.indices.map { i -> bandCenter(i) to bandLabels[i] }
                    banded -> {
                        // Every k-th band's label, so neighbours don't overlap.
                        val widest = bandLabels.maxOfOrNull { it.size.width } ?: 0
                        val step = ceil((widest + dp(8f)) / bandWidth()).toInt().coerceAtLeast(1)
                        bands.indices.filter { it % step == 0 }.map { i -> bandCenter(i) to bandLabels[i] }
                    }
                    else -> xAxis!!.ticks.map { (value, text) -> (plot.left + xAxis.fraction(value) * plot.width).toFloat() to measure(text) }
                }
        }
    }

    private fun label(x: Any): String =
        when (x) {
            is String -> shorten(x, MaxTooltipChars)
            is Double -> if (frame.xKind == ColumnKind.Temporal) ChartScales.date(x, spec.bucket) else ChartScales.value(x)
            else -> x.toString()
        }

    /** Band length along the category axis. */
    private fun bandWidth(): Float = (if (horizontal) plot.height else plot.width) / bands.size.coerceAtLeast(1)

    private fun bandCenter(index: Int): Float =
        if (horizontal) plot.top + (index + 0.5f) * bandWidth() else plot.left + (index + 0.5f) * bandWidth()

    private fun xPixel(x: Any): Float =
        if (banded) bandCenter(bandIndex.getValue(x)) else (plot.left + xAxis!!.fraction(x as Double) * plot.width).toFloat()

    private fun valuePixel(value: Double): Float {
        val fraction = valueAxis!!.fraction(value).toFloat()
        return if (horizontal) plot.left + fraction * plot.width else plot.bottom - fraction * plot.height
    }

    private val byGroup: Map<String, List<ChartPoint>> = frame.points.groupBy { it.group }

    /** Everything but the hover marks, which [drawHover] adds on a layer of its own so moving the pointer stays cheap. */
    fun draw(scope: DrawScope) = with(scope) {
        drawRect(colors.background, size = size)
        if (pie) drawPie() else {
            drawAxes()
            when (spec.type) {
                ChartType.Line, ChartType.Area -> drawLines(area = spec.type == ChartType.Area)
                ChartType.Bar -> drawBars()
                ChartType.Scatter -> drawScatter()
                ChartType.Pie -> Unit
            }
        }
        drawLegend()
    }

    /** A guide at the hovered x on lines and areas, and a ring around the hovered point of a scatter plot. */
    fun drawHover(scope: DrawScope, hover: ChartTooltip) = with(scope) {
        when (spec.type) {
            ChartType.Line, ChartType.Area -> {
                drawLine(colors.secondaryText.copy(alpha = 0.5f), Offset(hover.anchor.x, plot.top), Offset(hover.anchor.x, plot.bottom), dp(1f))
                hover.marks.forEach { (color, at) ->
                    drawCircle(color, dp(4f), at)
                    drawCircle(colors.background, dp(4f), at, style = Stroke(dp(1.5f)))
                }
            }
            ChartType.Scatter -> drawCircle(colors.text, dp(5f), hover.anchor, style = Stroke(dp(1.5f)))
            else -> Unit
        }
    }

    private fun DrawScope.drawAxes() {
        val axis = valueAxis ?: return
        val stroke = dp(1f)
        for ((value, text) in valueLabels) {
            val p = valuePixel(value)
            if (horizontal) {
                drawLine(colors.grid, Offset(p, plot.top), Offset(p, plot.bottom), stroke)
                drawText(text, topLeft = Offset((p - text.size.width / 2f).coerceIn(0f, size.width - text.size.width), plot.bottom + dp(6f)))
            } else {
                drawLine(colors.grid, Offset(plot.left, p), Offset(plot.right, p), stroke)
                drawText(text, topLeft = Offset(plot.left - dp(6f) - text.size.width, p - text.size.height / 2f))
            }
        }
        // The category axis line, along zero when the values cross it.
        val zero = valuePixel(0.0.coerceIn(axis.min, axis.max))
        if (horizontal) drawLine(colors.secondaryText.copy(alpha = 0.6f), Offset(zero, plot.top), Offset(zero, plot.bottom), stroke)
        else drawLine(colors.secondaryText.copy(alpha = 0.6f), Offset(plot.left, zero), Offset(plot.right, zero), stroke)

        for ((position, text) in xLabels) {
            if (horizontal) drawText(text, topLeft = Offset(plot.left - dp(6f) - text.size.width, position - text.size.height / 2f))
            else {
                val x = (position - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, plot.bottom + dp(6f)))
            }
        }
        val tickHeight = textHeight
        // Horizontal bars swap the axes: values along the bottom, categories at the left.
        val (below, beside) = if (horizontal) yTitle to xTitle else xTitle to yTitle
        below?.let { drawText(it, topLeft = Offset(plot.center.x - it.size.width / 2f, plot.bottom + dp(10f) + tickHeight)) }
        beside?.let { title ->
            val center = Offset(dp(8f) + title.size.height / 2f, plot.center.y)
            rotate(-90f, center) { drawText(title, topLeft = Offset(center.x - title.size.width / 2f, center.y - title.size.height / 2f)) }
        }
    }

    private fun DrawScope.drawLines(area: Boolean) {
        val baseline = valuePixel(0.0.coerceIn(valueAxis!!.min, valueAxis.max))
        for ((group, points) in byGroup) {
            val color = groupColor.getValue(group)
            val ordered = if (banded) points.sortedBy { bandIndex.getValue(it.x) } else points
            val line = Path()
            ordered.forEachIndexed { i, point ->
                val at = Offset(xPixel(point.x), valuePixel(point.y))
                if (i == 0) line.moveTo(at.x, at.y) else line.lineTo(at.x, at.y)
            }
            if (area && ordered.size > 1) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xPixel(ordered.last().x), baseline)
                    lineTo(xPixel(ordered.first().x), baseline)
                    close()
                }
                drawPath(fill, color.copy(alpha = 0.22f))
            }
            drawPath(line, color, style = Stroke(dp(1.8f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (frame.points.size <= DotsUpTo || ordered.size == 1) ordered.forEach { drawCircle(color, dp(2.6f), Offset(xPixel(it.x), valuePixel(it.y))) }
        }
    }

    private fun DrawScope.drawBars() {
        val groups = frame.groups
        val band = bandWidth()
        val inner = band * 0.8f
        val thickness = inner / groups.size.coerceAtLeast(1)
        val zero = valuePixel(0.0.coerceIn(valueAxis!!.min, valueAxis.max))
        val radius = CornerRadius(dp(2f))
        for (point in frame.points) {
            val index = bandIndex[point.x] ?: continue
            val slot = groupIndex.getValue(point.group)
            val start = bandCenter(index) - inner / 2f + slot * thickness
            val end = valuePixel(point.y)
            val color = groupColor.getValue(point.group)
            val gap = if (thickness > dp(6f)) dp(1f) else 0f
            if (horizontal) {
                drawRoundRect(color, Offset(min(zero, end), start + gap), Size(abs(end - zero).coerceAtLeast(dp(1f)), thickness - 2 * gap), radius)
            } else {
                drawRoundRect(color, Offset(start + gap, min(zero, end)), Size(thickness - 2 * gap, abs(end - zero).coerceAtLeast(dp(1f))), radius)
            }
        }
    }

    private fun DrawScope.drawScatter() {
        val radius = dp(if (frame.points.size > 2000) 2f else 3.2f)
        for (point in frame.points) drawCircle(groupColor.getValue(point.group).copy(alpha = 0.75f), radius, Offset(xPixel(point.x), valuePixel(point.y)))
    }

    private val pieTotal = frame.points.sumOf { max(it.y, 0.0) }
    private val pieRadius = min(plot.width, plot.height) / 2f * 0.95f
    private val pieHole = pieRadius * 0.55f

    private fun DrawScope.drawPie() {
        if (pieTotal <= 0) return
        var angle = -90f
        val box = Rect(plot.center, pieRadius)
        for (point in frame.points) {
            val sweep = (max(point.y, 0.0) / pieTotal * 360).toFloat()
            drawArc(sliceColor.getValue(point.x), angle, sweep, useCenter = true, topLeft = box.topLeft, size = box.size)
            angle += sweep
        }
        // Separators between slices, then the hole: drawn in the background colour so they read as gaps.
        angle = -90f
        for (point in frame.points) {
            val radians = angle / 180f * PI.toFloat()
            drawLine(colors.background, plot.center, plot.center + Offset(kotlin.math.cos(radians), kotlin.math.sin(radians)) * pieRadius, dp(2f))
            angle += (max(point.y, 0.0) / pieTotal * 360).toFloat()
        }
        drawCircle(colors.background, pieHole, plot.center)
        val total = measure(ChartScales.value(pieTotal), legendStyle.copy(fontSize = 15.sp))
        drawText(total, topLeft = plot.center - Offset(total.size.width / 2f, total.size.height / 2f))
    }

    private fun DrawScope.drawLegend() {
        if (legendRows.isEmpty()) return
        val lineHeight = textHeight + dp(4f)
        var y = size.height - dp(8f) - legendHeight + dp(6f)
        for (row in legendRows) {
            val width = row.sumOf { (_, text) -> (dp(8f) + dp(5f) + text.size.width + dp(14f)).toDouble() }.toFloat() - dp(14f)
            var x = (size.width - width) / 2f
            for ((entry, text) in row) {
                drawCircle(entry.first, dp(4f), Offset(x + dp(4f), y + text.size.height / 2f))
                x += dp(13f)
                drawText(text, topLeft = Offset(x, y))
                x += text.size.width + dp(14f)
            }
            y += lineHeight
        }
    }

    /** What is under [at]: the x nearest it on lines and areas, the band of bars, the slice, or a point within reach. */
    fun tooltipAt(at: Offset): ChartTooltip? {
        if (frame.points.isEmpty()) return null
        if (pie) {
            val offset = at - plot.center
            val distance = hypot(offset.x, offset.y)
            if (distance > pieRadius || distance < pieHole || pieTotal <= 0) return null
            val angle = ((atan2(offset.y, offset.x) * 180 / PI + 90 + 360) % 360)
            var start = 0.0
            for (point in frame.points) {
                val sweep = max(point.y, 0.0) / pieTotal * 360
                if (angle < start + sweep) {
                    val share = "%.1f%%".format(java.util.Locale.ENGLISH, point.y / pieTotal * 100)
                    return ChartTooltip(at, label(point.x), listOf(sliceColor.getValue(point.x) to "${ChartScales.value(point.y)} ($share)"))
                }
                start += sweep
            }
            return null
        }
        if (!plot.inflate(dp(4f)).contains(at)) return null
        return when (spec.type) {
            ChartType.Bar -> {
                val position = if (horizontal) at.y - plot.top else at.x - plot.left
                val index = (position / bandWidth()).toInt().coerceIn(0, bands.lastIndex)
                atX(bands[index], Offset(at.x, at.y))
            }
            ChartType.Scatter -> {
                val nearest = frame.points.minByOrNull { hypot(xPixel(it.x) - at.x, valuePixel(it.y) - at.y) } ?: return null
                val where = Offset(xPixel(nearest.x), valuePixel(nearest.y))
                if (hypot(where.x - at.x, where.y - at.y) > dp(14f)) return null
                ChartTooltip(where, label(nearest.x), listOf(groupColor.getValue(nearest.group) to group(nearest)))
            }
            else -> {
                val nearest = frame.points.minByOrNull { abs(xPixel(it.x) - at.x) } ?: return null
                atX(nearest.x, Offset(xPixel(nearest.x), at.y))
            }
        }
    }

    private fun atX(x: Any, anchor: Offset): ChartTooltip {
        val points = byX[x].orEmpty().sortedBy { groupIndex.getValue(it.group) }
        val marks = if (spec.type == ChartType.Line || spec.type == ChartType.Area) points.map { groupColor.getValue(it.group) to Offset(xPixel(it.x), valuePixel(it.y)) } else emptyList()
        return ChartTooltip(anchor, label(x), points.map { groupColor.getValue(it.group) to group(it) }, marks)
    }

    private fun group(point: ChartPoint): String =
        if (frame.groups.size > 1 || spec.series != null) "${point.group}: ${ChartScales.value(point.y)}" else "${frame.yLabel}: ${ChartScales.value(point.y)}"

    internal companion object {
        /** Lines with this few points also get a dot per point. */
        const val DotsUpTo = 60
        /** Labels on axes and legends are cut here before layout; tooltips show a little more. */
        const val MaxLabelChars = 80
        const val MaxTooltipChars = 160

        fun shorten(text: String, max: Int): String {
            val line = if (text.length > max) text.take(max) + "…" else text
            return if ('\n' in line || '\r' in line) line.replace(Newlines, " ") else line
        }

        val Newlines = Regex("[\r\n]+")
        const val MaxLegendRows = 3
    }
}

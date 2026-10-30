package dev.datagrok.app.schema

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.schema.ErBox
import dev.datagrok.core.schema.ErEdge
import dev.datagrok.core.schema.ErLayout
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Draws the diagram with Java2D at 2×, the same way the canvas does, for saving as PNG. */
internal object ErImage {
    private const val Scale = 2.0
    private const val Margin = 40f
    /** Java2D can't allocate arbitrarily large images; big schemas are scaled down to fit this many pixels a side. */
    private const val MaxSide = 16_000.0

    fun write(tables: List<TableDetails>, boxes: Map<TableRef, ErBox>, edges: List<ErEdge>, dark: Boolean, file: File) {
        val colors = ErColors.of(dark, Color(0xFF3574F0))
        val left = boxes.values.minOf { it.x } - Margin
        val top = boxes.values.minOf { it.y } - Margin
        val width = boxes.values.maxOf { it.right } - left + Margin
        val height = boxes.values.maxOf { it.bottom } - top + Margin
        val scale = minOf(Scale, MaxSide / width, MaxSide / height)
        val image = BufferedImage((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = colors.background.awt()
            g.fillRect(0, 0, image.width, image.height)
            g.scale(scale, scale)
            g.translate(-left.toDouble(), -top.toDouble())
            val byRef = tables.associateBy { it.table }
            for (edge in edges) {
                val from = boxes[edge.from] ?: continue
                val to = boxes[edge.to] ?: continue
                drawEdge(g, edgeGeometry(byRef[edge.from], from, byRef[edge.to], to, edge), edge.optional, colors)
            }
            for (table in tables) drawTable(g, table, boxes[table.table] ?: continue, colors)
        } finally {
            g.dispose()
        }
        ImageIO.write(image, "png", file)
    }

    private fun drawTable(g: Graphics2D, table: TableDetails, box: ErBox, colors: ErColors) {
        val shape = RoundRectangle2D.Float(box.x, box.y, box.width, box.height, 20f, 20f)
        g.color = java.awt.Color(0, 0, 0, 26)
        g.fill(RoundRectangle2D.Float(box.x, box.y + 2f, box.width, box.height, 16f, 16f))
        g.color = colors.box.awt()
        g.fill(shape)
        val clip = g.clip
        g.clip(java.awt.Rectangle(box.x.toInt(), box.y.toInt(), box.width.toInt() + 1, ErLayout.HeaderHeight.toInt()))
        g.color = colors.header.awt()
        g.fill(shape)
        g.clip = clip
        g.color = colors.border.awt()
        g.stroke = BasicStroke(1f)
        g.draw(Line2D.Float(box.x, box.y + ErLayout.HeaderHeight, box.right, box.y + ErLayout.HeaderHeight))
        g.draw(shape)

        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        g.color = colors.headerText.awt()
        text(g, table.table.name, box.x + 12f, box.y + ErLayout.HeaderHeight / 2 + 5f, box.width - 48f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.color = colors.muted.awt()
        val count = table.columns.size.toString()
        g.drawString(count, box.right - 12f - g.fontMetrics.stringWidth(count), box.y + ErLayout.HeaderHeight / 2 + 4f)

        val foreignKeys = table.foreignKeys.flatMap { it.columns }.toSet()
        table.columns.take(ErLayout.MaxRows).forEachIndexed { i, column ->
            val center = box.y + ErLayout.HeaderHeight + ErLayout.RowPadding + i * ErLayout.RowHeight + ErLayout.RowHeight / 2
            val baseline = center + 4.5f
            when {
                column.primaryKey -> {
                    g.color = colors.primaryKey.awt()
                    g.fill(Ellipse2D.Float(box.x + 11.4f, center - 3.6f, 7.2f, 7.2f))
                }
                column.name in foreignKeys -> {
                    g.color = colors.foreignKey.awt()
                    g.stroke = BasicStroke(1.6f)
                    g.draw(Ellipse2D.Float(box.x + 11.6f, center - 3.4f, 6.8f, 6.8f))
                }
            }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            val type = column.typeName.lowercase()
            val typeWidth = g.fontMetrics.stringWidth(type).toFloat().coerceAtMost(box.width * 0.45f)
            g.color = colors.muted.awt()
            text(g, type, box.right - 12f - typeWidth, baseline, typeWidth)
            g.font = Font(Font.SANS_SERIF, if (column.primaryKey) Font.BOLD else Font.PLAIN, 12)
            g.color = colors.text.awt()
            text(g, column.name, box.x + 26f, baseline, box.width - 54f - typeWidth)
        }
        if (table.columns.size > ErLayout.MaxRows) {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            g.color = colors.muted.awt()
            text(g, "+ ${table.columns.size - ErLayout.MaxRows} more columns", box.x + 26f, box.y + ErLayout.HeaderHeight + ErLayout.RowPadding + ErLayout.MaxRows * ErLayout.RowHeight + ErLayout.RowHeight / 2 + 4.5f, box.width - 38f)
        }
    }

    private fun drawEdge(g: Graphics2D, geometry: EdgeGeometry, optional: Boolean, colors: ErColors) {
        g.color = colors.line.awt()
        g.stroke = BasicStroke(1.2f)
        g.draw(CubicCurve2D.Float(geometry.start.x, geometry.start.y, geometry.c1.x, geometry.c1.y, geometry.c2.x, geometry.c2.y, geometry.end.x, geometry.end.y))
        val s = geometry.start
        val baseX = s.x + geometry.startDir * 10f
        g.draw(Line2D.Float(baseX, s.y, s.x, s.y - 5f))
        g.draw(Line2D.Float(baseX, s.y, s.x, s.y + 5f))
        val e = geometry.end
        val tick = e.x + geometry.endDir * 7f
        g.draw(Line2D.Float(tick, e.y - 5f, tick, e.y + 5f))
        if (optional) {
            val cx = e.x + geometry.endDir * 15f
            g.draw(Ellipse2D.Float(cx - 3.5f, e.y - 3.5f, 7f, 7f))
        } else {
            val second = e.x + geometry.endDir * 11f
            g.draw(Line2D.Float(second, e.y - 5f, second, e.y + 5f))
        }
    }

    /** Draws [value] cut with an ellipsis to [maxWidth]. */
    private fun text(g: Graphics2D, value: String, x: Float, baseline: Float, maxWidth: Float) {
        if (maxWidth <= 4f) return
        val metrics = g.fontMetrics
        var shown = value
        if (metrics.stringWidth(shown) > maxWidth) {
            while (shown.isNotEmpty() && metrics.stringWidth("$shown…") > maxWidth) shown = shown.dropLast(1)
            shown = "$shown…"
        }
        g.drawString(shown, x, baseline)
    }

    private fun Color.awt(): java.awt.Color = java.awt.Color(toArgb(), true)
}

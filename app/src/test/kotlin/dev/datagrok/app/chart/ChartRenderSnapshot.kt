package dev.datagrok.app.chart

import androidx.compose.ui.graphics.Color
import dev.datagrok.core.chart.Aggregate
import dev.datagrok.core.chart.ChartData
import dev.datagrok.core.chart.ChartSpec
import dev.datagrok.core.chart.ChartType
import dev.datagrok.core.chart.ColumnProfiler
import dev.datagrok.core.chart.Measure
import dev.datagrok.core.chart.TimeBucket
import dev.datagrok.core.driver.ResultColumn
import java.io.File
import java.sql.Types
import kotlin.test.Test

class ChartRenderSnapshot {
    @Test
    fun render() {
        val dir = System.getProperty("snapshot.dir") ?: return
        val regions = listOf("north", "south", "east", "west")
        val rows = (0 until 2000).map { i -> listOf("2024-%02d-%02d 10:00".format(i % 12 + 1, i % 28 + 1), regions[i % 4], "${(i * 37) % 500 + i / 10}") }
        val columns = listOf(ResultColumn("created_at", "timestamp", Types.TIMESTAMP), ResultColumn("region", "text", Types.VARCHAR), ResultColumn("total", "numeric", Types.NUMERIC))
        val profiles = ColumnProfiler.profile(columns, rows)
        val specs = mapOf(
            "line" to ChartSpec(ChartType.Line, "created_at", listOf(Measure("total", Aggregate.Sum)), series = "region", bucket = TimeBucket.Month),
            "bar" to ChartSpec(ChartType.Bar, "region", listOf(Measure("total", Aggregate.Sum)), horizontal = true),
            "pie" to ChartSpec(ChartType.Pie, "region", listOf(Measure("total", Aggregate.Sum))),
            "scatter" to ChartSpec(ChartType.Scatter, "created_at", listOf(Measure("total"))),
            "area" to ChartSpec(ChartType.Area, "created_at", listOf(Measure("total", Aggregate.Avg)), bucket = TimeBucket.Week),
        )
        for ((dark, colors) in listOf(true to ResultChartColors.dark, false to ResultChartColors.light)) {
            for ((name, spec) in specs) {
                val frame = ChartData.build(rows, profiles, spec)
                File(dir, "$name-${if (dark) "dark" else "light"}.png").writeBytes(CanvasChartRenderer.export(frame, colors, ChartImageFormat.Png, 640, 360))
            }
        }
    }
}

package dev.tablegrip.app.chart

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.tablegrip.app.ui.theme.LocalColorTheme

/** Chart colours: the island's background, so a chart sits in the result panel like the grid does. [dark] and [light] are Graphite's. */
object ResultChartColors {
    /** Ten hues told apart in both themes, the first few the most distinct (after IntelliJ's chart palette). */
    private val DarkPalette =
        listOf(0xFF548AF7, 0xFFE5A33E, 0xFF5FB865, 0xFFE06C75, 0xFFB189F5, 0xFF3FB6C6, 0xFFD8829A, 0xFFA8B04F, 0xFF8E9BB0, 0xFFC97E4B).map(::Color)
    private val LightPalette =
        listOf(0xFF3574F0, 0xFFD98B1A, 0xFF3E9A47, 0xFFD24B57, 0xFF8A5CD6, 0xFF1F9AA9, 0xFFC25D7C, 0xFF878F2C, 0xFF6C7A92, 0xFFAA5F2D).map(::Color)

    val dark = ChartColors(Color(0xFF1E1F22), Color(0xFFDFE1E5), Color(0xFF9DA0A8), Color(0xFF2E3034), DarkPalette)
    val light = ChartColors(Color(0xFFFFFFFF), Color(0xFF1E1F22), Color(0xFF6C707E), Color(0xFFEBECF0), LightPalette)

    /** The colour theme's chart colours. */
    @Composable
    fun current(): ChartColors = LocalColorTheme.current.chart
}

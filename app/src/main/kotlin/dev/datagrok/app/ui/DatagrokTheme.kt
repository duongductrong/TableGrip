package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.Environment
import org.jetbrains.jewel.foundation.GlobalColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.createDefaultTextStyle
import org.jetbrains.jewel.intui.standalone.theme.createEditorTextStyle
import org.jetbrains.jewel.intui.standalone.theme.dark
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.light
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * Colours of the "Islands" look (JetBrains' new UI): a tinted frame that the title bar, tool stripe and status bar sit
 * on seamlessly, and rounded content islands floating on it.
 */
data class IslandsPalette(
    val frame: Color,
    val island: Color,
    val islandBorder: Color,
    val accent: Color,
    val stripeHover: Color,
    val chipHover: Color,
)

private val DarkIslands =
    IslandsPalette(
        frame = Color(0xFF27292E),
        island = Color(0xFF1E1F22),
        islandBorder = Color(0x14FFFFFF),
        accent = Color(0xFF3574F0),
        stripeHover = Color(0x14FFFFFF),
        chipHover = Color(0x1AFFFFFF),
    )

private val LightIslands =
    IslandsPalette(
        frame = Color(0xFFE9EBF0),
        island = Color(0xFFFFFFFF),
        islandBorder = Color(0x14000000),
        accent = Color(0xFF3574F0),
        stripeHover = Color(0x14000000),
        chipHover = Color(0x12000000),
    )

val LocalIslands = staticCompositionLocalOf { DarkIslands }

/** Jewel's Int UI theme with panels recoloured as islands and a title bar that blends into the frame. */
@Composable
fun DatagrokTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val islands = if (isDark) DarkIslands else LightIslands
    val textStyle = JewelTheme.createDefaultTextStyle()
    val editorStyle = JewelTheme.createEditorTextStyle()
    val theme =
        if (isDark) {
            JewelTheme.darkThemeDefinition(
                colors = GlobalColors.dark(panelBackground = islands.island),
                defaultTextStyle = textStyle,
                editorTextStyle = editorStyle,
            )
        } else {
            JewelTheme.lightThemeDefinition(
                colors = GlobalColors.light(panelBackground = islands.island),
                defaultTextStyle = textStyle,
                editorTextStyle = editorStyle,
            )
        }
    val titleBarStyle =
        if (isDark) {
            TitleBarStyle.dark(
                colors = TitleBarColors.dark(backgroundColor = islands.frame, inactiveBackground = islands.frame, borderColor = islands.frame)
            )
        } else {
            TitleBarStyle.lightWithLightHeader(
                colors =
                    TitleBarColors.lightWithLightHeader(
                        backgroundColor = islands.frame,
                        inactiveBackground = islands.frame,
                        borderColor = islands.frame,
                    )
            )
        }
    IntUiTheme(theme = theme, styling = ComponentStyling.default().decoratedWindow(titleBarStyle = titleBarStyle)) {
        CompositionLocalProvider(LocalIslands provides islands, content = content)
    }
}

private val IslandShape = RoundedCornerShape(10.dp)

/** A rounded content panel on the frame. */
@Composable
fun Island(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val islands = LocalIslands.current
    Box(modifier.clip(IslandShape).background(islands.island).border(1.dp, islands.islandBorder, IslandShape), content = content)
}

/** The connection's colour: its environment's if it has one, else a stable colour derived from its id. */
fun connectionColor(config: ConnectionConfig): Color =
    EnvironmentColors[config.environment] ?: BadgeColors[(config.id.hashCode() and Int.MAX_VALUE) % BadgeColors.size]

/** Short tag shown next to connections that have an environment. */
fun environmentTag(environment: Environment): String? =
    when (environment) {
        Environment.None -> null
        Environment.Local -> "LOCAL"
        Environment.Development -> "DEV"
        Environment.Staging -> "STG"
        Environment.Production -> "PROD"
    }

private val EnvironmentColors =
    mapOf(
        Environment.Local to Color(0xFF6F7A8C),
        Environment.Development to Color(0xFF4CA35A),
        Environment.Staging to Color(0xFFD9822B),
        Environment.Production to Color(0xFFDB4B4B),
    )

private val BadgeColors =
    listOf(
        Color(0xFF3574F0),
        Color(0xFF5FB865),
        Color(0xFFE08855),
        Color(0xFF9876D8),
        Color(0xFFD95C82),
        Color(0xFF2FA6A9),
        Color(0xFFC9A227),
    )

/** Two-letter initials for a badge, e.g. "Shop (SQLite)" → "SS", "analytics" → "AN". */
fun initials(name: String): String {
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "DB"
    }.uppercase()
}

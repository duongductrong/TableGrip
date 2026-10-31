package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.settings.Settings
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
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.jetbrains.jewel.ui.component.styling.LocalDividerStyle
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
    /** Separators inside an island; Int UI's default dark divider is the island colour itself, i.e. invisible. */
    val divider: Color,
    val accent: Color,
    val stripeHover: Color,
    val chipHover: Color,
    /** Dims the window behind a floating panel such as go-to-anything. */
    val scrim: Color,
    val cornerRadius: Dp = 10.dp,
)

private val DarkIslands =
    IslandsPalette(
        frame = Color(0xFF27292E),
        island = Color(0xFF1E1F22),
        islandBorder = Color(0x14FFFFFF),
        divider = Color(0xFF393B40),
        accent = Color(0xFF3574F0),
        stripeHover = Color(0x14FFFFFF),
        chipHover = Color(0x1AFFFFFF),
        scrim = Color(0x8C000000),
    )

private val LightIslands =
    IslandsPalette(
        frame = Color(0xFFE9EBF0),
        island = Color(0xFFFFFFFF),
        islandBorder = Color(0x14000000),
        divider = Color(0xFFE4E5EA),
        accent = Color(0xFF3574F0),
        stripeHover = Color(0x14000000),
        chipHover = Color(0x12000000),
        scrim = Color(0x47000000),
    )

val LocalIslands = staticCompositionLocalOf { DarkIslands }

/** The user's settings, for views that adapt to them (the editor, the grid) without threading them through. */
val LocalSettings = compositionLocalOf { Settings() }

/** The accent used when the settings don't name one. */
val DefaultAccent = Color(0xFF3574F0)

/** `#RRGGBB` or `#AARRGGBB` (the `#` optional) as a colour; null when it isn't one. */
fun parseHexColor(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(if (hex.length == 6) 0xFF000000 or value else value)
}

/** `#RRGGBB`, upper case, dropping alpha. */
fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** A font family installed on this machine by name, or null for blank names. */
@OptIn(ExperimentalTextApi::class)
fun systemFontFamily(name: String): FontFamily? = name.takeIf { it.isNotBlank() }?.let { FontFamily(SystemFont(it)) }

/** Jewel's Int UI theme with panels recoloured as islands and a title bar that blends into the frame. */
@Composable
fun DatagrokTheme(isDark: Boolean, settings: Settings = Settings(), content: @Composable () -> Unit) {
    val accent = parseHexColor(settings.accentColor) ?: DefaultAccent
    val islands = (if (isDark) DarkIslands else LightIslands).copy(accent = accent, cornerRadius = settings.islandCornerRadius.coerceIn(0, 24).dp)
    val textStyle = if (settings.uiFontSize > 0) JewelTheme.createDefaultTextStyle(fontSize = settings.uiFontSize.sp) else JewelTheme.createDefaultTextStyle()
    val editorFont = remember(settings.editorFontFamily) { systemFontFamily(settings.editorFontFamily) }
    val editorStyle = if (editorFont != null) JewelTheme.createEditorTextStyle(fontFamily = editorFont) else JewelTheme.createEditorTextStyle()
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
    val styling =
        ComponentStyling.default()
            .decoratedWindow(titleBarStyle = titleBarStyle)
            .provide(LocalDividerStyle provides DividerStyle(islands.divider, DividerMetrics.defaults()))
    IntUiTheme(theme = theme, styling = styling) {
        CompositionLocalProvider(LocalIslands provides islands, LocalSettings provides settings, content = content)
    }
}


/** A rounded content panel on the frame; the radius follows Settings → Appearance unless [shape] is given. */
@Composable
fun Island(modifier: Modifier = Modifier, shape: Shape? = null, content: @Composable BoxScope.() -> Unit) {
    val islands = LocalIslands.current
    val shape = shape ?: RoundedCornerShape(islands.cornerRadius)
    Box(modifier.clip(shape).background(islands.island).border(1.dp, islands.islandBorder, shape), content = content)
}

/** The connection's colour: its environment's if it has one, else a stable colour derived from its id. */
fun connectionColor(config: ConnectionConfig): Color =
    environmentColor(config.environment) ?: BadgeColors[(config.id.hashCode() and Int.MAX_VALUE) % BadgeColors.size]

/** The environment's badge colour, as the user set it or built in; null for [Environment.None]. */
fun environmentColor(environment: Environment): Color? = EnvironmentColorOverrides.colors[environment] ?: EnvironmentColors[environment]

fun defaultEnvironmentColor(environment: Environment): Color? = EnvironmentColors[environment]

/** Environment colours from Settings → Color Scheme; kept app-wide because badges are drawn outside composition too. */
object EnvironmentColorOverrides {
    var colors by mutableStateOf<Map<Environment, Color>>(emptyMap())
        private set

    fun configure(settings: Settings) {
        colors = settings.environmentColors.mapNotNull { (name, hex) -> Environment.entries.firstOrNull { it.name == name }?.let { env -> parseHexColor(hex)?.let { env to it } } }.toMap()
    }
}

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

package dev.tablegrip.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.tablegrip.app.chart.ChartColors
import dev.tablegrip.app.console.SyntaxToken
import dev.tablegrip.app.ui.IslandsPalette
import dev.tablegrip.core.settings.ThemeSelection
import dev.tablegrip.core.theme.SyntaxStyle
import dev.tablegrip.core.theme.ThemeSpec

/**
 * A theme variant with every colour the app draws with, resolved from a [ThemeSpec]'s keys.
 *
 * Keys a theme leaves out are filled in one of three ways: from a formula over the theme's own core colours (a
 * selection tint from its accent, a diagram box from its island), from Graphite's variant of the same appearance (for
 * neutral details such as find-match highlights), or, for text and control colours, by keeping Jewel's Int UI colours.
 * That is what lets a hand-written or imported theme set only a few keys and still look whole. The keys:
 *
 * - Window: `frame.background`, `island.background`, `island.border`, `divider`, `accent`, `accent.foreground`,
 *   `toolbar.hover.background`, `chip.hover.background`, `scrim`.
 * - Text (Jewel's own when absent): `text`, `text.muted`, `text.disabled`, `text.error`, `text.warning`.
 * - Controls (Jewel's Int UI controls unless the theme sets at least one): `border`, `border.focused`, `text.link`,
 *   `input.background`, `input.border`, `popup.background`, `popup.border`, `list.selection.background`,
 *   `list.selection.inactive.background`, `list.hover.background`, `button.background`, `button.foreground`,
 *   `button.hover.background`, `button.secondary.border`.
 * - SQL editor: `editor.background`, `editor.foreground`, `editor.currentLine`, `editor.findMatch`,
 *   `editor.findMatch.current`, `editor.bracket`, `editor.bracket.border`; tokens in `syntax`.
 * - Data grid: `grid.modified`, `grid.inserted`, `grid.deleted`.
 * - Charts: `chart.background`, `chart.text`, `chart.text.muted`, `chart.grid`; series colours in `chart`.
 * - ER diagram: `diagram.background`, `diagram.grid`, `diagram.box`, `diagram.header`, `diagram.header.text`,
 *   `diagram.border`, `diagram.text`, `diagram.text.muted`, `diagram.line`, `diagram.primaryKey`,
 *   `diagram.foreignKey`, `diagram.match`.
 */
class ColorTheme(
    val name: String,
    val family: String,
    val isDark: Boolean,
    val islands: IslandsPalette,
    /** Text the app draws itself (charts, diagrams); always set. */
    val text: Color,
    val textMuted: Color,
    /** Colours laid over Jewel's globals; null parts keep Int UI's. */
    val jewel: JewelOverrides,
    /** Restyled Jewel controls, or null to keep Int UI's. */
    val controls: ControlColors?,
    val syntax: Map<SyntaxToken, SpanStyle>,
    val editor: EditorColors,
    val grid: GridChangeColors,
    val chart: ChartColors,
    val diagram: DiagramColors,
)

class JewelOverrides(
    val text: Color? = null,
    val textMuted: Color? = null,
    val textDisabled: Color? = null,
    val error: Color? = null,
    val warning: Color? = null,
    val border: Color? = null,
    val focus: Color? = null,
)

class ControlColors(
    val text: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val focus: Color,
    val link: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val popupBackground: Color,
    val popupBorder: Color,
    val selection: Color,
    val selectionInactive: Color,
    val hover: Color,
    val buttonBackground: Color,
    val buttonForeground: Color,
    val buttonHover: Color,
    val buttonPressed: Color,
    val secondaryBorder: Color,
)

class EditorColors(
    /** Null keeps the panel colour. */
    val background: Color?,
    /** Null keeps the theme's text colour. */
    val foreground: Color?,
    val currentLine: Color,
    val findMatch: Color,
    val findCurrent: Color,
    val bracket: Color,
    val bracketBorder: Color,
)

class GridChangeColors(val modified: Color, val inserted: Color, val deleted: Color)

class DiagramColors(
    val background: Color,
    val grid: Color,
    val box: Color,
    val header: Color,
    val headerText: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val primaryKey: Color,
    val foreignKey: Color,
    val match: Color,
)

/** The theme in use; Graphite Dark outside [dev.tablegrip.app.ui.TableGripTheme], as [dev.tablegrip.app.ui.LocalIslands] is. */
val LocalColorTheme = staticCompositionLocalOf { ThemeRegistry.resolve(ThemeSelection.DefaultDarkTheme, dark = true) }

/** Keys that, when a theme sets any of them, make it restyle Jewel's controls. */
internal val ControlKeys =
    setOf(
        "border", "border.focused", "text.link", "input.background", "input.border", "popup.background", "popup.border",
        "list.selection.background", "list.selection.inactive.background", "list.hover.background", "button.background",
        "button.foreground", "button.hover.background", "button.secondary.border",
    )

/**
 * `#RRGGBB` or `#RRGGBBAA` (alpha last, as VS Code and Zed write it), also `#RGB` and `#RGBA`; null when it isn't one.
 * Note this differs from the settings' `parseHexColor`, which reads eight digits as `#AARRGGBB`.
 */
fun parseThemeColor(text: String): Color? {
    var hex = text.trim().removePrefix("#")
    if (hex.length == 3 || hex.length == 4) hex = hex.map { "$it$it" }.joinToString("")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) Color(0xFF000000 or value) else Color(((value and 0xFF) shl 24) or (value ushr 8))
}

/** `#RRGGBB`, or `#RRGGBBAA` when not opaque: the theme files' own notation. */
fun Color.toThemeHex(): String {
    val argb = (alpha * 255 + 0.5f).toInt() shl 24 or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()
    val rgb = "%06X".format(argb and 0xFFFFFF)
    val a = (argb ushr 24) and 0xFF
    return if (a == 0xFF) "#$rgb" else "#$rgb%02X".format(a)
}

/** Black or white, whichever reads better on [background]. */
fun readableOn(background: Color): Color {
    val l = background.luminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onBlack = (l + 0.05f) / 0.05f
    return if (onWhite >= onBlack) Color.White else Color.Black
}

/**
 * Resolves [spec] against [base], the Graphite variant of the same appearance that supplies keys a theme leaves out.
 * [base] is null only when resolving Graphite itself.
 */
fun resolveTheme(spec: ThemeSpec, family: String, base: ThemeSpec?): ColorTheme {
    val dark = spec.isDark
    fun own(key: String): Color? = spec.colors[key]?.let(::parseThemeColor)
    fun inherited(key: String): Color? = base?.colors?.get(key)?.let(::parseThemeColor)
    fun color(key: String, fallback: () -> Color): Color = own(key) ?: inherited(key) ?: fallback()

    val frame = color("frame.background") { if (dark) Color(0xFF27292E) else Color(0xFFE9EBF0) }
    val island = color("island.background") { if (dark) Color(0xFF1E1F22) else Color.White }
    val islandBorder = color("island.border") { if (dark) Color(0x14FFFFFF) else Color(0x14000000) }
    val divider = color("divider") { if (dark) Color(0xFF393B40) else Color(0xFFE4E5EA) }
    val accent = color("accent") { Color(0xFF3574F0) }
    // The accent's own label colour only carries over when the accent does; a new accent gets black or white.
    val accentForeground = own("accent.foreground") ?: (if (own("accent") == null) inherited("accent.foreground") else null) ?: readableOn(accent)
    val text = own("text") ?: inherited("text") ?: if (dark) Color(0xFFDFE1E5) else Color(0xFF1E1F22)
    val textMuted = own("text.muted") ?: inherited("text.muted") ?: lerp(text, island, 0.38f)
    val textDisabled = own("text.disabled") ?: lerp(text, island, 0.55f)

    val islands =
        IslandsPalette(
            frame = frame,
            island = island,
            islandBorder = islandBorder,
            divider = divider,
            accent = accent,
            accentForeground = accentForeground,
            stripeHover = own("toolbar.hover.background") ?: if (dark) Color(0x14FFFFFF) else Color(0x14000000),
            chipHover = own("chip.hover.background") ?: if (dark) Color(0x1AFFFFFF) else Color(0x12000000),
            scrim = own("scrim") ?: if (dark) Color(0x8C000000) else Color(0x47000000),
        )

    val jewel =
        JewelOverrides(
            text = own("text"),
            textMuted = own("text.muted"),
            textDisabled = own("text.disabled"),
            error = own("text.error"),
            warning = own("text.warning"),
            border = own("border"),
            focus = own("border.focused"),
        )

    val controls =
        if (spec.colors.keys.none { it in ControlKeys }) null
        else {
            val buttonBackground = own("button.background") ?: accent
            val inputBorder = own("input.border") ?: divider
            ControlColors(
                text = text,
                textMuted = textMuted,
                textDisabled = textDisabled,
                focus = own("border.focused") ?: accent,
                link = own("text.link") ?: accent,
                inputBackground = own("input.background") ?: island,
                inputBorder = inputBorder,
                popupBackground = own("popup.background") ?: island,
                popupBorder = own("popup.border") ?: divider,
                selection = own("list.selection.background") ?: accent.copy(alpha = if (dark) 0.35f else 0.2f),
                selectionInactive = own("list.selection.inactive.background") ?: text.copy(alpha = 0.1f),
                hover = own("list.hover.background") ?: text.copy(alpha = 0.06f),
                buttonBackground = buttonBackground,
                buttonForeground = own("button.foreground") ?: if (own("button.background") == null) accentForeground else readableOn(buttonBackground),
                buttonHover = own("button.hover.background") ?: lerp(buttonBackground, if (dark) Color.White else Color.Black, 0.08f),
                buttonPressed = lerp(buttonBackground, Color.Black, 0.12f),
                secondaryBorder = own("button.secondary.border") ?: inputBorder,
            )
        }

    val editor =
        EditorColors(
            background = own("editor.background"),
            foreground = own("editor.foreground"),
            currentLine = color("editor.currentLine") { if (dark) Color(0x0FFFFFFF) else Color(0x0A000000) },
            findMatch = color("editor.findMatch") { if (dark) Color(0x4D3D6185) else Color(0x66F2E36B) },
            findCurrent = color("editor.findMatch.current") { if (dark) Color(0x996F8FBF) else Color(0xCCF2C94C) },
            bracket = color("editor.bracket") { if (dark) Color(0x3343A047) else Color(0x3393D9A3) },
            bracketBorder = color("editor.bracket.border") { Color(0x9943A047) },
        )

    // Edits are tinted with the theme's accent; inserts and deletes keep Graphite's green and red, which mean the same everywhere.
    val grid =
        GridChangeColors(
            modified = own("grid.modified") ?: accent.copy(alpha = if (dark) 0.25f else 0.2f),
            inserted = color("grid.inserted") { if (dark) Color(0x3349A35B) else Color(0x3355A76A) },
            deleted = color("grid.deleted") { if (dark) Color(0x33DB5C5C) else Color(0x33E55765) },
        )

    val palette = spec.chart.mapNotNull(::parseThemeColor).ifEmpty { base?.chart?.mapNotNull(::parseThemeColor).orEmpty() }.ifEmpty { listOf(accent) }
    val chart =
        ChartColors(
            background = own("chart.background") ?: island,
            text = own("chart.text") ?: text,
            secondaryText = own("chart.text.muted") ?: textMuted,
            grid = own("chart.grid") ?: lerp(divider, island, 0.4f),
            palette = palette,
        )

    val box = own("diagram.box") ?: lerp(island, text, 0.05f)
    val diagram =
        DiagramColors(
            background = own("diagram.background") ?: island,
            grid = own("diagram.grid") ?: lerp(island, text, 0.05f),
            box = box,
            header = own("diagram.header") ?: divider,
            headerText = own("diagram.header.text") ?: text,
            border = own("diagram.border") ?: lerp(divider, text, 0.15f),
            text = own("diagram.text") ?: text,
            muted = own("diagram.text.muted") ?: textMuted,
            line = own("diagram.line") ?: textMuted,
            primaryKey = color("diagram.primaryKey") { Color(0xFFE0B53E) },
            foreignKey = own("diagram.foreignKey") ?: accent,
            match = color("diagram.match") { Color(0xFFE0B53E) },
        )

    val syntax =
        SyntaxToken.entries.associateWith { token ->
            val style = spec.syntax[token.key] ?: base?.syntax?.get(token.key)
            style.toSpanStyle(fallbackColor = text, italicByDefault = token == SyntaxToken.Comment)
        }

    return ColorTheme(spec.name, family, dark, islands, text, textMuted, jewel, controls, syntax, editor, grid, chart, diagram)
}

private fun SyntaxStyle?.toSpanStyle(fallbackColor: Color, italicByDefault: Boolean): SpanStyle {
    val italic = when (this?.fontStyle?.lowercase()) {
        "italic" -> true
        "normal" -> false
        else -> italicByDefault
    }
    return SpanStyle(
        color = this?.color?.let(::parseThemeColor) ?: fallbackColor,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (this?.fontWeight.equals("bold", ignoreCase = true)) FontWeight.Bold else null,
    )
}

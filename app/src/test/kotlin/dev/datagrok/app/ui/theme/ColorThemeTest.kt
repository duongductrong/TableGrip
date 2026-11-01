package dev.datagrok.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import dev.datagrok.app.chart.ResultChartColors
import dev.datagrok.app.console.SyntaxToken
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.ThemeSelection
import dev.datagrok.core.theme.ThemeOverride
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorThemeTest {
    @AfterTest
    fun forgetUserThemes() = ThemeRegistry.reloadUserThemes(createTempDirectory("no-themes"))

    @Test
    fun `every built-in family has a dark and a light variant that resolve`() {
        val families = ThemeRegistry.builtIn.map { it.name }
        assertEquals(listOf("Graphite", "VS Code Modern", "Dracula", "One", "Catppuccin", "Contrast"), families)
        for (family in ThemeRegistry.builtIn) {
            assertEquals(setOf(true, false), family.themes.map { it.isDark }.toSet(), family.name)
            for (spec in family.themes) {
                // Every colour in the file parses: a typo would silently fall back otherwise.
                spec.colors.forEach { (key, value) -> assertNotNull(parseThemeColor(value), "${spec.name} $key = $value") }
                spec.chart.forEach { assertNotNull(parseThemeColor(it), "${spec.name} chart $it") }
                val theme = ThemeRegistry.resolve(spec.name, spec.isDark)
                assertEquals(spec.name, theme.name)
                assertEquals(SyntaxToken.entries.toSet(), theme.syntax.keys)
            }
        }
        assertEquals(listOf("Graphite Dark", "VS Code Dark Modern", "Dracula", "One Dark", "Catppuccin Frappé", "Contrast Dark"), ThemeRegistry.names(dark = true))
        assertEquals(listOf("Graphite Light", "VS Code Light Modern", "Alucard", "One Light", "Catppuccin Latte", "Contrast Light"), ThemeRegistry.names(dark = false))
    }

    @Test
    fun `Graphite is today's look and keeps Jewel's own controls`() {
        val dark = Settings().colorTheme(dark = true)
        assertEquals("Graphite Dark", dark.name)
        assertEquals(Color(0xFF27292E), dark.islands.frame)
        assertEquals(Color(0xFF1E1F22), dark.islands.island)
        assertEquals(Color(0x14FFFFFF), dark.islands.islandBorder)
        assertEquals(Color(0xFF3574F0), dark.islands.accent)
        assertNull(dark.controls)
        assertNull(dark.jewel.text)
        assertEquals(ResultChartColors.dark, dark.chart)
        assertEquals(Color(0xFFCF8E6D), dark.syntax.getValue(SyntaxToken.Keyword).color)
        assertEquals(FontStyle.Italic, dark.syntax.getValue(SyntaxToken.Comment).fontStyle)
        assertEquals(ResultChartColors.light, Settings().colorTheme(dark = false).chart)
    }

    @Test
    fun `colours are written alpha last, as in VS Code and Zed`() {
        assertEquals(Color(0x14FFFFFF), parseThemeColor("#FFFFFF14"))
        assertEquals(Color(0xFF3574F0), parseThemeColor("3574F0"))
        assertEquals(Color(0xFFFFAA00), parseThemeColor("#FA0"))
        assertNull(parseThemeColor("#12345"))
        assertEquals("#FFFFFF14", Color(0x14FFFFFF).toThemeHex())
        assertEquals("#3574F0", Color(0xFF3574F0).toThemeHex())
    }

    @Test
    fun `a theme that sets a few keys gets the rest from its own colours`() {
        val dir = createTempDirectory("themes")
        dir.resolve("mine.json").writeText(
            """{ "name": "Mine", "themes": [ { "name": "Mine Dark", "appearance": "dark",
                 "colors": { "island.background": "#102030", "accent": "#FFCC00", "input.background": "#0A1520" } } ] }"""
        )
        ThemeRegistry.reloadUserThemes(dir)
        val theme = Settings(theme = ThemeSelection(dark = "Mine Dark")).colorTheme(dark = true)
        assertEquals("Mine Dark", theme.name)
        assertEquals(Color(0xFF102030), theme.islands.island)
        assertEquals(Color(0xFF27292E), theme.islands.frame, "unset core keys come from Graphite")
        assertEquals(Color.Black, theme.islands.accentForeground, "a light accent gets dark labels")
        assertEquals(Color(0xFF102030), theme.diagram.background, "the diagram follows the island")
        assertEquals(Color(0xFFFFCC00), theme.diagram.foreignKey)
        val controls = assertNotNull(theme.controls, "setting a control key restyles the controls")
        assertEquals(Color(0xFF0A1520), controls.inputBackground)
        assertEquals(Color(0xFFFFCC00), controls.focus)
        assertTrue(ThemeRegistry.names(dark = true).contains("Mine Dark"))
    }

    @Test
    fun `a user file with a built-in theme's name replaces it`() {
        val dir = createTempDirectory("themes")
        dir.resolve("one.json").writeText("""{ "name": "One", "themes": [ { "name": "One Dark", "colors": { "accent": "#00FF00" } } ] }""")
        ThemeRegistry.reloadUserThemes(dir)
        assertEquals(Color(0xFF00FF00), ThemeRegistry.resolve("One Dark", dark = true).islands.accent)
        assertEquals(1, ThemeRegistry.names(dark = true).count { it == "One Dark" })
    }

    @Test
    fun `settings overrides and the accent colour lay over the theme`() {
        val settings =
            Settings(
                theme = ThemeSelection(dark = "One Dark"),
                themeOverrides = mapOf("One Dark" to ThemeOverride(colors = mapOf("island.background" to "#000000"))),
                accentColor = "#FFCC00",
            )
        val theme = settings.colorTheme(dark = true)
        assertEquals(Color.Black, theme.islands.island)
        assertEquals(Color(0xFFFFCC00), theme.islands.accent)
        assertEquals(Color.Black, theme.islands.accentForeground)
        assertEquals(Color(0xFF21252B), theme.islands.frame)
    }

    @Test
    fun `an unknown or mismatched theme name falls back to Graphite of the mode`() {
        assertEquals("Graphite Dark", ThemeRegistry.resolve("No Such Theme", dark = true).name)
        assertEquals("Graphite Light", ThemeRegistry.resolve("One Dark", dark = false).name)
    }
}

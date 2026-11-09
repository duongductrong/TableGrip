package dev.tablegrip.app.conformance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.theme.ColorTheme
import dev.tablegrip.app.ui.theme.ThemeRegistry
import dev.tablegrip.app.ui.theme.colorTheme
import dev.tablegrip.app.ui.theme.toThemeHex
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.ThemeSelection
import dev.tablegrip.core.theme.ThemeJson
import dev.tablegrip.core.theme.ThemeOverride
import java.io.File
import java.text.Normalizer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.LocalDividerStyle
import org.jetbrains.jewel.ui.theme.linkStyle

/**
 * Every colour token the app resolves for a theme, dumped to `conformance/golden/themes/<name>.json` so the Rust
 * `tg-theme` crate can be checked value by value.
 *
 * One file per built-in variant (default settings), plus one per case in `fixtures/themes/cases.json`, which adds the
 * user themes in `fixtures/themes/user`, `themeOverrides`, `accentColor`, the UI font size and the island radius.
 *
 * `colors`, `syntax` and `chart` are the [ColorTheme] as resolved (null where the app keeps Jewel's Int UI colour).
 * `ui` and `fonts` are what Jewel actually draws with inside [TableGripTheme], read at runtime: for a theme that leaves
 * a key out (Graphite leaves out all text and control keys) they are Int UI's own values, which the Rust side cannot
 * derive and therefore takes from these files.
 *
 * `-Pconformance.record=true` rewrites the files; without it the test fails when they have drifted.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeTokensGoldenTest {
    private val record = System.getProperty("conformance.record") == "true"
    private val pretty = Json { prettyPrint = true }
    private val fixtures = File(Scenarios.root, "fixtures/themes")
    private val out = File(Scenarios.root, "golden/themes")

    @AfterTest
    fun noUserThemes() = ThemeRegistry.reloadUserThemes(File(fixtures, "none").toPath())

    @Test
    fun tokens() {
        val files = linkedMapOf<String, JsonObject>()

        noUserThemes()
        for (entry in ThemeRegistry.entries) {
            val name = entry.spec.name
            val setup = buildJsonObject {
                put("theme", name)
                put("dark", entry.spec.isDark)
            }
            files["${slug(name)}.json"] = dump("built-in", setup)
        }

        ThemeRegistry.reloadUserThemes(File(fixtures, "user").toPath())
        val cases = Json.parseToJsonElement(File(fixtures, "cases.json").readText()).jsonObject.getValue("cases").jsonArray
        for (case in cases) {
            val setup = case.jsonObject
            files["case-${setup.getValue("id").jsonPrimitive.content}.json"] = dump(setup.getValue("id").jsonPrimitive.content, setup)
        }

        val failures = mutableListOf<String>()
        if (record) {
            out.mkdirs()
            out.listFiles { f -> f.extension == "json" }.orEmpty().forEach { it.delete() }
        }
        for ((name, json) in files) {
            val file = File(out, name)
            if (record) file.writeText(pretty.encodeToString(JsonObject.serializer(), json) + "\n")
            else if (!file.isFile || Json.parseToJsonElement(file.readText()) != json) failures += name
        }
        val stale = out.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.filter { it !in files }
        assertEquals(emptyList(), failures + stale.map { "stale: $it" }, "Re-record with -Pconformance.record=true if the change is intended.")
    }

    private fun dump(case: String, setup: JsonObject): JsonObject {
        val settings = settingsOf(setup)
        val dark = setup.getValue("dark").jsonPrimitive.boolean
        val theme = settings.colorTheme(dark)
        var runtime: Pair<JsonObject, JsonObject>? = null
        runComposeUiTest {
            setContent {
                TableGripTheme(isDark = dark, settings = settings) {
                    val g = JewelTheme.globalColors
                    val link = JewelTheme.linkStyle.colors
                    val ui = buildJsonObject {
                        put("ui.text", g.text.normal.hex())
                        put("ui.text.info", g.text.info.hex())
                        put("ui.text.disabled", g.text.disabled.hex())
                        put("ui.text.error", g.text.error.hex())
                        put("ui.text.warning", g.text.warning.hex())
                        put("ui.border", g.borders.normal.hex())
                        put("ui.border.focused", g.borders.focused.hex())
                        put("ui.border.disabled", g.borders.disabled.hex())
                        put("ui.outline.focused", g.outlines.focused.hex())
                        put("ui.outline.error", g.outlines.error.hex())
                        put("ui.outline.warning", g.outlines.warning.hex())
                        put("ui.divider", LocalDividerStyle.current.color.hex())
                        put("ui.link", link.content.hex())
                        put("ui.link.disabled", link.contentDisabled.hex())
                        put("ui.link.hovered", link.contentHovered.hex())
                        put("ui.link.pressed", link.contentPressed.hex())
                        put("ui.link.focused", link.contentFocused.hex())
                        put("ui.link.visited", link.contentVisited.hex())
                    }
                    val text = JewelTheme.defaultTextStyle
                    val editor = JewelTheme.editorTextStyle
                    val fonts = buildJsonObject {
                        put("ui.size", text.fontSize.value)
                        put("ui.lineHeight", text.lineHeight.value)
                        put("editor.size", editor.fontSize.value)
                        put("editor.lineHeight", editor.lineHeight.value)
                        put("island.cornerRadius", LocalIslands.current.cornerRadius.value)
                    }
                    runtime = ui to fonts
                }
            }
            waitForIdle()
        }
        val (ui, fonts) = runtime!!
        return buildJsonObject {
            put("case", case)
            put("setup", setup)
            putJsonObject("resolved") {
                put("name", theme.name)
                put("family", theme.family)
                put("dark", theme.isDark)
            }
            put("colors", colors(theme))
            putJsonObject("syntax") {
                for ((token, style) in theme.syntax) {
                    putJsonObject(token.key) {
                        put("color", style.color.hex())
                        put("italic", style.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic)
                        put("bold", style.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
            put("chart", buildJsonArray { theme.chart.palette.forEach { add(JsonPrimitive(it.hex())) } })
            put("ui", ui)
            put("fonts", fonts)
        }
    }

    private fun colors(t: ColorTheme): JsonObject {
        val i = t.islands
        val c = t.controls
        val pairs =
            listOf(
                "frame.background" to i.frame, "island.background" to i.island, "island.border" to i.islandBorder,
                "divider" to i.divider, "accent" to i.accent, "accent.foreground" to i.accentForeground,
                "toolbar.hover.background" to i.stripeHover, "chip.hover.background" to i.chipHover, "scrim" to i.scrim,
                "text" to t.text, "text.muted" to t.textMuted,
                "jewel.text" to t.jewel.text, "jewel.text.muted" to t.jewel.textMuted, "jewel.text.disabled" to t.jewel.textDisabled,
                "jewel.text.error" to t.jewel.error, "jewel.text.warning" to t.jewel.warning, "jewel.border" to t.jewel.border,
                "jewel.border.focused" to t.jewel.focus,
                "controls.text" to c?.text, "controls.text.muted" to c?.textMuted, "controls.text.disabled" to c?.textDisabled,
                "controls.focus" to c?.focus, "controls.link" to c?.link, "controls.input.background" to c?.inputBackground,
                "controls.input.border" to c?.inputBorder, "controls.popup.background" to c?.popupBackground,
                "controls.popup.border" to c?.popupBorder, "controls.selection" to c?.selection,
                "controls.selection.inactive" to c?.selectionInactive, "controls.hover" to c?.hover,
                "controls.button.background" to c?.buttonBackground, "controls.button.foreground" to c?.buttonForeground,
                "controls.button.hover" to c?.buttonHover, "controls.button.pressed" to c?.buttonPressed,
                "controls.secondary.border" to c?.secondaryBorder,
                "editor.background" to t.editor.background, "editor.foreground" to t.editor.foreground,
                "editor.currentLine" to t.editor.currentLine, "editor.findMatch" to t.editor.findMatch,
                "editor.findMatch.current" to t.editor.findCurrent, "editor.bracket" to t.editor.bracket,
                "editor.bracket.border" to t.editor.bracketBorder,
                "grid.modified" to t.grid.modified, "grid.inserted" to t.grid.inserted, "grid.deleted" to t.grid.deleted,
                "chart.background" to t.chart.background, "chart.text" to t.chart.text,
                "chart.text.muted" to t.chart.secondaryText, "chart.grid" to t.chart.grid,
                "diagram.background" to t.diagram.background, "diagram.grid" to t.diagram.grid, "diagram.box" to t.diagram.box,
                "diagram.header" to t.diagram.header, "diagram.header.text" to t.diagram.headerText,
                "diagram.border" to t.diagram.border, "diagram.text" to t.diagram.text, "diagram.text.muted" to t.diagram.muted,
                "diagram.line" to t.diagram.line, "diagram.primaryKey" to t.diagram.primaryKey,
                "diagram.foreignKey" to t.diagram.foreignKey, "diagram.match" to t.diagram.match,
            )
        return JsonObject(pairs.associate { (k, v) -> k to (v?.hex()?.let(::JsonPrimitive) ?: JsonNull) })
    }

    private fun Color.hex(): String = toThemeHex()

    private fun settingsOf(setup: JsonObject): Settings {
        val name = setup.getValue("theme").jsonPrimitive.content
        val dark = setup.getValue("dark").jsonPrimitive.boolean
        val overrides =
            setup["themeOverrides"]?.jsonObject?.mapValues { (_, v) -> ThemeJson.json.decodeFromJsonElement(ThemeOverride.serializer(), v) }.orEmpty()
        return Settings(
            theme = if (dark) ThemeSelection(mode = "Dark", dark = name) else ThemeSelection(mode = "Light", light = name),
            accentColor = setup.string("accentColor") ?: "",
            themeOverrides = overrides,
            uiFontSize = setup["uiFontSize"]?.jsonPrimitive?.int ?: 0,
            islandCornerRadius = setup["islandCornerRadius"]?.jsonPrimitive?.int ?: 10,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** `Catppuccin Frappé` → `catppuccin-frappe`. */
    private fun slug(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

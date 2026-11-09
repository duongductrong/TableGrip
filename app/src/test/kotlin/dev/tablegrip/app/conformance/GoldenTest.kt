package dev.tablegrip.app.conformance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.theme.ThemeRegistry
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.ThemeSelection
import java.awt.image.BufferedImage
import java.io.File
import java.text.Normalizer
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sizes and colours of the components as the app draws them today, measured from offscreen renders of the gallery
 * specimens ([Gallery]): `conformance/golden/<theme>.json`, plus each render in `reference/<theme>/<name>.png`
 * and its tagged text, icon and skip regions in `reference/<theme>/<name>.json`. The Rust UI kit is checked against
 * these files, for every built-in theme.
 *
 * Colours are sampled from flat areas (fills, borders), never from text or anti-aliased edges, and measured rather
 * than copied from any library's palette. `./gradlew :app:test --tests '*GoldenTest*' -Pconformance.record=true`
 * rewrites the files; without it the test fails when the app's look has drifted from them. Only the JSON is compared:
 * the PNGs are rewritten with it.
 */
@OptIn(ExperimentalTestApi::class)
class GoldenTest {
    private val record = System.getProperty("conformance.record") == "true"
    private val pretty = Json { prettyPrint = true }

    @Test
    fun golden() {
        ThemeRegistry.reloadUserThemes(File(Scenarios.root, "fixtures/themes/none").toPath())
        val failures = mutableListOf<String>()
        for (entry in ThemeRegistry.entries.filter { it.builtIn }) {
            val theme = entry.spec.name
            val dark = entry.spec.isDark
            val slug = slug(theme)
            val metrics = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            val colors = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            val renders = mutableListOf<Triple<String, BufferedImage, JsonObject>>()
            for (specimen in Gallery.all) {
                val (m, image, regions) = render(specimen, theme, dark)
                metrics += m.metrics
                colors += m.colors
                renders += Triple(specimen.name, image, regions)
            }
            val golden = buildJsonObject {
                put("theme", theme)
                put("density", Scale)
                put("metrics", JsonObject(metrics))
                put("colors", JsonObject(colors))
            }
            val file = File(Scenarios.root, "golden/$slug.json")
            if (record) {
                file.parentFile.mkdirs()
                file.writeText(pretty.encodeToString(JsonObject.serializer(), golden) + "\n")
                val dir = File(Scenarios.root, "reference/$slug").apply { mkdirs() }
                dir.listFiles().orEmpty().forEach { it.delete() }
                for ((name, image, regions) in renders) {
                    ImageIO.write(image, "png", File(dir, "$name.png"))
                    File(dir, "$name.json").writeText(pretty.encodeToString(JsonObject.serializer(), regions) + "\n")
                }
            } else if (!file.isFile || Json.parseToJsonElement(file.readText()) != golden) {
                failures += "$theme differs from ${file.relativeTo(Scenarios.root)}:\n${pretty.encodeToString(JsonObject.serializer(), golden)}"
            }
        }
        assertEquals(emptyList(), failures, "Re-record with -Pconformance.record=true if the change is intended.")
    }

    private fun render(specimen: GallerySpecimen, theme: String, dark: Boolean): Triple<Measure, BufferedImage, JsonObject> {
        var result: Triple<Measure, BufferedImage, JsonObject>? = null
        runComposeUiTest {
            val selection = if (dark) ThemeSelection(mode = "Dark", dark = theme) else ThemeSelection(mode = "Light", light = theme)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(Scale)) {
                    TableGripTheme(isDark = dark, settings = Settings(theme = selection)) {
                        Box(Modifier.size(specimen.size).background(LocalIslands.current.frame)) { specimen.Content() }
                    }
                }
            }
            waitForIdle()
            with(specimen) { prepare() }
            waitForIdle()
            val bitmap = onRoot().captureToImage()
            fun bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val measure = Measure(bitmap.toPixelMap(), ::bounds, Scale)
            with(specimen) { measure(measure) }
            val tags =
                onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
                    generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
                }.mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.let { it to node.boundsInRoot } }
            val regions = buildJsonObject {
                put("specimen", specimen.name)
                put("theme", theme)
                put("density", Scale)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put(
                    "regions",
                    buildJsonArray {
                        for ((tag, b) in tags.sortedBy { it.first }) {
                            val kind =
                                when {
                                    tag.startsWith(TextRegionTag) -> "text"
                                    tag.startsWith(IconRegionTag) -> "icon"
                                    tag.startsWith(SkipRegionTag) -> "skip"
                                    else -> continue
                                }
                            add(
                                buildJsonObject {
                                    put("tag", tag)
                                    put("kind", kind)
                                    put("rect", JsonArray(listOf(b.left, b.top, b.width, b.height).map { JsonPrimitive(it.roundToInt()) }))
                                }
                            )
                        }
                    },
                )
            }
            result = Triple(measure, bitmap.toAwtImage(), regions)
        }
        return result!!
    }

    private companion object {
        const val Scale = 2f

        /** `Catppuccin Frappé` → `catppuccin-frappe`. */
        fun slug(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}

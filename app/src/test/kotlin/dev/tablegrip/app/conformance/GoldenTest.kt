package dev.tablegrip.app.conformance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.toHex
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.ThemeSelection
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Sizes and colours of the components as the app draws them today, measured from an offscreen render of a small
 * gallery: `ui-kit/conformance/golden/<theme>.json`, plus the render itself in `reference/<theme>/gallery.png`. The
 * Rust UI kit is checked against these files.
 *
 * Colours are sampled from flat areas (fills, borders), never from text or anti-aliased edges, and measured rather
 * than copied from any library's palette. `./gradlew :app:test --tests '*GoldenTest*' -Pconformance.record=true`
 * rewrites the files; without it the test fails when the app's look has drifted from them.
 */
@OptIn(ExperimentalTestApi::class)
class GoldenTest {
    private val themes = listOf("Graphite Dark" to true, "Graphite Light" to false, "One Dark" to true)
    private val record = System.getProperty("conformance.record") == "true"
    private val pretty = Json { prettyPrint = true }

    @Test
    fun golden() {
        val failures = mutableListOf<String>()
        for ((name, dark) in themes) {
            val (golden, image) = measure(name, dark)
            val slug = name.lowercase().replace(' ', '-')
            val file = File(Scenarios.root, "golden/$slug.json")
            if (record) {
                file.parentFile.mkdirs()
                file.writeText(pretty.encodeToString(JsonObject.serializer(), golden) + "\n")
                File(Scenarios.root, "reference/$slug").mkdirs()
                ImageIO.write(image, "png", File(Scenarios.root, "reference/$slug/gallery.png"))
            } else if (!file.isFile || Json.parseToJsonElement(file.readText()) != golden) {
                failures += "$name differs from ${file.relativeTo(Scenarios.root)}:\n${pretty.encodeToString(JsonObject.serializer(), golden)}"
            }
        }
        assertEquals(emptyList(), failures, "Re-record with -Pconformance.record=true if the change is intended.")
    }

    private fun measure(theme: String, dark: Boolean): Pair<JsonObject, java.awt.image.BufferedImage> {
        var result: Pair<JsonObject, java.awt.image.BufferedImage>? = null
        runComposeUiTest {
            val selection = if (dark) ThemeSelection(mode = "Dark", dark = theme) else ThemeSelection(mode = "Light", light = theme)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(Scale)) {
                    TableGripTheme(isDark = dark, settings = Settings(theme = selection)) { Gallery() }
                }
            }
            waitForIdle()
            val bitmap = onRoot().captureToImage()
            val pixels = bitmap.toPixelMap()
            fun bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val golden = buildJsonObject {
                put("theme", theme)
                put("density", Scale)
                putJsonObject("metrics") {
                    for (tag in Specimens) {
                        val b = bounds(tag)
                        putJsonObject(tag) {
                            put("width", dp(b.width))
                            put("height", dp(b.height))
                        }
                    }
                }
                putJsonObject("colors") {
                    put("island.background", pixels.hex(bounds("blank").center.x, bounds("blank").center.y))
                    for (tag in Specimens.filter { it.startsWith("button") || it.startsWith("textfield") }) {
                        val b = bounds(tag)
                        put("$tag.fill", pixels.hex(b.left + 6 * Scale, b.center.y))
                        put("$tag.border", pixels.hex(b.center.x, b.top + Scale / 2))
                        // The focus ring and the error outline are drawn just outside the bounds.
                        put("$tag.outline", pixels.hex(b.center.x, b.top - Scale / 2))
                    }
                    for (tag in Specimens.filter { it.startsWith("checkbox") }) {
                        // The box sits 4dp in from the row's left edge and 4dp down; sampled clear of the check mark.
                        val b = bounds(tag)
                        put("$tag.box.border", pixels.hex(b.left + 4 * Scale + Scale / 2, b.top + 8 * Scale))
                        put("$tag.box.fill", pixels.hex(b.left + 6 * Scale, b.top + 6 * Scale))
                    }
                    put("divider", pixels.hex(bounds("divider").center.x, bounds("divider").center.y))
                }
            }
            result = golden to bitmap.toAwtImage()
        }
        return result!!
    }

    private fun dp(px: Float): JsonPrimitive = JsonPrimitive((px / Scale * 100).roundToInt() / 100.0)

    private fun PixelMap.hex(x: Float, y: Float): String = this[x.toInt(), y.toInt()].toHex()

    private companion object {
        const val Scale = 2f
        val Specimens =
            listOf(
                "button.default", "button.default.disabled", "button.outlined", "button.outlined.disabled",
                "checkbox.unchecked", "checkbox.checked", "checkbox.disabled",
                "textfield", "textfield.disabled", "textfield.error", "textfield.focused", "divider",
            )
    }
}

/** Every specimen the golden file measures, in the states it measures them. */
@Composable
private fun Gallery() {
    val focus = remember { FocusRequester() }
    Island(Modifier.size(560.dp, 300.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DefaultButton({}, Modifier.testTag("button.default")) { Text("Save") }
                DefaultButton({}, Modifier.testTag("button.default.disabled"), enabled = false) { Text("Save") }
                OutlinedButton({}, Modifier.testTag("button.outlined")) { Text("Cancel") }
                OutlinedButton({}, Modifier.testTag("button.outlined.disabled"), enabled = false) { Text("Cancel") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CheckboxRow("Read-only", false, {}, Modifier.testTag("checkbox.unchecked"))
                CheckboxRow("Read-only", true, {}, Modifier.testTag("checkbox.checked"))
                CheckboxRow("Read-only", false, {}, Modifier.testTag("checkbox.disabled"), enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield"))
                TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.disabled"), enabled = false)
                TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.error"), outline = Outline.Error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(remember { TextFieldState("users") }, Modifier.width(120.dp).testTag("textfield.focused").focusRequester(focus))
                Box(Modifier.size(40.dp, 20.dp).testTag("blank"))
            }
            Divider(Orientation.Horizontal, Modifier.width(200.dp).testTag("divider"))
            Spacer(Modifier.size(1.dp))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

package dev.tablegrip.app.conformance

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.DpSize
import dev.tablegrip.app.ui.toHex
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** Tags starting with this mark text whose pixels the image diff compares by ink (position, size, colour), not 1:1. */
const val TextRegionTag = "text:"

/** Tags starting with this mark icons; the image diff compares them with a looser threshold. */
const val IconRegionTag = "icon:"

/** Tags starting with this mark areas the image diff skips (anti-aliased curves, platform-drawn parts). */
const val SkipRegionTag = "skip:"

/**
 * One gallery specimen: components drawn the way the app draws them, on a canvas of a fixed [size], which the Rust
 * gallery (`ui-kit/crates/tg-gallery/src/specimens/<name>.rs`) lays out identically. [GoldenTest] renders every
 * specimen in every built-in theme to `ui-kit/conformance/reference/<theme>/<name>.png`, with the text, icon and skip
 * regions tagged inside it in `<name>.json`, and merges what [measure] records into `golden/<theme>.json`.
 *
 * Each lives in its own file in the `gallery` package as an `object`; [Gallery] finds them, so adding a specimen
 * never touches a shared file.
 */
@OptIn(ExperimentalTestApi::class)
interface GallerySpecimen {
    /** File name of the reference render, and the Rust specimen's name. */
    val name: String

    val size: DpSize

    /** Drawn inside `TableGripTheme` on the frame colour, at the top left of the canvas. */
    @Composable fun Content()

    /** Interaction before the capture, such as hovering or focusing a tagged node. */
    fun ComposeUiTest.prepare() {}

    /** Sizes and colours measured from the render, merged into the theme's golden file. */
    fun measure(m: Measure) {}
}

/** What a specimen's [GallerySpecimen.measure] reads from the render and records. */
class Measure(private val pixels: PixelMap, private val boundsOf: (String) -> Rect, val scale: Float) {
    val metrics = linkedMapOf<String, JsonElement>()
    val colors = linkedMapOf<String, JsonElement>()

    fun bounds(tag: String): Rect = boundsOf(tag)

    /** Width and height of the node tagged [tag], in dp to two decimals. */
    fun size(tag: String) {
        val b = bounds(tag)
        metrics[tag] = kotlinx.serialization.json.buildJsonObject {
            put("width", dp(b.width))
            put("height", dp(b.height))
        }
    }

    /** The colour at ([x], [y]) in pixels of the render, as `#RRGGBB`. */
    fun color(name: String, x: Float, y: Float) {
        colors[name] = JsonPrimitive(pixels[x.toInt(), y.toInt()].toHex())
    }

    private fun dp(px: Float): JsonPrimitive = JsonPrimitive((px / scale * 100).roundToInt() / 100.0)
}

object Gallery {
    private const val Package = "dev.tablegrip.app.conformance.gallery"

    val all: List<GallerySpecimen> by lazy {
        val dirs = javaClass.classLoader.getResources(Package.replace('.', '/')).toList().filter { it.protocol == "file" }.map { File(it.toURI()) }
        dirs
            .flatMap { dir -> dir.listFiles { f -> f.extension == "class" && '$' !in f.name }.orEmpty().toList() }
            .map { Class.forName("$Package.${it.nameWithoutExtension}") }
            .filter { GallerySpecimen::class.java.isAssignableFrom(it) }
            .map { it.getField("INSTANCE").get(null) as GallerySpecimen }
            .sortedBy { it.name }
    }
}

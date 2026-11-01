package dev.datagrok.core.theme

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject

/**
 * A theme family as one JSON file, shaped after Zed's theme files: several variants (usually a dark and a light one)
 * that share a name, each a flat map of dotted colour keys such as `island.background`.
 *
 * Colours are `#RRGGBB` or `#RRGGBBAA`, as in VS Code and Zed. Every key is optional and unknown keys are ignored, so a
 * file may set just an accent, carry keys of a later version, or come from an importer that maps another editor's
 * theme onto these keys. Core keeps the colours as text so it stays UI-free; the app resolves them.
 */
@Serializable
data class ThemeFamily(
    val name: String,
    val author: String = "",
    val license: String = "",
    val themes: List<ThemeSpec> = emptyList(),
)

/** One variant of a [ThemeFamily]; its [name] is what settings select it by, e.g. `One Dark`. */
@Serializable
data class ThemeSpec(
    val name: String,
    /** `dark` or `light`. */
    val appearance: String = "dark",
    val colors: Map<String, String> = emptyMap(),
    /** SQL token styles by token key (`keyword`, `string`…), each a colour or `{ "color", "fontStyle", "fontWeight" }`. */
    val syntax: Map<String, @Serializable(with = SyntaxStyleSerializer::class) SyntaxStyle> = emptyMap(),
    /** Series colours for charts, most distinct first. */
    val chart: List<String> = emptyList(),
) {
    val isDark: Boolean
        get() = !appearance.equals("light", ignoreCase = true)

    /** This variant with [override]'s keys laid over its own. */
    fun with(override: ThemeOverride?): ThemeSpec =
        if (override == null) this else copy(colors = colors + override.colors, syntax = syntax + override.syntax, chart = override.chart.ifEmpty { chart })
}

/** A token's look; null parts keep the theme's. [fontStyle] is `normal` or `italic`, [fontWeight] `normal` or `bold`. */
@Serializable
data class SyntaxStyle(val color: String? = null, val fontStyle: String? = null, val fontWeight: String? = null)

/** Reads a [SyntaxStyle] from either a bare colour string or an object, and writes the short form when it can. */
object SyntaxStyleSerializer : JsonTransformingSerializer<SyntaxStyle>(SyntaxStyle.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) buildJsonObject { put("color", element) } else element

    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return if (obj.keys == setOf("color")) obj.getValue("color") else obj
    }
}

/** What settings lay over a theme by its name, like Zed's `theme_overrides` or VS Code's `workbench.colorCustomizations`. */
@Serializable
data class ThemeOverride(
    val colors: Map<String, String> = emptyMap(),
    val syntax: Map<String, @Serializable(with = SyntaxStyleSerializer::class) SyntaxStyle> = emptyMap(),
    val chart: List<String> = emptyList(),
)

/** A theme file that couldn't be read, kept so Settings can say what is wrong with it. */
data class ThemeFileError(val file: Path, val message: String)

data class ThemeFiles(val families: List<ThemeFamily>, val errors: List<ThemeFileError> = emptyList())

object ThemeJson {
    /** Lenient the way editors' JSON is: comments and trailing commas are fine, unknown keys are ignored. */
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun parse(text: String): ThemeFamily = json.decodeFromString(ThemeFamily.serializer(), text)

    fun encode(family: ThemeFamily): String = json.encodeToString(ThemeFamily.serializer(), family)

    /** Every `*.json` in [dir], in name order; a file that fails to parse is reported and skipped, never fatal. */
    fun loadDirectory(dir: Path): ThemeFiles {
        if (!dir.exists() || !dir.isDirectory()) return ThemeFiles(emptyList())
        val families = mutableListOf<ThemeFamily>()
        val errors = mutableListOf<ThemeFileError>()
        for (file in dir.listDirectoryEntries().filter { it.extension.equals("json", ignoreCase = true) }.sortedBy { it.fileName.toString() }) {
            runCatching { parse(file.readText()) }
                .onSuccess { family -> if (family.themes.isEmpty()) errors += ThemeFileError(file, "No themes in \"themes\"") else families += family }
                .onFailure { errors += ThemeFileError(file, it.message?.lineSequence()?.firstOrNull() ?: it.toString()) }
        }
        return ThemeFiles(families, errors)
    }
}

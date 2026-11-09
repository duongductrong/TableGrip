package dev.tablegrip.app.conformance

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A conformance scenario file from `ui-kit/conformance/scenarios`: one component and the cases it must pass. The same
 * files run against the Rust UI kit, so they describe behaviour, not Compose or Jewel APIs.
 */
data class ScenarioFile(val component: String, val cases: List<ScenarioCase>, val source: File)

data class ScenarioCase(val name: String, val setup: JsonObject, val steps: List<Step>, val expect: Map<String, JsonPrimitive>)

/**
 * One thing a scenario does. Pointer steps act on the subject unless they name a test tag (a menu item, a tree row);
 * key and text steps go to whatever has focus. The Rust runner understands the same vocabulary.
 */
sealed interface Step {
    data class Click(val tag: String? = null) : Step

    /** The middle mouse button. */
    data class MiddleClick(val tag: String? = null) : Step

    data class DoubleClick(val tag: String? = null) : Step

    data class RightClick(val tag: String? = null) : Step

    data class Hover(val tag: String? = null) : Step

    /** Presses at the target's centre, moves by ([dx], [dy]) dp and releases. */
    data class Drag(val tag: String?, val dx: Double, val dy: Double) : Step

    /** Moves the pointer off every control. */
    data object Unhover : Step

    data object Focus : Step

    /**
     * [key] is a name such as `Space`, `Enter`, `Tab`, `A`, `Equals`, `Digit1` or `NumpadEnter`. In [mods], `primary` is
     * Cmd on macOS and Ctrl elsewhere, `word` is Option on macOS and Ctrl elsewhere, `ctrl` and `meta` the physical keys.
     */
    data class Key(val key: String, val mods: List<String>) : Step

    data class Type(val text: String) : Step

    /** Lets [ms] milliseconds of (test) time pass, for delays such as a tooltip's. */
    data class Wait(val ms: Long) : Step
}

object Scenarios {
    /** `ui-kit/conformance`, found from Gradle's working directory (the module) or the repository root. */
    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ui-kit/conformance") }
            .firstOrNull { it.isDirectory }
            ?: error("ui-kit/conformance not found above ${File("").absolutePath}")
    }

    fun load(): List<ScenarioFile> =
        File(root, "scenarios").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map(::parse)

    fun parse(file: File): ScenarioFile {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        val cases =
            json.getValue("cases").jsonArray.map { element ->
                val case = element.jsonObject
                ScenarioCase(
                    name = case.getValue("name").jsonPrimitive.content,
                    setup = case["setup"]?.jsonObject ?: JsonObject(emptyMap()),
                    steps = case["steps"]?.jsonArray.orEmpty().map(::step),
                    expect = case.getValue("expect").jsonObject.mapValues { it.value.jsonPrimitive },
                )
            }
        return ScenarioFile(json.getValue("component").jsonPrimitive.content, cases, file)
    }

    private fun step(element: JsonElement): Step {
        if (element is JsonPrimitive) {
            return when (element.content) {
                "click" -> Step.Click()
                "middleClick" -> Step.MiddleClick()
                "doubleClick" -> Step.DoubleClick()
                "rightClick" -> Step.RightClick()
                "hover" -> Step.Hover()
                "unhover" -> Step.Unhover
                "focus" -> Step.Focus
                else -> error("Unknown step: $element")
            }
        }
        val o = element.jsonObject
        fun tag(name: String) = o.getValue(name).jsonPrimitive.content
        return when {
            "key" in o -> Step.Key(tag("key"), (o["mods"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
            "type" in o -> Step.Type(tag("type"))
            "wait" in o -> Step.Wait(tag("wait").toLong())
            "drag" in o -> Step.Drag((o["drag"] as? JsonPrimitive)?.contentOrNull, o.number("dx"), o.number("dy"))
            "click" in o -> Step.Click(tag("click"))
            "middleClick" in o -> Step.MiddleClick(tag("middleClick"))
            "doubleClick" in o -> Step.DoubleClick(tag("doubleClick"))
            "rightClick" in o -> Step.RightClick(tag("rightClick"))
            "hover" in o -> Step.Hover(tag("hover"))
            else -> error("Unknown step: $element")
        }
    }
}

private fun JsonObject.number(name: String): Double = this[name]?.jsonPrimitive?.content?.toDouble() ?: 0.0

fun JsonObject.string(name: String, default: String = ""): String = this[name]?.jsonPrimitive?.contentOrNull ?: default

fun JsonObject.bool(name: String, default: Boolean = false): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: default

/** An expected value as the runner compares it: booleans, ints and strings by their JSON text. */
fun JsonPrimitive.normalized(): String = booleanOrNull?.toString() ?: intOrNull?.toString() ?: content

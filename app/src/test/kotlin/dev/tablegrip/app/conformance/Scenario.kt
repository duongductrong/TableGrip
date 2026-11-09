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

sealed interface Step {
    data object Click : Step

    data object Focus : Step

    /** [key] is a name such as `Space`, `Enter`, `Tab`, `A`; `primary` in [mods] is Cmd on macOS and Ctrl elsewhere. */
    data class Key(val key: String, val mods: List<String>) : Step

    data class Type(val text: String) : Step
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

    private fun step(element: JsonElement): Step =
        when {
            element is JsonPrimitive && element.content == "click" -> Step.Click
            element is JsonPrimitive && element.content == "focus" -> Step.Focus
            element is JsonObject && "key" in element ->
                Step.Key(element.getValue("key").jsonPrimitive.content, (element["mods"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
            element is JsonObject && "type" in element -> Step.Type(element.getValue("type").jsonPrimitive.content)
            else -> error("Unknown step: $element")
        }
}

fun JsonObject.string(name: String, default: String = ""): String = this[name]?.jsonPrimitive?.contentOrNull ?: default

fun JsonObject.bool(name: String, default: Boolean = false): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: default

/** An expected value as the runner compares it: booleans, ints and strings by their JSON text. */
fun JsonPrimitive.normalized(): String = booleanOrNull?.toString() ?: intOrNull?.toString() ?: content

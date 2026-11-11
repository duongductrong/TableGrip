package dev.tablegrip.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A tool failed in a way the agent should read: a bad argument, a denial, a database error. Becomes `isError: true`. */
class ToolException(message: String) : Exception(message)

/** One MCP tool. [run] returns what the agent reads, usually JSON. */
class Tool(
    val name: String,
    val title: String,
    val description: String,
    /** Only reads: no data, schema or file changes. */
    val readOnly: Boolean,
    /** May delete or overwrite (only meaningful when not [readOnly]). */
    val destructive: Boolean = false,
    val input: JsonObject,
    val run: suspend ToolCall.() -> JsonElement,
) {
    fun describe(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("title", title)
            put("description", description)
            put("inputSchema", input)
            putJsonObject("annotations") {
                put("title", title)
                put("readOnlyHint", readOnly)
                if (!readOnly) put("destructiveHint", destructive)
                put("openWorldHint", false)
            }
        }
}

/** The arguments of one call, with the session it belongs to. */
class ToolCall(val arguments: JsonObject, val session: McpSession) {
    fun string(name: String): String = stringOrNull(name)?.takeIf { it.isNotBlank() } ?: throw ToolException("'$name' is required.")

    fun stringOrNull(name: String): String? =
        when (val value = arguments[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content
            else -> throw ToolException("'$name' must be a string.")
        }

    fun int(name: String, default: Int, range: IntRange): Int {
        val value = arguments[name] ?: return default
        if (value is JsonNull) return default
        val number = (value as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: throw ToolException("'$name' must be a whole number.")
        if (number !in range) throw ToolException("'$name' must be between ${range.first} and ${range.last}.")
        return number
    }

    fun long(name: String, default: Long): Long {
        val value = arguments[name] ?: return default
        if (value is JsonNull) return default
        return (value as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }?.takeIf { it >= 0 } ?: throw ToolException("'$name' must be a whole number, 0 or more.")
    }

    fun boolean(name: String, default: Boolean): Boolean {
        val value = arguments[name] ?: return default
        if (value is JsonNull) return default
        return (value as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() } ?: throw ToolException("'$name' must be true or false.")
    }

    fun strings(name: String): List<String> =
        when (val value = arguments[name]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.map { (it as? JsonPrimitive)?.contentOrNull ?: throw ToolException("'$name' must be a list of strings.") }
            is JsonPrimitive -> value.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw ToolException("'$name' must be a list of strings.")
        }

    fun element(name: String): JsonElement? = arguments[name]?.takeIf { it !is JsonNull }
}

/** Builds a tool's JSON Schema: `params { string("connection", "…", required = true) }`. */
class ParamsBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, schema: JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(schema)
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required) {
            put("type", "string")
            put("description", description)
            enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
        }

    fun integer(name: String, description: String, required: Boolean = false, minimum: Int? = null, maximum: Int? = null) =
        add(name, required) {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    fun boolean(name: String, description: String) =
        add(name, false) {
            put("type", "boolean")
            put("description", description)
        }

    fun strings(name: String, description: String, required: Boolean = false) =
        add(name, required) {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "string") }
        }

    fun raw(name: String, required: Boolean, schema: JsonObject) {
        properties[name] = schema
        if (required) this.required += name
    }

    fun connection() = string("connection", "The connection's id or name, as list_connections returns it.", required = true)

    fun build(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }
}

fun params(block: ParamsBuilder.() -> Unit): JsonObject = ParamsBuilder().apply(block).build()

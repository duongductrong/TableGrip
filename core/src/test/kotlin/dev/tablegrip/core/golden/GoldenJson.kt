package dev.tablegrip.core.golden

import dev.tablegrip.core.sql.SqlSyntax
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns one input case into its golden output. */
typealias Subject = (JsonObject) -> JsonElement

internal fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content

internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.intOrNull ?: error("$key is not an int")

internal fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

internal fun JsonObject.strings(key: String): List<String> = array(key).map { it.jsonPrimitive.content }

internal fun JsonObject.stringMap(key: String): Map<String, String> =
    this[key]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

internal fun syntaxOf(case: JsonObject): SqlSyntax =
    when (val name = case["syntax"]?.jsonPrimitive?.content ?: "Standard") {
        "PostgreSQL" -> SqlSyntax.PostgreSQL
        "MySQL" -> SqlSyntax.MySQL
        "SQLite" -> SqlSyntax.SQLite
        "TSql" -> SqlSyntax.TSql
        "ClickHouse" -> SqlSyntax.ClickHouse
        "Standard" -> SqlSyntax.Standard
        else -> error("unknown syntax $name")
    }

internal val goldenJson = Json { ignoreUnknownKeys = false }

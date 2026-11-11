package dev.tablegrip.core.driver.http

import dev.tablegrip.core.driver.ConsoleReply
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The Dev Tools style console that every HTTP engine shares: one request, its JSON reply as rows when it holds a list. */
object HttpConsole {
    /**
     * Sends [statement] and shows the reply: the first list of objects [rows] finds in it as rows, else the JSON itself.
     * An error status fails with the server's reply.
     */
    fun run(rest: RestClient, statement: String, rows: (JsonElement) -> List<JsonObject>? = ::firstList): ConsoleReply {
        val request = HttpStatement.parse(statement)
        val started = System.currentTimeMillis()
        val response = rest.send(request.method, request.path, request.body)
        val elapsed = System.currentTimeMillis() - started
        if (!response.ok) throw IOException("${response.status}: " + runCatching { JsonTables.pretty(response.json()) }.getOrDefault(response.body))
        val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull() ?: return ConsoleReply.Text(response.body.ifEmpty { "${response.status}" }, elapsed)
        val objects = rows(json)?.takeIf { it.isNotEmpty() }
        return if (objects != null) ConsoleReply.Table(JsonTables.rows(objects, elapsedMillis = elapsed)) else ConsoleReply.Text(JsonTables.pretty(json), elapsed)
    }

    /** The reply itself when it's a list of objects, else the first such list among its fields, one level down. */
    fun firstList(json: JsonElement): List<JsonObject>? {
        fun objects(element: JsonElement?) = (element as? JsonArray)?.takeIf { it.isNotEmpty() && it.all { e -> e is JsonObject } }?.map { it.jsonObject }
        objects(json)?.let { return it }
        val reply = json as? JsonObject ?: return null
        reply.values.firstNotNullOfOrNull(::objects)?.let { return it }
        return reply.values.filterIsInstance<JsonObject>().firstNotNullOfOrNull { inner -> inner.values.firstNotNullOfOrNull(::objects) }
    }

    /** GET and HEAD read; a POST reads when its path ends with one of [readSuffixes]. */
    fun isRead(statement: String, readSuffixes: List<String>): Boolean {
        val request = runCatching { HttpStatement.parse(statement) }.getOrNull() ?: return false
        if (request.method == "GET" || request.method == "HEAD") return true
        return request.method == "POST" && readSuffixes.any { request.endpoint.trimEnd('/').endsWith(it) }
    }
}

package dev.tablegrip.core.driver.keyvalue

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.HttpConsole
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * HashiCorp Consul's key-value store through its HTTP API (port 8500). The password is an ACL token; the `datacenter`
 * property picks a datacenter. Keys are browsed by folder (`/`-separated prefixes): a folder's records are the keys
 * directly in it. Every write is a check-and-set on the key's ModifyIndex, so one made from a stale page is refused.
 */
class ConsulClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Consul", config, host, port)
        val headers = password?.takeIf { it.isNotEmpty() }?.let { mapOf("X-Consul-Token" to it) } ?: emptyMap()
        return Connection(config, rest, headers).also { it.agent() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient, private val headers: Map<String, String>) : RecordConnection {
        private val dc = config.properties["datacenter"]?.trim()?.takeIf { it.isNotEmpty() }?.let { "dc=${encode(it)}" }

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun send(method: String, path: String, query: String = "", body: String? = null) =
            rest.send(method, path + listOfNotNull(query.ifEmpty { null }, dc).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }, body, "application/octet-stream", headers).also {
                if (it.status == 403 || it.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "Consul", it.body.take(200)))
            }

        internal fun agent(): String {
            val response = send("GET", "/v1/agent/self")
            val json = runCatching { response.json().jsonObject }.getOrNull()?.get("Config")?.jsonObject
                ?: throw IOException(tr("{0} doesn't look like Consul: {1}", rest.base, response.body.take(120)))
            send("GET", "/v1/kv/", "keys&separator=/").also { if (!it.ok && it.status != 404) throw IOException("Consul: ${it.body.take(200)}") }
            return "Consul ${json["Version"]?.jsonPrimitive?.contentOrNull.orEmpty()} (${json["Datacenter"]?.jsonPrimitive?.contentOrNull.orEmpty()})"
        }

        override suspend fun server(): String = io { agent() }

        /** The prefix a container stands for: `[/]` is the root, `[/, app, config]` is `app/config/`. */
        private fun prefix(container: List<String>): String = container.drop(1).joinToString("") { "$it/" }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isEmpty()) return@io listOf(RecordContainer(listOf(Root), "folder", browsable = true, hasChildren = true))
                val prefix = prefix(parent)
                val response = send("GET", "/v1/kv/${path(prefix)}", "keys&separator=/")
                if (response.status == 404) return@io emptyList()
                response.json().jsonArray.map { it.jsonPrimitive.content }.filter { it.endsWith("/") && it != prefix }
                    .map { it.removePrefix(prefix).removeSuffix("/") }.sorted().map { RecordContainer(parent + it, "folder", hasChildren = true) }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val prefix = prefix(container)
                val response = send("GET", "/v1/kv/${path(prefix)}", "keys")
                val keys = if (response.status == 404) emptyList() else response.json().jsonArray.map { it.jsonPrimitive.content }
                JsonTables.pretty(buildJsonObject { put("prefix", prefix); put("keys", keys.size); put("folders", keys.count { it.endsWith("/") }) })
            }

        override val queryLanguage =
            RecordLanguage(tr("Key"), tr("Part of a key name, e.g. timeout"), tr("Keeps the keys of this folder whose name contains the text."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val prefix = prefix(container)
                val response = send("GET", "/v1/kv/${path(prefix)}", "recurse")
                val entries = if (response.status == 404) emptyList() else response.json().jsonArray.map { it.jsonObject }
                val direct = entries.filter { e ->
                    val key = e["Key"]!!.jsonPrimitive.content
                    val rest = key.removePrefix(prefix)
                    rest.isNotEmpty() && '/' !in rest && rest.contains(query.trim(), ignoreCase = true)
                }.sortedBy { it["Key"]!!.jsonPrimitive.content }
                val offset = cursor?.toIntOrNull() ?: 0
                val page = direct.drop(offset).take(limit)
                RecordPage(page.map(::record), (offset + limit).takeIf { it < direct.size }?.toString(), direct.size.toLong())
            }

        private fun record(entry: JsonObject): Record {
            val key = entry["Key"]!!.jsonPrimitive.content
            val value = entry["Value"]?.jsonPrimitive?.contentOrNull?.let { String(Base64.getDecoder().decode(it)) }
            return Record(
                buildJsonObject { put("key", key); put("ModifyIndex", entry["ModifyIndex"]!!) },
                buildJsonObject { put("key", key); put("value", value?.let(::JsonPrimitive) ?: JsonNull) },
                buildJsonObject { put("flags", entry["Flags"] ?: JsonNull); put("modifyIndex", entry["ModifyIndex"] ?: JsonNull) },
            )
        }

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("key", prefix(container)); put("value", "") }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val name = document["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && !it.endsWith("/") } ?: throw IOException(tr("A key needs a name."))
                if (key != null && key["key"]?.jsonPrimitive?.content != name) throw IOException(tr("A key can't be renamed here; add a new key instead."))
                val value = document["value"].let { if (it is JsonPrimitive && it.isString) it.content else it?.takeIf { v -> v !is JsonNull }?.toString().orEmpty() }
                // cas=0 creates only a key that doesn't exist; otherwise the index it was read at.
                val index = key?.get("ModifyIndex")?.jsonPrimitive?.longOrNull ?: 0
                val response = send("PUT", "/v1/kv/${path(name)}", "cas=$index", value)
                if (!response.ok) throw IOException("Consul: ${response.body.take(200)}")
                if (response.body.trim() != "true") {
                    throw IOException(if (key == null) tr("The key {0} already exists.", name) else tr("{0} was changed by someone else since it was read; reload and try again.", name))
                }
                current(name)?.let { buildJsonObject { put("key", name); put("ModifyIndex", it["ModifyIndex"]!!) } } ?: buildJsonObject { put("key", name) }
            }

        private fun current(name: String): JsonObject? {
            val response = send("GET", "/v1/kv/${path(name)}")
            if (response.status == 404) return null
            return response.json().jsonArray.firstOrNull()?.jsonObject
        }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                keys.count { key ->
                    val name = key["key"]!!.jsonPrimitive.content
                    if (current(name) == null) return@count false
                    val index = key["ModifyIndex"]?.jsonPrimitive?.longOrNull
                    val response = send("DELETE", "/v1/kv/${path(name)}", index?.let { "cas=$it" } ?: "")
                    if (response.body.trim() != "true") throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", name))
                    true
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "GET /v1/kv/app/?recurse",
                tr("Requests to the Consul API: a method and a path, then a body. Lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String = "GET /v1/kv/${prefix(container)}?recurse"

        override suspend fun execute(statement: String): ConsoleReply =
            io { HttpConsole.run(rest, statement, headers) }

        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, emptyList())

        override fun close() = Unit
    }

    companion object {
        internal const val Root = "/"

        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

        /** A key as a URL path, its slashes kept. */
        internal fun path(key: String): String = key.split('/').joinToString("/") { encode(it).replace("+", "%20") }
    }
}

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
import dev.tablegrip.core.driver.http.HttpStatement
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.driver.http.RestResponse
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Nacos' configuration center through its open API (port 8848, under `/nacos`). A user and password sign in for an
 * access token, when the server has authentication on. Namespaces are the containers; their configurations are the
 * records (data id, group, content, type). Publishing first checks the content's MD5 against the one it was read with,
 * and sends it as `casMd5` too, so a change made since isn't overwritten.
 */
class NacosClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Nacos", config, host, port)
        return Connection(config, rest, password).also { it.check() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient, private val password: String?) : RecordConnection {
        @Volatile private var token: String? = null

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun login() {
            val user = config.user.trim().takeIf { it.isNotEmpty() } ?: return
            val response = rest.send("POST", "/nacos/v1/auth/login", "username=${encode(user)}&password=${encode(password.orEmpty())}", "application/x-www-form-urlencoded")
            if (!response.ok) throw IOException(tr("{0} refused the credentials: {1}", "Nacos", response.body.take(200)))
            token = response.json().jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull
        }

        /** Sends a request with the access token, signing in again once if it expired. */
        private fun send(method: String, path: String, query: String = "", body: String? = null): RestResponse {
            fun attempt(): RestResponse {
                val parameters = listOfNotNull(query.ifEmpty { null }, token?.let { "accessToken=${encode(it)}" }).joinToString("&")
                return rest.send(method, "/nacos$path" + if (parameters.isEmpty()) "" else "?$parameters", body, "application/x-www-form-urlencoded")
            }
            var response = attempt()
            if (response.status == 403 && config.user.isNotBlank()) {
                login()
                response = attempt()
            }
            if (response.status == 401 || response.status == 403) throw IOException(tr("{0} refused the credentials: {1}", "Nacos", response.body.take(200)))
            return response
        }

        private fun json(method: String, path: String, query: String = "", body: String? = null): JsonObject {
            val response = send(method, path, query, body)
            if (!response.ok) throw IOException("Nacos: ${response.body.take(300)}")
            return response.json().jsonObject
        }

        internal fun check(): String {
            login()
            val state = runCatching { json("GET", "/v1/console/server/state") }.getOrElse {
                if (it.message?.contains("credentials") == true) throw it
                throw IOException(tr("{0} doesn't look like Nacos: {1}", rest.base, it.message))
            }
            namespaces()
            return "Nacos ${state["version"]?.jsonPrimitive?.contentOrNull.orEmpty()} (${state["standalone_mode"]?.jsonPrimitive?.contentOrNull.orEmpty()})"
        }

        override suspend fun server(): String = io { check() }

        /** Namespace ids and names; `public` has the empty id. */
        private fun namespaces(): List<Pair<String, String>> =
            json("GET", "/v1/console/namespaces")["data"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
                it["namespace"]?.jsonPrimitive?.contentOrNull.orEmpty() to (it["namespaceShowName"]?.jsonPrimitive?.contentOrNull ?: "public")
            }

        /** A container's namespace id: the path holds the id, `public` for the default one. */
        private fun tenant(container: List<String>) = container.single().let { if (it == Public) "" else it }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                namespaces().map { (id, name) ->
                    val count = runCatching {
                        json("GET", "/v1/cs/configs", "search=blur&dataId=&group=&pageNo=1&pageSize=1&tenant=${encode(id)}")["totalCount"]?.jsonPrimitive?.longOrNull
                    }.getOrNull()
                    RecordContainer(listOf(id.ifEmpty { Public }), "namespace", count, name.takeIf { it != id && id.isNotEmpty() })
                }
            }

        override suspend fun definition(container: List<String>): String =
            io { JsonTables.pretty(json("GET", "/v1/console/namespaces")["data"]!!.jsonArray.first { it.jsonObject["namespace"]?.jsonPrimitive?.contentOrNull.orEmpty() == tenant(container) }) }

        override val queryLanguage =
            RecordLanguage(tr("Data ID"), tr("Part of a data id, or group:part for one group, e.g. DEFAULT_GROUP:app"), tr("Keeps the configurations whose data id contains the text; group: before it narrows to a group."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val text = query.trim()
                val group = text.substringBefore(':', "").takeIf { ':' in text }.orEmpty()
                val dataId = text.substringAfter(':')
                val pageNo = cursor?.toIntOrNull() ?: 1
                val reply = json("GET", "/v1/cs/configs", "search=blur&dataId=${encode(if (dataId.isEmpty()) "" else "*$dataId*")}&group=${encode(group)}&pageNo=$pageNo&pageSize=$limit&tenant=${encode(tenant(container))}")
                val items = reply["pageItems"]?.jsonArray.orEmpty().map { it.jsonObject }
                val total = reply["totalCount"]?.jsonPrimitive?.longOrNull
                RecordPage(items.map(::record), (pageNo + 1).takeIf { (reply["pagesAvailable"]?.jsonPrimitive?.longOrNull ?: 0) > pageNo }?.toString(), total)
            }

        private fun record(item: JsonObject): Record {
            val content = item["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return Record(
                buildJsonObject { put("dataId", item["dataId"]!!); put("group", item["group"]!!); put("md5", md5(content)) },
                buildJsonObject { put("dataId", item["dataId"]!!); put("group", item["group"]!!); put("type", item["type"] ?: JsonPrimitive("text")); put("content", content) },
            )
        }

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("dataId", ""); put("group", "DEFAULT_GROUP"); put("type", "text"); put("content", "") }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val dataId = document["dataId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: throw IOException(tr("A configuration needs its {0}.", "dataId"))
                val group = document["group"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "DEFAULT_GROUP"
                val content = document["content"]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("A configuration needs its {0}.", "content"))
                if (key != null && (key["dataId"]?.jsonPrimitive?.content != dataId || key["group"]?.jsonPrimitive?.content != group)) {
                    throw IOException(tr("A configuration's data id and group can't change; add a new one instead."))
                }
                val tenant = tenant(container)
                val now = current(tenant, dataId, group)
                if (key == null && now != null) throw IOException(tr("{0} already exists.", "$group/$dataId"))
                // Not every server honours casMd5, so the content is checked here first, too.
                if (key != null && (now == null || md5(now) != key["md5"]?.jsonPrimitive?.content)) {
                    throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", "$group/$dataId"))
                }
                val form = listOfNotNull(
                    "dataId=${encode(dataId)}", "group=${encode(group)}", "tenant=${encode(tenant)}", "content=${encode(content)}",
                    "type=${encode(document["type"]?.jsonPrimitive?.contentOrNull ?: "text")}",
                    key?.get("md5")?.jsonPrimitive?.contentOrNull?.let { "casMd5=${encode(it)}" },
                ).joinToString("&")
                val response = send("POST", "/v1/cs/configs", body = form)
                if (!response.ok || response.body.trim() != "true") {
                    throw IOException(if (key != null) tr("{0} was changed by someone else since it was read; reload and try again.", "$group/$dataId") else "Nacos: ${response.body.take(200)}")
                }
                buildJsonObject { put("dataId", dataId); put("group", group); put("md5", md5(content)) }
            }

        /**
         * The configuration's content now, or null when there is none. Read through the search, which reads the
         * database: a single configuration's read comes from a cache that catches up a moment after each write.
         */
        private fun current(tenant: String, dataId: String, group: String): String? =
            json("GET", "/v1/cs/configs", "search=accurate&dataId=${encode(dataId)}&group=${encode(group)}&pageNo=1&pageSize=1&tenant=${encode(tenant)}")["pageItems"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val tenant = tenant(container)
                keys.count { key ->
                    val dataId = key["dataId"]!!.jsonPrimitive.content
                    val group = key["group"]!!.jsonPrimitive.content
                    val now = current(tenant, dataId, group) ?: return@count false
                    if (key["md5"] != null && md5(now) != key["md5"]!!.jsonPrimitive.content) throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", "$group/$dataId"))
                    send("DELETE", "/v1/cs/configs", "dataId=${encode(dataId)}&group=${encode(group)}&tenant=${encode(tenant)}").body.trim() == "true"
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(tr("HTTP"), "GET /v1/ns/service/list?pageNo=1&pageSize=20", tr("Requests to the Nacos open API, under /nacos: a method and a path, then a form or JSON body."))

        override fun sampleStatement(container: List<String>): String =
            "GET /v1/cs/configs?search=blur&dataId=&group=&pageNo=1&pageSize=20&tenant=" + (container.firstOrNull()?.let { if (it == Public) "" else it } ?: "")

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val request = HttpStatement.parse(statement)
                val separator = if ('?' in request.path) "&" else "?"
                val path = "/nacos" + request.path + (token?.let { "${separator}accessToken=${encode(it)}" } ?: "")
                HttpConsole.run(rest, "${request.method} $path\n${request.body.orEmpty()}")
            }

        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, emptyList())

        override fun close() = Unit
    }

    companion object {
        /** The default namespace, whose id is empty. */
        internal const val Public = "public"

        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

        internal fun md5(text: String): String = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

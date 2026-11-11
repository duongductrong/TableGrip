package dev.tablegrip.core.driver.stream

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
import dev.tablegrip.core.driver.http.RestResponse
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * RabbitMQ through its management HTTP API (the management plugin, port 15672). Virtual hosts hold queues, which open on
 * the messages at their head: read with `ackmode=ack_requeue_true`, so they go back on the queue (marked redelivered).
 * Adding a record publishes a message to the queue through the default exchange; messages aren't changed or deleted one
 * by one. The console sends management API requests.
 */
class RabbitClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("RabbitMQ", config, host, port)
        rest.authorization = RestClient.basic(config.user.trim().ifEmpty { "guest" }, password ?: if (config.user.isBlank()) "guest" else "")
        return Connection(config, rest).also { it.overview() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        private fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
            val response = rest.send(method, path, body?.toString())
            if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "RabbitMQ", reason(response)))
            if (!response.ok) throw IOException("RabbitMQ: ${reason(response)}")
            return response.json()
        }

        internal fun overview(): String {
            val json = runCatching { call("GET", "/api/overview").jsonObject }.getOrElse {
                if (it.message?.contains("credentials") == true) throw it
                throw IOException(tr("{0} doesn't look like the RabbitMQ management API: {1}", rest.base, it.message))
            }
            return "RabbitMQ ${json["rabbitmq_version"]?.jsonPrimitive?.contentOrNull.orEmpty()} (${json["cluster_name"]?.jsonPrimitive?.contentOrNull.orEmpty()})"
        }

        override suspend fun server(): String = io { overview() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                when (parent.size) {
                    0 -> call("GET", "/api/vhosts").jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.sorted()
                        .map { RecordContainer(listOf(it), "vhost", browsable = false, hasChildren = true) }
                    1 -> {
                        val vhost = segment(parent[0])
                        val queues = call("GET", "/api/queues/$vhost?columns=name,messages,type").jsonArray.map { it.jsonObject }.map { q ->
                            RecordContainer(parent + q["name"]!!.jsonPrimitive.content, "queue", q["messages"]?.jsonPrimitive?.longOrNull, q["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "classic" })
                        }
                        val exchanges = call("GET", "/api/exchanges/$vhost").jsonArray.map { it.jsonObject }
                            .filter { it["name"]!!.jsonPrimitive.content.let { n -> n.isNotEmpty() && !n.startsWith("amq.") } }
                            .map { RecordContainer(parent + it["name"]!!.jsonPrimitive.content, "exchange", detail = it["type"]?.jsonPrimitive?.contentOrNull, browsable = false) }
                        queues.sortedBy { it.name } + exchanges.sortedBy { it.name }
                    }
                    else -> emptyList()
                }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val (vhost, queue) = container
                val info = call("GET", "/api/queues/${segment(vhost)}/${segment(queue)}").jsonObject
                val bindings = call("GET", "/api/queues/${segment(vhost)}/${segment(queue)}/bindings")
                JsonTables.pretty(buildJsonObject {
                    listOf("name", "vhost", "type", "durable", "auto_delete", "exclusive", "arguments", "messages", "consumers", "policy").forEach { k -> info[k]?.let { put(k, it) } }
                    put("bindings", bindings)
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("Peek"),
                tr("Nothing to fill in: the queue's first messages are read and put back"),
                tr("Messages are read from the head of the queue and requeued at once, so nothing is lost; they come back marked redelivered."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val (vhost, queue) = container
                val body = buildJsonObject { put("count", limit); put("ackmode", "ack_requeue_true"); put("encoding", "auto"); put("truncate", 100_000) }
                val messages = call("POST", "/api/queues/${segment(vhost)}/${segment(queue)}/get", body).jsonArray.map { it.jsonObject }
                RecordPage(messages.map { message ->
                    val document = buildJsonObject {
                        put("payload", message["payload"] ?: JsonNull)
                        put("routing_key", message["routing_key"] ?: JsonNull)
                        put("exchange", message["exchange"] ?: JsonNull)
                        put("properties", message["properties"] ?: JsonNull)
                    }
                    Record(null, document, buildJsonObject { put("redelivered", message["redelivered"] ?: JsonNull); put("payload_encoding", message["payload_encoding"] ?: JsonNull) })
                }, null, messages.firstOrNull()?.get("message_count")?.jsonPrimitive?.longOrNull?.plus(1))
            }

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("payload", ""); put("properties", JsonObject(emptyMap())) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                if (key != null) throw IOException(tr("Messages can't be changed; publish a new one."))
                val (vhost, queue) = container
                val payload = document["payload"].let { if (it is JsonPrimitive && it.isString) it.content else it?.toString() ?: JsonTables.pretty(document) }
                val reply = call("POST", "/api/exchanges/${segment(vhost)}/amq.default/publish", buildJsonObject {
                    put("properties", document["properties"] as? JsonObject ?: JsonObject(emptyMap()))
                    put("routing_key", queue)
                    put("payload", payload)
                    put("payload_encoding", "string")
                }).jsonObject
                if (reply["routed"]?.jsonPrimitive?.booleanOrNull != true) throw IOException(tr("The message wasn't routed to {0}.", queue))
                JsonObject(emptyMap())
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long = throw IOException(tr("Messages aren't deleted one by one; purge the queue from the console."))

        override val consoleLanguage =
            RecordLanguage(
                tr("HTTP"),
                "GET /api/queues",
                tr("Requests to the RabbitMQ management API: a method and a path, then a JSON body. Lists show as rows, the rest as JSON."),
            )

        override fun sampleStatement(container: List<String>): String =
            when (container.size) {
                0 -> "GET /api/overview"
                1 -> "GET /api/queues/${segment(container[0])}"
                else -> "GET /api/queues/${segment(container[0])}/${segment(container[1])}/bindings"
            }

        override suspend fun execute(statement: String): ConsoleReply = io { HttpConsole.run(rest, statement) }

        /** GET reads; everything else (a get from a queue included, which redelivers) counts as a write. */
        override fun isRead(statement: String): Boolean = HttpConsole.isRead(statement, emptyList())

        override fun close() = Unit
    }

    companion object {
        private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        internal fun reason(response: RestResponse): String {
            val json = runCatching { response.json() }.getOrNull() as? JsonObject
            return "${(json?.get("reason") ?: json?.get("error"))?.jsonPrimitive?.contentOrNull ?: response.body.take(200)} (${response.status})"
        }
    }
}

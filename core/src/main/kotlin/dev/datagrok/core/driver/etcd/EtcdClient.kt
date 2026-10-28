package dev.datagrok.core.driver.etcd

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.KeyEdit
import dev.datagrok.core.driver.KeyInfo
import dev.datagrok.core.driver.KeyPage
import dev.datagrok.core.driver.KeyType
import dev.datagrok.core.driver.KeyValue
import dev.datagrok.core.driver.KeyValueClient
import dev.datagrok.core.driver.KeyValueConnection
import dev.datagrok.core.driver.Keyspace
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * etcd through its v3 HTTP/JSON gateway, which every etcd server serves next to gRPC: no client library to download.
 * Keys and values travel base64-encoded and int64s as strings, as the gateway does. Properties: `ssl=true` for HTTPS,
 * `verifyPeer=false` to accept a self-signed certificate. A user and password authenticate for a token.
 */
class EtcdClient : KeyValueClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): KeyValueConnection {
        val tls = config.properties["ssl"]?.toBoolean() == true
        val http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).apply {
                if (tls && config.properties["verifyPeer"]?.toBoolean() == false) sslContext(trustingEverything())
            }.build()
        val connection = Connection(config, http, "${if (tls) "https" else "http"}://${formatHost(host)}:$port")
        val user = config.user.trim()
        if (user.isNotEmpty()) connection.authenticate(user, password.orEmpty())
        connection.version()
        return connection
    }

    private fun formatHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

    private fun trustingEverything(): SSLContext =
        SSLContext.getInstance("TLS").apply {
            val trustAll =
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }

    internal class Connection(override val config: ConnectionConfig, private val http: HttpClient, private val base: String) : KeyValueConnection {
        private var token: String? = null

        fun authenticate(user: String, password: String) {
            token = post("/v3/auth/authenticate", buildJsonObject { put("name", user); put("password", password) })["token"]?.jsonPrimitive?.content
        }

        /** One gateway call. Errors come back as JSON with a message, which becomes the exception's. */
        private fun post(path: String, body: JsonObject): JsonObject {
            val request = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            token?.let { request.header("Authorization", it) }
            val response =
                try {
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString())
                } catch (e: IOException) {
                    throw IOException("Couldn't reach etcd at $base: ${e.message ?: e.javaClass.simpleName}", e)
                }
            val json = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            if (response.statusCode() != 200) {
                val message = json?.get("message")?.jsonPrimitive?.content ?: json?.get("error")?.jsonPrimitive?.content ?: response.body().take(200)
                throw IOException("etcd: $message")
            }
            return json ?: throw IOException("etcd sent something that isn't JSON: ${response.body().take(200)}")
        }

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        override suspend fun server(): String = io { version() }

        /** `etcd 3.5.17`, from `/version`; also how connecting checks the server is etcd. */
        internal fun version(): String {
            val request = HttpRequest.newBuilder(URI("$base/version")).timeout(Duration.ofSeconds(10)).GET().build()
            val body =
                try {
                    http.send(request, HttpResponse.BodyHandlers.ofString()).body()
                } catch (e: IOException) {
                    throw IOException("Couldn't reach etcd at $base: ${e.message ?: e.javaClass.simpleName}", e)
                }
            val version = runCatching { Json.parseToJsonElement(body).jsonObject["etcdserver"]?.jsonPrimitive?.content }.getOrNull()
                ?: throw IOException("$base doesn't look like etcd: ${body.take(100)}")
            return "etcd $version"
        }

        override suspend fun keyspaces(): List<Keyspace> =
            io {
                // The whole keyspace: from the lowest key ("\0") to the end ("\0" as range_end).
                val count = post("/v3/kv/range", buildJsonObject { put("key", b64(byteArrayOf(0))); put("range_end", b64(byteArrayOf(0))); put("count_only", true) })
                listOf(Keyspace(0, count.long("count")))
            }

        override suspend fun scan(keyspace: Int, pattern: String, cursor: String?, count: Int): KeyPage =
            io {
                single(keyspace)
                // etcd ranges by prefix; the rest of the glob is matched here.
                val glob = Glob(pattern.ifEmpty { "*" })
                val prefix = glob.prefix.toByteArray()
                val start = cursor?.let { Base64.getDecoder().decode(it) } ?: prefix
                val reply =
                    post(
                        "/v3/kv/range",
                        buildJsonObject {
                            put("key", b64(start))
                            put("range_end", b64(prefixEnd(prefix)))
                            put("limit", count)
                            put("keys_only", true)
                        },
                    )
                val kvs = reply["kvs"]?.jsonArray.orEmpty().map { it.jsonObject }
                val keys =
                    kvs.mapNotNull { kv ->
                        val name = String(unb64(kv.string("key")))
                        if (!glob.matches(name)) null else KeyInfo(name, KeyType.Text, null)
                    }
                val more = reply["more"]?.jsonPrimitive?.content == "true"
                val next = if (more && kvs.isNotEmpty()) Base64.getEncoder().encodeToString(unb64(kvs.last().string("key")) + byteArrayOf(0)) else null
                KeyPage(keys, next)
            }

        override suspend fun read(keyspace: Int, key: String, limit: Int): KeyValue? =
            io {
                single(keyspace)
                val kv = post("/v3/kv/range", buildJsonObject { put("key", b64(key)) })["kvs"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@io null
                val value = String(unb64(kv["value"]?.jsonPrimitive?.content.orEmpty()))
                val ttl = kv["lease"]?.jsonPrimitive?.content?.takeIf { it != "0" }?.let { lease -> ttlOf(lease)?.times(1_000) }
                val truncated = value.length > TextLimit
                KeyValue.Text(key, ttl, if (truncated) value.take(TextLimit) else value, truncated)
            }

        private fun ttlOf(lease: String): Long? = post("/v3/lease/timetolive", buildJsonObject { put("ID", lease) }).long("TTL")?.takeIf { it >= 0 }

        override suspend fun edit(keyspace: Int, key: String, edit: KeyEdit) =
            io {
                single(keyspace)
                val value = (edit as? KeyEdit.SetText)?.value ?: throw IllegalArgumentException("etcd keys hold plain values.")
                // Existing keys keep their lease (and so their TTL); new ones get none.
                val exists = post("/v3/kv/range", buildJsonObject { put("key", b64(key)); put("count_only", true) }).long("count") == 1L
                post("/v3/kv/put", buildJsonObject { put("key", b64(key)); put("value", b64(value)); if (exists) put("ignore_lease", true) })
                Unit
            }

        override suspend fun delete(keyspace: Int, keys: List<String>): Long =
            io {
                single(keyspace)
                keys.sumOf { key -> post("/v3/kv/deleterange", buildJsonObject { put("key", b64(key)) }).long("deleted") ?: 0 }
            }

        /** One transaction: only if [to] doesn't exist, write it with [from]'s value and lease, then delete [from]. */
        override suspend fun rename(keyspace: Int, from: String, to: String) =
            io {
                single(keyspace)
                val kv = post("/v3/kv/range", buildJsonObject { put("key", b64(from)) })["kvs"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw IllegalStateException("$from no longer exists.")
                val reply =
                    post(
                        "/v3/kv/txn",
                        buildJsonObject {
                            put("compare", buildJsonArray { add(buildJsonObject { put("key", b64(to)); put("result", "EQUAL"); put("target", "CREATE"); put("create_revision", "0") }) })
                            put(
                                "success",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "request_put",
                                                buildJsonObject {
                                                    put("key", b64(to))
                                                    put("value", kv["value"] ?: JsonPrimitive(""))
                                                    kv["lease"]?.let { put("lease", it) }
                                                },
                                            )
                                        }
                                    )
                                    add(buildJsonObject { put("request_delete_range", buildJsonObject { put("key", b64(from)) }) })
                                },
                            )
                        },
                    )
                if (reply["succeeded"]?.jsonPrimitive?.content != "true") throw IllegalStateException("A key named \"$to\" already exists.")
            }

        /** A TTL is a lease: grant one and attach it, or detach the key from its lease to keep it forever. */
        override suspend fun expire(keyspace: Int, key: String, ttlMillis: Long?) =
            io {
                single(keyspace)
                val lease =
                    if (ttlMillis == null) "0"
                    else post("/v3/lease/grant", buildJsonObject { put("TTL", ((ttlMillis + 999) / 1_000).coerceAtLeast(1)) }).string("ID")
                post("/v3/kv/put", buildJsonObject { put("key", b64(key)); put("lease", lease); put("ignore_value", true) })
                Unit
            }

        /**
         * etcdctl's everyday commands: `get KEY [--prefix] [--keys-only] [--limit=N]`, `put KEY VALUE [--lease=ID]`, `del KEY
         * [--prefix]`, `lease grant|revoke|timetolive|list`, `member list`, `endpoint status`, `version`.
         */
        override suspend fun command(keyspace: Int, arguments: List<String>): String =
            io {
                try {
                    run(arguments)
                } catch (e: IOException) {
                    "Error: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    "Error: ${e.message}"
                }
            }

        private fun run(arguments: List<String>): String {
            val flags = arguments.filter { it.startsWith("--") }.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
            val args = arguments.filterNot { it.startsWith("--") }
            fun arg(i: Int) = args.getOrNull(i) ?: throw IllegalArgumentException("Usage: ${Usage[args.first().lowercase()] ?: args.joinToString(" ")}")
            return when (args.firstOrNull()?.lowercase()) {
                "get" -> {
                    val key = arg(1).toByteArray()
                    val reply =
                        post(
                            "/v3/kv/range",
                            buildJsonObject {
                                put("key", b64(key))
                                if ("prefix" in flags) put("range_end", b64(prefixEnd(key)))
                                flags["limit"]?.toLongOrNull()?.let { put("limit", it) }
                                if ("keys-only" in flags) put("keys_only", true)
                            },
                        )
                    reply["kvs"]?.jsonArray.orEmpty().joinToString("\n") { kv ->
                        val name = String(unb64(kv.jsonObject.string("key")))
                        if ("keys-only" in flags) name else name + "\n" + String(unb64(kv.jsonObject["value"]?.jsonPrimitive?.content.orEmpty()))
                    }
                }
                "put" -> {
                    post("/v3/kv/put", buildJsonObject { put("key", b64(arg(1))); put("value", b64(arg(2))); flags["lease"]?.let { put("lease", leaseId(it)) } })
                    "OK"
                }
                "del" -> {
                    val key = arg(1).toByteArray()
                    post("/v3/kv/deleterange", buildJsonObject { put("key", b64(key)); if ("prefix" in flags) put("range_end", b64(prefixEnd(key))) }).long("deleted")?.toString() ?: "0"
                }
                "lease" ->
                    when (arg(1).lowercase()) {
                        "grant" -> {
                            val reply = post("/v3/lease/grant", buildJsonObject { put("TTL", arg(2).toLong()) })
                            "lease ${reply.string("ID").toULong().toString(16)} granted with TTL(${reply.string("TTL")}s)"
                        }
                        "revoke" -> {
                            post("/v3/lease/revoke", buildJsonObject { put("ID", leaseId(arg(2))) })
                            "lease ${arg(2)} revoked"
                        }
                        "timetolive" -> {
                            val reply = post("/v3/lease/timetolive", buildJsonObject { put("ID", leaseId(arg(2))) })
                            if ((reply.long("TTL") ?: -1) < 0) "lease ${arg(2)} already expired"
                            else "lease ${arg(2)} granted with TTL(${reply.string("grantedTTL")}s), remaining(${reply.string("TTL")}s)"
                        }
                        "list" -> post("/v3/lease/leases", buildJsonObject {})["leases"]?.jsonArray.orEmpty().joinToString("\n") { it.jsonObject.string("ID").toULong().toString(16) }
                            .ifEmpty { "found 0 leases" }
                        else -> throw IllegalArgumentException("Usage: ${Usage["lease"]}")
                    }
                "member" ->
                    post("/v3/cluster/member/list", buildJsonObject {})["members"]?.jsonArray.orEmpty().joinToString("\n") { m ->
                        val member = m.jsonObject
                        listOf(
                            member.string("ID").toULong().toString(16),
                            member["name"]?.jsonPrimitive?.content.orEmpty(),
                            member.list("peerURLs"),
                            member.list("clientURLs"),
                        ).joinToString(", ")
                    }
                "endpoint" -> {
                    val status = post("/v3/maintenance/status", buildJsonObject {})
                    "$base, version ${status.string("version")}, db size ${status.string("dbSize")} bytes, leader ${status.string("leader").toULong().toString(16)}, " +
                        "raft term ${status.string("raftTerm")}, raft index ${status.string("raftIndex")}"
                }
                "version" -> version()
                null -> ""
                else -> throw IllegalArgumentException("Unknown command ${args.first()}. Try: ${Usage.values.joinToString(" · ")}")
            }
        }

        private fun single(keyspace: Int) = require(keyspace == 0) { "etcd has a single keyspace." }

        override fun close() = Unit
    }

    internal companion object {
        const val TextLimit = 1_000_000

        /** etcdctl prints lease IDs in hex; the gateway wants them in decimal. */
        fun leaseId(text: String): String = (text.toULongOrNull(16) ?: throw IllegalArgumentException("$text isn't a lease ID")).toString()

        val Usage =
            linkedMapOf(
                "get" to "get KEY [--prefix] [--keys-only] [--limit=N]",
                "put" to "put KEY VALUE [--lease=ID]",
                "del" to "del KEY [--prefix]",
                "lease" to "lease grant TTL | revoke ID | timetolive ID | list",
                "member" to "member list",
                "endpoint" to "endpoint status",
            )

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun b64(text: String): String = b64(text.toByteArray())

        fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

        /** The end of a prefix range: the prefix with its last byte below 0xff incremented, or "\0" (no end) for all keys. */
        fun prefixEnd(prefix: ByteArray): ByteArray {
            val end = prefix.copyOf()
            for (i in end.indices.reversed()) {
                if (end[i] != 0xff.toByte()) {
                    end[i]++
                    return end.copyOf(i + 1)
                }
            }
            return byteArrayOf(0)
        }

        private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content ?: throw IOException("etcd's reply has no $name")

        private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L.takeIf { name !in this }

        private fun JsonObject.list(name: String): String = (this[name] as? JsonArray)?.joinToString(",") { it.jsonPrimitive.content }.orEmpty()
    }
}

/** A Redis-style glob (`*`, `?`, `[abc]`, `\` escapes): the literal prefix etcd can range over, and a matcher for the rest. */
internal class Glob(pattern: String) {
    val prefix: String
    private val regex: Regex

    init {
        val literal = StringBuilder()
        val expression = StringBuilder()
        var wild = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    i++
                    if (!wild) literal.append(pattern[i])
                    expression.append(Regex.escape(pattern[i].toString()))
                }
                c == '*' -> { wild = true; expression.append(".*") }
                c == '?' -> { wild = true; expression.append('.') }
                c == '[' -> {
                    wild = true
                    val close = pattern.indexOf(']', i + 1)
                    if (close < 0) expression.append("\\[") else {
                        expression.append('[').append(pattern.substring(i + 1, close).replace("\\", "\\\\")).append(']')
                        i = close
                    }
                }
                else -> {
                    if (!wild) literal.append(c)
                    expression.append(Regex.escape(c.toString()))
                }
            }
            i++
        }
        prefix = literal.toString()
        regex = Regex(expression.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    fun matches(key: String): Boolean = regex.matches(key)
}

package dev.datagrok.engines.redis

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.KeyEdit
import dev.datagrok.core.driver.KeyInfo
import dev.datagrok.core.driver.KeyPage
import dev.datagrok.core.driver.KeyType
import dev.datagrok.core.driver.KeyValue
import dev.datagrok.core.driver.KeyValueClient
import dev.datagrok.core.driver.KeyValueConnection
import dev.datagrok.core.driver.Keyspace
import dev.datagrok.core.driver.StreamEntry
import io.lettuce.core.ClientOptions
import io.lettuce.core.Limit
import io.lettuce.core.Range
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.sync.RedisClusterCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.CommandOutput
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.ProtocolKeyword
import io.lettuce.core.protocol.ProtocolVersion
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Datagrok's Redis adapter over Lettuce: standalone servers, Sentinel and Cluster, with TLS and ACL users. Profile
 * properties choose the mode: `mode=cluster` (hosts are seed nodes), `mode=sentinel` with `sentinelMaster` (hosts are
 * sentinels), `ssl=true`, `verifyPeer=false`.
 */
class RedisClient : KeyValueClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): KeyValueConnection {
        val properties = config.properties
        val tls = properties["ssl"]?.toBoolean() == true
        val user = config.user.trim().takeIf { it.isNotEmpty() }
        fun uri(builder: RedisURI.Builder): RedisURI {
            builder.withSsl(tls).withTimeout(Duration.ofSeconds(10))
            if (tls && properties["verifyPeer"]?.toBoolean() == false) builder.withVerifyPeer(false)
            if (!password.isNullOrEmpty()) {
                if (user != null) builder.withAuthentication(user, password) else builder.withPassword(password.toCharArray())
            }
            return builder.build()
        }
        val seeds = hosts(config.host, host, port)
        return when (properties["mode"]?.lowercase()) {
            "cluster" -> {
                val client = RedisClusterClient.create(seeds.map { (h, p) -> uri(RedisURI.Builder.redis(h, p)) })
                client.setOptions(ClusterClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build())
                val connection = try { client.connect() } catch (e: Throwable) { client.shutdown(); throw e }
                Connection(config, connection, connection.sync(), cluster = true) { client.shutdown() }
            }
            else -> {
                val master = properties["sentinelMaster"]
                val target =
                    if (properties["mode"]?.lowercase() == "sentinel") {
                        requireNotNull(master) { "Sentinel mode needs the sentinelMaster property (the monitored master's name)." }
                        val builder = RedisURI.Builder.sentinel(seeds.first().first, seeds.first().second, master)
                        seeds.drop(1).forEach { (h, p) -> builder.withSentinel(h, p) }
                        uri(builder)
                    } else uri(RedisURI.Builder.redis(host, port))
                val client = io.lettuce.core.RedisClient.create(target)
                client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build())
                val connection = try { client.connect() } catch (e: Throwable) { client.shutdown(); throw e }
                Connection(config, connection, connection.sync(), cluster = false) { client.shutdown() }
            }
        }
    }

    /** Seed nodes or sentinels: the profile's host list, or the (tunnelled) host and port for a single one. */
    private fun hosts(list: String, host: String, port: Int): List<Pair<String, Int>> {
        val entries = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.size <= 1) return listOf(host to port)
        return entries.map { entry -> entry.substringBeforeLast(':') to (entry.substringAfterLast(':', "").toIntOrNull() ?: port) }
    }

    private class Connection(
        override val config: ConnectionConfig,
        private val connection: StatefulConnection<String, String>,
        private val redis: RedisClusterCommands<String, String>,
        private val cluster: Boolean,
        private val shutdown: () -> Unit,
    ) : KeyValueConnection {
        /** SELECT is per connection, so every call that depends on it runs alone. */
        private val lock = Mutex()
        private var selected = 0
        /** Cluster scans keep per-node state in the cursor object, so the browser gets a token for it. */
        private val cursors = ConcurrentHashMap<String, ScanCursor>()

        private suspend fun <T> on(keyspace: Int, block: RedisClusterCommands<String, String>.() -> T): T =
            lock.withLock {
                withContext(Dispatchers.IO) {
                    if (cluster) require(keyspace == 0) { "A Redis cluster has database 0 only." }
                    else if (keyspace != selected) {
                        (connection as StatefulRedisConnection<String, String>).sync().select(keyspace)
                        selected = keyspace
                    }
                    redis.block()
                }
            }

        override suspend fun server(): String =
            on(selected) {
                val info = parseInfo(info("server"))
                val (name, version) =
                    when {
                        info["server_name"] == "valkey" || "valkey_version" in info -> "Valkey" to info["valkey_version"]
                        "dragonfly_version" in info -> "Dragonfly" to info["dragonfly_version"]
                        "keydb_version" in info -> "KeyDB" to info["keydb_version"]
                        else -> "Redis" to info["redis_version"]
                    }
                listOfNotNull(name, version).joinToString(" ") + (info["redis_mode"]?.takeIf { it != "standalone" }?.let { " ($it)" } ?: "")
            }

        override suspend fun keyspaces(): List<Keyspace> =
            on(selected) {
                val counts =
                    parseInfo(info("keyspace")).mapNotNull { (name, value) ->
                        val index = name.removePrefix("db").toIntOrNull() ?: return@mapNotNull null
                        index to (value.split(',').firstOrNull { it.startsWith("keys=") }?.removePrefix("keys=")?.toLongOrNull() ?: 0)
                    }.toMap()
                if (cluster) return@on listOf(Keyspace(0, counts[0] ?: 0))
                // Managed services often forbid CONFIG; 16 is the default.
                val databases = runCatching { configGet("databases")["databases"]?.toInt() }.getOrNull() ?: 16
                (0 until databases).map { Keyspace(it, counts[it] ?: 0) }
            }

        override suspend fun scan(keyspace: Int, pattern: String, cursor: String?, count: Int): KeyPage =
            on(keyspace) {
                val previous = cursor?.let { cursors.remove(it) ?: ScanCursor.of(it) } ?: ScanCursor.INITIAL
                val result = scan(previous, ScanArgs.Builder.matches(pattern.ifEmpty { "*" }).limit(count.toLong()))
                val async = connection.let { if (it is StatefulRedisClusterConnection) it.async() else (it as StatefulRedisConnection).async() }
                val types = result.keys.map { async.type(it) }
                val ttls = result.keys.map { async.pttl(it) }
                val keys =
                    result.keys.mapIndexed { i, key ->
                        KeyInfo(key, keyType(types[i].get()), ttls[i].get().takeIf { it >= 0 })
                    }
                val next =
                    if (result.isFinished) null
                    else UUID.randomUUID().toString().also { token -> cursors[token] = result }
                KeyPage(keys.sortedBy { it.name }, next)
            }

        override suspend fun read(keyspace: Int, key: String, limit: Int): KeyValue? =
            on(keyspace) {
                val type = type(key)
                if (type == "none") return@on null
                val ttl = pttl(key).takeIf { it >= 0 }
                when (type) {
                    "string" -> {
                        val length = strlen(key)
                        val max = TextLimit
                        if (length > max) KeyValue.Text(key, ttl, getrange(key, 0, max - 1), truncated = true)
                        else KeyValue.Text(key, ttl, get(key).orEmpty(), truncated = false)
                    }
                    "hash" -> {
                        val fields = mutableListOf<Pair<String, String>>()
                        var cursor: ScanCursor = ScanCursor.INITIAL
                        do {
                            val page = hscan(key, cursor, ScanArgs.Builder.limit(500))
                            page.map.forEach { (f, v) -> if (fields.size < limit) fields += f to v }
                            cursor = page
                        } while (!page.isFinished && fields.size < limit)
                        KeyValue.Hash(key, ttl, fields.sortedBy { it.first }, hlen(key))
                    }
                    "list" -> KeyValue.ListValue(key, ttl, lrange(key, 0, limit - 1L), llen(key))
                    "set" -> {
                        val members = mutableListOf<String>()
                        var cursor: ScanCursor = ScanCursor.INITIAL
                        do {
                            val page = sscan(key, cursor, ScanArgs.Builder.limit(500))
                            page.values.forEach { if (members.size < limit) members += it }
                            cursor = page
                        } while (!page.isFinished && members.size < limit)
                        KeyValue.SetValue(key, ttl, members.sorted(), scard(key))
                    }
                    "zset" -> KeyValue.SortedSet(key, ttl, zrangeWithScores(key, 0, limit - 1L).map { it.value to it.score }, zcard(key))
                    "stream" ->
                        KeyValue.Stream(
                            key,
                            ttl,
                            xrange(key, Range.create("-", "+"), Limit.from(limit.toLong())).map { m -> StreamEntry(m.id, m.body.toList()) },
                            xlen(key),
                        )
                    "ReJSON-RL" -> KeyValue.Other(key, ttl, "json", raw(listOf("JSON.GET", key)))
                    else -> KeyValue.Other(key, ttl, type, "")
                }
            }

        override suspend fun edit(keyspace: Int, key: String, edit: KeyEdit) =
            on(keyspace) {
                when (edit) {
                    is KeyEdit.SetText -> set(key, edit.value, SetArgs().keepttl())
                    is KeyEdit.SetField -> hset(key, edit.field, edit.value)
                    is KeyEdit.DeleteField -> hdel(key, edit.field)
                    is KeyEdit.SetItem -> lset(key, edit.index, edit.value)
                    is KeyEdit.AppendItem -> rpush(key, edit.value)
                    is KeyEdit.RemoveItem -> lrem(key, 1, edit.value)
                    is KeyEdit.AddMember -> sadd(key, edit.member)
                    is KeyEdit.RemoveMember -> srem(key, edit.member)
                    is KeyEdit.SetScore -> zadd(key, edit.score, edit.member)
                    is KeyEdit.AddEntry -> xadd(key, edit.fields.toMap())
                    is KeyEdit.DeleteEntry -> xdel(key, edit.id)
                }
                Unit
            }

        override suspend fun delete(keyspace: Int, keys: List<String>): Long = if (keys.isEmpty()) 0 else on(keyspace) { del(*keys.toTypedArray()) }

        override suspend fun rename(keyspace: Int, from: String, to: String) =
            on(keyspace) { if (!renamenx(from, to)) throw IllegalStateException("A key named \"$to\" already exists.") }

        override suspend fun expire(keyspace: Int, key: String, ttlMillis: Long?) =
            on(keyspace) {
                if (ttlMillis == null) persist(key) else pexpire(key, ttlMillis)
                Unit
            }

        override suspend fun command(keyspace: Int, arguments: List<String>): String {
            val name = arguments.firstOrNull()?.uppercase() ?: return ""
            if (name in Unsupported) return "(error) $name keeps the connection busy; it isn't available in the console."
            if (name == "SELECT") {
                val index = arguments.getOrNull(1)?.toIntOrNull() ?: return "(error) ERR invalid DB index"
                return runCatching { on(index) { "OK" } }.getOrElse { "(error) ${it.message}" }
            }
            return on(keyspace) { raw(arguments) }
        }

        /** Runs any command and formats its reply as redis-cli would. */
        private fun RedisClusterCommands<String, String>.raw(arguments: List<String>): String {
            val keyword =
                object : ProtocolKeyword {
                    private val bytes = arguments.first().uppercase().toByteArray()

                    override fun getBytes() = bytes

                    override fun toString() = arguments.first().uppercase()
                }
            val args = CommandArgs(StringCodec.UTF8).apply { arguments.drop(1).forEach { add(it) } }
            return try {
                val reply = dispatch(keyword, ReplyOutput(), args)
                // Lettuce hands status replies over like bulk strings; redis-cli shows them bare.
                format(if (reply is Bulk && keyword.toString() in StatusCommands) Status(reply.text) else reply)
            } catch (e: RedisCommandExecutionException) {
                "(error) ${e.message}"
            }
        }

        override fun close() {
            runCatching { connection.close() }
            shutdown()
        }
    }

    /** A reply kept as its shape: status replies, bulk strings, integers, nils and nested arrays. */
    private class ReplyOutput : CommandOutput<String, String, Any?>(StringCodec.UTF8, null) {
        private val stack = ArrayDeque<MutableList<Any?>>()
        private var depth = 0

        private fun add(value: Any?) {
            if (stack.isEmpty()) output = value else stack.last().add(value)
        }

        override fun set(bytes: ByteBuffer?) = add(bytes?.let { Bulk(StringCodec.UTF8.decodeValue(it)) })

        override fun setSingle(bytes: ByteBuffer?) = add(bytes?.let { Status(StringCodec.UTF8.decodeValue(it)) })

        override fun set(integer: Long) = add(integer)

        override fun set(number: Double) = add(Bulk(number.toString()))

        override fun set(value: Boolean) = add(if (value) 1L else 0L)

        override fun multi(count: Int) {
            val list = ArrayList<Any?>(count.coerceAtLeast(0))
            add(list)
            stack.addLast(list)
            depth++
        }

        override fun complete(depth: Int) {
            if (depth > 0 && depth < this.depth) {
                stack.removeLastOrNull()
                this.depth--
            }
        }
    }

    private data class Bulk(val text: String)

    private data class Status(val text: String)

    companion object {
        /** Strings longer than this are shown cut; editing them in full belongs in the console. */
        const val TextLimit = 1_000_000L

        /** Commands whose reply is a status (`OK`, `PONG`, a type name), shown without quotes. */
        private val StatusCommands =
            setOf(
                "SET", "MSET", "SETEX", "PSETEX", "FLUSHDB", "FLUSHALL", "PING", "TYPE", "RENAME", "LSET", "LTRIM", "HMSET", "SAVE", "BGSAVE",
                "BGREWRITEAOF", "SWAPDB", "SELECT", "AUTH", "MULTI", "DISCARD", "WATCH", "UNWATCH", "RESTORE", "PFMERGE", "MIGRATE", "RESET",
                "CONFIG", "CLIENT", "SCRIPT", "FUNCTION", "MODULE", "REPLICAOF", "SLAVEOF", "XGROUP", "JSON.SET",
            )

        /** Commands that would take over the shared connection. */
        private val Unsupported = setOf("SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "MONITOR", "SYNC", "PSYNC", "BLPOP", "BRPOP", "BLMOVE", "BZPOPMIN", "BZPOPMAX", "XREAD", "XREADGROUP", "WAIT")

        internal fun keyType(type: String): KeyType =
            when (type) {
                "string" -> KeyType.Text
                "hash" -> KeyType.Hash
                "list" -> KeyType.List
                "set" -> KeyType.Set
                "zset" -> KeyType.SortedSet
                "stream" -> KeyType.Stream
                "ReJSON-RL" -> KeyType.Json
                else -> KeyType.Other
            }

        internal fun parseInfo(text: String): Map<String, String> =
            text.lines().map { it.trim() }.filter { ':' in it && !it.startsWith("#") }.associate { it.substringBefore(':') to it.substringAfter(':') }

        internal fun format(reply: Any?): String =
            when (reply) {
                null -> "(nil)"
                is Long -> "(integer) $reply"
                is Status -> reply.text
                is Bulk -> quote(reply.text)
                is List<*> ->
                    if (reply.isEmpty()) "(empty array)"
                    else {
                        val width = reply.size.toString().length
                        reply.mapIndexed { i, item ->
                            val label = "${(i + 1).toString().padStart(width)}) "
                            val body = format(item)
                            val indent = " ".repeat(label.length)
                            label + body.replace("\n", "\n$indent")
                        }.joinToString("\n")
                    }
                else -> reply.toString()
            }

        private fun quote(text: String): String =
            buildString {
                append('"')
                text.forEach { c ->
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '\r' -> append("\\r")
                        c == '\t' -> append("\\t")
                        c.code < 0x20 || c.code == 0x7f -> append("\\x%02x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }
    }
}

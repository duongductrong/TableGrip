package dev.tablegrip.engines.redis

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.KeyEdit
import dev.tablegrip.core.driver.KeyType
import dev.tablegrip.core.driver.KeyValue
import dev.tablegrip.core.driver.KeyValueConnection
import dev.tablegrip.core.driver.KeyValueStore
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.drivers.DriverLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The adapter against real servers, skipped unless their properties are passed:
 * ```
 * docker run -d --name dg-it-redis -p 56379:6379 redis:7.4
 * docker run -d --name dg-it-valkey -p 56380:6379 valkey/valkey:8.1
 * ./gradlew :engines:redis:test -Pit.redis=localhost:56379 -Pit.valkey=localhost:56380
 * ```
 */
class RedisClientTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"))

    private fun server(
        property: String,
        type: DatabaseType,
        readOnly: Boolean = false,
        properties: Map<String, String> = emptyMap(),
        test: suspend (KeyValueConnection) -> Unit,
    ) = runBlocking<Unit> {
        val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), readOnly = readOnly, properties = properties)
        KeyValueStore.connect(config, password = null, drivers = drivers).use { test(it) }
    }

    @Test fun redis() = server("it.redis", DatabaseType.Redis) { contract(it, "Redis") }

    @Test fun valkey() = server("it.valkey", DatabaseType.Valkey) { contract(it, "Valkey") }

    /** `docker run -d --name dg-it-keydb -p 56381:6379 eqalpha/keydb`, `-Pit.keydb=localhost:56381` */
    @Test fun keydb() = server("it.keydb", DatabaseType.KeyDB) { contract(it, "KeyDB") }

    /** `docker run -d --name dg-it-dragonfly -p 56382:6379 --ulimit memlock=-1 docker.dragonflydb.io/dragonflydb/dragonfly`, `-Pit.dragonfly=localhost:56382` */
    @Test fun dragonfly() = server("it.dragonfly", DatabaseType.Dragonfly) { contract(it, "Dragonfly") }

    /**
     * A master and a sentinel on the host network, so the address the sentinel hands out is reachable:
     * `docker run -d --network host redis:7.4 redis-server --port 56390` and a sentinel on 56391 monitoring it as
     * `mymaster`; `-Pit.sentinel=localhost:56391`.
     */
    @Test
    fun sentinel() = server("it.sentinel", DatabaseType.Redis, properties = mapOf("mode" to "sentinel", "sentinelMaster" to "mymaster")) { redis ->
        assertTrue(redis.server().startsWith("Redis"), redis.server())
        redis.edit(3, "dg:sentinel", KeyEdit.SetText("through the sentinel"))
        assertEquals("through the sentinel", (redis.read(3, "dg:sentinel") as KeyValue.Text).value)
        assertEquals(1L, redis.delete(3, listOf("dg:sentinel")))
    }

    /** `docker run -d --name dg-it-rcluster -e IP=0.0.0.0 -e INITIAL_PORT=7100 -p 7100-7105:7100-7105 grokzen/redis-cluster:7.0.10`, `-Pit.rediscluster=localhost:7100` */
    @Test
    fun cluster() = server("it.rediscluster", DatabaseType.Redis, properties = mapOf("mode" to "cluster")) { redis ->
        assertTrue(redis.server().endsWith("(cluster)"), redis.server())
        assertEquals(listOf(0), redis.keyspaces().map { it.index })
        redis.command(0, listOf("FLUSHALL"))
        // Keys spread over all three primaries' slots.
        repeat(300) { redis.edit(0, "dg:c:$it", KeyEdit.SetText("$it")) }
        redis.edit(0, "dg:c:hash", KeyEdit.SetField("f", "v"))

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = redis.scan(0, "dg:c:*", cursor, count = 40)
            seen += page.keys.map { it.name }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals(301, seen.size, "every key once, across nodes")
        assertEquals(301, seen.toSet().size)

        assertEquals(listOf("f" to "v"), (redis.read(0, "dg:c:hash") as KeyValue.Hash).fields)
        assertEquals("\"7\"", redis.command(0, listOf("GET", "dg:c:7")))
        // Keys in different slots, deleted in one call.
        assertEquals(3L, redis.delete(0, listOf("dg:c:1", "dg:c:2", "dg:c:3")))
        assertFailsWith<IllegalArgumentException> { redis.read(1, "dg:c:4") }
        redis.command(0, listOf("FLUSHALL"))
    }

    @Test
    fun `read-only profiles refuse writes, also from the console`() = server("it.redis", DatabaseType.Redis, readOnly = true) { redis ->
        assertFailsWith<ReadOnlyViolationException> { redis.edit(0, "dg:ro", KeyEdit.SetText("x")) }
        assertFailsWith<ReadOnlyViolationException> { redis.command(0, listOf("SET", "dg:ro", "x")) }
        assertFailsWith<ReadOnlyViolationException> { redis.command(0, listOf("FLUSHALL")) }
        assertEquals("(nil)", redis.command(0, listOf("GET", "dg:ro-missing")))
    }

    private suspend fun scanAll(redis: KeyValueConnection, db: Int, pattern: String): List<String> {
        val keys = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = redis.scan(db, pattern, cursor)
            keys += page.keys.map { it.name }
            cursor = page.cursor
        } while (cursor != null)
        return keys
    }

    private suspend fun contract(redis: KeyValueConnection, name: String) {
        val db = 7
        assertTrue(redis.server().startsWith(name), redis.server())
        assertEquals("OK", redis.command(db, listOf("FLUSHDB")))

        redis.edit(db, "dg:text", KeyEdit.SetText("hello\nworld"))
        redis.edit(db, "dg:hash", KeyEdit.SetField("b", "2"))
        redis.edit(db, "dg:hash", KeyEdit.SetField("a", "1"))
        listOf("x", "y", "z").forEach { redis.edit(db, "dg:list", KeyEdit.AppendItem(it)) }
        redis.edit(db, "dg:set", KeyEdit.AddMember("m"))
        redis.edit(db, "dg:zset", KeyEdit.SetScore("low", 1.0))
        redis.edit(db, "dg:zset", KeyEdit.SetScore("high", 9.5))
        redis.edit(db, "dg:stream", KeyEdit.AddEntry(listOf("event" to "login")))
        repeat(300) { redis.edit(db, "dg:many:$it", KeyEdit.SetText("$it")) }

        val keyspaces = redis.keyspaces()
        assertEquals(16, keyspaces.size)
        assertEquals(306L, keyspaces[db].keys)

        // Every key comes back exactly once across pages, with its type.
        val seen = mutableListOf<String>()
        var cursor: String? = null
        val types = mutableMapOf<String, KeyType>()
        do {
            val page = redis.scan(db, "dg:*", cursor, count = 50)
            page.keys.forEach { seen += it.name; types[it.name] = it.type }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals(306, seen.toSet().size)
        assertEquals(
            mapOf("dg:text" to KeyType.Text, "dg:hash" to KeyType.Hash, "dg:list" to KeyType.List, "dg:set" to KeyType.Set, "dg:zset" to KeyType.SortedSet, "dg:stream" to KeyType.Stream),
            types.filterKeys { !it.startsWith("dg:many") },
        )
        // A single SCAN batch may hold no match at all; only the whole scan is complete.
        assertEquals(listOf("dg:hash"), scanAll(redis, db, "dg:h*"))

        assertEquals("hello\nworld", (redis.read(db, "dg:text") as KeyValue.Text).value)
        assertEquals(listOf("a" to "1", "b" to "2"), (redis.read(db, "dg:hash") as KeyValue.Hash).fields)
        val list = redis.read(db, "dg:list", limit = 2) as KeyValue.ListValue
        assertEquals(listOf("x", "y") to 3L, list.items to list.total)
        assertEquals(listOf("low" to 1.0, "high" to 9.5), (redis.read(db, "dg:zset") as KeyValue.SortedSet).members)
        val stream = redis.read(db, "dg:stream") as KeyValue.Stream
        assertEquals(listOf("event" to "login"), stream.entries.single().fields)
        assertNull(redis.read(db, "dg:missing"))

        redis.edit(db, "dg:list", KeyEdit.SetItem(0, "first"))
        redis.edit(db, "dg:list", KeyEdit.RemoveItem("y"))
        assertEquals(listOf("first", "z"), (redis.read(db, "dg:list") as KeyValue.ListValue).items)
        redis.edit(db, "dg:hash", KeyEdit.DeleteField("a"))
        redis.edit(db, "dg:stream", KeyEdit.DeleteEntry(stream.entries.single().id))
        assertEquals(0L, (redis.read(db, "dg:stream") as KeyValue.Stream).total)

        // Editing text keeps the TTL; TTL can be set and removed.
        redis.expire(db, "dg:text", 60_000)
        redis.edit(db, "dg:text", KeyEdit.SetText("changed"))
        assertTrue((redis.read(db, "dg:text")!!.ttlMillis ?: 0) > 50_000)
        redis.expire(db, "dg:text", null)
        assertNull(redis.read(db, "dg:text")!!.ttlMillis)

        redis.rename(db, "dg:text", "dg:renamed")
        assertFailsWith<IllegalStateException> { redis.rename(db, "dg:renamed", "dg:hash") }
        assertEquals(2L, redis.delete(db, listOf("dg:renamed", "dg:set", "dg:missing")))

        // The console formats replies like redis-cli, in the browser's database.
        assertEquals("OK", redis.command(db, listOf("SET", "dg:c", "a \"b\"")))
        assertEquals("\"a \\\"b\\\"\"", redis.command(db, listOf("GET", "dg:c")))
        assertEquals("(integer) 1", redis.command(db, listOf("INCR", "dg:n")))
        assertEquals("1) \"first\"\n2) \"z\"", redis.command(db, listOf("LRANGE", "dg:list", "0", "-1")))
        assertEquals("(empty array)", redis.command(db, listOf("LRANGE", "dg:none", "0", "-1")))
        assertTrue(redis.command(db, listOf("NOSUCHCOMMAND")).startsWith("(error) ERR unknown command"))
        assertTrue(redis.command(db, listOf("SUBSCRIBE", "x")).startsWith("(error)"))
        assertEquals("(nil)", redis.command(0, listOf("GET", "dg:c")), "keyspace 0 is another database")
        assertIs<String>(redis.command(db, listOf("INFO", "server")))
        assertEquals("OK", redis.command(db, listOf("FLUSHDB")))
    }
}

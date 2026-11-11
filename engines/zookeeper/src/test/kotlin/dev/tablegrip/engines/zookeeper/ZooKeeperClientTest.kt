package dev.tablegrip.engines.zookeeper

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.driver.RecordStore
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The ZooKeeper adapter. The server test is skipped unless the property is passed:
 * ```
 * docker run -d --name dg-it-zk -p 52181:2181 zookeeper:3.9.3
 * -Pit.zookeeper=localhost:52181
 * ```
 */
class ZooKeeperClientTest {
    @Test
    fun `paths and commands`() {
        assertEquals("/", ZooKeeperClient.znode(listOf("/")))
        assertEquals("/app/config", ZooKeeperClient.znode(listOf("/", "app", "config")))
        assertEquals("/a", ZooKeeperClient.child("/", "a"))
        assertTrue(ZooKeeperClient.isReadCommand("ls /app"))
        assertFalse(ZooKeeperClient.isReadCommand("delete /app"))
    }

    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = true)

    @Test
    fun `zookeeper browses znodes and writes by version`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.zookeeper")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "zk", name = "zk", type = DatabaseType.ZooKeeper, host = host, port = port.toInt())
        RecordStore.connect(config, null, drivers = drivers).use { zk ->
            assertTrue(zk.server().startsWith("Apache ZooKeeper"), zk.server())
            (List(30) { "/tg_app/config/key$it" } + listOf("/tg_app/config", "/tg_app")).forEach { runCatching { zk.execute("delete $it") } }
            zk.execute("create /tg_app")
            zk.execute("create /tg_app/config {\"debug\":true}")
            val path = listOf("/", "tg_app", "config")
            repeat(25) { zk.put(path, null, buildJsonObject { put("name", "key$it"); put("data", "value $it") }) }
            assertTrue(zk.containers().single().hasChildren)
            assertTrue(zk.containers(listOf("/")).any { it.name == "tg_app" })
            assertEquals(listOf("config"), zk.containers(listOf("/", "tg_app")).map { it.name })
            assertTrue("debug" in zk.definition(path))

            val first = zk.page(path, "", null, 20)
            assertEquals(25L, first.total)
            assertEquals(5, zk.page(path, "", first.cursor, 20).records.size)
            assertEquals(listOf("key1", "key10", "key11"), zk.page(path, "key1", null, 3).records.map { it.document["name"]!!.jsonPrimitive.content })

            val record = zk.page(path, "key0", null, 1).records.single()
            zk.put(path, record.key, JsonObject(record.document + ("data" to JsonPrimitive("changed"))))
            assertEquals("changed", (zk.execute("get /tg_app/config/key0") as ConsoleReply.Text).text)
            assertFailsWith<IOException>("a stale version is refused") { zk.put(path, record.key, record.document) }
            assertFailsWith<IOException> { zk.put(path, null, buildJsonObject { put("name", "key1") }) }
            assertFailsWith<IOException>("a znode with children isn't deleted") { zk.delete(listOf("/", "tg_app"), listOf(buildJsonObject { put("path", "/tg_app"); put("version", 0) })) }
            val doomed = zk.page(path, "key2", null, 50).records.map { it.key!! }
            assertEquals(doomed.size.toLong(), zk.delete(path, doomed))
            assertEquals(0L, zk.delete(path, doomed))
            assertTrue(zk.execute("ls /tg_app/config") is ConsoleReply.Table)
        }
        RecordStore.connect(config.copy(readOnly = true), null, drivers = drivers).use {
            assertTrue(it.execute("ls /") is ConsoleReply.Table)
            assertFailsWith<ReadOnlyViolationException> { it.execute("create /tg_nope") }
        }
    }
}

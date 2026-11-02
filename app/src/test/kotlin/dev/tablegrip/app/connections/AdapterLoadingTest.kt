package dev.tablegrip.app.connections

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.driver.KeyValueStore
import dev.tablegrip.core.drivers.DriverLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The way the app reaches Redis: Lettuce downloaded from Maven Central, TableGrip's adapter taken from the app's own
 * resources, both in one class loader. Opt-in: `-Pit.network=true -Pit.redis=localhost:56379`.
 */
class AdapterLoadingTest {
    @Test
    fun `the redis adapter runs on the downloaded client`() = runBlocking<Unit> {
        if (System.getProperty("it.network") != "true") return@runBlocking
        val (host, port) = System.getProperty("it.redis")?.split(":") ?: return@runBlocking
        val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)
        drivers.install(DriverId.Lettuce)

        val client = drivers.loadClient(DriverId.Lettuce)
        assertEquals("tablegrip-client-Lettuce", client.javaClass.classLoader.name, "the adapter must not come from the app's classpath")
        val config = ConnectionConfig(id = "r", name = "r", type = DatabaseType.Redis, host = host, port = port.toInt())
        KeyValueStore.connect(config, null, drivers = drivers).use { redis ->
            assertTrue(redis.server().startsWith("Redis"), redis.server())
            assertEquals("PONG", redis.command(0, listOf("PING")))
        }
    }

    @Test
    fun `the mongodb adapter runs on the downloaded driver`() = runBlocking<Unit> {
        if (System.getProperty("it.network") != "true") return@runBlocking
        val (host, port) = System.getProperty("it.mongo")?.split(":") ?: return@runBlocking
        val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)
        drivers.install(DriverId.MongoDB)

        assertEquals("tablegrip-client-MongoDB", drivers.loadClient(DriverId.MongoDB).javaClass.classLoader.name)
        val config = ConnectionConfig(id = "m", name = "m", type = DatabaseType.MongoDB, host = host, port = port.toInt())
        dev.tablegrip.core.driver.DocumentStore.connect(config, null, drivers = drivers).use { mongo ->
            assertTrue(mongo.server().startsWith("MongoDB"), mongo.server())
            assertTrue("\"ok\": 1" in mongo.command("admin", "db.runCommand({ping: 1})"))
        }
    }
}

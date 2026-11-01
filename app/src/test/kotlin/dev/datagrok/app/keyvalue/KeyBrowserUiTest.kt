package dev.datagrok.app.keyvalue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.driver.KeyEdit
import dev.datagrok.core.driver.KeyValue
import dev.datagrok.core.driver.KeyValueStore
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** The key browser and console against a real Redis. Opt-in: `-Pit.redis=localhost:56379`. */
@OptIn(ExperimentalTestApi::class)
class KeyBrowserUiTest {
    private val server = System.getProperty("it.redis")?.split(":")

    private fun app(): Pair<AppState, ConnectionConfig> {
        val (host, port) = server!!
        val home = createTempDirectory("datagrok-kv")
        val config = ConnectionConfig(id = "redis", name = "cache", type = DatabaseType.Redis, host = host, port = port.toInt())
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()).apply { save(config, null) }
        val app = AppState(SettingsStore(home.resolve("settings.json")), store, QueryHistory(home.resolve("history.jsonl")), DriverLibrary(home.resolve("drivers")))
        runBlocking {
            KeyValueStore.connect(config, null).use { redis ->
                redis.command(Db, listOf("FLUSHDB"))
                redis.edit(Db, "user:1", KeyEdit.SetField("name", "Ada"))
                redis.edit(Db, "user:1", KeyEdit.SetField("email", "ada@example.com"))
                redis.edit(Db, "user:2", KeyEdit.SetField("name", "Linus"))
                redis.edit(Db, "session:abc", KeyEdit.SetText("{\"user\": 1, \"exp\": 1730000000}"))
                redis.expire(Db, "session:abc", 3_600_000)
                listOf("a", "b", "c").forEach { redis.edit(Db, "queue:jobs", KeyEdit.AppendItem(it)) }
                redis.edit(Db, "leaders", KeyEdit.SetScore("ada", 42.0))
                redis.edit(Db, "leaders", KeyEdit.SetScore("linus", 17.5))
            }
        }
        return app to config
    }

    @Test
    fun `keys list, filter and open with their values`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = KeyBrowserTab(app, config, Db)
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1000.dp, 560.dp)) { KeyBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.keys.size == 5 }
        onNodeWithText("Database 5").assertExists()
        tab.select("user:1")
        waitUntil(timeoutMillis = 10_000) { tab.value != null }
        onNodeWithText("ada@example.com").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser.png")) }

        tab.select("session:abc")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "session:abc" }
        onNodeWithText("Expires in 59m", substring = true).assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-text.png")) }

        onNodeWithText("JSON").performClick()
        onNodeWithText("Expand All").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-json.png")) }
        onNodeWithText("Expires in", substring = true).performClick()
        onNodeWithText("Set expiry").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-ttl.png")) }
        onNodeWithText("1 day").performClick()
        waitUntil(timeoutMillis = 10_000) { (tab.value?.ttlMillis ?: 0) > 3_600_000 }

        tab.select("leaders")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "leaders" }
        onNodeWithText("2 members").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-zset.png")) }

        onNodeWithText("New key").performClick()
        onNodeWithText("Create key").assertExists()
        onNodeWithText("hash").performClick()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-new.png")) }
        onNodeWithText("Cancel").performClick()

        // A hash value edits in place: click it, change it, save.
        tab.select("user:1")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "user:1" }
        onNodeWithText("Ada").performClick()
        onNode(hasSetTextAction() and hasText("Ada")).performTextReplacement("Ada Lovelace")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { (tab.value as? KeyValue.Hash)?.fields?.contains("name" to "Ada Lovelace") == true }

        // Deleting asks first.
        tab.select("queue:jobs")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "queue:jobs" }
        onNodeWithContentDescription("Delete key").performClick()
        onNodeWithText("Delete queue:jobs?").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-delete.png")) }
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 10_000) { tab.keys.none { it.name == "queue:jobs" } }

        tab.pattern.edit { replace(0, length, "user:*") }
        tab.reload()
        waitUntil(timeoutMillis = 10_000) { !tab.scanning && tab.keys.size == 2 }
        assertEquals(listOf("user:1", "user:2"), tab.keys.map { it.name })
        app.dispose()
    }

    @Test
    fun `the console runs commands in its database and follows SELECT`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = CommandConsoleTab(app, config, "cache", Db)
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(900.dp, 420.dp)) { CommandConsoleView(tab) } } }
        listOf("HGETALL user:1", "ZRANGE leaders 0 -1 WITHSCORES", "LRANGE queue:jobs 0 -1", "NOPE").forEach { line ->
            tab.input.edit { replace(0, length, line) }
            tab.run()
            waitUntil(timeoutMillis = 10_000) { !tab.running }
        }
        waitUntil(timeoutMillis = 10_000) { tab.entries.size == 4 }
        assertEquals("1) \"a\"\n2) \"b\"\n3) \"c\"", tab.entries[2].reply)
        assertEquals(true, tab.entries[3].isError)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "redis-console.png")) }

        tab.input.edit { replace(0, length, "SELECT 0") }
        tab.run()
        waitUntil(timeoutMillis = 10_000) { !tab.running && tab.keyspace == 0 }
        onNodeWithText("db0>").assertExists()
        app.dispose()
    }

    @Test
    fun `long key lists and collections scroll`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        runBlocking {
            KeyValueStore.connect(config, null).use { redis ->
                redis.command(LongDb, listOf("FLUSHDB"))
                (1..80).forEach { redis.edit(LongDb, "item:%03d".format(it), KeyEdit.SetText("v$it")) }
                (1..120).forEach { redis.edit(LongDb, "big", KeyEdit.SetField("field%03d".format(it), "value $it")) }
            }
        }
        val tab = KeyBrowserTab(app, config, LongDb)
        tab.treeView = false
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1000.dp, 420.dp)) { KeyBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { !tab.scanning && tab.keys.size == 81 }
        tab.select("big")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "big" }
        onNodeWithText("120 fields").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-scroll.png")) }
        onNodeWithText("New key").performClick()
        onNodeWithText("hash").performClick()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-new-scroll.png")) }
        runBlocking { KeyValueStore.connect(config, null).use { it.command(LongDb, listOf("FLUSHDB")) } }
        app.dispose()
    }

    private companion object {
        const val LongDb = 6
        const val Db = 5
    }

    @Test
    fun `etcd keys browse and edit with the same screens`() = runComposeUiTest {
        val (host, port) = System.getProperty("it.etcd")?.split(":") ?: return@runComposeUiTest
        val home = createTempDirectory("datagrok-etcd")
        val config = ConnectionConfig(id = "etcd", name = "cluster", type = DatabaseType.Etcd, host = host, port = port.toInt())
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()).apply { save(config, null) }
        val app = AppState(SettingsStore(home.resolve("settings.json")), store, QueryHistory(home.resolve("history.jsonl")), DriverLibrary(home.resolve("drivers")))
        runBlocking {
            KeyValueStore.connect(config, null).use { etcd ->
                etcd.command(0, listOf("del", "/dg/", "--prefix"))
                etcd.edit(0, "/dg/services/api/port", KeyEdit.SetText("8080"))
                etcd.edit(0, "/dg/services/api/host", KeyEdit.SetText("10.0.0.12"))
                etcd.edit(0, "/dg/feature-flags", KeyEdit.SetText("{\"newCheckout\": true}"))
                etcd.expire(0, "/dg/feature-flags", 600_000)
            }
        }
        val tab = KeyBrowserTab(app, config, 0)
        tab.pattern.edit { replace(0, length, "/dg/*") }
        tab.reload()
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1000.dp, 420.dp)) { KeyBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { !tab.scanning && tab.keys.size == 3 }
        tab.select("/dg/feature-flags")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "/dg/feature-flags" }
        onNodeWithText("Expires in 9m", substring = true).assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "etcd-keys.png")) }

        val console = CommandConsoleTab(app, config, "cluster")
        tab.dispose()
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(900.dp, 300.dp)) { CommandConsoleView(console) } } }
        console.input.edit { replace(0, length, "get /dg/services/ --prefix") }
        console.run()
        waitUntil(timeoutMillis = 10_000) { console.entries.size == 1 }
        assertEquals("/dg/services/api/host\n10.0.0.12\n/dg/services/api/port\n8080", console.entries.single().reply)
        onNodeWithText("etcd>").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "etcd-console.png")) }
        runBlocking { KeyValueStore.connect(config, null).use { it.command(0, listOf("del", "/dg/", "--prefix")) } }
        app.dispose()
    }
}

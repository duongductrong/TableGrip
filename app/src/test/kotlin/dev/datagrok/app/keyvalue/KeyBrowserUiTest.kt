package dev.datagrok.app.keyvalue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
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
        tab.select("user:1")
        waitUntil(timeoutMillis = 10_000) { tab.value != null }
        onNodeWithText("ada@example.com").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser.png")) }

        tab.select("session:abc")
        waitUntil(timeoutMillis = 10_000) { tab.value?.key == "session:abc" }
        onNodeWithText("TTL 59m", substring = true).assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "key-browser-text.png")) }

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

    private companion object {
        const val Db = 5
    }
}

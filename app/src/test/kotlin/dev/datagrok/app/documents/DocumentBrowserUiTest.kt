package dev.datagrok.app.documents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.GridCell
import dev.datagrok.app.keyvalue.CommandConsoleTab
import dev.datagrok.app.keyvalue.CommandConsoleView
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.driver.DocumentStore
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalTestApi::class)
class DocumentBrowserUiTest {
    @Test
    fun `cells show values, not their Extended JSON wrappers`() {
        val document = Json.parseToJsonElement(
            """{"_id": {"${'$'}oid": "65f0c0ffee0000000000abcd"}, "name": "Ada", "placed": {"${'$'}date": "2026-10-01T10:00:00Z"},
                "big": {"${'$'}numberLong": "9007199254740993"}, "tags": ["a", "b"], "address": {"city": "London"}, "note": null}""",
        ).jsonObject
        val other = Json.parseToJsonElement("""{"_id": 2, "extra": true}""").jsonObject
        val rows = DocumentBrowserTab.rows(listOf(document, other))
        assertEquals(listOf("_id", "name", "placed", "big", "tags", "address", "note", "extra"), rows.columns.map { it.name })
        assertEquals(
            listOf("ObjectId(65f0c0ffee0000000000abcd)", "Ada", "2026-10-01T10:00:00Z", "9007199254740993", "[\"a\",\"b\"]", "{\"city\":\"London\"}", null, ""),
            rows.rows[0],
        )
        assertEquals("", rows.rows[1][1], "a field the document lacks is blank, not NULL")
        assertEquals(listOf("mixed", "string", "date", "number", "array", "object", "null", "bool"), rows.columns.map { it.typeName })
        assertEquals("{\"\$oid\":\"65f0c0ffee0000000000abcd\"}", DocumentBrowserTab.idOf(document))
    }

    private val server = System.getProperty("it.mongo")?.split(":")
    private val db = "dg_ui_${System.currentTimeMillis()}"

    private fun app(): Pair<AppState, ConnectionConfig> {
        val (host, port) = server!!
        val home = createTempDirectory("datagrok-mongo")
        val config = ConnectionConfig(id = "mongo", name = "shop", type = DatabaseType.MongoDB, host = host, port = port.toInt())
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()).apply { save(config, null) }
        val app = AppState(SettingsStore(home.resolve("settings.json")), store, QueryHistory(home.resolve("history.jsonl")), DriverLibrary(home.resolve("drivers")))
        runBlocking {
            DocumentStore.connect(config, null).use { mongo ->
                mongo.command(
                    db,
                    "db.orders.insertMany([" +
                        "{_id: 1, customer: 'ada', status: 'A', total: 30, items: ['pen', 'ink'], placed: ISODate('2026-10-01T10:00:00Z')}," +
                        "{_id: 2, customer: 'linus', status: 'B', total: 10, address: {city: 'Helsinki'}}," +
                        "{_id: 3, customer: 'grace', status: 'A', total: 50, vip: true}])",
                )
            }
        }
        return app to config
    }

    @Test
    fun `documents show in the grid and edit as JSON`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1100.dp, 520.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 3 }
        assertEquals(listOf("_id", "customer", "status", "total", "items", "placed", "address", "vip"), tab.model.columns.map { it.name })

        tab.grid.select(GridCell(2, 1))
        waitUntil(timeoutMillis = 5_000) { tab.shown == 2 }
        assertTrue("\"grace\"" in tab.editor.text)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents.png")) }

        tab.editor.setTextAndPlaceCursorAtEnd(tab.editor.text.toString().replace("\"grace\"", "\"grace hopper\""))
        tab.save()
        waitUntil(timeoutMillis = 10_000) { tab.documents.getOrNull(2)?.get("customer")?.toString() == "\"grace hopper\"" }

        tab.filter.setTextAndPlaceCursorAtEnd("{status: 'A', total: {\$gt: 40}}")
        tab.reload()
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 1 }
        tab.count()
        waitUntil(timeoutMillis = 10_000) { tab.total == 1L }
        onNodeWithText("grace hopper").assertExists()
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `the console runs shell commands and follows use`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = CommandConsoleTab(app, config, "shop", database = db)
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(900.dp, 460.dp)) { CommandConsoleView(tab) } } }
        listOf("db.orders.find({status: 'A'}, {customer: 1, total: 1}).sort({total: -1})", "db.orders.countDocuments({})", "db.orders.foo()").forEach { line ->
            tab.input.setTextAndPlaceCursorAtEnd(line)
            tab.run()
            waitUntil(timeoutMillis = 10_000) { !tab.running }
        }
        waitUntil(timeoutMillis = 10_000) { tab.entries.size == 3 }
        assertTrue(tab.entries[0].reply.indexOf("grace") < tab.entries[0].reply.indexOf("ada"))
        assertEquals("3", tab.entries[1].reply)
        assertTrue(tab.entries[2].isError)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "mongo-console.png")) }

        tab.input.setTextAndPlaceCursorAtEnd("use admin")
        tab.run()
        onNodeWithText("admin>").assertExists()
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }
}

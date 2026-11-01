package dev.datagrok.app.documents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
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
    fun `documents show as a grid or JSON, and the filter applies on Enter or cmd-Enter`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1100.dp, 520.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 3 }
        assertEquals(listOf("_id", "customer", "status", "total", "items", "placed", "address", "vip"), tab.model.columns.map { it.name })
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents.png")) }

        // The JSON view shows the documents themselves, with pending edits, in the grid's column order.
        tab.model.setValue(2, tab.model.fields.indexOf("customer"), "grace hopper")
        onNodeWithText("JSON").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("\"ada\"", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("\"grace hopper\"", tab.model.json(tab.grid.visibleColumns)[2].jsonObject["customer"].toString())
        assertTrue(onAllNodesWithText("\"${'$'}date\"", substring = true).fetchSemanticsNodes().isNotEmpty(), "dates stay Extended JSON")
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents-json.png")) }
        onNodeWithText("Grid").performClick()
        tab.submit()
        waitUntil(timeoutMillis = 10_000) { tab.documents.getOrNull(2)?.get("customer")?.toString() == "\"grace hopper\"" }

        val field = onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performTextReplacement("{status: 'A', total: {\$gt: 40}}")
        field.performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 1 && !tab.loading }
        tab.count()
        waitUntil(timeoutMillis = 10_000) { tab.total == 1L }
        onNodeWithText("grace hopper").assertExists()
        field.performTextReplacement("status: 'B'")
        field.performKeyInput { withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitUntil(timeoutMillis = 10_000) { tab.appliedFilter == "{status: 'B'}" && tab.documents.size == 1 && !tab.loading }
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `pages move back and forth over what was applied`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        runBlocking {
            DocumentStore.connect(config, null).use { mongo ->
                mongo.command(db, "db.many.insertMany([" + (1..120).joinToString { "{_id: $it, g: ${it % 3}}" } + "])")
            }
        }
        app.updateSettings { it.copy(pageSize = 50) }
        val tab = DocumentBrowserTab(app, config, db, "many")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 50 }
        // Half-typed text in the filter doesn't leak into paging; it applies on Enter.
        tab.filter.setTextAndPlaceCursorAtEnd("{g: 99}")
        onNodeWithContentDescription("Next page").performClick()
        waitUntil(timeoutMillis = 10_000) { tab.skip == 50L && !tab.loading }
        assertEquals(51, tab.documents.first()["_id"].toString().toInt())
        onNodeWithContentDescription("Next page").performClick()
        waitUntil(timeoutMillis = 10_000) { tab.skip == 100L && !tab.loading }
        assertEquals(20, tab.documents.size)
        assertEquals(false, tab.hasMore)
        onNodeWithContentDescription("Previous page").performClick()
        waitUntil(timeoutMillis = 10_000) { tab.skip == 50L && !tab.loading }
        onNodeWithContentDescription("First page").performClick()
        waitUntil(timeoutMillis = 10_000) { tab.skip == 0L && !tab.loading }
        assertEquals(1, tab.documents.first()["_id"].toString().toInt())

        // A sort with ties still shows every document once across the pages.
        tab.filter.setTextAndPlaceCursorAtEnd("")
        tab.sort.setTextAndPlaceCursorAtEnd("g: 1")
        tab.reload()
        val seen = mutableListOf<Int>()
        while (true) {
            waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.appliedSort == "{g: 1}" }
            seen += tab.documents.map { it["_id"].toString().toInt() }
            if (!tab.hasMore) break
            val before = tab.skip
            tab.nextPage()
            waitUntil(timeoutMillis = 10_000) { tab.skip == before + 50 && !tab.loading }
        }
        assertEquals((1..120).toList(), seen.sorted())
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `grid edits save field by field, keep types and never overwrite newer changes`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.orders.updateOne({_id: 1}, {\$set: {qty: NumberLong(7)}})") } }
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 3 }
        val model = tab.model
        val column = { name: String -> model.fields.indexOf(name) }
        model.setValue(0, column("qty"), "8")
        model.setValue(0, column("total"), "31")
        model.setDefault(0, column("items"))
        model.setValue(1, column("vip"), "true")
        val row = model.addRow()
        model.setValue(row, column("customer"), "new")
        model.setValue(row, column("total"), "5")
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents-edits.png")) }
        tab.submit()
        waitUntil(timeoutMillis = 10_000) { !tab.submitting && !tab.loading && tab.documents.size == 4 }
        assertEquals(false, tab.message?.isError, tab.message?.text)
        runBlocking {
            DocumentStore.connect(config, null).use { mongo ->
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 1, qty: {\$type: 'long'}, qty: 8, total: 31, items: {\$exists: false}, placed: {\$type: 'date'}})"))
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 2, vip: true})"))
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({customer: 'new', total: {\$type: 'int'}})"))
                // Someone else changes order 3 after the page was loaded.
                mongo.command(db, "db.orders.updateOne({_id: 3}, {\$set: {total: 99}})")
            }
        }
        val stale = tab.model
        stale.setValue(2, stale.fields.indexOf("total"), "51")
        stale.setValue(1, stale.fields.indexOf("status"), "C")
        tab.submit()
        waitUntil(timeoutMillis = 10_000) { !tab.submitting && !tab.loading && tab.model !== stale }
        assertEquals(true, tab.message?.isError)
        assertTrue("Row 3" in tab.message!!.text, tab.message!!.text)
        runBlocking {
            DocumentStore.connect(config, null).use { mongo ->
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 3, total: 99})"), "the newer change survives")
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 2, status: 'C'})"), "the other document saved")
            }
        }
        // The edit that didn't apply is still pending, now over the current value.
        assertEquals("51", tab.model.value(2, tab.model.fields.indexOf("total")))
        assertTrue(tab.hasChanges)
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `the filter completes fields, operators and values`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 3 }
        val field = onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performTextReplacement("{address.c")
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("address.city").fetchSemanticsNodes().isNotEmpty() }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(it, "documents-completion.png")) }
        field.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("{'address.city': ", tab.filter.text.toString())
        // Right after the key, the field's values are offered.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("'Helsinki'").fetchSemanticsNodes().isNotEmpty() }
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

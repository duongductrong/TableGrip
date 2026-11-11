package dev.tablegrip.app.documents

import androidx.compose.ui.test.performTextInput
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import dev.tablegrip.app.grid.isMac
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.keyvalue.CommandConsoleTab
import dev.tablegrip.app.keyvalue.CommandConsoleView
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.driver.DocumentStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
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
        val home = createTempDirectory("tablegrip-mongo")
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 520.dp)) { DocumentBrowserView(tab) } } }
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
        tab.submitNow()
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
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
        tab.submitNow()
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
        tab.submitNow()
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
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 600.dp)) { DocumentBrowserView(tab) } } }
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
    fun `fields dragged into the builder become conditions, sorts and projections`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 760.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 3 }
        onNodeWithText("Builder").performClick()
        waitForIdle()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents-builder-empty.png")) }
        // While a field is dragged, every card shows it takes drops, and the one under the pointer lights up.
        val sortCard = onNodeWithText("Drop fields to sort by").fetchSemanticsNode().boundsInRoot.center
        tab.fieldDrag.start("total", sortCard)
        waitForIdle()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents-builder-dragging.png")) }
        tab.fieldDrag.cancel()
        waitForIdle()

        /** Drags the topmost node showing [text] (the builder's field list sits above the grid) onto [target]'s middle. */
        fun drag(text: String, target: String, pick: (List<androidx.compose.ui.semantics.SemanticsNode>) -> androidx.compose.ui.semantics.SemanticsNode = { nodes -> nodes.minBy { it.boundsInRoot.top } }) {
            val nodes = onAllNodesWithText(text).fetchSemanticsNodes()
            val source = pick(nodes)
            val to = onNodeWithText(target).fetchSemanticsNode().boundsInRoot.center
            val index = nodes.indexOf(source)
            onAllNodesWithText(text)[index].performMouseInput {
                moveTo(center)
                press()
                val steps = 12
                val from = source.boundsInRoot.center
                for (step in 1..steps) moveTo(center + (to - from) * (step / steps.toFloat()))
                release()
            }
            waitForIdle()
        }

        drag("status", "Drop a field to filter by it")
        assertEquals(listOf("status"), tab.builder.conditions.map { it.field })
        tab.builder.conditions.single().value.setTextAndPlaceCursorAtEnd("A")
        waitUntil(timeoutMillis = 5_000) { tab.filter.text.toString() == "{status: 'A'}" }

        // A column header dragged up out of the grid lands in the builder too.
        drag("total", "Drop fields to sort by") { nodes -> nodes.maxBy { it.boundsInRoot.top } }
        assertEquals(listOf("total"), tab.builder.sort.map { it.field })
        waitUntil(timeoutMillis = 5_000) { tab.sort.text.toString() == "{total: 1}" }
        assertEquals(listOf("_id", "customer", "status", "total", "items", "placed", "address", "vip"), tab.model.columns.map { it.name }, "the columns didn't move")

        drag("customer", "Drop fields to show only those")
        waitUntil(timeoutMillis = 5_000) { tab.projection.text.toString() == "{customer: 1}" }
        tab.reload()
        waitUntil(timeoutMillis = 10_000) { !tab.loading && tab.appliedFilter == "{status: 'A'}" && tab.documents.size == 2 }
        assertEquals(listOf("ada", "grace"), tab.documents.map { it["customer"]!!.toString().trim('"') })
        assertTrue(tab.documents.all { it.keys == setOf("_id", "customer") })
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "documents-builder.png")) }

        // Typing in the filter bar shows in the builder.
        tab.filter.setTextAndPlaceCursorAtEnd("{total: {\$gte: 20, \$lt: 60}}")
        waitUntil(timeoutMillis = 5_000) { tab.builder.conditions.map { it.operator } == listOf(FilterOperator.Gte, FilterOperator.Lt) }
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `the console runs a script's commands and follows use`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val script =
            """
            // Orders, biggest first.
            db.orders.find({status: 'A'}, {customer: 1, total: 1})
              .sort({total: -1})

            db.orders.countDocuments({});
            db.orders.foo()
            db.orders.countDocuments({status: 'A'})
            """.trimIndent()
        val tab = CommandConsoleTab(app, config, "shop", database = db, initial = script)
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 520.dp)) { CommandConsoleView(tab) } } }
        // Everything, stopping at the command that fails.
        tab.runScript(all = true)
        waitUntil(timeoutMillis = 10_000) { tab.entries.size == 3 && !tab.running }
        assertTrue(tab.entries[0].reply.indexOf("grace") < tab.entries[0].reply.indexOf("ada"), tab.entries[0].reply)
        assertEquals("3", tab.entries[1].reply)
        assertTrue(tab.entries[2].isError)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "mongo-console.png")) }
        // The command at the caret.
        tab.script.edit { selection = TextRange(script.indexOf("{status: 'A'})") + 3) }
        tab.runScript()
        waitUntil(timeoutMillis = 10_000) { tab.entries.size == 4 && !tab.running }
        assertEquals("db.orders.countDocuments({status: 'A'})", tab.entries[3].command)
        assertEquals("2", tab.entries[3].reply)

        tab.script.setTextAndPlaceCursorAtEnd("use admin")
        tab.runScript()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("admin").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("admin>", tab.prompt)
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `console results show as a grid, JSON or tree, and their edits are written back`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        app.updateSettings { it.copy(reviewChanges = false) }
        val script = "db.getCollection('orders').find({}).sort({_id: 1})\ndb.orders.aggregate([{\$group: {_id: '\$status', n: {\$sum: 1}}}])\ndb.runCommand({ping: 1})"
        val tab = CommandConsoleTab(app, config, "shop", database = db, initial = script)
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 620.dp)) { CommandConsoleView(tab) } } }
        tab.script.edit { selection = TextRange(0) }
        tab.runScript()
        waitUntil(timeoutMillis = 10_000) { tab.results.size == 1 && !tab.running }
        val result = tab.results.single()
        // The documents open in a tab of their own, as a grid; the log just counts them.
        assertEquals(1, tab.selectedOutput)
        assertEquals("orders", result.title)
        assertTrue(result.editable)
        assertEquals(listOf("_id", "customer", "status", "total", "items", "placed", "address", "vip"), result.model.columns.map { it.name })
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("grace").fetchSemanticsNodes().isNotEmpty() }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "mongo-console-grid.png")) }
        onNodeWithText("Tree").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Expand All").fetchSemanticsNodes().isNotEmpty() }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "mongo-console-tree.png")) }
        onNodeWithText("JSON").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("\"ada\"", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Grid").performClick()

        // Edit one, delete one, add one: submitted, then read back by running the command again.
        val model = result.model
        model.setValue(0, model.fields.indexOf("total"), "35")
        model.deleteRows(listOf(1))
        val added = model.addRow()
        model.setValue(added, model.fields.indexOf("_id"), "4")
        model.setValue(added, model.fields.indexOf("customer"), "edsger")
        assertTrue(tab.hasUnsavedWork)
        result.submit()
        waitUntil(timeoutMillis = 10_000) { !result.submitting && !result.loading && !result.hasChanges && result.documents.size == 3 }
        assertEquals(listOf("ada", "grace", "edsger"), result.documents.map { it["customer"]!!.toString().trim('"') })
        assertEquals("35", result.documents[0]["total"].toString())
        runBlocking {
            DocumentStore.connect(config, null).use { mongo ->
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 1, total: 35})"))
                assertEquals("0", mongo.command(db, "db.orders.countDocuments({_id: 2})"))
                assertEquals("1", mongo.command(db, "db.orders.countDocuments({_id: 4, customer: 'edsger'})"))
            }
        }

        // Pinned, it stays when the console runs again; computed documents can't be edited, and one reply reads as a tree.
        result.pinned = true
        tab.runScript(all = true)
        waitUntil(timeoutMillis = 10_000) { tab.results.size == 4 && !tab.running }
        assertTrue(result in tab.results)
        val (grouped, ping) = tab.results.takeLast(2)
        assertTrue(!grouped.editable && grouped.whyReadOnly != null)
        assertEquals(DocumentView.Tree, ping.view)
        assertEquals(4, tab.selectedOutput)
        tab.closeResult(ping)
        assertEquals(3, tab.selectedOutput)
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }

    @Test
    fun `the console completes collections, methods and the collection's fields`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (app, config) = app()
        val tab = CommandConsoleTab(app, config, "shop", database = db, initial = "// Open orders\n")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 520.dp)) { CommandConsoleView(tab) } } }
        val field = onAllNodes(hasSetTextAction())[0]
        field.performClick()
        tab.script.edit { selection = TextRange(length) }
        "db.ord".forEach { field.performTextInput(it.toString()) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("orders").fetchSemanticsNodes().isNotEmpty() }
        field.performKeyInput { pressKey(Key.Tab) }
        ".fi".forEach { field.performTextInput(it.toString()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("findOne").fetchSemanticsNodes().isNotEmpty() }
        field.performKeyInput { pressKey(Key.Tab) }
        assertEquals("// Open orders\ndb.orders.find({})", tab.script.text.toString())
        onNodeWithText("find(filter, projection) — documents matching the filter").assertExists()
        // The collection's fields, learnt from a sample, then the field's values.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("customer").fetchSemanticsNodes().isNotEmpty() }
        "sta".forEach { field.performTextInput(it.toString()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("status").fetchSemanticsNodes().isNotEmpty() }
        field.performKeyInput { pressKey(Key.Tab) }
        assertEquals("// Open orders\ndb.orders.find({status: })", tab.script.text.toString())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("'B'").fetchSemanticsNodes().isNotEmpty() }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(it, "mongo-console-completion.png")) }
        "'B".forEach { field.performTextInput(it.toString()) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("'B'").fetchSemanticsNodes().isNotEmpty() }
        field.performKeyInput { pressKey(Key.Tab) }
        assertEquals("// Open orders\ndb.orders.find({status: 'B'})", tab.script.text.toString())
        // ⌘⏎ runs the command at the caret.
        field.performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Enter) } }
        waitUntil(timeoutMillis = 10_000) { tab.entries.size == 1 && !tab.running }
        assertTrue("linus" in tab.entries[0].reply, tab.entries[0].reply)
        runBlocking { DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }
}

package dev.tablegrip.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.grid.GridMention
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.agent.MentionText
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** `@` mentions and ⌘L outside the console, over SQLite; `-Psnapshot.dir=/tmp` also writes what the list looks like. */
@OptIn(ExperimentalTestApi::class)
class MentionSourcesTest {
    private class ScriptedAgent : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Claude Code", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            emit(AgentEvent.Completed("Noted."))
        }
    }

    private fun app(agent: AgentAdapter): AppState {
        val home = createTempDirectory("tablegrip-sources")
        val db = home.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
                it.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id), total REAL NOT NULL)")
                it.executeUpdate("CREATE INDEX orders_user ON orders(user_id)")
                it.executeUpdate("INSERT INTO orders VALUES (1, NULL, 12.5), (2, NULL, 99), (3, NULL, 5)")
            }
        }
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"$db"}]""")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
        ).also { it.pickConnection(it.connections.profiles.single()) }
    }

    private val pill = MentionText.First

    @Test
    fun `@ finds tables, picks one with Enter, and the table's structure is sent`() {
        val agent = ScriptedAgent()
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(420.dp, 560.dp)) { AgentPanel(app) } } }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            val field = onNode(hasSetTextAction())
            val composer = app.agent.composer

            field.performTextInput("how big is @ord")
            // The tables load in the background; the list fills in once they have.
            waitUntil(timeoutMillis = 10_000) { app.agent.sources.search("ord").any { it.title == "orders" } }
            waitForIdle()
            onNodeWithText("orders").assertExists()
            snapshot("tables")
            field.performKeyInput { pressKey(Key.Enter) }
            assertEquals("how big is $pill ", composer.text.text.toString())
            assertIs<Mention.Table>(composer.mention(pill))
            assertTrue(agent.requests.isEmpty(), "Enter picked the table rather than sending")

            // `table.` narrows to its columns.
            field.performTextInput("vs @orders.to")
            waitUntil(timeoutMillis = 10_000) { app.agent.sources.search("orders.to").isNotEmpty() }
            waitForIdle()
            onNodeWithText("orders.total").assertExists()
            field.performKeyInput { pressKey(Key.Enter) }
            assertIs<Mention.Column>(composer.mention(pill + 1))

            // Esc closes the list; Enter then sends.
            field.performTextInput("@us")
            field.performKeyInput { pressKey(Key.Escape) }
            assertNull(composer.typing)
            field.performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 10_000) { !app.agent.busy && agent.requests.size == 1 }
            val sent = agent.requests.single().messages.last().text
            assertTrue(sent.startsWith("how big is @orders [1] vs @orders.total [2] @us"), sent)
            assertTrue("[1] SQLite table main.orders on connection Shop:\nColumns:\n  id integer pk" in sent, sent)
            assertTrue("Foreign key: (user_id) -> users(id)" in sent && "Index orders_user: (user_id)" in sent, sent)
            assertTrue("[2] Column orders.total of SQLite connection Shop: real" in sent, sent)
            // The structure read at send time stays with the message for later turns.
            assertTrue((app.agent.messages.first().mentions.getValue(pill) as Mention.Table).details!!.contains("Primary key: id"))
        }
    }

    @Test
    fun `open tabs are offered before anything is typed`() {
        val app = app(ScriptedAgent())
        val config = app.connections.profiles.single()
        app.workspace.openConsole(config, "SELECT count(*) FROM orders;")
        app.workspace.openTable(config, TableRef(Namespace(null, "main"), "users"))
        val found = app.agent.sources.search("")
        val console = found.single { it.mention is Mention.Sql }
        assertEquals(MentionCandidate.Kind.Tab, console.kind)
        assertEquals("SELECT count(*) FROM orders;", (console.mention as Mention.Sql).sql)
        assertEquals("users", (found.single { it.mention is Mention.Table }.mention as Mention.Table).name)

        // Nothing at all from a connection kept from agents.
        app.updateSettings { it.copy(aiAccess = AiAccess.Off.name) }
        assertTrue(app.agent.sources.search("").isEmpty())
        assertTrue(app.agent.sources.search("ord").isEmpty())
    }

    @Test
    fun `cells carry their values only when the connection shares data`() {
        val app = app(ScriptedAgent())
        app.workspace.openTable(app.connections.profiles.single(), TableRef(Namespace(null, "main"), "orders"))
        val tab = app.workspace.selected as TableDataTab
        val columns = listOf(ResultColumn("id", "INTEGER"), ResultColumn("total", "REAL"))

        tab.mentionCells(GridMention(columns, 0..1, listOf(listOf("1", "12.5"), listOf("2", "99"))))
        val schemaOnly = app.agent.composer.mention(pill) as Mention.Cells
        assertEquals("orders rows 1-2 · 2 cols", schemaOnly.label)
        assertNull(schemaOnly.values)
        assertTrue(app.agentOpen)

        app.updateSettings { it.copy(aiAccess = AiAccess.SchemaAndSamples.name) }
        tab.mentionCells(GridMention(columns, 1..1, listOf(listOf("2", "99"))))
        assertEquals(listOf(listOf("2", "99")), (app.agent.composer.mention(pill + 1) as Mention.Cells).values)

        // ⌘L with nothing selected mentions the table.
        assertTrue(tab.mentionTable())
        assertEquals("orders", (app.agent.composer.mention(pill + 2) as Mention.Table).name)
    }

    @Test
    fun `table cells carry the filter, the key of each row and which ones aren't saved`() {
        val app = app(ScriptedAgent())
        app.updateSettings { it.copy(aiAccess = AiAccess.SchemaAndSamples.name) }
        app.workspace.openTable(app.connections.profiles.single(), TableRef(Namespace(null, "main"), "orders"))
        val tab = app.workspace.selected as TableDataTab
        tab.showRowsWhere("total > 10")
        waitFor { !tab.loading && tab.model?.rowCount == 2 && tab.details != null }
        val model = tab.model!!
        val total = model.columns.indexOfFirst { it.name == "total" }
        model.setValue(1, total, "100")

        tab.mentionCells(GridMention(listOf(model.columns[total]), 0..1, listOf(listOf("12.5"), listOf("100"))))
        val cells = app.agent.composer.mention(pill) as Mention.Cells
        assertEquals("orders.total rows 1-2", cells.label)
        assertEquals("SELECT * FROM \"main\".\"orders\" WHERE (total > 10)", cells.origin)
        assertEquals(listOf("id"), cells.keys.map { it.name })
        assertEquals(listOf(listOf("1"), listOf("2")), cells.keyValues)
        assertEquals(mapOf(2 to "edited"), cells.notes)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a console mentioned in full isn't repeated in the framing`() {
        val agent = ScriptedAgent()
        val app = app(agent)
        val sql = "SELECT * FROM orders WHERE total > 10;"
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(420.dp, 560.dp)) { AgentPanel(app) } } }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.workspace.openConsole(app.connections.profiles.single(), sql) }
            val console = app.workspace.selected as ConsoleTab

            runOnUiThread { app.agent.send("what does it do?") }
            waitUntil(timeoutMillis = 10_000) { !app.agent.busy && agent.requests.size == 1 }
            assertTrue("The user's open console contains" in agent.requests[0].system!!)

            runOnUiThread {
                app.agent.mention(console.whole()!!)
                app.agent.composer.text.edit { append("and this?") }
                app.agent.sendComposer()
            }
            waitUntil(timeoutMillis = 10_000) { !app.agent.busy && agent.requests.size == 2 }
            assertFalse("The user's open console contains" in agent.requests[1].system!!)
            assertTrue(sql in agent.requests[1].messages.last().text)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            val image = onAllNodes(isRoot())[index].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(out, if (index == 0) "mention-$name.png" else "mention-$name-popup.png"))
        }
    }
}

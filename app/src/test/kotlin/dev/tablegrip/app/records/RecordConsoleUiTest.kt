package dev.tablegrip.app.records

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.driver.RecordStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The record console's completion against real servers. Opt-in, as in the core's engine tests:
 * ```
 * docker run -d --name dg-it-es -p 59200:9200 -e discovery.type=single-node -e xpack.security.enabled=false docker.elastic.co/elasticsearch/elasticsearch:8.17.4
 * docker run -d --name dg-it-neo4j -p 57474:7474 -e NEO4J_AUTH=neo4j/tablegrip123 neo4j:5.26.2
 * -Pit.elasticsearch=localhost:59200 -Pit.neo4j=localhost:57474
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class RecordConsoleUiTest {
    private fun app(config: ConnectionConfig, password: String?): AppState {
        val home = createTempDirectory("tablegrip-records")
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()).apply { save(config, password) }
        return AppState(SettingsStore(home.resolve("settings.json")), store, QueryHistory(home.resolve("history.jsonl")), DriverLibrary(home.resolve("drivers")))
    }

    private fun elastic(readOnly: Boolean = false): ConnectionConfig? {
        val (host, port) = System.getProperty("it.elasticsearch")?.split(":") ?: return null
        val config = ConnectionConfig(id = "es", name = "search", type = DatabaseType.Elasticsearch, host = host, port = port.toInt(), readOnly = readOnly)
        runBlocking {
            RecordStore.connect(config.copy(readOnly = false), null).use { es ->
                runCatching { es.execute("DELETE /tg_orders") }
                es.put(listOf("tg_orders"), null, buildJsonObject { put("status", "paid"); put("total", 30) })
                es.execute("POST /tg_orders/_refresh")
            }
        }
        return config
    }

    /** Types [text] a character at a time at the end of the editor, as a person would. */
    private fun ComposeUiTest.type(tab: RecordConsoleTab, before: String, text: String) {
        tab.editor.setTextAndPlaceCursorAtEnd(before)
        waitForIdle()
        text.forEach { c ->
            onNode(hasSetTextAction()).performTextInput(c.toString())
            waitForIdle()
        }
    }

    private fun ComposeUiTest.waitForSuggestion(label: String) = waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithText(label).assertExists() }.isSuccess }

    @Test
    fun `the HTTP console completes paths, index names and query DSL keys`() = runComposeUiTest {
        val config = elastic() ?: return@runComposeUiTest
        val app = app(config, null)
        val tab = RecordConsoleTab(app, config, "search")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 520.dp)) { RecordConsoleView(tab) } } }
        waitUntil(timeoutMillis = 15_000) { tab.language != null }
        onNode(hasSetTextAction()).performClick()

        type(tab, "", "GET /tg_o")
        waitForSuggestion("tg_orders")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) { tab.editor.text.toString() == "GET /tg_orders" }

        type(tab, "GET /tg_orders/_search\n{\n  \"query\": {\"match\": {", "\"st")
        waitForSuggestion("status")
        // The list is a popup, a root of its own.
        System.getProperty("snapshot.dir")?.let { dir ->
            onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                ImageIO.write(onAllNodes(isRoot())[i].captureToImage().toAwtImage(), "png", File(dir, "record-console-completion-$i.png"))
            }
        }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) { tab.editor.text.endsWith("{\"status\": ") }
        app.dispose()
    }

    @Test
    fun `a read-only profile isn't offered the endpoints that write`() = runComposeUiTest {
        val config = elastic(readOnly = true) ?: return@runComposeUiTest
        val app = app(config, null)
        val tab = RecordConsoleTab(app, config, "search")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 520.dp)) { RecordConsoleView(tab) } } }
        waitUntil(timeoutMillis = 15_000) { tab.language != null }
        onNode(hasSetTextAction()).performClick()
        type(tab, "", "POST /tg_orders/_")
        waitForSuggestion("_search")
        onNodeWithText("_count").assertExists()
        onNodeWithText("_delete_by_query").assertDoesNotExist()
        onNodeWithText("_update_by_query").assertDoesNotExist()
        app.dispose()
    }

    @Test
    fun `the Cypher console completes labels, relationship types and property keys`() = runComposeUiTest {
        val (host, port) = System.getProperty("it.neo4j")?.split(":") ?: return@runComposeUiTest
        val config = ConnectionConfig(id = "neo4j", name = "graph", type = DatabaseType.Neo4j, host = host, port = port.toInt(), user = "neo4j")
        runBlocking {
            RecordStore.connect(config, "tablegrip123").use { neo ->
                neo.execute("MATCH (n:TgPerson) DETACH DELETE n")
                neo.execute("CREATE (:TgPerson {tg_name: 'Ada'})-[:TG_KNOWS]->(:TgPerson {tg_name: 'Linus'})")
            }
        }
        val app = app(config, "tablegrip123")
        val tab = RecordConsoleTab(app, config, "graph")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(900.dp, 520.dp)) { RecordConsoleView(tab) } } }
        waitUntil(timeoutMillis = 15_000) { tab.language != null }
        onNode(hasSetTextAction()).performClick()

        type(tab, "", "MATCH (p:TgP")
        waitForSuggestion("TgPerson")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) { tab.editor.text.toString() == "MATCH (p:TgPerson" }

        type(tab, "MATCH (p:TgPerson)-[", ":TG_")
        waitForSuggestion("TG_KNOWS")

        type(tab, "MATCH (p:TgPerson) RETURN p", ".tg_")
        waitForSuggestion("tg_name")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitUntil(timeoutMillis = 5_000) { tab.editor.text.toString() == "MATCH (p:TgPerson) RETURN p.tg_name" }
        assertEquals("Cypher", tab.language?.name)
        app.dispose()
    }
}

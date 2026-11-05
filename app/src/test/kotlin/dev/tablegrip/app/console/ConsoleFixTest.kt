package dev.tablegrip.app.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fixing failed statements in a console over SQLite, with a scripted agent: nothing reaches a real one.
 * `-Psnapshot.dir=/tmp` also writes what it looks like.
 */
@OptIn(ExperimentalTestApi::class)
class ConsoleFixTest {
    private class ScriptedAgent(private vararg val answers: String) : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Claude Code", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            val answer = answers[requests.size]
            requests += request
            emit(AgentEvent.Thinking)
            emit(AgentEvent.Completed(answer))
        }
    }

    private fun app(agent: AgentAdapter): AppState {
        val home = createTempDirectory("tablegrip-fix")
        val db = home.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total REAL)")
                it.executeUpdate("INSERT INTO orders VALUES (1, 7, 12.5), (2, 8, 99)")
            }
        }
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"${db.toString().replace('\\', '/')}"}]""")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
        )
    }

    private fun ComposeUiTest.runAndFail(app: AppState, sql: String): ConsoleTab {
        runOnUiThread {
            val console = app.workspace.selected as? ConsoleTab
            if (console == null) app.workspace.openConsole(app.connections.profiles.single(), sql)
            else console.editor.edit { replace(0, length, sql) }
        }
        val console = app.workspace.selected as ConsoleTab
        runOnUiThread { console.run(RunMode.Current) }
        waitUntil(timeoutMillis = 10_000) { !console.running && console.output.any { it.failure?.sql == sql.removeSuffix(";") } }
        return console
    }

    @Test
    fun `a misspelt column gets a quick fix that replaces only the failed statement`() {
        val agent = ScriptedAgent()
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1200.dp, 720.dp)) { MainScreen(app) } } }
            val console = runAndFail(app, "SELECT usr_id, total FROM orders WHERE usr_id > 1;")
            assertEquals("no such column: usr_id", console.output.last { it.failure != null }.failure!!.error.message.substringAfter("(").removeSuffix(")"))
            // The quick fix appears once completion's schema has loaded; no agent is involved.
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithText("Quick fix").assertExists() }.isSuccess }
            onNodeWithText("Quick fix").performClick()
            val proposed = assertIs<FixState.Proposed>(console.fix)
            assertEquals("Quick fix", proposed.source)
            onNodeWithText("Proposed fix").assertExists()
            snapshot("quick")

            onNodeWithText("Apply").performClick()
            assertEquals("SELECT user_id, total FROM orders WHERE user_id > 1;", console.editor.text.toString())
            assertEquals(null, console.fix)
            assertTrue(agent.requests.isEmpty())
            // One undo step puts the failed statement back.
            runOnUiThread { console.editor.undoState.undo() }
            assertEquals("SELECT usr_id, total FROM orders WHERE usr_id > 1;", console.editor.text.toString())
        }
        app.dispose()
    }

    @Test
    fun `the agent's fix is reviewed, applied and run, and a fix that writes can't be run from the card`() {
        val agent =
            ScriptedAgent(
                "Sure:\n```json\n{\"sql\": \"SELECT * FROM orders WHERE total > 50\", \"explanation\": \"There is no amount column; total holds the order amount.\"}\n```",
                "{\"sql\": \"DELETE FROM orders WHERE total > 50\", \"explanation\": \"Removes them.\"}",
            )
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1200.dp, 720.dp)) { MainScreen(app) } } }
            val console = runAndFail(app, "SELECT * FROM orders WHERE amount > 50;")
            onNodeWithText("Fix with AI").performClick()
            waitUntil(timeoutMillis = 10_000) { console.fix is FixState.Proposed }
            val proposed = console.fix as FixState.Proposed
            assertEquals("Claude Code", proposed.source)
            assertEquals(emptyList(), proposed.warnings)
            // The agent got the statement, the error and the schema (names and types only), not the data.
            val prompt = agent.requests.single().messages.single().text
            assertTrue("SELECT * FROM orders WHERE amount > 50" in prompt, prompt)
            assertTrue("no such column: amount" in prompt, prompt)
            assertTrue("orders(id integer" in prompt && "total real)" in prompt, prompt)
            assertFalse("12.5" in prompt)
            snapshot("agent")

            onNodeWithText("Apply & Run").performClick()
            waitUntil(timeoutMillis = 10_000) { !console.running && console.results.isNotEmpty() }
            assertEquals("SELECT * FROM orders WHERE total > 50;", console.editor.text.toString())
            assertEquals(1, console.results.last().rows.rows.size)

            // A "fix" that would delete rows is flagged and can only be applied for the user to run themselves.
            runAndFail(app, "SELECT * FROM orders WHERE amount > 50;")
            // The card for the new failure replaces the old one; click it once it has settled.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Fix with AI").fetchSemanticsNodes().size == 1 }
            onNodeWithText("Fix with AI").performClick()
            waitUntil(timeoutMillis = 10_000) { console.fix is FixState.Proposed }
            assertTrue((console.fix as FixState.Proposed).warnings.any { "the original only read" in it })
            onNodeWithText("Apply & Run").assertDoesNotExist()
            snapshot("warning")
        }
        app.dispose()
    }

    @Test
    fun `a connection not shared with agents offers no AI fix`() {
        val agent = ScriptedAgent()
        val app = app(agent)
        app.updateSettings { it.copy(aiAccess = AiAccess.Off.name) }
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1200.dp, 720.dp)) { MainScreen(app) } } }
            val console = runAndFail(app, "SELECT * FROM orders WHERE amount > 50;")
            assertFalse(console.canAskAgent)
            onNodeWithText("Fix with AI").assertDoesNotExist()
            onNodeWithText("Ask in chat").assertDoesNotExist()
            runOnUiThread { console.askAgentToFix(console.output.last { it.failure != null }.failure!!) }
            assertEquals(null, console.fix)
            assertTrue(agent.requests.isEmpty())
        }
        app.dispose()
    }

    private fun ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        Thread.sleep(400)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "fix-$name.png"))
    }
}

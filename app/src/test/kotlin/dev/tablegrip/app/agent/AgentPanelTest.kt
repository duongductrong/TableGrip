package dev.tablegrip.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The agent island against a scripted agent; `-Psnapshot.dir=/tmp` also writes what it looks like. */
@OptIn(ExperimentalTestApi::class)
class AgentPanelTest {
    /** Answers each turn with the next of [answers]. */
    private class ScriptedAgent(private vararg val answers: String) : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Claude Code", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            val answer = answers[requests.size]
            requests += request
            emit(AgentEvent.Thinking)
            answer.chunked(16).forEach { emit(AgentEvent.TextDelta(it)) }
            emit(AgentEvent.Completed(answer))
        }
    }

    private val answer =
        """
        Here are the **top customers** by order count:

        ```sql
        SELECT c.name, count(*) AS orders
        FROM customers c JOIN orders o ON o.customer_id = c.id
        GROUP BY c.name
        ORDER BY orders DESC
        LIMIT 10;
        ```

        - Uses the `orders.customer_id` foreign key
        - Add a `WHERE o.status = 'paid'` to skip refunds
        """.trimIndent()

    private val lookup =
        """
        Let me check the names first.

        ```sql run
        SELECT name FROM customers ORDER BY name
        ```
        """.trimIndent()

    @Test
    fun `sends a turn and renders the streamed answer`() {
        for (dark in listOf(true, false)) {
            val home = createTempDirectory("tablegrip-agent")
            DriverManager.getConnection("jdbc:sqlite:${home.resolve("shop.db")}").use { db ->
                db.createStatement().use {
                    it.executeUpdate("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT)")
                    it.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER)")
                    it.executeUpdate("INSERT INTO customers VALUES (1, 'Ada'), (2, 'Linus')")
                    it.executeUpdate("INSERT INTO orders VALUES (1, 1), (2, 1), (3, 2)")
                }
            }
            home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop (SQLite)","type":"SQLite","database":"${home.resolve("shop.db").toString().replace('\\', '/')}"}]""")
            val agent = ScriptedAgent(answer, lookup, "Your customers are Ada and Linus.")
            val app =
                AppState(
                    settingsStore = SettingsStore(home.resolve("settings.json")),
                    connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                    history = QueryHistory(home.resolve("history.jsonl")),
                    agentRegistry = AgentRegistry(listOf(object : AgentSource {
                        override suspend fun discover(rescan: Boolean) = listOf(agent)
                    })),
                )
            runComposeUiTest(testTimeout = 5.minutes) {
                setContent { TableGripTheme(isDark = dark) { Box(Modifier.size(1300.dp, 760.dp)) { MainScreen(app) } } }
                runOnUiThread { app.agentOpen = true }
                waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
                snapshot(dark, "empty")
                runOnUiThread { app.agent.send("Who are the top customers?") }
                waitUntil(timeoutMillis = 5_000) { !app.agent.busy }
                onNodeWithText("Who are the top customers?").assertExists()
                assertEquals(answer, app.agent.messages.last().text)
                assertEquals("Shop (SQLite)", app.currentConnection?.name)
                // The agent is told the real schema and the rules, and reminded of them right before the message.
                val first = agent.requests.single()
                assertTrue(first.system!!.contains("customers(id integer, name text)"), first.system)
                assertTrue(first.system!!.contains("You can't execute anything"))
                assertTrue(first.reminder!!.contains("sql run"))
                snapshot(dark, "answer")

                // ▶ on the suggestion opens it in a console of the connection and runs it there.
                onAllNodesWithContentDescription("Run in console").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? ConsoleTab)?.let { !it.running && it.results.isNotEmpty() } == true }
                val console = app.workspace.selected as ConsoleTab
                assertTrue(console.editor.text.contains("FROM customers"))
                snapshot(dark, "ran")

                // A run request waits for the user; allowing it runs the read in a console and hands the rows back.
                runOnUiThread { app.agent.send("List customer names") }
                waitUntil(timeoutMillis = 5_000) { !app.agent.busy }
                assertEquals(2, agent.requests.size)
                onNodeWithText("Claude Code wants to run a query").assertExists()
                snapshot(dark, "request")
                onNodeWithText("Run").performClick()
                waitUntil(timeoutMillis = 10_000) { agent.requests.size == 3 && !app.agent.busy }
                val result = agent.requests.last().messages.last().text
                assertTrue(result.contains("Ada") && result.contains("Linus"), result)
                onNodeWithText("Result shared · 2 row(s)").assertExists()
                onNodeWithText("Your customers are Ada and Linus.").assertExists()
                snapshot(dark, "request-done")
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(dark: Boolean, name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        Thread.sleep(400)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "agent-$name-${if (dark) "dark" else "light"}.png"))
    }
}

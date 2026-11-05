package dev.tablegrip.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import dev.tablegrip.core.agent.AgentEvent.Activity.State
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Reasoning and activities shown while the agent works; `-Psnapshot.dir=/tmp` also writes what it looks like. */
@OptIn(ExperimentalTestApi::class)
class AgentActivityTest {
    /** Thinks, starts two tools and waits for [release] before finishing them and answering. */
    private class WorkingAgent(private val release: CompletableDeferred<Unit>) : AgentAdapter {
        override val descriptor = AgentDescriptor("test:working", "Codex", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            emit(AgentEvent.Thinking)
            emit(AgentEvent.ReasoningDelta("The user wants the busiest customers.\nOrders reference customers by customer_id.\nCount per customer, then sort."))
            emit(AgentEvent.Activity("a", "Read · schema.sql", State.Running))
            emit(AgentEvent.Activity("b", "Ran sqlite3 shop.db .tables", State.Running))
            emit(AgentEvent.Activity("a", "Read · schema.sql", State.Done))
            release.await()
            emit(AgentEvent.Activity("b", "Ran sqlite3 shop.db .tables", State.Failed))
            emit(AgentEvent.TextDelta("Join **orders** to customers and count."))
            emit(AgentEvent.Completed("Join **orders** to customers and count."))
        }
    }

    @Test
    fun `reasoning folds away once the answer comes, activities keep their outcome`() {
        val home = createTempDirectory("tablegrip-activity")
        val release = CompletableDeferred<Unit>()
        val app =
            AppState(
                settingsStore = SettingsStore(home.resolve("settings.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = QueryHistory(home.resolve("history.jsonl")),
                agentRegistry = AgentRegistry(listOf(object : AgentSource {
                    override suspend fun discover(rescan: Boolean) = listOf(WorkingAgent(release))
                })),
            )
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 760.dp)) { MainScreen(app) } } }
            runOnUiThread { app.agentOpen = true }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.agent.send("Who orders most?") }
            waitUntil(timeoutMillis = 5_000) { app.agent.messages.last().reasoning.endsWith("then sort.") }
            val reply = app.agent.messages.last()
            assertEquals(listOf(State.Done, State.Running), reply.activities.map { it.state })
            // Open while thinking, showing the latest lines.
            onNodeWithText("Count per customer", substring = true).assertExists()
            snapshot("working")

            release.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy }
            assertEquals("Join **orders** to customers and count.", reply.text)
            assertEquals(listOf(State.Done, State.Failed), reply.activities.map { it.state })
            assertNotNull(reply.thoughtSeconds)
            // The history the agent sees next carries the answer only.
            assertEquals(2, app.agent.messages.size)
            onNodeWithText("Thought for", substring = true).assertExists()
            onNodeWithText("Count per customer", substring = true).assertDoesNotExist()
            snapshot("answered")
            onNodeWithText("Thought for", substring = true).performClick()
            onNodeWithText("Count per customer", substring = true).assertExists()
            snapshot("reopened")
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        Thread.sleep(400)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "agent-activity-$name.png"))
    }
}

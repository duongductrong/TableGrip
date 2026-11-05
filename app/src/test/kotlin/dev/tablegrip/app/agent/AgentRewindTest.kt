package dev.tablegrip.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentException
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Rewind and retry keep failed or unwanted turns out of what the agent is sent next. */
@OptIn(ExperimentalTestApi::class)
class AgentRewindTest {
    /** Answers with the next of [answers]; null fails the turn, like a provider refusing the request. */
    private class ScriptedAgent(private vararg val answers: String?) : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Scripted", AgentTransport.Http)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            val answer = answers[requests.size]
            requests += request
            emit(AgentEvent.Thinking)
            emit(AgentEvent.Completed(answer ?: throw AgentException("Upstream request failed")))
        }
    }

    private fun app(agent: AgentAdapter): AppState {
        val home = createTempDirectory("tablegrip-rewind")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
        )
    }

    private fun AppState.transcript() = agent.messages.map { "${it.role}:${it.error ?: it.text}" }

    @Test
    fun `retry resends a failed turn without duplicating the question`() {
        val agent = ScriptedAgent(null, "hi")
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
            runOnUiThread { app.agentOpen = true }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.agent.send("say hi") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy }
            assertEquals(listOf("User:say hi", "Assistant:Upstream request failed"), app.transcript())

            runOnUiThread { app.agent.retry(app.agent.messages.last()) }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && agent.requests.size == 2 }
            assertEquals(listOf("User:say hi", "Assistant:hi"), app.transcript())
            assertEquals(listOf("say hi"), agent.requests.last().messages.map { it.text })
        }
    }

    @Test
    fun `rewinding a message drops it and what followed, and hands it back to edit`() {
        val agent = ScriptedAgent("one", null, "two")
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
            runOnUiThread { app.agentOpen = true }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.agent.send("first") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && app.agent.messages.size == 2 }
            runOnUiThread { app.agent.send("say hi as well") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && app.agent.messages.size == 4 }

            runOnUiThread { app.agent.rewind(app.agent.messages[2]) }
            waitForIdle()
            assertEquals(listOf("User:first", "Assistant:one"), app.transcript())
            // The composer took the rewound text back.
            assertEquals("say hi as well", app.agent.composer.text.text.toString())

            runOnUiThread { app.agent.send("second") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && agent.requests.size == 3 }
            // The rewound message and its failure never reach the agent again.
            assertEquals(listOf("first", "one", "second"), agent.requests.last().messages.map { it.text })
            assertEquals(AgentMessage.Role.Assistant, app.agent.messages.last().role)
        }
    }
}

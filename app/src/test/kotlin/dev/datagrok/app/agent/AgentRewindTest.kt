package dev.datagrok.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.core.agent.AgentAdapter
import dev.datagrok.core.agent.AgentDescriptor
import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentException
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.AgentSource
import dev.datagrok.core.agent.AgentTransport
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
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
        val home = createTempDirectory("datagrok-rewind")
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
            setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
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
            setContent { DatagrokTheme(isDark = false) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
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
            assertNull(app.agent.draft)

            runOnUiThread { app.agent.send("second") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && agent.requests.size == 3 }
            // The rewound message and its failure never reach the agent again.
            assertEquals(listOf("first", "one", "second"), agent.requests.last().messages.map { it.text })
            assertEquals(AgentMessage.Role.Assistant, app.agent.messages.last().role)
        }
    }
}

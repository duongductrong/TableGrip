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
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.AgentSource
import dev.datagrok.core.agent.AgentTransport
import dev.datagrok.core.agent.ChatStore
import dev.datagrok.core.agent.Mention
import dev.datagrok.core.agent.MentionText
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Chats are kept as they go, and reopen from the history as they were left. */
@OptIn(ExperimentalTestApi::class)
class AgentChatHistoryTest {
    /** Echoes the latest message, so each chat's answers are told apart. */
    private class EchoAgent : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:echo", "Echo", AgentTransport.Http)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            emit(AgentEvent.Completed("echo: ${request.messages.last().text}"))
        }
    }

    private val home = createTempDirectory("datagrok-chats")
    private val store = ChatStore(home.resolve("chats"))

    private fun app(agent: AgentAdapter) =
        AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
            chatStore = store,
        )

    private fun AppState.transcript() = agent.messages.map { "${it.role}:${it.text}" }

    @Test
    fun `a new chat keeps the last one, which reopens with its history`() {
        val echo = EchoAgent()
        val app = app(echo)
        runComposeUiTest {
            setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
            runOnUiThread { app.agentOpen = true }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.agent.send("revenue by month\nfor 2026") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && store.list().isNotEmpty() && store.load(store.list().first().id)?.messages?.size == 2 }
            val first = app.agent.chatId
            assertEquals("revenue by month", app.agent.chatTitle)

            runOnUiThread { app.agent.newChat() }
            assertNull(app.agent.chatTitle)
            runOnUiThread { app.agent.send("top customers") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && store.list().size == 2 }
            waitUntil(timeoutMillis = 5_000) { app.agent.chats.size == 2 }
            assertEquals(listOf("top customers", "revenue by month"), app.agent.chats.map { it.title })

            runOnUiThread { app.agent.openChat(first) }
            waitUntil(timeoutMillis = 5_000) { app.agent.chatId == first }
            assertEquals(listOf("User:revenue by month\nfor 2026", "Assistant:echo: revenue by month\nfor 2026"), app.transcript())

            // The reopened chat goes on where it was: the agent is sent its history, not the other chat's.
            runOnUiThread { app.agent.send("and 2025?") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && echo.requests.size == 3 }
            assertEquals(listOf("revenue by month\nfor 2026", "echo: revenue by month\nfor 2026", "and 2025?"), echo.requests.last().messages.map { it.text })

            runOnUiThread { app.agent.deleteChat(first) }
            assertEquals(emptyList(), app.agent.messages.toList())
            waitUntil(timeoutMillis = 5_000) { store.list().map { it.title } == listOf("top customers") }
        }
    }

    @Test
    fun `rename sticks to the chat`() {
        val app = app(EchoAgent())
        runComposeUiTest {
            setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
            runOnUiThread { app.agentOpen = true }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            runOnUiThread { app.agent.send("hello") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && store.list().isNotEmpty() }
            runOnUiThread { app.agent.renameChat(app.agent.chatId, "Greeting") }
            assertEquals("Greeting", app.agent.chatTitle)
            waitUntil(timeoutMillis = 5_000) { store.list().single().title == "Greeting" }
            runOnUiThread { app.agent.send("again") }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && store.load(app.agent.chatId)?.messages?.size == 4 }
            assertEquals("Greeting", store.list().single().title)
        }
    }

    @Test
    fun `mentions, notes and answered runs survive a save`() {
        val question = ChatMessage(AgentMessage.Role.User, "rows in ${MentionText.First}?", mentions = mapOf(MentionText.First to Mention.Query("orders", "SELECT * FROM orders")))
        val answer = ChatMessage(AgentMessage.Role.Assistant, "```sql\nSELECT 1\n```").apply { runs[0] = RunRequestState.Done("1 row(s)") }
        val note = ChatMessage(AgentMessage.Role.User, "result", ChatMessage.Kind.Note).apply {
            label = "Result shared"
            origin = answer to 0
        }
        val streaming = ChatMessage(AgentMessage.Role.Assistant).apply { streaming = true }
        val restored = listOf(question, answer, note, streaming).toSaved().toChat()
        assertEquals(3, restored.size)
        assertEquals(question.mentionText.prompt, restored[0].mentionText.prompt)
        assertEquals(RunRequestState.Done("1 row(s)"), restored[1].runs[0])
        assertEquals(restored[1] to 0, restored[2].origin)
        assertEquals("Result shared", restored[2].label)
    }

    @Test
    fun `chats group by age`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 31)
        fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
        assertEquals(ChatAge.Today, ChatAge.of(at(today), today, zone))
        assertEquals(ChatAge.Yesterday, ChatAge.of(at(today.minusDays(1)), today, zone))
        assertEquals(ChatAge.Week, ChatAge.of(at(today.minusDays(6)), today, zone))
        assertEquals(ChatAge.Month, ChatAge.of(at(today.minusDays(7)), today, zone))
        assertEquals(ChatAge.Older, ChatAge.of(at(today.minusDays(30)), today, zone))
        assertEquals("Sep 12", chatTime(at(LocalDate.of(2026, 9, 12)), today, zone))
        assertEquals("Sep 12, 2025", chatTime(at(LocalDate.of(2025, 9, 12)), today, zone))
    }
}

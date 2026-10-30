package dev.datagrok.app.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import dev.datagrok.app.ui.MainScreen
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.StatementFailure
import dev.datagrok.app.grid.isMac
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.agent.AgentAdapter
import dev.datagrok.core.agent.AgentDescriptor
import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.AgentSource
import dev.datagrok.core.agent.AgentTransport
import dev.datagrok.core.agent.Mention
import dev.datagrok.core.agent.MentionText
import dev.datagrok.core.connection.AiAccess
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.driver.QueryError
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Mentions (⌘L) as pills in the agent composer; `-Psnapshot.dir=/tmp` also writes what they look like. */
@OptIn(ExperimentalTestApi::class)
class MentionComposerTest {
    private class ScriptedAgent : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Claude Code", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            emit(AgentEvent.Completed("It scans orders."))
        }
    }

    private fun app(agent: AgentAdapter): AppState {
        val home = createTempDirectory("datagrok-mention")
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"${home.resolve("shop.db")}"}]""")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
        )
    }

    private val sql = "SELECT 1;\n\nSELECT *\nFROM orders\nWHERE total > 10;\n"
    private val pill = MentionText.First

    @Test
    fun `a console mentions the statement at the caret, or the selection`() {
        val app = app(ScriptedAgent())
        app.workspace.openConsole(app.connections.profiles.single(), sql)
        val console = app.workspace.selected as ConsoleTab
        console.editor.edit { selection = TextRange(sql.indexOf("orders")) }
        val statement = console.mention() as Mention.Sql
        assertEquals(3..5, statement.lines)
        assertEquals("SELECT *\nFROM orders\nWHERE total > 10", statement.sql)

        console.editor.edit { selection = TextRange(sql.indexOf("FROM"), sql.indexOf("WHERE") - 1) }
        val selection = console.mention() as Mention.Sql
        assertEquals(4..4, selection.lines)
        assertEquals("FROM orders", selection.sql)
        assertTrue(selection.label.endsWith(":4"), selection.label)

        // Nothing once the user keeps connections from agents.
        app.updateSettings { it.copy(aiAccess = AiAccess.Off.name) }
        assertNull(console.mention())
    }

    @Test
    fun `a pill moves, deletes and undoes as one character, and is sent with what it stands for`() {
        val agent = ScriptedAgent()
        val app = app(agent)
        val mention = Mention.Sql("Console", 3..5, "SQLite", "SELECT *\nFROM orders\nWHERE total > 10")
        runComposeUiTest {
            setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(420.dp, 520.dp)) { AgentPanel(app) } } }
            waitUntil(timeoutMillis = 5_000) { app.agent.selected != null }
            val field = onNode(hasSetTextAction())
            val composer = app.agent.composer

            runOnUiThread { app.agent.mention(mention) }
            waitForIdle()
            assertEquals("$pill ", composer.text.text.toString())
            assertEquals(TextRange(2), composer.text.selection)
            field.performTextInput("why is it slow?")
            assertEquals("$pill why is it slow?", composer.text.text.toString())

            // A mention already in the message isn't added twice.
            runOnUiThread { app.agent.mention(mention) }
            waitForIdle()
            assertEquals(1, composer.text.text.count { it == pill })

            // Arrow keys step over the whole pill: the caret never stops inside it.
            runOnUiThread { composer.text.edit { selection = TextRange(1) } }
            waitForIdle()
            field.performKeyInput { pressKey(Key.DirectionLeft) }
            assertEquals(TextRange(0), composer.text.selection)
            field.performKeyInput { pressKey(Key.DirectionRight) }
            assertEquals(TextRange(1), composer.text.selection)
            snapshot("composer")

            // Clicking the pill shows what it stands for; Esc closes it.
            field.performMouseInput { click(Offset(24f, 8f)) }
            waitForIdle()
            assertEquals(pill, composer.preview)
            onNodeWithText("SQLite · lines 3–5").assertExists()
            snapshot("preview")
            // The click lands the caret on an edge of the pill, never inside it.
            assertTrue(composer.text.selection == TextRange(0) || composer.text.selection == TextRange(1), composer.text.selection.toString())
            field.performKeyInput { pressKey(Key.Escape) }
            assertNull(composer.preview)
            runOnUiThread { composer.text.edit { selection = TextRange(1) } }
            waitForIdle()

            // One backspace removes it; undo brings it back, still a mention.
            field.performKeyInput { pressKey(Key.Backspace) }
            assertEquals(" why is it slow?", composer.text.text.toString())
            field.performKeyInput { withKeyDown(if (isMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals("$pill why is it slow?", composer.text.text.toString())

            // A stand-in pasted from elsewhere means nothing here.
            field.performTextInput("")
            assertEquals("$pill why is it slow?", composer.text.text.toString())

            field.performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { !app.agent.busy && agent.requests.size == 1 }
            val sent = agent.requests.single().messages.last().text
            assertTrue(sent.startsWith("@Console:3-5 [1] why is it slow?"), sent)
            assertTrue("[1] SQLite SQL from Console, lines 3-5:\n```sql\nSELECT *" in sent, sent)
            assertEquals("", composer.text.text.toString())
            snapshot("sent")

            // The sent message's pill shows what was sent for it.
            onNodeWithText("@Console:3-5", substring = true).performMouseInput { click(Offset(24f, 8f)) }
            waitForIdle()
            onNodeWithText("SQLite · lines 3–5").assertExists()
        }
    }

    @Test
    fun `the editor's context menu adds the statement to the chat`() {
        val app = app(ScriptedAgent())
        runComposeUiTest {
            setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1200.dp, 700.dp)) { MainScreen(app) } } }
            runOnUiThread { app.workspace.openConsole(app.connections.profiles.single(), sql) }
            val console = app.workspace.selected as ConsoleTab
            runOnUiThread { console.editor.edit { selection = TextRange(sql.indexOf("orders")) } }
            waitForIdle()
            onNode(hasSetTextAction() and hasText("FROM orders", substring = true)).performMouseInput { rightClick(Offset(120f, 40f)) }
            waitForIdle()
            onNodeWithText("Add to Chat", substring = true).performClick()
            waitForIdle()
            assertTrue(app.agentOpen)
            assertEquals(3..5, (app.agent.composer.mention(pill) as Mention.Sql).lines)
        }
    }

    @Test
    fun `asking about a failed statement mentions it, keeping what was typed`() {
        val app = app(ScriptedAgent())
        app.workspace.openConsole(app.connections.profiles.single(), "SELECT amount FROM orders;")
        val console = app.workspace.selected as ConsoleTab
        val failure = StatementFailure("SELECT amount FROM orders", QueryError("no such column: amount"))
        val composer = app.agent.composer

        console.discussInChat(failure)
        assertTrue(app.agentOpen)
        assertEquals("$pill How do I fix it?", composer.text.text.toString())
        val error = composer.mention(pill) as Mention.Error
        assertEquals("no such column: amount", error.message)

        composer.take()
        composer.text.edit { append("my own question ") }
        console.discussInChat(failure)
        assertEquals("my own question $pill ", composer.text.text.toString().replace(pill + 1, pill))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        // A popup is a root of its own: written beside the panel.
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            val image = onAllNodes(isRoot())[index].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(out, if (index == 0) "mention-$name.png" else "mention-$name-popup.png"))
        }
    }
}

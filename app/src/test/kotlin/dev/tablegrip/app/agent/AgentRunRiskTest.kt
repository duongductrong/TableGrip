package dev.tablegrip.app.agent

import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Queries from the chat that can lose data ask first. Nothing here reaches a database or an agent. */
class AgentRunRiskTest {
    private val app =
        createTempDirectory("tablegrip-risk").let { home ->
            AppState(
                settingsStore = SettingsStore(home.resolve("settings.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = QueryHistory(home.resolve("history.jsonl")),
            )
        }

    private fun use(config: ConnectionConfig) {
        app.connections.profiles += config
        app.currentConnectionId = config.id
    }

    @AfterTest
    fun tearDown() = app.dispose()

    @Test
    fun `reads carry no risk and any change does, worst first`() {
        use(ConnectionConfig(id = "db", name = "Orders", type = DatabaseType.SQLite, environment = Environment.Production))
        assertEquals(emptyList(), app.agent.risks("SELECT * FROM users; SHOW TABLES"))
        assertEquals(
            listOf("DROP permanently removes table", "Changes data or schema: UPDATE, DROP", "Runs on Orders (production)"),
            app.agent.risks("UPDATE users SET active = 0 WHERE id = 1; DROP TABLE users"),
        )
        assertEquals(listOf("Changes data or schema: INSERT", "Runs on Orders (production)"), app.agent.risks("INSERT INTO t VALUES (1)"))
    }

    @Test
    fun `running a risky suggestion asks in the chat and opens nothing until confirmed`() {
        use(ConnectionConfig(id = "db", name = "DB", type = DatabaseType.SQLite))
        app.agent.runSql("DELETE FROM users")
        val pending = assertNotNull(app.agent.pendingRun)
        assertTrue("DELETE without WHERE affects every row" in pending.reasons)
        assertTrue(app.workspace.tabs.isEmpty())

        // Switching connection before confirming drops it rather than running it somewhere else.
        use(ConnectionConfig(id = "other", name = "Other", type = DatabaseType.SQLite))
        app.agent.confirmRun()
        assertNull(app.agent.pendingRun)
        assertTrue(app.workspace.tabs.isEmpty())
    }

    @Test
    fun `a confirmed run still respects a read-only connection`() {
        use(ConnectionConfig(id = "db", name = "DB", type = DatabaseType.SQLite, readOnly = true))
        app.agent.runSql("DROP TABLE users")
        app.agent.confirmRun()
        val console = app.workspace.selected as ConsoleTab
        assertNull(console.pendingConfirmation)
        assertTrue(console.output.last().isError, console.output.last().toString())
        assertTrue(!console.running)
    }
}

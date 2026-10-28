package dev.datagrok.app.workspace

import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceCloseTest {
    private val app =
        createTempDirectory("datagrok-workspace").let { home ->
            AppState(
                settingsStore = SettingsStore(home.resolve("settings.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = QueryHistory(home.resolve("history.jsonl")),
            )
        }
    private val config = ConnectionConfig(id = "db", name = "DB", type = DatabaseType.SQLite)
    private val workspace = app.workspace

    @AfterTest
    fun tearDown() = app.dispose()

    private fun console(sql: String = ""): ConsoleTab {
        workspace.openConsole(config, sql)
        return workspace.tabs.last() as ConsoleTab
    }

    @Test
    fun `the SQL a console opens with is not unsaved work`() {
        val tab = console("SELECT * FROM users;")
        assertFalse(tab.hasUnsavedWork)
        tab.editor.edit { append(" -- edited") }
        assertTrue(tab.hasUnsavedWork)
    }

    @Test
    fun `clean tabs close without asking`() {
        console()
        console("SELECT 1;")
        var done = false
        workspace.requestCloseAll { done = true }
        assertTrue(done)
        assertTrue(workspace.tabs.isEmpty())
    }

    @Test
    fun `cancelling the save question keeps every tab open`() {
        val edited = console()
        console()
        edited.editor.edit { append("DELETE FROM users") }
        var done = false
        workspace.requestCloseAll { done = true }

        assertEquals(edited, workspace.selected)
        assertNotNull(edited.pendingScriptClose)
        edited.pendingScriptClose = null
        assertFalse(done)
        assertEquals(2, workspace.tabs.size)
    }

    @Test
    fun `discarding resumes closing the rest`() {
        val first = console()
        val second = console()
        first.editor.edit { append("UPDATE a SET b = 1") }
        second.editor.edit { append("UPDATE c SET d = 2") }
        var done = false
        workspace.requestCloseAll { done = true }

        first.closeAfterScript(save = false)
        assertNull(first.pendingScriptClose)
        assertEquals(second, workspace.selected)
        second.closeAfterScript(save = false)
        assertTrue(done)
        assertTrue(workspace.tabs.isEmpty())
    }
}

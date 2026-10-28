package dev.datagrok.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private fun app(): AppState {
        val home = createTempDirectory("datagrok-settings")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
        )
    }

    @Test
    fun `settings apply as typed and only when valid`() = runComposeUiTest {
        val app = app()
        app.settingsOpen = true
        setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1100.dp, 640.dp)) { MainScreen(app) } } }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-screen.png")) }

        onAllNodes(hasSetTextAction())[0].performTextInput("timeout")
        waitForIdle()
        assertEquals(2, onAllNodesWithText("Query Execution").fetchSemanticsNodes().size, "sidebar item and page title")
        onNodeWithText("Appearance").assertDoesNotExist()

        // Search, max result rows, then the timeout.
        val timeoutField = onAllNodes(hasSetTextAction())[2]
        timeoutField.performTextReplacement("-5")
        waitForIdle()
        onNodeWithText("Must be a number from 0 to 86400").assertExists()
        assertEquals(0, app.settings.queryTimeoutSeconds)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-screen-error.png")) }

        timeoutField.performTextReplacement("45")
        waitForIdle()
        assertEquals(45, app.settings.queryTimeoutSeconds)

        onNodeWithContentDescription("Settings (${Shortcuts.Settings})").performClick()
        waitForIdle()
        assertFalse(app.settingsOpen)
        app.dispose()
    }

    @Test
    fun `reset puts a page back to defaults`() {
        val app = app()
        app.updateSettings { it.copy(queryTimeoutSeconds = 60, maxResultRows = 50) }
        val form = SettingsForm({ app.settings }) { updated -> app.updateSettings { updated } }
        form.reset(SettingsPages.byId("queries")!!)
        assertEquals(0, app.settings.queryTimeoutSeconds)
        assertEquals(1_000, app.settings.maxResultRows)
        app.dispose()
    }
}

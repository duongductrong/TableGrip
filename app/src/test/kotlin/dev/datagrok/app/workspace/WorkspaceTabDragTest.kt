package dev.datagrok.app.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkspaceTabDragTest {
    private val app =
        createTempDirectory("datagrok-tab-drag").let { home ->
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

    private fun titles() = workspace.tabs.map { it.title }

    private fun ComposeUiTest.showTabs(count: Int, width: Int = 900) {
        setContent { DatagrokTheme(isDark = false) { Box(Modifier.size(width.dp, 300.dp)) { WorkspaceView(app) } } }
        runOnUiThread { repeat(count) { workspace.openConsole(config) } }
        waitForIdle()
    }

    /** Drags the tab titled [title] by [dx] in small steps, like a hand would; [midway] runs before releasing. */
    private fun ComposeUiTest.dragTab(title: String, dx: Float, midway: () -> Unit = {}) {
        onNodeWithText(title).performMouseInput {
            moveTo(center)
            press()
            repeat(20) { moveBy(Offset(dx / 20, 0f)) }
        }
        waitForIdle()
        midway()
        onNodeWithText(title).performMouseInput { release() }
        waitForIdle()
    }

    @Test
    fun `moving keeps the other tabs in order`() {
        repeat(4) { workspace.openConsole(config) }
        workspace.move(0, 2)
        assertEquals(listOf("DB (2)", "DB (3)", "DB", "DB (4)"), titles())
        workspace.move(3, 0)
        assertEquals(listOf("DB (4)", "DB (2)", "DB (3)", "DB"), titles())
    }

    @Test
    fun `dragging a tab right past its neighbours reorders it`() = runComposeUiTest {
        showTabs(3)
        dragTab("DB", 260f) {
            val out = System.getProperty("snapshot.dir") ?: return@dragTab
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "tab-drag.png"))
        }
        assertEquals(listOf("DB (2)", "DB (3)", "DB"), titles())
        // Dragging selects the tab, as pressing it would.
        assertEquals("DB", workspace.selected?.title)
    }

    @Test
    fun `dragging a tab left past its neighbour reorders it`() = runComposeUiTest {
        showTabs(3)
        dragTab("DB (3)", -90f)
        assertEquals(listOf("DB", "DB (3)", "DB (2)"), titles())
    }

    @Test
    fun `a short drag leaves the order alone`() = runComposeUiTest {
        showTabs(3)
        dragTab("DB (2)", 15f)
        assertEquals(listOf("DB", "DB (2)", "DB (3)"), titles())
    }

    @Test
    fun `holding a tab against the edge scrolls the row and carries it along`() = runComposeUiTest {
        showTabs(10, width = 400)
        runOnUiThread { workspace.selected = workspace.tabs.first() }
        waitForIdle()
        dragTab("DB", 600f)
        assertEquals("DB", titles().last())
        assertEquals((2..10).map { "DB ($it)" }, titles().dropLast(1))
    }
}

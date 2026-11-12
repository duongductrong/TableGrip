package dev.tablegrip.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ExplorerContextMenuTest {
    private fun app(vararg configs: ConnectionConfig): AppState {
        val home = createTempDirectory("tablegrip-explorer-menu")
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore())
        configs.forEach { store.save(it, password = null) }
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = store,
            history = QueryHistory(home.resolve("history.jsonl")),
            driverLibrary = DriverLibrary(home.resolve("drivers"), useClasspath = false),
        )
    }

    @Test
    fun `blank space below the tree offers the sidebar's actions`() = runComposeUiTest {
        val app = app(ConnectionConfig("a", "Orders", DatabaseType.PostgreSQL, group = "Shop"), ConnectionConfig("c", "Analytics", DatabaseType.ClickHouse))
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(320.dp, 600.dp)) { ExplorerPanel(app) } } }
        waitForIdle()

        onRoot().performMouseInput { rightClick(Offset(160f, 500f)) }
        waitForIdle()
        onNodeWithText("Import from Other Tools…").assertExists()
        // The Shop group starts open, so there is something to collapse.
        onNodeWithText("Collapse All").assertExists()
        onAllNodesWithText("Disconnect All").assertCountEquals(0)
        System.getProperty("snapshot.dir")?.let { dir ->
            onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                ImageIO.write(onAllNodes(isRoot())[i].captureToImage().toAwtImage(), "png", File(dir, "explorer-blank-menu-$i.png"))
            }
        }

        onNodeWithText("New Connection…").performClick()
        waitForIdle()
        assertNotNull(app.connections.dialog)
        app.dispose()
    }

    @Test
    fun `the add menu lists engines and opens the dialog on the one picked`() = runComposeUiTest {
        val app = app(ConnectionConfig("c", "Analytics", DatabaseType.ClickHouse))
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(320.dp, 800.dp)) { ExplorerPanel(app) } } }
        waitForIdle()

        onNodeWithContentDescription("Add connection").performClick()
        waitForIdle()
        onAllNodesWithText("More Data Sources").assertCountEquals(1)
        onAllNodesWithText("Open Data File…").assertCountEquals(1)
        System.getProperty("snapshot.dir")?.let { dir ->
            // The popup is its own window root, so capture every root and keep the one that holds the menu.
            onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                ImageIO.write(onAllNodes(isRoot())[i].captureToImage().toAwtImage(), "png", File(dir, "explorer-add-menu-$i.png"))
            }
        }

        onNodeWithText("MySQL").performClick()
        waitForIdle()
        assertEquals(DatabaseType.MySQL, app.connections.dialog?.type)
        app.dispose()
    }

    @Test
    fun `a connection row keeps its own menu`() = runComposeUiTest {
        val app = app(ConnectionConfig("c", "Analytics", DatabaseType.ClickHouse))
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(320.dp, 600.dp)) { ExplorerPanel(app) } } }
        waitForIdle()

        onNodeWithText("Analytics").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("New Console").assertExists()
        onAllNodesWithText("Import from Other Tools…").assertCountEquals(0)
        app.dispose()
    }

    @Test
    fun `the empty sidebar has the menu too`() = runComposeUiTest {
        val app = app()
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(320.dp, 600.dp)) { ExplorerPanel(app) } } }
        waitForIdle()

        onRoot().performMouseInput { rightClick(Offset(160f, 560f)) }
        waitForIdle()
        // The empty state's button and the menu item.
        onAllNodesWithText("Open Data File…").assertCountEquals(2)
        onAllNodesWithText("Collapse All").assertCountEquals(0)
        assertTrue(app.connections.profiles.isEmpty())
        app.dispose()
    }
}

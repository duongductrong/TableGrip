package dev.tablegrip.app.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ConnectionDialogTest {
    private fun app(): AppState {
        val home = createTempDirectory("tablegrip-dialog")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            // No drivers on this machine, and nothing reachable to download them from.
            driverLibrary = DriverLibrary(home.resolve("drivers"), useClasspath = false).apply { repository = "http://127.0.0.1:9/" },
        ).also { it.drivers.library.repository = "http://127.0.0.1:9/" }
    }

    @Test
    fun `any other JDBC URL becomes a profile for the user's own driver jar`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("jdbc:teradata://localhost/DATABASE=sales?password=masterkey")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("Other (JDBC driver)").assertExists()
        onNodeWithText("jdbc:teradata://localhost/DATABASE=sales").assertExists()
        onNodeWithText("Choose jars…").assertExists()
        // URL-only engines have nothing else to connect by.
        onAllNodesWithText("Host and port").assertCountEquals(0)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-other-jdbc.png")) }
        app.dispose()
    }

    @Test
    fun `mongodb connection strings connect by string, with the password moved out`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("mongodb+srv://app:s3cret@cluster0.example.net/shop?retryWrites=true")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("MongoDB").assertExists()
        onNodeWithText("Connection string").assertExists()
        onNodeWithText("mongodb+srv://app@cluster0.example.net/shop?retryWrites=true").assertExists()
        onAllNodesWithText("JDBC URL").assertCountEquals(0)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-mongo.png")) }
        app.dispose()
    }

    @Test
    fun `the driver banner acts on the driver now selected, not the first one`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = false) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onNodeWithText("PostgreSQL JDBC Driver 42", substring = true).assertExists()
        onAllNodes(hasSetTextAction())[0].performTextInput("mysql://root@localhost/shop")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("Download").performClick()
        waitForIdle()
        assertNotNull(app.drivers.state(DriverId.MariaDB), "Download must fetch the MySQL profile's driver")
        assertNull(app.drivers.state(DriverId.PostgreSQL))
        app.dispose()
    }

    @Test
    fun `pasting a libpq URI fills the form and previews the pgjdbc URL`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("postgresql://admin:p%40ss@db.example.com:6543/shop?sslmode=require&application_name=dg")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("db.example.com").assertExists()
        onNodeWithText("6543").assertExists()
        onNodeWithText("shop").assertExists()
        onNodeWithText("admin").assertExists()
        onNodeWithText("jdbc:postgresql://db.example.com:6543/shop").assertExists()
        onNodeWithText("Filled in from the connection string.", substring = true).assertExists()

        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog.png")) }
        app.dispose()
    }

    @Test
    fun `advanced MySQL URLs switch to URL mode without the password`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = false) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("jdbc:mysql:replication://sandy:secret@primary:3306,replica:3306/db")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("jdbc:mysql:replication://sandy@primary:3306,replica:3306/db").assertExists()
        onNodeWithText("JDBC URL").assertExists()
        // MySQL's own driver is GPL and never downloaded, so pasted MySQL URLs use MariaDB Connector/J.
        // The driver picker, and the banner saying what the driver needs.
        onAllNodesWithText("MariaDB Connector/J", substring = true).assertCountEquals(2)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-url.png")) }
        app.dispose()
    }

    @Test
    fun `options say what the connection shares with AI agents`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onNodeWithText("Options").performClick()
        waitForIdle()
        onNodeWithText("AI agents").assertExists()
        onNodeWithText("Use default (Settings › Agent)").assertExists()
        onNodeWithText("Follows Settings › Agent.").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-options.png")) }
        app.dispose()
    }
}

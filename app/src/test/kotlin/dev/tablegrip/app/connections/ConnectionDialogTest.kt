package dev.tablegrip.app.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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

    @Test
    fun `the engine list is searched by name, id or a common alias`() {
        val engines = dev.tablegrip.core.connection.DatabaseType.entries
        kotlin.test.assertEquals(engines, engineMatches(engines, "  "))
        kotlin.test.assertEquals("ClickHouse", engineMatches(engines, "click").first().displayName)
        kotlin.test.assertTrue(engineMatches(engines, "pg").any { it.displayName == "PostgreSQL" })
        kotlin.test.assertTrue(engineMatches(engines, "maria").first().displayName == "MariaDB")
        kotlin.test.assertTrue(engineMatches(engines, "no such engine").isEmpty())
        // Engines whose own name has the word outrank those found only through their family (Neon is PostgreSQL).
        val sql = engineMatches(engines, "sql").map { it.displayName }
        kotlin.test.assertTrue(sql.indexOf("MySQL") < sql.indexOf("Neon"))
    }

    @Test
    fun `typing in the engine list and pressing Enter picks the match`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodesWithText("PostgreSQL")[0].performClick()
        waitForIdle()
        onNode(isFocused() and hasSetTextAction()).performTextInput("clickh")
        waitForIdle()
        onNode(isFocused() and hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("ClickHouse").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-engine-search.png")) }
        app.dispose()
    }

    @Test
    fun `a pasted sslmode lands on the SSL tab, not among the driver properties`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("postgresql://admin@db.example.com/shop?sslmode=verify-full")
        onNodeWithText("Parse").performClick()
        waitForIdle()
        onNodeWithText("SSL ✓").performClick()
        waitForIdle()
        onNodeWithText("Verify full", substring = true).assertExists()
        onNodeWithText("CA certificate").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-ssl.png")) }
        onNodeWithText("Options").performClick()
        waitForIdle()
        onAllNodesWithText("sslmode=verify-full", substring = true).assertCountEquals(0)
        app.dispose()
    }

    @Test
    fun `an error on another tab takes the user there`() = runComposeUiTest {
        val app = app()
        setContent {
            TableGripTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("postgresql://admin@db.example.com/shop")
        onNodeWithText("Parse").performClick()
        onNodeWithText("SSH").performClick()
        waitForIdle()
        onNodeWithText("Connect through an SSH tunnel").performClick()
        onNodeWithText("General").performClick()
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitForIdle()
        onNodeWithText("SSH host is required.").assertExists()
        onNodeWithText("bastion.example.com").assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-error-tab.png")) }
        app.dispose()
    }

    @Test
    fun `a profile that asks for its password keeps none, and asks on connect`() = kotlinx.coroutines.runBlocking {
        val app = app()
        val config = dev.tablegrip.core.connection.ConnectionConfig("p1", "shop", dev.tablegrip.core.connection.DatabaseType.PostgreSQL, database = "shop", askPassword = true)
        withContext(Dispatchers.Main) { app.connections.save(config, "s3cret", "") }.getOrThrow()
        assertNull(app.connections.storedPassword("p1"))
        val asked = async(Dispatchers.Main) { app.connections.connectPassword("p1") }
        while (app.connections.passwordPrompt == null) kotlinx.coroutines.delay(10)
        withContext(Dispatchers.Main) { app.connections.answerPassword(app.connections.passwordPrompt!!, "typed") }
        kotlin.test.assertEquals("typed", asked.await())
        app.dispose()
    }
}

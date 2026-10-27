package dev.datagrok.app.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConnectionDialogTest {
    private fun app(): AppState {
        val home = createTempDirectory("datagrok-dialog")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
        )
    }

    @Test
    fun `pasting a libpq URI fills the form and previews the pgjdbc URL`() = runComposeUiTest {
        val app = app()
        setContent {
            DatagrokTheme(isDark = true) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
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
            DatagrokTheme(isDark = false) { Box(Modifier.size(640.dp, 600.dp)) { ConnectionDialogContent(app.connections, ConnectionDialogRequest(null)) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("jdbc:mysql:replication://sandy:secret@primary:3306,replica:3306/db")
        onNodeWithText("Parse").performClick()
        waitForIdle()

        onNodeWithText("jdbc:mysql:replication://sandy@primary:3306,replica:3306/db").assertExists()
        onNodeWithText("JDBC URL").assertExists()
        onNodeWithText("MySQL Connector/J", substring = true).assertExists()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "connection-dialog-url.png")) }
        app.dispose()
    }
}

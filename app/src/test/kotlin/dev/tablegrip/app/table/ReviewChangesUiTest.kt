package dev.tablegrip.app.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Submit shows the pending SQL first, until the user says not to. With `-Psnapshot.dir=/tmp` it saves screenshots. */
@OptIn(ExperimentalTestApi::class)
class ReviewChangesUiTest {
    private val home = createTempDirectory("tablegrip-review")
    private val db = home.resolve("shop.db").toString()

    init {
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, status TEXT, total NUMERIC)")
                s.executeUpdate("INSERT INTO orders VALUES (1, 'pending', 20), (2, 'pending', 35), (3, 'shipped', 980)")
            }
        }
    }

    private fun status(id: Int): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s -> s.executeQuery("SELECT status FROM orders WHERE id = $id").use { it.next(); it.getString(1) } } }

    private fun app(confirmWrites: Boolean, dark: Boolean = false): AppState {
        home.resolve("connections.json").writeText(
            """[{"id":"shop","name":"Shop","type":"SQLite","database":"${db.replace('\\', '/')}","confirmWrites":$confirmWrites}]"""
        )
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings-$confirmWrites-$dark.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
        )
    }

    @Test
    fun `submit reviews the SQL until told not to`() {
        val out = System.getProperty("snapshot.dir")
        val app = app(confirmWrites = false)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1100.dp, 520.dp)) { MainScreen(app) } } }
            runOnUiThread { app.workspace.openTable(app.connections.profiles.single(), TableRef(Namespace(null, null), "orders")) }
            waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null }
            val tab = app.workspace.selected as TableDataTab
            val status = tab.model!!.columns.indexOfFirst { it.name == "status" }

            // On by default: Submit opens the review and saves nothing yet.
            assertTrue(app.settings.reviewChanges)
            runOnUiThread {
                tab.model!!.setValue(1, status, "paid")
                tab.submit()
            }
            waitForIdle()
            assertNotNull(tab.preview)
            onNodeWithText("Review 1 change").assertExists()
            onNodeWithText("Don't show this again").assertExists()
            out?.let { ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(it, "review-changes.png")) }

            // Cancel keeps the edit pending.
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertNull(tab.preview)
            assertTrue(tab.hasChanges)
            assertEquals("pending", status(2))

            // Submitting with the box ticked saves and stops asking.
            runOnUiThread { tab.submit() }
            waitForIdle()
            onNodeWithText("Don't show this again").performClick()
            onAllNodesWithText("Submit").onLast().performClick()
            waitUntil(timeoutMillis = 10_000) { !tab.submitting && !tab.hasChanges }
            assertEquals("paid", status(2))
            assertFalse(app.settings.reviewChanges)

            // The next submit saves straight away.
            runOnUiThread {
                tab.model!!.setValue(0, status, "paid")
                tab.submit()
            }
            assertNull(tab.preview)
            waitUntil(timeoutMillis = 10_000) { !tab.submitting && !tab.hasChanges }
            assertEquals("paid", status(1))
        }
        app.dispose()
    }

    @Test
    fun `connections that confirm writes always review and can't be skipped`() {
        val app = app(confirmWrites = true)
        app.updateSettings { it.copy(reviewChanges = false) }
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1100.dp, 520.dp)) { MainScreen(app) } } }
            runOnUiThread { app.workspace.openTable(app.connections.profiles.single(), TableRef(Namespace(null, null), "orders")) }
            waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null }
            val tab = app.workspace.selected as TableDataTab
            runOnUiThread {
                tab.model!!.setValue(2, tab.model!!.columns.indexOfFirst { it.name == "status" }, "returned")
                tab.submit()
            }
            waitForIdle()
            assertNotNull(tab.preview)
            onNodeWithText("This connection asks before every change.").assertExists()
            assertTrue(onAllNodesWithText("Don't show this again").fetchSemanticsNodes().isEmpty())
            onAllNodesWithText("Submit").onLast().performClick()
            waitUntil(timeoutMillis = 10_000) { !tab.submitting && !tab.hasChanges }
            assertEquals("returned", status(3))
        }
        app.dispose()
    }
}

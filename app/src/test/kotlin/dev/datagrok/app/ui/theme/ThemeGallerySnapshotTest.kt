package dev.datagrok.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.SettingsStore
import dev.datagrok.core.settings.ThemeSelection
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Renders the main window in every built-in theme for eyeballing:
 * `./gradlew :app:test --tests '*ThemeGallerySnapshotTest*' -Psnapshot.dir=/tmp/themes`.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeGallerySnapshotTest {
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        val home = createTempDirectory("datagrok-themes")
        val db = home.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY, email TEXT, total REAL, shipped_at TEXT)")
                s.executeUpdate("INSERT INTO orders VALUES (1042,'ana@shop.io',312.5,'2026-10-28'),(1043,'li@shop.io',980,NULL),(1047,'tom@shop.io',254.1,'2026-10-30'),(1051,'mai@shop.io',1204.99,'2026-10-31')")
            }
        }
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop (SQLite)","type":"SQLite","database":"$db","environment":"Production"}]""")
        for (entry in ThemeRegistry.entries.filter { it.builtIn }) {
            val dark = entry.spec.isDark
            val settings = Settings(theme = if (dark) ThemeSelection(mode = "Dark", dark = entry.spec.name) else ThemeSelection(mode = "Light", light = entry.spec.name))
            val app =
                AppState(
                    settingsStore = SettingsStore(home.resolve("settings-${entry.spec.name.hashCode()}.json")),
                    connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                    history = dev.datagrok.core.history.QueryHistory(home.resolve("history.jsonl")),
                )
            runComposeUiTest {
                setContent { DatagrokTheme(isDark = dark, settings = settings) { Box(Modifier.size(1300.dp, 760.dp)) { MainScreen(app) } } }
                val config = app.connections.profiles.single()
                runOnUiThread {
                    app.explorer.reveal(config.id)
                    app.workspace.openTable(config, TableRef(Namespace(null, null), "orders"))
                    app.workspace.openConsole(config, "-- paid orders\nSELECT id, lower(email), total FROM orders\nWHERE status = 'paid' AND total > 250 LIMIT 50;")
                }
                waitForIdle()
                Thread.sleep(800)
                waitForIdle()
                val file = entry.spec.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$file-console.png"))
                runOnUiThread { app.workspace.selected = app.workspace.tabs.filterIsInstance<TableDataTab>().first() }
                waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null }
                runOnUiThread { (app.workspace.selected as TableDataTab).grid.reveal(dev.datagrok.app.grid.GridCell(2, 1)) }
                waitForIdle()
                Thread.sleep(500)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$file-table.png"))
                runOnUiThread {
                    app.settingsOpen = true
                    app.settingsPage = "appearance"
                }
                waitForIdle()
                Thread.sleep(300)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$file-settings.png"))
            }
            app.dispose()
        }
    }
}

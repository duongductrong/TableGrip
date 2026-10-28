package dev.datagrok.app.ui

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
import dev.datagrok.app.table.StructureSection
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Renders the main window content against a real SQLite file for eyeballing the Islands layout:
 * `./gradlew :app:test --tests '*MainScreenSnapshotTest*' -Psnapshot.dir=/tmp -Psnapshot.db=/path/to/shop.db`.
 */
@OptIn(ExperimentalTestApi::class)
class MainScreenSnapshotTest {
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        val db = System.getProperty("snapshot.db") ?: return
        for (dark in listOf(true, false)) {
            val home: Path = createTempDirectory("datagrok-snapshot")
            home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop (SQLite)","type":"SQLite","database":"$db","environment":"Production","confirmWrites":true}]""")
            val app =
                AppState(
                    settingsStore = SettingsStore(home.resolve("settings.json")),
                    connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                    history = dev.datagrok.core.history.QueryHistory(home.resolve("history.jsonl")),
                )
            runComposeUiTest {
                setContent { DatagrokTheme(isDark = dark) { Box(Modifier.size(1300.dp, 760.dp)) { MainScreen(app) } } }
                val config = app.connections.profiles.single()
                runOnUiThread {
                    app.explorer.reveal(config.id)
                    app.workspace.openConsole(config, "SELECT c.name, count(*) AS orders\nFROM customers c JOIN orders o ON o.customer_id = c.id\nGROUP BY c.name\nORDER BY orders DESC;")
                    app.workspace.openTable(config, TableRef(Namespace(null, null), "orders"))
                }
                waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null }
                runOnUiThread {
                    val tab = app.workspace.selected as TableDataTab
                    tab.model!!.setValue(2, 3, "refunded")
                    tab.grid.reveal(dev.datagrok.app.grid.GridCell(4, 2))
                    app.explorer.treeState.selectedKeys = setOf(dev.datagrok.app.explorer.ConnectionKey(config.id))
                }
                waitForIdle()
                Thread.sleep(800)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "main-${if (dark) "dark" else "light"}.png"))
                runOnUiThread { app.workspace.selected = app.workspace.tabs.first() }
                waitForIdle()
                Thread.sleep(400)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "console-${if (dark) "dark" else "light"}.png"))
                val table = app.workspace.tabs.filterIsInstance<TableDataTab>().single()
                runOnUiThread {
                    app.workspace.selected = table
                    table.showStructure(StructureSection.Columns)
                }
                waitUntil(timeoutMillis = 10_000) { table.structure != null }
                for (section in listOf(StructureSection.Columns, StructureSection.Constraints, StructureSection.Ddl)) {
                    runOnUiThread { table.section = section }
                    waitForIdle()
                    Thread.sleep(400)
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "structure-${section.name.lowercase()}-${if (dark) "dark" else "light"}.png"))
                }
            }
            app.dispose()
        }
    }
}

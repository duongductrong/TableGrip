package dev.tablegrip.app.schema

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Renders the schema tools against a seeded SQLite file for eyeballing:
 * `./gradlew :app:test --tests '*SchemaToolsSnapshotTest*' -Psnapshot.dir=/tmp/shots`.
 */
@OptIn(ExperimentalTestApi::class)
class SchemaToolsSnapshotTest {
    private fun seed(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            connection.createStatement().use { st ->
                listOf(
                    "CREATE TABLE teams (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, region TEXT DEFAULT 'eu')",
                    "CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email VARCHAR(120) NOT NULL UNIQUE, full_name TEXT, team_id INTEGER REFERENCES teams(id) ON DELETE SET NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE INDEX users_team ON users(team_id)",
                    "CREATE TABLE products (id INTEGER PRIMARY KEY, sku TEXT NOT NULL, title TEXT NOT NULL, price NUMERIC(10,2) NOT NULL, stock INTEGER DEFAULT 0)",
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), status TEXT DEFAULT 'new', total NUMERIC(10,2), placed_at DATETIME)",
                    "CREATE TABLE order_items (order_id INTEGER REFERENCES orders(id), product_id INTEGER REFERENCES products(id), quantity INTEGER NOT NULL, PRIMARY KEY (order_id, product_id))",
                    "CREATE TABLE audit_log (id INTEGER PRIMARY KEY, message TEXT, at DATETIME)",
                    "INSERT INTO teams (name) VALUES ('Core'), ('Growth')",
                    "CREATE TABLE assistant_messages (id BIGINT PRIMARY KEY, session_id VARCHAR(64) NOT NULL, user_id INTEGER REFERENCES users(id), role VARCHAR(16), " +
                        "content LONGTEXT, tool_calls MEDIUMTEXT, tool_call_id VARCHAR(64), model VARCHAR(64), prompt_tokens INT, completion_tokens INT, created_at DATETIME)",
                ).forEach(st::execute)
            }
        }
    }

    /** An older copy of the schema: a column missing, one extra, a table not there yet. */
    private fun seedLegacy(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            connection.createStatement().use { st ->
                listOf(
                    "CREATE TABLE teams (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)",
                    "CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email VARCHAR(80) NOT NULL UNIQUE, full_name TEXT, nickname TEXT, team_id INTEGER REFERENCES teams(id))",
                    "CREATE TABLE products (id INTEGER PRIMARY KEY, sku TEXT NOT NULL, title TEXT NOT NULL, price NUMERIC(10,2) NOT NULL)",
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), total NUMERIC(10,2))",
                    "CREATE TABLE sessions (token TEXT PRIMARY KEY, user_id INTEGER)",
                ).forEach(st::execute)
            }
        }
    }

    private fun ComposeUiTest.shot(out: File, name: String) {
        waitForIdle()
        Thread.sleep(600)
        waitForIdle()
        // A dialog is a root of its own: capture each, the main window first.
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it] }
        roots.forEachIndexed { i, root -> ImageIO.write(root.captureToImage().toAwtImage(), "png", File(out, if (i == 0) "$name.png" else "$name-dialog.png")) }
    }

    @Test
    fun snapshot() {
        val out = File(System.getProperty("snapshot.dir") ?: return).apply { mkdirs() }
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            val home = createTempDirectory("tablegrip-schema-snapshot")
            val db = home.resolve("shop.db").toFile().also(::seed)
            val legacy = home.resolve("legacy.db").toFile().also(::seedLegacy)
            home.resolve("connections.json").writeText(
                """[{"id":"shop","name":"Shop","type":"SQLite","database":"${db.path}","environment":"Development"},""" +
                    """{"id":"legacy","name":"Shop (prod)","type":"SQLite","database":"${legacy.path}","environment":"Production"}]""",
            )
            val app =
                AppState(
                    settingsStore = SettingsStore(home.resolve("settings.json")),
                    connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                    history = dev.tablegrip.core.history.QueryHistory(home.resolve("history.jsonl")),
                )
            val config = app.connections.profiles.first { it.id == "shop" }
            val main = Namespace(null, null)
            runComposeUiTest {
                // Dialogs are windows of their own, which captures don't include: their bodies are shown in place instead.
                var dialog by mutableStateOf<(@Composable () -> Unit)?>(null)
                var density by mutableStateOf(1f)
                setContent {
                    TableGripTheme(isDark = dark) {
                        val body = dialog
                        CompositionLocalProvider(LocalDensity provides Density(density)) {
                            if (body == null) Box(Modifier.size(1300.dp, 800.dp)) { MainScreen(app) } else Box(Modifier.size(940.dp, 660.dp)) { body() }
                        }
                    }
                }

                // Designer: an existing table with a renamed column, a new one and a dropped one.
                runOnUiThread { app.workspace.openTableDesigner(config, main, TableRef(main, "users")) }
                val designer = app.workspace.selected as TableDesignerTab
                waitUntil(timeoutMillis = 10_000) { !designer.loading && designer.columns.isNotEmpty() }
                runOnUiThread {
                    designer.columns.first { it.name.text.toString() == "full_name" }.name.setTextAndPlaceCursorAtEnd("display_name")
                    designer.removeColumn(designer.columns.first { it.name.text.toString() == "created_at" })
                    designer.addColumn().apply { name.setTextAndPlaceCursorAtEnd("last_login_at"); type.setTextAndPlaceCursorAtEnd("DATETIME") }
                }
                shot(out, "designer-columns-$theme")
                runOnUiThread { designer.section = DesignerSection.ForeignKeys }
                shot(out, "designer-fks-$theme")
                // The apply dialog's body, on its own (dialogs are windows of their own).
                runOnUiThread { designer.requestApply() }
                val run = designer.pendingRun!!
                runOnUiThread { designer.pendingRun = null }

                // A new table.
                runOnUiThread { app.workspace.openTableDesigner(config, main) }
                shot(out, "designer-new-$theme")

                // ER diagram of the whole schema, one table selected.
                runOnUiThread { app.workspace.openErDiagram(config, main) }
                val diagram = app.workspace.selected as ErDiagramTab
                waitUntil(timeoutMillis = 10_000) { diagram.boxes.isNotEmpty() }
                runOnUiThread { diagram.selected = TableRef(main, "orders") }
                shot(out, "er-$theme")
                diagram.savePng(File(out, "er-export-$theme.png"), dark)
                // A Retina screen, where text once outgrew the boxes, zoomed in on one wide table.
                runOnUiThread { density = 2f }
                waitForIdle()
                runOnUiThread {
                    diagram.selected = TableRef(main, "assistant_messages")
                    diagram.search.setTextAndPlaceCursorAtEnd("assistant")
                }
                shot(out, "er-retina-$theme")
                runOnUiThread {
                    diagram.search.setTextAndPlaceCursorAtEnd("")
                    density = 1f
                }
                File(out, "er.mmd").writeText(diagram.mermaid())

                // Schema compare against a second, older database.
                runOnUiThread { app.workspace.openSchemaCompare(config, main) }
                val compare = app.workspace.selected as SchemaCompareTab
                runOnUiThread { compare.target.pick(app.connections.profiles.first { it.id == "legacy" }) }
                waitUntil(timeoutMillis = 10_000) { compare.canCompare }
                runOnUiThread { compare.compare() }
                waitUntil(timeoutMillis = 10_000) { compare.comparison != null }
                shot(out, "compare-$theme")

                // Dialog bodies.
                runOnUiThread { dialog = { ScriptDialogContent(app, run, onClose = {}) } }
                shot(out, "designer-apply-dialog-$theme")
                val csv = File(out.parentFile ?: out, "people.csv").apply {
                    writeText("Full Name;Email;Age;Joined;Active\nNguyễn Văn An;an@example.com;31;2024-03-02;yes\nBob Smith;bob@example.com;45;2023-11-20;no\nCarol Lee;;29;2025-01-15;yes\n")
                }
                val wizard = ImportWizard(app, config, main, null)
                runOnUiThread { wizard.open(csv) }
                waitUntil(timeoutMillis = 10_000) { wizard.preview != null }
                runOnUiThread { dialog = { ImportDialogContent(app, wizard) } }
                shot(out, "import-source-$theme")
                runOnUiThread { wizard.next() }
                shot(out, "import-target-$theme")
                val testData = TestDataState(app, config, TableRef(main, "users"))
                waitUntil(timeoutMillis = 10_000) { !testData.loading }
                runOnUiThread { dialog = { TestDataContent(app, testData) } }
                shot(out, "testdata-$theme")
            }
        }
    }
}

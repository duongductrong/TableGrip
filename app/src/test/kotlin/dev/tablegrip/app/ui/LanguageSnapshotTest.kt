package dev.tablegrip.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.Language
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Renders the main window and the settings in one language, for checking translations and how they fit. Run it alone,
 * since the language is fixed for the JVM once texts are built:
 * `./gradlew :app:test --tests '*LanguageSnapshotTest*' -Psnapshot.dir=/tmp -Psnapshot.db=/path/to/shop.db -Psnapshot.lang=vi`.
 */
@OptIn(ExperimentalTestApi::class)
class LanguageSnapshotTest {
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        val db = System.getProperty("snapshot.db") ?: return
        val language = Language.ofTag(System.getProperty("snapshot.lang") ?: return) ?: return
        I18n.install(language)
        val home = createTempDirectory("tablegrip-snapshot")
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"${db.replace('\\', '/')}","environment":"Production"}]""")
        val app =
            AppState(
                settingsStore = SettingsStore(home.resolve("settings.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = dev.tablegrip.core.history.QueryHistory(home.resolve("history.jsonl")),
            )
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1300.dp, 760.dp)) { MainScreen(app) } } }
            val config = app.connections.profiles.single()
            runOnUiThread {
                app.explorer.reveal(config.id)
                app.workspace.openTable(config, TableRef(Namespace(null, null), "orders"))
            }
            waitUntil(timeoutMillis = 10_000) { (app.workspace.selected as? TableDataTab)?.model != null }
            fun shot(name: String) {
                waitForIdle()
                Thread.sleep(600)
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name-${language.tag}.png"))
            }
            shot("main")
            for (page in listOf("appearance", "data-editor")) {
                runOnUiThread {
                    app.settingsPage = page
                    app.settingsOpen = true
                }
                shot("settings-$page")
            }
        }
    }
}

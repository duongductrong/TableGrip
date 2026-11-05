package dev.tablegrip.app.activity

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
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.activity.SessionState
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * Renders the activity tab with one session blocking another, for eyeballing:
 * `./gradlew :app:test --tests '*ActivitySnapshotTest*' -Psnapshot.dir=/tmp -Pit.postgres=localhost:55432` (password `pw`).
 */
@OptIn(ExperimentalTestApi::class)
class ActivitySnapshotTest {
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        val (host, port) = System.getProperty("it.postgres")?.split(":") ?: return
        val url = "jdbc:postgresql://$host:$port/postgres?ApplicationName=billing-worker"
        val driver = DriverLibrary.shared.load(DriverId.PostgreSQL)
        fun connect() = driver.connect(url, Properties().apply { setProperty("user", "postgres"); setProperty("password", "pw") })
        val holder = connect()
        val waiter = connect()
        holder.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS activity_snapshot (id int PRIMARY KEY, total numeric); INSERT INTO activity_snapshot VALUES (1, 0) ON CONFLICT DO NOTHING") }
        holder.autoCommit = false
        holder.createStatement().use { it.execute("UPDATE activity_snapshot SET total = total + 10 WHERE id = 1") }
        val blocked = Thread { runCatching { waiter.createStatement().use { it.execute("UPDATE activity_snapshot SET total = total - 5 WHERE id = 1") } } }.apply { start() }
        try {
            for (dark in listOf(true, false)) {
                val home = createTempDirectory("tablegrip-activity")
                val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore())
                store.save(
                    ConnectionConfig(id = "pg", name = "Orders (PG)", type = DatabaseType.PostgreSQL, host = host, port = port.toInt(), database = "postgres", user = "postgres", environment = Environment.Production),
                    password = "pw",
                )
                val app = AppState(SettingsStore(home.resolve("settings.json")), store, dev.tablegrip.core.history.QueryHistory(home.resolve("history.jsonl")))
                runComposeUiTest {
                    setContent { TableGripTheme(isDark = dark) { Box(Modifier.size(1400.dp, 720.dp)) { MainScreen(app) } } }
                    runOnUiThread { app.workspace.openActivity(app.connections.profiles.single()) }
                    val tab = app.workspace.selected as ActivityTab
                    waitUntil(timeoutMillis = 15_000) { tab.snapshot?.sessions?.any { it.state == SessionState.Blocked } == true }
                    runOnUiThread {
                        tab.interval = 0
                        tab.selectedId = tab.snapshot!!.sessions.first { it.state == SessionState.Blocked }.id
                    }
                    waitForIdle()
                    Thread.sleep(500)
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "activity-${if (dark) "dark" else "light"}.png"))
                    runOnUiThread { tab.requestSignal(tab.selected!!, terminate = true) }
                    waitForIdle()
                    Thread.sleep(500)
                    waitForIdle()
                    app.dispose()
                }
            }
        } finally {
            holder.rollback()
            blocked.join(5_000)
            holder.createStatement().use { it.execute("DROP TABLE IF EXISTS activity_snapshot") }
            holder.commit()
            holder.close()
            waiter.close()
        }
    }
}

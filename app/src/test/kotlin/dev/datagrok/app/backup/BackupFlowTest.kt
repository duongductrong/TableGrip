package dev.datagrok.app.backup

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
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.core.backup.BackupSchedule
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.driver.Database
import dev.datagrok.core.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalTestApi::class)
class BackupFlowTest {
    private fun app(vararg configs: Pair<ConnectionConfig, String?>): AppState {
        val home = createTempDirectory("datagrok-backup")
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore())
        configs.forEach { (config, password) -> store.save(config, password) }
        return AppState(SettingsStore(home.resolve("settings.json")), store, dev.datagrok.core.history.QueryHistory(home.resolve("history.jsonl")))
    }

    @Test
    fun scheduledSqliteBackupKeepsTheNewest(): Unit = runBlocking {
        val dir = createTempDirectory("lite")
        val config = ConnectionConfig("lite", "Lite", DatabaseType.SQLite, database = dir.resolve("a.db").toString())
        Database.connect(config, null).use { db -> db.openSession().use { it.execute("CREATE TABLE t (x int)") } }
        val app = app(config to null)
        try {
            val folder = dir.resolve("backups")
            val schedule = BackupSchedule("s1", "lite", folder = folder.toString(), keep = 2)
            repeat(3) {
                assertNull(app.scheduledBackups.runOnce(schedule))
                delay(1_100) // file names are stamped to the second
            }
            val files = Files.list(folder).use { it.map { p -> p.name }.toList() }
            assertEquals(2, files.size, files.toString())
            assertTrue(files.all { it.startsWith("scheduled-s1-") && it.endsWith(".db") })
        } finally {
            app.dispose()
        }
    }

    /** Skipped unless `-Pit.postgres=host:port` and pg_dump is installed. */
    @Test
    fun postgresBackupAndRestoreThroughTheDialogState(): Unit = runBlocking {
        val (host, port) = System.getProperty("it.postgres")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig("pg", "Orders", DatabaseType.PostgreSQL, host, port.toInt(), "postgres", "postgres")
        val app = app(config to "pw")
        try {
            val backup = BackupState(app, config, restore = false)
            withTimeout(20_000) { while (backup.check is ToolCheck.Checking) delay(50) }
            if (backup.check !is ToolCheck.Ready) return@runBlocking
            val target = createTempDirectory("pg").resolve("orders.dump")
            backup.target = target
            backup.start()
            withTimeout(60_000) { while (backup.running) delay(100) }
            assertNull(backup.error)
            assertTrue(target.exists() && Files.size(target) > 0)
            assertTrue(backup.logFile!!.exists())

            val restore = BackupState(app, config.copy(environment = Environment.Production), restore = true)
            withTimeout(20_000) { while (restore.check is ToolCheck.Checking) delay(50) }
            restore.chooseSource(target)
            withTimeout(20_000) { while (restore.listing || restore.check is ToolCheck.Checking) delay(50) }
            assertTrue(restore.entries.isNotEmpty())
            restore.targetDatabase.edit { replace(0, length, "backup_flow_it") }
            assertTrue(restore.blocker!!.contains("Production"), "Production needs the name typed again")
            restore.confirmName.edit { replace(0, length, "backup_flow_it") }
            assertNull(restore.blocker)
            restore.start()
            withTimeout(60_000) { while (restore.running) delay(100) }
            assertNull(restore.error, restore.error)
        } finally {
            runCatching { Database.connect(config, "pw").use { db -> db.openSession().use { it.execute("DROP DATABASE IF EXISTS backup_flow_it WITH (FORCE)") } } }
            app.dispose()
        }
    }

    @Test
    fun readOnlyConnectionsCantBeRestoredInto() {
        val config = ConnectionConfig("pg", "Orders", DatabaseType.PostgreSQL, "localhost", 1, "postgres", "postgres", readOnly = true)
        val app = app(config to null)
        try {
            val restore = BackupState(app, config, restore = true)
            assertTrue(restore.blocker!!.contains("read-only"))
        } finally {
            app.dispose()
        }
    }

    /** `-Psnapshot.dir=/tmp -Pit.postgres=localhost:55432`: the backup and restore forms and Settings → Tools and Backups. */
    @Test
    fun snapshots() {
        val out = System.getProperty("snapshot.dir") ?: return
        val (host, port) = System.getProperty("it.postgres")?.split(":") ?: return
        val config = ConnectionConfig("pg", "Orders (PG)", DatabaseType.PostgreSQL, host, port.toInt(), "postgres", "postgres", environment = Environment.Production)
        val app = app(config to "pw")
        app.updateSettings { it.copy(backupSchedules = listOf(BackupSchedule("s1", "pg", keep = 7))) }
        try {
            for ((name, restore) in listOf("backup" to false, "restore" to true)) {
                val state = BackupState(app, config, restore)
                runComposeUiTest {
                    setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(680.dp, 600.dp)) { BackupContent(app, state) } } }
                    waitUntil(timeoutMillis = 15_000) { state.check !is ToolCheck.Checking }
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name-dialog.png"))
                }
            }
            for (page in listOf("tools", "backups")) {
                runComposeUiTest {
                    setContent { DatagrokTheme(isDark = true) { Box(Modifier.size(1100.dp, 760.dp)) { MainScreen(app) } } }
                    runOnUiThread {
                        app.settingsPage = page
                        app.settingsOpen = true
                    }
                    // Tools are found on IO; each pass lets their results land.
                    repeat(8) {
                        Thread.sleep(400)
                        waitForIdle()
                    }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "settings-$page.png"))
                }
            }
        } finally {
            app.dispose()
        }
    }
}

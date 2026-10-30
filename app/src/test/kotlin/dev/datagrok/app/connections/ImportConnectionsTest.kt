package dev.datagrok.app.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ImportConnectionsTest {
    private val home: Path = createTempDirectory("datagrok-import")
    private val secrets = InMemorySecretStore()

    private fun app() =
        AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), secrets),
            history = QueryHistory(home.resolve("history.jsonl")),
            driverLibrary = DriverLibrary(home.resolve("drivers"), useClasspath = false).apply { repository = "http://127.0.0.1:9/" },
        )

    @Test
    fun `a project's env file lists its databases, skips ones already saved, and imports the rest with passwords`() = runComposeUiTest {
        val project = home.resolve("shop").createDirectories()
        project.resolve(".env").writeText(
            """
            DATABASE_URL=postgres://app:pw@db.local:5432/shop
            REDIS_URL=redis://cache.local:6379
            """.trimIndent(),
        )
        val app = app()
        app.connections.updateProfile(ConnectionConfig("existing", "Cache", DatabaseType.Redis, host = "cache.local", port = 6379))
        waitUntil { app.connections.profiles.size == 1 }

        val import = ConnectionImportState(app.connections, app.scope)
        setContent {
            DatagrokTheme(isDark = true) { Box(Modifier.size(780.dp, 580.dp)) { ImportConnectionsContent(app.connections, import) } }
        }
        import.add(project)
        waitUntil(timeoutMillis = 5_000) { import.rows.size == 2 && !import.scanning }

        onNodeWithText("shop · DATABASE_URL").assertExists()
        onNodeWithText("already added").assertExists()
        // Only the new one is ticked.
        onNodeWithText("Import 1 Connection").performClick()
        waitUntil(timeoutMillis = 5_000) { app.connections.profiles.size == 2 }

        val imported = app.connections.profiles.single { it.id != "existing" }
        assertEquals("db.local", imported.host)
        assertEquals("shop", imported.group)
        assertEquals("pw", secrets.get(imported.id))
        assertNull(app.connections.importing)
    }

    @Test
    fun `files it doesn't recognise are reported instead of failing`() {
        val app = app()
        val import = ConnectionImportState(app.connections, app.scope)
        val file = home.resolve("notes.bin").also { it.writeText("\u0000\u0001 nothing here") }
        import.add(file)
        assertEquals("Datagrok doesn't recognise notes.bin as a connection list.", import.message)
    }
}

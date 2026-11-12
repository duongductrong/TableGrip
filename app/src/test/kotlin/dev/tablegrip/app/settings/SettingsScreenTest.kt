package dev.tablegrip.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import dev.tablegrip.app.ui.Shortcut
import dev.tablegrip.core.settings.KeyBinding
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.drivers.DriverLock
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.drivers.LicenseTier
import dev.tablegrip.core.drivers.LockedArtifact
import dev.tablegrip.core.drivers.LockedDriver
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.fileSize
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private fun app(): AppState {
        val home = createTempDirectory("tablegrip-settings")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
        )
    }

    @Test
    fun `settings apply as typed and only when valid`() = runComposeUiTest {
        val app = app()
        app.settingsOpen = true
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 640.dp)) { MainScreen(app) } } }
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-screen.png")) }

        onAllNodes(hasSetTextAction())[0].performTextInput("timeout")
        waitForIdle()
        assertEquals(2, onAllNodesWithText("Query Execution").fetchSemanticsNodes().size, "sidebar item and page title")
        onNodeWithText("Appearance").assertDoesNotExist()

        // Search, max result rows, then the timeout.
        val timeoutField = onAllNodes(hasSetTextAction())[2]
        timeoutField.performTextReplacement("-5")
        waitForIdle()
        onNodeWithText("Must be a number from 0 to 86400").assertExists()
        assertEquals(0, app.settings.queryTimeoutSeconds)
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-screen-error.png")) }

        timeoutField.performTextReplacement("45")
        waitForIdle()
        assertEquals(45, app.settings.queryTimeoutSeconds)

        onNodeWithContentDescription("Settings (${Shortcuts.Settings})").performClick()
        waitForIdle()
        assertFalse(app.settingsOpen)
        app.dispose()
    }

    @Test
    fun `the drivers page lists installed drivers and removes them`() = runComposeUiTest {
        // SQLite's jar from the test classpath, imported as if copied from a Maven repository.
        val jar = Path.of(Class.forName("org.sqlite.JDBC").protectionDomain.codeSource.location.toURI())
        val lock =
            DriverLock(
                listOf(
                    LockedDriver(
                        "sqlite", "SQLite JDBC", "1.0.0", "org.sqlite.JDBC", "Apache-2.0", LicenseTier.Permissive, "https://example.invalid/license", "https://example.invalid/source",
                        listOf(LockedArtifact("org.xerial:sqlite-jdbc:1.0.0", "org/xerial/sqlite-jdbc/1.0.0/${jar.fileName}", sha256(jar), jar.fileSize(), "Apache-2.0")),
                    )
                )
            )
        val home = createTempDirectory("tablegrip-drivers")
        val library = DriverLibrary(home.resolve("drivers"), lock, useClasspath = false)
        runBlocking { library.importFrom(jar.parent) }
        val app =
            AppState(
                settingsStore = SettingsStore(home.resolve("settings.json")),
                connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
                history = QueryHistory(home.resolve("history.jsonl")),
                driverLibrary = library,
            )
        app.settingsOpen = true
        app.settingsPage = "drivers"
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 900.dp)) { MainScreen(app) } } }
        waitForIdle()
        System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-drivers.png")) }

        onNodeWithText("SQLite JDBC (Xerial)").assertExists()
        onNodeWithText("1.0.0").assertExists()
        onNodeWithText("Remove").performClick()
        waitForIdle()
        onNodeWithText("Delete").performClick()
        // The library reads the disk, so it reports the driver gone as soon as the manifest is deleted: before the
        // rest of the folder is (slow on Windows) and before the page recomposes. Wait for the page instead.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Not downloaded").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList(), library.installedVersions(DriverId.SQLite))
        assertIs<DriverResolution.Download>(library.resolve(DriverId.SQLite))
        app.dispose()
    }

    @Test
    fun `automatic installs can only be changed while automatic checks are on`() = runComposeUiTest {
        val app = app()
        app.settingsOpen = true
        app.settingsPage = "about"
        setContent { TableGripTheme(isDark = true, settings = app.settings) { Box(Modifier.size(1100.dp, 900.dp)) { MainScreen(app) } } }

        onNodeWithContentDescription("Download and install updates automatically").assertIsEnabled()
        onNodeWithContentDescription("Check for updates automatically").performClick()
        waitForIdle()
        assertFalse(app.settings.checkForUpdates)
        onNodeWithContentDescription("Download and install updates automatically").assertIsNotEnabled()
        app.dispose()
    }

    @Test
    fun `customization pages render and a shortcut can be recorded`() = runComposeUiTest {
        val app = app()
        app.settingsOpen = true
        setContent { TableGripTheme(isDark = true, settings = app.settings) { Box(Modifier.size(1100.dp, 900.dp)) { MainScreen(app) } } }
        for (page in listOf("appearance", "color-scheme", "behavior", "editor", "data-editor", "about", "keymap")) {
            app.settingsPage = page
            waitForIdle()
            System.getProperty("snapshot.dir")?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "settings-$page.png")) }
        }

        // Narrow the list so the action is on screen.
        onAllNodes(hasSetTextAction())[1].performTextInput("Reformat")
        waitForIdle()
        onNodeWithText(Shortcuts.Format.label).performClick()
        waitForIdle()
        onNodeWithText("Press keys… (Esc cancels)").performKeyInput { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.F) } } }
        waitForIdle()
        assertEquals(KeyBinding(java.awt.event.KeyEvent.VK_F, "F", shift = true, alt = true), app.settings.keyBindings["Format"])
        assertEquals(Shortcut(Key.F, "F", shift = true, alt = true), Shortcuts.Format)
        onNodeWithText("Reset to defaults").performClick()
        waitForIdle()
        assertEquals(emptyMap(), app.settings.keyBindings)
        app.dispose()
    }

    private fun sha256(file: Path): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `reset puts a page back to defaults`() {
        val app = app()
        app.updateSettings { it.copy(queryTimeoutSeconds = 60, maxResultRows = 50) }
        val form = SettingsForm({ app.settings }) { updated -> app.updateSettings { updated } }
        form.reset(SettingsPages.byId("queries")!!)
        assertEquals(0, app.settings.queryTimeoutSeconds)
        assertEquals(1_000, app.settings.maxResultRows)
        app.dispose()
    }
}

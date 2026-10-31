package dev.datagrok.app.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.sql.SqlSyntax
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyntaxSchemeTest {
    /** Whether some pixel is (nearly) [rgb], i.e. text was drawn in that colour. */
    private fun BufferedImage.has(rgb: Int): Boolean {
        for (x in 0 until width) for (y in 0 until height) {
            val p = getRGB(x, y)
            if (abs((p shr 16 and 0xFF) - (rgb shr 16 and 0xFF)) < 6 && abs((p shr 8 and 0xFF) - (rgb shr 8 and 0xFF)) < 6 && abs((p and 0xFF) - (rgb and 0xFF)) < 6) return true
        }
        return false
    }

    @Test
    fun `changing the scheme recolours an open editor`() = runComposeUiTest {
        var settings by mutableStateOf(Settings(editorFontSize = 20))
        val state = TextFieldState("SELECT 1 FROM t WHERE x = 'abc'")
        setContent { DatagrokTheme(isDark = true, settings = settings) { Box(Modifier.size(700.dp, 200.dp)) { SqlEditor(state, SqlSyntax.PostgreSQL, settings.editorFontSize) } } }
        waitForIdle()
        val before = onRoot().captureToImage().toAwtImage()
        assertTrue(before.has(0xCF8E6D), "Datagrok keyword colour drawn")
        assertFalse(before.has(0x272822), "Datagrok keeps the island background")

        settings = settings.copy(syntaxScheme = SyntaxScheme.Monokai.name)
        waitForIdle()
        val after = onRoot().captureToImage().toAwtImage()
        assertTrue(after.has(0xF92672), "Monokai keyword colour drawn")
        assertFalse(after.has(0xCF8E6D), "Datagrok keyword colour gone")
        assertTrue(after.has(0x272822), "Monokai background drawn")

        settings = settings.copy(syntaxColors = mapOf("dark.keyword" to "#00FF00"))
        waitForIdle()
        assertTrue(onRoot().captureToImage().toAwtImage().has(0x00FF00), "custom keyword colour drawn")
    }

    @Test
    fun `picking a scheme in Settings recolours the console`() {
        val home = kotlin.io.path.createTempDirectory("datagrok-scheme")
        val db = home.resolve("shop.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").close()
        java.nio.file.Files.writeString(home.resolve("connections.json"), """[{"id":"shop","name":"Shop","type":"SQLite","database":"$db"}]""")
        val app =
            dev.datagrok.app.AppState(
                settingsStore = dev.datagrok.core.settings.SettingsStore(home.resolve("settings.json")),
                connectionStore = dev.datagrok.core.connection.ConnectionStore(home.resolve("connections.json"), dev.datagrok.core.connection.InMemorySecretStore()),
                history = dev.datagrok.core.history.QueryHistory(home.resolve("history.jsonl")),
            )
        runComposeUiTest {
            setContent { DatagrokTheme(isDark = true, settings = app.settings) { Box(Modifier.size(1200.dp, 700.dp)) { dev.datagrok.app.ui.MainScreen(app) } } }
            runOnUiThread { app.workspace.openConsole(app.connections.profiles.single(), "SELECT 1 FROM t WHERE x = 'abc'") }
            waitForIdle()
            assertTrue(onRoot().captureToImage().toAwtImage().has(0xCF8E6D), "Datagrok keyword colour drawn")
            // As the Settings screen does it.
            app.settingsOpen = true
            app.settingsPage = "color-scheme"
            waitForIdle()
            onNodeWithText("Datagrok").performClick()
            waitForIdle()
            onNodeWithText("Monokai").performClick()
            waitForIdle()
            kotlin.test.assertEquals("Monokai", app.settings.syntaxScheme)
            // The page's own preview shows it while Settings covers the console.
            val page = onRoot().captureToImage().toAwtImage()
            System.getProperty("snapshot.dir")?.let { javax.imageio.ImageIO.write(page, "png", java.io.File(it, "settings-scheme-preview.png")) }
            assertTrue(page.has(0xF92672) && page.has(0x272822), "preview in Monokai")
            app.settingsOpen = false
            waitForIdle()
            System.getProperty("snapshot.dir")?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(it, "console-monokai.png")) }
            assertTrue(onRoot().captureToImage().toAwtImage().has(0xF92672), "Monokai keyword colour drawn")
        }
        app.dispose()
    }
}

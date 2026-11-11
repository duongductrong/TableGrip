package dev.tablegrip.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.settings.Settings
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class UpdatesPanelTest {
    private class FakeUpdater : Updater {
        override val unavailableReason: String? = null
        override val lastCheck: Instant? = null
        override val quitting = false
        @Volatile override var status: UpdateStatus = UpdateStatus.Idle
        var checks = 0
        var windowChecks = 0
        var installs = 0

        override fun start(settings: Settings) {}

        override fun configure(settings: Settings) {}

        override fun checkNow() {
            windowChecks++
            status = UpdateStatus.Checking
        }

        override fun check() {
            checks++
            status = UpdateStatus.Checking
        }

        override fun install() {
            installs++
        }

        override fun quit(relaunch: Boolean) = false
    }

    @Test
    fun `checking, installing and restarting are offered as the status changes`() = runComposeUiTest {
        val updater = FakeUpdater()
        var restarts = 0
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(700.dp, 300.dp)) { UpdatesContent(updater) { restarts++ } } } }

        onNodeWithText("Not checked yet.").assertExists()
        onNodeWithText("Check for Updates…").performClick()
        assertEquals(1, updater.windowChecks, "the button opens the updater's window right away")
        assertEquals(0, updater.checks)
        mainClock.advanceTimeBy(1_100)
        onNodeWithText("Checking for updates…").assertExists()
        onNodeWithText("Checking…").assertIsNotEnabled()

        updater.status = UpdateStatus.Available("1.0.1")
        mainClock.advanceTimeBy(1_100)
        onNodeWithText("TableGrip 1.0.1 is available.").assertExists()
        onNodeWithText("What's new").assertExists()
        onNodeWithText("Download and Install…").performClick()
        assertEquals(1, updater.installs)

        updater.status = UpdateStatus.Ready("1.0.1")
        mainClock.advanceTimeBy(1_100)
        onNodeWithText("Download and Install…").assertDoesNotExist()
        onNodeWithText("Restart to Update").performClick()
        assertEquals(1, restarts)

        updater.status = UpdateStatus.Failed("The feed couldn't be read.")
        mainClock.advanceTimeBy(1_100)
        onNodeWithText("Couldn't check for updates.").assertExists()
        onNodeWithText("The feed couldn't be read.").assertExists()
    }

    @Test
    fun `the check button stays visible but disabled where the app can't update itself`() = runComposeUiTest {
        val updater = object : Updater by FakeUpdater() {
            override val unavailableReason = "This copy runs from source, not an installed app."
        }
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(700.dp, 300.dp)) { UpdatesContent(updater) {} } } }

        onNodeWithText("Check for Updates…").assertIsNotEnabled()
        onNodeWithText("This copy runs from source, not an installed app.").assertExists()
    }

    /** Renders each status for eyeballing: `./gradlew :app:test --tests '*UpdatesPanelTest*' -Psnapshot.dir=/tmp`. */
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        val statuses = listOf(UpdateStatus.Idle, UpdateStatus.Checking, UpdateStatus.UpToDate, UpdateStatus.Available("1.0.1"), UpdateStatus.Ready("1.0.1"), UpdateStatus.Failed("The feed couldn't be read."))
        for (dark in listOf(true, false)) {
            for (status in statuses) {
                val updater = object : Updater by FakeUpdater() {
                    override val status = status
                    override val lastCheck: Instant = Instant.parse("2026-11-11T08:30:00Z")
                }
                runComposeUiTest {
                    setContent { TableGripTheme(isDark = dark) { Box(Modifier.size(720.dp, 240.dp).background(LocalIslands.current.frame).padding(24.dp)) { UpdatesContent(updater) {} } } }
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "updates-${status::class.simpleName}-${if (dark) "dark" else "light"}.png"))
                }
            }
        }
    }

    @Test
    fun `native status codes map to statuses`() {
        assertEquals(UpdateStatus.Available("1.2.0"), UpdateStatus.of(3, "1.2.0"))
        assertEquals(UpdateStatus.Ready(null), UpdateStatus.of(4, ""))
        assertEquals(UpdateStatus.Failed("The check failed."), UpdateStatus.of(5, ""))
        assertEquals(UpdateStatus.Idle, UpdateStatus.of(42, ""))
    }
}

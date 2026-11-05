package dev.tablegrip.app.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.settings.Settings
import java.time.Instant
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
        var installs = 0

        override fun start(settings: Settings) {}

        override fun configure(settings: Settings) {}

        override fun checkNow() {}

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
        onNodeWithText("Check for Updates").performClick()
        assertEquals(1, updater.checks)
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
        onNodeWithText("Couldn't check for updates: The feed couldn't be read.").assertExists()
    }

    @Test
    fun `native status codes map to statuses`() {
        assertEquals(UpdateStatus.Available("1.2.0"), UpdateStatus.of(3, "1.2.0"))
        assertEquals(UpdateStatus.Ready(null), UpdateStatus.of(4, ""))
        assertEquals(UpdateStatus.Failed("The check failed."), UpdateStatus.of(5, ""))
        assertEquals(UpdateStatus.Idle, UpdateStatus.of(42, ""))
    }
}

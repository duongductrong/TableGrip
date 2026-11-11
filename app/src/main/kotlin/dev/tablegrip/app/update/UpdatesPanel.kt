package dev.tablegrip.app.update

import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.browse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

private const val ReleasesPage = "https://github.com/duongductrong/TableGrip/releases"

/** Settings → Updates: the running version, what the last check found, and checking or installing now. */
@Composable
internal fun UpdatesPanel(app: AppState) {
    UpdatesContent(app.updater, onRestartToUpdate = { app.restartToUpdate() })
}

@Composable
internal fun UpdatesContent(updater: Updater, onRestartToUpdate: () -> Unit) {
    val info = JewelTheme.globalColors.text.info
    // The updaters report on their own threads; the panel only needs to catch up within a second.
    val state by produceState(updater.status to updater.lastCheck, updater) {
        while (true) {
            value = updater.status to updater.lastCheck
            delay(1_000)
        }
    }
    val (status, lastCheck) = state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("TableGrip {0}", AppVersion.current))
        val reason = updater.unavailableReason
        if (reason != null) {
            // The button stays where it always is, so it can be found; it just can't run here.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {}, enabled = false) { Text(tr("Check for Updates…")) }
                Link(tr("Releases page"), onClick = { browse(ReleasesPage) })
            }
            Text(reason, color = info, fontSize = 12.sp)
            return@Column
        }
        StatusLine(status, lastCheck, info)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                is UpdateStatus.Ready -> DefaultButton(onClick = onRestartToUpdate) { Text(tr("Restart to Update")) }
                is UpdateStatus.Available -> DefaultButton(onClick = updater::install) { Text(tr("Download and Install…")) }
                else -> {}
            }
            // Opens the updater's own window at once: it shows the check's progress, then the next version with its
            // release notes and Install / Skip / Remind Me Later, or says TableGrip is up to date.
            OutlinedButton(onClick = updater::checkNow, enabled = status != UpdateStatus.Checking) {
                Text(if (status == UpdateStatus.Checking) tr("Checking…") else tr("Check for Updates…"))
            }
            val version = (status as? UpdateStatus.Available)?.version ?: (status as? UpdateStatus.Ready)?.version
            if (version != null) Link(tr("What's new"), onClick = { browse("$ReleasesPage/tag/v$version") })
        }
        Text(
            tr("A check only asks GitHub for the list of releases; nothing about you or your databases is sent. Every update is verified against ") +
                tr("TableGrip's signing key before it's installed."),
            color = info,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusLine(status: UpdateStatus, lastCheck: Instant?, info: Color) {
    val checked = lastCheck?.let { tr("Last checked {0}.", CheckTime.format(it)) } ?: tr("Not checked yet.")
    val message =
        when (status) {
            UpdateStatus.Idle -> checked
            UpdateStatus.Checking -> tr("Checking for updates…")
            UpdateStatus.UpToDate -> tr("TableGrip is up to date. {0}", checked)
            is UpdateStatus.Available -> tr("{0} is available.", status.version?.let { tr("TableGrip {0}", it) } ?: tr("A new version"))
            is UpdateStatus.Ready -> tr("{0} is downloaded and installs when you quit, or restart now.", status.version?.let { tr("TableGrip {0}", it) } ?: tr("The update"))
            is UpdateStatus.Failed -> tr("Couldn't check for updates: {0}", status.reason)
        }
    val color = if (status is UpdateStatus.Failed) JewelTheme.globalColors.text.error else if (status is UpdateStatus.Idle) info else Color.Unspecified
    Text(message, color = color, fontSize = 13.sp)
}

private val CheckTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(I18n.locale).withZone(ZoneId.systemDefault())

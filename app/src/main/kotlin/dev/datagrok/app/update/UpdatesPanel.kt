package dev.datagrok.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.browse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

private const val ReleasesPage = "https://github.com/duongductrong/datagrok/releases"

/** Settings → Updates: the running version, the last check, and checking now. */
@Composable
internal fun UpdatesPanel(app: AppState) {
    val updater = app.updater
    val info = JewelTheme.globalColors.text.info
    // Sparkle records the check on its own thread; the panel only needs to catch up within a few seconds.
    val lastCheck by produceState<Instant?>(updater.lastCheck, updater) {
        while (true) {
            value = updater.lastCheck
            delay(2_000)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Datagrok ${AppVersion.current}")
        val reason = updater.unavailableReason
        if (reason != null) {
            Text(reason, color = info, fontSize = 12.sp)
            Link("Releases page", onClick = { browse(ReleasesPage) })
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = updater::checkNow) { Text("Check Now") }
            Text(lastCheck?.let { "Last checked ${CheckTime.format(it)}" } ?: "Not checked yet", color = info, fontSize = 12.sp)
        }
        Text(
            "A check only asks GitHub for the list of releases; nothing about you or your databases is sent. Every update is verified against " +
                "Datagrok's signing key before it's installed.",
            color = info,
            fontSize = 12.sp,
        )
    }
}

private val CheckTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

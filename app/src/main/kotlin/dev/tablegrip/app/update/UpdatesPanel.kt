package dev.tablegrip.app.update

import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.browse
import dev.tablegrip.app.ui.AppIcon
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.LocalIslands
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val ReleasesPage = "https://github.com/duongductrong/TableGrip/releases"

/** Settings → Updates: the running version, what the last check found, and checking or installing now. */
@Composable
internal fun UpdatesPanel(app: AppState) {
    UpdatesContent(app.updater, onRestartToUpdate = { app.restartToUpdate() })
}

/**
 * A card in the manner of the system's own software-update pane: who and which version on top, then what the last
 * check found beside the one action that fits it.
 */
@Composable
internal fun UpdatesContent(updater: Updater, onRestartToUpdate: () -> Unit) {
    // The updaters report on their own threads; the panel only needs to catch up within a second.
    val state by produceState(updater.status to updater.lastCheck, updater) {
        while (true) {
            value = updater.status to updater.lastCheck
            delay(1_000)
        }
    }
    val (status, lastCheck) = state
    val reason = updater.unavailableReason
    val version = (status as? UpdateStatus.Available)?.version ?: (status as? UpdateStatus.Ready)?.version
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Island(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                AppHeader(link = if (reason != null) tr("Releases page") to ReleasesPage else version?.let { tr("What's new") to "$ReleasesPage/tag/v$it" })
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth(), color = LocalIslands.current.divider)
                if (reason != null) {
                    // The button stays where it always is, so it can be found; it just can't run here.
                    StatusRow(
                        glyph = { StatusGlyph(UpdateStatus.Idle) },
                        headline = tr("Updates aren't available here"),
                        detail = reason,
                    ) {
                        OutlinedButton(onClick = {}, enabled = false) { Text(tr("Check for Updates…")) }
                    }
                } else {
                    val (headline, detail) = describe(status, lastCheck)
                    StatusRow(glyph = { StatusGlyph(status) }, headline = headline, detail = detail, failed = status is UpdateStatus.Failed) {
                        // Opens the updater's own window at once: it shows the check's progress, then the next version with its
                        // release notes and Install / Skip / Remind Me Later, or says TableGrip is up to date.
                        val checkLabel = if (status == UpdateStatus.Checking) tr("Checking…") else tr("Check for Updates…")
                        when (status) {
                            is UpdateStatus.Ready -> {
                                OutlinedButton(onClick = updater::checkNow) { Text(checkLabel) }
                                DefaultButton(onClick = onRestartToUpdate) { Text(tr("Restart to Update")) }
                            }
                            is UpdateStatus.Available -> {
                                OutlinedButton(onClick = updater::checkNow) { Text(checkLabel) }
                                DefaultButton(onClick = updater::install) { Text(tr("Download and Install…")) }
                            }
                            else -> DefaultButton(onClick = updater::checkNow, enabled = status != UpdateStatus.Checking) { Text(checkLabel) }
                        }
                    }
                }
            }
        }
        Text(
            tr("A check only asks GitHub for the list of releases; nothing about you or your databases is sent. Every update is verified against ") +
                tr("TableGrip's signing key before it's installed."),
            Modifier.padding(horizontal = 4.dp),
            color = JewelTheme.globalColors.text.info,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AppHeader(link: Pair<String, String>?) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = AppIcon
        if (icon != null) {
            Image(icon, null, Modifier.size(52.dp))
        } else {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(LocalIslands.current.accent))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("TableGrip", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("Version {0}", AppVersion.current), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        if (link != null) Link(link.first, onClick = { browse(link.second) })
    }
}

@Composable
private fun StatusRow(
    glyph: @Composable () -> Unit,
    headline: String,
    detail: String?,
    failed: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { glyph() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, color = if (failed) JewelTheme.globalColors.text.error else Color.Unspecified, fontWeight = FontWeight.Medium)
            if (detail != null) Text(detail, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun StatusGlyph(status: UpdateStatus) {
    val size = Modifier.size(18.dp)
    val tint =
        when (status) {
            UpdateStatus.UpToDate -> Color(0xFF4CA35A)
            is UpdateStatus.Failed -> Color(0xFFDB4B4B)
            else -> LocalIslands.current.accent
        }
    // A soft disc behind the glyph, as the system's update panes have.
    Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = if (JewelTheme.isDark) 0.18f else 0.12f)), contentAlignment = Alignment.Center) {
        when (status) {
            UpdateStatus.Checking -> CircularProgressIndicator(size)
            UpdateStatus.UpToDate -> Icon(AllIconsKeys.General.InspectionsOK, null, size)
            is UpdateStatus.Available, is UpdateStatus.Ready -> Icon(AllIconsKeys.Actions.Download, null, size)
            is UpdateStatus.Failed -> Icon(AllIconsKeys.General.Error, null, size)
            UpdateStatus.Idle -> Icon(AllIconsKeys.General.Information, null, size)
        }
    }
}

/** The status's headline and the line under it. */
private fun describe(status: UpdateStatus, lastCheck: Instant?): Pair<String, String?> {
    val checked = lastCheck?.let { tr("Last checked {0}.", CheckTime.format(it)) }
    return when (status) {
        UpdateStatus.Idle -> (checked ?: tr("Not checked yet.")) to null
        UpdateStatus.Checking -> tr("Checking for updates…") to null
        UpdateStatus.UpToDate -> tr("TableGrip is up to date.") to (checked ?: tr("Not checked yet."))
        is UpdateStatus.Available -> tr("{0} is available.", status.version?.let { tr("TableGrip {0}", it) } ?: tr("A new version")) to checked
        is UpdateStatus.Ready ->
            tr("{0} is ready to install.", status.version?.let { tr("TableGrip {0}", it) } ?: tr("The update")) to tr("It installs when you quit, or restart now.")
        is UpdateStatus.Failed -> tr("Couldn't check for updates.") to status.reason
    }
}

private val CheckTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(I18n.locale).withZone(ZoneId.systemDefault())

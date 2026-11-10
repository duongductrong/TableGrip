package dev.tablegrip.app.settings

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.DownloadState
import dev.tablegrip.app.connections.DriverDownloads
import dev.tablegrip.app.connections.browse
import dev.tablegrip.app.connections.byteSize
import dev.tablegrip.app.connections.chooseFolder
import dev.tablegrip.app.connections.chooseJarFiles
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.drivers.DriverSource
import java.awt.Desktop
import java.nio.file.Path
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The drivers on this machine ([installed]) or the ones that could be ([installed] false), each with what can be done
 * to it: download, update, remove, or point it at other jars. [DriverId.Generic] is left out: its jars belong to each
 * profile, not to the library.
 */
@Composable
internal fun DriversPanel(app: AppState, installed: Boolean) {
    val downloads = app.drivers
    val revision by downloads.library.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // Bumped by "Refresh", for files changed on disk behind TableGrip's back.
    var refreshes by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        message = null
        scope.launch { runCatching { action() }.onFailure { message = it.message ?: it.toString() } }
    }

    val drivers = DriverId.entries.filter { it != DriverId.Generic && (downloads.resolve(it) is DriverResolution.Ready) == installed }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (installed) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Link(tr("Import from folder…"), onClick = {
                    val folder = chooseFolder(tr("Folder with driver jars")) ?: return@Link
                    run {
                        val imported = downloads.importFrom(folder)
                        message = if (imported.isEmpty()) tr("No jar in that folder matches a driver's checksums.") else tr("Imported {0}.", imported.joinToString { it.displayName })
                    }
                })
                Link(tr("Open drivers folder"), onClick = { open(downloads.library.folder) })
                Link(tr("Refresh"), onClick = {
                    refreshes++
                    downloads.refresh()
                })
            }
        }
        message?.let { Text(it, color = JewelTheme.globalColors.text.info, fontSize = 12.sp) }
        if (drivers.isEmpty()) {
            Text(
                if (installed) tr("No drivers yet. They're downloaded the first time a connection needs one.") else tr("Every driver is installed."),
                color = JewelTheme.globalColors.text.info,
            )
        }
        drivers.forEach { driver -> DriverRow(driver, app, downloads, revision to refreshes, ::run) }
    }
}

@Composable
private fun DriverRow(driver: DriverId, app: AppState, downloads: DriverDownloads, diskKey: Any, run: (suspend () -> Unit) -> Unit) {
    val library = downloads.library
    val resolution = downloads.resolve(driver)
    val locked = library.locked(driver)
    // Reads the disk, so only when the library changes.
    val versions = remember(driver, diskKey) { library.installedVersions(driver) }
    val diskSize = remember(driver, diskKey) { library.downloadedSize(driver) }
    val userJars = remember(driver, diskKey) { library.userJarPaths(driver) }
    val usedBy = DatabaseType.entries.filter { driver in it.drivers }.map { it.displayName }
    val connections = app.connections.profiles.count { it.effectiveDriver == driver }
    var confirmRemove by remember(driver) { mutableStateOf(false) }

    fun chooseJars() {
        val jars = chooseJarFiles(tr("Choose the {0} jar", driver.displayName)) ?: return
        run { downloads.useJars(driver, jars) }
    }

    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(driver.displayName, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(status(resolution, downloads.state(driver)), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        Text(
            listOfNotNull(
                tr("For {0}", usedBy.joinToString()),
                connections.takeIf { it > 0 }?.let { if (it == 1) tr("1 saved connection") else tr("{0} saved connections", it) },
                diskSize.takeIf { it > 0 }?.let { byteSize(it) + if (versions.size > 1) tr(" in {0} versions", versions.size) else "" },
                (locked?.license ?: driver.license)?.let { tr("License: {0}", it) },
            ).joinToString("  ·  "),
            color = JewelTheme.globalColors.text.info,
            fontSize = 12.sp,
        )
        if (resolution is DriverResolution.Ready && resolution.source == DriverSource.UserJars) {
            Text(userJars.joinToString("\n") { it.toString() }, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        if (resolution is DriverResolution.BringYourOwn && userJars.isNotEmpty()) {
            Text(
                tr("{0} can't be found any more.", userJars.joinToString { it.fileName.toString() }),
                color = JewelTheme.globalColors.text.warning,
                fontSize = 12.sp,
            )
        }
        (downloads.state(driver) as? DownloadState.Failed)?.let {
            Text(tr("Couldn't download: {0}", it.message), color = JewelTheme.globalColors.text.error, fontSize = 12.sp)
        }

        if (confirmRemove) {
            Text(
                tr("Delete {0} {1} ({2})?", driver.displayName, versions.joinToString(), byteSize(diskSize)) +
                    if (connections > 0) tr(" Connections using it will ask for it again the next time they connect.") else "",
                Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = {
                    confirmRemove = false
                    run { downloads.uninstall(driver) }
                }) { Text(tr("Delete")) }
                OutlinedButton(onClick = { confirmRemove = false }) { Text(tr("Cancel")) }
            }
            return@Column
        }

        if (downloads.state(driver) is DownloadState.Running) return@Column
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when (resolution) {
                is DriverResolution.Ready ->
                    when (resolution.source) {
                        DriverSource.BuiltIn, DriverSource.Classpath -> Unit
                        DriverSource.Downloaded -> {
                            resolution.update?.let { update -> Link(tr("Update to {0}", update.version), onClick = { downloads.download(driver) }) }
                            if (versions.size > 1 && resolution.update == null) {
                                Link(tr("Remove old versions"), onClick = { run { downloads.removeOldVersions(driver) } })
                            }
                            Link(tr("Remove"), onClick = { confirmRemove = true })
                            library.downloadFolder(driver)?.let { folder -> Link(tr("Show files"), onClick = { open(folder) }) }
                        }
                        DriverSource.UserJars -> {
                            Link(tr("Choose another jar…"), onClick = ::chooseJars)
                            Link(if (locked != null) tr("Use the download instead") else tr("Forget jar"), onClick = { run { downloads.forgetJars(driver) } })
                            if (versions.isNotEmpty()) Link(tr("Remove download"), onClick = { confirmRemove = true })
                        }
                    }
                is DriverResolution.Download -> {
                    Link(tr("Download ({0})", byteSize(resolution.locked.size)), onClick = { downloads.download(driver) })
                    Link(tr("License"), onClick = { browse(resolution.locked.licenseUrl) })
                    Link(tr("Choose jar…"), onClick = ::chooseJars)
                }
                is DriverResolution.BringYourOwn -> {
                    Link(tr("Choose jar…"), onClick = ::chooseJars)
                    driver.vendorUrl?.let { url -> Link(tr("Vendor download page"), onClick = { browse(url) }) }
                    if (userJars.isNotEmpty()) Link(tr("Forget jar"), onClick = { run { downloads.forgetJars(driver) } })
                }
            }
        }
    }
}

private fun status(resolution: DriverResolution, state: DownloadState?): String {
    if (state is DownloadState.Running) {
        val percent = if (state.total == 0L) 0 else state.done * 100 / state.total
        return tr("Downloading… {0}%", percent)
    }
    return when (resolution) {
        is DriverResolution.Ready ->
            when (resolution.source) {
                DriverSource.BuiltIn -> tr("Built in")
                DriverSource.Classpath -> tr("On the classpath")
                DriverSource.UserJars -> tr("Your jar")
                DriverSource.Downloaded -> resolution.version + (resolution.update?.let { tr("  ({0} available)", it.version) } ?: "")
            }
        is DriverResolution.Download -> tr("Not downloaded")
        is DriverResolution.BringYourOwn -> tr("Needs your jar")
    }
}

private fun open(folder: Path) {
    runCatching { Desktop.getDesktop().open(folder.toFile()) }
}

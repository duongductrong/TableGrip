package dev.datagrok.app.settings

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
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.DownloadState
import dev.datagrok.app.connections.DriverDownloads
import dev.datagrok.app.connections.browse
import dev.datagrok.app.connections.byteSize
import dev.datagrok.app.connections.chooseFolder
import dev.datagrok.app.connections.chooseJarFiles
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.drivers.DriverResolution
import dev.datagrok.core.drivers.DriverSource
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
    // Bumped by "Refresh", for files changed on disk behind Datagrok's back.
    var refreshes by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        message = null
        scope.launch { runCatching { action() }.onFailure { message = it.message ?: it.toString() } }
    }

    val drivers = DriverId.entries.filter { it != DriverId.Generic && (downloads.resolve(it) is DriverResolution.Ready) == installed }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (installed) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Link("Import from folder…", onClick = {
                    val folder = chooseFolder("Folder with driver jars") ?: return@Link
                    run {
                        val imported = downloads.importFrom(folder)
                        message = if (imported.isEmpty()) "No jar in that folder matches a driver's checksums." else "Imported ${imported.joinToString { it.displayName }}."
                    }
                })
                Link("Open drivers folder", onClick = { open(downloads.library.folder) })
                Link("Refresh", onClick = {
                    refreshes++
                    downloads.refresh()
                })
            }
        }
        message?.let { Text(it, color = JewelTheme.globalColors.text.info, fontSize = 12.sp) }
        if (drivers.isEmpty()) {
            Text(
                if (installed) "No drivers yet. They're downloaded the first time a connection needs one." else "Every driver is installed.",
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
        val jars = chooseJarFiles("Choose the ${driver.displayName} jar") ?: return
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
                "For ${usedBy.joinToString()}",
                connections.takeIf { it > 0 }?.let { "$it saved connection${if (it == 1) "" else "s"}" },
                diskSize.takeIf { it > 0 }?.let { byteSize(it) + if (versions.size > 1) " in ${versions.size} versions" else "" },
                (locked?.license ?: driver.license)?.let { "License: $it" },
            ).joinToString("  ·  "),
            color = JewelTheme.globalColors.text.info,
            fontSize = 12.sp,
        )
        if (resolution is DriverResolution.Ready && resolution.source == DriverSource.UserJars) {
            Text(userJars.joinToString("\n") { it.toString() }, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }
        if (resolution is DriverResolution.BringYourOwn && userJars.isNotEmpty()) {
            Text(
                "${userJars.joinToString { it.fileName.toString() }} can't be found any more.",
                color = JewelTheme.globalColors.text.warning,
                fontSize = 12.sp,
            )
        }
        (downloads.state(driver) as? DownloadState.Failed)?.let {
            Text("Couldn't download: ${it.message}", color = JewelTheme.globalColors.text.error, fontSize = 12.sp)
        }

        if (confirmRemove) {
            Text(
                "Delete ${driver.displayName} ${versions.joinToString()} (${byteSize(diskSize)})?" +
                    if (connections > 0) " Connections using it will ask for it again the next time they connect." else "",
                Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = {
                    confirmRemove = false
                    run { downloads.uninstall(driver) }
                }) { Text("Delete") }
                OutlinedButton(onClick = { confirmRemove = false }) { Text("Cancel") }
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
                            resolution.update?.let { update -> Link("Update to ${update.version}", onClick = { downloads.download(driver) }) }
                            if (versions.size > 1 && resolution.update == null) {
                                Link("Remove old versions", onClick = { run { downloads.removeOldVersions(driver) } })
                            }
                            Link("Remove", onClick = { confirmRemove = true })
                            library.downloadFolder(driver)?.let { folder -> Link("Show files", onClick = { open(folder) }) }
                        }
                        DriverSource.UserJars -> {
                            Link("Choose another jar…", onClick = ::chooseJars)
                            Link(if (locked != null) "Use the download instead" else "Forget jar", onClick = { run { downloads.forgetJars(driver) } })
                            if (versions.isNotEmpty()) Link("Remove download", onClick = { confirmRemove = true })
                        }
                    }
                is DriverResolution.Download -> {
                    Link("Download (${byteSize(resolution.locked.size)})", onClick = { downloads.download(driver) })
                    Link("License", onClick = { browse(resolution.locked.licenseUrl) })
                    Link("Choose jar…", onClick = ::chooseJars)
                }
                is DriverResolution.BringYourOwn -> {
                    Link("Choose jar…", onClick = ::chooseJars)
                    driver.vendorUrl?.let { url -> Link("Vendor download page", onClick = { browse(url) }) }
                    if (userJars.isNotEmpty()) Link("Forget jar", onClick = { run { downloads.forgetJars(driver) } })
                }
            }
        }
    }
}

private fun status(resolution: DriverResolution, state: DownloadState?): String {
    if (state is DownloadState.Running) {
        val percent = if (state.total == 0L) 0 else state.done * 100 / state.total
        return "Downloading… $percent%"
    }
    return when (resolution) {
        is DriverResolution.Ready ->
            when (resolution.source) {
                DriverSource.BuiltIn -> "Built in"
                DriverSource.Classpath -> "On the classpath"
                DriverSource.UserJars -> "Your jar"
                DriverSource.Downloaded -> resolution.version + (resolution.update?.let { "  (${it.version} available)" } ?: "")
            }
        is DriverResolution.Download -> "Not downloaded"
        is DriverResolution.BringYourOwn -> "Needs your jar"
    }
}

private fun open(folder: Path) {
    runCatching { Desktop.getDesktop().open(folder.toFile()) }
}

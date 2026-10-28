package dev.datagrok.app.connections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.drivers.DriverResolution
import dev.datagrok.core.drivers.DriverSource
import dev.datagrok.core.drivers.LicenseTier
import dev.datagrok.core.drivers.LockedDriver
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineInformationBanner
import org.jetbrains.jewel.ui.component.InlineSuccessBanner
import org.jetbrains.jewel.ui.component.InlineWarningBanner

/**
 * What [driver] needs before a connection can use it, with the action that gets it there: download it, choose its jar,
 * or nothing. [onSwitch] offers another driver for the same database (MariaDB Connector/J instead of MySQL's GPL one).
 */
@Composable
internal fun DriverBanner(
    driver: DriverId,
    downloads: DriverDownloads,
    modifier: Modifier = Modifier,
    onSwitch: ((DriverId) -> Unit)? = null,
    onMessage: (String) -> Unit = {},
    /** The jars of a [DriverId.Generic] profile, which belong to the profile rather than the library. */
    profileJars: List<String> = emptyList(),
    onProfileJars: (List<String>) -> Unit = {},
) {
    if (driver == DriverId.Generic) {
        key(profileJars) { ProfileJarsBanner(profileJars, downloads, modifier, onProfileJars, onMessage) }
        return
    }
    // Jewel's banners remember their link actions from the first composition. Without a fresh composition per driver
    // and state, switching drivers would keep the old links, e.g. "Download" fetching the previous driver.
    val resolution = downloads.resolve(driver)
    val shape = listOf(driver, downloads.state(driver)?.javaClass, resolution.javaClass, (resolution as? DriverResolution.Ready)?.source, (resolution as? DriverResolution.Ready)?.update?.version)
    key(shape) { DriverBannerContent(driver, downloads, modifier, onSwitch, onMessage) }
}

@Composable
private fun DriverBannerContent(
    driver: DriverId,
    downloads: DriverDownloads,
    modifier: Modifier,
    onSwitch: ((DriverId) -> Unit)?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) {
        scope.launch { runCatching { action() }.onFailure { onMessage(it.message ?: it.toString()) } }
    }
    fun chooseJars() {
        val jars = chooseJarFiles("Choose the ${driver.displayName} jar") ?: return
        run { downloads.useJars(driver, jars) }
    }
    fun importFolder() {
        val folder = chooseFolder("Folder with driver jars") ?: return
        run {
            val imported = downloads.importFrom(folder)
            onMessage(if (driver in imported) "Imported ${driver.displayName}." else "No jar in that folder matches ${driver.displayName}'s checksums.")
        }
    }

    when (val state = downloads.state(driver)) {
        is DownloadState.Running -> {
            val percent = if (state.total == 0L) 0 else state.done * 100 / state.total
            InlineInformationBanner("Downloading ${driver.displayName}… $percent% of ${size(state.total)}", modifier)
            return
        }
        is DownloadState.Failed ->
            if (downloads.resolve(driver) !is DriverResolution.Ready) {
                InlineErrorBanner(
                    "Couldn't download ${driver.displayName}: ${state.message}",
                    modifier,
                    linkActions = {
                        action("Retry") { downloads.download(driver) }
                        action("Import from folder…") { importFolder() }
                    },
                )
                return
            }
        null -> Unit
    }

    when (val resolution = downloads.resolve(driver)) {
        is DriverResolution.Ready ->
            when {
                resolution.update != null ->
                    InlineInformationBanner(
                        "${driver.displayName} ${resolution.version} is installed; ${resolution.update!!.version} is available.",
                        modifier,
                        linkActions = { action("Update") { downloads.download(driver) } },
                    )
                resolution.source == DriverSource.UserJars ->
                    InlineSuccessBanner(
                        "Using your ${driver.displayName} jar.",
                        modifier,
                        linkActions = {
                            action("Choose another…") { chooseJars() }
                            if (driver.catalogId != null) action("Use the download instead") { run { downloads.forgetJars(driver) } }
                        },
                    )
                else -> InlineSuccessBanner("${driver.displayName}${resolution.version?.let { " $it" }.orEmpty()} is ready.", modifier)
            }
        is DriverResolution.Download -> {
            val locked = resolution.locked
            InlineInformationBanner(
                "${locked.name} ${locked.version} needs to be downloaded from ${repositoryName(downloads)} (${size(locked.size)}). ${licenseNote(locked)}",
                modifier,
                // A banner shows at most three links. An LGPL driver's license and source must stay in view, so
                // importing from a folder moves to the error banner a failed download shows.
                linkActions = {
                    action("Download") { downloads.download(driver) }
                    if (locked.tier == LicenseTier.WeakCopyleft) {
                        action("License") { browse(locked.licenseUrl) }
                        action("Source") { browse(locked.sourceUrl) }
                    } else {
                        action("Import from folder…") { importFolder() }
                    }
                },
            )
        }
        is DriverResolution.BringYourOwn ->
            InlineWarningBanner(
                "Datagrok doesn't distribute ${driver.displayName}" + (driver.license?.let { " ($it)" } ?: "") +
                    ". Download it from its vendor under their terms, then choose the jar.",
                modifier,
                linkActions = {
                    action("Choose jar…") { chooseJars() }
                    driver.vendorUrl?.let { url -> action("Vendor download page") { browse(url) } }
                    if (driver == DriverId.MySQL && onSwitch != null) action("Use MariaDB Connector/J") { onSwitch(DriverId.MariaDB) }
                },
            )
    }
}

private fun repositoryName(downloads: DriverDownloads): String =
    if (downloads.library.repository == DriverLibrary.MavenCentral) "Maven Central" else "your Maven repository"

private fun licenseNote(driver: LockedDriver): String =
    when (driver.tier) {
        LicenseTier.Permissive -> "License: ${driver.license}."
        LicenseTier.WeakCopyleft -> "License: ${driver.license}; it's used unmodified and you can replace it with your own build."
    }

private fun size(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "${(bytes + 999) / 1_000} KB"
    }

private fun browse(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

private fun chooseJarFiles(title: String): List<Path>? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> name.endsWith(".jar") }
    dialog.isVisible = true
    return dialog.files.map { it.toPath() }.takeIf { it.isNotEmpty() }
}

private fun chooseFolder(title: String): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/** A [DriverId.Generic] profile's driver: the jars the user chose for it, which Datagrok loads but never copies. */
@Composable
private fun ProfileJarsBanner(jars: List<String>, downloads: DriverDownloads, modifier: Modifier, onJars: (List<String>) -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    fun choose() {
        val chosen = chooseJarFiles("Choose the JDBC driver jars") ?: return
        scope.launch {
            runCatching { downloads.library.inspectJars(chosen) }
                .onSuccess { onJars(chosen.map { it.toAbsolutePath().toString() }) }
                .onFailure { onMessage(it.message ?: it.toString()) }
        }
    }
    val present = jars.isNotEmpty() && jars.all { java.io.File(it).isFile }
    if (present) {
        InlineSuccessBanner(
            "Using the driver in ${jars.joinToString { java.io.File(it).name }}.",
            modifier,
            linkActions = { action("Choose other jars…") { choose() } },
        )
    } else {
        InlineWarningBanner(
            (if (jars.isEmpty()) "" else "${jars.joinToString { java.io.File(it).name }} can't be found. ") +
                "Choose the JDBC driver's jar (and any jars it needs). Datagrok loads the driver from them; it doesn't copy or download anything.",
            modifier,
            linkActions = { action("Choose jars…") { choose() } },
        )
    }
}

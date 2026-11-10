package dev.tablegrip.app.connections

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.drivers.DriverSource
import dev.tablegrip.core.drivers.LicenseTier
import dev.tablegrip.core.drivers.LockedDriver
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
        val jars = chooseJarFiles(tr("Choose the {0} jar", driver.displayName)) ?: return
        run { downloads.useJars(driver, jars) }
    }
    fun importFolder() {
        val folder = chooseFolder(tr("Folder with driver jars")) ?: return
        run {
            val imported = downloads.importFrom(folder)
            onMessage(if (driver in imported) tr("Imported {0}.", driver.displayName) else tr("No jar in that folder matches {0}'s checksums.", driver.displayName))
        }
    }

    when (val state = downloads.state(driver)) {
        is DownloadState.Running -> {
            val percent = if (state.total == 0L) 0 else state.done * 100 / state.total
            InlineInformationBanner(tr("Downloading {0}… {1}% of {2}", driver.displayName, percent, byteSize(state.total)), modifier)
            return
        }
        is DownloadState.Failed ->
            if (downloads.resolve(driver) !is DriverResolution.Ready) {
                InlineErrorBanner(
                    tr("Couldn't download {0}: {1}", driver.displayName, state.message),
                    modifier,
                    linkActions = {
                        action(tr("Retry")) { downloads.download(driver) }
                        action(tr("Import from folder…")) { importFolder() }
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
                        tr("{0} {1} is installed; {2} is available.", driver.displayName, resolution.version, resolution.update!!.version),
                        modifier,
                        linkActions = { action(tr("Update")) { downloads.download(driver) } },
                    )
                resolution.source == DriverSource.BuiltIn -> InlineSuccessBanner(tr("Built into TableGrip: nothing to download."), modifier)
                resolution.source == DriverSource.UserJars ->
                    InlineSuccessBanner(
                        tr("Using your {0} jar.", driver.displayName),
                        modifier,
                        linkActions = {
                            action(tr("Choose another…")) { chooseJars() }
                            if (driver.catalogId != null) action(tr("Use the download instead")) { run { downloads.forgetJars(driver) } }
                        },
                    )
                else -> InlineSuccessBanner(tr("{0}{1} is ready.", driver.displayName, resolution.version?.let { " $it" }.orEmpty()), modifier)
            }
        is DriverResolution.Download -> {
            val locked = resolution.locked
            InlineInformationBanner(
                tr("{0} {1} needs to be downloaded from {2} ({3}). {4}", locked.name, locked.version, repositoryName(downloads), byteSize(locked.size), licenseNote(locked)),
                modifier,
                // A banner shows at most three links. An LGPL driver's license and source must stay in view, so
                // importing from a folder moves to the error banner a failed download shows.
                linkActions = {
                    action(tr("Download")) { downloads.download(driver) }
                    if (locked.tier == LicenseTier.WeakCopyleft) {
                        action(tr("License")) { browse(locked.licenseUrl) }
                        action(tr("Source")) { browse(locked.sourceUrl) }
                    } else {
                        action(tr("Import from folder…")) { importFolder() }
                    }
                },
            )
        }
        is DriverResolution.BringYourOwn ->
            InlineWarningBanner(
                tr("TableGrip doesn't distribute {0}", driver.displayName) + (driver.license?.let { " ($it)" } ?: "") +
                    tr(". Download it from its vendor under their terms, then choose the jar."),
                modifier,
                linkActions = {
                    action(tr("Choose jar…")) { chooseJars() }
                    driver.vendorUrl?.let { url -> action(tr("Vendor download page")) { browse(url) } }
                    if (driver == DriverId.MySQL && onSwitch != null) action(tr("Use MariaDB Connector/J")) { onSwitch(DriverId.MariaDB) }
                },
            )
    }
}

private fun repositoryName(downloads: DriverDownloads): String =
    if (downloads.library.repository == DriverLibrary.MavenCentral) tr("Maven Central") else tr("your Maven repository")

private fun licenseNote(driver: LockedDriver): String =
    when (driver.tier) {
        LicenseTier.Permissive -> tr("License: {0}.", driver.license)
        LicenseTier.WeakCopyleft -> tr("License: {0}; it's used unmodified and you can replace it with your own build.", driver.license)
    }

internal fun byteSize(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "${(bytes + 999) / 1_000} KB"
    }

internal fun browse(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

internal fun chooseJarFiles(title: String): List<Path>? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> name.endsWith(".jar") }
    dialog.isVisible = true
    return dialog.files.map { it.toPath() }.takeIf { it.isNotEmpty() }
}

internal fun chooseFolder(title: String): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/** A [DriverId.Generic] profile's driver: the jars the user chose for it, which TableGrip loads but never copies. */
@Composable
private fun ProfileJarsBanner(jars: List<String>, downloads: DriverDownloads, modifier: Modifier, onJars: (List<String>) -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    fun choose() {
        val chosen = chooseJarFiles(tr("Choose the JDBC driver jars")) ?: return
        scope.launch {
            runCatching { downloads.library.inspectJars(chosen) }
                .onSuccess { onJars(chosen.map { it.toAbsolutePath().toString() }) }
                .onFailure { onMessage(it.message ?: it.toString()) }
        }
    }
    val present = jars.isNotEmpty() && jars.all { java.io.File(it).isFile }
    if (present) {
        InlineSuccessBanner(
            tr("Using the driver in {0}.", jars.joinToString { java.io.File(it).name }),
            modifier,
            linkActions = { action(tr("Choose other jars…")) { choose() } },
        )
    } else {
        InlineWarningBanner(
            (if (jars.isEmpty()) "" else tr("{0} can't be found. ", jars.joinToString { java.io.File(it).name })) +
                tr("Choose the JDBC driver's jar (and any jars it needs). TableGrip loads the driver from them; it doesn't copy or download anything."),
            modifier,
            linkActions = { action(tr("Choose jars…")) { choose() } },
        )
    }
}

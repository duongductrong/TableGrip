package dev.tablegrip.app.tools

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.browse
import dev.tablegrip.app.connections.byteSize
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.core.tools.InstalledTool
import dev.tablegrip.core.tools.Tool
import dev.tablegrip.core.tools.ToolCatalog
import dev.tablegrip.core.tools.ToolFamily
import dev.tablegrip.core.tools.ToolPackage
import dev.tablegrip.core.tools.ToolSource
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Settings → Tools: one card per family with where each tool comes from, and the download TableGrip can make. */
@Composable
internal fun ToolsPanel(app: AppState, family: ToolFamily) {
    val tools = app.tools
    val revision = tools.revision
    val found by produceState<Map<Tool, List<InstalledTool>>?>(null, family, revision, app.settings.toolPaths) {
        value = Tool.entries.filter { it.family == family }.associateWith { tools.find(it) }
    }
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pkg = ToolCatalog.packageFor(family)
        if (pkg != null) PackageCard(app, pkg)
        Text(ToolCatalog.installHint(family), color = info, fontSize = 12.sp)
        val all = found
        if (all == null) {
            Text(tr("Looking for {0} tools…", family.displayName), color = info)
            return@Column
        }
        for ((tool, copies) in all) {
            // MariaDB's names are only worth a row when they exist; mysqldump covers both servers.
            if (copies.isEmpty() && (tool == Tool.MariaDbDump || tool == Tool.MariaDb)) continue
            ToolRow(app, tool, copies)
        }
    }
}

@Composable
private fun ToolRow(app: AppState, tool: Tool, copies: List<InstalledTool>) {
    val info = JewelTheme.globalColors.text.info
    val used = copies.firstOrNull { it.source == ToolSource.Custom } ?: copies.filter { it.version != null }.maxByOrNull { it.major ?: 0 }
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tool.command, fontWeight = FontWeight.SemiBold)
            Text("  ${tool.purpose}", color = info, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(used?.let { "${it.version ?: "?"} · ${it.source.label}" } ?: tr("Not found"), color = if (used == null) JewelTheme.globalColors.text.warning else info, fontSize = 12.sp)
        }
        copies.forEach { copy ->
            val marker = if (copy == used) "▸ " else "   "
            Text("$marker${copy.path}  ${copy.version ?: tr("(doesn't run)")}", color = info, fontSize = 12.sp, maxLines = 1)
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link(tr("Choose…"), onClick = {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, tr("Choose {0}", tool.executable), java.awt.FileDialog.LOAD)
                dialog.isVisible = true
                dialog.file?.let { app.tools.choose(tool, Path.of(dialog.directory, it)) }
            })
            if (copies.any { it.source == ToolSource.Custom }) Link(tr("Find automatically"), onClick = { app.tools.forget(tool) })
            used?.let { copy -> Link(tr("Show in folder"), onClick = { reveal(copy.path.parent) }) }
        }
    }
}

@Composable
private fun PackageCard(app: AppState, pkg: ToolPackage) {
    val tools = app.tools
    val info = JewelTheme.globalColors.text.info
    val revision = tools.revision
    val installed by produceState(false, pkg, revision) { value = withContext(Dispatchers.IO) { pkg in tools.library.installedPackages() } }
    val size by produceState(0L, pkg, revision) { value = withContext(Dispatchers.IO) { tools.library.packageSize(pkg) } }
    var confirmRemove by remember(pkg) { mutableStateOf(false) }
    val progress = tools.progress[pkg.id]
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("{0} {1} client tools", pkg.family.displayName, pkg.version), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val status =
                when {
                    progress != null -> tr("Downloading… {0}%", if (progress.second > 0) progress.first * 100 / progress.second else 0)
                    installed -> tr("Downloaded · {0}", byteSize(size))
                    else -> tr("Not downloaded")
                }
            Text(status, color = info, fontSize = 12.sp)
        }
        Text(tr("{0} from {1}", pkg.tools.joinToString { it.command }, pkg.publisher), color = info, fontSize = 12.sp)
        tools.failures[pkg.id]?.let { Text(tr("Couldn't download: {0}", it), color = JewelTheme.globalColors.text.error, fontSize = 12.sp) }
        if (confirmRemove) {
            Text(tr("Delete the downloaded {0} {1} tools ({2})? Backups will use other copies or ask to download again.", pkg.family.displayName, pkg.version, byteSize(size)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = {
                    confirmRemove = false
                    tools.uninstall(pkg)
                }) { Text(tr("Delete")) }
                OutlinedButton(onClick = { confirmRemove = false }) { Text(tr("Cancel")) }
            }
            return@Column
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                progress != null -> Link(tr("Cancel download"), onClick = { tools.cancelDownload(pkg) })
                installed -> {
                    Link(tr("Remove"), onClick = { confirmRemove = true })
                    Link(tr("Show files"), onClick = { reveal(tools.library.folder.resolve(pkg.id)) })
                }
                else -> Link(tr("Download…"), onClick = { tools.requestDownload(pkg) })
            }
            Link(tr("Open tools folder"), onClick = {
                Files.createDirectories(tools.library.folder)
                reveal(tools.library.folder)
            })
        }
    }
}

/** Asks before anything is downloaded: what, why, from where, how big, under which license. */
@Composable
internal fun ToolDownloadDialog(app: AppState, request: ToolDownloadRequest) {
    val pkg = request.pkg
    AppDialog(onCloseRequest = { app.tools.pending = null }, size = DpSize(600.dp, 400.dp), title = tr("Download {0} Tools?", pkg.family.displayName), resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.Actions.Download, null)
                Text("${pkg.tools.joinToString { it.command }} ${pkg.version}", fontWeight = FontWeight.SemiBold)
            }
            request.reason?.let { Text(it) }
            Fact(tr("Source"), pkg.url)
            Fact(tr("Publisher"), pkg.publisher)
            Fact(tr("License"), pkg.license)
            Fact(tr("Download"), tr("{0}, checked against a pinned SHA-256", byteSize(pkg.downloadBytes)))
            Fact(tr("Kept"), tr("Only the {0} tools and their libraries (about 25 MB), in {1}", pkg.tools.size, app.tools.library.folder.resolve(pkg.id)))
            Text(
                tr("TableGrip downloads nothing without asking. Remove the tools any time in Settings → Tools."),
                color = JewelTheme.globalColors.text.info,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Link(tr("Install them myself"), onClick = {
                    app.tools.pending = null
                    browse(if (pkg.family == ToolFamily.PostgreSQL) "https://www.postgresql.org/download/" else "https://dev.mysql.com/downloads/")
                })
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { app.tools.pending = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = app.tools::confirmDownload) { Text(tr("Download")) }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, Modifier.width(90.dp), color = JewelTheme.globalColors.text.info)
        Text(value)
    }
}

private fun reveal(path: Path) {
    runCatching { Desktop.getDesktop().open(path.toFile()) }
}

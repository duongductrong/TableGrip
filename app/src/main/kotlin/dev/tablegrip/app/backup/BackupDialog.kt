package dev.tablegrip.app.backup

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.byteSize
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.backup.BackupContent
import dev.tablegrip.core.backup.BackupFormat
import dev.tablegrip.core.backup.BackupSupport
import dev.tablegrip.core.tools.ToolFamily
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun BackupDialog(app: AppState, state: BackupState) {
    val title = if (state.restore) tr("Restore into {0}", state.config.name) else tr("Back Up {0}", state.config.name)
    AppDialog(onCloseRequest = { app.backup = null }, size = DpSize(680.dp, 600.dp), title = title) { BackupContent(app, state) }
}

/** The dialog's content, apart from its window so it can be rendered on its own. */
@Composable
internal fun BackupContent(app: AppState, state: BackupState) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.config.name} · ${state.config.type.displayName}", fontWeight = FontWeight.SemiBold)
            EnvironmentChip(state.config)
            if (state.config.readOnly) Text(tr("read-only"), color = JewelTheme.globalColors.text.info)
        }
        val support = state.support
        if (support is BackupSupport.NotYet) {
            Text(support.reason)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OutlinedButton(onClick = { app.backup = null }) { Text(tr("Close")) } }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolStatus(state)
            if (state.restore) RestoreForm(state) else BackupForm(state)
            state.preview()?.let { command -> CommandPreview(command) }
        }
        Progress(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            val blocker = state.blocker
            if (blocker != null && !state.running) Text(blocker, Modifier.weight(1f), color = JewelTheme.globalColors.text.info, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            if (state.running) {
                OutlinedButton(onClick = { app.backup = null }) { Text(tr("Hide")) }
                OutlinedButton(onClick = state::cancel) { Text(tr("Cancel {0}", if (state.restore) tr("Restore") else tr("Backup"))) }
            } else {
                OutlinedButton(onClick = { app.backup = null }) { Text(tr("Close")) }
                DefaultButton(onClick = state::start, enabled = blocker == null) { Text(if (state.restore) tr("Restore") else tr("Back Up")) }
            }
        }
    }
}

@Composable
private fun ToolStatus(state: BackupState) {
    val info = JewelTheme.globalColors.text.info
    when (val check = state.check) {
        ToolCheck.Checking -> Text(tr("Looking for {0} tools…", state.family?.displayName ?: ""), color = info)
        ToolCheck.NotNeeded -> Text(tr("{0} backs itself up: no tools needed.", state.config.type.displayName), color = info)
        is ToolCheck.Ready -> Text(tr("Using {0} {1} · {2}", check.tool.tool.command, check.tool.version, check.tool.path), color = info, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        is ToolCheck.Missing -> {
            Column(
                Modifier.fillMaxWidth().background(JewelTheme.globalColors.text.warning.copy(alpha = 0.10f), RoundedCornerShape(6.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AllIconsKeys.General.Warning, null)
                    Text(check.message)
                }
                state.family?.let { family -> Text(dev.tablegrip.core.tools.ToolCatalog.installHint(family), color = info, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    check.download?.let { pkg -> Link(tr("Download {0} {1} tools ({2})…", pkg.family.displayName, pkg.version, byteSize(pkg.downloadBytes)), onClick = state::download) }
                    Link(tr("Choose in Settings → Tools"), onClick = state::openToolSettings)
                    Link(tr("Check again"), onClick = state::checkTools)
                }
            }
        }
    }
}

@Composable
private fun BackupForm(state: BackupState) {
    val info = JewelTheme.globalColors.text.info
    if (state.support !is BackupSupport.Native) {
        Field(tr("Database")) {
            TextField(state.database, Modifier.width(260.dp))
            if (state.databases.isNotEmpty()) {
                ListComboBox(
                    items = state.databases,
                    selectedIndex = state.databases.indexOf(state.database.text.toString()).coerceAtLeast(0),
                    onSelectedItemChange = { i -> state.database.edit { replace(0, length, state.databases[i]) } },
                    modifier = Modifier.width(180.dp),
                )
            }
        }
    }
    if (state.formats.size > 1) {
        Field(tr("Format")) {
            ListComboBox(
                items = state.formats.map { it.label },
                selectedIndex = state.formats.indexOf(state.format),
                onSelectedItemChange = { state.formatChanged(state.formats[it]) },
                modifier = Modifier.width(320.dp),
            )
        }
    }
    Field(if (state.format.isFolder) tr("Folder") else tr("Save to")) {
        Text(state.target?.toString() ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Link(tr("Change…"), onClick = {
            val current = state.target
            val chosen = if (state.format.isFolder) chooseNewFolder(current) else chooseSaveFile(current)
            chosen?.let { state.target = it }
        })
    }
    if (state.family != null) {
        Field(tr("Contents")) {
            ListComboBox(
                items = BackupContent.entries.map { it.label },
                selectedIndex = BackupContent.entries.indexOf(state.content),
                onSelectedItemChange = { state.content = BackupContent.entries[it] },
                modifier = Modifier.width(200.dp),
            )
        }
        Field(tr("Only")) {
            TextField(state.only, Modifier.width(360.dp), placeholder = { Text(if (state.family == ToolFamily.PostgreSQL) tr("schemas or schema.table, comma-separated") else tr("tables, comma-separated")) })
        }
        Text(tr("Leave empty to back up everything."), color = info, fontSize = 12.sp, modifier = Modifier.padding(start = 100.dp))
    }
    if (state.family == ToolFamily.PostgreSQL) {
        Row(Modifier.padding(start = 100.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckboxRow(tr("No owners"), state.noOwner, onCheckedChange = { state.noOwner = it })
            CheckboxRow(tr("No privileges"), state.noPrivileges, onCheckedChange = { state.noPrivileges = it })
            if (state.format == BackupFormat.PgPlain) CheckboxRow(tr("DROP before CREATE"), state.clean, onCheckedChange = { state.clean = it })
        }
    }
    if (state.family == ToolFamily.MySQL) {
        Text(tr("Consistent snapshot (--single-transaction) with routines, triggers and events."), color = info, fontSize = 12.sp, modifier = Modifier.padding(start = 100.dp))
    }
}

@Composable
private fun RestoreForm(state: BackupState) {
    val info = JewelTheme.globalColors.text.info
    Field(tr("Backup")) {
        Text(state.source?.toString() ?: tr("None chosen"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (state.source == null) info else JewelTheme.globalColors.text.normal)
        Link(tr("Choose file…"), onClick = { chooseOpenFile()?.let(state::chooseSource) })
        if (state.family == ToolFamily.PostgreSQL) Link(tr("Folder…"), onClick = { chooseFolder()?.let(state::chooseSource) })
    }
    if (state.source != null) Text(tr("Format: {0}", state.format.label), color = info, fontSize = 12.sp, modifier = Modifier.padding(start = 100.dp))
    Field(tr("Into")) {
        TextField(state.targetDatabase, Modifier.width(280.dp))
        CheckboxRow(tr("Create it"), state.createDatabase, onCheckedChange = { state.createDatabase = it })
    }
    Text(
        if (state.createDatabase) tr("A new database, so nothing existing is overwritten.") else tr("Restores into an existing database: objects that already exist will clash unless the backup drops them."),
        color = if (state.createDatabase) info else JewelTheme.globalColors.text.warning,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 100.dp),
    )
    if (state.family == ToolFamily.PostgreSQL && state.format != BackupFormat.PgPlain) {
        Row(Modifier.padding(start = 100.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CheckboxRow(tr("No owners"), state.noOwner, onCheckedChange = { state.noOwner = it })
            CheckboxRow(tr("No privileges"), state.noPrivileges, onCheckedChange = { state.noPrivileges = it })
            if (!state.createDatabase) CheckboxRow(tr("Drop existing objects first"), state.clean, onCheckedChange = { state.clean = it })
        }
    }
    if (state.listing) Text(tr("Reading the backup's contents…"), color = info)
    if (state.entries.isNotEmpty()) {
        Text(tr("Contents ({0} of {1} selected)", state.entries.size - state.excluded.size, state.entries.size), fontWeight = FontWeight.SemiBold)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 180.dp).border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp)).verticalScroll(rememberScrollState()).padding(6.dp),
        ) {
            state.entries.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Checkbox(entry.line !in state.excluded, onCheckedChange = { state.toggle(entry) })
                    Text(entry.label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
    if (state.production) {
        Column(
            Modifier.fillMaxWidth().background(JewelTheme.globalColors.text.error.copy(alpha = 0.08f), RoundedCornerShape(6.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("{0} is a Production connection. Type the target database's name to confirm.", state.config.name), fontWeight = FontWeight.SemiBold)
            TextField(state.confirmName, Modifier.width(280.dp), placeholder = { Text(state.targetDatabase.text.toString()) })
        }
    }
}

@Composable
private fun CommandPreview(command: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Equivalent command"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Link(tr("Copy"), onClick = { copyToClipboard(command) })
        }
        Box(Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp)).padding(8.dp)) {
            Text(command, style = JewelTheme.editorTextStyle, fontSize = 12.sp)
        }
        Text(tr("The password is passed privately (environment or a temporary owner-only file), never on the command line."), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
    }
}

@Composable
private fun Progress(state: BackupState) {
    val info = JewelTheme.globalColors.text.info
    when {
        state.running -> Text(tr("Working… {0}", state.lastLine), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.error != null -> Text(state.error!!, color = JewelTheme.globalColors.text.error, maxLines = 6)
        state.done != null -> Text("✓ ${state.done}", maxLines = 2)
    }
    state.logFile?.takeIf { !state.running }?.let { log ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link(tr("Open log"), onClick = { open(log) })
            if (!state.restore && state.done != null) state.target?.let { target -> Link(tr("Show backup"), onClick = { open(target.parent) }) }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(92.dp))
        content()
    }
}

private fun open(path: Path) {
    runCatching { Desktop.getDesktop().open(path.toFile()) }
}

private fun chooseSaveFile(current: Path?): Path? {
    val dialog = FileDialog(null as Frame?, tr("Save Backup"), FileDialog.SAVE)
    current?.let {
        dialog.directory = it.parent.toString()
        dialog.file = it.fileName.toString()
    }
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

private fun chooseOpenFile(): Path? {
    val dialog = FileDialog(null as Frame?, tr("Choose Backup"), FileDialog.LOAD)
    dialog.directory = Path.of(System.getProperty("user.home"), "Backups").toString()
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

private fun chooseFolder(): Path? {
    val chooser = JFileChooser(Path.of(System.getProperty("user.home"), "Backups").toFile()).apply {
        dialogTitle = tr("Choose Backup Folder")
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

/** A folder that doesn't exist yet: the parent is chosen, the name kept. */
private fun chooseNewFolder(current: Path?): Path? {
    val chooser = JFileChooser(current?.parent?.toFile()).apply {
        dialogTitle = tr("Folder to Save the Backup In")
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.toPath().resolve(current?.fileName?.toString() ?: "backup")
}

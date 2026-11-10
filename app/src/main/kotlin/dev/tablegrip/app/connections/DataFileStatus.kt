package dev.tablegrip.app.connections

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.UnsavedDataFiles
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.datafile.DataFileConnection
import dev.tablegrip.core.datafile.DataFileState
import java.awt.Desktop
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * Asks what to do with a data file's changes when they can't simply be saved: the file changed on disk, saving would
 * lose formulas, or writing failed. Shown above the tabs of that file's connection; nothing while all is saved.
 */
@Composable
internal fun DataFileBanner(app: AppState, connectionId: String) {
    val connection = app.databases.dataFile(connectionId) ?: return
    val state by connection.sync.state.collectAsState()
    val name = connection.sync.file.fileName.toString()
    fun act(block: suspend () -> Unit) {
        app.scope.launch { block() }
    }
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    // Jewel's banners keep their link actions from the first composition, so each state gets a fresh one.
    key(state) {
        when (val current = state) {
            is DataFileState.Held ->
                InlineWarningBanner(
                    current.message,
                    modifier,
                    linkActions = {
                        when (current.reason) {
                            DataFileState.Held.Reason.ChangedOnDisk -> {
                                action(tr("Keep mine, overwrite {0}", name)) { act { connection.sync.save(force = true) } }
                                action(tr("Discard mine, reload {0}", name)) { act { connection.sync.reload() } }
                            }
                            DataFileState.Held.Reason.LosesContent -> {
                                action(tr("Save anyway")) { act { connection.sync.save(force = true) } }
                                action(tr("Discard changes")) { act { connection.sync.reload() } }
                            }
                        }
                    },
                )
            is DataFileState.Failed ->
                InlineErrorBanner(
                    tr("{0}. Your changes are kept and saved as soon as writing works again.", current.message),
                    modifier,
                    linkActions = {
                        action(tr("Try again")) { act { connection.sync.save() } }
                        action(tr("Reload {0}", name)) { act { connection.sync.reload() } }
                    },
                )
            else -> Unit
        }
    }
}

/** A word next to a data file's connection in the explorer when its file isn't up to date with it. */
@Composable
internal fun DataFileBadge(connection: DataFileConnection) {
    val state by connection.sync.state.collectAsState()
    val (label, tooltip, error) =
        when (val current = state) {
            DataFileState.Saving -> Triple(tr("saving…"), tr("Writing {0}", connection.sync.file.fileName), false)
            is DataFileState.Held -> Triple(tr("unsaved"), current.message, false)
            is DataFileState.Failed -> Triple(tr("not saved"), current.message, true)
            is DataFileState.InSync -> {
                val notes = connection.sync.notes + listOfNotNull(
                    connection.sync.readOnlyReason?.let { tr("Read-only: {0}", it) },
                    current.sessionOnly.takeIf { it.isNotEmpty() }?.let { tr("Only in this session (the file holds one table): {0}", it.joinToString()) },
                )
                if (notes.isEmpty()) return
                Triple(tr("note"), notes.joinToString("\n"), false)
            }
        }
    val colors = JewelTheme.globalColors.text
    Tooltip({ Text(tooltip) }) {
        Text(label, color = if (error) colors.error else if (state is DataFileState.InSync) colors.info else colors.warning, maxLines = 1, softWrap = false)
    }
}

/** Explorer menu items for a data file's connection. */
internal fun dataFileMenu(app: AppState, config: ConnectionConfig): List<ContextMenuItem> {
    val connection = app.databases.dataFile(config.id)
    val file = runCatching { dev.tablegrip.core.datafile.DataFileStore.pathOf(config.database).toFile() }.getOrNull()
    return listOfNotNull(
        file?.let { ContextMenuItem(tr("Open in Separate Window")) { app.files.open(listOf(it)) } },
        connection?.takeIf { it.sync.readOnlyReason == null }?.let { ContextMenuItem(tr("Save to File Now")) { app.scope.launch { it.sync.save() } } },
        connection?.let { ContextMenuItem(tr("Reload from File")) { app.scope.launch { it.sync.reload() } } },
        file?.takeIf { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) }?.let {
            ContextMenuItem(tr("Reveal in File Manager")) { runCatching { Desktop.getDesktop().browseFileDirectory(it) } }
        },
    )
}

/**
 * Before connections close (quitting, disconnecting) while a data file holds changes it couldn't write: write them anyway,
 * drop them, or stay.
 */
@Composable
internal fun UnsavedDataFilesDialog(app: AppState, request: UnsavedDataFiles) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun cancel() {
        app.unsavedDataFiles = null
    }
    AppDialog(onCloseRequest = ::cancel, size = DpSize(520.dp, 260.dp), title = tr("Unsaved Data Files"), resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("These files don't have your latest changes yet. Closing their connections discards those changes."))
            request.connections.forEach { connection ->
                val state by connection.sync.state.collectAsState()
                val detail = (state as? DataFileState.Held)?.message ?: (state as? DataFileState.Failed)?.message ?: tr("Saved.")
                Text("${connection.sync.file.fileName}: $detail", color = JewelTheme.globalColors.text.info, maxLines = 2)
            }
            error?.let { Text(it, color = JewelTheme.globalColors.text.error, maxLines = 3) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = ::cancel, enabled = !busy) { Text(tr("Cancel")) }
                OutlinedButton(onClick = {
                    app.unsavedDataFiles = null
                    request.then()
                }, enabled = !busy) { Text(tr("Discard Changes")) }
                DefaultButton(onClick = {
                    busy = true
                    app.scope.launch {
                        request.connections.forEach { it.sync.save(force = true) }
                        busy = false
                        val failed = request.connections.mapNotNull { (it.sync.state.value as? DataFileState.Failed)?.message }
                        if (failed.isEmpty()) {
                            app.unsavedDataFiles = null
                            request.then()
                        } else {
                            error = failed.joinToString("\n")
                        }
                    }
                }, enabled = !busy) { Text(tr("Save and Continue")) }
            }
        }
    }
}

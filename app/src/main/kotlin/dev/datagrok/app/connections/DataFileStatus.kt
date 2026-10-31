package dev.datagrok.app.connections

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
import dev.datagrok.app.AppState
import dev.datagrok.app.UnsavedDataFiles
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.datafile.DataFileConnection
import dev.datagrok.core.datafile.DataFileState
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
                                action("Keep mine, overwrite $name") { act { connection.sync.save(force = true) } }
                                action("Discard mine, reload $name") { act { connection.sync.reload() } }
                            }
                            DataFileState.Held.Reason.LosesContent -> {
                                action("Save anyway") { act { connection.sync.save(force = true) } }
                                action("Discard changes") { act { connection.sync.reload() } }
                            }
                        }
                    },
                )
            is DataFileState.Failed ->
                InlineErrorBanner(
                    "${current.message}. Your changes are kept and saved as soon as writing works again.",
                    modifier,
                    linkActions = {
                        action("Try again") { act { connection.sync.save() } }
                        action("Reload $name") { act { connection.sync.reload() } }
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
            DataFileState.Saving -> Triple("saving…", "Writing ${connection.sync.file.fileName}", false)
            is DataFileState.Held -> Triple("unsaved", current.message, false)
            is DataFileState.Failed -> Triple("not saved", current.message, true)
            is DataFileState.InSync -> {
                val notes = connection.sync.notes + listOfNotNull(
                    connection.sync.readOnlyReason?.let { "Read-only: $it" },
                    current.sessionOnly.takeIf { it.isNotEmpty() }?.let { "Only in this session (the file holds one table): ${it.joinToString()}" },
                )
                if (notes.isEmpty()) return
                Triple("note", notes.joinToString("\n"), false)
            }
        }
    val colors = JewelTheme.globalColors.text
    Tooltip({ Text(tooltip) }) {
        Text(label, color = if (error) colors.error else if (label == "note") colors.info else colors.warning, maxLines = 1, softWrap = false)
    }
}

/** Explorer menu items for a data file's connection. */
internal fun dataFileMenu(app: AppState, config: ConnectionConfig): List<ContextMenuItem> {
    val connection = app.databases.dataFile(config.id)
    val file = runCatching { dev.datagrok.core.datafile.DataFileStore.pathOf(config.database).toFile() }.getOrNull()
    return listOfNotNull(
        connection?.takeIf { it.sync.readOnlyReason == null }?.let { ContextMenuItem("Save to File Now") { app.scope.launch { it.sync.save() } } },
        connection?.let { ContextMenuItem("Reload from File") { app.scope.launch { it.sync.reload() } } },
        file?.takeIf { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) }?.let {
            ContextMenuItem("Reveal in File Manager") { runCatching { Desktop.getDesktop().browseFileDirectory(it) } }
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
    AppDialog(onCloseRequest = ::cancel, size = DpSize(520.dp, 260.dp), title = "Unsaved Data Files", resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("These files don't have your latest changes yet. Closing their connections discards those changes.")
            request.connections.forEach { connection ->
                val state by connection.sync.state.collectAsState()
                val detail = (state as? DataFileState.Held)?.message ?: (state as? DataFileState.Failed)?.message ?: "Saved."
                Text("${connection.sync.file.fileName}: $detail", color = JewelTheme.globalColors.text.info, maxLines = 2)
            }
            error?.let { Text(it, color = JewelTheme.globalColors.text.error, maxLines = 3) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = ::cancel, enabled = !busy) { Text("Cancel") }
                OutlinedButton(onClick = {
                    app.unsavedDataFiles = null
                    request.then()
                }, enabled = !busy) { Text("Discard Changes") }
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
                }, enabled = !busy) { Text("Save and Continue") }
            }
        }
    }
}

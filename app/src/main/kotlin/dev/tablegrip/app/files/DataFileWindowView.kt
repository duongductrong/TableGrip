package dev.tablegrip.app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.connections.DataFileBadge
import dev.tablegrip.app.connections.DataFileBanner
import dev.tablegrip.app.grid.SelectionStats
import dev.tablegrip.app.table.TableDataView
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.handleFilterShortcut
import dev.tablegrip.app.ui.AppDialogs
import dev.tablegrip.app.ui.FollowThemeAppearance
import dev.tablegrip.app.ui.HeaderButton
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.IslandGap
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.ZoomControl
import dev.tablegrip.app.ui.Zoomed
import dev.tablegrip.app.ui.handleZoomShortcut
import dev.tablegrip.core.connection.DatabaseType
import java.awt.Desktop
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

/**
 * A data file's own window: its grid, editable and saved back to the file, with the workbook's sheets along the bottom.
 * [onClosed] runs once the window has closed, after any unsaved edits were dealt with.
 */
@Composable
internal fun DataFileWindowView(app: AppState, file: DataFileWindow, onClosed: () -> Unit) {
    DecoratedWindow(
        onCloseRequest = { app.files.requestClose(file, onClosed) },
        state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
        title = file.title,
        onPreviewKeyEvent = { handleZoomShortcut(app, it) },
        onKeyEvent = { handleShortcut(app, file, it, onClosed) },
    ) {
        FollowThemeAppearance(window)
        // Opening the same file again brings this window up instead of a second one.
        LaunchedEffect(file.raiseRequests) {
            if (file.raiseRequests > 0) {
                if (window.isMinimized) window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        DisposableEffect(window) {
            val listener =
                object : WindowAdapter() {
                    override fun windowActivated(e: WindowEvent) {
                        app.files.focused = file
                    }
                }
            window.addWindowListener(listener)
            // Data files dropped on this window open in windows of their own too.
            val previous = window.contentPane.dropTarget
            window.contentPane.dropTarget = FileDropTarget { app.files.open(it) }
            onDispose {
                window.removeWindowListener(listener)
                window.contentPane.dropTarget = previous
            }
        }
        FileTitleBar(app, file)
        Zoomed {
            Column(Modifier.fillMaxSize().background(LocalIslands.current.frame)) {
                Island(Modifier.weight(1f).fillMaxWidth().padding(start = IslandGap, end = IslandGap)) { FileContent(app, file) }
                FileStatusBar(app, file)
            }
        }
        if (!app.mainWindowVisible && app.files.dialogHost === file) AppDialogs(app)
    }
}

@Composable
private fun org.jetbrains.jewel.window.DecoratedWindowScope.FileTitleBar(app: AppState, file: DataFileWindow) {
    val connection = file.connectionId?.let(app.databases::dataFile)
    TitleBar(Modifier.newFullscreenControls()) {
        Row(Modifier.align(Alignment.Start).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AllIconsKeys.Nodes.DataTables, null, Modifier.size(16.dp))
            connection?.let { DataFileBadge(it) }
        }
        Text(file.title, color = JewelTheme.globalColors.text.info)
        Row(Modifier.align(Alignment.End).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderButton("open_file", AllIconsKeys.General.Add, "Open another data file…") { app.connections.chooseDataFiles(app.files::open) }
            HeaderButton("reload_file", AllIconsKeys.Actions.Refresh, "Reload from file", enabled = connection != null) {
                connection?.let { app.scope.launch { it.sync.reload() } }
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                HeaderButton("reveal_file", AllIconsKeys.Nodes.Folder, "Reveal in file manager") { runCatching { Desktop.getDesktop().browseFileDirectory(file.file) } }
            }
            val config = file.config
            HeaderButton("file_console", AllIconsKeys.Nodes.Console, "Query with SQL in the main window", enabled = config != null) {
                config?.let(app.workspace::openConsole)
            }
            HeaderButton("open_in_main", AllIconsKeys.Nodes.DataSchema, "Show the main window: connections and the explorer") { app.mainWindowVisible = true }
        }
    }
}

@Composable
private fun FileContent(app: AppState, file: DataFileWindow) {
    val selected = file.selected
    Column(Modifier.fillMaxSize()) {
        file.connectionId?.let { DataFileBanner(app, it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = file.error
            val config = file.config
            when {
                error != null ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error, color = JewelTheme.globalColors.text.error)
                        DefaultButton(onClick = file::load) { Text("Try Again") }
                    }
                selected != null -> key(selected) { TableDataView(selected) }
                config != null && !file.loading && file.tables.isEmpty() -> Text("${file.title} has no tables.", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                config != null -> ConnectionLoadingPlaceholder(config, app.databases.status(config.id), "Opening ${file.title}…", Modifier.align(Alignment.Center))
                else -> Text("Opening ${file.title}…", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
            }
        }
    }
}

/** The workbook's sheets on the left, like a spreadsheet's; the selection's aggregates or the latest status on the right. */
@Composable
private fun FileStatusBar(app: AppState, file: DataFileWindow) {
    val info = JewelTheme.globalColors.text.info
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            // A CSV's single table needs no tab; a workbook's sheets always get one, even when there's only one.
            if (file.tables.size > 1 || file.config?.type == DatabaseType.ExcelFile) {
                file.tables.forEach { table -> ViewChip(table.name, file.selected?.table == table) { file.select(table) } }
            }
        }
        val stats = file.selected?.activeGrid?.let { (model, state) -> SelectionStats.of(model, state) }
        ZoomControl(app)
        Text(stats?.summary ?: app.status, color = if (stats != null) JewelTheme.globalColors.text.normal else info, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun handleShortcut(app: AppState, file: DataFileWindow, event: KeyEvent, onClosed: () -> Unit): Boolean {
    val tab = file.selected
    fun done(block: () -> Unit) = true.also { block() }
    fun cycle(offset: Int): Boolean {
        val tables = file.tables
        if (tables.size < 2) return false
        val index = tables.indexOf(tab?.table).coerceAtLeast(0)
        file.select(tables[Math.floorMod(index + offset, tables.size)])
        return true
    }
    return when {
        Shortcuts.CloseTab.matches(event) -> done { app.files.requestClose(file, onClosed) }
        Shortcuts.NextTab.matches(event) || Shortcuts.NextTabAlt.matches(event) -> cycle(1)
        Shortcuts.PreviousTab.matches(event) || Shortcuts.PreviousTabAlt.matches(event) -> cycle(-1)
        Shortcuts.Settings.matches(event) -> done {
            app.mainWindowVisible = true
            app.settingsOpen = true
        }
        tab == null -> false
        Shortcuts.Reload.matches(event) -> done { tab.refresh() }
        Shortcuts.Save.matches(event) -> done { tab.submit() }
        else -> handleFilterShortcut(tab, event)
    }
}

/** Accepts files dragged from the file manager and hands over the regular files among them. */
internal class FileDropTarget(private val onFiles: (List<java.io.File>) -> Unit) : java.awt.dnd.DropTarget() {
    override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent) {
        if (event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) event.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY) else event.rejectDrag()
    }

    @Synchronized
    override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
        if (!event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return event.rejectDrop()
        event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
        val files = runCatching { (event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<java.io.File>() }.getOrDefault(emptyList())
        event.dropComplete(files.isNotEmpty())
        // The drop arrives on the AWT thread, which is also Compose's UI thread on desktop.
        onFiles(files.filter { it.isFile })
    }
}

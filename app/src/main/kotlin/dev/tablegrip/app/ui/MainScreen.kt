package dev.tablegrip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.activity.ActivityTab
import dev.tablegrip.app.backup.BackupDialog
import dev.tablegrip.app.agent.AgentPanel
import dev.tablegrip.app.settings.SettingsScreen
import dev.tablegrip.app.LeftTool
import dev.tablegrip.app.connections.ConnectionDialog
import dev.tablegrip.app.connections.DeleteConnectionDialog
import dev.tablegrip.app.connections.ImportConnectionsDialog
import dev.tablegrip.app.connections.MissingDriverDialog
import dev.tablegrip.app.connections.GroupDialog
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.explorer.ExplorerPanel
import dev.tablegrip.app.export.ExportDialog
import dev.tablegrip.app.grid.SelectionStats
import dev.tablegrip.app.history.HistoryPanel
import dev.tablegrip.app.queries.DeleteQueryDialog
import dev.tablegrip.app.queries.QueriesPanel
import dev.tablegrip.app.queries.RenameQueryDialog
import dev.tablegrip.app.queries.SaveQueryDialog
import dev.tablegrip.app.search.GoToAnythingPopup
import dev.tablegrip.app.schema.ImportDialog
import dev.tablegrip.app.schema.TestDataDialog
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.tools.ToolDownloadDialog
import dev.tablegrip.app.workspace.WorkspaceView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Gap between islands, and between islands and the frame edge. */
internal val IslandGap = 6.dp

/**
 * Root layout in the Islands style: the tool stripe and status bar sit directly on the frame, while the tool window
 * and the editor tabs float on it as rounded islands.
 */
@Composable
internal fun MainScreen(app: AppState, dialogs: Boolean = true) {
    val islands = LocalIslands.current
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(islands.frame)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ToolStripe(app)
            // Movable, so opening the agent doesn't rebuild the editors and lose their scroll and focus.
            val center = remember { movableContentOf { CenterArea(app) } }
            Box(Modifier.weight(1f).fillMaxSize()) {
                if (!app.agentOpen) {
                    center()
                } else {
                    HorizontalSplitLayout(
                        first = { Box(Modifier.fillMaxSize().padding(end = IslandGap / 2)) { center() } },
                        second = { Island(Modifier.fillMaxSize().padding(start = IslandGap / 2)) { AgentPanel(app) } },
                        modifier = Modifier.fillMaxSize(),
                        dividerStyle = remember { DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)) },
                        draggableWidth = IslandGap,
                        firstPaneMinWidth = 480.dp,
                        secondPaneMinWidth = 300.dp,
                        state = app.agentSplit,
                    )
                }
            }
            if (hasRightStripe(app)) RightToolStripe(app) else Box(Modifier.width(IslandGap))
        }
        StatusBar(app)
    }
    // Dims the window behind go-to-anything so the panel reads as its own layer; the popup itself can't cover the
    // window, and its outside click closes it.
    if (app.goToOpen) Box(Modifier.fillMaxSize().background(islands.scrim))
    }
    if (dialogs) AppDialogs(app)
}

/**
 * Dialogs any window can lead to (a driver to download, an export, unsaved data files). One window shows them: the main
 * window, or while it's hidden, the data file window in front.
 */
@Composable
internal fun AppDialogs(app: AppState) {
    app.connections.dialog?.let { ConnectionDialog(app.connections, it) }
    app.connections.pendingDelete?.let { DeleteConnectionDialog(app.connections, it) }
    app.connections.groupRequest?.let { GroupDialog(app.connections, it) }
    app.connections.importing?.let { ImportConnectionsDialog(app.connections, it) }
    app.connections.driverPrompt?.let { MissingDriverDialog(app.connections, it) }
    if (app.shortcutsOpen) ShortcutsDialog(app)
    app.exportRequest?.let { ExportDialog(app, it) }
    app.importWizard?.let { ImportDialog(app, it) }
    app.testData?.let { TestDataDialog(app, it) }
    app.savedQueries.saveRequest?.let { SaveQueryDialog(app, it) }
    app.savedQueries.renaming?.let { RenameQueryDialog(app, it) }
    app.savedQueries.pendingDelete?.let { DeleteQueryDialog(app, it) }
    if (app.goToOpen) GoToAnythingPopup(app)
    app.backup?.let { BackupDialog(app, it) }
    app.tools.pending?.let { ToolDownloadDialog(app, it) }
    app.unsavedDataFiles?.let { dev.tablegrip.app.connections.UnsavedDataFilesDialog(app, it) }
}

/** The left tool window and the editor tabs, or the settings screen in their place. */
@Composable
private fun CenterArea(app: AppState) {
    val workspace = @Composable { Island(Modifier.fillMaxSize()) { WorkspaceView(app) } }
    val tool = app.leftTool
    if (app.settingsOpen) {
        // Settings take the whole content area; tabs and tool windows come back when it closes.
        Island(Modifier.fillMaxSize()) { SettingsScreen(app) }
    } else if (tool == null) {
        workspace()
    } else {
        // The split's divider is invisible: the gap between islands is the drag handle.
        HorizontalSplitLayout(
            first = {
                Island(Modifier.fillMaxSize().padding(end = IslandGap / 2)) {
                    when (tool) {
                        LeftTool.Explorer -> ExplorerPanel(app)
                        LeftTool.History -> HistoryPanel(app)
                        LeftTool.Queries -> QueriesPanel(app)
                    }
                }
            },
            second = { Box(Modifier.fillMaxSize().padding(start = IslandGap / 2)) { workspace() } },
            modifier = Modifier.fillMaxSize(),
            dividerStyle = remember { DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)) },
            draggableWidth = IslandGap,
            firstPaneMinWidth = 220.dp,
            secondPaneMinWidth = 420.dp,
            state = app.sidebarSplit,
        )
    }
}

/** Breadcrumbs of what's selected on the left, the latest status message on the right — all on the frame. */
/** Sessions stuck behind a lock on a server whose Activity tab is open; clicking shows them. */
@Composable
private fun BlockedSessionsChip(app: AppState) {
    val tab = app.workspace.tabs.filterIsInstance<ActivityTab>().filter { it.stuck > 0 }.maxByOrNull { it.connectionId == app.currentConnectionId } ?: return
    val red = Color(0xFFDB4B4B)
    Tooltip({ Text("Sessions on ${tab.config.name} waiting on a lock for more than 5 s") }) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).background(red.copy(alpha = 0.15f)).clickable(onClick = tab::showBlocked).padding(horizontal = 6.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.General.Warning, null, Modifier.size(12.dp))
            Text("${tab.stuck} blocked on ${tab.config.name}", color = red, maxLines = 1)
        }
    }
}

@Composable
private fun StatusBar(app: AppState) {
    val info = JewelTheme.globalColors.text.info
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val connection = app.currentConnection
        val crumbs =
            buildList {
                when (val tab = app.workspace.selected) {
                    is TableDataTab -> {
                        add(tab.config.name)
                        listOfNotNull(tab.table.namespace.catalog, tab.table.namespace.schema).forEach(::add)
                        add(tab.table.name)
                    }
                    is ConsoleTab -> {
                        add(tab.config.name)
                        add(tab.title)
                    }
                    else -> connection?.let { add(it.name) }
                }
            }
        val showCrumbs = app.settings.statusBreadcrumbs
        if (connection != null && showCrumbs) Badge(connection, size = 14)
        if (showCrumbs) crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Icon(AllIconsKeys.General.ChevronRight, null, tint = info)
            Text(crumb, color = if (index == crumbs.lastIndex) JewelTheme.globalColors.text.normal else info, maxLines = 1)
        }
        Box(Modifier.weight(1f))
        ZoomControl(app)
        BlockedSessionsChip(app)
        // Like a spreadsheet: several cells selected shows their aggregates instead of the last status message.
        val stats = app.workspace.selected?.takeIf { !app.settingsOpen && app.settings.statusSelectionStats }?.activeGrid?.let { (model, state) -> SelectionStats.of(model, state) }
        if (stats != null) {
            Tooltip({ Text("${stats.cells} cell(s) selected · ${stats.numbers} numeric") }) {
                Text(stats.summary, color = JewelTheme.globalColors.text.normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(app.status, color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        connection?.let { Text(it.type.displayName, color = info) }
    }
}

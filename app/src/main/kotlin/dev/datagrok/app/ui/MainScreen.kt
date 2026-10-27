package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.LeftTool
import dev.datagrok.app.connections.ConnectionDialog
import dev.datagrok.app.connections.DeleteConnectionDialog
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.explorer.ExplorerPanel
import dev.datagrok.app.export.ExportDialog
import dev.datagrok.app.history.HistoryPanel
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.workspace.WorkspaceView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
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
internal fun MainScreen(app: AppState) {
    val islands = LocalIslands.current
    Column(Modifier.fillMaxSize().background(islands.frame)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ToolStripe(app)
            val workspace = @Composable { Island(Modifier.fillMaxSize()) { WorkspaceView(app) } }
            val tool = app.leftTool
            Box(Modifier.weight(1f).fillMaxSize().padding(end = IslandGap)) {
                if (tool == null) {
                    workspace()
                } else {
                    // The split's divider is invisible: the gap between islands is the drag handle.
                    HorizontalSplitLayout(
                        first = {
                            Island(Modifier.fillMaxSize().padding(end = IslandGap / 2)) {
                                when (tool) {
                                    LeftTool.Explorer -> ExplorerPanel(app)
                                    LeftTool.History -> HistoryPanel(app)
                                }
                            }
                        },
                        second = { Box(Modifier.fillMaxSize().padding(start = IslandGap / 2)) { workspace() } },
                        modifier = Modifier.fillMaxSize(),
                        dividerStyle = remember { DividerStyle(Color.Transparent, DividerMetrics(0.dp, 0.dp)) },
                        draggableWidth = IslandGap,
                        firstPaneMinWidth = 220.dp,
                        secondPaneMinWidth = 420.dp,
                        state = rememberSplitLayoutState(0.24f),
                    )
                }
            }
        }
        StatusBar(app)
    }

    app.connections.dialog?.let { ConnectionDialog(app.connections, it) }
    app.connections.pendingDelete?.let { DeleteConnectionDialog(app.connections, it) }
    if (app.settingsOpen) SettingsDialog(app)
    app.exportRequest?.let { ExportDialog(app, it) }
}

/** Breadcrumbs of what's selected on the left, the latest status message on the right — all on the frame. */
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
        if (connection != null) Badge(connection, size = 14)
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Icon(AllIconsKeys.General.ChevronRight, null, tint = info)
            Text(crumb, color = if (index == crumbs.lastIndex) JewelTheme.globalColors.text.normal else info, maxLines = 1)
        }
        Box(Modifier.weight(1f))
        Text(app.status, color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        connection?.let { Text(it.type.displayName, color = info) }
    }
}

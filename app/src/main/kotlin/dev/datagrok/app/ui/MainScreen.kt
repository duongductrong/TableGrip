package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.ConnectionDialog
import dev.datagrok.app.connections.DeleteConnectionDialog
import dev.datagrok.app.explorer.ExplorerPanel
import dev.datagrok.app.workspace.WorkspaceView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState

/** Root layout: database explorer on the left, editor tabs on the right, status bar at the bottom. */
@Composable
internal fun MainScreen(app: AppState) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        HorizontalSplitLayout(
            first = { ExplorerPanel(app) },
            second = { WorkspaceView(app) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            firstPaneMinWidth = 200.dp,
            secondPaneMinWidth = 400.dp,
            state = rememberSplitLayoutState(0.24f),
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(app.status, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    app.connections.dialog?.let { ConnectionDialog(app.connections, it) }
    app.connections.pendingDelete?.let { DeleteConnectionDialog(app.connections, it) }
}

package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState

/** Root layout: database explorer on the left, query consoles on the right, status bar at the bottom. */
@Composable
internal fun MainScreen(state: AppState) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        HorizontalSplitLayout(
            first = { ExplorerPanel(state) },
            second = { QueryWorkspace(state) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            firstPaneMinWidth = 200.dp,
            secondPaneMinWidth = 400.dp,
            state = rememberSplitLayoutState(0.22f),
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        StatusBar(state.status)
    }

    state.connectionDialog?.let { ConnectionDialog(state, it) }
    state.pendingDelete?.let { DeleteConnectionDialog(state, it) }
}

@Composable
private fun StatusBar(text: String) {
    Box(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = JewelTheme.globalColors.text.info)
    }
}

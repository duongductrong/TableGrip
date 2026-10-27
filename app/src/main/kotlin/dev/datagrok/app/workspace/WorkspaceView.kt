package dev.datagrok.app.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.ConsoleView
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.table.TableDataView
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.editorTabStyle

@Composable
internal fun WorkspaceView(app: AppState) {
    val workspace = app.workspace
    val selected = workspace.selected
    if (selected == null) {
        EmptyWorkspace()
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabStrip(
            tabs =
                workspace.tabs.map { tab ->
                    TabData.Default(
                        selected = tab == selected,
                        content = { state ->
                            SimpleTabContent(
                                label = tab.title,
                                state = state,
                                iconKey = if (tab is ConsoleTab) AllIconsKeys.Nodes.Console else AllIconsKeys.Nodes.DataTables,
                            )
                        },
                        onClick = { workspace.selected = tab },
                        onClose = { workspace.requestClose(tab) },
                    )
                },
            style = JewelTheme.editorTabStyle,
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        // Keyed so each tab keeps its own scroll positions and split sizes.
        key(selected) {
            when (selected) {
                is ConsoleTab -> ConsoleView(app, selected)
                is TableDataTab -> TableDataView(selected)
                else -> Unit
            }
        }
    }
}

@Composable
private fun EmptyWorkspace() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val info = JewelTheme.globalColors.text.info
        Text("Double-click a table to browse its data", color = info)
        Text("Right-click a connection → New Console to write SQL", color = info)
    }
}

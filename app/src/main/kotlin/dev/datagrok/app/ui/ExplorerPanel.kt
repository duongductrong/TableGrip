package dev.datagrok.app.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.DbObject
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Left-hand database explorer: saved connections and the objects of the active one. */
@Composable
internal fun ExplorerPanel(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Database Explorer", Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Tooltip({ Text("New connection") }) {
                IconButton(onClick = state::openNewConnection) { Icon(AllIconsKeys.General.Add, "New connection") }
            }
            Tooltip({ Text("Refresh") }) {
                IconButton(onClick = state::refreshObjects) { Icon(AllIconsKeys.Actions.Refresh, "Refresh") }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (state.connections.isEmpty()) {
            EmptyExplorer(state)
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
            items(state.connections, key = { it.id }) { connection ->
                ContextMenuArea(
                    items = {
                        listOf(
                            ContextMenuItem("Connect") { state.connect(connection) },
                            ContextMenuItem("Edit…") { state.openEditConnection(connection) },
                            ContextMenuItem("Delete…") { state.pendingDelete = connection },
                        )
                    }
                ) {
                    ConnectionRow(connection, active = connection == state.activeConnection) { state.connect(connection) }
                }
                if (connection == state.activeConnection) {
                    state.objects.forEach { ObjectRow(it) }
                }
            }
        }
    }
}

@Composable
private fun EmptyExplorer(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No connections yet", color = JewelTheme.globalColors.text.info)
        OutlinedButton(onClick = state::openNewConnection) { Text("New Connection…") }
    }
}

@Composable
private fun ConnectionRow(connection: ConnectionConfig, active: Boolean, onClick: () -> Unit) {
    TreeRow(depth = 0, onClick = onClick) {
        Icon(AllIconsKeys.Nodes.DataTables, connection.type.displayName)
        Text(connection.name, color = if (active) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun ObjectRow(obj: DbObject) {
    TreeRow(depth = 1, onClick = {}) {
        Icon(if (obj.kind == DbObject.Kind.View) AllIconsKeys.Nodes.DataSchema else AllIconsKeys.Nodes.DataTables, obj.kind.name)
        Text(obj.name)
    }
}

@Composable
private fun TreeRow(depth: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(24.dp).padding(start = (8 + depth * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

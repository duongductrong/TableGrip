package dev.datagrok.app.explorer

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.ConnectionStatus
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Left-hand database explorer: connections → schemas → tables/views → columns, indexes, keys. */
@Composable
internal fun ExplorerPanel(app: AppState) {
    val explorer = app.explorer
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Database Explorer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Tooltip({ Text("New connection") }) {
                IconButton(onClick = app.connections::openNew) { Icon(AllIconsKeys.General.Add, "New connection") }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (app.connections.profiles.isEmpty()) {
            EmptyExplorer(app)
            return@Column
        }

        val tree by remember { derivedStateOf { explorer.buildTree() } }
        LaunchedEffect(explorer) { snapshotFlow { explorer.treeState.openNodes }.collect(explorer::onNodesOpened) }

        LazyTree(
            tree = tree,
            treeState = explorer.treeState,
            modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
            onElementDoubleClick = { element ->
                val node = element.data
                if (node is ExplorerNode.Table) app.connections.byId(node.connectionId)?.let { app.workspace.openTable(it, node.info.ref) }
            },
        ) { element ->
            ContextMenuArea(items = { contextMenu(app, element.data) }) { NodeRow(app, element.data) }
        }
    }
}

private fun contextMenu(app: AppState, node: ExplorerNode): List<ContextMenuItem> =
    when (node) {
        is ExplorerNode.Connection -> {
            val config = node.config
            val connected = app.databases.status(config.id) is ConnectionStatus.Connected
            listOfNotNull(
                ContextMenuItem("New Console") { app.workspace.openConsole(config) },
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
                ContextMenuItem("Disconnect") { app.explorer.disconnect(config.id) }.takeIf { connected },
                ContextMenuItem("Edit…") { app.connections.openEdit(config) },
                ContextMenuItem("Delete…") { app.connections.pendingDelete = config },
            )
        }
        is ExplorerNode.Schema -> listOf(ContextMenuItem("Refresh") { app.explorer.refresh(node.key) })
        is ExplorerNode.Table -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            val ref = node.info.ref
            listOf(
                ContextMenuItem("Open Data") { app.workspace.openTable(config, ref) },
                ContextMenuItem("New Console with SELECT") {
                    val name = SqlDialect.of(config.type).qualifiedName(ref)
                    app.workspace.openConsole(config, "SELECT * FROM $name;")
                },
                ContextMenuItem("Copy Name") { copyToClipboard(ref.name) },
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
            )
        }
        is ExplorerNode.Column -> listOf(ContextMenuItem("Copy Name") { copyToClipboard(node.column.name) })
        else -> emptyList()
    }

@Composable
private fun NodeRow(app: AppState, node: ExplorerNode) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val info = JewelTheme.globalColors.text.info
        when (node) {
            is ExplorerNode.Connection -> {
                NodeIcon(AllIconsKeys.Nodes.DataSchema)
                Text(node.config.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (val status = app.databases.status(node.config.id)) {
                    ConnectionStatus.Connecting -> Text("connecting…", color = info)
                    is ConnectionStatus.Failed -> Text("failed", color = JewelTheme.globalColors.text.error)
                    is ConnectionStatus.Connected -> Text(node.config.type.displayName, color = info)
                    ConnectionStatus.Disconnected -> Unit
                }
            }
            is ExplorerNode.Schema -> {
                NodeIcon(AllIconsKeys.Nodes.Folder)
                Text(node.namespace.displayName)
            }
            is ExplorerNode.Folder -> {
                NodeIcon(AllIconsKeys.Nodes.Folder)
                Text(node.label)
                Text(node.children.size.toString(), color = info)
            }
            is ExplorerNode.Table -> {
                NodeIcon(AllIconsKeys.Nodes.DataTables)
                Text(node.info.ref.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is ExplorerNode.Column -> {
                val column = node.column
                NodeIcon(if (column.primaryKey) AllIconsKeys.Nodes.FieldPK else AllIconsKeys.Nodes.DataColumn)
                Text(column.name)
                Text(column.typeName.lowercase() + if (column.nullable) "" else " not null", color = info)
            }
            is ExplorerNode.Index -> {
                NodeIcon(AllIconsKeys.Nodes.FieldPK)
                Text(node.index.name)
                Text((if (node.index.unique) "unique " else "") + "(${node.index.columns.joinToString()})", color = info)
            }
            is ExplorerNode.ForeignKey -> {
                val fk = node.foreignKey
                NodeIcon(AllIconsKeys.Nodes.FieldPK)
                Text(fk.name)
                Text("(${fk.columns.joinToString()}) → ${fk.referencedTable.name}(${fk.referencedColumns.joinToString()})", color = info)
            }
            is ExplorerNode.Message ->
                Text(node.text, color = if (node.isError) JewelTheme.globalColors.text.error else info, maxLines = 2)
        }
    }
}

@Composable
private fun NodeIcon(key: IconKey) = Icon(key, contentDescription = null)

@Composable
private fun EmptyExplorer(app: AppState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No connections yet", color = JewelTheme.globalColors.text.info)
        OutlinedButton(onClick = app.connections::openNew) { Text("New Connection…") }
    }
}

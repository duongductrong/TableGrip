package dev.datagrok.app.explorer

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.ConnectionStatus
import dev.datagrok.app.table.StructureSection
import dev.datagrok.app.ui.Badge
import dev.datagrok.app.ui.EnvironmentChip
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** Left-hand connections tree: connections → schemas → tables/views → columns, indexes, keys. */
@Composable
internal fun ExplorerPanel(app: AppState) {
    val explorer = app.explorer
    Column(Modifier.fillMaxSize()) {
        // The title starts where the tree rows' chevrons do, so the header and the tree share one left edge.
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(start = TreeRowInset, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connections", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
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
            style = explorerTreeStyle(),
            onElementDoubleClick = { element -> openNode(app, element.data) },
        ) { element ->
            ContextMenuArea(items = { contextMenu(app, element.data) }) { NodeRow(app, element.data, Modifier.fillMaxWidth()) }
        }
    }
}

private val TreeRowOuterPadding = 6.dp
private val TreeRowInnerPadding = 4.dp
private val TreeRowInset = TreeRowOuterPadding + TreeRowInnerPadding

/**
 * Int UI's tree metrics are tuned for IntelliJ's project view: a 23dp indent and 12dp side gutters push deep schemas
 * off a narrow sidebar. A tighter indent and gutter keep each level readable while the content stays balanced.
 */
@Composable
private fun explorerTreeStyle(): LazyTreeStyle {
    val base = JewelTheme.treeStyle
    return LazyTreeStyle(
        colors = base.colors,
        metrics =
            LazyTreeMetrics.defaults(
                indentSize = 16.dp,
                elementBackgroundCornerSize = CornerSize(4.dp),
                elementPadding = PaddingValues(horizontal = TreeRowOuterPadding),
                elementContentPadding = PaddingValues(horizontal = TreeRowInnerPadding, vertical = 2.dp),
                elementMinHeight = 26.dp,
                chevronContentGap = 4.dp,
            ),
        icons = base.icons,
    )
}

/** Tables open on their data; columns, indexes and keys open the table's structure on their section. */
private fun openNode(app: AppState, node: ExplorerNode) {
    val (table, section) =
        when (node) {
            is ExplorerNode.Table -> TableKey(node.connectionId, node.info.ref) to null
            is ExplorerNode.Column -> node.table to StructureSection.Columns
            is ExplorerNode.Index -> node.table to StructureSection.Indexes
            is ExplorerNode.ForeignKey -> node.table to StructureSection.ForeignKeys
            else -> return
        }
    app.connections.byId(table.connectionId)?.let { app.workspace.openTable(it, table.ref, section) }
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
                ContextMenuItem("Open Structure") { app.workspace.openTable(config, ref, StructureSection.Columns) },
                ContextMenuItem("Show DDL") { app.workspace.openTable(config, ref, StructureSection.Ddl) },
                ContextMenuItem("Copy DDL") { app.explorer.copyDdl(config, ref) },
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
private fun NodeRow(app: AppState, node: ExplorerNode, modifier: Modifier = Modifier) {
    // Filling the width makes the tree's selection highlight span the whole island, as in IntelliJ.
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val info = JewelTheme.globalColors.text.info
        when (node) {
            is ExplorerNode.Connection -> {
                Badge(node.config, size = 16)
                Text(node.config.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                EnvironmentChip(node.config)
                if (node.config.readOnly) Text("read-only", color = info, maxLines = 1, softWrap = false)
                when (val status = app.databases.status(node.config.id)) {
                    ConnectionStatus.Connecting -> Text("connecting…", color = info, maxLines = 1, softWrap = false)
                    is ConnectionStatus.Failed ->
                        Text("failed", color = JewelTheme.globalColors.text.error, maxLines = 1, softWrap = false)
                    is ConnectionStatus.Connected ->
                        Text(node.config.type.displayName, color = info, maxLines = 1, softWrap = false)
                    ConnectionStatus.Disconnected -> Unit
                }
            }
            is ExplorerNode.Schema -> {
                NodeIcon(AllIconsKeys.Nodes.Folder)
                Text(node.namespace.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is ExplorerNode.Folder -> {
                NodeIcon(AllIconsKeys.Nodes.Folder)
                NameAndDetail(node.label, node.children.size.toString(), Modifier.weight(1f))
            }
            is ExplorerNode.Table -> {
                NodeIcon(AllIconsKeys.Nodes.DataTables)
                Text(node.info.ref.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is ExplorerNode.Column -> {
                val column = node.column
                NodeIcon(if (column.primaryKey) AllIconsKeys.Nodes.FieldPK else AllIconsKeys.Nodes.DataColumn)
                NameAndDetail(column.name, column.typeName.lowercase() + if (column.nullable) "" else " not null", Modifier.weight(1f))
            }
            is ExplorerNode.Index -> {
                NodeIcon(AllIconsKeys.Nodes.FieldPK)
                NameAndDetail(
                    node.index.name,
                    (if (node.index.unique) "unique " else "") + "(${node.index.columns.joinToString()})",
                    Modifier.weight(1f),
                )
            }
            is ExplorerNode.ForeignKey -> {
                val fk = node.foreignKey
                NodeIcon(AllIconsKeys.Nodes.FieldPK)
                NameAndDetail(
                    fk.name,
                    "(${fk.columns.joinToString()}) → ${fk.referencedTable.name}(${fk.referencedColumns.joinToString()})",
                    Modifier.weight(1f),
                )
            }
            is ExplorerNode.Message ->
                Text(node.text, color = if (node.isError) JewelTheme.globalColors.text.error else info, maxLines = 2)
        }
    }
}

@Composable
private fun NodeIcon(key: IconKey) = Icon(key, contentDescription = null)

/**
 * A node's name followed by its greyed detail on a single line. The name gets first claim on the width and
 * the detail is ellipsized into what is left, so a narrow sidebar never wraps either one character by character.
 */
@Composable
private fun NameAndDetail(name: String, detail: String, modifier: Modifier = Modifier) {
    val info = JewelTheme.globalColors.text.info
    Layout(
        content = {
            Text(name, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(detail, color = info, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val nameText = measurables[0].measure(loose)
        val remaining = if (constraints.hasBoundedWidth) constraints.maxWidth - nameText.width - gap else Constraints.Infinity
        val detailText = if (remaining > 0) measurables[1].measure(loose.copy(maxWidth = remaining)) else null
        val width = nameText.width + (detailText?.let { gap + it.width } ?: 0)
        val height = maxOf(nameText.height, detailText?.height ?: 0)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            nameText.placeRelative(0, (height - nameText.height) / 2)
            detailText?.placeRelative(nameText.width + gap, (height - detailText.height) / 2)
        }
    }
}

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

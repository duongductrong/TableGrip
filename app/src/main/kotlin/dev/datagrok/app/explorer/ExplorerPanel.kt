package dev.datagrok.app.explorer

import dev.datagrok.core.agent.Mention
import dev.datagrok.app.schema.openImport
import dev.datagrok.app.schema.openTestData
import dev.datagrok.app.schema.schemaTools
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import dev.datagrok.app.connections.GroupRequest
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.separator
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
import dev.datagrok.app.connections.ProgressLine
import dev.datagrok.app.connections.connectingText
import dev.datagrok.app.connections.friendlyError
import dev.datagrok.app.table.StructureSection
import dev.datagrok.app.ui.Badge
import dev.datagrok.app.ui.EnvironmentChip
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.EngineFamily
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
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
            AddConnectionMenu(app)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (app.connections.profiles.isEmpty()) {
            EmptyExplorer(app)
            return@Column
        }

        TextField(
            explorer.search,
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            placeholder = { Text("Search connections and tables") },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
        )
        val tree by remember { derivedStateOf { explorer.buildTree() } }
        LaunchedEffect(explorer) { snapshotFlow { explorer.treeState.openNodes }.collect(explorer::onNodesOpened) }
        // Groups start open, like folders in TablePlus' sidebar; closing one is remembered for the session.
        val groups = remember { mutableSetOf<GroupKey>() }
        LaunchedEffect(tree) {
            val fresh = app.connections.profiles.map { GroupKey(if (it.favorite) "Favorites" else it.group.trim(), it.favorite) }.filter { it.name.isNotEmpty() && groups.add(it) }
            if (fresh.isNotEmpty()) explorer.treeState.openNodes(fresh)
        }
        // While searching, open the way to every match that's already loaded.
        LaunchedEffect(explorer.search.text.toString()) {
            val keys = explorer.searchMatchAncestors()
            if (keys.isNotEmpty()) explorer.treeState.openNodes(keys.toList())
        }

        val drag = rememberConnectionDrag(app)
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyTree(
                        tree = tree,
                        treeState = explorer.treeState,
                        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                        style = explorerTreeStyle(),
                        onElementDoubleClick = { element -> openNode(app, element.data) },
                    ) { element ->
                        ContextMenuArea(items = { contextMenu(app, element.data) }) { NodeRow(app, element.data, Modifier.fillMaxWidth().dragAndDrop(drag, element.data)) }
                    }
                    VerticalScrollbar(
                        explorer.treeState.lazyListState.lazyListState,
                        Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp),
                    )
                }
                if (drag.dragging) DropZones(drag)
            }
            if (drag.dragging) DragGhost(drag, origin)
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

/** Connections can be dragged; groups, and connections standing for the group they're in, take drops. */
@Composable
private fun Modifier.dragAndDrop(drag: ConnectionDragState, node: ExplorerNode): Modifier {
    val target =
        when (node) {
            is ExplorerNode.Group -> if (node.favorites) DropTarget.Favorites else DropTarget.Group(node.name)
            is ExplorerNode.Connection ->
                when {
                    node.config.favorite -> DropTarget.Favorites
                    node.config.group.isNotBlank() -> DropTarget.Group(node.config.group.trim())
                    else -> DropTarget.TopLevel
                }
            else -> return this
        }
    ForgetDropTarget(drag, node.key)
    val dropping = drag.hovered == target
    return dropTarget(drag, node.key, target)
        .let { if (node is ExplorerNode.Connection) it.draggableConnection(drag, node.config) else it }
        // A group lights up when connections would land in it, whether the pointer is on it or on one of its members.
        .dropHighlight(dropping && node is ExplorerNode.Group)
}

/** Tables open on their data; columns, indexes and keys open the table's structure on their section. */
private fun openNode(app: AppState, node: ExplorerNode) {
    if (node is ExplorerNode.Keyspace) {
        app.connections.byId(node.connectionId)?.let { app.workspace.openKeys(it, node.keyspace.index) }
        return
    }
    if (node is ExplorerNode.Collection) {
        app.connections.byId(node.connectionId)?.let { app.workspace.openCollection(it, node.database, node.info.name) }
        return
    }
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
                *schemaTools(app, config).toTypedArray(),
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
                ContextMenuItem("Disconnect") { app.explorer.disconnect(config.id) }.takeIf { connected },
                ContextMenuItem(if (config.favorite) "Remove from Favorites" else "Add to Favorites") { app.connections.toggleFavorite(config) },
                ContextMenuItem("Move to Group…") { app.connections.groupRequest = GroupRequest(listOf(config)) },
                ContextMenuItem("Remove from Group") { app.connections.moveToGroup(listOf(config), "") }.takeIf { config.group.isNotBlank() && !config.favorite },
                ContextMenuItem("Edit…") { app.connections.openEdit(config) },
                ContextMenuItem("Delete…") { app.connections.pendingDelete = config },
            )
        }
        is ExplorerNode.Group ->
            if (node.favorites) emptyList()
            else
                listOf(
                    ContextMenuItem("New Connection in ${node.name}…") { app.connections.openNew(group = node.name) },
                    ContextMenuItem("Rename Group…") {
                        app.connections.groupRequest = GroupRequest(node.children.mapNotNull { (it as? ExplorerNode.Connection)?.config }, renaming = node.name)
                    },
                    ContextMenuItem("Ungroup") { app.connections.ungroup(node.name) },
                )
        is ExplorerNode.Schema -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOfNotNull(
                ContextMenuItem("New Table…") { app.workspace.openTableDesigner(config, node.namespace) }.takeUnless { config.readOnly },
                ContextMenuItem("Import Data…") { app.openImport(config, node.namespace) }.takeUnless { config.readOnly },
                ContextMenuItem("Show ER Diagram") { app.workspace.openErDiagram(config, node.namespace) },
                ContextMenuItem("Compare Schema With…") { app.workspace.openSchemaCompare(config, node.namespace) },
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
            )
        }
        is ExplorerNode.DocumentDatabase -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem("New Console") { app.workspace.openConsole(config, database = node.name) },
                ContextMenuItem("Copy Name") { copyToClipboard(node.name) },
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
            )
        }
        is ExplorerNode.Collection -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem("Open Documents") { app.workspace.openCollection(config, node.database, node.info.name) },
                ContextMenuItem("New Console with find()") {
                    app.workspace.openConsole(config, database = node.database, initialSql = "db.getCollection('${node.info.name}').find({})")
                },
                ContextMenuItem("Copy Name") { copyToClipboard(node.info.name) },
            )
        }
        is ExplorerNode.Keyspace -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem("Browse Keys") { app.workspace.openKeys(config, node.keyspace.index) },
                ContextMenuItem("New Console") { app.workspace.openConsole(config, keyspace = node.keyspace.index) },
            )
        }
        is ExplorerNode.Table -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            val ref = node.info.ref
            listOf(
                ContextMenuItem("Open Data") { app.workspace.openTable(config, ref) },
                ContextMenuItem("Open Structure") { app.workspace.openTable(config, ref, StructureSection.Columns) },
                ContextMenuItem("Show DDL") { app.workspace.openTable(config, ref, StructureSection.Ddl) },
                ContextMenuItem("Edit Structure…") { app.workspace.openTableDesigner(config, ref.namespace, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem("Show in ER Diagram") { app.workspace.openErDiagram(config, ref.namespace, ref) },
                ContextMenuItem("Import Data…") { app.openImport(config, ref.namespace, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem("Generate Test Data…") { app.openTestData(config, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem("Copy DDL") { app.explorer.copyDdl(config, ref) },
                ContextMenuItem("New Console with SELECT") {
                    val name = SqlDialect.of(config.type).qualifiedName(ref)
                    app.workspace.openConsole(config, "SELECT * FROM $name;")
                },
                ContextMenuItem("Copy Name") { copyToClipboard(ref.name) },
                ContextMenuItem("Add to Chat") {
                    app.agent.mention(Mention.Table(config.id, config.name, config.type.displayName, ref.namespace.displayName, ref.name))
                }.takeIf { app.aiAccess(config).sharesSchema },
                ContextMenuItem("Refresh") { app.explorer.refresh(node.key) },
            ).filterNotNull()
        }
        is ExplorerNode.Column -> {
            val config = app.connections.byId(node.table.connectionId)
            listOfNotNull(
                ContextMenuItem("Copy Name") { copyToClipboard(node.column.name) },
                config?.takeIf { app.aiAccess(it).sharesSchema }?.let {
                    ContextMenuItem("Add to Chat") {
                        val column = node.column
                        app.agent.mention(Mention.Column(it.name, it.type.displayName, node.table.ref.name, column.name, column.typeName, column.primaryKey))
                    }
                },
            )
        }
        else -> emptyList()
    }

@Composable
private fun NodeRow(app: AppState, node: ExplorerNode, modifier: Modifier = Modifier) {
    // Filling the width makes the tree's selection highlight span the whole island, as in IntelliJ.
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val info = JewelTheme.globalColors.text.info
        when (node) {
            is ExplorerNode.Connection -> {
                val status = app.databases.status(node.config.id)
                StatusBadge(node.config, status)
                Text(node.config.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                EnvironmentChip(node.config)
                if (node.config.readOnly) Text("read-only", color = info, maxLines = 1, softWrap = false)
                when (status) {
                    // The spinner stays on the row itself, so a collapsed connection still shows it's being opened.
                    is ConnectionStatus.Connecting -> Tooltip({ Text(connectingText(node.config, status)) }) { CircularProgressIndicator() }
                    is ConnectionStatus.Failed ->
                        Tooltip({ Text(friendlyError(status.message).headline) }) {
                            Text("failed", color = JewelTheme.globalColors.text.error, maxLines = 1, softWrap = false)
                        }
                    is ConnectionStatus.Connected ->
                        Text(node.config.type.displayName, color = info, maxLines = 1, softWrap = false)
                    ConnectionStatus.Disconnected -> Unit
                }
            }
            is ExplorerNode.Group -> {
                NodeIcon(if (node.favorites) AllIconsKeys.Nodes.Favorite else AllIconsKeys.Nodes.Folder)
                NameAndDetail(node.name, node.children.size.toString(), Modifier.weight(1f))
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
            is ExplorerNode.DocumentDatabase -> {
                NodeIcon(AllIconsKeys.Nodes.DataSchema)
                Text(node.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is ExplorerNode.Collection -> {
                NodeIcon(AllIconsKeys.Nodes.DataTables)
                val detail = listOfNotNull(node.info.kind.takeIf { it != "collection" }, node.info.documents?.let { "~$it docs" }).joinToString(" · ")
                NameAndDetail(node.info.name, detail, Modifier.weight(1f))
            }
            is ExplorerNode.Keyspace -> {
                NodeIcon(AllIconsKeys.Nodes.DataSchema)
                val etcd = app.connections.byId(node.connectionId)?.type?.family == EngineFamily.Etcd
                NameAndDetail(if (etcd) "keys" else "db${node.keyspace.index}", node.keyspace.keys?.let { "$it ${if (it == 1L) "key" else "keys"}" }.orEmpty(), Modifier.weight(1f))
            }
            is ExplorerNode.Loading -> LoadingRow(app, node.parent, Modifier.weight(1f))
            is ExplorerNode.Failure -> FailureRow(app, node, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NodeIcon(key: IconKey) = Icon(key, contentDescription = null)

/** The connection's badge with a dot in its corner once it's open (green) or has failed (red). */
@Composable
private fun StatusBadge(config: ConnectionConfig, status: ConnectionStatus) {
    val dark = JewelTheme.isDark
    val dot =
        when (status) {
            is ConnectionStatus.Connected -> if (dark) Color(0xFF5FB865) else Color(0xFF369650)
            is ConnectionStatus.Failed -> if (dark) Color(0xFFDB5C5C) else Color(0xFFE55765)
            else -> null
        }
    Box {
        Badge(config, size = 16)
        if (dot != null) {
            // A ring in the island's colour cuts the dot out of the badge, as on IntelliJ's data source icons.
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .offset(3.dp, 3.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(LocalIslands.current.island)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
    }
}

/** What a node is waiting for: the connection's progress while it opens, then what is being listed. */
@Composable
private fun LoadingRow(app: AppState, parent: Any, modifier: Modifier = Modifier) {
    val connectionId = app.explorer.connectionIdOf(parent)
    val config = connectionId?.let(app.connections::byId)
    val status = connectionId?.let(app.databases::status)
    val startedAt = remember(parent) { System.currentTimeMillis() }
    if (config != null && status is ConnectionStatus.Connecting) {
        ProgressLine(connectingText(config, status), modifier, since = status.since)
        return
    }
    val text =
        when (parent) {
            is ConnectionKey ->
                when (config?.type?.paradigm) {
                    Paradigm.Document -> "Loading databases…"
                    Paradigm.KeyValue -> "Loading keys…"
                    else -> "Loading schemas…"
                }
            is ExplorerNode.Schema -> "Loading tables…"
            is ExplorerNode.DocumentDatabase -> "Loading collections…"
            is TableKey -> "Loading columns…"
            else -> "Loading…"
        }
    ProgressLine(text, modifier, since = startedAt)
}

/** Why a node's children couldn't load, in plain words, with ways out: retry, fix the connection, or copy the details. */
@Composable
private fun FailureRow(app: AppState, node: ExplorerNode.Failure, modifier: Modifier = Modifier) {
    val error = remember(node.message) { friendlyError(node.message) }
    val config = (node.parent as? ConnectionKey)?.let { app.connections.byId(it.id) }
    // The hint and actions line up with the headline's text rather than its icon.
    val textInset = 22.dp
    Column(modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Tooltip({ Text(error.detail, Modifier.widthIn(max = 420.dp)) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Error, contentDescription = null)
                Text(error.headline, color = JewelTheme.globalColors.text.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        error.hint?.let { Text(it, Modifier.padding(start = textInset), color = JewelTheme.globalColors.text.info, maxLines = 2) }
        Row(Modifier.padding(start = textInset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Link("Retry", onClick = { app.explorer.refresh(node.parent) })
            if (config != null) Link("Edit…", onClick = { app.connections.openEdit(config) })
            Link("Copy details", onClick = { copyToClipboard(error.detail) })
        }
    }
}

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

/**
 * The header's + button: a menu with a new connection first and, below it, importing from other tools — saying how many
 * connections they have, so the option explains itself.
 */
@Composable
private fun AddConnectionMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) app.connections.lookForImports() }
    Box {
        Tooltip({ Text("New or imported connection") }) {
            IconButton(onClick = { open = !open }) {
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AllIconsKeys.General.Add, "Add connection")
                    Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(10.dp))
                }
            }
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                selectableItem(selected = false, iconKey = AllIconsKeys.General.Add, onClick = {
                    open = false
                    app.connections.openNew()
                }) { Text("New Connection…") }
                separator()
                selectableItem(selected = false, iconKey = AllIconsKeys.ToolbarDecorator.Import, onClick = {
                    open = false
                    app.connections.openImport()
                }) {
                    val hint = app.connections.importHint
                    Column {
                        Text(if (hint == null) "Import from Other Tools…" else "Import ${hint.count} from ${hint.tools.joinToString(", ")}…")
                        Text("TablePlus, DBeaver, DataGrip, .pgpass, .env", color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
                    }
                }
            }
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
        LaunchedEffect(Unit) { app.connections.lookForImports() }
        Text("No connections yet", color = JewelTheme.globalColors.text.info)
        val hint = app.connections.importHint
        if (hint != null) {
            Text(
                (if (hint.count == 1) "1 connection" else "${hint.count} connections") + " found in " + hint.tools.joinToString(", "),
                color = JewelTheme.globalColors.text.info,
                textAlign = TextAlign.Center,
            )
            DefaultButton(onClick = app.connections::openImport) { Text(if (hint.count == 1) "Import It…" else "Import Them…") }
            OutlinedButton(onClick = app.connections::openNew) { Text("New Connection…") }
        } else {
            OutlinedButton(onClick = app.connections::openNew) { Text("New Connection…") }
            Link("Import from another tool…", onClick = app.connections::openImport)
        }
    }
}

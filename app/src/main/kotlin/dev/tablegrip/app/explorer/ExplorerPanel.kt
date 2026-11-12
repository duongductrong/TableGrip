package dev.tablegrip.app.explorer

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.metadata.SchemaObjectKind
import dev.tablegrip.core.activity.ActivitySupport
import dev.tablegrip.core.backup.BackupSupport
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.app.schema.openImport
import dev.tablegrip.app.schema.openTestData
import dev.tablegrip.app.schema.schemaTools
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
import dev.tablegrip.app.connections.GroupRequest
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.ContextMenuDivider
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
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.DataFileBadge
import dev.tablegrip.app.connections.dataFileMenu
import dev.tablegrip.app.backup.BackupState
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.connections.ProgressLine
import dev.tablegrip.app.connections.connectingText
import dev.tablegrip.app.connections.friendlyError
import dev.tablegrip.app.table.StructureSection
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.foundation.lazy.tree.Tree
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
            Text(tr("Connections"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            AddConnectionMenu(app)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        if (app.connections.profiles.isEmpty()) {
            ContextMenuArea(items = { sidebarMenu(app) }) { EmptyExplorer(app) }
            return@Column
        }

        TextField(
            explorer.search,
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            placeholder = { Text(tr("Search connections and tables")) },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
        )
        val tree by remember { derivedStateOf { explorer.buildTree() } }
        LaunchedEffect(explorer) { snapshotFlow { explorer.treeState.openNodes }.collect(explorer::onNodesOpened) }
        // Groups start open, like folders in a sidebar; closing one is remembered for the session.
        val groups = remember { mutableSetOf<GroupKey>() }
        LaunchedEffect(tree) {
            val fresh = app.connections.profiles.map { GroupKey(if (it.favorite) tr("Favorites") else it.group.trim(), it.favorite) }.filter { it.name.isNotEmpty() && groups.add(it) }
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
                    // Right-clicking blank space below the rows offers the sidebar's own actions; rows keep their own menus.
                    ContextMenuArea(items = { sidebarMenu(app) }) {
                        LazyTree(
                            tree = tree,
                            treeState = explorer.treeState,
                            modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                            style = explorerTreeStyle(),
                            onElementDoubleClick = { element -> openNode(app, element.data) },
                        ) { element ->
                            // Jewel draws no chevron for leaves, so their icons would sit under their siblings' chevrons.
                            val leafInset = if (element is Tree.Element.Leaf) TreeChevronSize + TreeChevronGap else 0.dp
                            ContextMenuArea(items = { contextMenu(app, element.data) }) {
                                NodeRow(app, element.data, Modifier.fillMaxWidth().dragAndDrop(drag, element.data).padding(start = leafInset))
                            }
                        }
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
private val TreeChevronSize = 16.dp
private val TreeChevronGap = 4.dp

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
                chevronContentGap = TreeChevronGap,
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
    if (node is ExplorerNode.SchemaObject) {
        app.connections.byId(node.connectionId)?.let { app.workspace.openSchemaObject(it, node.info.ref) }
        return
    }
    if (node is ExplorerNode.Records) {
        if (node.container.browsable) app.connections.byId(node.connectionId)?.let { app.workspace.openRecords(it, node.container) }
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

/** The menu on the sidebar's blank space: adding connections, then actions on the whole tree. */
private fun sidebarMenu(app: AppState): List<ContextMenuItem> {
    val connected = app.connections.profiles.map { it.id }.filter { app.databases.status(it) is ConnectionStatus.Connected }
    val tree =
        listOfNotNull(
            ContextMenuItem(tr("Collapse All")) { app.explorer.treeState.openNodes = emptySet() }.takeIf { app.explorer.treeState.openNodes.isNotEmpty() },
            ContextMenuItem(tr("Disconnect All")) {
                app.confirmDataFiles(connected) { connected.forEach(app.explorer::disconnectNow) }
            }.takeIf { connected.isNotEmpty() },
        )
    return listOf(
        ContextMenuItem(tr("New Connection…")) { app.connections.openNew() },
        ContextMenuItem(tr("Open Data File…")) { app.connections.chooseDataFiles() },
        ContextMenuItem(tr("Import from Other Tools…")) { app.connections.openImport() },
    ) + (if (tree.isEmpty()) emptyList() else listOf(ContextMenuDivider) + tree)
}

private fun contextMenu(app: AppState, node: ExplorerNode): List<ContextMenuItem> =
    when (node) {
        is ExplorerNode.Connection -> {
            val config = node.config
            val connected = app.databases.status(config.id) is ConnectionStatus.Connected
            listOfNotNull(
                ContextMenuItem(tr("New Console")) { app.workspace.openConsole(config) },
                *schemaTools(app, config).toTypedArray(),
                ContextMenuItem(tr("Activity")) { app.workspace.openActivity(config) }
                    .takeUnless { ActivitySupport.of(config.type) == ActivitySupport.NotApplicable },
                ContextMenuItem(tr("Back Up…")) { app.backup = BackupState(app, config, restore = false) }
                    .takeUnless { BackupSupport.of(config.type) == BackupSupport.NotApplicable },
                ContextMenuItem(tr("Restore…")) { app.backup = BackupState(app, config, restore = true) }
                    .takeUnless { BackupSupport.of(config.type) == BackupSupport.NotApplicable },
                *(if (config.type.isDataFile) dataFileMenu(app, config).toTypedArray() else emptyArray()),
                ContextMenuItem(tr("Refresh")) { app.explorer.refresh(node.key) },
                ContextMenuItem(tr("Disconnect")) { app.explorer.disconnect(config.id) }.takeIf { connected },
                ContextMenuItem(if (config.favorite) tr("Remove from Favorites") else tr("Add to Favorites")) { app.connections.toggleFavorite(config) },
                ContextMenuItem(tr("Move to Group…")) { app.connections.groupRequest = GroupRequest(listOf(config)) },
                ContextMenuItem(tr("Remove from Group")) { app.connections.moveToGroup(listOf(config), "") }.takeIf { config.group.isNotBlank() && !config.favorite },
                ContextMenuItem(tr("Edit…")) { app.connections.openEdit(config) },
                ContextMenuItem(tr("Delete…")) { app.connections.pendingDelete = config },
            )
        }
        is ExplorerNode.Group ->
            if (node.favorites) emptyList()
            else
                listOf(
                    ContextMenuItem(tr("New Connection in {0}…", node.name)) { app.connections.openNew(group = node.name) },
                    ContextMenuItem(tr("Rename Group…")) {
                        app.connections.groupRequest = GroupRequest(node.children.mapNotNull { (it as? ExplorerNode.Connection)?.config }, renaming = node.name)
                    },
                    ContextMenuItem(tr("Ungroup")) { app.connections.ungroup(node.name) },
                )
        is ExplorerNode.Schema -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOfNotNull(
                ContextMenuItem(tr("New Table…")) { app.workspace.openTableDesigner(config, node.namespace) }.takeUnless { config.readOnly },
                ContextMenuItem(tr("Import Data…")) { app.openImport(config, node.namespace) }.takeUnless { config.readOnly },
                ContextMenuItem(tr("Show ER Diagram")) { app.workspace.openErDiagram(config, node.namespace) },
                ContextMenuItem(tr("Compare Schema With…")) { app.workspace.openSchemaCompare(config, node.namespace) },
                ContextMenuItem(tr("Refresh")) { app.explorer.refresh(node.key) },
            )
        }
        is ExplorerNode.DocumentDatabase -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem(tr("New Console")) { app.workspace.openConsole(config, database = node.name) },
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(node.name) },
                ContextMenuItem(tr("Refresh")) { app.explorer.refresh(node.key) },
            )
        }
        is ExplorerNode.Collection -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem(tr("Open Documents")) { app.workspace.openCollection(config, node.database, node.info.name) },
                ContextMenuItem(tr("New Console with find()")) {
                    app.workspace.openConsole(config, database = node.database, initialSql = "db.getCollection('${node.info.name}').find({})")
                },
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(node.info.name) },
            )
        }
        is ExplorerNode.Records -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            val container = node.container
            listOfNotNull(
                ContextMenuItem(tr("Open Records")) { app.workspace.openRecords(config, container) }.takeIf { container.browsable },
                ContextMenuItem(tr("Show Definition")) { app.workspace.openRecords(config, container, definition = true) }.takeIf { container.browsable },
                ContextMenuItem(tr("New Console")) { app.workspace.openConsole(config, records = container.path) },
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(container.name) },
                ContextMenuItem(tr("Refresh")) { app.explorer.refresh(node.key) }.takeIf { container.hasChildren },
            )
        }
        is ExplorerNode.Keyspace -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            listOf(
                ContextMenuItem(tr("Browse Keys")) { app.workspace.openKeys(config, node.keyspace.index) },
                ContextMenuItem(tr("New Console")) { app.workspace.openConsole(config, keyspace = node.keyspace.index) },
            )
        }
        is ExplorerNode.Table -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            val ref = node.info.ref
            listOf(
                ContextMenuItem(tr("Open Data")) { app.workspace.openTable(config, ref) },
                ContextMenuItem(tr("Open Structure")) { app.workspace.openTable(config, ref, StructureSection.Columns) },
                ContextMenuItem(tr("Show DDL")) { app.workspace.openTable(config, ref, StructureSection.Ddl) },
                ContextMenuItem(tr("Edit Structure…")) { app.workspace.openTableDesigner(config, ref.namespace, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem(tr("Show in ER Diagram")) { app.workspace.openErDiagram(config, ref.namespace, ref) },
                ContextMenuItem(tr("Import Data…")) { app.openImport(config, ref.namespace, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem(tr("Generate Test Data…")) { app.openTestData(config, ref) }.takeUnless { config.readOnly || node.info.isView },
                ContextMenuItem(tr("Copy DDL")) { app.explorer.copyDdl(config, ref) },
                ContextMenuItem(tr("New Console with SELECT")) {
                    val name = SqlDialect.of(config.type).qualifiedName(ref)
                    app.workspace.openConsole(config, "SELECT * FROM $name;")
                },
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(ref.name) },
                ContextMenuItem(tr("Add to Chat")) {
                    app.agent.mention(Mention.Table(config.id, config.name, config.type.displayName, ref.namespace.displayName, ref.name))
                }.takeIf { app.aiAccess(config).sharesSchema },
                ContextMenuItem(tr("Refresh")) { app.explorer.refresh(node.key) },
            ).filterNotNull()
        }
        is ExplorerNode.SchemaObject -> {
            val config = app.connections.byId(node.connectionId) ?: return emptyList()
            val ref = node.info.ref
            listOf(
                ContextMenuItem(if (ref.kind == SchemaObjectKind.Sequence) tr("Open") else tr("Show Source")) { app.workspace.openSchemaObject(config, ref) },
                ContextMenuItem(tr("Copy Source")) { app.explorer.copySource(config, ref) },
                ContextMenuItem(tr("Open in Console")) { app.explorer.openSourceInConsole(config, ref) },
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(ref.name) },
            )
        }
        is ExplorerNode.Column -> {
            val config = app.connections.byId(node.table.connectionId)
            listOfNotNull(
                ContextMenuItem(tr("Copy Name")) { copyToClipboard(node.column.name) },
                config?.takeIf { app.aiAccess(it).sharesSchema }?.let {
                    ContextMenuItem(tr("Add to Chat")) {
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
                if (node.config.readOnly) Text(tr("read-only"), color = info, maxLines = 1, softWrap = false)
                app.databases.dataFile(node.config.id)?.let { DataFileBadge(it) }
                when (status) {
                    // The spinner stays on the row itself, so a collapsed connection still shows it's being opened.
                    is ConnectionStatus.Connecting -> Tooltip({ Text(connectingText(node.config, status)) }) { CircularProgressIndicator() }
                    is ConnectionStatus.Failed ->
                        Tooltip({ Text(friendlyError(status.message).headline) }) {
                            Text(tr("failed"), color = JewelTheme.globalColors.text.error, maxLines = 1, softWrap = false)
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
            is ExplorerNode.SchemaObject -> {
                val ref = node.info.ref
                NodeIcon(
                    when (ref.kind) {
                        SchemaObjectKind.Function -> AllIconsKeys.Nodes.Function
                        SchemaObjectKind.Procedure -> AllIconsKeys.Nodes.Method
                        SchemaObjectKind.Sequence -> AllIconsKeys.Nodes.Constant
                    }
                )
                NameAndDetail(ref.name, node.info.detail.orEmpty(), Modifier.weight(1f))
            }
            is ExplorerNode.Column -> {
                val column = node.column
                NodeIcon(if (column.primaryKey) AllIconsKeys.Nodes.FieldPK else AllIconsKeys.Nodes.DataColumn)
                NameAndDetail(column.name, column.typeName.lowercase() + if (column.nullable) "" else tr(" not null"), Modifier.weight(1f))
            }
            is ExplorerNode.Index -> {
                NodeIcon(AllIconsKeys.Nodes.FieldPK)
                NameAndDetail(
                    node.index.name,
                    (if (node.index.unique) tr("unique ") else "") + "(${node.index.columns.joinToString()})",
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
                val detail = listOfNotNull(node.info.kind.takeIf { it != "collection" }, node.info.documents?.let { tr("~{0} docs", it) }).joinToString(" · ")
                NameAndDetail(node.info.name, detail, Modifier.weight(1f))
            }
            is ExplorerNode.Keyspace -> {
                NodeIcon(AllIconsKeys.Nodes.DataSchema)
                val etcd = app.connections.byId(node.connectionId)?.type?.family == EngineFamily.Etcd
                NameAndDetail(if (etcd) tr("keys") else "db${node.keyspace.index}", node.keyspace.keys?.let { if (it == 1L) tr("1 key") else tr("{0} keys", it) }.orEmpty(), Modifier.weight(1f))
            }
            is ExplorerNode.Records -> {
                val container = node.container
                NodeIcon(if (container.hasChildren && !container.browsable) AllIconsKeys.Nodes.DataSchema else AllIconsKeys.Nodes.DataTables)
                val detail = listOfNotNull(container.kind.takeIf { it !in PlainKinds }, container.count?.let { tr("~{0}", it) }, container.detail).joinToString(" · ")
                NameAndDetail(container.name, detail, Modifier.weight(1f))
            }
            is ExplorerNode.Loading -> LoadingRow(app, node.parent, Modifier.weight(1f))
            is ExplorerNode.Failure -> FailureRow(app, node, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NodeIcon(key: IconKey) = Icon(key, contentDescription = null)

/** Container kinds too common to name beside each one. */
private val PlainKinds = setOf("index", "table", "keyspace", "collection")

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
                    Paradigm.Document -> tr("Loading databases…")
                    Paradigm.KeyValue -> tr("Loading keys…")
                    Paradigm.Search -> tr("Loading indices…")
                    Paradigm.WideColumn -> tr("Loading keyspaces…")
                    Paradigm.TimeSeries -> tr("Loading…")
                    Paradigm.Graph -> tr("Loading labels…")
                    else -> tr("Loading schemas…")
                }
            is ExplorerNode.Schema -> tr("Loading tables…")
            is ExplorerNode.DocumentDatabase -> tr("Loading collections…")
            is TableKey -> tr("Loading columns…")
            else -> tr("Loading…")
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
            Link(tr("Retry"), onClick = { app.explorer.refresh(node.parent) })
            if (config != null) Link(tr("Edit…"), onClick = { app.connections.openEdit(config) })
            Link(tr("Copy details"), onClick = { copyToClipboard(error.detail) })
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
        Tooltip({ Text(tr("New or imported connection")) }) {
            IconButton(onClick = { open = !open }) {
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AllIconsKeys.General.Add, tr("Add connection"))
                    Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(10.dp))
                }
            }
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                selectableItem(selected = false, iconKey = AllIconsKeys.General.Add, onClick = {
                    open = false
                    app.connections.openNew()
                }) { MenuEntry(tr("New Connection…"), tr("Connect to a database server")) }
                selectableItem(selected = false, iconKey = AllIconsKeys.Actions.MenuOpen, onClick = {
                    open = false
                    app.connections.chooseDataFiles()
                }) { MenuEntry(tr("Open Data File…"), tr("CSV, Excel, JSON — or drop files on the window")) }
                separator()
                selectableItem(selected = false, iconKey = AllIconsKeys.ToolbarDecorator.Import, onClick = {
                    open = false
                    app.connections.openImport()
                }) {
                    val hint = app.connections.importHint
                    MenuEntry(
                        if (hint == null) tr("Import from Other Tools…") else tr("Import {0} from {1}…", hint.count, hint.tools.joinToString(", ")),
                        tr("DBeaver, IDE data sources, .pgpass, .env"),
                    )
                }
            }
        }
    }
}

/** A menu item's title over a one-line hint, padded so every entry in the menu has the same height. */
@Composable
private fun MenuEntry(title: String, detail: String) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title)
        Text(detail, color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
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
        Text(tr("No connections yet"), color = JewelTheme.globalColors.text.info)
        val hint = app.connections.importHint
        if (hint != null) {
            Text(
                if (hint.count == 1) tr("1 connection found in {0}", hint.tools.joinToString(", ")) else tr("{0} connections found in {1}", hint.count, hint.tools.joinToString(", ")),
                color = JewelTheme.globalColors.text.info,
                textAlign = TextAlign.Center,
            )
            DefaultButton(onClick = app.connections::openImport) { Text(if (hint.count == 1) tr("Import It…") else tr("Import Them…")) }
            OutlinedButton(onClick = app.connections::openNew) { Text(tr("New Connection…")) }
            OutlinedButton(onClick = app.connections::chooseDataFiles) { Text(tr("Open Data File…")) }
        } else {
            OutlinedButton(onClick = app.connections::openNew) { Text(tr("New Connection…")) }
            OutlinedButton(onClick = app.connections::chooseDataFiles) { Text(tr("Open Data File…")) }
            Link(tr("Import from another tool…"), onClick = app.connections::openImport)
        }
    }
}

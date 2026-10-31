package dev.datagrok.app.explorer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateMapOf
import dev.datagrok.app.AppState
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.ForeignKeyInfo
import dev.datagrok.core.metadata.IndexInfo
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableInfo
import dev.datagrok.core.metadata.TableRef
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/** Nodes of the database explorer tree. [key] is the stable tree id that keeps nodes open across rebuilds. */
sealed interface ExplorerNode {
    val key: Any

    /** A folder of connections: one of the user's groups, or the favorites listed above them. */
    data class Group(val name: String, val favorites: Boolean, val children: List<ExplorerNode>) : ExplorerNode {
        override val key = GroupKey(name, favorites)
    }

    data class Connection(val config: ConnectionConfig) : ExplorerNode {
        override val key = ConnectionKey(config.id)
    }

    data class Schema(val connectionId: String, val namespace: Namespace) : ExplorerNode {
        override val key = this
    }

    data class Folder(val parent: Any, val label: String, val children: List<ExplorerNode>) : ExplorerNode {
        override val key = parent to label
    }

    data class Table(val connectionId: String, val info: TableInfo) : ExplorerNode {
        override val key = TableKey(connectionId, info.ref)
    }

    data class Column(val table: TableKey, val column: ColumnInfo) : ExplorerNode {
        override val key = table to column.name
    }

    data class Index(val table: TableKey, val index: IndexInfo) : ExplorerNode {
        override val key = table to "index:${index.name}"
    }

    data class ForeignKey(val table: TableKey, val foreignKey: ForeignKeyInfo) : ExplorerNode {
        override val key = table to "fk:${foreignKey.name}"
    }

    /** One database of a key-value store; opening it browses its keys. */
    data class Keyspace(val connectionId: String, val keyspace: dev.datagrok.core.driver.Keyspace) : ExplorerNode {
        override val key = connectionId to "keyspace:${keyspace.index}"
    }

    /** A database of a document store; its children are collections. */
    data class DocumentDatabase(val connectionId: String, val name: String) : ExplorerNode {
        override val key = this
    }

    data class Collection(val connectionId: String, val database: String, val info: dev.datagrok.core.driver.CollectionInfo) : ExplorerNode {
        override val key = connectionId to "collection:$database.${info.name}"
    }

    /** Stands in for [parent]'s children while they load, connecting first if need be. */
    data class Loading(val parent: Any) : ExplorerNode {
        override val key = parent to "loading"
    }

    /** Stands in for [parent]'s children when loading them failed; it offers a retry. */
    data class Failure(val parent: Any, val message: String) : ExplorerNode {
        override val key = parent to "failure"
    }
}

data class ConnectionKey(val id: String)

data class GroupKey(val name: String, val favorites: Boolean)

data class TableKey(val connectionId: String, val ref: TableRef)

private sealed interface Children {
    data object Loading : Children

    data class Loaded(val nodes: List<ExplorerNode>) : Children

    data class Failed(val message: String) : Children
}

/**
 * Explorer state. Children of connections, schemas and tables are loaded the first time their node is opened and
 * cached until refreshed.
 */
class ExplorerViewModel(private val app: AppState) {
    val treeState = TreeState(SelectableLazyListState(LazyListState()))
    private val children = mutableStateMapOf<Any, Children>()

    /** Narrows the tree to connections, and tables and columns already loaded, whose name matches. */
    val search = TextFieldState()

    /** Reads all state it depends on eagerly, so callers can wrap it in `derivedStateOf`. */
    fun buildTree(): Tree<ExplorerNode> {
        val snapshot = children.toMap()
        val query = search.text.toString().trim()
        val roots = topLevel(app.connections.profiles.toList()).mapNotNull { filtered(it, query, snapshot) }
        return buildTree { roots.forEach { add(it, snapshot, query) } }
    }

    /** Favorites first, then each group in name order, then connections without a group, keeping their saved order. */
    private fun topLevel(profiles: List<ConnectionConfig>): List<ExplorerNode> = buildList {
        val favorites = profiles.filter { it.favorite }
        if (favorites.isNotEmpty()) add(ExplorerNode.Group("Favorites", favorites = true, favorites.map(ExplorerNode::Connection)))
        profiles.filter { !it.favorite && it.group.isNotBlank() }
            .groupBy { it.group.trim() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (group, members) -> add(ExplorerNode.Group(group, favorites = false, members.map(ExplorerNode::Connection))) }
        profiles.filter { !it.favorite && it.group.isBlank() }.forEach { add(ExplorerNode.Connection(it)) }
    }

    /** The node's own name, the one search matches. */
    private fun ExplorerNode.searchName(): String? =
        when (this) {
            is ExplorerNode.Group -> name
            is ExplorerNode.Connection -> config.name
            is ExplorerNode.Schema -> namespace.displayName
            is ExplorerNode.Table -> info.ref.name
            is ExplorerNode.Column -> column.name
            is ExplorerNode.DocumentDatabase -> name
            is ExplorerNode.Collection -> info.name
            else -> null
        }

    private fun matches(node: ExplorerNode, query: String): Boolean =
        node.searchName()?.contains(query, ignoreCase = true) == true ||
            (node is ExplorerNode.Connection && listOf(node.config.host, node.config.database, node.config.type.displayName).any { it.contains(query, ignoreCase = true) })

    private fun childrenOf(node: ExplorerNode, snapshot: Map<Any, Children>): List<ExplorerNode> =
        when (node) {
            is ExplorerNode.Group -> node.children
            is ExplorerNode.Folder -> node.children
            else -> (snapshot[node.key] as? Children.Loaded)?.nodes.orEmpty()
        }

    /**
     * [node] if it or something loaded under it matches [query], else null. A match shows with everything under it; a
     * folder or group only keeps its matching children.
     */
    private fun filtered(node: ExplorerNode, query: String, snapshot: Map<Any, Children>): ExplorerNode? {
        if (query.isEmpty() || (matches(node, query) && node !is ExplorerNode.Group)) return node
        val kept = childrenOf(node, snapshot).mapNotNull { filtered(it, query, snapshot) }
        return when {
            node is ExplorerNode.Group -> if (matches(node, query)) node else node.copy(children = kept).takeIf { kept.isNotEmpty() }
            node is ExplorerNode.Folder -> node.copy(children = kept).takeIf { kept.isNotEmpty() }
            kept.isNotEmpty() -> node
            else -> null
        }
    }

    /** Nodes to open so every search match shows; only nodes already loaded, so searching never connects anything. */
    fun searchMatchAncestors(): Set<Any> {
        val query = search.text.toString().trim()
        if (query.isEmpty()) return emptySet()
        val snapshot = children.toMap()
        val keys = mutableSetOf<Any>()
        fun visit(node: ExplorerNode, path: List<Any>): Boolean {
            val below = childrenOf(node, snapshot)
            val childMatched = below.map { visit(it, path + node.key) }.any { it }
            if (childMatched) keys.addAll(path + node.key)
            return childMatched || matches(node, query)
        }
        topLevel(app.connections.profiles.toList()).forEach { visit(it, emptyList()) }
        return keys
    }

    private fun TreeGeneratorScope<ExplorerNode>.add(node: ExplorerNode, snapshot: Map<Any, Children>, searched: String) {
        // Below a match everything shows; elsewhere only the paths to matches do.
        val query = if (searched.isNotEmpty() && matches(node, searched)) "" else searched
        val childNodes: List<ExplorerNode>? =
            when (node) {
                is ExplorerNode.Group -> node.children
                is ExplorerNode.Folder -> node.children
                is ExplorerNode.Connection,
                is ExplorerNode.Schema,
                is ExplorerNode.DocumentDatabase,
                is ExplorerNode.Table ->
                    when (val loaded = snapshot[node.key]) {
                        is Children.Loaded -> loaded.nodes
                        is Children.Failed -> listOf(ExplorerNode.Failure(node.key, loaded.message))
                        Children.Loading,
                        null -> listOf(ExplorerNode.Loading(node.key))
                    }.let { loaded -> if (query.isEmpty()) loaded else loaded.mapNotNull { filtered(it, query, snapshot) } }
                else -> null
            }
        if (childNodes == null) addLeaf(node, node.key) else addNode(node, node.key) { childNodes.forEach { add(it, snapshot, query) } }
    }

    /** Called with the tree's open node ids; loads children of any newly opened lazy node. */
    fun onNodesOpened(keys: Set<Any>) = keys.forEach(::ensureLoaded)

    private fun ensureLoaded(key: Any) {
        if (children[key] is Children.Loaded || children[key] == Children.Loading) return
        val connectionId = connectionIdOf(key) ?: return
        val config = app.connections.byId(connectionId) ?: return
        children[key] = Children.Loading
        app.scope.launch {
            children[key] =
                runCatching { load(config, key) }
                    .fold(onSuccess = { Children.Loaded(it) }, onFailure = { Children.Failed(it.message ?: it.toString()) })
        }
    }

    private suspend fun load(config: ConnectionConfig, key: Any): List<ExplorerNode> {
        if (config.type.paradigm == Paradigm.Document) {
            val mongo = app.databases.documents(config)
            return when (key) {
                is ConnectionKey -> mongo.databases().map { ExplorerNode.DocumentDatabase(config.id, it) }
                is ExplorerNode.DocumentDatabase -> mongo.collections(key.name).map { ExplorerNode.Collection(config.id, key.name, it) }
                else -> emptyList()
            }
        }
        if (config.type.paradigm == Paradigm.KeyValue) {
            // Empty databases past the first add nothing but noise; the console's SELECT still reaches them.
            val keyspaces = app.databases.keyValue(config).keyspaces()
            return keyspaces.filter { it.index == 0 || (it.keys ?: 0) > 0 }.map { ExplorerNode.Keyspace(config.id, it) }
        }
        val database = app.databases.database(config)
        return when (key) {
            is ConnectionKey -> {
                val namespaces = database.namespaces()
                // A single namespace (SQLite's main, or a one-schema database) adds nothing: open it right away.
                if (namespaces.size == 1) app.scope.launch { treeState.openNodes(listOf(ExplorerNode.Schema(config.id, namespaces.single()).key)) }
                namespaces.map { ExplorerNode.Schema(config.id, it) }
            }
            is ExplorerNode.Schema -> {
                val (views, tables) = database.tables(key.namespace).partition { it.isView }
                listOfNotNull(
                    ExplorerNode.Folder(key, "tables", tables.map { ExplorerNode.Table(config.id, it) }).takeIf { tables.isNotEmpty() },
                    ExplorerNode.Folder(key, "views", views.map { ExplorerNode.Table(config.id, it) }).takeIf { views.isNotEmpty() },
                )
            }
            is TableKey -> {
                val details = database.tableDetails(key.ref)
                listOfNotNull(
                    ExplorerNode.Folder(key, "columns", details.columns.map { ExplorerNode.Column(key, it) }),
                    ExplorerNode.Folder(key, "indexes", details.indexes.map { ExplorerNode.Index(key, it) })
                        .takeIf { details.indexes.isNotEmpty() },
                    ExplorerNode.Folder(key, "foreign keys", details.foreignKeys.map { ExplorerNode.ForeignKey(key, it) })
                        .takeIf { details.foreignKeys.isNotEmpty() },
                )
            }
            else -> emptyList()
        }
    }

    fun copyDdl(config: ConnectionConfig, table: TableRef) {
        app.scope.launch {
            runCatching { app.databases.database(config).tableStructure(table).ddl }
                .onSuccess {
                    copyToClipboard(it)
                    app.status = "Copied DDL of ${table.name}"
                }
                .onFailure { app.status = "Couldn't read DDL of ${table.name}: ${it.message}" }
        }
    }

    /** Opens a connection's node, which connects and loads its schemas. */
    fun reveal(connectionId: String) {
        treeState.openNodes(listOf(ConnectionKey(connectionId)))
    }

    /** Drops cached children of [key] and reloads them if the node is open. */
    fun refresh(key: Any) {
        connectionIdOf(key)?.let(app::forgetSchema)
        children.remove(key)
        if (key is ConnectionKey) forget(key.id, keepConnection = true)
        if (key in treeState.openNodes) ensureLoaded(key)
    }

    fun disconnect(connectionId: String) = app.confirmDataFiles(listOf(connectionId)) { disconnectNow(connectionId) }

    /** Disconnects without asking about unsaved data files; callers confirm those first. */
    fun disconnectNow(connectionId: String) {
        app.databases.disconnect(connectionId)
        forget(connectionId)
    }

    /** Clears everything cached for a connection and collapses it. */
    fun forget(connectionId: String, keepConnection: Boolean = false) {
        if (!keepConnection) app.forgetSchema(connectionId)
        val stale = children.keys.filter { connectionIdOf(it) == connectionId && !(keepConnection && it is ConnectionKey) }
        stale.forEach(children::remove)
        if (!keepConnection) treeState.openNodes = treeState.openNodes.filterNot { connectionIdOf(it) == connectionId }.toSet()
    }

    fun connectionIdOf(key: Any): String? =
        when (key) {
            is ConnectionKey -> key.id
            is ExplorerNode.Schema -> key.connectionId
            is ExplorerNode.DocumentDatabase -> key.connectionId
            is TableKey -> key.connectionId
            is Pair<*, *> -> key.first?.let(::connectionIdOf)
            else -> null
        }
}

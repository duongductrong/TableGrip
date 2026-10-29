package dev.datagrok.app.explorer

import androidx.compose.foundation.lazy.LazyListState
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

    /** Reads all state it depends on eagerly, so callers can wrap it in `derivedStateOf`. */
    fun buildTree(): Tree<ExplorerNode> {
        val snapshot = children.toMap()
        val profiles = app.connections.profiles.toList()
        return buildTree {
            profiles.forEach { add(ExplorerNode.Connection(it), snapshot) }
        }
    }

    private fun TreeGeneratorScope<ExplorerNode>.add(node: ExplorerNode, snapshot: Map<Any, Children>) {
        val childNodes: List<ExplorerNode>? =
            when (node) {
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
                    }
                else -> null
            }
        if (childNodes == null) addLeaf(node, node.key) else addNode(node, node.key) { childNodes.forEach { add(it, snapshot) } }
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

    fun disconnect(connectionId: String) {
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

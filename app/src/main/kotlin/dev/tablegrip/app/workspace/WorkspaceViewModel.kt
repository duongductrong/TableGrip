package dev.tablegrip.app.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.activity.ActivityTab
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.table.StructureSection
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.documents.DocumentBrowserTab
import dev.tablegrip.app.keyvalue.CommandConsoleTab
import dev.tablegrip.app.keyvalue.KeyBrowserTab
import dev.tablegrip.app.schema.ErDiagramTab
import dev.tablegrip.app.schema.SchemaCompareTab
import dev.tablegrip.app.schema.SchemaObjectTab
import dev.tablegrip.app.schema.TableDesignerTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ClientApi
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.app.records.RecordBrowserTab
import dev.tablegrip.app.records.RecordConsoleTab
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.launch
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.SchemaObjectRef
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.session.SessionTab
import java.io.File

/** An editor tab in the main area. */
interface WorkspaceTab {
    val connectionId: String
    val title: String

    /** Work that closing the tab would lose: unsubmitted edits, an unsaved script or an open transaction. */
    val hasUnsavedWork: Boolean
        get() = false

    /** Asks the user what to do with [hasUnsavedWork]; calls [close] only once they chose to go ahead and it is done. */
    fun confirmClose(close: () -> Unit) = close()

    /** Work the session can't bring back after quitting, e.g. an open transaction; unsaved console text it can. */
    val losesWorkOnQuit: Boolean
        get() = hasUnsavedWork

    /** Like [confirmClose], before quitting: only asks about [losesWorkOnQuit]. */
    fun confirmQuit(proceed: () -> Unit) = confirmClose(proceed)

    /** How the tab reopens on the next start; null for tabs that don't. */
    fun toSession(): SessionTab? = null

    /** The grid in front, if any, with what it shows; the status bar aggregates its selection. */
    val activeGrid: Pair<GridModel, DataGridState>?
        get() = null

    /** Cancels running work and releases connections. */
    fun dispose()
}

/** The tabs of the main area: SQL consoles and table data viewers. */
class WorkspaceViewModel(private val app: AppState) {
    val tabs = mutableStateListOf<WorkspaceTab>()
    private var selectedTab by mutableStateOf<WorkspaceTab?>(null)

    /** Selecting a tab also makes its connection current, and brings the tabs back if settings were showing. */
    var selected: WorkspaceTab?
        get() = selectedTab
        set(value) {
            selectedTab = value
            if (value != null) app.settingsOpen = false
            value?.let { app.currentConnectionId = it.connectionId }
        }
    private var consoleCount = 0
    private var tabCount = 0L
    private val keys = mutableMapOf<WorkspaceTab, Long>()

    /** A stable identity for [tab] while it is open, for keying the tab strip. */
    fun keyOf(tab: WorkspaceTab): Long = keys.getValue(tab)

    /**
     * A SQL console, or a command console for engines without SQL: [keyspace] picks a key-value store's database,
     * [database] a document store's; [initialSql] is the first command to show. For engines browsed as records, [records]
     * names the container whose sample statement the console starts with.
     */
    fun openConsole(config: ConnectionConfig, initialSql: String = "", keyspace: Int = 0, database: String? = null, file: File? = null, records: List<String>? = null) {
        consoleCount++
        val name = if (consoleCount == 1) config.name else "${config.name} ($consoleCount)"
        open(
            when (config.type.api) {
                ClientApi.KeyValue -> CommandConsoleTab(app, config, name, keyspace = keyspace)
                ClientApi.Document -> CommandConsoleTab(app, config, name, database = database ?: config.database.ifBlank { "test" }, initial = initialSql)
                ClientApi.Records -> RecordConsoleTab(app, config, name, initialSql).also { tab ->
                    // The engine's sample statement, once connected, unless the caller gave one.
                    if (initialSql.isEmpty()) app.scope.launch {
                        runCatching { app.databases.records(config).sampleStatement(records.orEmpty()) }.onSuccess { sample ->
                            if (tab.editor.text.isEmpty()) tab.editor.setTextAndPlaceCursorAtEnd(sample)
                        }
                    }
                }
                else -> ConsoleTab(app, config, name, initialSql, file)
            }
        )
    }

    /** Focuses the collection's document browser if it is already open. */
    fun openCollection(config: ConnectionConfig, database: String, collection: String) {
        tabs.firstOrNull { it is DocumentBrowserTab && it.connectionId == config.id && it.database == database && it.collection == collection }?.let {
            bringToFront(it)
            return
        }
        open(DocumentBrowserTab(app, config, database, collection))
    }

    /** Focuses the container's record browser if it is already open; [definition] switches it to the definition. */
    fun openRecords(config: ConnectionConfig, container: RecordContainer, definition: Boolean = false) {
        val existing = tabs.firstOrNull { it is RecordBrowserTab && it.connectionId == config.id && it.path == container.path } as RecordBrowserTab?
        if (existing != null) {
            bringToFront(existing)
            if (definition) existing.showDefinition()
            return
        }
        open(RecordBrowserTab(app, config, container, definitionFirst = definition))
    }

    /** Focuses the key browser of [keyspace] if it is already open. */
    fun openKeys(config: ConnectionConfig, keyspace: Int) {
        tabs.firstOrNull { it is KeyBrowserTab && it.connectionId == config.id && it.keyspace == keyspace }?.let {
            bringToFront(it)
            return
        }
        open(KeyBrowserTab(app, config, keyspace))
    }

    /** Focuses the table's tab if it is already open; [structure] switches it to the structure view. */
    fun openTable(config: ConnectionConfig, table: TableRef, structure: StructureSection? = null) {
        val existing = tabs.firstOrNull { it is TableDataTab && it.connectionId == config.id && it.table == table } as TableDataTab?
        if (existing != null) bringToFront(existing)
        val tab = existing ?: TableDataTab(app, config, table).also(::open)
        when {
            structure != null -> tab.showStructure(structure)
            existing == null -> tab.showData()
        }
    }

    /** A function's, procedure's or sequence's properties and source; focuses its tab if it is already open. */
    fun openSchemaObject(config: ConnectionConfig, ref: SchemaObjectRef) {
        tabs.firstOrNull { it is SchemaObjectTab && it.connectionId == config.id && it.ref == ref }?.let {
            bringToFront(it)
            return
        }
        open(SchemaObjectTab(app, config, ref))
    }

    /** Designs [table] of [namespace], or a new table there when null; focuses an open designer of the same table. */
    fun openTableDesigner(config: ConnectionConfig, namespace: Namespace, table: TableRef? = null) {
        if (table != null) {
            tabs.firstOrNull { it is TableDesignerTab && it.connectionId == config.id && it.tableRef == table }?.let {
                bringToFront(it)
                return
            }
        }
        open(TableDesignerTab(app, config, namespace, table))
    }

    /** Compares [namespace] of [config] (the source) with a target picked in the tab. */
    fun openSchemaCompare(config: ConnectionConfig?, namespace: Namespace?) {
        open(SchemaCompareTab(app, config, namespace))
    }

    /** An ER diagram of [namespace], or of the tables around [focus]; focuses one already open for the same. */
    fun openErDiagram(config: ConnectionConfig, namespace: Namespace, focus: TableRef? = null) {
        tabs.firstOrNull { it is ErDiagramTab && it.connectionId == config.id && it.namespace == namespace && it.focus == focus }?.let {
            bringToFront(it)
            return
        }
        open(ErDiagramTab(app, config, namespace, focus))
    }

    /** The server's sessions and locks; focuses the connection's activity tab if it is already open. */
    fun openActivity(config: ConnectionConfig) {
        tabs.firstOrNull { it is ActivityTab && it.connectionId == config.id }?.let {
            bringToFront(it)
            return
        }
        open(ActivityTab(app, config))
    }

    /** Closes [tab], first asking what to do with its unsaved work; [then] runs once it is closed. */
    fun requestClose(tab: WorkspaceTab, then: () -> Unit = {}) {
        if (!tab.hasUnsavedWork) {
            close(tab)
            then()
            return
        }
        // The tab's own view shows the question, so bring it to front.
        selected = tab
        tab.confirmClose {
            close(tab)
            then()
        }
    }

    /**
     * Closes the tabs matching [filter], asking about each one with unsaved work in turn; [then] runs once all are
     * closed. Cancelling any question stops here and keeps the remaining tabs open.
     */
    fun requestCloseAll(filter: (WorkspaceTab) -> Boolean = { true }, then: () -> Unit) {
        val unsaved = tabs.firstOrNull { filter(it) && it.hasUnsavedWork }
        if (unsaved != null) {
            requestClose(unsaved) { requestCloseAll(filter, then) }
            return
        }
        tabs.filter(filter).forEach(::close)
        then()
    }

    /**
     * Before quitting: asks about each tab whose work quitting would lose, one at a time, then runs [then]. Tabs stay
     * open, so the session still has them. Cancelling any question cancels the quit.
     */
    fun confirmQuit(then: () -> Unit, from: Int = 0) {
        val next = tabs.drop(from).firstOrNull { it.losesWorkOnQuit } ?: return then()
        selected = next
        next.confirmQuit { confirmQuit(then, tabs.indexOf(next) + 1) }
    }

    /** Reopens a console from the last session, with its text, file and caret. */
    fun restoreConsole(config: ConnectionConfig, saved: SessionTab.Console) {
        consoleCount++
        val name = if (consoleCount == 1) config.name else "${config.name} ($consoleCount)"
        open(ConsoleTab(app, config, name, saved.text, saved.file?.let(::File), savedText = saved.savedText, caret = saved.caret), reveal = false)
    }

    /** Reopens a table from the last session; its rows load once it's shown. */
    fun restoreTable(config: ConnectionConfig, saved: SessionTab.Table) {
        val ref = TableRef(Namespace(saved.catalog, saved.schema), saved.name)
        open(TableDataTab(app, config, ref).also { it.restore(saved.structure, saved.filter) }, reveal = false)
    }

    /** Reopens a record browser from the last session, with its query. */
    fun restoreRecords(config: ConnectionConfig, saved: SessionTab.Records) {
        val container = RecordContainer(saved.path, saved.kind)
        open(RecordBrowserTab(app, config, container, definitionFirst = saved.definition, initialQuery = saved.query), reveal = false)
    }

    fun restoreRecordConsole(config: ConnectionConfig, saved: SessionTab.RecordConsole) {
        consoleCount++
        val name = if (consoleCount == 1) config.name else "${config.name} ($consoleCount)"
        open(RecordConsoleTab(app, config, name, saved.text), reveal = false)
    }

    /** Reopens a document store's shell console from the last session, in the database it was in. */
    fun restoreShellConsole(config: ConnectionConfig, saved: SessionTab.ShellConsole) {
        consoleCount++
        val name = if (consoleCount == 1) config.name else "${config.name} ($consoleCount)"
        open(CommandConsoleTab(app, config, name, database = saved.database, initial = saved.text), reveal = false)
    }

    fun close(tab: WorkspaceTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.dispose()
        tabs.removeAt(index)
        keys -= tab
        if (selected == tab) selected = tabs.getOrNull(index) ?: tabs.lastOrNull()
    }

    /** Moves the tab at [from] to [to], shifting the ones in between. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabs.add(to, tabs.removeAt(from))
    }

    /** Selects the tab [offset] places from the current one, wrapping around. */
    fun selectRelative(offset: Int) {
        if (tabs.isEmpty()) return
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        selected = tabs[Math.floorMod(index + offset, tabs.size)]
    }

    fun disposeAll() = tabs.forEach { it.dispose() }

    /** Brings an open tab to the front, showing the main window if only file windows were showing. */
    private fun bringToFront(tab: WorkspaceTab) {
        selected = tab
        app.mainWindowVisible = true
    }

    /** [reveal] shows the main window if only file windows were showing; restoring the session leaves it as it is. */
    private fun open(tab: WorkspaceTab, reveal: Boolean = true) {
        keys[tab] = tabCount++
        tabs += tab
        selected = tab
        if (reveal) app.mainWindowVisible = true
    }
}

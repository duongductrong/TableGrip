package dev.tablegrip.app.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.datafile.DataFileConnection
import dev.tablegrip.core.metadata.TableRef
import java.io.File
import kotlinx.coroutines.launch

/**
 * A data file in a window of its own, as when it's opened from the file manager: its tables (a CSV's one, a workbook's
 * sheets) as grids to browse and edit, saved back to the file. It is the file's data file connection, shared with the
 * main window, so the file also shows in the explorer there.
 */
class DataFileWindow(private val app: AppState, val file: File) {
    var config by mutableStateOf<ConnectionConfig?>(null)
        private set
    /** The file's tables, in its own order: sheets as the workbook lists them. */
    var tables by mutableStateOf<List<TableRef>>(emptyList())
        private set
    var selected by mutableStateOf<TableDataTab?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped to bring the window to the front, e.g. when the file is opened again. */
    var raiseRequests by mutableStateOf(0)
        private set

    private val tabs = mutableMapOf<TableRef, TableDataTab>()

    val title: String
        get() = file.name

    val connectionId: String?
        get() = config?.id

    /** Connects to the file (saving a profile for it the first time) and shows its first table. */
    fun load() {
        if (loading) return
        loading = true
        error = null
        app.scope.launch {
            try {
                val profile = config ?: app.connections.dataFileProfile(file)
                if (profile == null) {
                    error = app.status
                    return@launch
                }
                config = profile
                runCatching { readTables(profile) }
                    .onSuccess { found ->
                        tables = found
                        found.firstOrNull()?.let(::select)
                    }
                    .onFailure { error = "Couldn't open ${file.name}: ${it.message}" }
            } finally {
                loading = false
            }
        }
    }

    private suspend fun readTables(config: ConnectionConfig): List<TableRef> {
        val database = app.databases.database(config)
        val namespace = database.namespaces().firstOrNull() ?: return emptyList()
        val tables = database.tables(namespace).map { it.ref }
        // Metadata lists tables by name; tabs follow the file, like the workbook's own.
        val order = (database as? DataFileConnection)?.tableOrder().orEmpty().withIndex().associate { (index, name) -> name to index }
        return tables.sortedBy { order[it.name] ?: Int.MAX_VALUE }
    }

    fun select(table: TableRef) {
        val config = config ?: return
        selected = tabs.getOrPut(table) { TableDataTab(app, config, table).also { it.showData() } }
    }

    /**
     * After the file was read in again: sheets added or removed on disk show up, and the grids without unsaved edits
     * show the new rows.
     */
    fun reloaded() {
        val config = config ?: return
        app.scope.launch {
            val found = runCatching { readTables(config) }.getOrNull() ?: return@launch
            tables = found
            (tabs.keys - found.toSet()).forEach { gone -> tabs.remove(gone)?.dispose() }
            tabs.values.filter { !it.hasChanges }.forEach { it.refresh() }
            if (selected?.table !in found) selected = null
            if (selected == null) found.firstOrNull()?.let(::select)
        }
    }

    fun raise() {
        raiseRequests++
    }

    /** Edits in a grid that weren't saved to the file yet. */
    val hasUnsavedWork: Boolean
        get() = tabs.values.any { it.hasUnsavedWork }

    /** Asks about each grid's unsaved edits in turn, showing it first; cancelling any keeps the window open. */
    fun confirmClose(then: () -> Unit) {
        val unsaved = tabs.values.firstOrNull { it.hasUnsavedWork } ?: return then()
        selected = unsaved
        raise()
        unsaved.confirmClose { confirmClose(then) }
    }

    fun dispose() {
        tabs.values.forEach { it.dispose() }
        tabs.clear()
    }
}

/** The data files open in windows of their own, next to the main window. */
class FileWindows(private val app: AppState) {
    val windows = mutableStateListOf<DataFileWindow>()

    /** The one last in front; it shows the app's dialogs while the main window is hidden. */
    var focused by mutableStateOf<DataFileWindow?>(null)

    val dialogHost: DataFileWindow?
        get() = focused?.takeIf { it in windows } ?: windows.firstOrNull()

    /** Opens each file in a window, or brings its window to the front if it has one already. */
    fun open(files: List<File>) {
        for (file in files) {
            val absolute = file.absoluteFile.normalize()
            val existing = windows.firstOrNull { it.file == absolute }
            if (existing != null) {
                existing.raise()
                continue
            }
            val window = DataFileWindow(app, absolute)
            windows += window
            focused = window
            window.load()
        }
    }

    /**
     * Closes [window] once its unsaved edits are dealt with, then runs [then]. The file's connection closes too when
     * nothing else shows it, which asks first if it holds changes it couldn't write to the file.
     */
    fun requestClose(window: DataFileWindow, then: () -> Unit = {}) =
        window.confirmClose {
            val id = window.connectionId
            val disconnect = id != null && !inUse(id, except = window)
            fun close() {
                windows.remove(window)
                window.dispose()
                if (disconnect) app.explorer.disconnectNow(id!!)
                then()
            }
            if (disconnect) app.confirmDataFiles(listOf(id!!), ::close) else close()
        }

    /** Before quitting: asks about each window's unsaved edits; cancelling any cancels the quit. */
    fun confirmQuit(then: () -> Unit, from: Int = 0) {
        val next = windows.drop(from).firstOrNull { it.hasUnsavedWork } ?: return then()
        next.confirmClose { confirmQuit(then, windows.indexOf(next) + 1) }
    }

    /** A data file was read in again after it changed on disk. */
    fun reloaded(connectionId: String) = windows.filter { it.connectionId == connectionId }.forEach { it.reloaded() }

    private fun inUse(connectionId: String, except: DataFileWindow): Boolean =
        app.workspace.tabs.any { it.connectionId == connectionId } || windows.any { it !== except && it.connectionId == connectionId }

    fun disposeAll() = windows.forEach { it.dispose() }
}

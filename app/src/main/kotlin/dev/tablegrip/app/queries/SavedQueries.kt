package dev.tablegrip.app.queries

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.core.queries.QueryLibrary
import dev.tablegrip.core.queries.SavedQuery
import java.awt.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks for a name and folder to save [tab]'s script under, then runs [then]. */
class SaveQueryRequest(val tab: ConsoleTab, val then: () -> Unit)

/** The query library as the UI sees it: the list, opening queries in consoles, and the save / rename / delete dialogs. */
class SavedQueries(private val app: AppState, val library: QueryLibrary = QueryLibrary()) {
    val queries = mutableStateListOf<SavedQuery>()

    var saveRequest by mutableStateOf<SaveQueryRequest?>(null)
        private set
    var renaming by mutableStateOf<SavedQuery?>(null)
    var pendingDelete by mutableStateOf<SavedQuery?>(null)

    /** Folders already in use, for the save dialog to offer. */
    val folders: List<String>
        get() = queries.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()

    init {
        reload()
    }

    fun reload() {
        app.scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { library.list() }.getOrDefault(emptyList()) }
            queries.clear()
            queries.addAll(loaded)
        }
    }

    /** Whether [file] is one of the library's queries, e.g. a console that was opened from it. */
    fun contains(file: java.io.File): Boolean = file.toPath().toAbsolutePath().startsWith(library.root.toAbsolutePath())

    /**
     * Opens [query] in a console bound to its file, so ⌘S saves back to it; focuses that console if it's already open.
     * It runs on its own connection, else the current one.
     */
    fun open(query: SavedQuery) {
        val open = app.workspace.tabs.firstOrNull { it is ConsoleTab && it.file?.toPath() == query.file }
        if (open != null) {
            app.workspace.selected = open
            return
        }
        val config = query.connection?.let { name -> app.connections.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: app.currentConnection
        if (config == null) {
            app.status = tr("Add a connection to run {0}", query.name)
            return
        }
        app.workspace.openConsole(config, query.sql, file = query.file.toFile())
    }

    fun requestSave(tab: ConsoleTab, then: () -> Unit = {}) {
        saveRequest = SaveQueryRequest(tab, then)
    }

    fun cancelSave() {
        saveRequest = null
    }

    /**
     * Saves the requested console's script as [name] in [folder]. Linking it to the console's connection puts a
     * `-- connection:` line at the top of the script, which is how the file remembers it.
     */
    fun save(name: String, folder: String, linkConnection: Boolean) {
        val request = saveRequest ?: return
        saveRequest = null
        val tab = request.tab
        val text = tab.editor.text.toString()
        val linked = QueryLibrary.withConnection(text, tab.config.name.takeIf { linkConnection })
        if (linked != text) tab.editor.setTextAndPlaceCursorAtEnd(linked)
        tab.writeTo(library.fileFor(name, folder).toFile(), request.then)
    }

    /** Saves the requested console to a file of the user's choosing instead, outside the library. */
    fun saveToFile() {
        val request = saveRequest ?: return
        saveRequest = null
        request.tab.saveToFile(request.then)
    }

    fun rename(query: SavedQuery, name: String, folder: String) {
        renaming = null
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { library.rename(query, name, folder) } }
                .onSuccess { renamed ->
                    app.workspace.tabs.filterIsInstance<ConsoleTab>().filter { it.file?.toPath() == query.file }.forEach { it.moveTo(renamed.file.toFile()) }
                    app.status = tr("Renamed {0} to {1}", query.name, renamed.name)
                }
                .onFailure { app.status = tr("Couldn't rename {0}: {1}", query.name, it.message) }
            reload()
        }
    }

    fun delete(query: SavedQuery) {
        pendingDelete = null
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { library.delete(query) } }
                .onSuccess { app.status = tr("Deleted {0}", query.name) }
                .onFailure { app.status = tr("Couldn't delete {0}: {1}", query.name, it.message) }
            reload()
        }
    }

    /** Shows the query's file (or the library folder) in Finder / Explorer. */
    fun reveal(query: SavedQuery? = null) {
        val target = (query?.file ?: library.root.also { it.toFile().mkdirs() }).toFile()
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() } ?: return
        runCatching {
            if (query != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(target) else desktop.open(target.parentFile.takeIf { query != null } ?: target)
        }.onFailure { app.status = tr("Couldn't open {0}: {1}", target.path, it.message) }
    }
}

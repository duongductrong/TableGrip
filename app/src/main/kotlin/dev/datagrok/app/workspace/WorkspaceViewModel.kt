package dev.datagrok.app.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.metadata.TableRef

/** An editor tab in the main area. */
interface WorkspaceTab {
    val connectionId: String
    val title: String

    /** Cancels running work and releases connections. */
    fun dispose()
}

/** The tabs of the main area: SQL consoles and table data viewers. */
class WorkspaceViewModel(private val app: AppState) {
    val tabs = mutableStateListOf<WorkspaceTab>()
    var selected by mutableStateOf<WorkspaceTab?>(null)
    private var consoleCount = 0

    fun openConsole(config: ConnectionConfig, initialSql: String = "") {
        consoleCount++
        open(ConsoleTab(app, config, title = if (consoleCount == 1) config.name else "${config.name} ($consoleCount)", initialSql))
    }

    /** Focuses the table's tab if it is already open. */
    fun openTable(config: ConnectionConfig, table: TableRef) {
        tabs.firstOrNull { it is TableDataTab && it.connectionId == config.id && it.table == table }?.let {
            selected = it
            return
        }
        open(TableDataTab(app, config, table).also { it.reload() })
    }

    /** Closes [tab], first asking to discard unsaved table edits. */
    fun requestClose(tab: WorkspaceTab) {
        if (tab is TableDataTab && tab.hasChanges) {
            selected = tab
            tab.guard { close(tab) }
        } else {
            close(tab)
        }
    }

    fun close(tab: WorkspaceTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.dispose()
        tabs.removeAt(index)
        if (selected == tab) selected = tabs.getOrNull(index) ?: tabs.lastOrNull()
    }

    fun closeTabsOf(connectionId: String) = tabs.filter { it.connectionId == connectionId }.forEach(::close)

    fun disposeAll() = tabs.forEach { it.dispose() }

    private fun open(tab: WorkspaceTab) {
        tabs += tab
        selected = tab
    }
}

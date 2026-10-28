package dev.datagrok.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.connections.ConnectionsViewModel
import dev.datagrok.app.connections.DatabaseRegistry
import dev.datagrok.app.completion.SchemaCache
import dev.datagrok.app.explorer.ExplorerViewModel
import dev.datagrok.app.export.ExportRequest
import dev.datagrok.app.settings.SettingsPages
import dev.datagrok.app.workspace.WorkspaceViewModel
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

enum class LeftTool { Explorer, History }

/** Root of the UI state: owns app-wide services and wires the feature view models together. */
class AppState(
    private val settingsStore: SettingsStore = SettingsStore(),
    connectionStore: ConnectionStore = ConnectionStore(),
    val history: QueryHistory = QueryHistory(),
) {
    /** Lives as long as the app; everything here runs on the UI thread unless it hops to IO itself. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var settings by mutableStateOf(settingsStore.load())
        private set

    val theme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.name == settings.theme } ?: AppTheme.System

    var status by mutableStateOf("Ready")

    /** Tool window shown in the left island; null hides it. */
    var leftTool by mutableStateOf<LeftTool?>(LeftTool.Explorer)
    /** Shows the settings screen in place of the tool window and editor tabs. */
    var settingsOpen by mutableStateOf(false)
    /** The settings page shown when the dialog opens; kept so it reopens where the user left off. */
    var settingsPage by mutableStateOf(SettingsPages.all.first().id)
    var shortcutsOpen by mutableStateOf(false)
    var exportRequest by mutableStateOf<ExportRequest?>(null)

    /** The connection the title bar shows and new consoles use; follows the selected tab. */
    var currentConnectionId by mutableStateOf<String?>(null)

    val currentConnection: ConnectionConfig?
        get() = currentConnectionId?.let(connections::byId) ?: connections.profiles.firstOrNull()

    val connections = ConnectionsViewModel(this, connectionStore)
    val databases = DatabaseRegistry(scope, connections::storedPassword, connections::storedSshSecret)
    val explorer = ExplorerViewModel(this)
    val workspace = WorkspaceViewModel(this)

    private val schemaCaches = mutableMapOf<String, SchemaCache>()

    /** Completion metadata for a connection, shared by its consoles and table filters. */
    fun schemaCache(config: ConnectionConfig): SchemaCache = schemaCaches.getOrPut(config.id) { SchemaCache(scope, databases, config) }

    /** Drops cached completion metadata, e.g. after DDL or when the connection's settings change. */
    fun forgetSchema(connectionId: String) {
        schemaCaches.remove(connectionId)?.invalidate()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings)
        val snapshot = settings
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.save(snapshot) }.onFailure { log.warn("Failed to save settings", it) }
        }
    }

    fun toggleTheme() = updateSettings { it.copy(theme = theme.toggled().name) }

    /** Makes [config] current: reveals it in the explorer (connecting it) without changing open tabs. */
    fun pickConnection(config: ConnectionConfig) {
        currentConnectionId = config.id
        settingsOpen = false
        workspace.tabs.lastOrNull { it.connectionId == config.id }?.let { workspace.selected = it }
        leftTool = LeftTool.Explorer
        explorer.reveal(config.id)
    }

    /** From the settings screen, a tool button goes back to the workspace with that tool shown. */
    fun toggleTool(tool: LeftTool) {
        leftTool = if (leftTool == tool && !settingsOpen) null else tool
        settingsOpen = false
    }

    /** Quits via [exit] once every tab with unsaved work has been saved or discarded; does nothing if the user cancels. */
    fun requestExit(exit: () -> Unit) =
        workspace.requestCloseAll {
            dispose()
            exit()
        }

    fun dispose() {
        workspace.disposeAll()
        databases.closeAll()
        scope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppState::class.java)
    }
}

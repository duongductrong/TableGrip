package dev.datagrok.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.connections.ConnectionsViewModel
import dev.datagrok.app.connections.DatabaseRegistry
import dev.datagrok.app.explorer.ExplorerViewModel
import dev.datagrok.app.workspace.WorkspaceViewModel
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

    val connections = ConnectionsViewModel(this, connectionStore)
    val databases = DatabaseRegistry(scope, connections::storedPassword)
    val explorer = ExplorerViewModel(this)
    val workspace = WorkspaceViewModel(this)

    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings)
        val snapshot = settings
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.save(snapshot) }.onFailure { log.warn("Failed to save settings", it) }
        }
    }

    fun cycleTheme() = updateSettings { it.copy(theme = theme.next().name) }

    fun dispose() {
        workspace.disposeAll()
        databases.closeAll()
        scope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppState::class.java)
    }
}

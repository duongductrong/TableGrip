package dev.datagrok.app.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.driver.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionDialogRequest(val existing: ConnectionConfig?)

/** Saved connection profiles and the dialogs that edit them. */
class ConnectionsViewModel(private val app: AppState, private val store: ConnectionStore) {
    val profiles = mutableStateListOf<ConnectionConfig>()

    /** Non-null while the connection dialog is open; [ConnectionDialogRequest.existing] is null for a new one. */
    var dialog by mutableStateOf<ConnectionDialogRequest?>(null)
        private set

    /** Connection waiting for the user to confirm its deletion. */
    var pendingDelete by mutableStateOf<ConnectionConfig?>(null)

    init {
        runCatching { store.load() }
            .onSuccess { profiles.addAll(it) }
            .onFailure { app.status = "Failed to load connections: ${it.message}" }
    }

    fun byId(id: String): ConnectionConfig? = profiles.firstOrNull { it.id == id }

    fun openNew() {
        dialog = ConnectionDialogRequest(existing = null)
    }

    fun openEdit(config: ConnectionConfig) {
        dialog = ConnectionDialogRequest(existing = config)
    }

    fun closeDialog() {
        dialog = null
    }

    /** Reads the stored password off the UI thread: the OS keychain may block or prompt. */
    suspend fun storedPassword(id: String): String? = withContext(Dispatchers.IO) { store.password(id) }

    suspend fun test(config: ConnectionConfig, password: String): Result<Unit> = runCatching { Database.test(config, password) }

    suspend fun save(config: ConnectionConfig, password: String): Result<Unit> = runCatching {
        val previous = byId(config.id)
        val updated = withContext(Dispatchers.IO) { store.save(config, password) }
        replaceAll(updated)
        // Settings may have changed: drop the live connection so the next use reconnects with the new ones.
        if (previous != null) {
            app.databases.disconnect(config.id)
            app.explorer.forget(config.id)
        }
        dialog = null
    }

    fun delete(config: ConnectionConfig) {
        pendingDelete = null
        app.workspace.closeTabsOf(config.id)
        app.databases.disconnect(config.id)
        app.explorer.forget(config.id)
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.delete(config.id) } }
                .onSuccess {
                    replaceAll(it)
                    app.status = "Deleted ${config.name}"
                }
                .onFailure { app.status = "Failed to delete ${config.name}: ${it.message}" }
        }
    }

    private fun replaceAll(configs: List<ConnectionConfig>) {
        profiles.clear()
        profiles.addAll(configs)
    }
}

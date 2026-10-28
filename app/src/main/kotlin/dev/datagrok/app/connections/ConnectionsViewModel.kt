package dev.datagrok.app.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.driver.DocumentStore
import dev.datagrok.core.driver.KeyValueStore
import dev.datagrok.core.driver.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionDialogRequest(val existing: ConnectionConfig?)

/** What Test Connection learned: the server's own description, or a better-fitting engine in its family. */
data class TestOutcome(val server: String? = null, val suggested: DatabaseType? = null)

/** Saved connection profiles and the dialogs that edit them. */
class ConnectionsViewModel(private val app: AppState, private val store: ConnectionStore) {
    val profiles = mutableStateListOf<ConnectionConfig>()

    val drivers: DriverDownloads
        get() = app.drivers

    /** Non-null while the connection dialog is open; [ConnectionDialogRequest.existing] is null for a new one. */
    var dialog by mutableStateOf<ConnectionDialogRequest?>(null)
        private set

    /** Connection waiting for the user to confirm its deletion. */
    var pendingDelete by mutableStateOf<ConnectionConfig?>(null)

    /** A saved connection that couldn't connect because its driver isn't installed; the user is asked to get it. */
    var driverPrompt by mutableStateOf<ConnectionConfig?>(null)

    fun askForDriver(config: ConnectionConfig) {
        if (driverPrompt == null && dialog == null) driverPrompt = config
    }

    /** Saves a profile whose driver the user switched while being asked for it (e.g. to MariaDB Connector/J). */
    fun updateDriver(config: ConnectionConfig) {
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.save(config, password = null, sshSecret = null) } }
                .onSuccess(::replaceAll)
                .onFailure { app.status = "Failed to save ${config.name}: ${it.message}" }
        }
    }

    /** Connects [config] again once its driver is there. */
    fun retryAfterDriver(config: ConnectionConfig) {
        driverPrompt = null
        app.explorer.refresh(dev.datagrok.app.explorer.ConnectionKey(config.id))
        app.pickConnection(config)
    }

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

    suspend fun storedSshSecret(id: String): String? = withContext(Dispatchers.IO) { store.sshSecret(id) }

    /**
     * Waits for a driver download the user started; fails if the driver is still missing. Succeeds with the engine the
     * server really is when that differs from the profile's type.
     */
    suspend fun test(config: ConnectionConfig, password: String, sshSecret: String): Result<TestOutcome> = runCatching {
        app.drivers.ensure(config)
        when (config.type.paradigm) {
            Paradigm.KeyValue -> TestOutcome(server = KeyValueStore.test(config, password, sshSecret, app.drivers.library))
            Paradigm.Document -> TestOutcome(server = DocumentStore.test(config, password, sshSecret, app.drivers.library))
            else -> TestOutcome(suggested = Database.test(config, password, sshSecret, app.drivers.library))
        }
    }

    /** A blank SSH secret removes the stored one, as does turning SSH off. */
    suspend fun save(config: ConnectionConfig, password: String, sshSecret: String): Result<Unit> = runCatching {
        val previous = byId(config.id)
        val updated = withContext(Dispatchers.IO) { store.save(config, password, if (config.ssh == null) "" else sshSecret) }
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
        // Unsaved work in the connection's tabs gets a chance to be saved; cancelling keeps the connection.
        app.workspace.requestCloseAll({ it.connectionId == config.id }) { deleteNow(config) }
    }

    private fun deleteNow(config: ConnectionConfig) {
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

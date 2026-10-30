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
import dev.datagrok.core.imports.ConnectionImport
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

    /** Groups in use, for the Move to Group dialog to offer. */
    val groups: List<String>
        get() = profiles.map { it.group }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    /** A connection waiting for the user to choose its group. */
    var moving by mutableStateOf<ConnectionConfig?>(null)

    fun moveToGroup(config: ConnectionConfig, group: String) {
        moving = null
        updateProfile(config.copy(group = group.trim()))
    }

    fun toggleFavorite(config: ConnectionConfig) = updateProfile(config.copy(favorite = !config.favorite))

    /** Saves a change that doesn't touch how to connect, e.g. its group; the live connection stays as it is. */
    fun updateProfile(config: ConnectionConfig) = updateDriver(config)

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

    /** Non-null while the Import Connections dialog is open. */
    var importing by mutableStateOf<ConnectionImportState?>(null)
        private set

    fun openImport() {
        if (importing == null) importing = ConnectionImportState(this, app.scope).also { it.scanThisComputer() }
    }

    fun closeImport() {
        importing = null
    }

    /** Connections other tools on this computer have and Datagrok doesn't; null until looked for, or if there are none. */
    var importHint by mutableStateOf<ImportHint?>(null)
        private set

    /** Looks for connections to import, so the empty explorer can offer them instead of a blank form. */
    fun lookForImports() {
        app.scope.launch {
            val hint =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val saved = profiles.map(ConnectionImport::keyOf).toSet()
                        val found = ConnectionImport.discover().map { it to ConnectionImport.read(it).candidates }
                        val keys = found.flatMap { (_, candidates) -> candidates.map { it.key } }.filter { it !in saved }.toSet()
                        ImportHint(keys.size, found.filter { it.second.isNotEmpty() }.map { it.first.format.shortLabel }.distinct())
                    }.getOrNull()
                }
            importHint = hint?.takeIf { it.count > 0 }
        }
    }

    /** Saves imported profiles with the passwords and SSH secrets found for them (null: none). */
    suspend fun saveImported(items: List<Triple<ConnectionConfig, String?, String?>>): Result<Unit> = runCatching {
        val updated = withContext(Dispatchers.IO) { items.fold(profiles.toList()) { _, (config, password, sshSecret) -> store.save(config, password, sshSecret) } }
        replaceAll(updated)
        importHint = null
        val missing = items.count { (config, password, _) -> password == null && config.type.isServer }
        app.status =
            (if (items.size == 1) "Imported ${items[0].first.name}" else "Imported ${items.size} connections") +
                if (missing > 0) " · $missing without a password: add it with Edit Connection" else ""
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

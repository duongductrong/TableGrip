package dev.tablegrip.app.connections

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.datafile.DataFileFormats
import dev.tablegrip.core.datafile.DataFileStore
import dev.tablegrip.core.driver.DocumentStore
import dev.tablegrip.core.driver.KeyValueStore
import dev.tablegrip.core.driver.RecordStore
import dev.tablegrip.core.connection.ClientApi
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.imports.ConnectionImport
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [group] is where a new connection goes, e.g. when created from a group's menu. */
/** Opens the connection dialog on [existing], or on a new connection in [group], starting on [type] when one was picked. */
data class ConnectionDialogRequest(val existing: ConnectionConfig?, val group: String = "", val type: DatabaseType? = null)

/**
 * Asks for a group name: one for [configs] to move into ([newGroup] when they were dropped on "New group"), or a new name
 * for the group [renaming].
 */
data class GroupRequest(val configs: List<ConnectionConfig>, val renaming: String? = null, val newGroup: Boolean = false)

/** What Test Connection learned: the server's own description, or a better-fitting engine in its family. */
data class TestOutcome(val server: String? = null, val suggested: DatabaseType? = null)

/** A connect waiting for the password of [config], which isn't kept in the keychain. */
class PasswordPrompt(val config: ConnectionConfig, internal val answer: CompletableDeferred<String?>)

/** Saved connection profiles and the dialogs that edit them. */
class ConnectionsViewModel(private val app: AppState, private val store: ConnectionStore) {
    val profiles = mutableStateListOf<ConnectionConfig>()

    /**
     * Every write to [store], one at a time and in the order asked for: each reads the file and writes it back, so two at
     * once could lose one's change, and a write finishing early would report an older list than a later one.
     */
    private val writes = Dispatchers.IO.limitedParallelism(1)

    /** Changes shown before they're saved; a saved list is shown only if none came after its write was asked for. */
    private var edits = 0

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

    /** Non-null while a connection that doesn't keep its password asks for it; answered with the password or null. */
    var passwordPrompt by mutableStateOf<PasswordPrompt?>(null)
        private set

    /** One prompt at a time: two connections opening at once ask in turn. */
    private val prompting = Mutex()

    /**
     * The password to connect [id] with: the keychain's, or for a profile set to [ConnectionConfig.askPassword], what the
     * user types now. Cancelling the prompt fails the connect.
     */
    suspend fun connectPassword(id: String): String? {
        val config = byId(id)
        if (config?.askPassword != true) return storedPassword(id)
        return prompting.withLock {
            val prompt = PasswordPrompt(config, CompletableDeferred())
            withContext(Dispatchers.Main) { passwordPrompt = prompt }
            try {
                prompt.answer.await() ?: throw IllegalStateException(tr("No password entered for {0}.", config.name))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { if (passwordPrompt === prompt) passwordPrompt = null }
            }
        }
    }

    fun answerPassword(prompt: PasswordPrompt, password: String?) {
        prompt.answer.complete(password)
        if (passwordPrompt === prompt) passwordPrompt = null
    }

    /** Groups in use, for the Move to Group dialog to offer. */
    val groups: List<String>
        get() = profiles.map { it.group }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    /** Non-null while the group name dialog is open. */
    var groupRequest by mutableStateOf<GroupRequest?>(null)

    /**
     * Puts [configs] in [group], or at the top level when it's blank. They leave Favorites too, so they show up where
     * they were put.
     */
    fun moveToGroup(configs: List<ConnectionConfig>, group: String) {
        groupRequest = null
        val name = group.trim()
        val moved = configs.filter { it.group.trim() != name || it.favorite }
        if (moved.isEmpty()) return
        updateProfiles(moved.map { it.copy(group = name, favorite = false) })
        app.status = describe(moved) + if (name.isEmpty()) tr(" moved out of its group") else tr(" moved to {0}", name)
    }

    fun addToFavorites(configs: List<ConnectionConfig>) {
        val moved = configs.filter { !it.favorite }
        if (moved.isNotEmpty()) updateProfiles(moved.map { it.copy(favorite = true) })
    }

    /** Renames a group, merging it into another of that name if there is one. */
    fun renameGroup(from: String, to: String) {
        groupRequest = null
        val name = to.trim()
        if (name.isEmpty() || name == from) return
        updateProfiles(profiles.filter { it.group.trim() == from }.map { it.copy(group = name) })
    }

    /** Removes a group, moving its connections to the top level. */
    fun ungroup(name: String) {
        updateProfiles(profiles.filter { it.group.trim() == name }.map { it.copy(group = "") })
        app.status = tr("Ungrouped {0}", name)
    }

    private fun describe(configs: List<ConnectionConfig>) = if (configs.size == 1) configs[0].name else tr("{0} connections", configs.size)

    fun toggleFavorite(config: ConnectionConfig) = updateProfile(config.copy(favorite = !config.favorite))

    /** Saves a change that doesn't touch how to connect, e.g. its group; the live connection stays as it is. */
    fun updateProfile(config: ConnectionConfig) = updateDriver(config)

    /** Like [updateProfile] for several, saved one after another so no change overwrites another. */
    fun updateProfiles(configs: List<ConnectionConfig>) {
        if (configs.isEmpty()) return
        // Shown at once, so a dragged connection lands where it was dropped without waiting for the disk.
        val byId = configs.associateBy { it.id }
        replaceAll(profiles.map { byId[it.id] ?: it })
        val edit = ++edits
        app.scope.launch {
            runCatching { withContext(writes) { configs.fold(emptyList<ConnectionConfig>()) { _, config -> store.save(config, password = null, sshSecret = null) } } }
                .onSuccess { if (edit == edits) replaceAll(it) }
                .onFailure { app.status = tr("Failed to save: {0}", it.message) }
        }
    }

    /** Saves a profile whose driver the user switched while being asked for it (e.g. to MariaDB Connector/J). */
    fun updateDriver(config: ConnectionConfig) {
        app.scope.launch {
            runCatching { withContext(writes) { store.save(config, password = null, sshSecret = null) } }
                .onSuccess(::replaceAll)
                .onFailure { app.status = tr("Failed to save {0}: {1}", config.name, it.message) }
        }
    }

    /** Connects [config] again once its driver is there. */
    fun retryAfterDriver(config: ConnectionConfig) {
        driverPrompt = null
        app.explorer.refresh(dev.tablegrip.app.explorer.ConnectionKey(config.id))
        app.pickConnection(config)
    }

    init {
        runCatching { store.load() }
            .onSuccess { profiles.addAll(it) }
            .onFailure { app.status = tr("Failed to load connections: {0}", it.message) }
    }

    fun byId(id: String): ConnectionConfig? = profiles.firstOrNull { it.id == id }

    fun openNew(group: String = "", type: DatabaseType? = null) {
        dialog = ConnectionDialogRequest(existing = null, group = group, type = type)
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

    /** Connections other tools on this computer have and TableGrip doesn't; null until looked for, or if there are none. */
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
        val updated = withContext(writes) { items.fold(profiles.toList()) { _, (config, password, sshSecret) -> store.save(config, password, sshSecret) } }
        replaceAll(updated)
        importHint = null
        val missing = items.count { (config, password, _) -> password == null && config.type.isServer }
        app.status =
            (if (items.size == 1) tr("Imported {0}", items[0].first.name) else tr("Imported {0} connections", items.size)) +
                if (missing > 0) tr(" · {0} without a password: add it with Edit Connection", missing) else ""
    }

    /** Reads the stored password off the UI thread: the OS keychain may block or prompt. */
    suspend fun storedPassword(id: String): String? = withContext(Dispatchers.IO) { store.password(id) }

    suspend fun storedSshSecret(id: String): String? = withContext(Dispatchers.IO) { store.sshSecret(id) }

    /**
     * Waits for a driver download the user started; fails if the driver is still missing. Succeeds with the engine the
     * server really is when that differs from the profile's type.
     */
    suspend fun test(config: ConnectionConfig, password: String, sshSecret: String): Result<TestOutcome> = runCatching {
        // A data file is read without its working copy, so testing it needs no driver.
        if (config.type.isDataFile) return@runCatching TestOutcome(server = DataFileStore.test(config))
        app.drivers.ensure(config)
        when (config.type.api) {
            ClientApi.KeyValue -> TestOutcome(server = KeyValueStore.test(config, password, sshSecret, app.drivers.library))
            ClientApi.Document -> TestOutcome(server = DocumentStore.test(config, password, sshSecret, app.drivers.library))
            ClientApi.Records -> TestOutcome(server = RecordStore.test(config, password, sshSecret, app.drivers.library))
            else -> TestOutcome(suggested = Database.test(config, password, sshSecret, app.drivers.library))
        }
    }

    /** A blank SSH secret removes the stored one, as does turning SSH off. */
    suspend fun save(config: ConnectionConfig, password: String, sshSecret: String): Result<Unit> = runCatching {
        val previous = byId(config.id)
        // A profile that asks every time keeps no password: an empty one removes what the keychain had.
        val updated = withContext(writes) { store.save(config, if (config.askPassword) "" else password, if (config.ssh == null) "" else sshSecret) }
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
            runCatching { withContext(writes) { store.delete(config.id) } }
                .onSuccess {
                    replaceAll(it)
                    app.status = tr("Deleted {0}", config.name)
                }
                .onFailure { app.status = tr("Failed to delete {0}: {1}", config.name, it.message) }
        }
    }

    /** Asks for data files (CSV, Excel, JSON) and hands them to [open]: as connections in the main window by default. */
    fun chooseDataFiles(open: (List<File>) -> Unit = ::openDataFiles) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, tr("Open Data File"), java.awt.FileDialog.LOAD)
        dialog.isMultipleMode = true
        val extensions = DataFileFormats.all.flatMap { it.extensions }.toSet()
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        dialog.isVisible = true
        open(dialog.files.toList())
    }

    /**
     * Opens each data file as a connection, reusing the profile already pointing at it or saving a new one, then opens
     * its first table. Files no format claims are reported, not opened.
     */
    fun openDataFiles(files: List<File>) {
        if (files.isEmpty()) return
        app.scope.launch {
            for (file in files) {
                val config = dataFileProfile(file) ?: continue
                app.status = tr("Opening {0}…", file.name)
                runCatching {
                    val database = app.databases.database(config)
                    database.namespaces().firstOrNull()?.let { namespace -> database.tables(namespace).firstOrNull() }
                }.onSuccess { table ->
                    app.explorer.treeState.openNodes(listOf(dev.tablegrip.app.explorer.ConnectionKey(config.id)))
                    if (table != null) app.workspace.openTable(config, table.ref) else app.workspace.openConsole(config)
                    app.status = tr("Opened {0}", file.name)
                }.onFailure { app.status = tr("Couldn't open {0}: {1}", file.name, it.message) }
            }
        }
    }

    /**
     * The profile of the data file at [file]: the one already pointing at it, or a new one, saved. Null, with the reason
     * in the status, when no format claims the file or the profile couldn't be saved.
     */
    suspend fun dataFileProfile(file: File): ConnectionConfig? {
        val path = file.toPath().toAbsolutePath().normalize()
        profiles.firstOrNull { it.type.isDataFile && samePath(it.database, path) }?.let { return it }
        val profile = DataFileStore.profileFor(path, UUID.randomUUID().toString())
        if (profile == null) {
            val known = DataFileFormats.all.flatMap { it.extensions }.sorted().joinToString(", ") { ".$it" }
            app.status = tr("{0} isn't a data file TableGrip opens ({1})", file.name, known)
            return null
        }
        return runCatching { withContext(writes) { store.save(profile, "", "") } }
            .onSuccess(::replaceAll)
            .onFailure { app.status = tr("Couldn't save a connection for {0}: {1}", file.name, it.message) }
            .getOrNull()
            ?.let { profile }
    }

    private fun samePath(database: String, path: Path): Boolean =
        runCatching { DataFileStore.pathOf(database) == path }.getOrDefault(false)

    private fun replaceAll(configs: List<ConnectionConfig>) {
        profiles.clear()
        profiles.addAll(configs)
    }
}

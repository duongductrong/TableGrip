package dev.datagrok.app

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.driver.DatabaseSession
import dev.datagrok.core.driver.DbObject
import dev.datagrok.core.driver.QueryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One SQL console tab: its text, and the last result or error. */
class QueryTab(val title: String, initialSql: String = "") {
    val editor = TextFieldState(initialSql)
    var result by mutableStateOf<QueryResult?>(null)
    var error by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)
}

data class ConnectionDialogRequest(val existing: ConnectionConfig?)

/** UI state of the whole application. Kept in one place until features need their own view models. */
class AppState(private val store: ConnectionStore = ConnectionStore()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var theme by mutableStateOf(AppTheme.System)

    val connections = mutableStateListOf<ConnectionConfig>()

    /** Non-null while the connection dialog is open; [ConnectionDialogRequest.existing] is null for a new one. */
    var connectionDialog by mutableStateOf<ConnectionDialogRequest?>(null)
        private set

    /** Connection waiting for the user to confirm its deletion. */
    var pendingDelete by mutableStateOf<ConnectionConfig?>(null)

    var activeConnection by mutableStateOf<ConnectionConfig?>(null)
        private set

    var session by mutableStateOf<DatabaseSession?>(null)
        private set

    val objects = mutableStateListOf<DbObject>()
    var status by mutableStateOf("Not connected")

    val tabs = mutableStateListOf(QueryTab("console", SAMPLE_SQL))
    var selectedTab by mutableStateOf(0)

    init {
        runCatching { store.load() }
            .onSuccess { connections.addAll(it) }
            .onFailure { status = "Failed to load connections: ${it.message}" }
    }

    fun openNewConnection() {
        connectionDialog = ConnectionDialogRequest(existing = null)
    }

    fun openEditConnection(config: ConnectionConfig) {
        connectionDialog = ConnectionDialogRequest(existing = config)
    }

    fun closeConnectionDialog() {
        connectionDialog = null
    }

    /** Reads the stored password off the UI thread: the OS keychain may block or prompt. */
    suspend fun storedPassword(id: String): String? = withContext(Dispatchers.IO) { store.password(id) }

    suspend fun testConnection(config: ConnectionConfig, password: String): Result<Unit> = runCatching {
        DatabaseSession.open(config, password).close()
    }

    suspend fun saveConnection(config: ConnectionConfig, password: String): Result<Unit> = runCatching {
        val updated = withContext(Dispatchers.IO) { store.save(config, password) }
        connections.clear()
        connections.addAll(updated)
        if (activeConnection?.id == config.id) activeConnection = config
        connectionDialog = null
    }

    fun deleteConnection(config: ConnectionConfig) {
        pendingDelete = null
        scope.launch {
            if (activeConnection?.id == config.id) disconnect()
            runCatching { withContext(Dispatchers.IO) { store.delete(config.id) } }
                .onSuccess {
                    connections.clear()
                    connections.addAll(it)
                    status = "Deleted ${config.name}"
                }
                .onFailure { status = "Failed to delete ${config.name}: ${it.message}" }
        }
    }

    fun disconnect() {
        session?.close()
        session = null
        activeConnection = null
        objects.clear()
        status = "Not connected"
    }

    fun connect(config: ConnectionConfig) {
        scope.launch {
            status = "Connecting to ${config.name}…"
            runCatching {
                session?.close()
                DatabaseSession.open(config, storedPassword(config.id))
            }
                .onSuccess {
                    session = it
                    activeConnection = config
                    status = "Connected to ${config.name}"
                    refreshObjects()
                }
                .onFailure { status = "Connection failed: ${it.message}" }
        }
    }

    fun refreshObjects() {
        val session = session ?: return
        scope.launch {
            runCatching { session.listTables() }
                .onSuccess {
                    objects.clear()
                    objects.addAll(it)
                }
                .onFailure { status = "Failed to load objects: ${it.message}" }
        }
    }

    fun newTab() {
        tabs.add(QueryTab("console ${tabs.size + 1}"))
        selectedTab = tabs.lastIndex
    }

    fun closeTab(index: Int) {
        if (tabs.size == 1) return
        tabs.removeAt(index)
        selectedTab = selectedTab.coerceAtMost(tabs.lastIndex)
    }

    fun run(tab: QueryTab) {
        val session = session
        if (session == null) {
            tab.error = "Connect to a database first."
            return
        }
        val sql = tab.editor.text.toString().trim()
        if (sql.isEmpty()) return
        scope.launch {
            tab.running = true
            tab.error = null
            // TODO: split scripts into statements properly (quotes, comments, dialect specifics).
            runCatching { sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { session.execute(it) }.last() }
                .onSuccess {
                    tab.result = it
                    if (it is QueryResult.Update) refreshObjects()
                }
                .onFailure { tab.error = it.message ?: it.toString() }
            tab.running = false
        }
    }

    fun dispose() {
        session?.close()
        scope.cancel()
    }

    companion object {
        val instance by lazy { AppState() }

        private val SAMPLE_SQL = "SELECT 1;"
    }
}

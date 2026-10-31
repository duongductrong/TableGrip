package dev.datagrok.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.agent.AgentViewModel
import dev.datagrok.app.backup.BackupState
import dev.datagrok.app.backup.ScheduledBackups
import dev.datagrok.app.connections.ConnectionsViewModel
import dev.datagrok.app.connections.DatabaseRegistry
import dev.datagrok.app.connections.DriverDownloads
import dev.datagrok.app.completion.SchemaCache
import dev.datagrok.app.explorer.ExplorerViewModel
import dev.datagrok.app.files.FileWindows
import dev.datagrok.app.export.ExportRequest
import dev.datagrok.app.queries.SavedQueries
import dev.datagrok.app.schema.ImportWizard
import dev.datagrok.app.schema.TestDataState
import dev.datagrok.app.settings.SettingsPages
import dev.datagrok.app.tools.ToolsViewModel
import dev.datagrok.app.workspace.SessionKeeper
import dev.datagrok.app.workspace.WorkspaceViewModel
import dev.datagrok.core.connection.AiAccess
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.datafile.DataFileConnection
import dev.datagrok.core.datafile.DataFileState
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.SecretStore
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

enum class LeftTool { Explorer, History, Queries }

/** Root of the UI state: owns app-wide services and wires the feature view models together. */
class AppState(
    private val settingsStore: SettingsStore = SettingsStore(),
    connectionStore: ConnectionStore = ConnectionStore(),
    val history: QueryHistory = QueryHistory(),
    driverLibrary: DriverLibrary = DriverLibrary.shared,
    /** Null picks agents by the settings: the CLIs on this machine, or the configured provider. */
    agentRegistry: AgentRegistry? = null,
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
    /** Shows the agent chat in an island on the right, next to whatever is in front. */
    var agentOpen by mutableStateOf(false)
    var exportRequest by mutableStateOf<ExportRequest?>(null)
    /** The import wizard, while it's open. */
    var importWizard by mutableStateOf<ImportWizard?>(null)
    /** The Backup or Restore dialog, while it's open; a running job keeps going when it is hidden. */
    var backup by mutableStateOf<BackupState?>(null)
    /** The test data generator, while it's open. */
    var testData by mutableStateOf<TestDataState?>(null)
    /** Shows the go-to-anything popup (⌘P / ⇧⇧). */
    var goToOpen by mutableStateOf(false)
    /** Values last given to query parameters, by name, offered again the next time a query asks for them. */
    val parameterValues = mutableMapOf<String, String>()

    /** The connection the title bar shows and new consoles use; follows the selected tab. */
    var currentConnectionId by mutableStateOf<String?>(null)

    val currentConnection: ConnectionConfig?
        get() = currentConnectionId?.let(connections::byId) ?: connections.profiles.firstOrNull()

    /** JDBC drivers on this machine and downloads in progress. */
    val drivers = DriverDownloads(scope, driverLibrary) { settings }.also { it.library.repository = settings.driverRepository }

    val connections = ConnectionsViewModel(this, connectionStore)
    val databases = DatabaseRegistry(scope, connections::storedPassword, connections::storedSshSecret, drivers, connections::askForDriver, ::dataFileReloaded)
    val explorer = ExplorerViewModel(this)
    val workspace = WorkspaceViewModel(this)
    /** Data files open in windows of their own, e.g. from the file manager. */
    val files = FileWindows(this)

    /**
     * Whether the main window is shown. A data file opened from the file manager shows only its own window; the main
     * window comes up when something opens in it, and hides rather than quits while file windows remain.
     */
    var mainWindowVisible by mutableStateOf(false)
    /** Secrets kept out of settings.json, in the same store as connection passwords. */
    val secrets: SecretStore = connectionStore.secrets
    val agent = agentRegistry?.let { AgentViewModel(this, it) } ?: AgentViewModel(this)
    val savedQueries = SavedQueries(this)
    /** pg_dump, mysqldump and the other clients backups run. */
    val tools = ToolsViewModel(this)
    /** Backups on a schedule while the app is open; off unless turned on in Settings → Backups. */
    val scheduledBackups = ScheduledBackups(this)
    /** Reopens the last session's tabs; [Main] restores it, tests start empty. */
    val session = SessionKeeper(this)

    /** What [config] may send to AI agents: its own choice, else the app-wide setting. */
    fun aiAccess(config: ConnectionConfig?): AiAccess = AiAccess.effective(AiAccess.of(settings.aiAccess), config)

    private val schemaCaches = mutableMapOf<String, SchemaCache>()

    /** Completion metadata for a connection, shared by its consoles and table filters. */
    fun schemaCache(config: ConnectionConfig): SchemaCache = schemaCaches.getOrPut(config.id) { SchemaCache(scope, databases, config) }

    /**
     * A data file was read in again after it changed on disk: the explorer and the file's table views show it afresh.
     * Views with unsaved edits are left alone, so the edits aren't lost.
     */
    private fun dataFileReloaded(config: ConnectionConfig) {
        explorer.refresh(dev.datagrok.app.explorer.ConnectionKey(config.id))
        workspace.tabs.filterIsInstance<TableDataTab>().filter { it.connectionId == config.id && !it.hasChanges }.forEach { it.refresh() }
        files.reloaded(config.id)
        status = "Reloaded ${config.name}: it changed on disk"
    }

    /** Drops cached completion metadata, e.g. after DDL or when the connection's settings change. */
    fun forgetSchema(connectionId: String) {
        schemaCaches.remove(connectionId)?.invalidate()
        agent.forgetSchema(connectionId)
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings
        settings = transform(settings)
        val snapshot = settings
        drivers.library.repository = snapshot.driverRepository
        agent.settingsChanged(before, snapshot)
        if (before.scheduledBackups != snapshot.scheduledBackups || before.backupSchedules.map { it.enabled } != snapshot.backupSchedules.map { it.enabled }) scheduledBackups.sync()
        scope.launch(Dispatchers.IO) {
            runCatching { settingsStore.save(snapshot) }.onFailure { log.warn("Failed to save settings", it) }
        }
    }

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

    /** The tool [toggleSidebar] brings back. */
    private var hiddenTool = LeftTool.Explorer

    /** Hides the left tool window, or shows the one last hidden this way. */
    fun toggleSidebar() {
        val tool = leftTool
        if (tool != null && !settingsOpen) {
            hiddenTool = tool
            leftTool = null
        } else {
            leftTool = tool ?: hiddenTool
        }
        settingsOpen = false
    }

    /**
     * Quits via [exit] once the work quitting would lose (open transactions, unsubmitted edits) has been dealt with;
     * does nothing if the user cancels. Tabs and unsaved console text are kept in the session for the next start.
     */
    fun requestExit(exit: () -> Unit) =
        files.confirmQuit({
            workspace.confirmQuit({
                confirmDataFiles(null) {
                    session.stopAutosave()
                    session.saveNow()
                    dispose()
                    exit()
                }
            })
        })

    /** Data files whose changes couldn't be written, shown before the connections holding them close. */
    var unsavedDataFiles by mutableStateOf<UnsavedDataFiles?>(null)

    /**
     * Runs [then] once no data file among [connectionIds] (null: all) holds changes it couldn't write, asking the user
     * first when one does: closing the connection would drop them with its working copy.
     */
    fun confirmDataFiles(connectionIds: Collection<String>?, then: () -> Unit) {
        val unsaved =
            connections.profiles
                .filter { connectionIds == null || it.id in connectionIds }
                .mapNotNull { databases.dataFile(it.id) }
                .filter { it.sync.state.value.let { state -> state is DataFileState.Held || state is DataFileState.Failed } }
        if (unsaved.isEmpty()) then() else unsavedDataFiles = UnsavedDataFiles(unsaved, then)
    }

    fun dispose() {
        agent.stop()
        workspace.disposeAll()
        files.disposeAll()
        databases.closeAll()
        scope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppState::class.java)
    }
}

/** Data files with changes that aren't in the file, and what to do once the user has decided. */
class UnsavedDataFiles(val connections: List<DataFileConnection>, val then: () -> Unit)

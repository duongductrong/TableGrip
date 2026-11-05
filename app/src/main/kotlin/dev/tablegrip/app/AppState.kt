package dev.tablegrip.app

import androidx.compose.runtime.getValue
import dev.tablegrip.app.settings.ConfigFileWatcher
import dev.tablegrip.app.settings.openInSystem
import dev.tablegrip.app.ui.theme.ThemeRegistry
import dev.tablegrip.core.TableGripPaths
import java.nio.file.Path
import kotlin.io.path.exists
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.agent.AgentViewModel
import dev.tablegrip.app.backup.BackupState
import dev.tablegrip.app.backup.ScheduledBackups
import dev.tablegrip.app.connections.ConnectionsViewModel
import dev.tablegrip.app.connections.DatabaseRegistry
import dev.tablegrip.app.connections.DriverDownloads
import dev.tablegrip.app.completion.SchemaCache
import dev.tablegrip.app.explorer.ExplorerViewModel
import dev.tablegrip.app.files.FileWindows
import dev.tablegrip.app.export.ExportRequest
import dev.tablegrip.app.queries.SavedQueries
import dev.tablegrip.app.schema.ImportWizard
import dev.tablegrip.app.schema.TestDataState
import dev.tablegrip.app.settings.SettingsPages
import dev.tablegrip.app.update.Updater
import dev.tablegrip.app.tools.ToolsViewModel
import dev.tablegrip.app.ui.EnvironmentColorOverrides
import dev.tablegrip.app.ui.KeymapPreset
import dev.tablegrip.app.ui.Shortcuts
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import org.jetbrains.jewel.ui.component.SplitLayoutState
import dev.tablegrip.app.workspace.SessionKeeper
import dev.tablegrip.app.workspace.WorkspaceViewModel
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.datafile.DataFileConnection
import dev.tablegrip.core.datafile.DataFileState
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.ChatStore
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.SecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.SettingsStore
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
    /** Where agent chats are kept; null keeps them only while the app runs, as tests do. [Main] passes the real one. */
    val chatStore: ChatStore? = null,
) {
    /** Lives as long as the app; everything here runs on the UI thread unless it hops to IO itself. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var settings by mutableStateOf(settingsStore.load())
        private set

    /** Why settings.json couldn't be read, while it can't: the app runs on defaults and leaves the file for the user to fix. */
    var settingsError by mutableStateOf(settingsStore.loadError)
        private set

    /** Where settings live, for Settings → Appearance's "Open settings.json". */
    val settingsFile: Path
        get() = settingsStore.file

    init {
        applyAppWide(settings)
    }

    /** The main window's size and place; restored from the session when Settings → Startup & Layout keeps it. */
    val windowState = WindowState(size = DpSize(1360.dp, 860.dp))
    /** Divider of the left tool window, kept here so it survives hiding the tool window and restarts. */
    val sidebarSplit = SplitLayoutState(0.24f)
    /** Divider between the editor and the agent chat. */
    val agentSplit = SplitLayoutState(0.68f)

    val theme: AppTheme
        get() = AppTheme.of(settings.theme.mode)

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
    /** Quits the app the way the user would, asking about unsaved work; [Main] sets it. */
    var quitApp: () -> Unit = {}
    /** Quits like [quitApp], then installs the downloaded update and starts the new version; [Main] sets it. */
    var restartToUpdate: () -> Unit = {}
    /** Keeps the installed app up to date; [Main] starts it, tests and `:app:run` get one that does nothing. */
    val updater: Updater = Updater.create(canQuit = { !quitWouldAsk }, requestQuit = { quitApp() })

    /** Starts update checks as Settings → Updates say. */
    fun startUpdater() {
        scope.launch(Dispatchers.IO) { updater.start(settings) }
    }
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
        explorer.refresh(dev.tablegrip.app.explorer.ConnectionKey(config.id))
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
        val snapshot = apply(transform(settings))
        scope.launch(Dispatchers.IO) {
            runCatching { if (!settingsStore.save(snapshot)) log.warn("Not saving settings: settings.json has an error to fix first") }
                .onFailure { log.warn("Failed to save settings", it) }
        }
    }

    /** Makes [next] the settings everywhere, without saving them. */
    private fun apply(next: Settings): Settings {
        val before = settings
        settings = next
        applyAppWide(next)
        drivers.library.repository = next.driverRepository
        agent.settingsChanged(before, next)
        if (before.scheduledBackups != next.scheduledBackups || before.backupSchedules.map { it.enabled } != next.backupSchedules.map { it.enabled }) scheduledBackups.sync()
        if (before.checkForUpdates != next.checkForUpdates || before.installUpdatesAutomatically != next.installUpdatesAutomatically || before.updateChannel != next.updateChannel) {
            updater.configure(next)
        }
        return next
    }

    /**
     * Reads settings.json again after it changed on disk, e.g. edited by hand: a valid file is applied at once, an
     * invalid one is reported in [settingsError] and left alone, keeping the settings in use. The app's own saves are
     * skipped. Call on the UI thread.
     */
    fun reloadSettingsFromDisk() {
        val text = settingsStore.readText()
        if (text != null && text == settingsStore.lastWritten && settingsError == null) return
        val loaded = settingsStore.load()
        settingsError = settingsStore.loadError
        if (settingsError == null && loaded != settings) {
            apply(loaded)
            status = "Applied settings.json"
        }
    }

    /** Opens settings.json in the system's editor for JSON, writing the current settings first if there is no file yet. */
    fun openSettingsFile() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (!settingsFile.exists()) settingsStore.save(settings)
                openInSystem(settingsFile)
            }.onFailure { log.warn("Couldn't open {}", settingsFile, it) }
        }
    }

    /** Opens the folder of the user's theme files in the file manager. */
    fun openThemesFolder() {
        scope.launch(Dispatchers.IO) {
            runCatching { openInSystem(ThemeRegistry.userThemesFolder()) }.onFailure { log.warn("Couldn't open the themes folder", it) }
        }
    }

    /** Copies the theme in use into the themes folder (once) and opens the copy, which then replaces the built-in theme. */
    fun customizeTheme(dark: Boolean) {
        val name = ThemeRegistry.resolve(if (dark) settings.theme.dark else settings.theme.light, dark).name
        scope.launch(Dispatchers.IO) {
            runCatching { ThemeRegistry.copyToUserFolder(name)?.let(::openInSystem) }.onFailure { log.warn("Couldn't copy theme {}", name, it) }
        }
    }

    /** Applies edits of settings.json and of theme files while the app runs; [Main] starts it, tests don't. */
    fun watchConfigFiles() {
        ThemeRegistry.reloadUserThemes()
        ConfigFileWatcher(settingsFile, TableGripPaths.themes, onSettingsChanged = ::reloadSettingsFromDisk, onThemesChanged = { ThemeRegistry.reloadUserThemes() }).start(scope)
    }

    /** Settings read outside composition: shortcuts are matched in key handlers, badges drawn in plain functions. */
    private fun applyAppWide(settings: Settings) {
        Shortcuts.configure(KeymapPreset.of(settings.keymap), settings.keyBindings)
        EnvironmentColorOverrides.configure(settings)
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
                    agent.saveNow()
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
        val unsaved = unsavedDataFileConnections(connectionIds)
        if (unsaved.isEmpty()) then() else unsavedDataFiles = UnsavedDataFiles(unsaved, then)
    }

    private fun unsavedDataFileConnections(connectionIds: Collection<String>?) =
        connections.profiles
            .filter { connectionIds == null || it.id in connectionIds }
            .mapNotNull { databases.dataFile(it.id) }
            .filter { it.sync.state.value.let { state -> state is DataFileState.Held || state is DataFileState.Failed } }

    /** Whether [requestExit] would ask the user anything before quitting, i.e. whether quitting now could lose work. */
    val quitWouldAsk: Boolean
        get() = files.windows.any { it.hasUnsavedWork } || workspace.tabs.any { it.losesWorkOnQuit } || unsavedDataFileConnections(null).isNotEmpty()

    fun dispose() {
        updater.dispose()
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

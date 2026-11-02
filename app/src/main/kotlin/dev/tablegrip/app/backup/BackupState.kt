package dev.tablegrip.app.backup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.core.TableGripPaths
import dev.tablegrip.core.backup.BackupCommands
import dev.tablegrip.core.backup.BackupContent
import dev.tablegrip.core.backup.BackupFormat
import dev.tablegrip.core.backup.BackupOptions
import dev.tablegrip.core.backup.BackupSupport
import dev.tablegrip.core.backup.RestoreEntry
import dev.tablegrip.core.backup.RestoreOptions
import dev.tablegrip.core.backup.ServerEndpoint
import dev.tablegrip.core.backup.ToolCommand
import dev.tablegrip.core.backup.ToolRunner
import dev.tablegrip.core.backup.openTunnelFor
import dev.tablegrip.core.backup.toolsFor
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.SshTunnel
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.tools.InstalledTool
import dev.tablegrip.core.tools.Tool
import dev.tablegrip.core.tools.ToolCatalog
import dev.tablegrip.core.tools.ToolFamily
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the tool check found: a usable tool, or why there's none and what can be done. */
sealed interface ToolCheck {
    data object Checking : ToolCheck

    data class Ready(val tool: InstalledTool) : ToolCheck

    /** No usable copy: [message] says why; [download] can fix it when TableGrip may download for this platform. */
    data class Missing(val message: String, val download: dev.tablegrip.core.tools.ToolPackage?) : ToolCheck

    /** SQLite and DuckDB back themselves up. */
    data object NotNeeded : ToolCheck
}

/**
 * The Backup or Restore dialog for one connection: the form, the tool it needs, and the run. The run goes on in the
 * app's scope, so the dialog can be closed while it works; the status bar reports how it ends.
 */
class BackupState(private val app: AppState, val config: ConnectionConfig, val restore: Boolean) {
    val support: BackupSupport = if (restore) BackupSupport.restoreOf(config.type) else BackupSupport.of(config.type)
    private val dialect: SqlDialect = SqlDialect.of(config.type)
    val family: ToolFamily? = (support as? BackupSupport.WithTools)?.family
    val formats: List<BackupFormat> =
        when (support) {
            is BackupSupport.WithTools -> BackupFormat.forFamily(support.family)
            is BackupSupport.Native -> listOf(support.format)
            else -> emptyList()
        }

    var format by mutableStateOf(formats.firstOrNull() ?: BackupFormat.PgCustom)
    val database = TextFieldState(config.database)
    /** Comma-separated schemas (PostgreSQL) or tables to limit the backup to. */
    val only = TextFieldState()
    var content by mutableStateOf(BackupContent.All)
    var noOwner by mutableStateOf(false)
    var noPrivileges by mutableStateOf(false)
    var clean by mutableStateOf(false)
    var target by mutableStateOf<Path?>(null)

    // Restore
    var source by mutableStateOf<Path?>(null)
    val targetDatabase = TextFieldState(defaultRestoreName(config.database))
    var createDatabase by mutableStateOf(true)
    /** Production restores need the database name typed again. */
    val confirmName = TextFieldState()
    val entries = mutableStateListOf<RestoreEntry>()
    val excluded = mutableStateListOf<String>()
    var listing by mutableStateOf(false)

    var check by mutableStateOf<ToolCheck>(ToolCheck.Checking)
        private set
    var running by mutableStateOf(false)
        private set
    /** The last line the tool printed, as progress. */
    var lastLine by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf<String?>(null)
        private set
    var logFile by mutableStateOf<Path?>(null)
        private set
    private var job: Job? = null
    private var serverMajor: Int? = null

    /** Databases the user may pick from (MySQL's catalogs); PostgreSQL backs up the connection's database. */
    val databases = mutableStateListOf<String>()

    init {
        if (!restore) target = defaultTarget()
        checkTools()
        if (config.type.family == EngineFamily.MySQL) {
            app.scope.launch {
                runCatching { app.databases.database(config).namespaces().mapNotNull { it.catalog }.distinct() }.onSuccess {
                    databases.clear()
                    databases += it.filter { db -> db !in setOf("information_schema", "performance_schema", "sys", "mysql") }
                    if (database.text.isBlank()) databases.firstOrNull()?.let { first -> database.edit { replace(0, length, first) } }
                }
            }
        }
    }

    val tool: Tool?
        get() = (check as? ToolCheck.Ready)?.tool?.tool

    /** Why the run can't start yet; null when it can. */
    val blocker: String?
        get() =
            when {
                support is BackupSupport.NotYet -> support.reason
                restore && config.readOnly -> "${config.name} is read-only: restoring writes to the server."
                check is ToolCheck.Checking -> "Looking for the tools…"
                check is ToolCheck.Missing -> (check as ToolCheck.Missing).message
                restore && source == null -> "Choose the backup to restore."
                restore && targetDatabase.text.isBlank() -> "Name the database to restore into."
                restore && production && confirmName.text.toString() != targetDatabase.text.toString() ->
                    "Type the target database's name to confirm restoring on Production."
                !restore && database.text.isBlank() && support !is BackupSupport.Native -> "Name the database to back up."
                !restore && target == null -> "Choose where to save the backup."
                else -> null
            }

    val production: Boolean
        get() = config.environment == Environment.Production

    /** Re-checks after a download or a change in Settings → Tools. */
    fun checkTools() {
        val family = family
        if (family == null) {
            check = if (support is BackupSupport.Native) ToolCheck.NotNeeded else ToolCheck.Missing("", null)
            return
        }
        check = ToolCheck.Checking
        app.scope.launch {
            if (serverMajor == null) serverMajor = runCatching { app.databases.database(config).serverMajorVersion() }.getOrNull()
            val wanted = toolsFor(config.type, restore, format)
            val found = wanted.firstNotNullOfOrNull { app.tools.best(it, serverMajor) }
            check =
                if (found != null) ToolCheck.Ready(found)
                else {
                    val older = app.tools.find(wanted.first()).mapNotNull { it.version }.maxOrNull()
                    val needs = if (family == ToolFamily.PostgreSQL && serverMajor != null) "${wanted.first().command} $serverMajor or newer" else wanted.first().command
                    val why =
                        if (older != null) "${wanted.first().command} $older is too old for this ${config.type.displayName} $serverMajor server: it needs $needs."
                        else "${if (restore) "Restoring" else "Backing up"} ${config.name} needs $needs, which isn't on this machine."
                    ToolCheck.Missing(why, ToolCatalog.packageFor(family))
                }
        }
    }

    fun download() {
        val missing = check as? ToolCheck.Missing ?: return
        val pkg = missing.download ?: return
        app.tools.requestDownload(pkg, missing.message) { checkTools() }
    }

    /** Settings → Tools, to choose a tool by hand. */
    fun openToolSettings() {
        app.settingsPage = "tools"
        app.settingsOpen = true
    }

    private fun defaultTarget(): Path {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val base = (database.text.toString().ifBlank { config.database.substringAfterLast('/').substringBeforeLast('.') }.ifBlank { config.name }) + "_" + stamp
        val folder = backupFolder(config)
        return folder.resolve(format.extension?.let { "$base.$it" } ?: base)
    }

    fun formatChanged(new: BackupFormat) {
        format = new
        if (!restore) target = target?.let { old -> old.resolveSibling(old.fileName.toString().substringBefore('.') + (new.extension?.let { ".$it" } ?: "")) }
        else checkTools()
    }

    fun chooseSource(path: Path) {
        source = path
        format = if (family == ToolFamily.MySQL) BackupFormat.MySqlSql else BackupFormat.ofFile(path)
        entries.clear()
        excluded.clear()
        checkTools()
        if (format == BackupFormat.PgCustom || format == BackupFormat.PgDirectory) listEntries(path)
    }

    /** Reads what a custom or directory backup holds, so parts can be left out. */
    private fun listEntries(path: Path) {
        listing = true
        app.scope.launch {
            try {
                val restoreTool = app.tools.best(Tool.PgRestore, null) ?: return@launch
                val run = ToolRunner.runChecked(BackupCommands.pgRestoreList(restoreTool.path, path), "Reading the backup")
                entries += RestoreEntry.parse(run.log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                listing = false
            }
        }
    }

    fun toggle(entry: RestoreEntry) {
        if (entry.line in excluded) excluded -= entry.line else excluded += entry.line
    }

    private fun splitList(text: CharSequence): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun backupOptions(): BackupOptions {
        val names = splitList(only.text)
        val pg = family == ToolFamily.PostgreSQL
        return BackupOptions(
            format = format,
            target = target ?: defaultTarget(),
            database = database.text.toString().trim(),
            content = content,
            schemas = if (pg) names.filter { '.' !in it } else emptyList(),
            tables = if (pg) names.filter { '.' in it } else names,
            noOwner = noOwner,
            noPrivileges = noPrivileges,
            clean = clean,
        )
    }

    fun restoreOptions(): RestoreOptions? {
        val file = source ?: return null
        val chosen = entries.filter { it.line !in excluded }.takeIf { excluded.isNotEmpty() }
        return RestoreOptions(file, format, targetDatabase.text.toString().trim(), noOwner = noOwner, noPrivileges = noPrivileges, clean = clean, entries = chosen)
    }

    /** The command that will run, for the "equivalent command" panel; the password isn't in it. */
    fun preview(): String? {
        val ready = check as? ToolCheck.Ready ?: return null
        val server = runCatching { ServerEndpoint.of(config, null) }.getOrNull() ?: return null
        return runCatching { command(ready.tool.path, server, Path.of("<option file>"), Path.of("<list file>")).display }.getOrNull()
    }

    private fun command(tool: Path, server: ServerEndpoint, secrets: Path, listFile: Path?): ToolCommand =
        if (!restore) {
            val options = backupOptions()
            if (family == ToolFamily.MySQL) BackupCommands.mysqlDump(tool, server, options, secrets) else BackupCommands.pgDump(tool, server, options)
        } else {
            val options = restoreOptions() ?: throw IllegalStateException("No backup chosen")
            when {
                family == ToolFamily.MySQL -> BackupCommands.mysqlRestore(tool, server, options, secrets)
                options.format == BackupFormat.PgPlain -> BackupCommands.psqlRestore(tool, server, options)
                else -> BackupCommands.pgRestore(tool, server, options, listFile.takeIf { options.entries != null })
            }
        }

    fun start() {
        if (blocker != null || running) return
        error = null
        done = null
        running = true
        lastLine = ""
        val what = if (restore) "Restore of ${source?.fileName} into ${targetDatabase.text}" else "Backup of ${config.name}"
        val log = TableGripPaths.home.resolve("logs").resolve("backup").resolve("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${safeName(config.name)}.log")
        logFile = log
        app.status = "$what…"
        job =
            app.scope.launch {
                var tunnel: SshTunnel? = null
                val scratch = Files.createTempDirectory("tablegrip-backup")
                try {
                    if (support is BackupSupport.Native) {
                        val options = backupOptions()
                        Files.createDirectories(options.target.parent)
                        app.databases.database(config).backupInto(options.target)
                    } else {
                        val tool = (check as ToolCheck.Ready).tool.path
                        val password = app.connections.storedPassword(config.id)
                        var server = ServerEndpoint.of(config, password)
                        tunnel = withContext(Dispatchers.IO) { openTunnelFor(config, server, app.connections.storedSshSecret(config.id)) }
                        tunnel?.let { server = server.copy(host = "127.0.0.1", port = it.localPort) }
                        if (restore && createDatabase) createTargetDatabase()
                        val listFile = scratch.resolve("restore.list")
                        restoreOptions()?.entries?.let { chosen -> withContext(Dispatchers.IO) { Files.writeString(listFile, chosen.joinToString("\n") { it.line }) } }
                        if (!restore) withContext(Dispatchers.IO) { Files.createDirectories(backupOptions().target.parent) }
                        val command = command(tool, server, scratch.resolve("client.cnf"), listFile)
                        ToolRunner.runChecked(command, what, log) { line -> app.scope.launch { lastLine = line } }
                    }
                    val message = if (restore) "Restored ${source?.fileName} into ${targetDatabase.text}" else "Backed up ${config.name} to ${backupOptions().target}"
                    done = message
                    app.status = message
                    if (restore) app.explorer.refresh(dev.tablegrip.app.explorer.ConnectionKey(config.id))
                } catch (e: CancellationException) {
                    if (!restore) ToolRunner.discard(backupOptions().target)
                    app.status = "$what cancelled"
                    error = "Cancelled.${if (!restore) " The partial backup was deleted." else " What was restored so far stays."}"
                } catch (e: Exception) {
                    if (!restore) ToolRunner.discard(backupOptions().target)
                    error = e.message ?: e.toString()
                    app.status = "$what failed"
                } finally {
                    tunnel?.close()
                    runCatching { scratch.toFile().deleteRecursively() }
                    running = false
                    job = null
                }
            }
    }

    private suspend fun createTargetDatabase() {
        val name = targetDatabase.text.toString().trim()
        app.databases.database(config).openSession().use { session -> session.execute("CREATE DATABASE ${dialect.quoteIdentifier(name)}") }
    }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        fun defaultRestoreName(database: String): String {
            val base = database.ifBlank { "restored" }
            return "${base}_restore_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}"
        }

        /** `~/Backups/<connection>`, where backups go unless the user picks another place. */
        fun backupFolder(config: ConnectionConfig): Path = Path.of(System.getProperty("user.home"), "Backups", safeName(config.name))

        fun safeName(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "connection" }
    }
}

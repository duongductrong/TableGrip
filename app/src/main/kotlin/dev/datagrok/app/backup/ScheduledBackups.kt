package dev.datagrok.app.backup

import dev.datagrok.app.AppState
import dev.datagrok.core.backup.BackupSchedule
import dev.datagrok.core.backup.BackupSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [dev.datagrok.core.settings.Settings.backupSchedules] while the app is open, one at a time, checking every
 * minute. Does nothing while [dev.datagrok.core.settings.Settings.scheduledBackups] is off, the default. A run missed
 * while Datagrok was closed happens soon after it opens, and the status bar says so.
 */
class ScheduledBackups(private val app: AppState) {
    private var loop: Job? = null
    private val started = Instant.now()

    /** Starts or stops the loop to match the settings; called at startup and when they change. */
    fun sync() {
        val on = app.settings.scheduledBackups && app.settings.backupSchedules.any { it.enabled }
        if (on && loop == null) {
            loop = app.scope.launch {
                // Let the app settle before the first check.
                delay(FirstCheckDelay)
                while (true) {
                    runDue()
                    delay(CheckInterval)
                }
            }
        } else if (!on) {
            loop?.cancel()
            loop = null
        }
    }

    private suspend fun runDue() {
        if (!app.settings.scheduledBackups) return
        for (schedule in app.settings.backupSchedules.filter { it.isDue(Instant.now()) }) {
            val missed = schedule.lastRun != null && Instant.ofEpochMilli(schedule.lastRun!!).plus(schedule.frequency.interval).isBefore(started)
            val error = runOnce(schedule, missed)
            record(schedule.id) { it.copy(lastRun = System.currentTimeMillis(), lastError = error) }
        }
    }

    /** Runs [schedule] now and returns why it failed, or null. */
    suspend fun runOnce(schedule: BackupSchedule, missed: Boolean = false): String? {
        val config = app.connections.byId(schedule.connectionId) ?: return "The connection was deleted."
        if (BackupSupport.of(config.type) !is BackupSupport.WithTools && BackupSupport.of(config.type) !is BackupSupport.Native) return "${config.type.displayName} can't be backed up."
        val state = BackupState(app, config, restore = false)
        if (schedule.database.isNotBlank()) state.database.edit { replace(0, length, schedule.database) }
        while (state.check is ToolCheck.Checking) delay(100)
        (state.check as? ToolCheck.Missing)?.let { return it.message }
        val folder = folderOf(schedule)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        state.target = folder.resolve(schedule.prefix + stamp + (state.format.extension?.let { ".$it" } ?: ""))
        app.status = "${if (missed) "Running a missed scheduled backup" else "Scheduled backup"} of ${config.name}…"
        state.start()
        while (state.running) delay(250)
        state.error?.let { return it }
        BackupSchedule.prune(folder, schedule)
        app.status = "Scheduled backup of ${config.name} saved to ${state.target}"
        return null
    }

    fun folderOf(schedule: BackupSchedule): Path =
        schedule.folder.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: app.connections.byId(schedule.connectionId)?.let(BackupState::backupFolder)
            ?: Path.of(System.getProperty("user.home"), "Backups")

    /** What stops [schedule] from running, if anything: the panel shows these before it's turned on. */
    suspend fun problems(schedule: BackupSchedule): List<String> {
        val config = app.connections.byId(schedule.connectionId) ?: return listOf("Its connection was deleted.")
        return buildList {
            when (val support = BackupSupport.of(config.type)) {
                is BackupSupport.NotYet -> add(support.reason)
                BackupSupport.NotApplicable -> add("${config.type.displayName} can't be backed up.")
                else -> Unit
            }
            val folder = folderOf(schedule)
            val writable = withContext(Dispatchers.IO) { runCatching { Files.createDirectories(folder); Files.isWritable(folder) }.getOrDefault(false) }
            if (!writable) add("Can't write to $folder.")
            if (config.user.isNotBlank() && config.type.isServer && app.connections.storedPassword(config.id).isNullOrEmpty()) {
                add("No password saved in the keychain for ${config.name}; the backup can only run if the server needs none.")
            }
        }
    }

    private fun record(id: String, change: (BackupSchedule) -> BackupSchedule) {
        app.updateSettings { s -> s.copy(backupSchedules = s.backupSchedules.map { if (it.id == id) change(it) else it }) }
    }

    companion object {
        private const val FirstCheckDelay = 20_000L
        private const val CheckInterval = 60_000L
    }
}

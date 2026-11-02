package dev.tablegrip.core.backup

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.Serializable

enum class BackupFrequency(val label: String, val interval: Duration) {
    Hourly("Every hour", Duration.ofHours(1)),
    Daily("Every day", Duration.ofDays(1)),
    Weekly("Every week", Duration.ofDays(7)),
}

/**
 * A backup TableGrip makes by itself while it is open. Kept in settings; nothing runs unless
 * [dev.tablegrip.core.settings.Settings.scheduledBackups] is on, which it isn't by default.
 */
@Serializable
data class BackupSchedule(
    val id: String,
    val connectionId: String,
    /** Blank means the connection's own database. */
    val database: String = "",
    val frequency: BackupFrequency = BackupFrequency.Daily,
    /** Blank means `~/Backups/<connection>`. */
    val folder: String = "",
    /** Newest backups kept in the folder; older ones made by this schedule are deleted. 0 keeps all. */
    val keep: Int = 7,
    val enabled: Boolean = true,
    /** When it last finished, successfully or not, in epoch milliseconds; null if never. */
    val lastRun: Long? = null,
    val lastError: String? = null,
) {
    /** Whether it should run at [now]; a run missed while the app was closed is due as soon as it opens. */
    fun isDue(now: Instant): Boolean = enabled && (lastRun == null || Duration.between(Instant.ofEpochMilli(lastRun), now) >= frequency.interval)

    /** File names start with this, so pruning only touches this schedule's backups. */
    val prefix: String
        get() = "scheduled-$id-"

    companion object {
        /** Deletes all but the newest [keep] backups of [schedule] in [folder]; returns what was deleted. */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fun prune(folder: Path, schedule: BackupSchedule): List<Path> {
            if (schedule.keep <= 0 || !folder.isDirectory()) return emptyList()
            val mine = Files.list(folder).use { files -> files.filter { it.name.startsWith(schedule.prefix) }.toList() }.sortedByDescending { it.name }
            return mine.drop(schedule.keep).onEach { old -> runCatching { ToolRunner.discard(old) } }
        }
    }
}

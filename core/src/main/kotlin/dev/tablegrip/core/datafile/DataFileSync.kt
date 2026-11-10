package dev.tablegrip.core.datafile

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.DataFileOptions
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/** Where a data file stands against its working copy. */
sealed interface DataFileState {
    /**
     * The file holds everything the working copy does. [at] is when it was last written or read, in epoch millis.
     * [sessionOnly] names tables that exist only in the working copy, because the file holds a single table.
     */
    data class InSync(val at: Long, val sessionOnly: List<String> = emptyList()) : DataFileState

    data object Saving : DataFileState

    /**
     * Changes are kept in the working copy, not written, until the user decides: [save] with `force` writes them anyway,
     * [reload] discards them for what the file holds.
     */
    data class Held(val reason: Reason, val message: String) : DataFileState {
        enum class Reason {
            /** The file changed (or went away) on disk while the working copy had changes of its own. */
            ChangedOnDisk,

            /** Writing would lose something the file has, e.g. formulas. */
            LosesContent,
        }
    }

    /** The last write or read failed; the working copy keeps the changes, and the next change or [save] tries again. */
    data class Failed(val message: String) : DataFileState
}

/**
 * Keeps a data file and its [WorkingCopy] in step. Changes committed to the working copy are written to the file: right
 * after the table viewer, import or a schema tool commits, and within [PollMillis] of a commit from a console. A change
 * to the file from elsewhere is read back in when the working copy has nothing unsaved, and held for the user to decide
 * when it has. Writes go to a temporary file that then replaces the original, so the file is never half written.
 */
class DataFileSync internal constructor(
    /** The data file. */
    val file: Path,
    private val format: DataFileFormat,
    private val options: DataFileOptions,
    private val copy: WorkingCopy,
    private var document: DataFileDocument,
    /** Why changes are never written back, or null. Changes from elsewhere are still read in. */
    val readOnlyReason: String?,
    stamp: FileStamp?,
) : AutoCloseable {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The tables as the file has them, by lower-case name: what a save compares the working copy against. */
    private var known: Map<String, TableState> = emptyMap()
    private var stamp: FileStamp? = stamp
    private var seenVersion: Long = 0
    /** A file change already found to clash with unsaved changes, so polling doesn't re-check it every tick. */
    private var heldStamp: FileStamp? = null
    /** After a failed write, when polling may try again; doubles up to [MaxRetryMillis] while it keeps failing. */
    private var retryAt = 0L
    private var retryDelay = PollMillis

    /** Tables whose loss of content (see [DataFileDocument.confirmBeforeWriting]) the user accepted. */
    private val confirmed = HashSet<String>()

    private val _state = MutableStateFlow<DataFileState>(DataFileState.InSync(System.currentTimeMillis()))
    val state: StateFlow<DataFileState> = _state.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** Goes up each time the tables are read in again from the file, so open views can refresh. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** What the user should know about this file, e.g. formulas that saving replaces. */
    val notes: List<String>
        get() = document.notes

    internal fun initialize() {
        copy.read { known = copy.states().associateBy { it.name.lowercase() } }
        seenVersion = copy.dataVersion()
    }

    internal fun start() {
        scope.launch {
            while (isActive) {
                delay(PollMillis)
                runCatching { poll() }.onFailure { log.warn("Checking {} failed", file, it) }
            }
        }
    }

    /**
     * Writes the working copy's changes to the file, if it has any. Without [force] it holds them instead when the file
     * changed on disk since it was read, or writing would lose content the user hasn't agreed to lose. Never throws:
     * failures go to [state].
     */
    suspend fun save(force: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock { saveLocked(force) }
        }

    /** Reads the file again, replacing the working copy's tables and discarding their unsaved changes. Never throws. */
    suspend fun reload(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock { reloadLocked() }
        }

    /** One tick: reads the file in when it changed elsewhere, and writes the working copy out when it changed. */
    private suspend fun poll() {
        if (mutex.isLocked) return
        mutex.withLock {
            val current = FileStamp.of(file)
            if (current != stamp) {
                when {
                    current == null -> if (_state.value !is DataFileState.Held) hold(DataFileState.Held.Reason.ChangedOnDisk, tr("{0} was moved or deleted. Save to write it again, or close the connection.", file.name))
                    current == heldStamp -> Unit
                    !hasUnsavedChanges() -> reloadLocked()
                    else -> {
                        heldStamp = current
                        hold(DataFileState.Held.Reason.ChangedOnDisk, tr("{0} changed on disk while you had unsaved changes.", file.name))
                    }
                }
                return
            }
            if (copy.dataVersion() != seenVersion && System.currentTimeMillis() >= retryAt) saveLocked(force = false)
        }
    }

    private fun hasUnsavedChanges(): Boolean =
        copy.read {
            val states = copy.states()
            states.size != known.size || states.any { known[it.name.lowercase()]?.fingerprint != it.fingerprint }
        }

    private fun saveLocked(force: Boolean) {
        val version = copy.dataVersion()
        if (readOnlyReason != null) {
            seenVersion = version
            return
        }
        try {
            copy.read<ReadAgain?> {
                val states = copy.states()
                val plan = plan(states)
                if (plan.tables.none { it.changed || it.source == null } && plan.removed.isEmpty()) {
                    seenVersion = version
                    val held = _state.value as? DataFileState.Held
                    when {
                        // Nothing of ours is unsaved any more, so what changed on disk can simply be read.
                        held?.reason == DataFileState.Held.Reason.ChangedOnDisk && !force -> return@read ReadAgain
                        held == null || force || held.reason == DataFileState.Held.Reason.LosesContent ->
                            _state.value = DataFileState.InSync(System.currentTimeMillis(), plan.sessionOnly)
                    }
                    return@read null
                }
                // Held changes wait for a new commit or the user, rather than being re-checked every tick.
                val onDisk = FileStamp.of(file)
                if (!force && onDisk != stamp) {
                    seenVersion = version
                    heldStamp = onDisk
                    hold(DataFileState.Held.Reason.ChangedOnDisk, if (onDisk == null) tr("{0} was moved or deleted.", file.name) else tr("{0} changed on disk while you had unsaved changes.", file.name))
                    return@read null
                }
                val sources = plan.tables.filter { it.changed && it.source != null }.mapNotNull { it.source }.filter { it.lowercase() !in confirmed }.toSet()
                if (force) confirmed += sources.map { it.lowercase() }
                else document.confirmBeforeWriting(sources)?.let { reason ->
                    seenVersion = version
                    hold(DataFileState.Held.Reason.LosesContent, reason)
                    return@read null
                }
                _state.value = DataFileState.Saving
                write(plan)
                known = plan.written.associateBy { it.name.lowercase() }
                stamp = FileStamp.of(file)
                heldStamp = null
                seenVersion = version
                _state.value = DataFileState.InSync(System.currentTimeMillis(), plan.sessionOnly)
                retryAt = 0
                retryDelay = PollMillis
                null
            }?.let { reloadLocked() }
        } catch (e: Throwable) {
            // Left unseen, so the next tick tries again: closing the program that locks the file is enough to save.
            val failed = DataFileState.Failed(tr("Couldn't save {0}: {1}", file.name, describe(e)))
            if (_state.value != failed) log.warn("Saving {} failed", file, e)
            _state.value = failed
            retryAt = System.currentTimeMillis() + retryDelay
            retryDelay = minOf(retryDelay * 2, MaxRetryMillis)
        }
    }

    /** What a save found it should do instead. */
    private object ReadAgain

    private fun reloadLocked() {
        val before = FileStamp.of(file)
        try {
            val fresh = format.open(file, options)
            try {
                copy.load(fresh.tables, replacing = known.values.map { it.name })
            } catch (e: Throwable) {
                fresh.close()
                throw e
            }
            document.close()
            document = fresh
            copy.read { known = copy.states().filter { state -> fresh.tables.any { it.name.equals(state.name, ignoreCase = true) } }.associateBy { it.name.lowercase() } }
            stamp = before
            heldStamp = null
            confirmed.clear()
            seenVersion = copy.dataVersion()
            _revision.value++
            _state.value = DataFileState.InSync(System.currentTimeMillis())
        } catch (e: Throwable) {
            log.warn("Reading {} again failed", file, e)
            heldStamp = before
            _state.value = DataFileState.Failed(tr("Couldn't read {0} again: {1}", file.name, describe(e)))
        }
    }

    private fun hold(reason: DataFileState.Held.Reason, message: String) {
        _state.value = DataFileState.Held(reason, message)
    }

    /** What a save writes: every table, matched to the one the file has, and the file's tables that are gone. */
    private class Plan(val tables: List<OutputTable>, val written: List<TableState>, val removed: Set<String>, val sessionOnly: List<String>)

    private fun plan(states: List<TableState>): Plan {
        val unmatched = known.values.toMutableList()
        val matched = LinkedHashMap<TableState, TableState?>()
        for (state in states) {
            val same = unmatched.firstOrNull { it.name.equals(state.name, ignoreCase = true) }
            if (same != null) unmatched -= same
            matched[state] = same
        }
        // A table gone from the file's list and an identical new one is a rename.
        for ((state, source) in matched.entries.toList()) {
            if (source != null) continue
            unmatched.firstOrNull { it.fingerprint == state.fingerprint }?.let {
                unmatched -= it
                matched[state] = it
            }
        }
        if (!document.multiTable) {
            val primary =
                matched.entries.firstOrNull { it.value != null }?.key
                    ?: states.singleOrNull()
                    ?: throw DataFileException(
                        if (states.isEmpty()) tr("The table is gone; a {0} holds exactly one table.", format.type.displayName)
                        else tr("The file's table is gone and there are several others; a {0} holds exactly one. Rename the one to save to {1}.", format.type.displayName, known.values.firstOrNull()?.name ?: tr("the original name")),
                    )
            val source = matched[primary] ?: known.values.firstOrNull()
            val table = output(primary, source)
            return Plan(listOf(table), listOf(primary), emptySet(), states.filter { it !== primary }.map { it.name })
        }
        val tables = states.map { output(it, matched[it]) }
        return Plan(tables, states, unmatched.map { it.name }.toSet(), emptyList())
    }

    private fun output(state: TableState, source: TableState?): OutputTable =
        OutputTable(
            name = state.name,
            source = source?.name,
            changed = source == null || source.fingerprint != state.fingerprint,
            columns = state.columns,
            rows = { copy.rows(state.name, state.columns) },
        )

    private fun write(plan: Plan) {
        val directory = file.toAbsolutePath().parent ?: throw IOException(tr("{0} has no folder", file.name))
        val temporary = directory.resolve(".${file.name}.tablegrip-${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            document.write(temporary, plan.tables, plan.removed)
            // Keep the original's permissions where the file system has them.
            runCatching { Files.getPosixFilePermissions(file) }.getOrNull()?.let { runCatching { Files.setPosixFilePermissions(temporary, it) } }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { temporary.deleteIfExists() }
        }
    }

    private fun describe(e: Throwable): String =
        when (e) {
            is DataFileException -> e.message.orEmpty()
            is NoSuchFileException -> tr("{0} doesn't exist", e.file)
            is java.nio.file.AccessDeniedException -> tr("access denied to {0} (is it open in another program, or read-only?)", e.file)
            is java.nio.file.FileSystemException -> "${e.reason ?: e.javaClass.simpleName} (${e.file})"
            else -> e.message ?: e.javaClass.simpleName
        }

    /**
     * Stops watching, and writes anything committed since the last write (waiting up to a few seconds). Unsaved changes
     * that are held or keep failing stay in the working copy, which is then deleted.
     */
    override fun close() {
        scope.cancel()
        runBlocking {
            withTimeoutOrNull(CloseTimeoutMillis) {
                mutex.withLock {
                    if (readOnlyReason == null && runCatching { copy.dataVersion() != seenVersion }.getOrDefault(false) && _state.value !is DataFileState.Held) {
                        withContext(Dispatchers.IO) { saveLocked(force = false) }
                    }
                }
            }
        }
        document.close()
        copy.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(DataFileSync::class.java)
        const val PollMillis = 1_000L
        private const val CloseTimeoutMillis = 10_000L
        private const val MaxRetryMillis = 30_000L
    }
}

/** Enough of a file's attributes to tell that it changed: size, modification time and identity (a replaced file is new). */
internal data class FileStamp(val size: Long, val modified: FileTime, val key: Any?) {
    companion object {
        fun of(path: Path): FileStamp? =
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
            } catch (_: IOException) {
                null
            }
    }
}

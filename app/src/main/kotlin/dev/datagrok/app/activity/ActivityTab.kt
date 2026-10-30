package dev.datagrok.app.activity

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.activity.ActivityMonitor
import dev.datagrok.core.activity.ActivitySnapshot
import dev.datagrok.core.activity.ActivitySupport
import dev.datagrok.core.activity.SessionActivity
import dev.datagrok.core.activity.SessionState
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.Environment
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which sessions the monitor lists. */
enum class ActivityFilter(val label: String) {
    Running("Running"),
    Blocked("Blocked"),
    All("All"),
}

/** A session in the list, indented under the session whose locks it waits for. */
data class ActivityRow(val session: SessionActivity, val depth: Int, val blocking: Int)

/** Cancel or terminate, waiting for the user to confirm. */
data class PendingSignal(val session: SessionActivity, val terminate: Boolean, val reasons: List<String>)

/**
 * The server's sessions and who blocks whom, refreshed every few seconds while the tab is in front. Reads over a
 * connection of its own (see [ActivityMonitor]), opened on first refresh and closed with the tab.
 */
class ActivityTab(private val app: AppState, val config: ConnectionConfig) : WorkspaceTab {
    override val connectionId: String = config.id
    override val title: String = "Activity: ${config.name}"
    val support: ActivitySupport = ActivitySupport.of(config.type)

    var snapshot by mutableStateOf<ActivitySnapshot?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    /** Privileges the user lacks, so the list may be incomplete; shown until dismissed. */
    var missingPrivileges by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
        private set

    /** Seconds between refreshes; 0 pauses. */
    var interval by mutableStateOf(2)
    var filter by mutableStateOf(ActivityFilter.Running)
    /** Datagrok's own sessions (pool, consoles, this monitor) are noise most of the time. */
    var hideDatagrok by mutableStateOf(true)
    val search = TextFieldState()
    var selectedId by mutableStateOf<String?>(null)
    var pending by mutableStateOf<PendingSignal?>(null)

    private var monitor: ActivityMonitor? = null
    private val opening = Mutex()

    /** Whether the view is showing; [watch] refreshes then, the background loop otherwise. */
    private var visible = false

    /**
     * While the tab is open but not in front, a slow refresh keeps [stuck] current for the status bar. It never starts
     * the connection on its own: only after the tab has been shown once.
     */
    private val background =
        app.scope.launch {
            while (true) {
                delay(BackgroundInterval)
                if (!visible && interval > 0 && monitor != null) refresh()
            }
        }

    /** Sessions waiting on a lock for longer than [StuckAfter], for the status bar's warning. */
    val stuck: Int
        get() = snapshot?.let { snap -> snap.sessions.count { it.state == SessionState.Blocked && (it.runningFor(snap.serverTime) ?: Duration.ZERO) >= StuckAfter } } ?: 0

    val selected: SessionActivity?
        get() = snapshot?.sessions?.firstOrNull { it.id == selectedId }

    val signalsAllowed: Boolean
        get() = !config.readOnly

    /** Runs while the tab is shown; the view launches it, so leaving the tab stops refreshing. */
    suspend fun watch() {
        if (support != ActivitySupport.Supported) return
        visible = true
        try {
            while (true) {
                refresh()
                val seconds = interval
                if (seconds <= 0) awaitCancellation()
                delay(seconds * 1_000L)
            }
        } finally {
            visible = false
        }
    }

    /** From the status bar's warning: shows what is blocked. */
    fun showBlocked() {
        filter = ActivityFilter.Blocked
        app.workspace.selected = this
    }

    suspend fun refresh() {
        loading = true
        try {
            val monitor = monitor()
            snapshot = monitor.snapshot()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The monitor's own connection may be what broke (network, or someone terminated it): reopen next time.
            closeMonitor()
            error = "Couldn't read sessions: ${e.message ?: e}"
        } finally {
            loading = false
        }
    }

    fun refreshNow() {
        app.scope.launch { refresh() }
    }

    private suspend fun monitor(): ActivityMonitor =
        opening.withLock {
            monitor ?: app.databases.database(config).openActivityMonitor().also { opened ->
                monitor = opened
                missingPrivileges = opened.missingPrivileges()
            }
        }

    /** The sessions to list, blocked ones under their blocker, after the filter and search. */
    fun rows(): List<ActivityRow> = activityRows(snapshot?.sessions.orEmpty(), filter, search.text.toString(), hideDatagrok)

    fun runningFor(session: SessionActivity): Duration? = snapshot?.let { session.runningFor(it.serverTime) }

    /** Cancel needs a confirmation on guarded connections; terminate always, since it drops the client's connection. */
    fun requestSignal(session: SessionActivity, terminate: Boolean) {
        if (!signalsAllowed || session.own) return
        val reasons =
            buildList {
                if (config.environment == Environment.Production) add("${config.name} is a Production connection.")
                else if (config.confirmWrites) add("${config.name} asks before changes.")
                if (terminate) {
                    add("Terminating ends the session: its client loses the connection.")
                    if (session.transactionStart != null) add("It has an open transaction, which will be rolled back.")
                } else {
                    add("Cancelling stops the running query; its client gets an error.")
                }
                if (session.application == DatagrokApplication) add("This is one of Datagrok's own sessions (a console or the explorer).")
            }
        val guarded = config.environment == Environment.Production || config.confirmWrites
        if (terminate || guarded) pending = PendingSignal(session, terminate, reasons) else send(session, terminate = false)
    }

    fun confirmPending() {
        val signal = pending ?: return
        pending = null
        send(signal.session, signal.terminate)
    }

    private fun send(session: SessionActivity, terminate: Boolean) {
        app.scope.launch {
            val verb = if (terminate) "terminate" else "cancel"
            try {
                val monitor = monitor()
                val done = if (terminate) monitor.terminate(session.id) else monitor.cancel(session.id)
                if (done) app.status = "${if (terminate) "Terminated" else "Cancelled"} session ${session.id}"
                else error = "Couldn't $verb session ${session.id}: it may have finished already, or your user may not be allowed to signal it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't $verb session ${session.id}: ${e.message ?: e}"
            }
            refresh()
        }
    }

    fun openInConsole(session: SessionActivity) {
        session.query?.let { app.workspace.openConsole(config, it) }
    }

    private fun closeMonitor() {
        val closing = monitor ?: return
        monitor = null
        app.scope.launch(Dispatchers.IO) { closing.close() }
    }

    override fun dispose() {
        background.cancel()
        closeMonitor()
    }

    companion object {
        private const val BackgroundInterval = 15_000L
        private val StuckAfter: Duration = Duration.ofSeconds(5)

        /** The name Datagrok gives its sessions: `ApplicationName` on PostgreSQL, `program_name` on MySQL and MariaDB. */
        const val DatagrokApplication = "Datagrok"
    }
}

/**
 * [sessions] as [ActivityFilter] and [search] pick them, each blocked session indented under the first session it
 * waits for. Sessions others wait for are always kept, so the tree shows what is stuck behind what.
 */
internal fun activityRows(sessions: List<SessionActivity>, filter: ActivityFilter, search: String, hideDatagrok: Boolean): List<ActivityRow> {
    val query = search.trim()
    val blockers = sessions.flatMap { it.blockedBy }.toSet()
    fun shown(s: SessionActivity): Boolean {
        if (s.id in blockers) return true
        if (hideDatagrok && (s.own || s.application == ActivityTab.DatagrokApplication)) return false
        if (query.isNotEmpty() && listOf(s.id, s.user, s.database, s.application, s.client, s.query).none { it?.contains(query, ignoreCase = true) == true }) return false
        return when (filter) {
            ActivityFilter.Running -> s.state != SessionState.Idle
            ActivityFilter.Blocked -> s.state == SessionState.Blocked
            ActivityFilter.All -> true
        }
    }
    val visible = sessions.filter(::shown)
    val ids = visible.map { it.id }.toSet()
    val children = visible.filter { s -> s.blockedBy.any { it in ids } }.groupBy { s -> s.blockedBy.first { it in ids } }
    val waiting = sessions.flatMap { s -> s.blockedBy.map { it to s.id } }.groupBy({ it.first }, { it.second })
    val rows = mutableListOf<ActivityRow>()
    val placed = mutableSetOf<String>()
    fun place(s: SessionActivity, depth: Int) {
        if (!placed.add(s.id)) return
        rows += ActivityRow(s, depth, waiting[s.id].orEmpty().size)
        children[s.id].orEmpty().forEach { place(it, depth + 1) }
    }
    // Roots first; sessions waiting on each other in a cycle (a deadlock the server hasn't broken yet) have none.
    visible.filter { s -> s.blockedBy.none { it in ids } }.forEach { place(it, 0) }
    visible.forEach { place(it, 0) }
    return rows
}

/** "850 ms", "12 s", "3 min 05 s", "2 h 10 min". */
internal fun formatDuration(duration: Duration): String {
    val ms = duration.toMillis()
    val s = duration.seconds
    return when {
        ms < 1_000 -> "$ms ms"
        s < 60 -> "$s s"
        s < 3_600 -> "${s / 60} min %02d s".format(s % 60)
        else -> "${s / 3_600} h ${s % 3_600 / 60} min"
    }
}

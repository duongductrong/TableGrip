package dev.datagrok.app.workspace

import dev.datagrok.app.AppState
import dev.datagrok.app.LeftTool
import dev.datagrok.core.session.Session
import dev.datagrok.core.session.SessionStore
import dev.datagrok.core.session.SessionTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Keeps the workspace across restarts, like DataGrip and TablePlus: open consoles (with unsaved text) and tables (with
 * their filter) come back as they were. Saved every few seconds while something changed, so a crash loses little,
 * and once more on quit.
 */
class SessionKeeper(private val app: AppState, private val store: SessionStore = SessionStore()) {
    private var last: Session? = null
    private var autosave: Job? = null

    fun snapshot(): Session {
        val tabs = app.workspace.tabs.toList()
        val saved = tabs.mapNotNull { tab -> tab.toSession()?.let { tab to it } }
        return Session(
            tabs = saved.map { it.second },
            selected = saved.indexOfFirst { it.first == app.workspace.selected },
            leftTool = app.leftTool?.name,
            connectionId = app.currentConnectionId,
        )
    }

    /** Reopens the last session's tabs whose connection still exists. */
    fun restore() {
        val session = runCatching { store.load() }.getOrElse { Session() }
        last = session
        val workspace = app.workspace
        val reopened =
            session.tabs.map { saved ->
                val config = app.connections.byId(saved.connectionId) ?: return@map null
                when (saved) {
                    is SessionTab.Console -> workspace.restoreConsole(config, saved)
                    is SessionTab.Table -> workspace.restoreTable(config, saved)
                }
                workspace.tabs.lastOrNull()
            }
        reopened.getOrNull(session.selected)?.let { workspace.selected = it }
        app.leftTool = session.leftTool?.let { name -> LeftTool.entries.firstOrNull { it.name == name } }
        session.connectionId?.takeIf { app.connections.byId(it) != null && workspace.selected == null }?.let { app.currentConnectionId = it }
    }

    /** Saves every [intervalMillis] while the workspace changed since the last save. */
    fun startAutosave(intervalMillis: Long = 5_000) {
        autosave?.cancel()
        autosave = app.scope.launch {
            while (isActive) {
                delay(intervalMillis)
                saveIfChanged()
            }
        }
    }

    fun stopAutosave() {
        autosave?.cancel()
        autosave = null
    }

    /** Writes the session now, on the calling thread; for quitting, when there's no time to hop threads. */
    fun saveNow() {
        val session = snapshot()
        last = session
        runCatching { store.save(session) }.onFailure { log.warn("Failed to save the session", it) }
    }

    private suspend fun saveIfChanged() {
        val session = snapshot()
        if (session == last) return
        last = session
        withContext(Dispatchers.IO) { runCatching { store.save(session) }.onFailure { log.warn("Failed to save the session", it) } }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SessionKeeper::class.java)
    }
}

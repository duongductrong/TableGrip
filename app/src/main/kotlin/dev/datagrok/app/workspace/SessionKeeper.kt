package dev.datagrok.app.workspace

import dev.datagrok.app.AppState
import dev.datagrok.app.LeftTool
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.datagrok.core.session.Session
import dev.datagrok.core.session.WindowBounds
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
            agentOpen = app.agentOpen,
            window = windowBounds(),
            sidebarSplit = app.sidebarSplit.dividerPosition,
            agentSplit = app.agentSplit.dividerPosition,
        )
    }

    /**
     * Reopens the last session's tabs whose connection still exists, and the layout around them, as far as Settings →
     * Startup & Layout asks for.
     */
    fun restore() {
        val session = runCatching { store.load() }.getOrElse { Session() }
        last = session
        val settings = app.settings
        val workspace = app.workspace
        val reopened =
            session.tabs.takeIf { settings.restoreTabs }.orEmpty().map { saved ->
                val config = app.connections.byId(saved.connectionId) ?: return@map null
                when (saved) {
                    is SessionTab.Console -> workspace.restoreConsole(config, saved)
                    is SessionTab.Table -> workspace.restoreTable(config, saved)
                }
                workspace.tabs.lastOrNull()
            }
        reopened.getOrNull(session.selected)?.let { workspace.selected = it }
        val tool = if (settings.startupTool == "LastUsed") session.leftTool else settings.startupTool
        app.leftTool = tool?.let { name -> LeftTool.entries.firstOrNull { it.name == name } }
        app.agentOpen =
            when (settings.startupAgent) {
                "Open" -> true
                "LastUsed" -> session.agentOpen
                else -> false
            }
        if (settings.rememberPanelSizes) {
            session.sidebarSplit?.let { app.sidebarSplit.dividerPosition = it.coerceIn(0.1f, 0.6f) }
            session.agentSplit?.let { app.agentSplit.dividerPosition = it.coerceIn(0.3f, 0.9f) }
        }
        if (settings.rememberWindowBounds) session.window?.let(::restoreWindow)
        session.connectionId?.takeIf { app.connections.byId(it) != null && workspace.selected == null }?.let { app.currentConnectionId = it }
    }

    private fun windowBounds(): WindowBounds? {
        val state = app.windowState
        val position = state.position as? WindowPosition.Absolute ?: return null
        return WindowBounds(position.x.value, position.y.value, state.size.width.value, state.size.height.value, state.placement == WindowPlacement.Maximized)
    }

    /** Puts the window back where it was, unless that place is no longer on any screen (a monitor was unplugged). */
    private fun restoreWindow(bounds: WindowBounds) {
        val state = app.windowState
        state.size = DpSize(bounds.width.coerceAtLeast(640f).dp, bounds.height.coerceAtLeast(420f).dp)
        val onScreen =
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
                    device.defaultConfiguration.bounds.contains(bounds.x.toInt() + 40, bounds.y.toInt() + 20)
                }
            }.getOrDefault(false)
        if (onScreen) state.position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
        if (bounds.maximized) state.placement = WindowPlacement.Maximized
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

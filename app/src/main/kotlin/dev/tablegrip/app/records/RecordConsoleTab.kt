package dev.tablegrip.app.records

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.ResultGridModel
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.session.SessionTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A console in the engine's own language (Dev Tools requests, CQL, Cypher…): a statement editor above, and the reply
 * below, as rows when the engine returns rows and as text otherwise. ⌘⏎ runs the selection, or everything.
 */
class RecordConsoleTab(private val app: AppState, val config: ConnectionConfig, name: String, initial: String = "") : WorkspaceTab {
    override val connectionId = config.id
    override val title = name

    val editor = TextFieldState(initial)

    var language by mutableStateOf<RecordLanguage?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    /** The last reply: rows for the grid, or text; null before the first run. */
    var reply by mutableStateOf<ConsoleReply?>(null)
        private set
    var model by mutableStateOf<GridModel?>(null)
        private set
    val grid = DataGridState()

    var error by mutableStateOf<String?>(null)
        private set

    /** What ran last, for the status line. */
    var ran by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    init {
        app.scope.launch {
            runCatching { language = app.databases.records(config).consoleLanguage }
        }
    }

    /** Runs the selected text, or the whole editor when nothing is selected. */
    fun run() {
        if (running) return
        val selection = editor.selection
        val text = editor.text.toString()
        val statement = (if (selection.collapsed) text else text.substring(selection.min, selection.max)).trim()
        if (statement.isEmpty()) return
        running = true
        error = null
        job =
            app.scope.launch {
                try {
                    val store = app.databases.records(config)
                    language = store.consoleLanguage
                    val result = store.execute(statement)
                    reply = result
                    model = (result as? ConsoleReply.Table)?.let { ResultGridModel(it.rows) }
                    grid.selection = null
                    ran = statement.lineSequence().first().take(80)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                    reply = null
                    model = null
                } finally {
                    running = false
                }
            }
    }

    fun cancel() {
        job?.cancel()
        running = false
    }

    val help: String
        get() = language?.help ?: tr("Connecting…")

    override fun toSession(): SessionTab = SessionTab.RecordConsole(config.id, editor.text.toString())

    override val activeGrid: Pair<GridModel, DataGridState>?
        get() = model?.let { it to grid }

    override fun dispose() {
        job?.cancel()
    }
}

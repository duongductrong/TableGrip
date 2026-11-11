package dev.tablegrip.app.records

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.ResultGridModel
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.completion.CompletionResult
import dev.tablegrip.core.completion.StatementCompleter
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.RecordConnection
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

    /** The connected store, once it is: read-only profiles complete only what it says reads. */
    private var store: RecordConnection? = null

    /** Keyspaces, tables, fields, labels… for completion, loaded as they're asked for. */
    internal val catalog = RecordCatalogCache(app.scope, config.type.family) { app.databases.records(config) }

    private val completer = StatementCompleter.of(config.type.family)

    /** Completion in the console's language, or null for an engine whose console has none. */
    internal val completion: CompletionSource? =
        completer?.let { completer ->
            CompletionSource(
                complete = { text, caret ->
                    val isRead = if (config.readOnly) { statement: String -> store?.isRead(statement) ?: false } else null
                    runCatching { completer.complete(text, caret, catalog, isRead) }.getOrElse { CompletionResult.empty(caret) }
                },
                dialect = SqlDialect.Generic,
                cache = null,
            )
        }

    init {
        app.scope.launch {
            runCatching {
                val connected = app.databases.records(config)
                store = connected
                language = connected.consoleLanguage
            }
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
                    // The statement may have made or dropped keyspaces, tables, labels or fields.
                    catalog.statementRan()
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
        catalog.dispose()
    }
}

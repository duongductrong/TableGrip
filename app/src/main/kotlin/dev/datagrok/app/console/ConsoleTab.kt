package dev.datagrok.app.console

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.grid.ResultGridModel
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.QuerySession
import dev.datagrok.core.history.HistoryEntry
import dev.datagrok.app.completion.CompletionSource
import dev.datagrok.core.completion.SqlCompleter
import dev.datagrok.core.sql.SqlClassifier
import dev.datagrok.core.sql.StatementKind
import dev.datagrok.core.sql.SqlSplitter
import dev.datagrok.core.sql.SqlStatement
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RunMode {
    /** The selection if there is one, else the statement at the caret. */
    Current,
    /** The selection if there is one, else the whole script. */
    All,
}

/** Statements waiting for the user to confirm, with why they need confirming. */
data class WriteConfirmation(val statements: List<String>, val reasons: List<String>, val proceed: () -> Unit)

data class OutputLine(val time: String, val text: String, val isError: Boolean)

class ResultSetView(val title: String, val sql: String, val rows: QueryResult.Rows) {
    val model = ResultGridModel(rows)
    val grid = DataGridState()
}

/** A SQL console bound to one connection, with its own dedicated session. */
class ConsoleTab(
    private val app: AppState,
    val config: ConnectionConfig,
    override val title: String,
    initialSql: String,
) : WorkspaceTab {
    override val connectionId: String = config.id
    val syntax = SqlDialect.of(config.type).syntax
    private val splitter = SqlSplitter(syntax)
    private val classifier = SqlClassifier(syntax)

    val editor = TextFieldState(initialSql).apply { edit { placeCursorAtEnd() } }
    val output = mutableStateListOf<OutputLine>()
    val results = mutableStateListOf<ResultSetView>()

    /** Index into the result panel tabs: 0 is Output, 1.. are [results], then History. */
    var selectedResultTab by mutableStateOf(0)
    var running by mutableStateOf(false)
        private set

    /** False in manual-commit mode. Mirrors the session, which may not exist yet. */
    var autoCommit by mutableStateOf(true)
        private set
    /** Statements run in manual mode since the last commit or rollback. */
    var uncommitted by mutableStateOf(0)
        private set
    var pendingConfirmation by mutableStateOf<WriteConfirmation?>(null)
    /** Set while asking what to do with an open transaction before closing the tab. */
    var pendingClose by mutableStateOf<(() -> Unit)?>(null)

    val hasOpenTransaction: Boolean
        get() = !autoCommit && uncommitted > 0

    private var session: QuerySession? = null
    private var job: Job? = null
    private var resultCount = 0

    /** Completion for this console's connection; created lazily so consoles don't load metadata until typed in. */
    val completion: CompletionSource by lazy {
        val cache = app.schemaCache(config)
        val completer = SqlCompleter(dialect)
        CompletionSource({ text, caret -> completer.complete(text, caret, cache) }, dialect, cache)
    }

    private val dialect = SqlDialect.of(config.type)

    /** Runs the statement at the caret under EXPLAIN, in the dialect's syntax. */
    fun explain() {
        if (running) return
        val sql = statementsFor(RunMode.Current).firstOrNull() ?: return
        val prefix =
            when (dialect) {
                SqlDialect.SQLite -> "EXPLAIN QUERY PLAN "
                else -> "EXPLAIN "
            }
        start(listOf(prefix + sql))
    }

    fun run(mode: RunMode) {
        if (running) return
        val statements = statementsFor(mode)
        if (statements.isEmpty()) return
        val infos = statements.map(classifier::classify)

        if (config.readOnly && infos.any { it.changesData }) {
            val verbs = infos.filter { it.changesData }.joinToString { it.verb }.ifEmpty { "writes" }
            log("This connection is read-only: $verbs not run.", isError = true)
            selectedResultTab = 0
            return
        }
        val reasons = buildList {
            infos.mapNotNullTo(this) { it.danger }
            if (config.confirmWrites && infos.any { it.changesData } && isEmpty()) {
                add("${config.name} asks before changing data (${config.environment.label.lowercase()} connection)")
            }
        }
        if (reasons.isNotEmpty()) {
            pendingConfirmation = WriteConfirmation(statements, reasons.distinct()) { start(statements) }
            return
        }
        start(statements)
    }

    fun confirmPending() {
        val confirmation = pendingConfirmation ?: return
        pendingConfirmation = null
        confirmation.proceed()
    }

    private fun start(statements: List<String>) {
        job = app.scope.launch {
            running = true
            results.clear()
            try {
                execute(statements)
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun statementsFor(mode: RunMode): List<String> {
        val text = editor.text.toString()
        val selection = editor.selection
        if (!selection.collapsed) return splitter.split(text.substring(selection.min, selection.max)).map(SqlStatement::text)
        return when (mode) {
            RunMode.Current -> listOfNotNull(splitter.statementAt(text, selection.start)?.text)
            RunMode.All -> splitter.split(text).map(SqlStatement::text)
        }
    }

    private suspend fun session(): QuerySession =
        session
            ?: app.databases.database(config).openSession().also {
                if (!autoCommit) it.setAutoCommit(false)
                session = it
            }

    /** Switching to auto-commit commits pending work, as JDBC does. */
    fun changeAutoCommit(enabled: Boolean) {
        if (enabled == autoCommit || running) return
        app.scope.launch {
            val pending = uncommitted
            runCatching { session?.setAutoCommit(enabled) }
                .onSuccess {
                    autoCommit = enabled
                    uncommitted = 0
                    log(if (enabled) "Auto-commit on" + (if (pending > 0) ": committed $pending pending statement(s)" else "") else "Manual commit: changes stay pending until Commit")
                }
                .onFailure { log("Could not change commit mode: ${it.message}", isError = true) }
        }
    }

    fun commit() = endTransaction(commit = true)

    fun rollback() = endTransaction(commit = false)

    private fun endTransaction(commit: Boolean, then: () -> Unit = {}) {
        val session = session ?: return then()
        app.scope.launch {
            runCatching { if (commit) session.commit() else session.rollback() }
                .onSuccess {
                    log(if (commit) "Committed" else "Rolled back")
                    uncommitted = 0
                    then()
                }
                .onFailure { log("${if (commit) "Commit" else "Rollback"} failed: ${it.message}", isError = true) }
        }
    }

    /** Resolves [pendingClose] after committing or rolling back. */
    fun closeAfter(commit: Boolean) {
        val close = pendingClose ?: return
        pendingClose = null
        endTransaction(commit, close)
    }

    private suspend fun execute(statements: List<String>) {
        val session =
            try {
                session()
            } catch (e: Exception) {
                log("Could not connect: ${e.message}", isError = true)
                selectedResultTab = 0
                return
            }

        for (sql in statements) {
            val startedAt = System.currentTimeMillis()
            try {
                val result = session.execute(sql, app.settings.maxResultRows, app.settings.queryTimeoutSeconds)
                uncommitted = session.uncommittedStatements
                // New or changed tables should show up in completion right away.
                if (classifier.classify(sql).kind == StatementKind.Ddl) app.forgetSchema(config.id)
                record(sql, startedAt, result.elapsedMillis, error = null)
                when (result) {
                    is QueryResult.Rows -> {
                        val suffix = if (result.truncated) " (first ${result.rows.size} shown)" else ""
                        log("${result.rows.size} row(s) retrieved in ${result.elapsedMillis} ms$suffix: ${sql.preview()}")
                        results += ResultSetView("Result ${++resultCount}", sql, result)
                    }
                    is QueryResult.Update -> log("${result.affectedRows} row(s) affected in ${result.elapsedMillis} ms: ${sql.preview()}")
                }
            } catch (e: CancellationException) {
                log("Cancelled: ${sql.preview()}", isError = true)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                record(sql, startedAt, System.currentTimeMillis() - startedAt, error = message)
                log("$message\n  in: ${sql.preview()}", isError = true)
                if (!session.isValid()) dropSession()
                selectedResultTab = 0
                return
            }
        }
        selectedResultTab = if (results.isEmpty()) 0 else results.size
    }

    private suspend fun record(sql: String, startedAt: Long, elapsed: Long, error: String?) {
        val entry = HistoryEntry(config.id, sql, startedAt, elapsed, error)
        withContext(Dispatchers.IO) { runCatching { app.history.append(entry) } }
    }

    fun recentHistory(): List<HistoryEntry> = runCatching { app.history.recent(config.id) }.getOrDefault(emptyList())

    /** Appends a statement from history at the end of the script. */
    fun insert(sql: String) {
        editor.edit {
            val separator = if (length == 0 || toString().endsWith("\n")) "" else "\n"
            append("$separator$sql;\n")
            placeCursorAtEnd()
        }
    }

    private fun log(text: String, isError: Boolean = false) {
        output += OutputLine(LocalTime.now().format(TimeFormat), text, isError)
    }

    /** The connection is gone, and with it any open transaction. */
    private fun dropSession() {
        runCatching { session?.close() }
        session = null
        uncommitted = 0
    }

    override fun dispose() {
        job?.cancel()
        // Closing may block on the network; don't do it on the UI thread.
        val toClose = session ?: return
        session = null
        app.scope.launch(Dispatchers.IO) { runCatching { toClose.close() } }
    }

    companion object {
        private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

        private fun String.preview(): String = lineSequence().first().let { if (it.length > 80) it.take(80) + "…" else it }
    }
}

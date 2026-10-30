package dev.datagrok.app.console

import dev.datagrok.core.connection.Capability
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.grid.GridModel
import dev.datagrok.app.grid.ResultGridModel
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.assist.AssistResult
import dev.datagrok.core.assist.SchemaDigest
import dev.datagrok.core.assist.SqlErrors
import dev.datagrok.core.assist.SqlFix
import dev.datagrok.core.assist.SqlFixTask
import dev.datagrok.core.assist.SqlNameFixer
import dev.datagrok.core.assist.reviewFix
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.driver.QueryError
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.QuerySession
import dev.datagrok.core.driver.ReadOnlyViolationException
import dev.datagrok.core.history.HistoryEntry
import dev.datagrok.core.session.SessionTab
import dev.datagrok.app.completion.CompletionSource
import dev.datagrok.core.completion.SqlCompleter
import dev.datagrok.core.sql.SqlClassifier
import dev.datagrok.core.sql.StatementKind
import dev.datagrok.core.sql.SqlParameters
import dev.datagrok.core.sql.SqlSplitter
import dev.datagrok.core.sql.SqlStatement
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

enum class RunMode {
    /** The selection if there is one, else the statement at the caret. */
    Current,
    /** The selection if there is one, else the whole script. */
    All,
}

/** What one run produced: its result sets, and the log lines it wrote (row counts, errors). */
class RunOutcome(val results: List<ResultSetView>, val log: List<OutputLine>)

/** Parameters ([names]) the statements need values for before they run; [run] takes them by name. */
class ParameterRequest(val names: List<String>, val run: (Map<String, String>) -> Unit)

/** Statements waiting for the user to confirm, with why they need confirming. */
data class WriteConfirmation(val statements: List<String>, val reasons: List<String>, val proceed: () -> Unit)

/** A statement the server refused, as it was sent, with why. */
class StatementFailure(val sql: String, val error: QueryError)

/** [failure] is set on the line logging a failed statement, so it can be fixed from there. */
data class OutputLine(val time: String, val text: String, val isError: Boolean, val failure: StatementFailure? = null)

/** Fixing a failed statement, one at a time per console: asking an agent, a fix to review, or why there is none. */
sealed interface FixState {
    val failure: StatementFailure

    data class Asking(override val failure: StatementFailure, val agent: String?) : FixState

    /** [sql] replaces the failed statement once the user applies it; [warnings] are what to notice first. */
    data class Proposed(override val failure: StatementFailure, val sql: String, val explanation: String, val source: String, val warnings: List<String>) : FixState

    data class NoFix(override val failure: StatementFailure, val message: String) : FixState
}

class ResultSetView(val title: String, val sql: String, val rows: QueryResult.Rows) {
    val model = ResultGridModel(rows)
    val grid = DataGridState()
    /** Pinned results survive the next run, like DataGrip's pinned result tabs, to compare before and after. */
    var pinned by mutableStateOf(false)
}

/** A SQL console bound to one connection, with its own dedicated session. */
class ConsoleTab(
    private val app: AppState,
    val config: ConnectionConfig,
    private val name: String,
    initialSql: String,
    /** The saved query or script file the console edits, if it was opened from one. */
    file: File? = null,
    /** The file's content when it differs from [initialSql], i.e. the console reopens with unsaved edits. */
    savedText: String? = null,
    /** Where the caret starts; null puts it at the end of [initialSql]. */
    caret: Int? = null,
) : WorkspaceTab {
    /** A new, empty console starts with blank lines to click into instead of a single one. */
    private val startText = initialSql.ifEmpty { "\n".repeat(BlankConsoleLines - 1) }

    override val connectionId: String = config.id

    /** Where the script was last saved; null until the first save. */
    var file by mutableStateOf(file)
        private set
    /** The script as last saved (or as the console opened with), to tell whether there are unsaved edits. */
    private var savedText by mutableStateOf(savedText ?: startText)

    override val title: String
        get() = file?.name ?: name
    val syntax = SqlDialect.of(config.type).syntax
    private val splitter = SqlSplitter(syntax)
    private val classifier = SqlClassifier(syntax)
    private val parameters = SqlParameters(syntax)

    val editor = TextFieldState(startText, TextRange((caret ?: if (initialSql.isEmpty()) 0 else startText.length).coerceIn(0, startText.length)))
    val output = mutableStateListOf<OutputLine>()
    val results = mutableStateListOf<ResultSetView>()

    /** Index into the result panel tabs: 0 is Output, 1.. are [results], then History. */
    var selectedResultTab by mutableStateOf(0)

    /** Removes one result tab, keeping the selection on the same tab or its nearest neighbour. */
    fun closeResult(view: ResultSetView) {
        val index = results.indexOf(view)
        if (index < 0) return
        val tabIndex = index + 1
        results.removeAt(index)
        selectedResultTab =
            when {
                selectedResultTab > tabIndex -> selectedResultTab - 1
                selectedResultTab == tabIndex -> if (index < results.size) tabIndex else index
                else -> selectedResultTab
            }
    }

    var running by mutableStateOf(false)
        private set

    /** False in manual-commit mode. Mirrors the session, which may not exist yet. */
    var autoCommit by mutableStateOf(true)
        private set
    /** Statements run in manual mode since the last commit or rollback. */
    var uncommitted by mutableStateOf(0)
        private set
    var pendingConfirmation by mutableStateOf<WriteConfirmation?>(null)
    /** Set while asking for the values of the script's `:name` / `${'$'}{name}` parameters. */
    var pendingParameters by mutableStateOf<ParameterRequest?>(null)
    /** Set while asking what to do with an open transaction before closing the tab. */
    var pendingClose by mutableStateOf<(() -> Unit)?>(null)
    /** Set while asking whether to save the script before closing the tab. */
    var pendingScriptClose by mutableStateOf<(() -> Unit)?>(null)

    val hasOpenTransaction: Boolean
        get() = !autoCommit && uncommitted > 0

    val hasUnsavedScript: Boolean
        get() = editor.text.toString() != savedText

    override val hasUnsavedWork: Boolean
        get() = hasOpenTransaction || hasUnsavedScript

    /** Unsaved text survives quitting in the session; an open transaction doesn't. */
    override val losesWorkOnQuit: Boolean
        get() = hasOpenTransaction

    override fun confirmQuit(proceed: () -> Unit) {
        if (hasOpenTransaction) pendingClose = proceed else proceed()
    }

    override fun toSession(): SessionTab =
        SessionTab.Console(config.id, editor.text.toString(), file?.path, editor.selection.start, savedText.takeIf { it != editor.text.toString() })

    override val activeGrid: Pair<GridModel, DataGridState>?
        get() = results.getOrNull(selectedResultTab - 1)?.let { it.model to it.grid }

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
    private val nameFixer = SqlNameFixer(dialect)
    private val completer = SqlCompleter(dialect)

    /** The fix being asked for or reviewed; null when there is none. */
    var fix by mutableStateOf<FixState?>(null)
        private set
    private var fixJob: Job? = null

    /** A misspelt table or column in [failure] corrected from completion's schema, without asking anyone. */
    fun quickFix(failure: StatementFailure): SqlFix? = nameFixer.fix(failure.sql, failure.error, app.schemaCache(config))

    fun proposeQuickFix(failure: StatementFailure, quick: SqlFix) {
        val sql = quick.sql ?: return
        cancelFix()
        fix = FixState.Proposed(failure, sql, quick.explanation, "Quick fix", reviewFix(failure.sql, sql, syntax))
    }

    /** Whether this connection may be described to an agent, which a fix needs. */
    val canAskAgent: Boolean
        get() = app.aiAccess(config).sharesSchema

    /**
     * Asks the chat's agent for a fix. It gets the statement, the error and, where the connection shares it, the schema
     * with the statement's tables first; never data. The answer is only proposed: [applyFix] puts it in the editor.
     */
    fun askAgentToFix(failure: StatementFailure) {
        if (!canAskAgent) return
        cancelFix()
        fix = FixState.Asking(failure, app.agent.selected?.descriptor?.name)
        fixJob =
            app.scope.launch {
                val agent = app.agent.assistAgent()
                if (agent == null) {
                    fix = FixState.NoFix(failure, "No agent is available. Install a command-line agent or set up an API provider in Settings › Agent.")
                    return@launch
                }
                fix = FixState.Asking(failure, agent.descriptor.name)
                val focus = completer.tablesIn(failure.sql).map { it.second }
                val schema =
                    runCatching { SchemaDigest.read(app.databases.database(config), config.database, FixSchemaBudget, focus) }
                        .onFailure { log.debug("No schema for the fix", it) }
                        .getOrNull()
                val nearMisses = SqlErrors.missingName(failure.error)?.let { nameFixer.nearMisses(failure.sql, it, app.schemaCache(config)) }.orEmpty()
                val task = SqlFixTask(dialect, config.type.displayName, failure.sql, failure.error, schema, nearMisses)
                fix =
                    when (val result = app.agent.assist.run(agent, task)) {
                        is AssistResult.Ok -> {
                            val sql = result.value.sql
                            if (sql == null) FixState.NoFix(failure, result.value.explanation)
                            else FixState.Proposed(failure, sql, result.value.explanation, result.agent, reviewFix(failure.sql, sql, syntax))
                        }
                        is AssistResult.Invalid -> FixState.NoFix(failure, "${agent.descriptor.name} didn't return a usable fix: ${result.reasons.first()}")
                        is AssistResult.Failed -> FixState.NoFix(failure, result.message)
                    }
            }
    }

    fun cancelFix() {
        fixJob?.cancel()
        fixJob = null
        fix = null
    }

    /**
     * Puts the proposed fix in place of the failed statement (one undo step), or at the end when that statement isn't
     * in the editor any more; [andRun] then runs it with the usual checks.
     */
    fun applyFix(andRun: Boolean = false) {
        val proposal = fix as? FixState.Proposed ?: return
        fix = null
        val range = locate(proposal.failure.sql)
        if (range == null) {
            insert(proposal.sql)
        } else {
            editor.edit {
                replace(range.first, range.second, proposal.sql)
                selection = TextRange(range.first, range.first + proposal.sql.length)
            }
        }
        if (andRun) run(proposal.sql)
    }

    /** Hands the failure to the chat, as a draft the user can add to before sending. */
    fun discussInChat(failure: StatementFailure) {
        app.agent.draft = "This ${config.type.displayName} statement failed:\n```sql\n${failure.sql.trim()}\n```\nError: ${failure.error.message.trim()}\n\nHow do I fix it?"
        app.agentOpen = true
    }

    /** Where [sql] is in the editor, as start until end: the occurrence nearest the caret, ignoring whitespace changes. */
    private fun locate(sql: String): Pair<Int, Int>? {
        val target = sql.normalized()
        val caret = editor.selection.start
        return splitter.split(editor.text.toString())
            .filter { it.text.normalized() == target }
            .minByOrNull { minOf(kotlin.math.abs(it.start - caret), kotlin.math.abs(it.end - caret)) }
            ?.let { it.start to it.end }
    }

    /** Runs the statement at the caret under EXPLAIN, in the dialect's syntax. */
    fun explain() {
        if (running || Capability.Explain !in config.type.capabilities) return
        val sql = statementsFor(RunMode.Current).firstOrNull() ?: return
        start(dialect.explain(sql))
    }

    fun run(mode: RunMode) = run(statementsFor(mode))

    /**
     * Runs [sql] as given rather than what's at the caret, e.g. a query the agent suggested; same checks as typed SQL.
     * [onFinished] gets this run's result sets and log lines, unless the user cancels a write confirmation. [confirmed]
     * skips that confirmation when the user already gave it elsewhere; a read-only connection still refuses writes.
     */
    fun run(sql: String, confirmed: Boolean = false, onFinished: ((RunOutcome) -> Unit)? = null) =
        run(splitter.split(sql).map(SqlStatement::text), confirmed, onFinished)

    /** Whether every statement in [sql] only reads. */
    fun onlyReads(sql: String): Boolean = splitter.split(sql).all { classifier.classify(it.text).kind == StatementKind.Read }

    private fun run(statements: List<String>, confirmed: Boolean = false, onFinished: ((RunOutcome) -> Unit)? = null) {
        if (running || statements.isEmpty()) return
        // Like DataGrip: parameters are asked for first, and the checks below see the statements as they will run.
        val names = statements.flatMap(parameters::find).distinct()
        if (names.isNotEmpty()) {
            pendingParameters = ParameterRequest(names) { values ->
                app.parameterValues.putAll(values)
                runChecked(statements.map { parameters.substitute(it, values) }, confirmed, onFinished)
            }
            return
        }
        runChecked(statements, confirmed, onFinished)
    }

    /** Refuses writes on a read-only connection and asks before risky ones, then runs [statements]. */
    private fun runChecked(statements: List<String>, confirmed: Boolean, onFinished: ((RunOutcome) -> Unit)?) {
        val infos = statements.map(classifier::classify)

        if (config.readOnly && infos.any { it.changesData }) {
            val verbs = infos.filter { it.changesData }.joinToString { it.verb }.ifEmpty { "writes" }
            log("This connection is read-only: $verbs not run.", isError = true)
            selectedResultTab = 0
            onFinished?.invoke(RunOutcome(emptyList(), output.takeLast(1)))
            return
        }
        val reasons = buildList {
            infos.mapNotNullTo(this) { it.danger }
            if (config.confirmWrites && infos.any { it.changesData } && isEmpty()) {
                add("${config.name} asks before changing data (${config.environment.label.lowercase()} connection)")
            }
        }
        if (reasons.isNotEmpty() && !confirmed) {
            pendingConfirmation = WriteConfirmation(statements, reasons.distinct()) { start(statements, onFinished) }
            return
        }
        start(statements, onFinished)
    }

    fun confirmPending() {
        val confirmation = pendingConfirmation ?: return
        pendingConfirmation = null
        confirmation.proceed()
    }

    private fun start(statements: List<String>, onFinished: ((RunOutcome) -> Unit)? = null) {
        cancelFix()
        job = app.scope.launch {
            running = true
            results.removeAll { !it.pinned }
            val firstLine = output.size
            try {
                execute(statements)
            } finally {
                running = false
                onFinished?.invoke(RunOutcome(results.toList(), output.drop(firstLine)))
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

    /** Asks about an open transaction first, then about the unsaved script. */
    override fun confirmClose(close: () -> Unit) {
        val askAboutScript = { if (hasUnsavedScript) pendingScriptClose = close else close() }
        if (hasOpenTransaction) pendingClose = askAboutScript else askAboutScript()
    }

    /**
     * Writes the script to its file. The first save asks for a name in the saved queries, like TablePlus; [saveAs]
     * picks a file anywhere instead. [then] runs once it is saved.
     */
    fun save(saveAs: Boolean = false, then: () -> Unit = {}) {
        val current = file
        when {
            saveAs -> saveToFile(then)
            current != null -> writeTo(current, then)
            else -> app.savedQueries.requestSave(this, then)
        }
    }

    /** Asks for a file with the native dialog and saves there. */
    fun saveToFile(then: () -> Unit = {}) {
        chooseScriptFile(title)?.let { writeTo(it, then) }
    }

    /** The console's file was moved, e.g. its saved query renamed. */
    fun moveTo(target: File) {
        file = target
    }

    fun writeTo(target: File, then: () -> Unit = {}) {
        val text = editor.text.toString()
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { target.writeText(text) } }
                .onSuccess {
                    file = target
                    savedText = text
                    app.status = "Saved ${target.path}"
                    if (app.savedQueries.contains(target)) app.savedQueries.reload()
                    then()
                }
                .onFailure {
                    log("Could not save ${target.path}: ${it.message}", isError = true)
                    selectedResultTab = 0
                }
        }
    }

    /** Resolves [pendingScriptClose]; cancelling the file chooser keeps the tab open. */
    fun closeAfterScript(save: Boolean) {
        val close = pendingScriptClose ?: return
        pendingScriptClose = null
        if (save) save(then = close) else close()
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
                val failure = if (e is ReadOnlyViolationException) null else StatementFailure(sql, QueryError.of(e))
                log("$message\n  in: ${sql.preview()}", isError = true, failure = failure)
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

    private fun log(text: String, isError: Boolean = false, failure: StatementFailure? = null) {
        output += OutputLine(LocalTime.now().format(TimeFormat), text, isError, failure)
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
        private val log = LoggerFactory.getLogger(ConsoleTab::class.java)
        /** Smaller than the chat's: a fix needs the statement's tables, which come first, more than the rest. */
        private const val FixSchemaBudget = 20_000
        private val Whitespace = Regex("\\s+")

        private fun String.normalized(): String = trim().removeSuffix(";").replace(Whitespace, " ").trim()

        private fun String.preview(): String = lineSequence().first().let { if (it.length > 80) it.take(80) + "…" else it }
    }
}

/** Native save dialog for a script, adding `.sql` when no extension is given; null when cancelled. */
private fun chooseScriptFile(suggested: String): File? {
    val dialog = FileDialog(null as Frame?, "Save Script", FileDialog.SAVE)
    dialog.file = if (suggested.endsWith(".sql", ignoreCase = true)) suggested else "$suggested.sql"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, if ('.' in name) name else "$name.sql")
}

/** Lines a new, empty console opens with. */
private const val BlankConsoleLines = 12

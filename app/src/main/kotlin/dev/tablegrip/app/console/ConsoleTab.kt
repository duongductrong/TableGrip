package dev.tablegrip.app.console

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.app.grid.GridMention
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.connection.Capability
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.tablegrip.app.AppState
import dev.tablegrip.app.chart.ChartAssistState
import dev.tablegrip.app.chart.ChartState
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.ResultGridModel
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.assist.AssistResult
import dev.tablegrip.core.assist.ChartSpecTask
import dev.tablegrip.core.assist.SchemaDigest
import dev.tablegrip.core.assist.SqlErrors
import dev.tablegrip.core.assist.SqlFix
import dev.tablegrip.core.assist.SqlFixTask
import dev.tablegrip.core.assist.SqlNameFixer
import dev.tablegrip.core.assist.reviewFix
import dev.tablegrip.core.chart.ChartQuery
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.QueryError
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.QuerySession
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.history.HistoryEntry
import dev.tablegrip.core.session.SessionTab
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.core.completion.SqlCompleter
import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.StatementKind
import dev.tablegrip.core.sql.SqlParameters
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.SqlStatement
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

/** How a result is shown in its tab. */
enum class ResultView(val label: String) {
    Grid(tr("Grid")),
    Json(tr("JSON")),
    Chart(tr("Chart")),
}

/**
 * The grid and JSON views show one page of [rows] at a time, like table viewers, so big results stay light to render;
 * the chart draws all of them.
 */
class ResultSetView(val title: String, val sql: String, val rows: QueryResult.Rows, pageSize: Int) {
    var pageSize by mutableIntStateOf(pageSize.coerceAtLeast(1))
        private set
    var page by mutableIntStateOf(0)
        private set
    val pageCount: Int
        get() = ((rows.rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    /** Index in [rows] of the page's first row. */
    val firstRow: Int
        get() = page * pageSize
    val model by derivedStateOf { ResultGridModel(rows, firstRow, pageSize) }
    val grid = DataGridState()

    fun goToPage(index: Int) {
        val target = index.coerceIn(0, pageCount - 1)
        if (target == page) return
        page = target
        grid.selection = null
    }

    /** Keeps the current first row on screen. */
    fun changePageSize(size: Int) {
        val first = firstRow
        pageSize = size.coerceAtLeast(1)
        page = first / pageSize
        grid.selection = null
    }

    /** Pinned results survive the next run, to compare before and after. */
    var pinned by mutableStateOf(false)
    var view by mutableStateOf(ResultView.Grid)
    val chart = ChartState(rows)

    /** Shows what [previous], the same statement's result from the last run, showed: its view and its chart. */
    fun continueFrom(previous: ResultSetView) {
        view = previous.view
        previous.chart.spec?.let(chart::show)
    }
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
        fix = FixState.Proposed(failure, sql, quick.explanation, tr("Quick fix"), reviewFix(failure.sql, sql, syntax))
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
                    fix = FixState.NoFix(failure, tr("No agent is available. Install a command-line agent or set up an API provider in Settings › Agent."))
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
                        is AssistResult.Invalid -> FixState.NoFix(failure, tr("{0} didn't return a usable fix: {1}", agent.descriptor.name, result.reasons.first()))
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
        val composer = app.agent.composer
        val wasBlank = composer.isBlank
        app.agent.mention(Mention.Error(title, config.type.displayName, failure.sql, failure.error.message))
        // An empty composer gets the obvious question; one with something typed keeps it.
        if (wasBlank) composer.text.edit { append(tr("How do I fix it?")) }
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

    /**
     * The selection, else the statement at the caret, as a mention for the agent chat (⌘L). Null over blank text, and when
     * the connection isn't shared with agents.
     */
    fun mention(): Mention? {
        if (!canAskAgent) return null
        val text = editor.text.toString()
        val selection = editor.selection
        val (start, end) =
            if (!selection.collapsed) selection.min to selection.max
            else splitter.statementAt(text, selection.start)?.let { it.start to it.end } ?: return null
        val sql = text.substring(start, end)
        if (sql.isBlank()) return null
        // Lines of the text itself, not of the whitespace around it.
        val first = start + (sql.length - sql.trimStart().length)
        val last = end - (sql.length - sql.trimEnd().length) - 1
        fun line(offset: Int) = text.substring(0, offset).count { it == '\n' } + 1
        return Mention.Sql(title, line(first)..line(last), config.type.displayName, sql.trim())
    }

    /** The whole console as a mention, for `@` in the chat; null when it's empty or not shared with agents. */
    fun whole(): Mention? {
        val text = editor.text.toString()
        if (!canAskAgent || text.isBlank()) return null
        return Mention.Sql(title, 1..text.trimEnd().count { it == '\n' } + 1, config.type.displayName, text.trim())
    }

    /** Whether [view]'s chart can be aggregated on the server instead of from the rows fetched. */
    fun canAggregateChart(view: ResultSetView): Boolean = view.chart.spec?.let { ChartQuery.aggregate(dialect, view.sql, it) } != null

    /**
     * Runs [view]'s statement grouped as its chart groups, so the chart covers every row rather than the first ones
     * fetched. The result opens as a new chart; [view] is pinned to stay for comparison.
     */
    fun aggregateChart(view: ResultSetView) {
        val spec = view.chart.spec ?: return
        val aggregate = ChartQuery.aggregate(dialect, view.sql, spec) ?: return
        view.pinned = true
        run(aggregate.sql) { outcome ->
            val result = outcome.results.lastOrNull { it.sql == aggregate.sql } ?: return@run
            result.view = ResultView.Chart
            result.chart.show(aggregate.spec)
        }
    }

    /**
     * Asks the chat's agent for a chart of [view] matching [request]. It gets the statement and the columns' kinds; a few
     * values per column only when the connection shares data. The answer is proposed, and only drawn once applied.
     */
    fun askForChart(view: ResultSetView, request: String) {
        val chart = view.chart
        val profiles = chart.profiles ?: return
        if (!canAskAgent || request.isBlank()) return
        chart.assistJob?.cancel()
        chart.assist = ChartAssistState.Asking(app.agent.selected?.descriptor?.name)
        chart.assistJob =
            app.scope.launch {
                val agent = app.agent.assistAgent()
                if (agent == null) {
                    chart.assist = ChartAssistState.Failed(tr("No agent is available. Install a command-line agent or set up an API provider in Settings › Agent."))
                    return@launch
                }
                chart.assist = ChartAssistState.Asking(agent.descriptor.name)
                val samples =
                    if (!app.aiAccess(config).sharesData) emptyMap()
                    else profiles.associate { column ->
                        column.name to view.rows.rows.asSequence().mapNotNull { it[column.index]?.take(SampleLength) }.distinct().take(ChartSamples).toList()
                    }
                val task = ChartSpecTask(request, config.type.displayName, view.sql, profiles, samples, view.rows.truncated)
                chart.assist =
                    when (val result = app.agent.assist.run(agent, task)) {
                        is AssistResult.Ok -> ChartAssistState.Proposed(result.value, result.agent)
                        is AssistResult.Invalid -> ChartAssistState.Failed(tr("{0} didn't return a usable chart: {1}", agent.descriptor.name, result.reasons.first()))
                        is AssistResult.Failed -> ChartAssistState.Failed(result.message)
                    }
            }
    }

    fun applyChartProposal(view: ResultSetView) {
        val proposed = view.chart.assist as? ChartAssistState.Proposed ?: return
        view.chart.show(proposed.proposal.spec)
        dismissChartAssist(view)
    }

    fun dismissChartAssist(view: ResultSetView) {
        view.chart.assistJob?.cancel()
        view.chart.assistJob = null
        view.chart.assist = null
    }

    /** Cells selected in result [view] as a mention in the agent chat (⌘L), with the query; their values only when the connection shares data. */
    fun mentionCells(view: ResultSetView, cells: GridMention) {
        val access = app.aiAccess(config)
        if (!access.sharesSchema) return
        val rows = view.firstRow + cells.rows.first + 1..view.firstRow + cells.rows.last + 1
        app.agent.mention(Mention.Cells(view.title, config.type.displayName, cells.columns, rows, cells.values.takeIf { access.sharesData }, origin = view.sql))
    }

    /** Mentions the selection or the statement at the caret in the agent chat (⌘L); false when there is nothing to. */
    fun mentionInChat(): Boolean = mention()?.let { app.agent.mention(it) } != null

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
        // Parameters are asked for first, and the checks below see the statements as they will run.
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
            val verbs = infos.filter { it.changesData }.joinToString { it.verb }.ifEmpty { tr("writes") }
            log(tr("This connection is read-only: {0} not run.", verbs), isError = true)
            selectedResultTab = 0
            onFinished?.invoke(RunOutcome(emptyList(), output.takeLast(1)))
            return
        }
        val reasons = buildList {
            infos.mapNotNullTo(this) { it.danger }
            if (config.confirmWrites && infos.any { it.changesData } && isEmpty()) {
                add(tr("{0} asks before changing data ({1} connection)", config.name, config.environment.label.lowercase()))
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
            val replaced = results.filter { !it.pinned }
            results.removeAll(replaced)
            val firstLine = output.size
            try {
                execute(statements, replaced)
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
                    log(if (enabled) tr("Auto-commit on") + (if (pending > 0) tr(": committed {0} pending statement(s)", pending) else "") else tr("Manual commit: changes stay pending until Commit"))
                }
                .onFailure { log(tr("Could not change commit mode: {0}", it.message), isError = true) }
        }
    }

    fun commit() = endTransaction(commit = true)

    fun rollback() = endTransaction(commit = false)

    private fun endTransaction(commit: Boolean, then: () -> Unit = {}) {
        val session = session ?: return then()
        app.scope.launch {
            runCatching { if (commit) session.commit() else session.rollback() }
                .onSuccess {
                    log(if (commit) tr("Committed") else tr("Rolled back"))
                    uncommitted = 0
                    then()
                }
                .onFailure { log(tr("{0} failed: {1}", if (commit) tr("Commit") else tr("Rollback"), it.message), isError = true) }
        }
    }

    /** Asks about an open transaction first, then about the unsaved script. */
    override fun confirmClose(close: () -> Unit) {
        val askAboutScript = { if (hasUnsavedScript) pendingScriptClose = close else close() }
        if (hasOpenTransaction) pendingClose = askAboutScript else askAboutScript()
    }

    /**
     * Writes the script to its file. The first save asks for a name in the saved queries; [saveAs]
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
                    app.status = tr("Saved {0}", target.path)
                    if (app.savedQueries.contains(target)) app.savedQueries.reload()
                    then()
                }
                .onFailure {
                    log(tr("Could not save {0}: {1}", target.path, it.message), isError = true)
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

    /** The n-th result of [statements] shows what the n-th of [replaced] did, so re-running keeps a chart in place. */
    private suspend fun execute(statements: List<String>, replaced: List<ResultSetView>) {
        val session =
            try {
                session()
            } catch (e: Exception) {
                log(tr("Could not connect: {0}", e.message), isError = true)
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
                        val suffix = if (result.truncated) tr(" (first {0} shown)", result.rows.size) else ""
                        log(tr("{0} row(s) retrieved in {1} ms{2}: {3}", result.rows.size, result.elapsedMillis, suffix, sql.preview()))
                        val view = ResultSetView(tr("Result {0}", ++resultCount), sql, result, app.settings.pageSize)
                        replaced.getOrNull(results.count { !it.pinned })?.let(view::continueFrom)
                        results += view
                    }
                    is QueryResult.Update -> log(tr("{0} row(s) affected in {1} ms: {2}", result.affectedRows, result.elapsedMillis, sql.preview()))
                }
            } catch (e: CancellationException) {
                log(tr("Cancelled: {0}", sql.preview()), isError = true)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                record(sql, startedAt, System.currentTimeMillis() - startedAt, error = message)
                val failure = if (e is ReadOnlyViolationException) null else StatementFailure(sql, QueryError.of(e))
                log(tr("{0}\n  in: {1}", message, sql.preview()), isError = true, failure = failure)
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
        /** Values per column a chart request shows the agent, when the connection shares data, and their length. */
        private const val ChartSamples = 5
        private const val SampleLength = 40
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

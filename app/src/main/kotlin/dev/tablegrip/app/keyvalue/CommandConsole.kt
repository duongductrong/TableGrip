package dev.tablegrip.app.keyvalue

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.completion.CompletionController
import dev.tablegrip.app.completion.CompletionPopup
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.completion.handleCompletionKey
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.completion.CommandCompleter
import dev.tablegrip.core.completion.CompletionKind
import dev.tablegrip.core.completion.ConsoleCommands
import dev.tablegrip.core.completion.MongoScript
import dev.tablegrip.core.completion.MongoShellCompleter
import dev.tablegrip.core.session.SessionTab
import dev.tablegrip.core.sql.TokenType
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.app.AppState
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.app.documents.DocumentView
import dev.tablegrip.app.documents.ShellResult
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.core.driver.ShellReply
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.driver.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * A console for engines without SQL: redis-cli style for key-value stores, one command per line with replies printed
 * above it (in the database [keyspace] selects); for document stores, a Mongo shell script in an editor, run a command
 * or all of them at a time with replies printed below (in [database], which `use` switches).
 */
class CommandConsoleTab(
    private val app: AppState,
    val config: ConnectionConfig,
    name: String,
    keyspace: Int = 0,
    database: String = "",
    initial: String = "",
) : WorkspaceTab {
    override val connectionId = config.id
    override val title = name

    internal val document = config.type.paradigm == Paradigm.Document

    var keyspace by mutableIntStateOf(keyspace)
        private set

    var database by mutableStateOf(database)
        private set

    /** The key-value console's command line. */
    val input = TextFieldState(if (document) "" else initial)

    /** The document console's script. */
    val script = TextFieldState(if (document) initial else "")

    /**
     * A command that ran, with its reply and, for script commands, how long it took; a reply of documents also opens
     * them as a [result] of their own.
     */
    data class Entry(
        val prompt: String,
        val command: String,
        val reply: String,
        val isError: Boolean,
        val millis: Long? = null,
        val result: ShellResult? = null,
    )

    val entries = mutableStateListOf<Entry>()

    /** The document console's results: the documents of each command that returned some, a tab each. */
    val results = mutableStateListOf<ShellResult>()

    /** The output tab shown: 0 is the log, 1.. are [results]. */
    var selectedOutput by mutableIntStateOf(0)

    /** Closing, waiting for the user to agree to drop results' unsubmitted edits. */
    var pendingClose by mutableStateOf<(() -> Unit)?>(null)

    internal fun selectResult(result: ShellResult) {
        val index = results.indexOf(result)
        if (index >= 0) selectedOutput = index + 1
    }

    internal fun closeResult(result: ShellResult) {
        val index = results.indexOf(result)
        if (index < 0) return
        result.dispose()
        results.removeAt(index)
        val tab = index + 1
        selectedOutput = when {
            selectedOutput > tab -> selectedOutput - 1
            selectedOutput == tab -> if (index < results.size) tab else index
            else -> selectedOutput
        }
    }

    /** Before a run: results that aren't pinned and hold no edits make way for the new ones. */
    private fun dropReplaceableResults() {
        results.filter { !it.pinned && !it.hasChanges }.forEach(::closeResult)
    }

    override val hasUnsavedWork: Boolean
        get() = results.any { it.hasChanges }

    override fun confirmClose(close: () -> Unit) = if (hasUnsavedWork) pendingClose = close else close()

    fun confirmPendingClose() {
        val close = pendingClose ?: return
        pendingClose = null
        results.forEach { it.model.revertAll() }
        close()
    }

    override val activeGrid: Pair<GridModel, DataGridState>?
        get() = results.getOrNull(selectedOutput - 1)?.takeIf { it.view == DocumentView.Grid }?.let { it.model to it.grid }

    var running by mutableStateOf(false)
        private set

    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var job: Job? = null

    private val etcd = config.type.family == EngineFamily.Etcd

    private val catalog = if (etcd) ConsoleCommands.Etcd else ConsoleCommands.Redis

    /** Key names for completion, in the console's database. */
    internal val keyNames = KeyNameCache(app.scope) { prefix, limit -> scanKeys(prefix, limit) }

    /** Databases, collections and fields for the Mongo shell's completion. */
    internal val mongo: MongoCatalogCache? =
        if (!document) null
        else
            MongoCatalogCache(
                app.scope,
                database = { database },
                listDatabases = { app.databases.documents(config).databases() },
                listCollections = { db -> app.databases.documents(config).collections(db).map { it.name } },
                sample = { db, collection -> app.databases.documents(config).sample(db, collection, MongoCatalogCache.SampleSize) },
            )

    /** Completion in the engine's own language: Mongo shell, redis-cli or etcdctl. */
    internal val completion =
        CompletionSource(
            complete = { text, caret ->
                val readOnly = config.readOnly
                mongo?.let { MongoShellCompleter.complete(text, caret, it, readOnly) } ?: CommandCompleter.complete(text, caret, catalog, keyNames::keys, readOnly)
            },
            dialect = SqlDialect.Generic,
            cache = null,
        )

    /** Bumps when completion data arrives, so an open list fills in. */
    internal val completionVersion: Int
        get() = mongo?.version ?: keyNames.version

    /** Completion in the script editor: the shell's, for the command at the caret. */
    internal val scriptCompletion =
        CompletionSource(
            complete = { text, caret ->
                val (start, command) = commandAround(text, caret)
                val result = MongoShellCompleter.complete(command, caret - start, mongo!!, config.readOnly)
                result.copy(replaceStart = result.replaceStart + start, replaceEnd = result.replaceEnd + start)
            },
            dialect = SqlDialect.Generic,
            cache = null,
        )

    /** The syntax of the command or method at the caret, if known. */
    fun hint(text: String, caret: Int): String? =
        if (document) commandAround(text, caret).let { (start, command) -> MongoShellCompleter.hint(command, caret - start) }
        else CommandCompleter.hint(text, caret, catalog)

    /**
     * The command of the script at [caret], with where it starts: the one around the caret, else what's been typed since
     * the command before it ended.
     */
    private fun commandAround(text: String, caret: Int): Pair<Int, String> {
        val statements = MongoScript.split(text)
        statements.firstOrNull { caret in it.start..it.end }?.let { return it.start to text.substring(it.start, it.end) }
        val after = statements.lastOrNull { it.end <= caret }?.end ?: 0
        // Past the `;`, blank space and comments since then.
        val start =
            MongoScript.tokenize(text.substring(after, caret))
                .firstOrNull { it.type != TokenType.Whitespace && it.type != TokenType.Comment && !(it.type == TokenType.Operator && text[after + it.start] == ';') }
                ?.let { after + it.start } ?: caret
        return start to text.substring(start, caret)
    }

    private suspend fun scanKeys(prefix: String, limit: Int): Pair<List<String>, Boolean> {
        val store = app.databases.keyValue(config)
        val pattern = KeyNameCache.prefixPattern(prefix)
        val keys = LinkedHashSet<String>()
        var cursor: String? = null
        repeat(MaxScanRounds) {
            val page = store.scan(keyspace, pattern, cursor, ScanCount)
            page.keys.forEach { keys += it.name }
            cursor = page.cursor
            if (cursor == null || keys.size >= limit) return keys.take(limit) to (cursor == null && keys.size <= limit)
        }
        return keys.take(limit) to false
    }

    val prompt: String
        get() = when {
            document -> "$database>"
            etcd -> "etcd>"
            else -> "db$keyspace>"
        }

    fun run() {
        val line = input.text.toString().trim()
        if (line.isEmpty() || running) return
        input.clearText()
        if (history.lastOrNull() != line) history += line
        historyIndex = history.size
        if (line.equals("clear", ignoreCase = true) || line.equals("cls", ignoreCase = true)) {
            entries.clear()
            return
        }
        if (document) {
            runDocument(line)
            return
        }
        val arguments =
            try {
                KeyValueStore.splitArguments(line)
            } catch (e: IllegalArgumentException) {
                entries += Entry(prompt, line, "(error) ${e.message}", isError = true)
                return
            }
        val prompt = prompt
        running = true
        job =
            app.scope.launch {
                try {
                    val reply = app.databases.keyValue(config).command(keyspace, arguments)
                    entries += Entry(prompt, line, reply, isError = reply.startsWith("(error)") || reply.startsWith("Error:"))
                    // The console follows SELECT, like redis-cli's prompt.
                    if (arguments.first().equals("SELECT", ignoreCase = true) && reply == "OK") arguments.getOrNull(1)?.toIntOrNull()?.let { keyspace = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    entries += Entry(prompt, line, "(error) ${e.message ?: e}", isError = true)
                } finally {
                    running = false
                    // The command may have added, removed or renamed keys, or switched database.
                    keyNames.clear()
                }
            }
    }

    private fun runDocument(line: String) = runCommands(listOf(line))

    /**
     * Runs the script's selected commands, else the one at the caret, or every command when [all] is set; one after
     * another, stopping at the first that fails.
     */
    fun runScript(all: Boolean = false) {
        if (running) return
        val text = script.text.toString()
        val selection = script.selection
        val commands =
            when {
                !selection.collapsed -> MongoScript.split(text.substring(selection.min, selection.max))
                all -> MongoScript.split(text)
                else -> listOfNotNull(MongoScript.statementAt(text, selection.start))
            }.map { it.text }
        runCommands(commands)
    }

    private fun runCommands(commands: List<String>) {
        if (commands.isEmpty() || running) return
        running = true
        dropReplaceableResults()
        job =
            app.scope.launch {
                var opened: ShellResult? = null
                try {
                    for (command in commands) {
                        val prompt = prompt
                        if (command.equals("clear", ignoreCase = true) || command.equals("cls", ignoreCase = true)) {
                            entries.clear()
                            continue
                        }
                        val use = Regex("""^use\s+(\S+?)$""").matchEntire(command)
                        val started = System.nanoTime()
                        val reply =
                            if (use != null) {
                                database = use.groupValues[1]
                                mongo?.commandRan()
                                ShellReply("switched to db $database")
                            } else {
                                try {
                                    app.databases.documents(config).shell(database, command)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    ShellReply("Error: ${e.message ?: e}", isError = true)
                                }
                            }
                        val millis = (System.nanoTime() - started) / 1_000_000
                        val result = reply.documents?.takeIf { !reply.isError }?.let { ShellResult(app, config, database, command, reply, millis) }
                        if (result != null) {
                            results += result
                            opened = result
                        }
                        entries += Entry(prompt, command, reply.text, isError = reply.isError, millis = millis, result = result)
                        if (reply.isError) break
                    }
                } finally {
                    running = false
                    mongo?.commandRan()
                    // The last documents read, unless something failed after them: then the log, which says what.
                    selectedOutput = if (entries.lastOrNull()?.isError == true) 0 else opened?.let { results.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
                }
            }
    }

    fun cancel() {
        job?.cancel()
        running = false
    }

    fun clearOutput() {
        entries.clear()
        dropReplaceableResults()
    }

    override fun toSession(): SessionTab? = if (document) SessionTab.ShellConsole(config.id, database, script.text.toString()) else null

    val help: String
        get() =
            if (etcd) tr("etcdctl-style commands: get KEY [--prefix] [--keys-only] [--limit=N], put KEY VALUE [--lease=ID], ") +
                tr("del KEY [--prefix], lease grant|revoke|timetolive|list, member list, endpoint status. ↑/↓ recall earlier commands.")
            else if (document) tr("Mongo shell commands without JavaScript, e.g. db.orders.find({status: 'A'}).sort({total: -1}).limit(10), ") +
                tr("db.orders.aggregate([...]), db.runCommand({...}), show collections, use <db>. {0} runs the command at the caret or the selection, {1} the whole script.", Shortcuts.RunStatement, Shortcuts.RunScript)
            else tr("Commands run as in redis-cli, e.g. HGETALL user:1 or SCAN 0 MATCH session:* COUNT 100. ") +
                tr("↑/↓ recall earlier commands; \"clear\" empties this view.")

    /** Steps through earlier commands: -1 for older, +1 for newer. */
    fun recall(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size)
        input.setTextAndPlaceCursorAtEnd(history.getOrElse(historyIndex) { "" })
    }

    override fun dispose() {
        job?.cancel()
        results.forEach { it.dispose() }
        keyNames.clear()
    }

    companion object {
        private const val ScanCount = 500
        private const val MaxScanRounds = 20
    }
}

@Composable
internal fun CommandConsoleView(tab: CommandConsoleTab) {
    if (tab.document) return ShellConsoleView(tab)
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab.entries.size) { if (tab.entries.isNotEmpty()) listState.animateScrollToItem(tab.entries.size - 1) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tab.entries.isEmpty()) {
                    item {
                        Text(tab.help, color = JewelTheme.globalColors.text.info)
                    }
                }
                items(tab.entries) { entry ->
                    Column {
                        Text("${entry.prompt} ${entry.command}", style = JewelTheme.editorTextStyle, color = JewelTheme.globalColors.text.info)
                        Text(
                            entry.reply,
                            style = JewelTheme.editorTextStyle,
                            color = if (entry.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                        )
                    }
                }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        val hint = tab.hint(tab.input.text.toString(), tab.input.selection.start)
        if (hint != null) {
            Text(
                hint,
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
                style = JewelTheme.editorTextStyle.copy(fontSize = JewelTheme.editorTextStyle.fontSize * 0.9f),
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.prompt, style = JewelTheme.editorTextStyle)
            CommandInput(tab, Modifier.weight(1f).focusRequester(focus))
        }
    }
}

/**
 * The command line, completing as the engine's own shell does (commands, options, keys; collections, methods, fields
 * and operators): Tab or Enter picks the highlighted suggestion, ⌃Space opens the list, Esc closes it. Enter runs the
 * line once nothing is offered (⌘Enter always does), and ↑/↓ recall earlier commands while the list is closed.
 */
@Composable
private fun CommandInput(tab: CommandConsoleTab, modifier: Modifier) {
    val state = tab.input
    val scope = rememberCoroutineScope()
    val controller = remember(tab) { CompletionController(scope) }
    val source = tab.completion
    // A pending trigger from the last key: null for none, else whether to open even with nothing typed yet.
    var trigger by remember { mutableStateOf<Boolean?>(null) }
    // Whether ↑/↓ moved through the list since the text last changed: only then does Enter pick from a list opened on its own.
    var navigated by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val style = JewelTheme.editorTextStyle
    val measurer = rememberTextMeasurer()
    val (charWidth, lineHeight) = remember(style, density) { measurer.measure("0123456789", style).let { it.size.width / 10f to it.size.height } }
    // Set while a recalled command replaces the line, which shouldn't open the list.
    var recalling by remember { mutableStateOf(false) }
    LaunchedEffect(controller) {
        var previous = state.text.toString()
        snapshotFlow { state.text.toString() to state.selection }.collect { (text, selection) ->
            // Typing is read from the text, not the keys, so input methods (Telex, dead keys…) complete too.
            val typed = if (text.length > previous.length && selection.collapsed) text.getOrNull(selection.start - 1) else null
            if (text != previous) navigated = false
            previous = text
            if (recalling) {
                recalling = false
                trigger = null
                return@collect
            }
            val explicit =
                trigger
                    ?: when {
                        typed == null -> null
                        // Brackets, separators and a new argument: what can come next, even with nothing typed.
                        typed in "{[(,:" || typed == ' ' -> true
                        typed.isLetterOrDigit() || typed in "_$.'\"-*#/" -> false
                        else -> null
                    }
            if (explicit != null || controller.isActive) controller.update(state, source, explicit = explicit == true)
            trigger = null
        }
    }
    // Keys, collections or fields that arrive fill a list that's already open.
    // Keeps a list opened with nothing typed yet (`find({|`) open while its fields load.
    LaunchedEffect(tab.completionVersion) { if (controller.isActive) controller.update(state, source, explicit = true) }
    LaunchedEffect(tab.running) { if (tab.running) controller.close() }

    /** Inserts the highlighted item and, where something follows it, goes straight on to what can come next. */
    fun accept(index: Int = controller.selected) {
        controller.accept(state, index)
        val before = state.text.substring(0, state.selection.start)
        if (before.endsWith(" ") || before.endsWith(".") || before.endsWith("(") || before.endsWith("[") || before.endsWith("{") || before.endsWith("=")) trigger = true
    }

    fun run() {
        controller.close()
        tab.run()
    }

    /** Enter with the list open picks from it, unless nothing's being typed or what's typed is already a suggestion. */
    fun enterPicks(): Boolean {
        val result = controller.result ?: return false
        val typed = result.prefix.trim('"', '\'')
        if (typed.isEmpty()) return navigated
        return navigated || controller.items.none { it.label.equals(typed, ignoreCase = it.kind != CompletionKind.Value) }
    }

    Box(modifier.onSizeChanged { fieldWidth = it.width }) {
        TextField(
            state,
            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (Shortcuts.Submit.matches(event)) return@onPreviewKeyEvent true.also { run() }
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (enter && controller.isOpen && !enterPicks()) return@onPreviewKeyEvent true.also { run() }
                if (controller.isOpen && event.key in NavigationKeys) navigated = true
                if (handleCompletionKey(controller, event.key) { accept() }) return@onPreviewKeyEvent true
                if (Shortcuts.Completion.matches(event)) return@onPreviewKeyEvent true.also { controller.update(state, source, explicit = true) }
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> return@onPreviewKeyEvent true.also { run() }
                    Key.DirectionUp, Key.DirectionDown -> return@onPreviewKeyEvent true.also {
                        controller.close()
                        val before = state.text.toString()
                        tab.recall(if (event.key == Key.DirectionUp) -1 else 1)
                        recalling = state.text.toString() != before
                    }
                    else -> false
                }
            },
            textStyle = style,
            enabled = !tab.running,
            placeholder = { Text(if (tab.config.readOnly) tr("Read-only: only commands that read run") else tr("Command; Tab completes")) },
        )
        // The field scrolls long text; keep the popup within it.
        val x = with(density) { 10.dp.toPx() } + charWidth * (controller.result?.replaceStart ?: 0)
        CompletionPopup(
            controller,
            IntOffset(x.toInt().coerceAtMost((fieldWidth - 200).coerceAtLeast(0)), with(density) { 30.dp.roundToPx() }),
            lineHeight = with(density) { 30.dp.roundToPx() } - (with(density) { 28.dp.roundToPx() } - lineHeight) / 2,
        ) { accept(it) }
    }
}

private val NavigationKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown)

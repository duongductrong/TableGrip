package dev.datagrok.app.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.core.DatagrokPaths
import dev.datagrok.core.agent.AgentAdapter
import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.provider.AgentMode
import dev.datagrok.core.agent.provider.ProviderConfig
import dev.datagrok.core.agent.provider.ProviderProtocol
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.sql.SqlClassifier
import dev.datagrok.core.sql.SqlSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a query the agent asked to run stands; absent means the user hasn't answered yet. */
sealed interface RunRequestState {
    data object Running : RunRequestState

    data class Done(val summary: String) : RunRequestState

    data object Declined : RunRequestState

    /** Not offered: the query changes data, which the agent may only suggest. */
    data object Refused : RunRequestState
}

/** A query from the chat that could lose data, waiting for the user to confirm it on [connectionId]. */
data class RunConfirmation(val sql: String, val connectionId: String, val reasons: List<String>)

/** One entry in the chat. [text] grows while the agent streams. */
class ChatMessage(val role: AgentMessage.Role, text: String = "", val kind: Kind = Kind.Chat) {
    enum class Kind {
        Chat,
        /** Sent to the agent on the user's behalf (a query result, a declined run); shown as a one-line note. */
        Note,
    }

    var text by mutableStateOf(text)
    var streaming by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The note's line in the chat, when [text] is what the agent reads. */
    var label by mutableStateOf<String?>(null)
    /** Run requests in this message, by the index of their code block. */
    val runs = mutableStateMapOf<Int, RunRequestState>()
    /** For a note: the run request it answers, so rewinding past the note asks that question again. */
    var origin: Pair<ChatMessage, Int>? = null
}

/** The agent chat in the right island: which agents exist, which one is picked, and the conversation with it. */
class AgentViewModel(private val app: AppState, private val registry: AgentRegistry = configured(app)) {
    var agents by mutableStateOf(emptyList<AgentAdapter>())
        private set
    /** Until the first discovery finishes; the panel says so instead of "no agents". */
    var discovering by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    /** Text handed back to the composer by a rewind, for the user to edit and send again; the composer takes it. */
    var draft by mutableStateOf<String?>(null)

    private val context = AgentContext(app)
    private var discovered = false
    private var discovery: Job? = null
    private var turn: Job? = null

    val mode: AgentMode
        get() = AgentMode.of(app.settings.agentMode)

    val selected: AgentAdapter?
        get() = agents.firstOrNull { it.descriptor.id == app.settings.agent } ?: agents.firstOrNull()

    fun select(agent: AgentAdapter) = app.updateSettings { it.copy(agent = agent.descriptor.id) }

    /** Finds installed agents the first time the panel opens; [rescan] re-reads the shell's PATH. */
    fun discover(rescan: Boolean = false) {
        if ((discovered && !rescan) || discovering) return
        discovered = true
        discovering = true
        discovery =
            app.scope.launch {
                agents = runCatching { registry.discover(rescan) }.getOrDefault(emptyList())
                discovering = false
            }
    }

    /**
     * Settings changed: a different source or provider means a different agent list. Only settings are read again; no
     * agent is started and nothing is sent. A turn already running finishes with the agent it started with.
     */
    fun settingsChanged(before: Settings, after: Settings) {
        val changed =
            before.agentMode != after.agentMode || before.agentProvider != after.agentProvider ||
                before.agentBaseUrl != after.agentBaseUrl || before.agentModel != after.agentModel
        if (changed) reconfigure()
    }

    /** Drops the agent list and, if the panel has already looked, looks again with the current settings. */
    fun reconfigure() {
        if (!discovered) return
        discovery?.cancel()
        discovering = false
        discovered = false
        discover()
    }

    /** Shows the agent's settings page, e.g. to switch to a provider when no CLI is installed. */
    fun openSettings() {
        app.settingsPage = "agent"
        app.settingsOpen = true
    }

    /** Whether a key for [protocol] is saved in the keychain. Blocking: call it off the UI thread. */
    fun hasSavedKey(protocol: ProviderProtocol): Boolean = !app.secrets.get(protocol.secretKey).isNullOrEmpty()

    /** Saves [key] for [protocol] in the keychain, or forgets it when blank. */
    suspend fun saveKey(protocol: ProviderProtocol, key: String) =
        withContext(Dispatchers.IO) {
            val value = key.trim()
            if (value.isEmpty()) app.secrets.delete(protocol.secretKey) else app.secrets.set(protocol.secretKey, value)
        }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isNotEmpty()) startTurn(ChatMessage(AgentMessage.Role.User, text))
    }

    private fun startTurn(message: ChatMessage) {
        val agent = selected ?: return
        if (busy) return
        val history = messages.filter { it.error == null && it.text.isNotBlank() }.map { AgentMessage(it.role, it.text) }
        messages += message
        val reply = ChatMessage(AgentMessage.Role.Assistant).apply { streaming = true }
        messages += reply
        busy = true
        turn =
            app.scope.launch {
                try {
                    val request =
                        AgentRequest(
                            history + AgentMessage(AgentMessage.Role.User, message.text),
                            system = context.systemPrompt(),
                            workingDirectory = DatagrokPaths.agentWorkspace,
                            reminder = AgentContext.Reminder,
                        )
                    agent.send(request).collect { event ->
                        when (event) {
                            AgentEvent.Thinking -> Unit
                            is AgentEvent.TextDelta -> reply.text += event.text
                            is AgentEvent.Completed -> reply.text = event.text
                        }
                    }
                } catch (e: CancellationException) {
                    if (reply.text.isBlank()) reply.error = "Stopped."
                    throw e
                } catch (e: Exception) {
                    reply.error = e.message ?: "The agent failed."
                } finally {
                    reply.streaming = false
                    busy = false
                }
            }
    }

    fun stop() {
        turn?.cancel()
    }

    /** SQL blocks offer console actions only where a SQL console exists: not for key-value or document stores. */
    val canRunSql: Boolean
        get() = app.currentConnection?.type?.paradigm?.let { it != Paradigm.KeyValue && it != Paradigm.Document } == true

    /** The selected tab, when it is a console of the current connection: suggestions go there rather than a new one. */
    private val currentConsole: ConsoleTab?
        get() = (app.workspace.selected as? ConsoleTab)?.takeIf { it.connectionId == app.currentConnection?.id }

    val consoleRunning: Boolean
        get() = currentConsole?.running == true

    /** Puts a suggested query at the end of the current console, or in a new one. */
    fun openSql(sql: String): ConsoleTab? {
        val connection = app.currentConnection ?: return null
        val statement = sql.trim().removeSuffix(";")
        currentConsole?.let { console ->
            console.insert(statement)
            return console
        }
        app.workspace.openConsole(connection, "$statement;")
        return app.workspace.selected as? ConsoleTab
    }

    /**
     * Why running [sql] on the current connection could lose data, worst first; empty when every statement only reads.
     * Any change counts, not just the statements the console always asks about: the agent wrote it, not the user.
     */
    fun risks(sql: String): List<String> {
        val connection = app.currentConnection ?: return emptyList()
        val syntax = SqlDialect.of(connection.type).syntax
        val classifier = SqlClassifier(syntax)
        val infos = SqlSplitter(syntax).split(sql).map { classifier.classify(it.text) }
        val changes = infos.filter { it.changesData }
        if (changes.isEmpty()) return emptyList()
        return buildList {
            infos.mapNotNullTo(this) { it.danger }
            add("Changes data or schema: ${changes.map { it.verb }.distinct().joinToString()}")
            val environment = connection.environment.takeIf { it != Environment.None }?.let { " (${it.label.lowercase()})" }.orEmpty()
            add("Runs on ${connection.name}$environment")
        }.distinct()
    }

    /** Set while asking the user to confirm a query from the chat that could lose data. */
    var pendingRun by mutableStateOf<RunConfirmation?>(null)

    /** Opens a suggested query in a console and runs it there; one that changes anything asks here first. */
    fun runSql(sql: String) {
        if (consoleRunning) return
        val connection = app.currentConnection ?: return
        val risks = risks(sql)
        if (risks.isNotEmpty()) {
            pendingRun = RunConfirmation(sql, connection.id, risks)
            return
        }
        openSql(sql)?.run(sql)
    }

    /** Runs the confirmed query, without the console asking a second time; dropped if the connection changed meanwhile. */
    fun confirmRun() {
        val run = pendingRun ?: return
        pendingRun = null
        if (consoleRunning || app.currentConnection?.id != run.connectionId) return
        openSql(run.sql)?.run(run.sql, confirmed = true)
    }

    /**
     * The user allowed a query the agent asked to run: it runs in a console, where the user sees it, and its result goes
     * back to the agent as the next turn. Only reads are run this way; anything else stays a suggestion.
     */
    fun allowRun(message: ChatMessage, block: Int, sql: String) {
        if (busy || consoleRunning || message.runs[block] != null) return
        val console = openSql(sql) ?: return
        if (!console.onlyReads(sql)) {
            message.runs[block] = RunRequestState.Refused
            return
        }
        message.runs[block] = RunRequestState.Running
        console.run(sql) { outcome ->
            val rows = outcome.results.sumOf { it.rows.rows.size }
            val failed = outcome.log.any { it.isError }
            message.runs[block] = RunRequestState.Done(if (failed) "Failed — the error was sent to the agent" else "$rows row(s) sent to the agent")
            startTurn(note(AgentContext.describe(sql, outcome), if (failed) "Query error shared with the agent" else "Result shared · $rows row(s)").also { it.origin = message to block })
        }
    }

    fun declineRun(message: ChatMessage, block: Int) {
        if (message.runs[block] != null) return
        message.runs[block] = RunRequestState.Declined
        startTurn(note("The user declined to run that query. Continue without its result, or suggest it for them to run.", "Run declined").also { it.origin = message to block })
    }

    private fun note(text: String, label: String) = ChatMessage(AgentMessage.Role.User, text, ChatMessage.Kind.Note).also { it.label = label }

    /**
     * Whether the conversation can be rewound now. Not mid-turn (stop first) and not while a query the agent asked for is
     * running: its result would arrive as a turn after the history it belongs to was gone.
     */
    val canRewind: Boolean
        get() = !busy && messages.none { message -> message.runs.values.any { it == RunRequestState.Running } }

    /**
     * Takes [message] and everything after it out of the conversation, so the agent never sees them again. A message the
     * user typed comes back to the composer to edit and resend; a note reopens the run request it answered.
     */
    fun rewind(message: ChatMessage) {
        if (!canRewind) return
        val index = messages.indexOf(message)
        if (index < 0) return
        truncate(index)
        if (message.role == AgentMessage.Role.User && message.kind == ChatMessage.Kind.Chat) draft = message.text
    }

    /** Asks again for [reply]: drops it and what followed, then resends the message it answered. */
    fun retry(reply: ChatMessage) {
        if (!canRewind || selected == null) return
        val index = messages.indexOf(reply)
        val question = messages.subList(0, maxOf(index, 0)).lastOrNull { it.role == AgentMessage.Role.User } ?: return
        truncate(messages.indexOf(question))
        startTurn(question)
    }

    private fun truncate(from: Int) {
        val removed = messages.subList(from, messages.size).toList()
        messages.removeRange(from, messages.size)
        removed.forEach { gone -> gone.origin?.let { (request, block) -> if (request in messages) request.runs.remove(block) } }
    }

    fun forgetSchema(connectionId: String) = context.forget(connectionId)

    fun newChat() {
        stop()
        messages.clear()
        context.forget()
    }

    companion object {
        /** Agents as settings describe them; read at each discovery, so switching needs no restart. */
        fun configured(app: AppState): AgentRegistry =
            AgentRegistry.default(
                mode = { AgentMode.of(app.settings.agentMode) },
                provider = { providerConfig(app.settings) },
                // The keychain first, then the variable the provider's own tools read.
                apiKey = { protocol -> app.secrets.get(protocol.secretKey)?.takeIf { it.isNotBlank() } ?: System.getenv(protocol.keyVariable) },
            )

        fun providerConfig(settings: Settings): ProviderConfig =
            ProviderConfig.of(ProviderProtocol.of(settings.agentProvider), settings.agentBaseUrl, settings.agentModel)
    }
}

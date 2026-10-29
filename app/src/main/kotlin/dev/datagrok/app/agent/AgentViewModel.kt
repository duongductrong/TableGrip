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
import dev.datagrok.core.connection.Paradigm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where a query the agent asked to run stands; absent means the user hasn't answered yet. */
sealed interface RunRequestState {
    data object Running : RunRequestState

    data class Done(val summary: String) : RunRequestState

    data object Declined : RunRequestState

    /** Not offered: the query changes data, which the agent may only suggest. */
    data object Refused : RunRequestState
}

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
}

/** The agent chat in the right island: which agents exist, which one is picked, and the conversation with it. */
class AgentViewModel(private val app: AppState, private val registry: AgentRegistry = AgentRegistry.default()) {
    var agents by mutableStateOf(emptyList<AgentAdapter>())
        private set
    /** Until the first discovery finishes; the panel says so instead of "no agents". */
    var discovering by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set

    private val context = AgentContext(app)
    private var discovered = false
    private var turn: Job? = null

    val selected: AgentAdapter?
        get() = agents.firstOrNull { it.descriptor.id == app.settings.agent } ?: agents.firstOrNull()

    fun select(agent: AgentAdapter) = app.updateSettings { it.copy(agent = agent.descriptor.id) }

    /** Finds installed agents the first time the panel opens; [rescan] re-reads the shell's PATH. */
    fun discover(rescan: Boolean = false) {
        if ((discovered && !rescan) || discovering) return
        discovered = true
        discovering = true
        app.scope.launch {
            agents = runCatching { registry.discover(rescan) }.getOrDefault(emptyList())
            discovering = false
        }
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

    /** Opens a suggested query in a console and runs it there, with the console's read-only and confirmation checks. */
    fun runSql(sql: String) {
        if (consoleRunning) return
        openSql(sql)?.run(sql)
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
            startTurn(note(AgentContext.describe(sql, outcome), if (failed) "Query error shared with the agent" else "Result shared · $rows row(s)"))
        }
    }

    fun declineRun(message: ChatMessage, block: Int) {
        if (message.runs[block] != null) return
        message.runs[block] = RunRequestState.Declined
        startTurn(note("The user declined to run that query. Continue without its result, or suggest it for them to run.", "Run declined"))
    }

    private fun note(text: String, label: String) = ChatMessage(AgentMessage.Role.User, text, ChatMessage.Kind.Note).also { it.label = label }

    fun forgetSchema(connectionId: String) = context.forget(connectionId)

    fun newChat() {
        stop()
        messages.clear()
        context.forget()
    }
}

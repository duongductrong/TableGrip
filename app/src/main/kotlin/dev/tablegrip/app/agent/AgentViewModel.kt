package dev.tablegrip.app.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.core.TableGripPaths
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.AgentModel
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.ChatSummary
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.agent.MentionText
import dev.tablegrip.core.agent.SavedChat
import dev.tablegrip.core.agent.SavedChatMessage
import dev.tablegrip.core.agent.withModel
import dev.tablegrip.core.agent.provider.AgentMode
import dev.tablegrip.core.agent.provider.ProviderConfig
import dev.tablegrip.core.agent.provider.ProviderProtocol
import dev.tablegrip.core.assist.AssistRunner
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.SqlSplitter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Where a query the agent asked to run stands; absent means the user hasn't answered yet. */
sealed interface RunRequestState {
    data object Running : RunRequestState

    data class Done(val summary: String) : RunRequestState

    data object Declined : RunRequestState

    /** Not offered: the query changes data, which the agent may only suggest. */
    data object Refused : RunRequestState

    /** Opened in a console, result kept back: the connection is not shared with agents. */
    data object NotShared : RunRequestState
}

/** A query from the chat that could lose data, waiting for the user to confirm it on [connectionId]. */
data class RunConfirmation(val sql: String, val connectionId: String, val reasons: List<String>)

/**
 * One entry in the chat. [text] grows while the agent streams. A message the user typed may hold [mentions], as stand-in
 * characters in [text] (see [MentionText]).
 */
class ChatMessage(val role: AgentMessage.Role, text: String = "", val kind: Kind = Kind.Chat, mentions: Map<Char, Mention> = emptyMap()) {
    enum class Kind {
        Chat,
        /** Sent to the agent on the user's behalf (a query result, a declined run); shown as a one-line note. */
        Note,
    }

    var text by mutableStateOf(text)
    /** Completed when the message is first sent (see [MentionSources.complete]), so every later turn sends the same. */
    var mentions by mutableStateOf(mentions)
    var streaming by mutableStateOf(false)
    /** When the agent was asked, from [System.nanoTime]; the panel counts the wait from here. */
    var startedAt = 0L
    /** The agent's reasoning, shown folded above the answer; never sent back to it. */
    var reasoning by mutableStateOf("")
    /** How long the agent reasoned, set once the answer starts or the turn ends; null while it is still thinking. */
    var thoughtSeconds by mutableStateOf<Int?>(null)
    /** What the agent did besides writing (tool calls, commands), in the order it started them. */
    val activities = mutableStateListOf<AgentEvent.Activity>()
    var error by mutableStateOf<String?>(null)
    /** The note's line in the chat, when [text] is what the agent reads. */
    var label by mutableStateOf<String?>(null)
    /** Run requests in this message, by the index of their code block. */
    val runs = mutableStateMapOf<Int, RunRequestState>()
    /** For a note: the run request it answers, so rewinding past the note asks that question again. */
    var origin: Pair<ChatMessage, Int>? = null

    val mentionText: MentionText
        get() = MentionText(text, mentions)
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
    /** What is being typed, mentions included; a rewind hands a message back here to edit and send again. */
    val composer = ComposerState()

    /** What `@` and ⌘L can mention. */
    val sources = MentionSources(app)

    /** Earlier chats, newest first, as [refreshChats] last read them. */
    var chats by mutableStateOf(emptyList<ChatSummary>())
        private set
    /** The open chat; a new one has an id from the start, and is kept once something is sent in it. */
    var chatId by mutableStateOf(newChatId())
        private set
    /** What the open chat is called: its first message until the user renames it; null before anything is sent. */
    var chatTitle by mutableStateOf<String?>(null)
        private set
    private var chatCreatedAt = 0L
    /** The conversation as last written, so a chat only moves up the history when something in it changed. */
    private var lastSaved: List<SavedChatMessage>? = emptyList()
    /** Writes go one at a time and in order, so an older version never lands over a newer one. */
    private val saving = Mutex()

    private val context = AgentContext(app)
    private var discovered = false
    private var discovery: Job? = null
    private var turn: Job? = null

    val mode: AgentMode
        get() = AgentMode.of(app.settings.agentMode)

    val selected: AgentAdapter?
        get() = agents.firstOrNull { it.descriptor.id == app.settings.agent } ?: agents.firstOrNull()

    fun select(agent: AgentAdapter) = app.updateSettings { it.copy(agent = agent.descriptor.id) }

    /** The model picked for the selected agent; null means the agent's own default, as does an agent that can't choose. */
    val model: String?
        get() = selected?.takeIf { it.choosesModel }?.let { app.settings.agentModels[it.descriptor.id] }

    /** Picks [model] for the selected agent from the next turn on; blank goes back to the agent's own default. */
    fun chooseModel(model: String?) {
        val id = selected?.takeIf { it.choosesModel }?.descriptor?.id ?: return
        val chosen = model?.trim().orEmpty()
        app.updateSettings { it.copy(agentModels = if (chosen.isEmpty()) it.agentModels - id else it.agentModels + (id to chosen)) }
    }

    /** The models [agent] says it offers; empty when it can't tell, and the user types one. */
    suspend fun models(agent: AgentAdapter): List<AgentModel> = agent.models()

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

    fun send(prompt: String) = send(MentionText(prompt))

    fun send(message: MentionText) {
        val text = message.text.trim()
        if (MentionText(text, message.mentions).plain.isNotBlank()) startTurn(ChatMessage(AgentMessage.Role.User, text, mentions = message.mentions))
    }

    /** Sends what the composer holds, which leaves it empty. */
    fun sendComposer() {
        if (selected == null || busy || composer.isBlank) return
        send(composer.take())
    }

    /** Opens the chat with the composer focused (⌘L with nothing to mention). */
    fun focusComposer() {
        composer.requestFocus()
        app.agentOpen = true
    }

    /** Adds [mention] where the user is typing to the agent, opening the chat (⌘L). */
    fun mention(mention: Mention) {
        composer.insert(mention)
        app.agentOpen = true
    }

    private fun startTurn(message: ChatMessage) {
        val agent = selected?.withModel(model) ?: return
        if (busy) return
        val history = messages.filter { it.error == null && it.text.isNotBlank() }.map { AgentMessage(it.role, it.mentionText.prompt) }
        messages += message
        val reply = ChatMessage(AgentMessage.Role.Assistant).apply {
            streaming = true
            startedAt = System.nanoTime()
        }
        messages += reply
        busy = true
        // Kept now, so the chat is in the history from its first message even if the app quits mid-answer.
        save()
        val chat = chatId
        turn =
            app.scope.launch {
                val reveal = TextReveal()
                val thought = TextReveal()
                var thinking = false
                var answered = false
                // Counted from the request, as the wait before the answer is what the user sat through.
                fun endThinking() {
                    if (thinking && reply.thoughtSeconds == null) reply.thoughtSeconds = ((System.nanoTime() - reply.startedAt) / 1_000_000_000).toInt()
                }
                val pacing = launch {
                    while (true) {
                        delay(TextReveal.FrameMillis)
                        thought.step()?.let { reply.reasoning = it }
                        reveal.step()?.let { reply.text = it }
                    }
                }
                try {
                    if (message.mentions.isNotEmpty()) message.mentions = message.mentions.mapValues { (_, mention) -> sources.complete(mention) }
                    val request =
                        AgentRequest(
                            history + AgentMessage(AgentMessage.Role.User, message.mentionText.prompt),
                            system = context.systemPrompt(message.mentions.values),
                            workingDirectory = TableGripPaths.agentWorkspace,
                            reminder = AgentContext.Reminder,
                        )
                    agent.send(request).collect { event ->
                        when (event) {
                            AgentEvent.Thinking -> Unit
                            is AgentEvent.ReasoningDelta -> {
                                thinking = true
                                thought.append(event.text)
                            }
                            is AgentEvent.Activity -> {
                                val index = reply.activities.indexOfFirst { it.id == event.id }
                                if (index < 0) reply.activities += event else reply.activities[index] = event
                            }
                            is AgentEvent.TextDelta -> {
                                endThinking()
                                reveal.append(event.text)
                            }
                            is AgentEvent.Completed -> reveal.complete(event.text)
                        }
                    }
                    // Let the rest flow out rather than land at once; it speeds up now the answer is complete.
                    while (reveal.pending > 0 || thought.pending > 0) delay(TextReveal.FrameMillis)
                    answered = true
                } catch (e: CancellationException) {
                    reply.text = reveal.flush()
                    if (reply.text.isBlank()) reply.error = "Stopped."
                    throw e
                } catch (e: Exception) {
                    reply.error = e.message ?: "The agent failed."
                } finally {
                    pacing.cancel()
                    endThinking()
                    reply.reasoning = thought.flush()
                    reply.text = reveal.flush()
                    // An activity the agent never reported back on: done if the turn succeeded, cut short if not.
                    val end = if (answered) AgentEvent.Activity.State.Done else AgentEvent.Activity.State.Failed
                    for (i in reply.activities.indices) if (reply.activities[i].state == AgentEvent.Activity.State.Running) reply.activities[i] = reply.activities[i].copy(state = end)
                    reply.streaming = false
                    busy = false
                    if (chatId == chat) save()
                }
            }
    }

    fun stop() {
        turn?.cancel()
    }

    /** Whether the current connection may be described to agents at all; when not, no result goes back to one. */
    val sharesCurrentConnection: Boolean
        get() = app.aiAccess(app.currentConnection).sharesSchema

    /** Runs assists (a fix, a filter) on the selected agent; see [assistAgent]. */
    val assist = AssistRunner(workingDirectory = TableGripPaths.agentWorkspace)

    /**
     * The agent assists run on: the one picked in the chat, found first if the panel was never opened. Null when no
     * agent is available.
     */
    suspend fun assistAgent(): AgentAdapter? {
        discover()
        discovery?.join()
        return selected?.withModel(model)
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
        if (!sharesCurrentConnection) {
            message.runs[block] = RunRequestState.NotShared
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
        if (message.role == AgentMessage.Role.User && message.kind == ChatMessage.Kind.Chat) composer.restore(message.mentionText)
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
        save()
    }

    fun forgetSchema(connectionId: String) = context.forget(connectionId)

    /** Starts over in a new chat; the one open stays in the history. */
    fun newChat() {
        stop()
        save()
        clear()
    }

    private fun clear() {
        messages.clear()
        context.forget()
        chatId = newChatId()
        chatTitle = null
        chatCreatedAt = 0L
        lastSaved = emptyList()
    }

    /** Re-reads the history, e.g. when its list opens. */
    fun refreshChats() {
        val store = app.chatStore ?: return
        app.scope.launch { chats = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) } }
    }

    /**
     * Switches to the chat [id] from the history, as it was left. A turn still running in the open one is stopped and
     * what it wrote so far is kept.
     */
    fun openChat(id: String) {
        val store = app.chatStore ?: return
        if (id == chatId && messages.isNotEmpty()) return
        app.scope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) }
            if (chat == null) {
                refreshChats()
                return@launch
            }
            stop()
            save()
            messages.clear()
            messages += chat.messages.toChat()
            context.forget()
            chatId = chat.id
            chatTitle = chat.title
            chatCreatedAt = chat.createdAt
            lastSaved = chat.messages
        }
    }

    fun renameChat(id: String, title: String) {
        val store = app.chatStore ?: return
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        if (id == chatId) chatTitle = name
        chats = chats.map { if (it.id == id) it.copy(title = name) else it }
        app.scope.launch { saving.withLock { withContext(Dispatchers.IO) { runCatching { store.rename(id, name) }.onFailure { log.warn("Failed to rename a chat", it) } } } }
    }

    /** Removes [id] from the history for good; deleting the open chat starts a new one. */
    fun deleteChat(id: String) {
        val store = app.chatStore ?: return
        if (id == chatId) {
            stop()
            clear()
        }
        chats = chats.filter { it.id != id }
        app.scope.launch { saving.withLock { withContext(Dispatchers.IO) { runCatching { store.delete(id) }.onFailure { log.warn("Failed to delete a chat", it) } } } }
    }

    /** The open chat as it is kept, from [saved]; null when there is nothing in it to keep. */
    private fun snapshot(saved: List<SavedChatMessage>): SavedChat? {
        if (saved.isEmpty()) return null
        val title = chatTitle ?: chatTitle(messages) ?: return null
        chatTitle = title
        val now = System.currentTimeMillis()
        if (chatCreatedAt == 0L) chatCreatedAt = now
        return SavedChat(chatId, title, chatCreatedAt, now, selected?.descriptor?.id, saved)
    }

    /** Keeps the open chat in the history, off the UI thread. Rewound to nothing, it leaves the history. */
    private fun save() {
        val store = app.chatStore ?: return
        val id = chatId
        val saved = messages.toSaved()
        if (saved == lastSaved) return
        lastSaved = saved
        val chat = snapshot(saved)
        app.scope.launch {
            saving.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { if (chat != null) store.save(chat) else store.delete(id) }.onFailure { log.warn("Failed to save the chat", it) }
                }
            }
            refreshChats()
        }
    }

    /** Writes the open chat now, on the calling thread; for quitting, with an answer perhaps still arriving. */
    fun saveNow() {
        val store = app.chatStore ?: return
        val saved = messages.toSaved()
        if (saved == lastSaved) return
        val chat = snapshot(saved) ?: return
        runCatching { store.save(chat) }.onFailure { log.warn("Failed to save the chat", it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentViewModel::class.java)

        private fun newChatId(): String = UUID.randomUUID().toString()

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

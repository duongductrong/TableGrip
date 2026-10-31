package dev.datagrok.core.agent

import java.nio.file.Path
import kotlinx.coroutines.flow.Flow

/**
 * How Datagrok reaches an agent. Every transport reduces to the same [AgentAdapter.send] stream, so the chat panel never
 * knows which one answered: a CLI installed on this machine, run headless, or an HTTP provider configured in settings;
 * later an Agent Client Protocol server.
 */
enum class AgentTransport { Cli, Acp, Http }

/** What the agent picker shows. [id] is stable across launches, so the last-used agent can be remembered. */
data class AgentDescriptor(val id: String, val name: String, val transport: AgentTransport, val detail: String? = null)

/** A model an agent can be asked for. [id] is what the agent takes; [label] is its display name, when it gives one. */
data class AgentModel(val id: String, val label: String? = null)

data class AgentMessage(val role: Role, val text: String) {
    enum class Role { User, Assistant }
}

/** One turn: the whole conversation so far, ending with the user's new message. */
data class AgentRequest(
    val messages: List<AgentMessage>,
    /** Framing for the turn; transports without a system channel put it at the top of the prompt. */
    val system: String? = null,
    /** Where a local agent runs; transports that can't touch this machine ignore it. */
    val workingDirectory: Path? = null,
    /**
     * Rules restated right before the latest message. Agents weigh what's nearest the end most, and a long conversation
     * can push the system framing far up; transports with a system channel may fold it in there instead.
     */
    val reminder: String? = null,
    /** The model to answer with; null or blank leaves it to the agent. Agents that can't choose one ignore it. */
    val model: String? = null,
)

sealed interface AgentEvent {
    /** The agent started working; a progress signal with nothing to show yet. */
    data object Thinking : AgentEvent

    data class TextDelta(val text: String) : AgentEvent

    /** The agent's reasoning, for showing while it works. Not part of the answer, and never sent back to it. */
    data class ReasoningDelta(val text: String) : AgentEvent

    /**
     * Something the agent does besides writing: a tool call, a command, a search. Updates to one activity share its [id];
     * [label] says what it is in a few words, e.g. "Read schema.sql".
     */
    data class Activity(val id: String, val label: String, val state: State) : AgentEvent {
        enum class State { Running, Done, Failed }
    }

    /** The whole answer. Always the last event of a successful turn. */
    data class Completed(val text: String) : AgentEvent
}

/** A failed turn, with a message short enough to show in the chat as the reason. */
class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One agent the chat can talk to. Implementations are stateless between turns: the request carries the conversation. */
interface AgentAdapter {
    val descriptor: AgentDescriptor

    /** Streams one turn. Cancelling the collector stops the agent; failures are thrown as [AgentException]. */
    fun send(request: AgentRequest): Flow<AgentEvent>

    /** Whether [AgentRequest.model] is honoured; when false the agent always answers with its own model. */
    val choosesModel: Boolean
        get() = false

    /**
     * The models the agent says it offers, best first. Empty when it can't tell, which still allows typing a model
     * by hand when [choosesModel]. Never throws.
     */
    suspend fun models(): List<AgentModel> = emptyList()
}

/** This agent, answering every turn with [model]; itself when it can't choose one or [model] is blank. */
fun AgentAdapter.withModel(model: String?): AgentAdapter {
    val chosen = model?.trim().orEmpty()
    if (!choosesModel || chosen.isEmpty()) return this
    val agent = this
    return object : AgentAdapter by agent {
        override fun send(request: AgentRequest): Flow<AgentEvent> = agent.send(request.copy(model = chosen))
    }
}

/** Somewhere agents come from: the CLIs on this machine, an ACP server list, providers configured in settings. */
interface AgentSource {
    /** The agents this source can offer right now. [rescan] drops anything cached, e.g. after the user installs a CLI. */
    suspend fun discover(rescan: Boolean = false): List<AgentAdapter>
}

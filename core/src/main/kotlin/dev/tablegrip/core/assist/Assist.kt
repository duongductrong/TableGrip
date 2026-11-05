package dev.tablegrip.core.assist

import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentException
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.AgentRequest
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * One request to an agent that must come back as data, not chat: a fix for a failed statement, a filter, a chart. The
 * agent answers with a JSON object, which [parse] checks against what TableGrip knows (the schema, the dialect) before
 * anything is shown. An assist only proposes: whatever it returns is applied by the user, through the usual checks.
 */
interface AssistTask<O> {
    /** For logs, e.g. `fix-sql`. */
    val name: String

    /** The task's role and rules. */
    fun system(): String

    /** The context and the request, ending with the JSON shape expected back. */
    fun prompt(): String

    /** Reads the agent's answer; throws [AssistInvalid] saying what's wrong, which goes back to the agent once. */
    fun parse(json: JsonObject): O
}

/** An answer that can't be used, with reasons specific enough for the agent to correct it. */
class AssistInvalid(val reasons: List<String>) : Exception(reasons.joinToString("; ")) {
    constructor(reason: String) : this(listOf(reason))
}

sealed interface AssistResult<out O> {
    data class Ok<O>(val value: O, val agent: String, val elapsedMillis: Long) : AssistResult<O>

    /** The agent answered, twice, with something that didn't pass [AssistTask.parse]. */
    data class Invalid(val reasons: List<String>) : AssistResult<Nothing>

    /** The agent couldn't be reached, failed, or took too long. */
    data class Failed(val message: String) : AssistResult<Nothing>
}

/**
 * Runs [AssistTask]s on an agent: one turn, plus one more to correct an answer that didn't parse. Cancelling the caller
 * stops the agent. Logs what ran, by whom and how it went, never what was sent or answered.
 */
class AssistRunner(private val timeout: Duration = 3.minutes, private val workingDirectory: Path? = null) {
    suspend fun <O> run(agent: AgentAdapter, task: AssistTask<O>): AssistResult<O> {
        val started = System.currentTimeMillis()
        val messages = mutableListOf(AgentMessage(AgentMessage.Role.User, task.prompt()))
        var reasons = emptyList<String>()
        for (attempt in 1..Attempts) {
            val request = AgentRequest(messages.toList(), system = task.system(), workingDirectory = workingDirectory, reminder = Reminder)
            val answer =
                try {
                    withTimeoutOrNull(timeout) { answer(agent, request) }
                } catch (e: AgentException) {
                    return failed(task, agent, started, e.message ?: "${agent.descriptor.name} failed.")
                } ?: return failed(task, agent, started, "${agent.descriptor.name} didn't answer within ${timeout.inWholeSeconds} s.")
            reasons =
                try {
                    val json = AssistJson.extract(answer) ?: throw AssistInvalid("The answer contained no JSON object.")
                    val value = task.parse(json)
                    val elapsed = System.currentTimeMillis() - started
                    log.info("Assist {} by {}: ok in {} ms (attempt {})", task.name, agent.descriptor.id, elapsed, attempt)
                    return AssistResult.Ok(value, agent.descriptor.name, elapsed)
                } catch (e: AssistInvalid) {
                    e.reasons
                } catch (e: IllegalArgumentException) {
                    // Wrong JSON types, e.g. a number where a string was expected.
                    listOf(e.message ?: "The JSON has the wrong shape.")
                }
            messages += AgentMessage(AgentMessage.Role.Assistant, answer)
            messages +=
                AgentMessage(
                    AgentMessage.Role.User,
                    "That answer can't be used:\n${reasons.joinToString("\n") { "- $it" }}\nReply again with only the corrected JSON object.",
                )
        }
        log.info("Assist {} by {}: invalid after {} attempts ({} ms)", task.name, agent.descriptor.id, Attempts, System.currentTimeMillis() - started)
        return AssistResult.Invalid(reasons)
    }

    private suspend fun answer(agent: AgentAdapter, request: AgentRequest): String {
        val streamed = StringBuilder()
        var completed: String? = null
        agent.send(request).collect { event ->
            when (event) {
                AgentEvent.Thinking, is AgentEvent.ReasoningDelta, is AgentEvent.Activity -> Unit
                is AgentEvent.TextDelta -> streamed.append(event.text)
                is AgentEvent.Completed -> completed = event.text
            }
        }
        return completed ?: streamed.toString()
    }

    private fun failed(task: AssistTask<*>, agent: AgentAdapter, started: Long, message: String): AssistResult.Failed {
        log.info("Assist {} by {}: failed after {} ms", task.name, agent.descriptor.id, System.currentTimeMillis() - started)
        return AssistResult.Failed(message)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AssistRunner::class.java)
        private const val Attempts = 2

        /** Restated last, where CLI agents weigh it most: they are tools that like to act. */
        const val Reminder = "Don't run tools, commands or queries. Reply with exactly one JSON object in a ```json block, nothing else."
    }
}

/** Finds the JSON object in an agent's answer, which may come with prose around it however clearly it was asked not to. */
object AssistJson {
    private val json = Json { isLenient = true }
    private val Fence = Regex("```(?:json)?[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** The last fenced JSON object, else the last balanced `{…}` that parses; null if there's none. */
    fun extract(text: String): JsonObject? {
        Fence.findAll(text).toList().asReversed().forEach { match -> parse(match.groupValues[1])?.let { return it } }
        return objects(text).asReversed().firstNotNullOfOrNull(::parse)
    }

    private fun parse(candidate: String): JsonObject? = runCatching { json.parseToJsonElement(candidate.trim()) as? JsonObject }.getOrNull()

    /** Top-level `{…}` spans, skipping braces inside strings. */
    private fun objects(text: String): List<String> {
        val spans = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for ((i, c) in text.withIndex()) {
            when {
                inString && escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString && c == '"' -> inString = false
                inString -> Unit
                c == '"' && depth > 0 -> inString = true
                c == '{' -> if (depth++ == 0) start = i
                c == '}' && depth > 0 -> if (--depth == 0) spans += text.substring(start, i + 1)
            }
        }
        return spans
    }
}

/** A string field of an assist answer; blank counts as missing. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** A required string field, or [AssistInvalid] naming it. */
fun JsonObject.requireText(key: String): String = text(key) ?: throw AssistInvalid("\"$key\" must be a non-empty string.")

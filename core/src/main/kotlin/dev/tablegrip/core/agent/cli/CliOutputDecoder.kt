package dev.tablegrip.core.agent.cli

import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a preset prints on stdout, and how to turn it back into [AgentEvent]s.
 *
 * Plain text streams by itself, but several CLIs only stream when asked for NDJSON (Claude Code's text mode prints the
 * whole answer at exit), and NDJSON also says how the run ended: some report a failure inside the stream and exit 0.
 */
enum class CliOutputFormat {
    PlainText,
    /** Claude Code's `stream-json`, which Cursor Agent also speaks. */
    ClaudeStreamJson,
    GeminiStreamJson,
    /** `codex exec --json`: whole messages, no token deltas. */
    CodexJson,
    AntigravityStreamJson,
    /** `opencode run --format json`: each text part whole once it is finished. */
    OpenCodeJson;

    fun decoder(title: String): CliOutputDecoder =
        when (this) {
            PlainText -> PlainTextDecoder()
            ClaudeStreamJson -> ClaudeStreamDecoder(title)
            GeminiStreamJson -> GeminiStreamDecoder(title)
            CodexJson -> CodexJsonDecoder(title)
            AntigravityStreamJson -> AntigravityStreamDecoder(title)
            OpenCodeJson -> OpenCodeJsonDecoder(title)
        }
}

/** Fed stdout as it arrives; owned by one run, so nothing here needs locking. */
interface CliOutputDecoder {
    /** Returns the events completed by [chunk]. Chunks are pipe reads, not lines. */
    fun consume(chunk: String): List<AgentEvent>

    /** The answer once the process has exited, or the reason there is none. */
    fun finish(exitStatus: Int, stderr: String): String
}

private class PlainTextDecoder : CliOutputDecoder {
    private val text = StringBuilder()

    override fun consume(chunk: String): List<AgentEvent> {
        text.append(chunk)
        return listOf(AgentEvent.TextDelta(chunk))
    }

    // Several agents exit non-zero on a warning they also print: only an empty run is a failure, and the adapter says why.
    override fun finish(exitStatus: Int, stderr: String): String = text.toString().trim()
}

/** Splits chunks into lines and tolerates the stray non-JSON line (update notices, warnings) these tools print. */
private abstract class NdjsonDecoder(protected val title: String) : CliOutputDecoder {
    private var partial = StringBuilder()
    protected val streamed = StringBuilder()
    /** Anything parsed at all: a stream of garbage fails differently from one that ran and said nothing. */
    protected var parsedAnything = false

    override fun consume(chunk: String): List<AgentEvent> {
        partial.append(chunk)
        val lines = partial.split('\n')
        partial = StringBuilder(lines.last())
        return lines.dropLast(1).flatMap(::line)
    }

    /** Flushes the last line, which need not end in a newline. Subclasses call it before deciding the outcome. */
    protected fun flush() {
        val rest = partial.toString()
        partial = StringBuilder()
        line(rest)
    }

    private fun line(raw: String): List<AgentEvent> {
        val line = raw.trim()
        if (!line.startsWith("{")) return emptyList()
        val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        parsedAnything = true
        return event(json)
    }

    protected fun delta(text: String): List<AgentEvent> {
        if (text.isEmpty()) return emptyList()
        streamed.append(text)
        return listOf(AgentEvent.TextDelta(text))
    }

    protected fun reasoning(text: String): List<AgentEvent> = if (text.isEmpty()) emptyList() else listOf(AgentEvent.ReasoningDelta(text))

    /** Labels by id, so an update that only says how an activity ended still names it. */
    private val labels = mutableMapOf<String, String>()

    protected fun activity(id: String, label: String?, state: AgentEvent.Activity.State): List<AgentEvent> {
        val name = label?.takeIf { it.isNotBlank() }?.let(::oneLine)?.also { labels[id] = it } ?: labels[id] ?: return emptyList()
        return listOf(AgentEvent.Activity(id, name, state))
    }

    protected abstract fun event(json: JsonObject): List<AgentEvent>

    /** Nothing parsed means the CLI failed before it streamed anything; that story is in stderr. */
    protected fun noOutcome(stderr: String): String {
        val partial = streamed.toString().trim()
        if (partial.isNotEmpty()) return partial
        if (!parsedAnything) throw CliFailure(stderr)
        throw AgentException("$title returned nothing.")
    }
}

/** Raised with the raw stderr, which the adapter boils down to one line. */
internal class CliFailure(val stderr: String) : Exception()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

/** An activity's label fits one row of the chat. */
private fun oneLine(text: String): String = text.lineSequence().first().trim().let { if (it.length > 80) it.take(79) + "…" else it }

/** What a tool call was about, from the argument that usually says it: a path, a command, a query. */
private fun toolLabel(tool: String, input: JsonObject?): String {
    val detail = listOf("file_path", "path", "command", "pattern", "query", "url", "description").firstNotNullOfOrNull { input?.string(it) }
    return if (detail.isNullOrBlank()) tool else "$tool · $detail"
}

/**
 * `--output-format stream-json`. Claude Code sends token deltas as `stream_event`s (with `--include-partial-messages`)
 * and whole `assistant` messages besides. Cursor Agent sends its deltas as `assistant` messages carrying `timestamp_ms`,
 * then the whole message again without one. Both end with a `result`.
 */
private class ClaudeStreamDecoder(title: String) : NdjsonDecoder(title) {
    private var result: String? = null
    private var error: String? = null
    private val whole = StringBuilder()

    override fun event(json: JsonObject): List<AgentEvent> =
        when (json.string("type")) {
            "stream_event" -> {
                val event = json.obj("event")
                val delta = event?.obj("delta")
                when {
                    event?.string("type") != "content_block_delta" -> emptyList()
                    delta?.string("type") == "text_delta" -> delta(delta.string("text").orEmpty())
                    delta?.string("type") == "thinking_delta" -> reasoning(delta.string("thinking").orEmpty())
                    else -> emptyList()
                }
            }
            "assistant" -> {
                val message = json.obj("message")
                val text = message?.let(::textOf).orEmpty()
                val tools = message?.array("content").orEmpty().filter { it.string("type") == "tool_use" }.flatMap { use ->
                    activity(use.string("id").orEmpty(), toolLabel(use.string("name").orEmpty(), use.obj("input")), AgentEvent.Activity.State.Running)
                }
                tools + if (json.containsKey("timestamp_ms")) delta(text) else emptyList<AgentEvent>().also { whole.append(text) }
            }
            // Tool results come back as the user's turn.
            "user" -> json.obj("message")?.array("content").orEmpty().filter { it.string("type") == "tool_result" }.flatMap { result ->
                val failed = (result["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                activity(result.string("tool_use_id").orEmpty(), null, if (failed) AgentEvent.Activity.State.Failed else AgentEvent.Activity.State.Done)
            }
            // Cursor Agent's own events: reasoning deltas, and tool calls named by their key, e.g. `readToolCall`.
            "thinking" -> if (json.string("subtype") == "delta") reasoning(json.string("text").orEmpty()) else emptyList()
            "tool_call" -> {
                val call = json.obj("tool_call")?.entries?.firstOrNull()
                val body = call?.value as? JsonObject
                val name = call?.key?.removeSuffix("ToolCall")
                val result = body?.obj("result")
                val state = when {
                    json.string("subtype") != "completed" -> AgentEvent.Activity.State.Running
                    result != null && (result.containsKey("error") || result.containsKey("rejected")) -> AgentEvent.Activity.State.Failed
                    else -> AgentEvent.Activity.State.Done
                }
                activity(json.string("call_id").orEmpty(), name?.let { toolLabel(it, body?.obj("args")) }, state)
            }
            "result" -> {
                val text = json.string("result").orEmpty()
                if ((json["is_error"] as? JsonPrimitive)?.booleanOrNull == true || json.string("subtype")?.startsWith("error") == true) {
                    error = text.ifBlank { json.string("subtype") }
                } else {
                    result = text
                }
                emptyList()
            }
            else -> emptyList()
        }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        error?.let { throw AgentException(it) }
        val answer = (result ?: streamed.toString().ifBlank { whole.toString() }).trim()
        return answer.ifEmpty { noOutcome(stderr) }
    }

    private fun textOf(message: JsonObject): String =
        (message["content"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").orEmpty() }
}

/** `gemini -o stream-json`: `message` events with `delta: true` while it streams, then a `result` with a status. */
private class GeminiStreamDecoder(title: String) : NdjsonDecoder(title) {
    private var error: String? = null

    override fun event(json: JsonObject): List<AgentEvent> =
        when (json.string("type")) {
            "message" -> if (json.string("role") == "assistant") delta(json.string("content").orEmpty()) else emptyList()
            "tool_use" -> activity(json.string("tool_id").orEmpty(), toolLabel(json.string("tool_name").orEmpty(), json.obj("parameters")), AgentEvent.Activity.State.Running)
            "tool_result" -> activity(json.string("tool_id").orEmpty(), null, if (json.string("status") == "error") AgentEvent.Activity.State.Failed else AgentEvent.Activity.State.Done)
            "error" -> emptyList<AgentEvent>().also { error = json.string("message") ?: error }
            "result" -> {
                if (json.string("status") == "error") error = json.obj("error")?.string("message") ?: json.string("error") ?: "$title failed."
                emptyList()
            }
            else -> emptyList()
        }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        if (streamed.isBlank()) error?.let { throw AgentException(it) }
        return noOutcome(stderr)
    }
}

/**
 * `codex exec --json`. The answer arrives as whole `agent_message` items. Items of type `error` are warnings (a skill
 * that didn't load, fallback model metadata) and the turn carries on; `turn.failed` is the real failure.
 */
private class CodexJsonDecoder(title: String) : NdjsonDecoder(title) {
    private var error: String? = null

    private var reasoned = false

    override fun event(json: JsonObject): List<AgentEvent> =
        when (json.string("type")) {
            "item.completed" -> {
                val item = json.obj("item")
                when (item?.string("type")) {
                    "agent_message" -> {
                        val text = item.string("text").orEmpty()
                        delta(if (streamed.isEmpty()) text else "\n\n$text")
                    }
                    "reasoning" -> {
                        val text = item.string("text").orEmpty().trim()
                        reasoning(if (reasoned) "\n\n$text" else text).also { if (text.isNotEmpty()) reasoned = true }
                    }
                    else -> work(item, done = true)
                }
            }
            "item.started", "item.updated" -> work(json.obj("item"), done = false)
            "turn.failed" -> emptyList<AgentEvent>().also { error = json.obj("error")?.string("message") ?: "$title failed." }
            "error" -> emptyList<AgentEvent>().also { error = error ?: json.string("message") }
            else -> emptyList()
        }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        if (streamed.isBlank()) error?.let { throw AgentException(unwrapApiError(it)) }
        return noOutcome(stderr)
    }

    /** Commands, tool calls, searches and edits, as activities. */
    private fun work(item: JsonObject?, done: Boolean): List<AgentEvent> {
        item ?: return emptyList()
        val label = when (item.string("type")) {
            "command_execution" -> item.string("command")?.let { "Ran $it" }
            "mcp_tool_call" -> listOfNotNull(item.string("server"), item.string("tool")).joinToString(".").ifEmpty { null }
            "web_search" -> item.string("query")?.let { "Searched $it" }
            "file_change" -> item.array("changes").mapNotNull { it.string("path") }.joinToString().ifEmpty { null }?.let { "Edited $it" }
            else -> return emptyList()
        }
        val status = item.string("status")
        val exit = (item["exit_code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val state = when {
            status == "failed" || status == "declined" || (exit != null && exit != 0) -> AgentEvent.Activity.State.Failed
            done || status == "completed" -> AgentEvent.Activity.State.Done
            else -> AgentEvent.Activity.State.Running
        }
        return activity(item.string("id").orEmpty(), label, state)
    }

    /** Codex passes the API's error body through as a string of JSON; its `message` is the readable part. */
    private fun unwrapApiError(message: String): String =
        runCatching { Json.parseToJsonElement(message).jsonObject.obj("error")?.string("message") }.getOrNull() ?: message
}

/** Antigravity's `stream-json`: `step_update` events carrying `text_delta`, then a `result` with its own status. */
private class AntigravityStreamDecoder(title: String) : NdjsonDecoder(title) {
    private var status: String? = null
    private var response = ""
    private var error: String? = null

    override fun event(json: JsonObject): List<AgentEvent> =
        when (json.string("event")) {
            "step_update" -> step(json.obj("step_update"))
            "result" -> {
                val result = json.obj("result")
                status = result?.string("status") ?: "INVALID"
                response = result?.string("response").orEmpty()
                error = result?.string("error")?.takeIf { it.isNotBlank() }
                emptyList()
            }
            else -> emptyList()
        }

    /** The answer streams in `agent_response` steps; any other step but the user's own is work worth showing. */
    private fun step(update: JsonObject?): List<AgentEvent> {
        update ?: return emptyList()
        val type = update.string("step_type").orEmpty()
        val text = update.string("text_delta").orEmpty()
        if (type.contains("thinking")) return reasoning(text)
        if (type.isEmpty() || type == "agent_response" || type == "user_input") return delta(text)
        val state = when (update.string("state")) {
            "DONE" -> AgentEvent.Activity.State.Done
            "ERROR", "FAILED", "CANCELED", "CANCELLED" -> AgentEvent.Activity.State.Failed
            else -> AgentEvent.Activity.State.Running
        }
        val label = type.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return activity("step-${update.string("step_index")}", label, state) + delta(text)
    }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        // Antigravity exits 0 with the refusal where the answer should be, so its own status decides.
        val answer = response.ifEmpty { streamed.toString() }.trim()
        return when {
            status == null -> noOutcome(stderr)
            status == "SUCCESS" && answer.isNotEmpty() -> answer
            answer.isNotEmpty() && error == null -> answer
            else -> throw AgentException(error ?: "$title ended in state $status.")
        }
    }
}

/**
 * `opencode run --format json`: `text` and `reasoning` parts arrive whole once finished, tool calls as `tool_use` parts
 * with their state, and a failure as an `error` event while the process still exits 0.
 */
private class OpenCodeJsonDecoder(title: String) : NdjsonDecoder(title) {
    private var error: String? = null
    private var reasoned = false

    override fun event(json: JsonObject): List<AgentEvent> {
        val part = json.obj("part")
        return when (json.string("type")) {
            "text" -> {
                val text = part?.string("text").orEmpty()
                delta(if (streamed.isEmpty() || text.isEmpty()) text else "\n\n$text")
            }
            "reasoning" -> {
                val text = part?.string("text").orEmpty().trim()
                reasoning(if (reasoned) "\n\n$text" else text).also { if (text.isNotEmpty()) reasoned = true }
            }
            "tool_use" -> {
                val state = part?.obj("state")
                val status = when (state?.string("status")) {
                    "completed" -> AgentEvent.Activity.State.Done
                    "error" -> AgentEvent.Activity.State.Failed
                    else -> AgentEvent.Activity.State.Running
                }
                val label = state?.string("title")?.takeIf { it.isNotBlank() }?.let { "${part?.string("tool")} · $it" } ?: toolLabel(part?.string("tool").orEmpty(), state?.obj("input"))
                activity(part?.string("callID") ?: part?.string("id").orEmpty(), label, status)
            }
            "error" -> emptyList<AgentEvent>().also {
                val body = json.obj("error")
                error = body?.string("message") ?: body?.obj("data")?.string("message") ?: "$title failed."
            }
            else -> emptyList()
        }
    }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        if (streamed.isBlank()) error?.let { throw AgentException(it) }
        return noOutcome(stderr)
    }
}

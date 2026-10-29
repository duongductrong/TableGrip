package dev.datagrok.core.agent.cli

import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentException
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
    AntigravityStreamJson;

    fun decoder(title: String): CliOutputDecoder =
        when (this) {
            PlainText -> PlainTextDecoder()
            ClaudeStreamJson -> ClaudeStreamDecoder(title)
            GeminiStreamJson -> GeminiStreamDecoder(title)
            CodexJson -> CodexJsonDecoder(title)
            AntigravityStreamJson -> AntigravityStreamDecoder(title)
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
                if (event?.string("type") == "content_block_delta" && delta?.string("type") == "text_delta") delta(delta.string("text").orEmpty()) else emptyList()
            }
            "assistant" -> {
                val text = json.obj("message")?.let(::textOf).orEmpty()
                if (json.containsKey("timestamp_ms")) delta(text) else emptyList<AgentEvent>().also { whole.append(text) }
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

    override fun event(json: JsonObject): List<AgentEvent> =
        when (json.string("type")) {
            "item.completed" -> {
                val item = json.obj("item")
                if (item?.string("type") == "agent_message") {
                    val text = item.string("text").orEmpty()
                    delta(if (streamed.isEmpty()) text else "\n\n$text")
                } else {
                    emptyList()
                }
            }
            "turn.failed" -> emptyList<AgentEvent>().also { error = json.obj("error")?.string("message") ?: "$title failed." }
            "error" -> emptyList<AgentEvent>().also { error = error ?: json.string("message") }
            else -> emptyList()
        }

    override fun finish(exitStatus: Int, stderr: String): String {
        flush()
        if (streamed.isBlank()) error?.let { throw AgentException(unwrapApiError(it)) }
        return noOutcome(stderr)
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
            "step_update" -> delta(json.obj("step_update")?.string("text_delta").orEmpty())
            "result" -> {
                val result = json.obj("result")
                status = result?.string("status") ?: "INVALID"
                response = result?.string("response").orEmpty()
                error = result?.string("error")?.takeIf { it.isNotBlank() }
                emptyList()
            }
            else -> emptyList()
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

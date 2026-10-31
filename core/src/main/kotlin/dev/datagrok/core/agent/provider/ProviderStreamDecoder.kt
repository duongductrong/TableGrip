package dev.datagrok.core.agent.provider

import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns a provider's server-sent events back into [AgentEvent]s. Fed the payload of each `data:` line; owned by one
 * turn, so nothing here needs locking. Both protocols put the event's type inside the JSON, so `event:` lines are not
 * needed.
 */
internal interface ProviderStreamDecoder {
    fun data(payload: String): List<AgentEvent>

    /** The whole answer once the stream has ended, or the reason there is none. */
    fun finish(): String
}

internal fun ProviderProtocol.decoder(title: String): ProviderStreamDecoder =
    when (this) {
        ProviderProtocol.Anthropic -> AnthropicStreamDecoder(title)
        ProviderProtocol.OpenAi -> OpenAiStreamDecoder(title)
    }

/** The readable part of an API error body, `{"error": {"message": …}}` in both protocols. */
internal fun errorMessage(body: String): String? =
    runCatching { (Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.string("message") }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun parse(payload: String): JsonObject? = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()

private abstract class TextStreamDecoder(protected val title: String) : ProviderStreamDecoder {
    protected val text = StringBuilder()
    /** The provider's safety layer stopped the answer; said so rather than "returned nothing". */
    protected var refused = false

    protected fun delta(chunk: String?): List<AgentEvent> {
        if (chunk.isNullOrEmpty()) return emptyList()
        text.append(chunk)
        return listOf(AgentEvent.TextDelta(chunk))
    }

    protected fun reasoning(chunk: String?): List<AgentEvent> = if (chunk.isNullOrEmpty()) emptyList() else listOf(AgentEvent.ReasoningDelta(chunk))

    override fun finish(): String {
        val answer = text.toString().trim()
        return when {
            answer.isNotEmpty() -> answer
            refused -> throw AgentException("$title declined to answer.")
            else -> throw AgentException("$title returned nothing.")
        }
    }
}

/** Messages API events: `content_block_delta` carries the text, `message_delta` the stop reason, `error` a failure mid-stream. */
private class AnthropicStreamDecoder(title: String) : TextStreamDecoder(title) {
    override fun data(payload: String): List<AgentEvent> {
        val json = parse(payload) ?: return emptyList()
        return when (json.string("type")) {
            "content_block_delta" -> {
                val delta = json.obj("delta")
                when (delta?.string("type")) {
                    "text_delta" -> delta(delta.string("text"))
                    "thinking_delta" -> reasoning(delta.string("thinking"))
                    else -> emptyList()
                }
            }
            "message_delta" -> emptyList<AgentEvent>().also { if (json.obj("delta")?.string("stop_reason") == "refusal") refused = true }
            "error" -> throw AgentException(json.obj("error")?.string("message") ?: "$title failed.")
            else -> emptyList()
        }
    }
}

/**
 * Chat Completions chunks: text in `choices[0].delta.content`, then a literal `[DONE]`. Reasoning models served this way
 * put their thinking in `reasoning_content` (DeepSeek, vLLM) or `reasoning` (OpenRouter).
 */
private class OpenAiStreamDecoder(title: String) : TextStreamDecoder(title) {
    override fun data(payload: String): List<AgentEvent> {
        if (payload == "[DONE]") return emptyList()
        val json = parse(payload) ?: return emptyList()
        json.obj("error")?.let { throw AgentException(it.string("message") ?: "$title failed.") }
        val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        if (choice.string("finish_reason") == "content_filter") refused = true
        val delta = choice.obj("delta")
        return reasoning(delta?.string("reasoning_content") ?: delta?.string("reasoning")) + delta(delta?.string("content"))
    }
}

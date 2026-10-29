package dev.datagrok.core.agent.provider

import dev.datagrok.core.agent.AgentAdapter
import dev.datagrok.core.agent.AgentDescriptor
import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentException
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.AgentSource
import dev.datagrok.core.agent.AgentTransport
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The provider configured in settings, as one agent. Discovery only reads settings: nothing is sent to the provider,
 * and the API key is looked up when a turn starts, so editing settings never costs a request.
 */
class ProviderAgentSource(
    private val config: () -> ProviderConfig,
    private val apiKey: (ProviderProtocol) -> String?,
    private val http: HttpClient = defaultClient,
) : AgentSource {
    override suspend fun discover(rescan: Boolean): List<AgentAdapter> = listOf(ProviderAgentAdapter(config(), apiKey, http))

    private companion object {
        val defaultClient: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }
    }
}

/**
 * Streams one turn from an HTTP provider. Stateless like every adapter: the whole conversation goes with each request.
 *
 * The agent answers and never acts: no tools are offered, so a query it wants run comes back as text for the user to
 * allow in the chat, exactly as with the CLI agents.
 */
class ProviderAgentAdapter(
    private val config: ProviderConfig,
    private val apiKey: (ProviderProtocol) -> String?,
    private val http: HttpClient,
) : AgentAdapter {
    private val title = config.protocol.title

    override val descriptor = AgentDescriptor("provider:${config.protocol.name.lowercase()}", config.model, AgentTransport.Http, config.baseUrl)

    override fun send(request: AgentRequest): Flow<AgentEvent> =
        channelFlow {
            send(AgentEvent.Thinking)
            val key = withContext(Dispatchers.IO) { apiKey(config.protocol)?.takeIf { it.isNotBlank() } }
            if (key == null && config.protocol == ProviderProtocol.Anthropic) {
                throw AgentException("No API key for $title. Add one in Settings › Agent, or set ${config.protocol.keyVariable}.")
            }
            // Cancelling the await cancels the future, which aborts the exchange.
            val response =
                try {
                    http.sendAsync(httpRequest(request, key), HttpResponse.BodyHandlers.ofInputStream()).await()
                } catch (e: java.util.concurrent.CompletionException) {
                    throw AgentException("Couldn't reach $title at ${config.baseUrl}.", e.cause ?: e)
                } catch (e: java.io.IOException) {
                    throw AgentException("Couldn't reach $title at ${config.baseUrl}.", e)
                }
            val body = response.body()
            try {
                if (response.statusCode() !in 200..299) {
                    val text = withContext(Dispatchers.IO) { body.readBounded(ERROR_CAP) }
                    throw AgentException("$title: ${errorMessage(text) ?: "HTTP ${response.statusCode()}"}")
                }
                val decoder = config.protocol.decoder(title)
                val reader = launch(Dispatchers.IO) {
                    body.bufferedReader().useLines { lines ->
                        lines.filter { it.startsWith("data:") }.forEach { line -> decoder.data(line.removePrefix("data:").trim()).forEach { send(it) } }
                    }
                }
                // Cancelling the collector cancels this join; closing the body in the finally then unblocks the reader.
                reader.join()
                send(AgentEvent.Completed(decoder.finish()))
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { runCatching { body.close() } }
            }
        }

    private fun httpRequest(request: AgentRequest, key: String?): HttpRequest {
        val builder = HttpRequest.newBuilder().header("content-type", "application/json").header("accept", "text/event-stream").timeout(Duration.ofMinutes(5))
        val body =
            when (config.protocol) {
                ProviderProtocol.Anthropic -> {
                    builder.uri(URI.create("${config.baseUrl}/v1/messages")).header("anthropic-version", ANTHROPIC_VERSION)
                    key?.let { builder.header("x-api-key", it) }
                    buildJsonObject {
                        put("model", config.model)
                        put("max_tokens", MAX_TOKENS)
                        put("stream", true)
                        request.system?.takeIf { it.isNotBlank() }?.let { put("system", it.trim()) }
                        put("messages", messages(request))
                    }
                }
                ProviderProtocol.OpenAi -> {
                    builder.uri(URI.create("${config.baseUrl}/chat/completions"))
                    // Local servers usually take no key; they get no Authorization header rather than an empty one.
                    key?.let { builder.header("authorization", "Bearer $it") }
                    buildJsonObject {
                        put("model", config.model)
                        put("stream", true)
                        val system = request.system?.takeIf { it.isNotBlank() }?.let { message("system", it.trim()) }
                        put("messages", JsonArray(listOfNotNull(system) + messages(request)))
                    }
                }
            }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
    }

    /**
     * The conversation as alternating turns. A failed reply is left out of the history, which can leave two user turns
     * in a row; they are joined rather than sent as a shape some providers reject. The reminder goes on the last turn.
     */
    private fun messages(request: AgentRequest): JsonArray {
        val merged = mutableListOf<Pair<AgentMessage.Role, String>>()
        request.messages.filter { it.text.isNotBlank() }.forEach { message ->
            val last = merged.lastOrNull()
            if (last?.first == message.role) merged[merged.lastIndex] = last.first to "${last.second}\n\n${message.text.trim()}"
            else merged += message.role to message.text.trim()
        }
        request.reminder?.takeIf { it.isNotBlank() }?.let { reminder ->
            val (role, text) = merged.lastOrNull() ?: return@let
            if (role == AgentMessage.Role.User) merged[merged.lastIndex] = role to "<system-reminder>\n${reminder.trim()}\n</system-reminder>\n\n$text"
        }
        return buildJsonArray { merged.forEach { (role, text) -> add(message(if (role == AgentMessage.Role.User) "user" else "assistant", text)) } }
    }

    private fun message(role: String, text: String) = JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(text)))

    private companion object {
        const val ANTHROPIC_VERSION = "2026-10-21"
        /** A cap on one answer, not a spend: only what the model writes is billed. */
        const val MAX_TOKENS = 16_000
        const val ERROR_CAP = 16 * 1024

        fun InputStream.readBounded(cap: Int): String = readNBytes(cap).toString(Charsets.UTF_8)
    }
}

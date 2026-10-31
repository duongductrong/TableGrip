package dev.datagrok.core.agent.provider

import com.sun.net.httpserver.HttpServer
import dev.datagrok.core.agent.AgentEvent
import dev.datagrok.core.agent.AgentException
import dev.datagrok.core.agent.AgentMessage
import dev.datagrok.core.agent.AgentRegistry
import dev.datagrok.core.agent.AgentRequest
import dev.datagrok.core.agent.AgentTransport
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.SettingsStore
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Everything here runs against recorded streams or a server on localhost: no provider is ever called. */
class ProviderAgentTest {
    private val requests = mutableListOf<Triple<String, Map<String, String>, String>>()
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** A provider on localhost answering every request with [status] and [body], recording what it was sent. */
    private fun serve(status: Int, body: String, contentType: String = "text/event-stream"): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            requests += Triple(exchange.requestURI.path, headers, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("content-type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        this.server = server
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun adapter(protocol: ProviderProtocol, baseUrl: String, key: String? = "test-key") =
        ProviderAgentAdapter(ProviderConfig.of(protocol, baseUrl, ""), { key }, HttpClient.newHttpClient())

    private val request =
        AgentRequest(
            listOf(
                AgentMessage(AgentMessage.Role.User, "List tables"),
                AgentMessage(AgentMessage.Role.Assistant, "users, orders"),
                AgentMessage(AgentMessage.Role.User, "Count users"),
                AgentMessage(AgentMessage.Role.User, "Only active ones"),
            ),
            system = "You help with SQL.",
            reminder = "Suggest, don't run.",
        )

    private fun decode(protocol: ProviderProtocol, vararg payloads: String): Pair<String, String> {
        val decoder = protocol.decoder("Provider")
        val deltas = payloads.flatMap(decoder::data).filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        return deltas to decoder.finish()
    }

    @Test
    fun `anthropic stream yields text deltas`() {
        val result =
            decode(
                ProviderProtocol.Anthropic,
                """{"type":"message_start","message":{"id":"msg_1"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """{"type":"message_stop"}""",
            )
        assertEquals("Hello" to "Hello", result)
    }

    @Test
    fun `anthropic error event and refusal fail the turn`() {
        val error = assertFailsWith<AgentException> { decode(ProviderProtocol.Anthropic, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") }
        assertEquals("Overloaded", error.message)
        val refusal = assertFailsWith<AgentException> { decode(ProviderProtocol.Anthropic, """{"type":"message_delta","delta":{"stop_reason":"refusal"}}""") }
        assertEquals("Provider declined to answer.", refusal.message)
    }

    @Test
    fun `openai stream yields content deltas until DONE`() {
        val result =
            decode(
                ProviderProtocol.OpenAi,
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"SELECT"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":" 1"},"finish_reason":"stop"}]}""",
                "[DONE]",
            )
        assertEquals("SELECT 1" to "SELECT 1", result)
    }

    @Test
    fun `reasoning streams apart from the answer in both protocols`() {
        fun reasoning(protocol: ProviderProtocol, vararg payloads: String): Pair<String, String> {
            val decoder = protocol.decoder("Provider")
            val thought = payloads.flatMap(decoder::data).filterIsInstance<AgentEvent.ReasoningDelta>().joinToString("") { it.text }
            return thought to decoder.finish()
        }
        assertEquals(
            "Count rows." to "42",
            reasoning(
                ProviderProtocol.Anthropic,
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Count rows."}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"42"}}""",
            ),
        )
        assertEquals(
            "Count rows." to "42",
            reasoning(
                ProviderProtocol.OpenAi,
                """{"choices":[{"index":0,"delta":{"reasoning_content":"Count "}}]}""",
                """{"choices":[{"index":0,"delta":{"reasoning":"rows."}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"42"}}]}""",
                "[DONE]",
            ),
        )
    }

    @Test
    fun `anthropic request carries system, alternating turns and the reminder`() = runBlocking {
        val base =
            serve(
                200,
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"42\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            )
        val events = adapter(ProviderProtocol.Anthropic, base).send(request).toList()
        assertEquals(AgentEvent.Completed("42"), events.last())

        val (path, headers, body) = requests.single()
        assertEquals("/v1/messages", path)
        assertEquals("test-key", headers["x-api-key"])
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("claude-opus-5-5", json["model"]!!.jsonPrimitive.content)
        assertEquals("You help with SQL.", json["system"]!!.jsonPrimitive.content)
        val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val last = messages.last()["content"]!!.jsonPrimitive.content
        assertTrue(last.startsWith("<system-reminder>\nSuggest, don't run.\n</system-reminder>"), last)
        assertTrue(last.endsWith("Count users\n\nOnly active ones"), last)
    }

    @Test
    fun `openai request puts the system first and sends no key when there is none`() = runBlocking {
        val base = serve(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
        val events = adapter(ProviderProtocol.OpenAi, base, key = null).send(request).toList()
        assertEquals(AgentEvent.Completed("ok"), events.last())

        val (path, headers, body) = requests.single()
        assertEquals("/chat/completions", path)
        assertEquals(null, headers["authorization"])
        val roles = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test
    fun `an api error is reported with the provider's message`() = runBlocking {
        val base = serve(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", "application/json")
        val error = assertFailsWith<AgentException> { adapter(ProviderProtocol.Anthropic, base).send(request).toList() }
        assertEquals("Anthropic: invalid x-api-key", error.message)
    }

    @Test
    fun `anthropic without a key fails before sending anything`() = runBlocking {
        val base = serve(200, "")
        val error = assertFailsWith<AgentException> { adapter(ProviderProtocol.Anthropic, base, key = null).send(request).toList() }
        assertTrue(error.message!!.contains("ANTHROPIC_API_KEY"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `provider mode offers the configured provider and discovery sends nothing`() = runBlocking {
        val base = serve(200, "")
        val registry = AgentRegistry.default(mode = { AgentMode.Provider }, provider = { ProviderConfig.of(ProviderProtocol.OpenAi, base, "local-model") })
        val agents = registry.discover()
        assertEquals(listOf("provider:openai"), agents.map { it.descriptor.id })
        assertEquals(AgentTransport.Http, agents.single().descriptor.transport)
        assertEquals("local-model", agents.single().descriptor.name)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `settings written before the choice existed stay on the CLI agents`() {
        val file = createTempDirectory("datagrok-settings").resolve("settings.json")
        file.writeText("""{"theme":"Dark","agent":"cli:codex"}""")
        val settings = SettingsStore(file).load()
        assertEquals("cli:codex", settings.agent)
        assertEquals(AgentMode.Cli, AgentMode.of(settings.agentMode))
        assertEquals(Settings().agentMode, settings.agentMode)
        assertEquals(AgentMode.Cli, AgentMode.of("Nonsense"))
    }
}

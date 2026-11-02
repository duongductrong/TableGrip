package dev.tablegrip.core.assist

import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentException
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** No agent is ever started: answers come from a script. */
class AssistRunnerTest {
    /** Answers turn n with [answers][n], recording each request. */
    private class Scripted(private vararg val answers: String) : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test", "Test agent", AgentTransport.Http)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            val answer = answers[requests.size - 1]
            emit(AgentEvent.Thinking)
            answer.chunked(7).forEach { emit(AgentEvent.TextDelta(it)) }
            emit(AgentEvent.Completed(answer))
        }
    }

    /** Wants `{"value": "<non-empty>"}` and nothing called "bad". */
    private object Echo : AssistTask<String> {
        override val name = "echo"
        override fun system() = "system"
        override fun prompt() = "prompt"
        override fun parse(json: JsonObject): String {
            val value = json.requireText("value")
            if (value == "bad") throw AssistInvalid("\"value\" can't be bad.")
            return value
        }
    }

    @Test
    fun `takes the JSON out of an answer with prose and fences around it`() {
        val agent = Scripted("Sure! Here you go:\n```json\n{\"value\": \"ok\"}\n```\nLet me know.")
        val result = runBlocking { AssistRunner().run(agent, Echo) }
        assertEquals("ok", assertIs<AssistResult.Ok<String>>(result).value)
        val request = agent.requests.single()
        assertEquals("system", request.system)
        assertEquals(AssistRunner.Reminder, request.reminder)
        assertEquals(listOf(AgentMessage(AgentMessage.Role.User, "prompt")), request.messages)
    }

    @Test
    fun `an unusable answer goes back once with the reasons, then gives up`() {
        val fixed = Scripted("{\"value\": \"bad\"}", "{\"value\": \"good\"}")
        assertEquals("good", assertIs<AssistResult.Ok<String>>(runBlocking { AssistRunner().run(fixed, Echo) }).value)
        val retry = fixed.requests[1].messages
        assertEquals(3, retry.size)
        assertEquals(AgentMessage.Role.Assistant, retry[1].role)
        assertTrue("\"value\" can't be bad." in retry[2].text)

        val stubborn = Scripted("no json here", "{\"value\": 3}")
        val invalid = assertIs<AssistResult.Invalid>(runBlocking { AssistRunner().run(stubborn, Echo) })
        assertEquals(listOf("\"value\" must be a non-empty string."), invalid.reasons)
        assertTrue("contained no JSON object" in stubborn.requests[1].messages.last().text)
    }

    @Test
    fun `agent failures and timeouts come back as a message`() {
        val failing = object : AgentAdapter {
            override val descriptor = AgentDescriptor("x", "X", AgentTransport.Cli)
            override fun send(request: AgentRequest): Flow<AgentEvent> = flow { throw AgentException("No API key") }
        }
        assertEquals(AssistResult.Failed("No API key"), runBlocking { AssistRunner().run(failing, Echo) })

        val silent = object : AgentAdapter {
            override val descriptor = AgentDescriptor("y", "Slow", AgentTransport.Cli)
            override fun send(request: AgentRequest): Flow<AgentEvent> = flow { awaitCancellation() }
        }
        val failed = assertIs<AssistResult.Failed>(runBlocking { AssistRunner(timeout = 50.milliseconds).run(silent, Echo) })
        assertTrue(failed.message.startsWith("Slow didn't answer"))
    }

    @Test
    fun `json extraction prefers the last fence, then the last balanced object`() {
        assertEquals("b", AssistJson.extract("```json\n{\"v\":\"a\"}\n```\n```json\n{\"v\":\"b\"}\n```")?.get("v")?.toString()?.trim('"'))
        assertEquals("}{", AssistJson.extract("first {\"v\": 1} then {\"v\": \"}{\"} done")?.text("v"))
        assertNull(AssistJson.extract("[1, 2]"))
        assertNull(AssistJson.extract("{ not json"))
    }
}

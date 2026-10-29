package dev.datagrok.core.agent

import dev.datagrok.core.agent.cli.CliOutputFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Recorded stdout of the real CLIs, fed in awkward chunks: pipe reads split lines anywhere. */
class CliOutputDecoderTest {
    private fun decode(format: CliOutputFormat, output: String, exitStatus: Int = 0): Pair<String, String> {
        val decoder = format.decoder("Agent")
        val deltas = output.chunked(7).flatMap(decoder::consume).filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        return deltas to decoder.finish(exitStatus, stderr = "")
    }

    @Test
    fun `cursor streams its timestamped deltas and skips the repeated whole message`() {
        val output =
            """
            {"type":"system","subtype":"init","apiKeySource":"login","model":"x"}
            {"type":"user","message":{"role":"user","content":[{"type":"text","text":"Count"}]}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"1\n2\n3\n4"}]},"timestamp_ms":1793237066348}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"\n5"}]},"timestamp_ms":1793237066376}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"1\n2\n3\n4\n5"}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"1\n2\n3\n4\n5"}
            """.trimIndent()
        assertEquals("1\n2\n3\n4\n5" to "1\n2\n3\n4\n5", decode(CliOutputFormat.ClaudeStreamJson, output))
    }

    @Test
    fun `claude streams text deltas`() {
        val output =
            """
            {"type":"system","subtype":"init","tools":[]}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hello"}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"Hello"}
            """.trimIndent()
        assertEquals("Hello" to "Hello", decode(CliOutputFormat.ClaudeStreamJson, output))
    }

    @Test
    fun `claude's error result is a failure, not an answer`() {
        val output = """{"type":"result","subtype":"success","is_error":true,"result":"Failed to authenticate: OAuth session expired"}"""
        val error = assertFailsWith<AgentException> { decode(CliOutputFormat.ClaudeStreamJson, output, exitStatus = 1) }
        assertEquals("Failed to authenticate: OAuth session expired", error.message)
    }

    @Test
    fun `codex warnings don't fail the turn but turn failed does`() {
        val answered =
            """
            {"type":"thread.started","thread_id":"t"}
            {"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata not found."}}
            {"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"pong"}}
            {"type":"turn.completed"}
            """.trimIndent()
        assertEquals("pong" to "pong", decode(CliOutputFormat.CodexJson, answered))

        val failed =
            """
            {"type":"item.completed","item":{"id":"item_0","type":"error","message":"Model metadata not found."}}
            {"type":"turn.failed","error":{"message":"{\"type\":\"error\",\"status\":400,\"error\":{\"type\":\"invalid_request_error\",\"message\":\"The model is not supported.\"}}"}}
            """.trimIndent()
        val error = assertFailsWith<AgentException> { decode(CliOutputFormat.CodexJson, failed, exitStatus = 1) }
        assertEquals("The model is not supported.", error.message)
    }

    @Test
    fun `gemini streams assistant deltas`() {
        val output =
            """
            {"type":"init","session_id":"s","model":"gemini"}
            {"type":"message","role":"user","content":"hi"}
            {"type":"message","role":"assistant","content":"Hi ","delta":true}
            {"type":"message","role":"assistant","content":"there","delta":true}
            {"type":"result","status":"success"}
            """.trimIndent()
        assertEquals("Hi there" to "Hi there", decode(CliOutputFormat.GeminiStreamJson, output))
    }

    @Test
    fun `antigravity trusts its own status over the exit code`() {
        val output =
            """
            {"event":"init"}
            {"event":"step_update","step_update":{"text_delta":"po"}}
            {"event":"step_update","step_update":{"text_delta":"ng"}}
            {"event":"result","result":{"status":"SUCCESS","response":"pong"}}
            """.trimIndent()
        assertEquals("pong" to "pong", decode(CliOutputFormat.AntigravityStreamJson, output))
        val refused = """{"event":"result","result":{"status":"INVALID","response":"","error":"invalid --effort"}}"""
        assertEquals("invalid --effort", assertFailsWith<AgentException> { decode(CliOutputFormat.AntigravityStreamJson, refused) }.message)
    }
}

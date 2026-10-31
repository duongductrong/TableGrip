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

    private fun events(format: CliOutputFormat, output: String): Pair<List<AgentEvent>, String> {
        val decoder = format.decoder("Agent")
        val events = output.chunked(7).flatMap(decoder::consume)
        return events to decoder.finish(0, stderr = "")
    }

    private fun List<AgentEvent>.reasoning() = filterIsInstance<AgentEvent.ReasoningDelta>().joinToString("") { it.text }

    private fun List<AgentEvent>.activities() = filterIsInstance<AgentEvent.Activity>().map { "${it.id} ${it.label} ${it.state}" }

    @Test
    fun `claude streams its thinking apart from the answer`() {
        val output =
            """
            {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"17*20=340, "}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plus 51."}}}
            {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"391"}}}
            {"type":"result","subtype":"success","is_error":false,"result":"391"}
            """.trimIndent()
        val (events, answer) = events(CliOutputFormat.ClaudeStreamJson, output)
        assertEquals("17*20=340, plus 51.", events.reasoning())
        assertEquals("391", answer)
    }

    @Test
    fun `tool calls in whole messages become activities, finished by their results`() {
        val output =
            """
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"schema.sql"}}]}}
            {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"..."}]}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"ls\nrm"}}]}}
            {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","is_error":true,"content":"denied"}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"Done"}
            """.trimIndent()
        val (events, _) = events(CliOutputFormat.ClaudeStreamJson, output)
        assertEquals(listOf("t1 Read · schema.sql Running", "t1 Read · schema.sql Done", "t2 Bash · ls Running", "t2 Bash · ls Failed"), events.activities())
    }

    @Test
    fun `cursor's thinking and tool calls`() {
        val output =
            """
            {"type":"thinking","subtype":"delta","text":"Looking at the schema","session_id":"s"}
            {"type":"thinking","subtype":"completed","session_id":"s"}
            {"type":"tool_call","subtype":"started","call_id":"c1","tool_call":{"readToolCall":{"args":{"path":"a.sql"}}}}
            {"type":"tool_call","subtype":"completed","call_id":"c1","tool_call":{"readToolCall":{"args":{"path":"a.sql"},"result":{"success":{"content":"x"}}}}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]},"timestamp_ms":1}
            {"type":"result","subtype":"success","is_error":false,"result":"ok"}
            """.trimIndent()
        val (events, answer) = events(CliOutputFormat.ClaudeStreamJson, output)
        assertEquals("Looking at the schema", events.reasoning())
        assertEquals(listOf("c1 read · a.sql Running", "c1 read · a.sql Done"), events.activities())
        assertEquals("ok", answer)
    }

    @Test
    fun `codex reasoning and commands`() {
        val output =
            """
            {"type":"item.completed","item":{"id":"item_0","type":"reasoning","text":"**Planning**"}}
            {"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"ls","status":"in_progress"}}
            {"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"ls","exit_code":0,"status":"completed"}}
            {"type":"item.started","item":{"id":"item_2","type":"command_execution","command":"cat x","status":"in_progress"}}
            {"type":"item.completed","item":{"id":"item_2","type":"command_execution","command":"cat x","exit_code":1,"status":"failed"}}
            {"type":"item.completed","item":{"id":"item_3","type":"reasoning","text":"**Answering**"}}
            {"type":"item.completed","item":{"id":"item_4","type":"agent_message","text":"pong"}}
            """.trimIndent()
        val (events, answer) = events(CliOutputFormat.CodexJson, output)
        assertEquals("**Planning**\n\n**Answering**", events.reasoning())
        assertEquals(listOf("item_1 Ran ls Running", "item_1 Ran ls Done", "item_2 Ran cat x Running", "item_2 Ran cat x Failed"), events.activities())
        assertEquals("pong", answer)
    }

    @Test
    fun `gemini tool calls`() {
        val output =
            """
            {"type":"tool_use","tool_name":"read_file","tool_id":"g1","parameters":{"path":"a.sql"}}
            {"type":"tool_result","tool_id":"g1","status":"success","output":"x"}
            {"type":"message","role":"assistant","content":"ok","delta":true}
            {"type":"result","status":"success"}
            """.trimIndent()
        val (events, _) = events(CliOutputFormat.GeminiStreamJson, output)
        assertEquals(listOf("g1 read_file · a.sql Running", "g1 read_file · a.sql Done"), events.activities())
    }

    /** Recorded from `agy --mode plan --output-format stream-json`. */
    @Test
    fun `antigravity streams its response steps and shows other steps as activities`() {
        val output =
            """
            {"event":"init","conversation_id":"c","init":{"cwd":"/tmp","tools":["ask_question"]}}
            {"event":"step_update","step_update":{"conversation_id":"c","step_index":0,"state":"DONE","step_type":"user_input"}}
            {"event":"step_update","step_update":{"conversation_id":"c","step_index":1,"state":"ACTIVE","step_type":"view_file"}}
            {"event":"step_update","step_update":{"conversation_id":"c","step_index":1,"state":"DONE","step_type":"view_file"}}
            {"event":"step_update","step_update":{"conversation_id":"c","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"**Answer:**\n1"}}
            {"event":"step_update","step_update":{"conversation_id":"c","step_index":2,"state":"DONE","step_type":"agent_response","text_delta":"7 times 23 is 391.\n","duration_seconds":8.25}}
            {"event":"result","result":{"conversation_id":"c","status":"SUCCESS","response":"**Answer:**\n17 times 23 is 391.\n"}}
            """.trimIndent()
        val (events, answer) = events(CliOutputFormat.AntigravityStreamJson, output)
        assertEquals("**Answer:**\n17 times 23 is 391.\n", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf("step-1 View file Running", "step-1 View file Done"), events.activities())
        assertEquals("**Answer:**\n17 times 23 is 391.", answer)
    }

    @Test
    fun `opencode parts become text, reasoning and activities`() {
        val output =
            """
            {"type":"step_start","timestamp":1,"sessionID":"s","part":{"type":"step-start"}}
            {"type":"reasoning","timestamp":2,"sessionID":"s","part":{"type":"reasoning","text":"Check the schema."}}
            {"type":"tool_use","timestamp":3,"sessionID":"s","part":{"type":"tool","callID":"call_1","tool":"read","state":{"status":"completed","title":"schema.sql","input":{"filePath":"schema.sql"}}}}
            {"type":"text","timestamp":4,"sessionID":"s","part":{"type":"text","text":"Two tables."}}
            {"type":"step_finish","timestamp":5,"sessionID":"s","part":{"type":"step-finish"}}
            """.trimIndent()
        val (events, answer) = events(CliOutputFormat.OpenCodeJson, output)
        assertEquals("Check the schema.", events.reasoning())
        assertEquals(listOf("call_1 read · schema.sql Done"), events.activities())
        assertEquals("Two tables.", answer)
    }

    /** Recorded: OpenCode exits 0 with its failure as an event. */
    @Test
    fun `opencode's error event fails the turn`() {
        val output =
            """{"type":"error","timestamp":1793428366854,"sessionID":"ses_e6f5","error":{"type":"provider.invalid-request","message":"Upstream request failed: This Go model trains on request data.","status":400}}"""
        assertEquals("Upstream request failed: This Go model trains on request data.", assertFailsWith<AgentException> { decode(CliOutputFormat.OpenCodeJson, output) }.message)
    }
}

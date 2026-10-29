package dev.datagrok.core.agent

import dev.datagrok.core.agent.cli.CliAgentAdapter
import dev.datagrok.core.agent.cli.CliAgentPreset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/** Runs the adapter against small shell scripts standing in for a CLI agent. */
@DisabledOnOs(OS.WINDOWS)
class CliAgentAdapterTest {
    private val dir = createTempDirectory("agent-test")

    private fun script(body: String): Path =
        dir.resolve("agent-${System.nanoTime()}").apply {
            writeText("#!/bin/sh\n$body\n")
            toFile().setExecutable(true)
        }

    private fun request(text: String) = AgentRequest(listOf(AgentMessage(AgentMessage.Role.User, text)), workingDirectory = dir.resolve("work"))

    @Test
    fun `streams stdout and completes with the whole answer`() = runBlocking {
        // The prompt is the last argument; no shell sits between us and the agent, so it arrives verbatim.
        val agent = CliAgentAdapter(CliAgentPreset.Hermes, script("for a; do last=\$a; done; printf 'you said: '; printf '%s' \"\$last\""))
        val events = agent.send(request("it's \$HOME `x`")).toList()
        assertEquals(AgentEvent.Thinking, events.first())
        assertEquals(AgentEvent.Completed("you said: it's \$HOME `x`"), events.last())
        assertEquals("you said: it's \$HOME `x`", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(Files.isDirectory(dir.resolve("work")))
    }

    @Test
    fun `a failed run reports the line that names the problem`() = runBlocking {
        val agent = CliAgentAdapter(CliAgentPreset.Hermes, script("echo '#####' >&2; printf '\\033[91mError: \\033[0mnot logged in\\n' >&2; echo 'bye' >&2; exit 3"))
        val error = assertFailsWith<AgentException> { agent.send(request("hi")).toList() }
        assertEquals("Error: not logged in", error.message)
    }

    @Test
    fun `cancelling the collector kills the agent`() = runBlocking {
        val agent = CliAgentAdapter(CliAgentPreset.Hermes, script("printf 'started'; sleep 30; echo done"))
        withTimeout(10_000) { agent.send(request("hi")).first { it is AgentEvent.TextDelta } }
    }

    @Test
    fun `earlier turns travel in the prompt`() {
        val request =
            AgentRequest(
                listOf(
                    AgentMessage(AgentMessage.Role.User, "count users"),
                    AgentMessage(AgentMessage.Role.Assistant, "SELECT count(*) FROM users"),
                    AgentMessage(AgentMessage.Role.User, "only active ones"),
                ),
                system = "Be brief.",
                reminder = "Don't run anything.",
            )
        assertEquals(
            "Be brief.\n\nConversation so far:\nUser: count users\n\nAssistant: SELECT count(*) FROM users\n\n" +
                "User: <system-reminder>\nDon't run anything.\n</system-reminder>\n\nonly active ones",
            request.toPrompt(),
        )
    }
}

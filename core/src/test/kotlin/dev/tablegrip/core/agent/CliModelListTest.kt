package dev.tablegrip.core.agent

import dev.tablegrip.core.agent.cli.CliAgentAdapter
import dev.tablegrip.core.agent.cli.CliAgentPreset
import dev.tablegrip.core.agent.cli.CliModelList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/** Model lists as each CLI prints them, and how a picked model reaches the command line. */
class CliModelListTest {
    @Test
    fun `cursor lists id and name under a heading`() {
        val output = "Available models\n\nauto - Auto (default)\ngpt-5.3-codex - Codex 5.3\n\nTip: use --model <id>\n"
        assertEquals(listOf(AgentModel("auto", "Auto"), AgentModel("gpt-5.3-codex", "Codex 5.3")), CliModelList.Cursor.parse(output))
    }

    @Test
    fun `grok marks the default with a star`() {
        val output = "You are not authenticated.\n\nDefault model: grok-4.6\n\nAvailable models:\n  * grok-4.6 (default)\n  - grok-4.5\n"
        assertEquals(listOf("grok-4.6", "grok-4.5"), CliModelList.Grok.parse(output).map { it.id })
    }

    @Test
    fun `opencode prints provider and model per line`() {
        val output = "\u001B[2mgitlab/duo-chat-opus-5\u001B[0m\nkimi-code-plan-cn/k3\nsome warning line\n"
        assertEquals(listOf("gitlab/duo-chat-opus-5", "kimi-code-plan-cn/k3"), CliModelList.OpenCode.parse(output).map { it.id })
    }

    @Test
    fun `antigravity separates id and name with a tab`() {
        val output = "Fetching available models...\ngemini-4-argon-high\tGemini 4 Argon (High)\nclaude-opus-5-5-low\tClaude Opus 5.5 (Low)\n"
        assertEquals(
            listOf(AgentModel("gemini-4-argon-high", "Gemini 4 Argon (High)"), AgentModel("claude-opus-5-5-low", "Claude Opus 5.5 (Low)")),
            CliModelList.Antigravity.parse(output),
        )
    }

    @Test
    fun `codex keeps only the models its own picker lists`() {
        val output =
            """{"models":[{"slug":"gpt-reserve","display_name":"GPT-Reserve","visibility":"hide"},""" +
                """{"slug":"gpt-5.6-terra","display_name":"GPT-5.6-Terra","visibility":"list"},{"slug":"gpt-6"}]}"""
        assertEquals(listOf(AgentModel("gpt-5.6-terra", "GPT-5.6-Terra"), AgentModel("gpt-6")), CliModelList.Codex.parse(output))
    }

    @Test
    fun `unreadable output lists nothing`() {
        assertEquals(emptyList(), CliModelList.Codex.parse("error: not logged in"))
        assertEquals(emptyList(), CliModelList.Antigravity.parse("Not signed in. Run agy login."))
    }

    @Test
    fun `a picked model sits before the prompt and a blank one adds nothing`() {
        val plain = CliAgentPreset.Codex.arguments("hi")
        assertFalse("--model" in plain)
        assertEquals(plain, CliAgentPreset.Codex.arguments("hi", "  "))
        val chosen = CliAgentPreset.Codex.arguments("hi", "gpt-6")
        assertEquals(listOf("--model", "gpt-6", "hi"), chosen.takeLast(3))
        assertEquals(plain.size + 2, chosen.size)
    }

    @Test
    fun `agents without a model flag ignore a picked model`() {
        assertFalse(CliAgentPreset.Hermes.choosesModel)
        assertEquals(CliAgentPreset.Hermes.arguments("hi"), CliAgentPreset.Hermes.arguments("hi", "x"))
        assertTrue(CliAgentPreset.Gemini.choosesModel)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the adapter runs the CLI's list command`() = runBlocking {
        val script = createTempDirectory("agent-models").resolve("grok").apply {
            writeText("#!/bin/sh\n[ \"$1\" = models ] || exit 1\nprintf 'Available models:\\n  * grok-4.6 (default)\\n  - grok-4.5\\n'\n")
            toFile().setExecutable(true)
        }
        assertEquals(listOf("grok-4.6", "grok-4.5"), CliAgentAdapter(CliAgentPreset.Grok, script).models().map { it.id })
        // Hermes takes no model, so it isn't asked for a list.
        assertEquals(emptyList(), CliAgentAdapter(CliAgentPreset.Hermes, script).models())
    }

    @Test
    fun `withModel leaves an agent that can't choose untouched`() {
        val hermes = CliAgentAdapter(CliAgentPreset.Hermes, createTempDirectory("agent-models"))
        assertTrue(hermes.withModel("x") === hermes)
    }
}

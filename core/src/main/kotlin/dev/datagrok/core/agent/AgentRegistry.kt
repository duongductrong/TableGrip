package dev.datagrok.core.agent

import dev.datagrok.core.agent.cli.CliAgentSource
import dev.datagrok.core.agent.provider.AgentMode
import dev.datagrok.core.agent.provider.ProviderAgentSource
import dev.datagrok.core.agent.provider.ProviderConfig
import dev.datagrok.core.agent.provider.ProviderProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/** Every agent Datagrok can offer, gathered from its sources. A new transport is a new [AgentSource] here, nothing more. */
class AgentRegistry(private val sources: List<AgentSource>) {
    /** Asks every source at once; one that fails is logged and left out rather than hiding the others. */
    suspend fun discover(rescan: Boolean = false): List<AgentAdapter> = coroutineScope {
        sources
            .map { source ->
                async {
                    runCatching { source.discover(rescan) }
                        .onFailure { log.warn("Agent source ${source::class.simpleName} failed", it) }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.descriptor.id }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AgentRegistry::class.java)

        /**
         * The CLIs on this machine or the provider in settings, whichever [mode] picks at discovery time. The defaults
         * are the CLI-only registry Datagrok had before the choice existed.
         */
        fun default(
            mode: () -> AgentMode = { AgentMode.Cli },
            provider: () -> ProviderConfig = { ProviderConfig.of(ProviderProtocol.Anthropic, "", "") },
            apiKey: (ProviderProtocol) -> String? = { null },
        ): AgentRegistry =
            AgentRegistry(
                listOf(
                    ModeSource(AgentMode.Cli, mode, CliAgentSource()),
                    ModeSource(AgentMode.Provider, mode, ProviderAgentSource(provider, apiKey)),
                ),
            )
    }
}

/** [source] while [mode] is [active], nothing otherwise; the switch takes effect on the next discovery. */
private class ModeSource(private val active: AgentMode, private val mode: () -> AgentMode, private val source: AgentSource) : AgentSource {
    override suspend fun discover(rescan: Boolean): List<AgentAdapter> = if (mode() == active) source.discover(rescan) else emptyList()
}

/** Flattens a conversation into one prompt, for transports that take a single string and keep no session of their own. */
fun AgentRequest.toPrompt(): String {
    val latest = messages.lastOrNull()?.text.orEmpty()
    val earlier = messages.dropLast(1)
    return buildString {
        system?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
        if (earlier.isNotEmpty()) {
            append("Conversation so far:\n")
            earlier.forEach { message ->
                append(if (message.role == AgentMessage.Role.User) "User: " else "Assistant: ")
                append(message.text.trim()).append("\n\n")
            }
            append("User: ")
        }
        reminder?.takeIf { it.isNotBlank() }?.let { append("<system-reminder>\n").append(it.trim()).append("\n</system-reminder>\n\n") }
        append(latest.trim())
    }
}

package dev.tablegrip.core.agent.provider

import dev.tablegrip.core.i18n.tr

/**
 * Where the chat panel's agents come from. [Cli] runs the agent CLIs installed on this machine and is the default, so
 * a settings file written before this choice existed keeps working as it did; [Provider] calls an HTTP API directly.
 */
enum class AgentMode(val label: String) {
    Cli(tr("Command-line agents")),
    Provider(tr("API provider"));

    companion object {
        /** Unknown or blank names fall back to [Cli]: a bad edit must not leave the panel with no agent. */
        fun of(name: String): AgentMode = entries.firstOrNull { it.name == name } ?: Cli
    }
}

/**
 * The wire protocols a provider can speak. [OpenAi] is the Chat Completions shape, which most gateways and local servers
 * (OpenRouter, LiteLLM, Ollama, LM Studio, vLLM) also serve, so it covers far more than OpenAI itself.
 */
enum class ProviderProtocol(
    val title: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Read when no key is saved in the keychain, for users who already export one. */
    val keyVariable: String,
) {
    Anthropic("Anthropic", "https://api.anthropic.com", "claude-opus-5-5", "ANTHROPIC_API_KEY"),
    OpenAi(tr("OpenAI-compatible"), "https://api.openai.com/v1", "gpt-5", "OPENAI_API_KEY");

    /** The keychain entry holding this protocol's API key; one per protocol, so switching back and forth keeps both. */
    val secretKey: String
        get() = "agent-provider:${name.lowercase()}"

    companion object {
        fun of(name: String): ProviderProtocol = entries.firstOrNull { it.name == name } ?: Anthropic
    }
}

/** A provider as settings describe it, with blanks already resolved to the protocol's defaults. */
data class ProviderConfig(val protocol: ProviderProtocol, val baseUrl: String, val model: String) {
    companion object {
        fun of(protocol: ProviderProtocol, baseUrl: String, model: String) =
            ProviderConfig(
                protocol,
                baseUrl.trim().ifEmpty { protocol.defaultBaseUrl }.trimEnd('/'),
                model.trim().ifEmpty { protocol.defaultModel },
            )
    }
}

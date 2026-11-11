package dev.tablegrip.core.agent

import dev.tablegrip.core.connection.AgentWrites
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig

/** What an agent connected from outside the app wants to do with a connection. */
enum class AgentCapability {
    /** Names, types, keys, indexes, DDL, plans, the user's saved SQL and history: no values from the data. */
    Schema,

    /** Anything that returns values: queries, samples, keys, documents. */
    Data,

    /** INSERT, UPDATE, DELETE and their key-value and document equivalents. */
    WriteData,

    /** CREATE, ALTER, DROP and the like. */
    WriteSchema,
}

sealed interface AgentDecision {
    data object Allowed : AgentDecision

    /** [reason] is English and addressed to the agent, which relays it to the user. */
    data class Denied(val reason: String) : AgentDecision
}

/**
 * The rules for agents outside the app (MCP clients), in one place. They add to, and never loosen, what the in-app
 * agent already follows: [AiAccess] decides what may be read, [AgentWrites] what may be changed, and read-only
 * connections are never written to.
 */
data class AgentPolicy(
    /** Settings → MCP: nothing at all is served while this is off. */
    val enabled: Boolean,
    /** The app-wide [AiAccess] for connections that don't choose their own. */
    val defaultAccess: AiAccess,
    /** Refuses every write, whatever the connections allow (`tablegrip mcp --read-only`). */
    val forceReadOnly: Boolean = false,
    /** Only these connections, by id or name; null means all (`tablegrip mcp --connections …`). */
    val only: Set<String>? = null,
) {
    fun access(config: ConnectionConfig): AiAccess = AiAccess.effective(defaultAccess, config)

    /** Whether the connection is listed at all. */
    fun visible(config: ConnectionConfig): Boolean =
        enabled && access(config).sharesSchema && (only == null || config.id in only || config.name in only)

    /** What agents may change on the connection, after read-only connections, data files and flags. */
    fun writes(config: ConnectionConfig): AgentWrites =
        if (forceReadOnly || config.readOnly || config.type.isDataFile) AgentWrites.None else config.agentWrites

    fun decide(capability: AgentCapability, config: ConnectionConfig): AgentDecision {
        if (!enabled) return AgentDecision.Denied(Disabled)
        if (!visible(config)) return AgentDecision.Denied("There is no connection '${config.name}' available to agents.")
        val name = config.name
        return when (capability) {
            AgentCapability.Schema -> AgentDecision.Allowed
            AgentCapability.Data ->
                if (access(config).sharesData) AgentDecision.Allowed
                else AgentDecision.Denied(
                    "'$name' shares its schema with agents but not its data. The user can allow it in TableGrip: " +
                        "edit the connection, Options › AI agents › Schema and sample data.",
                )
            AgentCapability.WriteData, AgentCapability.WriteSchema -> decideWrite(capability, config)
        }
    }

    private fun decideWrite(capability: AgentCapability, config: ConnectionConfig): AgentDecision {
        val name = config.name
        if (!access(config).sharesData) return decide(AgentCapability.Data, config)
        return when {
            config.readOnly -> AgentDecision.Denied("'$name' is a read-only connection.")
            config.type.isDataFile -> AgentDecision.Denied("Agents can't change data files; '$name' is read-only to them.")
            forceReadOnly -> AgentDecision.Denied("This MCP server was started with --read-only.")
            capability == AgentCapability.WriteData && !config.agentWrites.allowsData ||
                capability == AgentCapability.WriteSchema && !config.agentWrites.allowsSchema -> {
                val needed = if (capability == AgentCapability.WriteSchema) "Data and schema" else "Data"
                AgentDecision.Denied(
                    "'$name' doesn't let agents ${if (capability == AgentCapability.WriteSchema) "change its schema" else "change data"}. " +
                        "The user can allow it in TableGrip: edit the connection, Options › Agent writes › $needed.",
                )
            }
            else -> AgentDecision.Allowed
        }
    }

    companion object {
        const val Disabled =
            "TableGrip isn't sharing its connections with AI apps. The user can turn it on in TableGrip › Settings › MCP."
    }
}

package dev.tablegrip.mcp

import dev.tablegrip.core.connection.Capability
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.driver.QueryResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One client's connection to the server: who it is, what it supports, and the shared [workspace]. */
class McpSession(val workspace: Workspace, private val peer: JsonRpcPeer?) {
    /** The client's name from `initialize`, e.g. `claude-ai` or `cursor`. */
    @Volatile
    var client: String = "unknown"

    @Volatile
    var clientCapabilities: JsonObject = JsonObject(emptyMap())

    val canAsk: Boolean
        get() = peer != null && clientCapabilities["elicitation"] != null

    /** Whether a change on [config] must be confirmed by the user first. */
    fun needsConfirmation(config: ConnectionConfig, danger: String?): Boolean =
        config.confirmWrites || config.environment == Environment.Production || danger != null

    /**
     * Asks the user, through the client, to approve [action] on [config]. Throws [ToolException] when they decline or
     * when the client can't ask: a write that needs approval never runs without it.
     */
    suspend fun confirm(config: ConnectionConfig, action: String, danger: String?) {
        if (!canAsk) {
            throw ToolException(
                "This change on '${config.name}' needs the user's approval" +
                    (danger?.let { " ($it)" } ?: if (config.environment == Environment.Production) " (production connection)" else "") +
                    ", but this app can't ask for it (it doesn't support MCP elicitation). Show the user the statement so they can run it in TableGrip.",
            )
        }
        val message = buildString {
            append("Run this on ${config.name}")
            if (config.environment != Environment.None) append(" (${config.environment.name})")
            append("?\n\n").append(action.take(4_000))
            danger?.let { append("\n\nWarning: ").append(it) }
        }
        val answer =
            peer!!.request(
                "elicitation/create",
                buildJsonObject {
                    put("message", message)
                    putJsonObject("requestedSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("confirm") {
                                put("type", "boolean")
                                put("title", "Run it")
                                put("description", "Run the change on ${config.name}")
                                put("default", true)
                            }
                        }
                    }
                },
            )
        val accepted = answer.text("action") == "accept" && (answer.obj("content")["confirm"] as? JsonPrimitive)?.booleanOrNull != false
        if (!accepted) throw ToolException("The user declined the change on '${config.name}'. It did not run.")
    }

    /** Runs one statement the guard accepted, in a transaction of its own where the engine has transactions. */
    suspend fun runStatement(live: Workspace.Live, config: ConnectionConfig, sql: String, read: Boolean, commit: Boolean): QueryResult {
        val settings = workspace.settings()
        val maxRows = workspace.maxRows(settings) + 1
        val timeout = workspace.timeoutSeconds(settings)
        val started = System.currentTimeMillis()
        val session = live.session()
        return try {
            val result =
                if (Capability.Transactions in config.type.capabilities) session.isolated(readOnly = read, commit = commit) { execute(sql, maxRows, timeout) }
                else session.execute(sql, maxRows, timeout)
            workspace.remember(config, sql, started, null, client)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            workspace.remember(config, sql, started, "Cancelled", client)
            throw e
        } catch (e: Exception) {
            workspace.remember(config, sql, started, e.message ?: e.toString(), client)
            throw ToolException(errorText(e))
        }
    }

    companion object {
        fun errorText(e: Throwable): String {
            val error = dev.tablegrip.core.driver.QueryError.of(e)
            return buildString {
                append(error.message)
                error.sqlState?.let { append(" (SQLSTATE ").append(it).append(')') }
                error.position?.let { append(" at character ").append(it + 1) }
            }
        }
    }
}

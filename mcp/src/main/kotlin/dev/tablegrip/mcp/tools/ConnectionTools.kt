package dev.tablegrip.mcp.tools

import dev.tablegrip.core.agent.AgentCapability
import dev.tablegrip.core.agent.AgentPolicy
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.driver.DocumentConnection
import dev.tablegrip.core.driver.KeyValueConnection
import dev.tablegrip.core.driver.RelationalConnection
import dev.tablegrip.mcp.Tool
import dev.tablegrip.mcp.ToolException
import dev.tablegrip.mcp.params
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which tools fit a connection, so the agent doesn't have to guess from the engine's name. */
internal fun toolsFor(config: ConnectionConfig): String =
    when {
        config.type.paradigm == Paradigm.KeyValue -> "kv_*"
        config.type.paradigm == Paradigm.Document -> "doc_*"
        else -> "SQL (list_tables, describe_table, query…)"
    }

internal fun describeConnection(config: ConnectionConfig, policy: AgentPolicy): JsonObject =
    buildJsonObject {
        put("id", config.id)
        put("name", config.name)
        put("type", config.type.displayName)
        put("kind", if (config.type.isDataFile) "data file" else config.type.paradigm.name.lowercase())
        put("tools", toolsFor(config))
        if (config.type.isDataFile || config.type.defaultPort == null) {
            if (config.database.isNotBlank()) put("file", config.database)
        } else {
            put("host", config.host)
            config.port?.let { put("port", it) }
            if (config.database.isNotBlank()) put("database", config.database)
        }
        if (config.group.isNotBlank()) put("group", config.group)
        put("environment", config.environment.name)
        put("readOnly", config.readOnly)
        put("dataAccess", if (policy.access(config).sharesData) "schema and data" else "schema only")
        put(
            "writes",
            policy.writes(config).let { if (!it.allowsData) "none" else if (it.allowsSchema) "data and schema" else "data" },
        )
    }

internal fun connectionTools(): List<Tool> =
    listOf(
        Tool(
            name = "list_connections",
            title = "List connections",
            description =
                "Lists the database connections saved in TableGrip that the user shares with agents, with what each allows: " +
                    "'dataAccess' says whether values may be read or only the schema, 'writes' whether changes are allowed. " +
                    "Start here; every other tool takes a connection's id or name.",
            readOnly = true,
            input = params { },
        ) {
            val workspace = session.workspace
            val policy = workspace.policy()
            if (!policy.enabled) throw ToolException(AgentPolicy.Disabled)
            val visible = workspace.visible()
            buildJsonObject {
                put("connections", buildJsonArray { visible.forEach { add(describeConnection(it, policy)) } })
                if (visible.isEmpty()) {
                    put(
                        "note",
                        "No connection is shared with agents. In TableGrip, a connection's Options › AI agents must not be Off" +
                            (workspace.options.connections?.let { " (and this server only serves: ${it.joinToString()})" } ?: "") + ".",
                    )
                }
            }
        },
        Tool(
            name = "connection_info",
            title = "Connection info",
            description = "Connects (if not connected yet) and describes the server: its version and what the engine supports.",
            readOnly = true,
            input = params { connection() },
        ) {
            val workspace = session.workspace
            val config = workspace.resolve(string("connection"))
            workspace.require(AgentCapability.Schema, config)
            workspace.use(config) { live ->
                buildJsonObject {
                    put("connection", describeConnection(config, workspace.policy()))
                    when (val connection = live.connection) {
                        is RelationalConnection -> {
                            put("engine", config.type.displayName)
                            connection.serverMajorVersion()?.let { put("serverMajorVersion", it) }
                            put("explain", dev.tablegrip.core.connection.Capability.Explain in config.type.capabilities)
                            put("transactions", dev.tablegrip.core.connection.Capability.Transactions in config.type.capabilities)
                        }
                        is KeyValueConnection -> put("server", connection.server())
                        is DocumentConnection -> put("server", connection.server())
                        else -> Unit
                    }
                }
            }
        },
    )

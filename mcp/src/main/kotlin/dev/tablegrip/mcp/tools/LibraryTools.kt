package dev.tablegrip.mcp.tools

import dev.tablegrip.core.agent.AgentPolicy
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.queries.SavedQuery
import dev.tablegrip.mcp.ResultEncoder
import dev.tablegrip.mcp.Tool
import dev.tablegrip.mcp.ToolCall
import dev.tablegrip.mcp.ToolException
import dev.tablegrip.mcp.Workspace
import dev.tablegrip.mcp.params
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A saved query's path under the library, as tools and resources name it. */
internal fun Workspace.pathOf(query: SavedQuery): String = query.file.relativeTo(library.root).invariantSeparatorsPathString

/** The saved queries an agent may see: those of no connection, or of one it can see. */
internal fun Workspace.savedQueries(): List<SavedQuery> {
    val visible = visible()
    return library.list().filter { query -> query.connection == null || visible.any { it.id == query.connection || it.name == query.connection } }
}

internal fun Workspace.savedQuery(path: String): SavedQuery {
    val query = savedQueries().firstOrNull { pathOf(it) == path.trim().trimStart('/') } ?: throw ToolException("There is no saved query '$path'. Call list_saved_queries.")
    return library.read(query.file)
}

internal fun libraryTools(): List<Tool> =
    listOf(
        Tool(
            name = "list_saved_queries",
            title = "List saved queries",
            description = "Lists the SQL the user saved in TableGrip, with folders and the connection each belongs to.",
            readOnly = true,
            input = params { string("connection", "Only those saved for this connection (id or name).") },
        ) {
            val workspace = enabled()
            val only = stringOrNull("connection")?.let(workspace::resolve)
            val queries = workspace.savedQueries().filter { only == null || it.connection == only.id || it.connection == only.name }
            buildJsonObject {
                put(
                    "queries",
                    buildJsonArray {
                        queries.forEach { query ->
                            add(buildJsonObject {
                                put("path", workspace.pathOf(query))
                                put("name", query.name)
                                if (query.folder.isNotEmpty()) put("folder", query.folder)
                                query.connection?.let { put("connection", it) }
                            })
                        }
                    },
                )
            }
        },
        Tool(
            name = "get_saved_query",
            title = "Read a saved query",
            description = "Returns the SQL of a saved query, by the path list_saved_queries gives.",
            readOnly = true,
            input = params { string("path", "The query's path.", required = true) },
        ) {
            val query = enabled().savedQuery(string("path"))
            buildJsonObject {
                put("name", query.name)
                query.connection?.let { put("connection", it) }
                put("sql", query.sql)
            }
        },
        Tool(
            name = "save_query",
            title = "Save a query",
            description =
                "Saves SQL to the user's saved queries in TableGrip (a .sql file, in the 'mcp' folder unless another is given), " +
                    "so they can open it there. Doesn't run it. Won't replace an existing query unless 'overwrite' is true.",
            readOnly = false,
            destructive = false,
            input = params {
                string("name", "A short name for the query.", required = true)
                string("sql", "The SQL.", required = true)
                string("folder", "Folder, e.g. reports/monthly (default: mcp).")
                string("connection", "The connection it belongs to (id or name).")
                boolean("overwrite", "Replace a query with the same name.")
            },
        ) {
            val workspace = enabled()
            val config: ConnectionConfig? = stringOrNull("connection")?.let(workspace::resolve)
            val folder = stringOrNull("folder")?.takeIf { it.isNotBlank() } ?: "mcp"
            val name = string("name")
            if (workspace.library.fileFor(name, folder).exists() && !boolean("overwrite", false)) {
                throw ToolException("A query named '$name' already exists in '$folder'. Pick another name, or pass overwrite: true.")
            }
            val saved = workspace.library.save(name, folder, string("sql"), config?.id)
            buildJsonObject { put("saved", workspace.pathOf(saved)) }
        },
        Tool(
            name = "query_history",
            title = "Query history",
            description = "The statements run recently in TableGrip (by the user and by agents), newest first, with errors.",
            readOnly = true,
            input = params {
                string("connection", "Only this connection's (id or name).")
                integer("limit", "Entries at most (default 30).", minimum = 1, maximum = 200)
            },
        ) {
            val workspace = enabled()
            val only = stringOrNull("connection")?.let(workspace::resolve)
            val visible = workspace.visible().associateBy { it.id }
            val entries = workspace.history.recent(only?.id, limit = 1_000).filter { it.connectionId in visible }.take(int("limit", 30, 1..200))
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { entry ->
                            add(buildJsonObject {
                                put("connection", visible[entry.connectionId]?.name)
                                put("sql", ResultEncoder.clip(entry.sql, 4_000))
                                put("at", java.time.Instant.ofEpochMilli(entry.executedAt).toString())
                                put("elapsedMs", entry.elapsedMillis)
                                entry.error?.let { put("error", ResultEncoder.clip(it, 500)) }
                                entry.source?.let { put("by", it) }
                            })
                        }
                    },
                )
            }
        },
    )

private fun ToolCall.enabled(): Workspace {
    val workspace = session.workspace
    if (!workspace.policy().enabled) throw ToolException(AgentPolicy.Disabled)
    return workspace
}

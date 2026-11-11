package dev.tablegrip.app.agent

import dev.tablegrip.core.agent.Mention
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.console.RunOutcome
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.assist.SchemaDigest
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ClientApi
import org.slf4j.LoggerFactory

/**
 * What the agent is told about the database, so it writes queries against tables that exist instead of guessing names
 * from the conversation. Read from the live connection once per connection and kept until the chat is cleared.
 */
internal class AgentContext(private val app: AppState) {
    private val schemas = mutableMapOf<String, String>()

    /** Drops a cached schema, e.g. after DDL; null drops them all. */
    fun forget(connectionId: String? = null) {
        if (connectionId == null) schemas.clear() else schemas.remove(connectionId)
    }

    /**
     * The framing for a turn: rules first, then the connection, its schema, and what the user has open. An open console
     * the message already [mentioned] in full isn't repeated.
     */
    suspend fun systemPrompt(mentioned: Collection<Mention> = emptyList()): String = buildString {
        append(Rules)
        val connection = app.currentConnection
        if (connection == null) {
            append("\n\nNo connection is selected in TableGrip.")
            return@buildString
        }
        val access = app.aiAccess(connection)
        if (!access.sharesSchema) {
            append("\n\nThe user has chosen not to share the selected connection (${connection.type.displayName}) with you: you know nothing about its schema or data. ")
            append("Answer in general terms, and don't ask TableGrip to run queries.")
            return@buildString
        }
        append("\n\n# Connection\n")
        append("Name: ${connection.name}\nEngine: ${connection.type.displayName} (write SQL in this dialect)\n")
        connection.database.takeIf { it.isNotBlank() }?.let { append("Database: $it\n") }
        if (connection.readOnly) append("The connection is read-only.\n")
        append("\n# Schema\n")
        append(schema(connection))
        when (val tab = app.workspace.selected) {
            is TableDataTab ->
                append("\n\n# Open in TableGrip\nThe user is viewing table ${listOfNotNull(tab.table.namespace.catalog, tab.table.namespace.schema, tab.table.name).joinToString(".")}.")
            is ConsoleTab -> tab.editor.text.toString().trim().takeIf { text -> text.isNotEmpty() && mentioned.none { it is Mention.Sql && it.sql == text } }?.let {
                append("\n\n# Open in TableGrip\nThe user's open console contains:\n```sql\n${it.take(ConsoleBudget)}\n```")
            }
            else -> Unit
        }
    }

    private suspend fun schema(config: ConnectionConfig): String {
        schemas[config.id]?.let { return it }
        if (config.type.api != ClientApi.Relational) {
            return "${config.type.displayName} has no SQL schema; answer in its own command language."
        }
        val text =
            runCatching { describe(config) }
                .onFailure { log.debug("No schema for the agent", it) }
                .getOrElse { "Couldn't read the schema (${it.message}). Ask the user for table names rather than guessing them." }
        schemas[config.id] = text
        return text
    }

    /** Tables and columns of the default namespace in a compact `table(column type, …)` form, within a budget. */
    private suspend fun describe(config: ConnectionConfig): String = SchemaDigest.read(app.databases.database(config), config.database, SchemaBudget)

    companion object {
        private val log = LoggerFactory.getLogger(AgentContext::class.java)
        private const val SchemaBudget = 40_000
        private const val ConsoleBudget = 4_000
        const val ResultRows = 50
        private const val CellBudget = 200

        private val Rules =
            """
            You are the assistant built into TableGrip, a desktop database client, talking to the user in its chat panel.

            # Rules
            - You can't execute anything. Don't use tools, shell commands, files, scripts or database clients of your own, even if some seem available. TableGrip runs queries itself, and only after the user allows it in this chat.
            - Use only the tables and columns listed under Schema. Never invent or guess a name; if what the user wants isn't there, say so, or look it up with a run request.
            - To suggest a query, put it in a ```sql block. The user can open it in a console or run it from the chat.
            - When you need data to answer (which table holds something, distinct values, counts), ask TableGrip to run one read-only query: put it in a ```sql run block and end your message there. The user is asked for permission; if they allow it, the result comes back in the next message. Never put statements that change data or schema in a run block: suggest those in a ```sql block for the user to run.
            - Reply in the user's language, concisely, in Markdown.
            """.trimIndent()

        /** Restated before every message, so a long conversation can't bury the rules. */
        val Reminder =
            "Answer in the chat only: don't run tools, commands or queries yourself. Use only tables and columns from the Schema section. " +
                "To see data, end your message with one read-only query in a ```sql run block and wait for its result."

        /** A run's outcome as the agent reads it: result sets as pipe tables, capped, and any errors. */
        fun describe(sql: String, outcome: RunOutcome): String = buildString {
            append("TableGrip ran your query with the user's permission:\n```sql\n${sql.trim()}\n```\n")
            outcome.log.filter { it.isError }.forEach { append("Error: ${it.text}\n") }
            outcome.results.forEach { view ->
                val rows = view.rows
                append("\nResult: ${rows.rows.size}${if (rows.truncated) "+" else ""} row(s)\n")
                append(rows.columns.joinToString(" | ") { it.name }).append('\n')
                rows.rows.take(ResultRows).forEach { row ->
                    append(row.joinToString(" | ") { value -> value?.replace('\n', ' ')?.take(CellBudget) ?: "NULL" }).append('\n')
                }
                if (rows.rows.size > ResultRows) append("(${rows.rows.size - ResultRows} more rows not shown)\n")
            }
            if (outcome.results.isEmpty() && outcome.log.none { it.isError }) append("It returned no result set.\n")
        }.trimEnd()
    }
}

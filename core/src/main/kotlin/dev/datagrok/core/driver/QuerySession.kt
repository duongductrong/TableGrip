package dev.datagrok.core.driver

import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A dedicated connection for one SQL console, so session state (transactions, `SET` variables, temp tables) stays with
 * that console and long queries don't block the explorer.
 */
class QuerySession internal constructor(private val connection: Connection) : AutoCloseable {

    /** Executes one statement. Cancel the calling coroutine to cancel the query on the server. */
    suspend fun execute(sql: String, maxRows: Int = 1_000, timeoutSeconds: Int = 0): QueryResult =
        connection.withStatement(timeoutSeconds) { it.executeForResult(sql, maxRows) }

    suspend fun isValid(): Boolean = withContext(Dispatchers.IO) { runCatching { connection.isValid(2) }.getOrDefault(false) }

    override fun close() = connection.close()
}

package dev.datagrok.core.driver

import dev.datagrok.core.sql.SqlClassifier
import dev.datagrok.core.sql.StatementKind
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown before a statement reaches the server when the connection is read-only. */
class ReadOnlyViolationException(verb: String) : SQLException("This connection is read-only: $verb is not allowed.")

/**
 * A dedicated connection for one SQL console, so session state (transactions, `SET` variables, temp tables) stays with
 * that console and long queries don't block the explorer.
 */
class QuerySession internal constructor(
    private val connection: Connection,
    private val classifier: SqlClassifier,
    val readOnly: Boolean,
) : AutoCloseable {

    /** False in manual-commit mode, where changes stay pending until [commit] or [rollback]. */
    var autoCommit: Boolean = true
        private set

    /** Statements run since the last commit/rollback in manual mode; > 0 means there may be uncommitted work. */
    var uncommittedStatements: Int = 0
        private set

    /** Executes one statement. Cancel the calling coroutine to cancel the query on the server. */
    suspend fun execute(sql: String, maxRows: Int = 1_000, timeoutSeconds: Int = 0): QueryResult {
        val info = classifier.classify(sql)
        if (readOnly && info.changesData) throw ReadOnlyViolationException(info.verb)
        val result = connection.withStatement(timeoutSeconds) { it.executeForResult(sql, maxRows) }
        when {
            info.kind == StatementKind.Transaction && info.verb in setOf("COMMIT", "END", "ROLLBACK") -> uncommittedStatements = 0
            !autoCommit && info.kind != StatementKind.Read -> uncommittedStatements++
        }
        return result
    }

    /** Switching back to auto-commit commits pending work, as JDBC specifies. */
    suspend fun setAutoCommit(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            connection.autoCommit = enabled
            autoCommit = enabled
            uncommittedStatements = 0
        }

    suspend fun commit() =
        withContext(Dispatchers.IO) {
            if (!connection.autoCommit) connection.commit()
            uncommittedStatements = 0
        }

    suspend fun rollback() =
        withContext(Dispatchers.IO) {
            if (!connection.autoCommit) connection.rollback()
            uncommittedStatements = 0
        }

    suspend fun isValid(): Boolean = withContext(Dispatchers.IO) { runCatching { connection.isValid(2) }.getOrDefault(false) }

    /** Rolls back anything uncommitted, then closes. */
    override fun close() {
        runCatching { if (!connection.autoCommit) connection.rollback() }
        connection.close()
    }
}

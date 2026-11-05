package dev.tablegrip.core.activity

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.driver.ReadOnlyViolationException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionState(val label: String) {
    Active("active"),
    /** Waiting on a lock held by another session. */
    Blocked("blocked"),
    IdleInTransaction("idle in transaction"),
    Idle("idle"),
    Other("other"),
}

/** One server session, as the activity monitor lists it. */
data class SessionActivity(
    /** The server's id for it: the backend pid on PostgreSQL. */
    val id: String,
    val user: String?,
    val database: String?,
    val client: String?,
    val application: String?,
    val state: SessionState,
    /** What it's waiting for, e.g. `Lock: transactionid`; null when it isn't. */
    val wait: String?,
    /** The running query, or the last one for idle sessions. */
    val query: String?,
    val queryStart: Instant?,
    val transactionStart: Instant?,
    /** Sessions holding locks this one waits for. */
    val blockedBy: List<String>,
    /** The monitor's own session, which can't be cancelled or terminated from the monitor. */
    val own: Boolean,
) {
    /** How long the current query (or, when idle in a transaction, the transaction) has run at [now]. */
    fun runningFor(now: Instant): java.time.Duration? {
        val since = if (state == SessionState.IdleInTransaction) transactionStart else queryStart.takeIf { state != SessionState.Idle }
        return since?.let { java.time.Duration.between(it, now).coerceAtLeast(java.time.Duration.ZERO) }
    }
}

/** What the monitor read: the sessions and the server's clock, so durations don't depend on the local one. */
data class ActivitySnapshot(val sessions: List<SessionActivity>, val serverTime: Instant)

/** Whether an engine has an activity monitor, and why not. */
sealed interface ActivitySupport {
    data object Supported : ActivitySupport

    /** A server engine the monitor doesn't read yet. */
    data class NotYet(val reason: String) : ActivitySupport

    /** Embedded and file databases have no other sessions to watch; the monitor isn't offered at all. */
    data object NotApplicable : ActivitySupport

    companion object {
        fun of(type: DatabaseType): ActivitySupport =
            when {
                type.paradigm != Paradigm.Relational || !type.isServer || type.family == EngineFamily.Generic -> NotApplicable
                ActivityReader.of(type) != null -> Supported
                else -> NotYet("The activity monitor doesn't support ${type.displayName} yet. It supports PostgreSQL, MySQL and MariaDB.")
            }
    }
}

/** The engine-specific queries behind the monitor. Blocking; [ActivityMonitor] runs them on IO. */
abstract class ActivityReader {
    abstract fun read(connection: Connection): ActivitySnapshot

    /** Stops the session's running query; the session stays connected. Returns whether the server did it. */
    abstract fun cancel(connection: Connection, id: String): Boolean

    /** Ends the session, rolling back its open transaction. Returns whether the server did it. */
    abstract fun terminate(connection: Connection, id: String): Boolean

    /** Privileges the user lacks, each with what it costs, e.g. not seeing other users' queries. */
    abstract fun missingPrivileges(connection: Connection): List<String>

    companion object {
        fun of(type: DatabaseType): ActivityReader? =
            when (type.dialect) {
                // CockroachDB speaks the protocol but has neither pg_stat_activity's columns nor pg_blocking_pids.
                is SqlDialect.CockroachDBDialect -> null
                is SqlDialect.PostgreSQLDialect -> PostgresActivity
                // TiDB and PlanetScale (Vitess) speak the protocol but list and kill sessions their own way.
                is SqlDialect.MySQLDialect -> MySqlActivity.takeIf { type == DatabaseType.MySQL || type == DatabaseType.MariaDB }
                else -> null
            }
    }
}

internal object PostgresActivity : ActivityReader() {
    private const val Sessions =
        """
        SELECT pid, usename, datname, client_addr::text AS client, client_hostname, application_name, state,
               wait_event_type, wait_event, query, query_start, xact_start,
               array_to_string(pg_blocking_pids(pid), ',') AS blocked_by, pid = pg_backend_pid() AS own, now() AS server_now
        FROM pg_stat_activity
        WHERE backend_type = 'client backend'
        ORDER BY query_start NULLS LAST, pid
        """

    override fun read(connection: Connection): ActivitySnapshot {
        var now: Instant? = null
        val sessions =
            connection.createStatement().use { st ->
                st.queryTimeout = 10
                st.executeQuery(Sessions).use { rs ->
                    buildList {
                        while (rs.next()) {
                            now = now ?: rs.instant("server_now")
                            add(session(rs))
                        }
                    }
                }
            }
        return ActivitySnapshot(sessions, now ?: Instant.now())
    }

    private fun session(rs: ResultSet): SessionActivity {
        val blockedBy = rs.getString("blocked_by").orEmpty().split(',').filter { it.isNotBlank() }
        val waitType = rs.getString("wait_event_type")
        val state =
            when {
                blockedBy.isNotEmpty() -> SessionState.Blocked
                else ->
                    when (rs.getString("state")) {
                        "active" -> SessionState.Active
                        "idle" -> SessionState.Idle
                        "idle in transaction", "idle in transaction (aborted)" -> SessionState.IdleInTransaction
                        else -> SessionState.Other
                    }
            }
        val client = rs.getString("client_hostname") ?: rs.getString("client")?.substringBefore('/')
        return SessionActivity(
            id = rs.getInt("pid").toString(),
            user = rs.getString("usename"),
            database = rs.getString("datname"),
            // A null address is a Unix socket; a null state means we may not see other users' sessions (see missingPrivileges).
            client = client ?: "local",
            application = rs.getString("application_name")?.takeIf { it.isNotBlank() },
            state = state,
            // Idle sessions always "wait" for their client to send something; that's not worth showing.
            wait = waitType?.takeUnless { it == "Client" }?.let { type -> rs.getString("wait_event")?.let { "$type: $it" } ?: type },
            query = rs.getString("query")?.takeIf { it.isNotBlank() },
            queryStart = rs.instant("query_start"),
            transactionStart = rs.instant("xact_start"),
            blockedBy = blockedBy,
            own = rs.getBoolean("own"),
        )
    }

    override fun cancel(connection: Connection, id: String): Boolean = signal(connection, "pg_cancel_backend", id)

    override fun terminate(connection: Connection, id: String): Boolean = signal(connection, "pg_terminate_backend", id)

    private fun signal(connection: Connection, function: String, id: String): Boolean =
        connection.prepareStatement("SELECT $function(?)").use { ps ->
            ps.setInt(1, id.toIntOrNull() ?: throw SQLException("Not a PostgreSQL backend pid: $id"))
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    override fun missingPrivileges(connection: Connection): List<String> {
        val sql =
            """
            SELECT r.rolsuper,
                   pg_has_role(current_user, 'pg_read_all_stats', 'USAGE') AS read_stats,
                   pg_has_role(current_user, 'pg_signal_backend', 'USAGE') AS signal
            FROM pg_roles r WHERE r.rolname = current_user
            """
        return runCatching {
            connection.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    if (!rs.next() || rs.getBoolean("rolsuper")) return emptyList()
                    listOfNotNull(
                        "pg_read_all_stats — other users' queries and states are hidden".takeUnless { rs.getBoolean("read_stats") },
                        "pg_signal_backend — only your own user's sessions can be cancelled or terminated".takeUnless { rs.getBoolean("signal") },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}

/**
 * MySQL 8 and MariaDB: `PROCESSLIST` for sessions, `INNODB_TRX` for open transactions, and lock waits from
 * `performance_schema.data_lock_waits` (MySQL 8) or `INNODB_LOCK_WAITS` (MariaDB, MySQL 5.7). Ages are computed on the
 * server, in seconds, so the drivers' time zone handling doesn't matter.
 */
internal object MySqlActivity : ActivityReader() {
    private const val Sessions =
        """
        SELECT p.ID, p.USER, p.HOST, p.DB, p.COMMAND, p.TIME, p.STATE, p.INFO, p.ID = CONNECTION_ID() AS own,
               TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS trx_age, t.trx_id
        FROM information_schema.PROCESSLIST p
        LEFT JOIN information_schema.INNODB_TRX t ON t.trx_mysql_thread_id = p.ID
        WHERE p.COMMAND NOT IN ('Daemon', 'Binlog Dump', 'Binlog Dump GTID') AND p.USER NOT IN ('event_scheduler', 'system user')
        """

    private val lockWaits =
        listOf(
            """
            SELECT r.trx_mysql_thread_id AS waiting, b.trx_mysql_thread_id AS blocking
            FROM performance_schema.data_lock_waits w
            JOIN information_schema.INNODB_TRX r ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID
            JOIN information_schema.INNODB_TRX b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID
            """,
            """
            SELECT r.trx_mysql_thread_id AS waiting, b.trx_mysql_thread_id AS blocking
            FROM information_schema.INNODB_LOCK_WAITS w
            JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id
            JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id
            """,
        )

    /** Client program names, which TableGrip sets through connection attributes; some servers don't keep them. */
    private const val ProgramNames =
        "SELECT PROCESSLIST_ID, ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE ATTR_NAME = 'program_name'"

    override fun read(connection: Connection): ActivitySnapshot {
        val now = Instant.now()
        val waits = optional(connection, lockWaits) { rs -> rs.getString("waiting") to rs.getString("blocking") }.groupBy({ it.first }, { it.second })
        val programs = optional(connection, listOf(ProgramNames)) { rs -> rs.getString(1) to rs.getString(2) }.toMap()
        val sessions =
            connection.createStatement().use { st ->
                st.queryTimeout = 10
                st.executeQuery(Sessions).use { rs ->
                    buildList {
                        while (rs.next()) add(session(rs, now, waits, programs))
                    }
                }
            }
        return ActivitySnapshot(sessions, now)
    }

    private fun session(rs: ResultSet, now: Instant, waits: Map<String, List<String>>, programs: Map<String, String>): SessionActivity {
        val id = rs.getLong("ID").toString()
        val command = rs.getString("COMMAND")
        val stateText = rs.getString("STATE")?.takeIf { it.isNotBlank() }
        val inTransaction = rs.getString("trx_id") != null
        val blockedBy = waits[id].orEmpty().distinct()
        // Metadata and table locks don't show up as InnoDB lock waits; the state says so instead.
        val waitingOnLock = stateText != null && stateText.startsWith("Waiting", ignoreCase = true) && stateText.contains("lock", ignoreCase = true)
        val state =
            when {
                blockedBy.isNotEmpty() || waitingOnLock -> SessionState.Blocked
                command == "Sleep" -> if (inTransaction) SessionState.IdleInTransaction else SessionState.Idle
                command == "Query" || command == "Execute" -> SessionState.Active
                else -> SessionState.Other
            }
        val inState = rs.getLong("TIME").takeUnless { rs.wasNull() }
        val transactionAge = rs.getLong("trx_age").takeUnless { rs.wasNull() }
        return SessionActivity(
            id = id,
            user = rs.getString("USER"),
            database = rs.getString("DB"),
            client = rs.getString("HOST")?.substringBeforeLast(':'),
            application = programs[id],
            state = state,
            wait = stateText?.takeIf { state == SessionState.Blocked },
            query = rs.getString("INFO")?.takeIf { it.isNotBlank() },
            // TIME is seconds in the current state: the query's age while one runs.
            queryStart = inState?.takeIf { state == SessionState.Active || state == SessionState.Blocked }?.let { now.minusSeconds(it) },
            transactionStart = transactionAge?.let { now.minusSeconds(it) },
            blockedBy = blockedBy,
            own = rs.getBoolean("own"),
        )
    }

    /** The first of [queries] the server accepts, or nothing: lock waits and attributes need privileges or features some servers lack. */
    private fun <T> optional(connection: Connection, queries: List<String>, row: (ResultSet) -> T): List<T> {
        for (sql in queries) {
            try {
                return connection.createStatement().use { st ->
                    st.queryTimeout = 10
                    st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
                }
            } catch (_: SQLException) {
                continue
            }
        }
        return emptyList()
    }

    override fun cancel(connection: Connection, id: String): Boolean = kill(connection, "KILL QUERY", id)

    override fun terminate(connection: Connection, id: String): Boolean = kill(connection, "KILL", id)

    private fun kill(connection: Connection, statement: String, id: String): Boolean {
        val thread = id.toLongOrNull() ?: throw SQLException("Not a MySQL connection id: $id")
        return try {
            connection.createStatement().use { it.execute("$statement $thread") }
            true
        } catch (e: SQLException) {
            // 1094: unknown thread id, i.e. it ended already.
            if (e.errorCode == 1094) false else throw e
        }
    }

    override fun missingPrivileges(connection: Connection): List<String> {
        val sql =
            "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES " +
                "WHERE GRANTEE = CONCAT('''', SUBSTRING_INDEX(CURRENT_USER(), '@', 1), '''@''', SUBSTRING_INDEX(CURRENT_USER(), '@', -1), '''')"
        val privileges =
            runCatching {
                connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildSet { while (rs.next()) add(rs.getString(1).uppercase()) } } }
            }.getOrElse { return emptyList() }
        return listOfNotNull(
            "PROCESS — only your own user's sessions are listed, and lock waits are hidden".takeUnless { "PROCESS" in privileges },
            "CONNECTION_ADMIN or SUPER — only your own user's sessions can be cancelled or terminated"
                .takeUnless { "SUPER" in privileges || "CONNECTION_ADMIN" in privileges },
        )
    }
}

/**
 * Watches one server's sessions over a connection of its own, so it keeps working when the pool is busy or stuck
 * behind the very locks it is showing. Calls are serialized: one JDBC connection runs one statement at a time.
 */
class ActivityMonitor(private val connection: Connection, private val reader: ActivityReader, val readOnly: Boolean) : AutoCloseable {
    private val mutex = Mutex()

    suspend fun snapshot(): ActivitySnapshot = io { reader.read(connection) }

    suspend fun missingPrivileges(): List<String> = io { reader.missingPrivileges(connection) }

    suspend fun cancel(id: String): Boolean {
        if (readOnly) throw ReadOnlyViolationException("cancelling other sessions' queries")
        return io { reader.cancel(connection, id) }
    }

    suspend fun terminate(id: String): Boolean {
        if (readOnly) throw ReadOnlyViolationException("terminating sessions")
        return io { reader.terminate(connection, id) }
    }

    private suspend fun <T> io(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    override fun close() {
        runCatching { connection.close() }
    }
}

package dev.datagrok.core.driver

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.SshTunnel
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.edit.ChangeStatement
import dev.datagrok.core.export.ResultWriter
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.metadata.Introspector
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableInfo
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.sql.SqlClassifier
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A live connection to one database profile. A small pool serves the explorer and table viewers; SQL consoles get their
 * own dedicated [QuerySession]s.
 */
class Database private constructor(
    val config: ConnectionConfig,
    private val password: String?,
    private val jdbcUrl: String,
    private val pool: HikariDataSource,
    private val tunnel: SshTunnel?,
) : AutoCloseable {
    val dialect: SqlDialect = SqlDialect.of(config.type)
    private val introspector = Introspector(dialect)

    suspend fun namespaces(): List<Namespace> = withMetadata { introspector.namespaces(it.metaData) }

    suspend fun tables(namespace: Namespace): List<TableInfo> = withMetadata { introspector.tables(it.metaData, namespace) }

    suspend fun tableDetails(table: TableRef): TableDetails = withMetadata { introspector.details(it.metaData, table) }

    suspend fun fetchPage(
        table: TableRef,
        filter: String?,
        orderBy: List<SortColumn>,
        limit: Int,
        offset: Long,
        timeoutSeconds: Int = 0,
    ): QueryResult.Rows {
        // One extra row tells whether another page exists; it comes back as `truncated`.
        val sql = dialect.selectPage(table, filter, orderBy, limit + 1, offset)
        return withPooled { connection ->
            connection.withStatement(timeoutSeconds) { it.executeForResult(sql, limit) as QueryResult.Rows }
        }
    }

    /**
     * Streams every row of [table] (with the viewer's filter and sort) into [writer] using a server-side cursor, calling
     * [onProgress] every few thousand rows. Cancel the coroutine to stop.
     */
    suspend fun exportTable(
        table: TableRef,
        filter: String?,
        orderBy: List<SortColumn>,
        writer: (List<ResultColumn>) -> ResultWriter,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val sql = dialect.selectAll(table, filter, orderBy)
        return withPooled { connection ->
            // PostgreSQL only streams with a cursor inside a transaction; everyone else ignores this.
            val autoCommit = withContext(Dispatchers.IO) { connection.autoCommit.also { connection.autoCommit = false } }
            try {
                connection.withStatement(timeoutSeconds = 0) { statement ->
                    statement.fetchSize = if (config.type == DatabaseType.MySQL) Int.MIN_VALUE else 1_000
                    statement.executeQuery(sql).use { rs -> rs.streamTo(writer, onProgress) }
                }
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching { connection.rollback() }
                    connection.autoCommit = autoCommit
                }
            }
        }
    }

    suspend fun countRows(table: TableRef, filter: String?, timeoutSeconds: Int = 0): Long =
        withPooled { connection ->
            connection.withStatement(timeoutSeconds) { statement ->
                statement.executeQuery(dialect.countRows(table, filter)).use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    /**
     * Applies [statements] in one transaction. Rolls back if any update or delete doesn't hit exactly the expected
     * number of rows, e.g. because the row changed since it was read.
     */
    suspend fun applyChanges(statements: List<ChangeStatement>): Int {
        if (config.readOnly) throw ReadOnlyViolationException("editing data")
        return withMetadata { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                var total = 0
                for (change in statements) {
                    val affected =
                        connection.prepareStatement(change.sql).use { ps ->
                            change.parameters.forEachIndexed { i, p -> dialect.bind(ps, i + 1, p.value, p.jdbcType) }
                            ps.executeUpdate()
                        }
                    if (change.expectedRows != null && affected != change.expectedRows) {
                        throw SQLException(
                            "Expected ${change.expectedRows} row(s) but $affected would be affected by: ${change.preview(dialect)}. " +
                                "Nothing was saved."
                        )
                    }
                    total += affected
                }
                connection.commit()
                total
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = autoCommit
            }
        }
    }

    suspend fun openSession(): QuerySession =
        withContext(Dispatchers.IO) {
            val connection = newConnection(jdbcUrl, config, password)
            // Defence in depth on top of the client-side check; not every driver supports it after connecting.
            if (config.readOnly) runCatching { connection.isReadOnly = true }
            QuerySession(connection, SqlClassifier(dialect.syntax), config.readOnly)
        }

    override fun close() {
        pool.close()
        tunnel?.close()
    }

    private suspend fun <T> withMetadata(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { pool.connection.use(block) }

    private suspend fun <T> withPooled(block: suspend (Connection) -> T): T {
        val connection = withContext(Dispatchers.IO) { pool.connection }
        return try {
            block(connection)
        } finally {
            withContext(Dispatchers.IO) { connection.close() }
        }
    }

    companion object {
        /**
         * Opens the SSH tunnel (if any) and the pool, and validates the connection, so bad credentials fail here
         * rather than later.
         */
        suspend fun connect(config: ConnectionConfig, password: String?, sshSecret: String? = null): Database =
            withContext(Dispatchers.IO) {
                val tunnel = openTunnel(config, sshSecret)
                try {
                    val url = jdbcUrl(config, tunnel)
                    val hikari =
                        HikariConfig().apply {
                            jdbcUrl = url
                            dataSourceProperties = credentials(config, password)
                            poolName = "datagrok-${config.name}"
                            maximumPoolSize = 4
                            // Keeps one connection open, which also keeps shared in-memory SQLite databases alive.
                            minimumIdle = 1
                            connectionTimeout = 10_000
                            initializationFailTimeout = 1
                            isReadOnly = config.readOnly && config.type != DatabaseType.SQLite
                        }
                    val pool =
                        try {
                            HikariDataSource(hikari)
                        } catch (e: HikariPool.PoolInitializationException) {
                            throw e.cause ?: e
                        }
                    Database(config, password, url, pool, tunnel)
                } catch (e: Throwable) {
                    tunnel?.close()
                    throw e
                }
            }

        /** A single unpooled connection (through the tunnel, if any), e.g. for "Test Connection". */
        suspend fun test(config: ConnectionConfig, password: String?, sshSecret: String? = null) =
            withContext(Dispatchers.IO) {
                openTunnel(config, sshSecret).use { tunnel -> newConnection(jdbcUrl(config, tunnel), config, password).close() }
            }

        private fun openTunnel(config: ConnectionConfig, sshSecret: String?): SshTunnel? {
            val ssh = config.ssh ?: return null
            val port = config.port ?: config.type.defaultPort ?: return null
            return SshTunnel.open(ssh, sshSecret, config.host, port)
        }

        /** Through a tunnel, the database is reached on the tunnel's local port. */
        private fun jdbcUrl(config: ConnectionConfig, tunnel: SshTunnel?): String =
            config.type.jdbcUrl(if (tunnel == null) config else config.copy(host = "127.0.0.1", port = tunnel.localPort))

        private fun newConnection(url: String, config: ConnectionConfig, password: String?): Connection =
            DriverManager.getConnection(url, credentials(config, password))

        private fun credentials(config: ConnectionConfig, password: String?) =
            Properties().apply {
                if (config.user.isNotBlank()) setProperty("user", config.user)
                if (!password.isNullOrEmpty()) setProperty("password", password)
            }
    }
}

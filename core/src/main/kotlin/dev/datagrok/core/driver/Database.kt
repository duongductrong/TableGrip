package dev.datagrok.core.driver

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.edit.ChangeStatement
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.metadata.Introspector
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableInfo
import dev.datagrok.core.metadata.TableRef
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
    private val pool: HikariDataSource,
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
    suspend fun applyChanges(statements: List<ChangeStatement>): Int =
        withMetadata { connection ->
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

    suspend fun openSession(): QuerySession = withContext(Dispatchers.IO) { QuerySession(newConnection(config, password)) }

    override fun close() = pool.close()

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
        /** Opens the pool and validates the connection, so bad credentials fail here rather than later. */
        suspend fun connect(config: ConnectionConfig, password: String?): Database =
            withContext(Dispatchers.IO) {
                val hikari =
                    HikariConfig().apply {
                        jdbcUrl = config.type.jdbcUrl(config)
                        dataSourceProperties = credentials(config, password)
                        poolName = "datagrok-${config.name}"
                        maximumPoolSize = 4
                        // Keeps one connection open, which also keeps shared in-memory SQLite databases alive.
                        minimumIdle = 1
                        connectionTimeout = 10_000
                        initializationFailTimeout = 1
                    }
                val pool =
                    try {
                        HikariDataSource(hikari)
                    } catch (e: HikariPool.PoolInitializationException) {
                        throw e.cause ?: e
                    }
                Database(config, password, pool)
            }

        /** A single unpooled connection, e.g. for "Test Connection". */
        suspend fun test(config: ConnectionConfig, password: String?) =
            withContext(Dispatchers.IO) { newConnection(config, password).close() }

        private fun newConnection(config: ConnectionConfig, password: String?): Connection =
            DriverManager.getConnection(config.type.jdbcUrl(config), credentials(config, password))

        private fun credentials(config: ConnectionConfig, password: String?) =
            Properties().apply {
                if (config.user.isNotBlank()) setProperty("user", config.user)
                if (!password.isNullOrEmpty()) setProperty("password", password)
            }
    }
}

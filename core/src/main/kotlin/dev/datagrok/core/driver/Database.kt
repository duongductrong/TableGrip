package dev.datagrok.core.driver

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import dev.datagrok.core.activity.ActivityMonitor
import dev.datagrok.core.activity.ActivityReader
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionString
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.connection.JdbcUrls
import dev.datagrok.core.connection.SshTunnel
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.edit.ChangeStatement
import dev.datagrok.core.export.ResultWriter
import dev.datagrok.core.filter.SqlFilter
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.ddl.DdlPhase
import dev.datagrok.core.ddl.DdlScript
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.Introspector
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.StructureReader
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableInfo
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.metadata.TableRelation
import dev.datagrok.core.metadata.TableStructure
import dev.datagrok.core.schema.SchemaSnapshot
import dev.datagrok.core.sql.SqlClassifier
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * A [RelationalConnection] over JDBC. A small pool serves the explorer and table viewers; SQL consoles get their own
 * dedicated [QuerySession]s.
 */
class Database private constructor(
    override val config: ConnectionConfig,
    private val password: String?,
    private val jdbcUrl: String,
    private val driver: java.sql.Driver,
    private val pool: HikariDataSource,
    private val tunnel: SshTunnel?,
) : RelationalConnection {
    override val dialect: SqlDialect = SqlDialect.of(config.type)
    private val introspector = Introspector(dialect)

    override suspend fun namespaces(): List<Namespace> = withMetadata { introspector.namespaces(it.metaData) }

    override suspend fun tables(namespace: Namespace): List<TableInfo> = withMetadata { introspector.tables(it.metaData, namespace) }

    override suspend fun tableDetails(table: TableRef): TableDetails = withMetadata { introspector.details(it.metaData, table) }

    /** Two round trips instead of four or more. */
    override suspend fun tableColumns(table: TableRef): TableDetails = withMetadata { introspector.details(it.metaData, table, keysAndIndexes = false) }

    override suspend fun tableStructure(table: TableRef): TableStructure =
        withMetadata { StructureReader.of(dialect).read(it, introspector.details(it.metaData, table)) }

    override suspend fun columnsOf(namespace: Namespace): Map<String, List<ColumnInfo>> = withMetadata { introspector.columnsOf(it.metaData, namespace) }

    override suspend fun relations(table: TableRef): List<TableRelation> = withMetadata { introspector.relations(it.metaData, table) }

    override suspend fun sampleValues(table: TableRef, column: String, prefix: String, limit: Int): List<String> {
        val sql = dialect.sampleValues(table, column, limit, scan = 10_000)
        val pattern = dialect.samplePattern(prefix)
        return withPooled { connection ->
            withContext(Dispatchers.IO) {
                connection.prepareStatement(sql).use { ps ->
                    ps.queryTimeout = 3
                    ps.setString(1, pattern)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) rs.getString(1)?.let(::add) } }
                }
            }
        }
    }

    override suspend fun fetchPage(
        table: TableRef,
        filter: SqlFilter?,
        orderBy: List<SortColumn>,
        limit: Int,
        offset: Long,
        timeoutSeconds: Int,
    ): QueryResult.Rows {
        // One extra row tells whether another page exists; it comes back as `truncated`.
        val sql = dialect.selectPage(table, filter?.sql, orderBy, limit + 1, offset)
        val start = System.nanoTime()
        return withPooled { connection ->
            // The SQL already has a LIMIT; Statement.maxRows would cost Connector/J two `SET sql_select_limit` round trips.
            connection.query(sql, filter, timeoutSeconds) { rs ->
                rs.readRows(limit) { (System.nanoTime() - start) / 1_000_000 }
            }
        }
    }

    /**
     * Streams every row of [table] (with the viewer's filter and sort) into [writer] using a server-side cursor, calling
     * [onProgress] every few thousand rows. Cancel the coroutine to stop.
     */
    override suspend fun exportTable(
        table: TableRef,
        filter: SqlFilter?,
        orderBy: List<SortColumn>,
        writer: (List<ResultColumn>) -> ResultWriter,
        onProgress: (Long) -> Unit,
    ): Long {
        val sql = dialect.selectAll(table, filter?.sql, orderBy)
        return withPooled { connection ->
            // PostgreSQL only streams with a cursor inside a transaction; everyone else ignores this.
            val autoCommit = withContext(Dispatchers.IO) { connection.autoCommit.also { connection.autoCommit = false } }
            try {
                connection.query(
                    sql,
                    filter,
                    timeoutSeconds = 0,
                    // Connector/J streams only with MIN_VALUE; MariaDB Connector/J and pgjdbc stream with a positive size.
                    configure = { it.fetchSize = if (config.effectiveDriver == DriverId.MySQL) Int.MIN_VALUE else 1_000 },
                ) { rs -> rs.streamTo(writer, onProgress) }
            } finally {
                // Also when the export is cancelled, so the connection goes back to the pool in auto-commit mode.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { connection.rollback() }
                    runCatching { connection.autoCommit = autoCommit }
                }
            }
        }
    }

    override suspend fun countRows(table: TableRef, filter: SqlFilter?, timeoutSeconds: Int): Long =
        withPooled { connection ->
            connection.query(dialect.countRows(table, filter?.sql), filter, timeoutSeconds) { rs -> rs.next(); rs.getLong(1) }
        }

    /**
     * Runs the query [sql], whose WHERE clause is [filter], and reads its results. Filters typed as SQL have no
     * parameters and run unprepared, so a `?` in them (e.g. a jsonb operator) stays an operator.
     */
    private suspend fun <T> Connection.query(
        sql: String,
        filter: SqlFilter?,
        timeoutSeconds: Int,
        configure: (Statement) -> Unit = {},
        read: (ResultSet) -> T,
    ): T {
        val parameters = filter?.parameters.orEmpty()
        return if (parameters.isEmpty()) {
            withStatement(timeoutSeconds) { statement ->
                configure(statement)
                statement.executeQuery(sql).use(read)
            }
        } else {
            withPrepared(sql, timeoutSeconds) { statement ->
                configure(statement)
                parameters.forEachIndexed { i, parameter -> dialect.bind(statement, i + 1, parameter) }
                statement.executeQuery().use(read)
            }
        }
    }

    /**
     * Applies [statements] in one transaction. Rolls back if any update or delete doesn't hit exactly the expected
     * number of rows, e.g. because the row changed since it was read.
     */
    override suspend fun applyChanges(statements: List<ChangeStatement>): Int {
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

    override suspend fun schemaSnapshot(namespace: Namespace, onProgress: (done: Int, total: Int) -> Unit): SchemaSnapshot {
        val (views, tables) = tables(namespace).partition { it.isView }
        onProgress(0, tables.size)
        val done = AtomicInteger()
        // A few tables at a time: each is several metadata round trips, which add up on a remote server, but the pool is
        // shared with open table tabs.
        val permits = Semaphore(PoolSize - 1)
        val details =
            coroutineScope {
                tables.map { table ->
                    async {
                        permits.withPermit {
                            withMetadata { introspector.details(it.metaData, table.ref) }.also { onProgress(done.incrementAndGet(), tables.size) }
                        }
                    }
                }.awaitAll()
            }
        return SchemaSnapshot(namespace, details, views.map { it.ref.name })
    }

    override suspend fun executeScript(script: DdlScript, onProgress: (Int) -> Unit): Int {
        if (config.readOnly) throw ReadOnlyViolationException("changing the schema")
        val prologue = script.statements.filter { it.phase == DdlPhase.Prologue }.map { it.sql }
        val epilogue = script.statements.filter { it.phase == DdlPhase.Epilogue }.map { it.sql }
        val body = script.statements.filter { it.phase != DdlPhase.Prologue && it.phase != DdlPhase.Epilogue }.map { it.sql }
        return withPooled { connection ->
            withContext(Dispatchers.IO) {
                val autoCommit = connection.autoCommit
                // SQLite rebuilds turn foreign keys off; put back whatever the connection had, not blindly ON.
                val sqliteForeignKeys =
                    if (dialect is SqlDialect.SQLiteDialect && prologue.isNotEmpty()) connection.scalar("PRAGMA foreign_keys") else null
                var done = 0
                fun run(sql: String) {
                    connection.createStatement().use { it.execute(sql) }
                    onProgress(++done)
                }
                try {
                    prologue.forEach(::run)
                    if (script.transactional) connection.autoCommit = false
                    for ((index, sql) in body.withIndex()) {
                        try {
                            coroutineContext.ensureActive()
                            run(sql)
                        } catch (e: Exception) {
                            val rolledBack = script.transactional && runCatching { connection.rollback() }.isSuccess
                            val reason = (e as? SQLException)?.message ?: e.toString()
                            val outcome =
                                when {
                                    rolledBack -> "Nothing was changed."
                                    index == 0 -> "Nothing was changed."
                                    else -> "The $index statement(s) before it were applied and stay applied: $dialect can't roll back DDL."
                                }
                            throw ScriptException("Statement ${index + 1} of ${body.size} failed: $reason\n$outcome", sql, index, rolledBack, e)
                        }
                    }
                    if (sqliteForeignKeys != null) {
                        val violations = connection.createStatement().use { st -> st.executeQuery("PRAGMA foreign_key_check").use { rs -> buildList { while (rs.next() && size < 5) add(rs.getString(1)) } } }
                        if (violations.isNotEmpty()) {
                            connection.rollback()
                            throw ScriptException(
                                "The change leaves rows whose foreign keys point nowhere (in ${violations.distinct().joinToString()}). Nothing was changed.",
                                "PRAGMA foreign_key_check", body.size, rolledBack = true, cause = null,
                            )
                        }
                    }
                    if (script.transactional) connection.commit()
                    if (sqliteForeignKeys != null) connection.createStatement().use { it.execute("PRAGMA foreign_keys = $sqliteForeignKeys") }
                    else epilogue.forEach(::run)
                    done
                } finally {
                    withContext(NonCancellable) {
                        runCatching { if (!connection.autoCommit) connection.rollback() }
                        runCatching { connection.autoCommit = autoCommit }
                        if (sqliteForeignKeys != null) runCatching { connection.createStatement().use { it.execute("PRAGMA foreign_keys = $sqliteForeignKeys") } }
                    }
                }
            }
        }
    }

    override suspend fun <T> insertRows(
        table: TableRef,
        columns: List<ColumnInfo>,
        rows: Sequence<T>,
        values: (T) -> List<String?>,
        describe: (index: Long, row: T) -> String,
        onProgress: (Long) -> Unit,
    ): Long {
        if (config.readOnly) throw ReadOnlyViolationException("inserting rows")
        require(columns.isNotEmpty()) { "No columns to insert into" }
        val sql =
            "INSERT INTO ${dialect.qualifiedName(table)} (${columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }}) " +
                "VALUES (${columns.joinToString(", ") { "?" }})"
        return withPooled { connection ->
            withContext(Dispatchers.IO) {
                val autoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    var count = 0L
                    connection.prepareStatement(sql).use { statement ->
                        // Rows of the batch being built, to name the one that fails.
                        val pending = ArrayList<Pair<Long, T>>(InsertBatchSize)
                        fun flush() {
                            if (pending.isEmpty()) return
                            try {
                                statement.executeBatch()
                            } catch (e: SQLException) {
                                // Drivers that stop at the bad row report how many went in before it.
                                val failed = (e as? java.sql.BatchUpdateException)?.updateCounts?.let { counts ->
                                    counts.indexOfFirst { it == Statement.EXECUTE_FAILED }.takeIf { it >= 0 } ?: counts.size.takeIf { it < pending.size }
                                }
                                val culprit = failed?.let { pending.getOrNull(it) }
                                val where = culprit?.let { (index, row) -> describe(index, row) } ?: "rows ${pending.first().first + 1}–${pending.last().first + 1}"
                                val reason = generateSequence<Throwable>(e) { (it as? SQLException)?.nextException ?: it.cause }.drop(1).firstOrNull()?.message ?: e.message
                                throw InsertException("Couldn't insert $where: $reason. Nothing was imported.", where, e)
                            }
                            count += pending.size
                            pending.clear()
                            onProgress(count)
                        }
                        for ((index, row) in rows.withIndex()) {
                            if (index % InsertBatchSize == 0) coroutineContext.ensureActive()
                            val rowValues = values(row)
                            columns.forEachIndexed { i, column -> dialect.bind(statement, i + 1, rowValues.getOrNull(i), column.jdbcType) }
                            statement.addBatch()
                            pending += index.toLong() to row
                            if (pending.size == InsertBatchSize) flush()
                        }
                        flush()
                    }
                    connection.commit()
                    count
                } catch (e: Throwable) {
                    withContext(NonCancellable) { runCatching { connection.rollback() } }
                    throw e
                } finally {
                    withContext(NonCancellable) { runCatching { connection.autoCommit = autoCommit } }
                }
            }
        }
    }

    private fun Connection.scalar(sql: String): String? = createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    override suspend fun openSession(): QuerySession =
        withContext(Dispatchers.IO) {
            val connection = newConnection(driver, jdbcUrl, config, password)
            // Defence in depth on top of the client-side check; not every driver supports it after connecting.
            if (config.readOnly) runCatching { connection.isReadOnly = true }
            QuerySession(connection, SqlClassifier(dialect.syntax), config.readOnly)
        }

    override suspend fun serverMajorVersion(): Int? = withMetadata { runCatching { it.metaData.databaseMajorVersion }.getOrNull() }

    override suspend fun backupInto(target: java.nio.file.Path) {
        val path = target.toAbsolutePath().toString().replace("'", "''")
        val sql =
            when (dialect) {
                is SqlDialect.SQLiteDialect -> "VACUUM INTO '$path'"
                is SqlDialect.DuckDBDialect -> "EXPORT DATABASE '$path' (FORMAT PARQUET)"
                else -> throw UnsupportedOperationException("${config.type.displayName} can't back itself up")
            }
        withMetadata { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    override suspend fun openActivityMonitor(): ActivityMonitor {
        val reader = ActivityReader.of(config.type) ?: throw UnsupportedOperationException("${config.type.displayName} has no activity monitor")
        return withContext(Dispatchers.IO) {
            ActivityMonitor(newConnection(driver, jdbcUrl, config, password), reader, config.readOnly)
        }
    }

    override fun close() {
        pool.close()
        tunnel?.close()
    }

    /** Runs blocking metadata reads; they go through [withPooled], so a closed tab's reads never start. */
    private suspend fun <T> withMetadata(block: (Connection) -> T): T = withPooled { connection -> withContext(Dispatchers.IO) { block(connection) } }

    /**
     * Borrows a pool connection for [block], which doesn't run if the caller was cancelled while waiting for it: opening
     * many tabs and closing most of them must not leave their reads queued ahead of the table still open. Giving the
     * connection back must survive cancellation: a plain `withContext` in `finally` throws straight away in a cancelled
     * coroutine, which leaked one connection per cancelled load until the pool ran dry.
     */
    private suspend fun <T> withPooled(block: suspend (Connection) -> T): T {
        var borrowed: Connection? = null
        try {
            // A connection checked out just as the caller is cancelled still has to go back, so it's recorded first.
            withContext(Dispatchers.IO) { borrowed = pool.connection }
            return block(borrowed!!)
        } finally {
            borrowed?.let { connection -> withContext(NonCancellable + Dispatchers.IO) { runCatching { connection.close() } } }
        }
    }

    companion object {
        /** Connections shared by explorer and table viewers; consoles have their own. */
        private const val PoolSize = 4

        /** Rows per JDBC batch when inserting: big enough to amortize round trips, small enough to name a failing row. */
        private const val InsertBatchSize = 500

        /**
         * Opens the SSH tunnel (if any) and the pool, and validates the connection, so bad credentials fail here
         * rather than later. Throws [dev.datagrok.core.drivers.DriverNotInstalledException] if the profile's driver
         * isn't on this machine yet.
         */
        suspend fun connect(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): Database =
            withContext(Dispatchers.IO) {
                val driver = drivers.load(config)
                val tunnel = openTunnel(config, sshSecret)
                try {
                    val url = jdbcUrl(config, tunnel)
                    val hikari =
                        HikariConfig().apply {
                            // The driver lives in its own class loader, so the pool gets it as a DataSource rather than
                            // a class name it would look up itself.
                            dataSource = DriverDataSource(driver, url, JdbcUrls.properties(config, password))
                            poolName = "datagrok-${config.name}"
                            maximumPoolSize = PoolSize
                            // A fixed, warm pool: a new connection to a remote server costs a TLS handshake and session
                            // setup (hundreds of ms), too much to pay whenever a few tabs load at once. It also keeps shared
                            // in-memory SQLite databases alive. Keepalive pings stop firewalls and NAT dropping idle ones.
                            minimumIdle = PoolSize
                            keepaliveTime = 120_000
                            connectionTimeout = 10_000
                            initializationFailTimeout = 1
                            isReadOnly = config.readOnly && config.effectiveDriver != DriverId.SQLite
                        }
                    val pool =
                        try {
                            HikariDataSource(hikari)
                        } catch (e: HikariPool.PoolInitializationException) {
                            throw e.cause ?: e
                        }
                    Database(config, password, url, driver, pool, tunnel)
                } catch (e: Throwable) {
                    tunnel?.close()
                    throw e
                }
            }

        /**
         * A single unpooled connection (through the tunnel, if any), e.g. for "Test Connection". Returns the engine the
         * server turned out to be when that isn't the profile's type (see [ServerIdentity]), else null.
         */
        suspend fun test(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): DatabaseType? =
            withContext(Dispatchers.IO) {
                val driver = drivers.load(config)
                openTunnel(config, sshSecret).use { tunnel ->
                    newConnection(driver, jdbcUrl(config, tunnel), config, password).use { ServerIdentity.suggest(it, config.type) }
                }
            }

        private fun openTunnel(config: ConnectionConfig, sshSecret: String?): SshTunnel? {
            val ssh = config.ssh ?: return null
            val (host, port) = tunnelTarget(config)
            return SshTunnel.open(ssh, sshSecret, host, port)
        }

        /** The database host and port as seen from the SSH server. */
        private fun tunnelTarget(config: ConnectionConfig): Pair<String, Int> {
            val defaultPort = config.type.defaultPort ?: throw SQLException("${config.type.displayName} doesn't use a network connection.")
            config.url?.let { url ->
                val parsed = ConnectionString.parse(url)
                val host = parsed.host?.takeIf { ',' !in it } ?: throw SQLException("SSH tunnels need a single-host URL.")
                return host.trim('[', ']') to (parsed.port ?: defaultPort)
            }
            if (',' in config.host) throw SQLException("SSH tunnels need a single host.")
            return config.host to (config.port ?: defaultPort)
        }

        /** Through a tunnel, the database is reached on the tunnel's local port. */
        private fun jdbcUrl(config: ConnectionConfig, tunnel: SshTunnel?): String =
            JdbcUrls.build(config, tunnel?.let { "127.0.0.1" to it.localPort })

        /** Connects with the profile's own driver, so it handles the URL even if another driver also claims it. */
        private fun newConnection(driver: java.sql.Driver, url: String, config: ConnectionConfig, password: String?): Connection =
            driver.connect(url, JdbcUrls.properties(config, password))
                ?: throw SQLException("${config.effectiveDriver.displayName} doesn't accept the URL $url")
    }
}

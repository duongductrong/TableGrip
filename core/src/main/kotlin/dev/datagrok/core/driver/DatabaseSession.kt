package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An open connection to a database. All calls are safe to invoke from the UI thread. */
interface DatabaseSession : AutoCloseable {
    val config: ConnectionConfig

    suspend fun listTables(): List<DbObject>

    suspend fun execute(sql: String, maxRows: Int = 1_000): QueryResult

    companion object {
        suspend fun open(config: ConnectionConfig, password: String?): DatabaseSession =
            withContext(Dispatchers.IO) {
                val connection = DriverManager.getConnection(config.type.jdbcUrl(config), config.user, password.orEmpty())
                JdbcSession(config, connection)
            }
    }
}

internal class JdbcSession(override val config: ConnectionConfig, private val connection: Connection) :
    DatabaseSession {

    override suspend fun listTables(): List<DbObject> =
        withContext(Dispatchers.IO) {
            connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
                buildList {
                    while (rs.next()) {
                        val kind = if (rs.getString("TABLE_TYPE") == "VIEW") DbObject.Kind.View else DbObject.Kind.Table
                        add(DbObject(rs.getString("TABLE_NAME"), kind, rs.getString("TABLE_SCHEM")))
                    }
                }
            }
        }

    override suspend fun execute(sql: String, maxRows: Int): QueryResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            connection.createStatement().use { statement ->
                statement.maxRows = maxRows + 1
                if (statement.execute(sql)) {
                    statement.resultSet.use { rs ->
                        val meta = rs.metaData
                        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val rows = buildList {
                            while (rs.next() && size <= maxRows) {
                                add(columns.indices.map { rs.getString(it + 1) })
                            }
                        }
                        QueryResult.Rows(
                            columns = columns,
                            rows = rows.take(maxRows),
                            truncated = rows.size > maxRows,
                            elapsedMillis = System.currentTimeMillis() - start,
                        )
                    }
                } else {
                    QueryResult.Update(statement.updateCount, System.currentTimeMillis() - start)
                }
            }
        }

    override fun close() = connection.close()
}

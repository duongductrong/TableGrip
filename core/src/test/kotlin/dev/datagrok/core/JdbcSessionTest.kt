package dev.datagrok.core

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.driver.DatabaseSession
import dev.datagrok.core.driver.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class JdbcSessionTest {
    @Test
    fun `executes queries against an in-memory SQLite database`() = runBlocking {
        val config = ConnectionConfig(id = "test", name = "test", type = DatabaseType.SQLite, database = ":memory:")
        DatabaseSession.open(config, password = null).use { session ->
            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)")
            val inserted = session.execute("INSERT INTO users (name) VALUES ('alice'), ('bob')")
            assertEquals(2, (inserted as QueryResult.Update).affectedRows)

            val rows = session.execute("SELECT name FROM users ORDER BY id") as QueryResult.Rows
            assertEquals(listOf("name"), rows.columns)
            assertEquals(listOf(listOf("alice"), listOf("bob")), rows.rows)

            assertEquals(listOf("users"), session.listTables().map { it.name })
        }
    }
}

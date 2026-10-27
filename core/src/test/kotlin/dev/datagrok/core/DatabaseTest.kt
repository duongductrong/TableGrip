package dev.datagrok.core

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.driver.Database
import dev.datagrok.core.edit.BoundValue
import dev.datagrok.core.edit.ChangeSql
import dev.datagrok.core.edit.RowChange
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.metadata.TableRef
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DatabaseTest {
    private val config =
        ConnectionConfig(id = UUID.randomUUID().toString(), name = "test", type = DatabaseType.SQLite, database = ":memory:")
    private val database = runBlocking { Database.connect(config, password = null) }

    @AfterTest fun close() = database.close()

    private suspend fun seed() =
        database.openSession().use { session ->
            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, team_id INTEGER REFERENCES teams(id))")
            session.execute("CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT)")
            session.execute("CREATE UNIQUE INDEX users_name ON users(name)")
            session.execute("CREATE VIEW names AS SELECT name FROM users")
            session.execute("INSERT INTO users (name) VALUES ('carol'), ('alice'), ('bob')")
        }

    @Test
    fun `sessions and the pool share the in-memory database`() = runBlocking {
        seed()
        val namespace = database.namespaces().single()
        val tables = database.tables(namespace)
        assertEquals(listOf("names" to true, "teams" to false, "users" to false), tables.map { it.ref.name to it.isView })
    }

    @Test
    fun `introspects columns, keys and indexes`() = runBlocking {
        seed()
        val users = database.tableDetails(TableRef(database.namespaces().single(), "users"))
        assertEquals(listOf("id", "name", "team_id"), users.columns.map { it.name })
        assertEquals(listOf("id"), users.primaryKey)
        assertEquals(false, users.columns[1].nullable)
        assertTrue(users.indexes.any { it.name == "users_name" && it.unique && it.columns == listOf("name") })
        assertEquals("teams", users.foreignKeys.single().referencedTable.name)
    }

    @Test
    fun `pages, sorts, filters and counts table rows`() = runBlocking {
        seed()
        val users = TableRef(database.namespaces().single(), "users")
        val byName = listOf(SortColumn("name", SortDirection.Asc))

        val page = database.fetchPage(users, filter = null, orderBy = byName, limit = 2, offset = 1)
        assertEquals(listOf("bob", "carol"), page.rows.map { it[1] })
        assertEquals(false, page.truncated)
        assertEquals(true, database.fetchPage(users, null, byName, limit = 1, offset = 0).truncated)

        val filtered = database.fetchPage(users, filter = "name LIKE 'a%'", orderBy = emptyList(), limit = 10, offset = 0)
        assertEquals(listOf("alice"), filtered.rows.map { it[1] })
        assertEquals(2L, database.countRows(users, filter = "id > 1"))
    }

    @Test
    fun `applies a change set in one transaction and rolls back on surprises`() = runBlocking {
        seed()
        val users = TableRef(database.namespaces().single(), "users")
        fun int(v: String?) = BoundValue(v, Types.INTEGER, numeric = true)
        fun text(v: String?) = BoundValue(v, Types.VARCHAR, numeric = false)

        val changes =
            listOf(
                RowChange.Update(key = mapOf("id" to int("1")), values = mapOf("name" to text("carol2"))),
                RowChange.Delete(key = mapOf("id" to int("2"))),
                RowChange.Insert(values = mapOf("name" to text("dave"))),
            )
        val statements = ChangeSql.statements(database.dialect, users, changes)
        assertEquals("DELETE FROM \"users\" WHERE \"id\" = 2", statements.first().preview(database.dialect))
        assertEquals(3, database.applyChanges(statements))

        val names = { runBlocking { database.fetchPage(users, null, listOf(SortColumn("id", SortDirection.Asc)), 10, 0).rows.map { it[1] } } }
        assertEquals(listOf("carol2", "bob", "dave"), names())

        // Updating a row that no longer exists fails and leaves everything as it was.
        val stale =
            ChangeSql.statements(
                database.dialect,
                users,
                listOf(
                    RowChange.Update(key = mapOf("id" to int("1")), values = mapOf("name" to text("x"))),
                    RowChange.Update(key = mapOf("id" to int("2")), values = mapOf("name" to text("y"))),
                ),
            )
        assertFailsWith<SQLException> { database.applyChanges(stale) }
        assertEquals(listOf("carol2", "bob", "dave"), names())
    }

    @Test
    fun `reports updates and truncates large results`() = runBlocking {
        database.openSession().use { session ->
            session.execute("CREATE TABLE n (x INTEGER)")
            val inserted = session.execute("INSERT INTO n VALUES (1), (2), (3)") as QueryResult.Update
            assertEquals(3, inserted.affectedRows)

            val rows = session.execute("SELECT x FROM n", maxRows = 2) as QueryResult.Rows
            assertEquals(2, rows.rows.size)
            assertTrue(rows.truncated)
        }
    }

    @Test
    fun `cancelling the coroutine cancels a running query`() = runBlocking {
        database.openSession().use { session ->
            val started = CompletableDeferred<Unit>()
            val endless = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c"
            val job = launch(Dispatchers.Default) {
                started.complete(Unit)
                session.execute(endless)
            }
            started.await()
            delay(300)
            withTimeout(5_000) { job.cancelAndJoin() }
            // The session stays usable after a cancelled query.
            val result = async { session.execute("SELECT 42") }.await() as QueryResult.Rows
            assertEquals("42", result.rows.single().single())
        }
    }
}

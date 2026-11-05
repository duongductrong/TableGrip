package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.edit.BoundValue
import dev.tablegrip.core.edit.ChangeSql
import dev.tablegrip.core.edit.RowChange
import dev.tablegrip.core.export.CopyFormat
import dev.tablegrip.core.export.ResultWriter
import dev.tablegrip.core.filter.SqlFilter
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.TableRef
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
    fun `reads constraints, triggers and DDL`() = runBlocking {
        database.openSession().use { session ->
            session.execute("CREATE TABLE teams (id INTEGER PRIMARY KEY, name TEXT UNIQUE)")
            session.execute(
                "CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0), " +
                    "team_id INTEGER REFERENCES teams(id) ON DELETE CASCADE, CONSTRAINT adult CHECK (age >= (18)))"
            )
            session.execute("CREATE INDEX users_team ON users(team_id)")
            session.execute("CREATE TRIGGER users_touch AFTER UPDATE ON users BEGIN SELECT 1; END")
        }
        val namespace = database.namespaces().single()
        val users = database.tableStructure(TableRef(namespace, "users"))
        assertEquals(
            listOf(
                ConstraintKind.PrimaryKey to "PRIMARY KEY (\"id\")",
                ConstraintKind.ForeignKey to "FOREIGN KEY (\"team_id\") REFERENCES \"teams\" (\"id\") ON DELETE CASCADE",
                ConstraintKind.Check to "CHECK (age > 0)",
                ConstraintKind.Check to "CHECK (age >= (18))",
            ),
            users.constraints.map { it.kind to it.definition },
        )
        assertEquals("adult", users.constraints.last().name)
        assertEquals(listOf(Triple("users_touch", "AFTER", "UPDATE")), users.triggers.map { Triple(it.name, it.timing, it.events) })
        assertTrue(users.ddl.startsWith("CREATE TABLE users"))
        assertTrue("CREATE INDEX users_team ON users(team_id);" in users.ddl)
        assertTrue(users.ddl.trimEnd().endsWith("END;"))

        val teams = database.tableStructure(TableRef(namespace, "teams"))
        assertEquals(listOf(ConstraintKind.PrimaryKey, ConstraintKind.Unique), teams.constraints.map { it.kind })
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

        val filtered = database.fetchPage(users, filter = SqlFilter.raw("name LIKE 'a%'"), orderBy = emptyList(), limit = 10, offset = 0)
        assertEquals(listOf("alice"), filtered.rows.map { it[1] })
        assertEquals(2L, database.countRows(users, filter = SqlFilter.raw("id > 1")))
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
    fun `manual commit mode keeps changes pending until commit or rollback`() = runBlocking {
        database.openSession().use { session ->
            session.execute("CREATE TABLE tx (x INTEGER)")
            session.setAutoCommit(false)
            session.execute("INSERT INTO tx VALUES (1)")
            assertEquals(1, session.uncommittedStatements)
            session.rollback()
            assertEquals(0, session.uncommittedStatements)

            session.execute("INSERT INTO tx VALUES (2)")
            session.commit()
            val rows = database.openSession().use { other -> other.execute("SELECT x FROM tx") as QueryResult.Rows }
            assertEquals(listOf(listOf("2")), rows.rows)
        }
    }

    @Test
    fun `read-only connections refuse writes before they reach the server`(): Unit = runBlocking {
        seed()
        Database.connect(config.copy(readOnly = true), password = null).use { readOnly ->
            readOnly.openSession().use { session ->
                assertEquals("3", (session.execute("SELECT count(*) FROM users") as QueryResult.Rows).rows.single().single())
                assertFailsWith<ReadOnlyViolationException> { session.execute("DELETE FROM users") }
                assertFailsWith<ReadOnlyViolationException> { session.execute("DROP TABLE users") }
            }
            assertFailsWith<ReadOnlyViolationException> { readOnly.applyChanges(emptyList()) }
        }
    }

    @Test
    fun `exports a whole table with filter and sort by streaming`(): Unit = runBlocking {
        seed()
        val users = TableRef(database.namespaces().single(), "users")
        val out = java.io.StringWriter()
        var progress = 0L
        val count =
            database.exportTable(
                users,
                filter = SqlFilter.raw("id > 1"),
                orderBy = listOf(SortColumn("name", SortDirection.Asc)),
                writer = { columns -> ResultWriter(CopyFormat.Csv, columns, out) },
                onProgress = { progress = it },
            )
        assertEquals(2, count)
        assertEquals(2, progress)
        // seed(): carol=1, alice=2, bob=3; id > 1 sorted by name.
        assertEquals("id,name,team_id\n2,alice,\n3,bob,", out.toString())
    }

    @Test
    fun `connects with a raw JDBC URL through the profile's driver`(): Unit = runBlocking {
        val raw = config.copy(url = "jdbc:sqlite:file:tablegrip-raw-${config.id}?mode=memory&cache=shared")
        Database.connect(raw, password = null).use { db ->
            db.openSession().use { assertEquals("1", (it.execute("SELECT 1") as QueryResult.Rows).rows.single().single()) }
        }
    }

    @Test
    fun `loads schema-wide columns, relations both ways and sample values`(): Unit = runBlocking {
        seed()
        val namespace = database.namespaces().single()
        val columns = database.columnsOf(namespace)
        assertEquals(listOf("id", "name", "team_id"), columns["users"]?.map { it.name })
        assertEquals(listOf("id", "name"), columns["teams"]?.map { it.name })

        val teams = TableRef(namespace, "teams")
        val users = TableRef(namespace, "users")
        val relation = database.relations(teams).single()
        assertEquals("users" to listOf("team_id"), relation.fromTable.name to relation.fromColumns)
        assertEquals(relation, database.relations(users).single(), "seen from either side")

        database.openSession().use { it.execute("INSERT INTO users (name) VALUES ('al_x'), ('alpha%')") }
        assertEquals(listOf("al_x", "alice", "alpha%"), database.sampleValues(users, "name", "al"))
        assertEquals(listOf("al_x"), database.sampleValues(users, "name", "al_"), "wildcards in the prefix are literal")
        assertEquals(listOf("1", "2"), database.sampleValues(users, "id", "").take(2))
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

    @Test
    fun `cancelled table loads give their pooled connection back`() = runBlocking {
        seed()
        database.openSession().use { session ->
            session.execute("CREATE VIEW endless AS WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS n FROM c")
        }
        val endless = TableRef(database.namespaces().single(), "endless")
        // More cancelled loads than the pool has connections, like paging or typing in the filter faster than queries return.
        repeat(6) {
            val job = launch(Dispatchers.Default) { database.fetchPage(endless, null, emptyList(), 10, 0) }
            delay(200)
            withTimeout(5_000) { job.cancelAndJoin() }
        }
        val users = TableRef(database.namespaces().single(), "users")
        assertEquals(3, withTimeout(5_000) { database.fetchPage(users, null, emptyList(), 10, 0) }.rows.size)
    }
}

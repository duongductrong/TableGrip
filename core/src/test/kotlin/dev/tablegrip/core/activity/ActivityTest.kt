package dev.tablegrip.core.activity

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.driver.ReadOnlyViolationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ActivityTest {
    @Test
    fun support() {
        assertEquals(ActivitySupport.Supported, ActivitySupport.of(DatabaseType.PostgreSQL))
        assertEquals(ActivitySupport.Supported, ActivitySupport.of(DatabaseType.Supabase))
        assertEquals(ActivitySupport.Supported, ActivitySupport.of(DatabaseType.MySQL))
        assertEquals(ActivitySupport.Supported, ActivitySupport.of(DatabaseType.MariaDB))
        assertIs<ActivitySupport.NotYet>(ActivitySupport.of(DatabaseType.TiDB))
        assertIs<ActivitySupport.NotYet>(ActivitySupport.of(DatabaseType.CockroachDB))
        assertIs<ActivitySupport.NotYet>(ActivitySupport.of(DatabaseType.SqlServer))
        for (type in listOf(DatabaseType.SQLite, DatabaseType.DuckDB, DatabaseType.H2, DatabaseType.Redis, DatabaseType.MongoDB, DatabaseType.OtherJdbc)) {
            assertEquals(ActivitySupport.NotApplicable, ActivitySupport.of(type), type.id)
        }
    }

    private fun connect(readOnly: Boolean = false, property: String = "it.postgres"): Database? {
        val (host, port) = System.getProperty(property)?.split(":") ?: return null
        val config =
            if (property == "it.mysql") ConnectionConfig(id = property, name = "it", type = DatabaseType.MySQL, host = host, port = port.toInt(), database = "app", user = "root", readOnly = readOnly)
            else ConnectionConfig(id = property, name = "it", type = DatabaseType.PostgreSQL, host = host, port = port.toInt(), database = "postgres", user = "postgres", readOnly = readOnly)
        return runBlocking { Database.connect(config, password = "pw") }
    }

    /** Skipped unless `-Pit.postgres=host:port`. */
    @Test
    fun postgresBlockingAndCancel(): Unit = blockingAndCancel(connect() ?: return, idleInTransactionLabel = "TableGrip")

    /** Skipped unless `-Pit.mysql=host:port`. */
    @Test
    fun mysqlBlockingAndCancel(): Unit = blockingAndCancel(connect(property = "it.mysql") ?: return, idleInTransactionLabel = "TableGrip")

    private fun blockingAndCancel(database: Database, idleInTransactionLabel: String): Unit = runBlocking {
        database.use {
            val holder = database.openSession()
            val waiter = database.openSession()
            val monitor = database.openActivityMonitor()
            try {
                holder.execute("CREATE TABLE IF NOT EXISTS activity_it (id int PRIMARY KEY)")
                holder.execute("DELETE FROM activity_it")
                holder.execute("INSERT INTO activity_it VALUES (1)")
                holder.setAutoCommit(false)
                holder.execute("UPDATE activity_it SET id = 1 WHERE id = 1")
                val blocked = async { runCatching { waiter.execute("UPDATE activity_it SET id = 1 WHERE id = 1") } }

                val waiting =
                    withTimeout(10_000) {
                        var found: SessionActivity? = null
                        while (found == null) {
                            found = monitor.snapshot().sessions.firstOrNull { it.state == SessionState.Blocked && it.query?.contains("activity_it") == true }
                            if (found == null) delay(100)
                        }
                        found
                    }
                val snapshot = monitor.snapshot()
                val holding = snapshot.sessions.single { it.id == waiting.blockedBy.single() }
                assertEquals(SessionState.IdleInTransaction, holding.state)
                assertEquals(idleInTransactionLabel, holding.application)
                assertTrue(snapshot.sessions.single { it.own }.query != null)
                assertTrue(waiting.wait != null)
                assertTrue(monitor.missingPrivileges().isEmpty(), "postgres is a superuser")

                assertTrue(monitor.cancel(waiting.id))
                assertTrue(blocked.await().isFailure, "the cancelled UPDATE fails")
                assertTrue(monitor.terminate(holding.id))
                assertTrue(!holder.isValid())
            } finally {
                monitor.close()
                waiter.close()
                runCatching { holder.close() }
                database.openSession().use { it.execute("DROP TABLE IF EXISTS activity_it") }
            }
        }
    }

    @Test
    fun readOnlyRefusesSignals(): Unit = runBlocking {
        val database = connect(readOnly = true) ?: return@runBlocking
        database.use {
            database.openActivityMonitor().use { monitor ->
                val own = monitor.snapshot().sessions.single { it.own }
                assertFailsWith<ReadOnlyViolationException> { monitor.cancel(own.id) }
                assertFailsWith<ReadOnlyViolationException> { monitor.terminate(own.id) }
            }
        }
    }
}

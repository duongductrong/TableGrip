package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.sqlite.SqliteHttpClient
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * SQLite over HTTP: rqlite and libSQL. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-rqlite -p 54001:4001 rqlite/rqlite:8.36.5
 * docker run -d --name dg-it-libsql -p 58088:8080 ghcr.io/tursodatabase/libsql-server:v0.24.32
 * -Pit.rqlite=localhost:54001 -Pit.libsql=localhost:58088
 * ```
 */
class SqliteHttpEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `statements split and reads are told from writes`() {
        assertEquals(listOf("SELECT 'a;b'", "SELECT 2"), SqliteHttpClient.splitStatements("SELECT 'a;b'; -- c;\nSELECT 2;"))
        listOf("SELECT 1", "WITH x AS (SELECT 1) SELECT * FROM x", "PRAGMA table_info(t)", "PRAGMA user_version", "EXPLAIN QUERY PLAN SELECT 1").forEach {
            assertTrue(SqliteHttpClient.isReadSql(it), it)
        }
        listOf("INSERT INTO t VALUES (1)", "SELECT 1; DELETE FROM t", "PRAGMA user_version = 3", "PRAGMA journal_mode(WAL)", "WITH x AS (SELECT 1) DELETE FROM t", "").forEach {
            assertFalse(SqliteHttpClient.isReadSql(it), it)
        }
    }

    @Test
    fun `rqlite browses tables and writes rows`() = engine("it.rqlite", DatabaseType.Rqlite, "rqlite ")

    @Test
    fun `libsql browses tables and writes rows`() = engine("it.libsql", DatabaseType.Libsql, "libSQL")

    private fun engine(property: String, type: DatabaseType, expected: String) = runBlocking<Unit> {
        val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt())
        RecordStore.connect(config, null, drivers = drivers).use { db ->
            assertTrue(db.server().startsWith(expected), db.server())
            db.execute("DROP TABLE IF EXISTS tg_orders; DROP TABLE IF EXISTS tg_log; DROP VIEW IF EXISTS tg_paid")
            db.execute("CREATE TABLE tg_orders (id INTEGER PRIMARY KEY, customer TEXT NOT NULL, total REAL, note TEXT); CREATE TABLE tg_log (line TEXT); CREATE VIEW tg_paid AS SELECT * FROM tg_orders WHERE total > 20")
            db.execute((1..45).joinToString(";") { "INSERT INTO tg_orders (id, customer, total) VALUES ($it, 'c${it % 3}', $it.5)" })
            db.execute("INSERT INTO tg_log VALUES ('a'); INSERT INTO tg_log VALUES ('b')")

            val containers = db.containers()
            assertEquals(45L, containers.single { it.name == "tg_orders" }.count)
            assertEquals("view", containers.single { it.name == "tg_paid" }.kind)
            assertTrue("CREATE TABLE tg_orders" in db.definition(listOf("tg_orders")))
            assertTrue(db.canWrite(listOf("tg_orders")))
            assertFalse(db.canWrite(listOf("tg_paid")))

            val path = listOf("tg_orders")
            val first = db.page(path, "", null, 20)
            assertEquals(20, first.records.size)
            assertEquals(5, db.page(path, "", db.page(path, "", first.cursor, 20).cursor, 20).records.size)
            assertEquals(15, db.page(path, "customer = 'c1'", null, 50).records.size)
            assertEquals(45.5, db.page(path, "ORDER BY total DESC", null, 1).records.single().document["total"]!!.jsonPrimitive.content.toDouble())
            assertEquals(26, db.page(listOf("tg_paid"), "", null, 50).records.size)
            assertNull(db.page(listOf("tg_paid"), "", null, 1).records.single().key, "views are read-only")

            val record = db.page(path, "id = 1", null, 1).records.single()
            val key = db.put(path, record.key, JsonObject(record.document + ("note" to JsonPrimitive("changed"))))
            assertEquals("changed", db.page(path, "id = 1", null, 1).records.single().document["note"]!!.jsonPrimitive.content)
            assertFailsWith<IOException>("a stale edit is refused") { db.put(path, record.key, record.document) }
            assertFailsWith<IOException> { db.put(path, key, JsonObject(record.document + ("id" to JsonPrimitive(99)))) }
            db.put(path, null, buildJsonObject { put("id", 100); put("customer", "new") })
            assertFailsWith<IOException>("a duplicate key fails") { db.put(path, null, buildJsonObject { put("id", 100); put("customer", "again") }) }

            // A table without a primary key is written by rowid.
            val line = db.page(listOf("tg_log"), "line = 'a'", null, 1).records.single()
            assertTrue("rowid" in line.key!!)
            db.put(listOf("tg_log"), line.key, buildJsonObject { put("line", "A") })
            assertEquals(1, db.page(listOf("tg_log"), "line = 'A'", null, 5).records.size)

            val doomed = db.page(path, "id IN (2, 3, 100)", null, 5).records.map { it.key!! }
            assertEquals(3L, db.delete(path, doomed))
            assertEquals(0L, db.delete(path, doomed))

            // A batch is all or nothing.
            assertFailsWith<IOException> { db.execute("INSERT INTO tg_orders (id, customer) VALUES (500, 'x'); INSERT INTO tg_orders (id, customer) VALUES (1, 'clash')") }
            assertEquals(0, db.page(path, "id = 500", null, 1).records.size)

            val rows = db.execute("SELECT customer, count(*) AS n FROM tg_orders GROUP BY customer ORDER BY customer") as ConsoleReply.Table
            assertEquals(listOf("customer", "n"), rows.rows.columns.map { it.name })
            assertEquals("number", rows.rows.columns[1].typeName)
            assertTrue(db.execute("UPDATE tg_orders SET note = 'x' WHERE id = 4") is ConsoleReply.Text)
            db.execute("DROP VIEW tg_paid; DROP TABLE tg_orders; DROP TABLE tg_log")
        }
        RecordStore.connect(config.copy(readOnly = true), null, drivers = drivers).use {
            assertTrue(it.execute("SELECT 1") is ConsoleReply.Table)
            assertFailsWith<ReadOnlyViolationException> { it.execute("CREATE TABLE tg_nope (a)") }
        }
    }
}

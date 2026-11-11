package dev.tablegrip.engines.cassandra

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.ReadOnlyViolationException
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordStore
import dev.tablegrip.core.drivers.DriverLibrary
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Cassandra adapter. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-cassandra -p 59042:9042 -e MAX_HEAP_SIZE=512M -e HEAP_NEWSIZE=128M cassandra:5.0.3
 * docker run -d --name dg-it-scylla -p 59043:9042 scylladb/scylla:6.2.3 --smp 1 --memory 750M --overprovisioned 1 --developer-mode 1
 * -Pit.cassandra=localhost:59042 -Pit.scylladb=localhost:59043
 * ```
 */
class CassandraClientTest {
    @Test
    fun `statements split on semicolons outside quotes and comments`() {
        assertEquals(
            listOf("SELECT 'a;b' FROM t", "INSERT INTO t (a) VALUES ('it''s;')", "SELECT * FROM \"we;ird\""),
            CassandraClient.splitStatements("SELECT 'a;b' FROM t; -- one; two\nINSERT INTO t (a) VALUES ('it''s;');/* x; */ SELECT * FROM \"we;ird\""),
        )
    }

    @Test
    fun `reads are told from writes`() {
        listOf("SELECT * FROM t", "select json * from t;", "DESCRIBE TABLES", "desc keyspace ks", "LIST ROLES", "USE ks; SELECT 1 FROM t").forEach {
            assertTrue(CassandraClient.isReadStatement(it), it)
        }
        listOf("INSERT INTO t (a) VALUES (1)", "SELECT * FROM t; DELETE FROM t WHERE a = 1", "TRUNCATE t", "DROP TABLE t", "UPDATE t SET b = 1 WHERE a = 1", "").forEach {
            assertFalse(CassandraClient.isReadStatement(it), it)
        }
    }

    @Test
    fun `contact points take the profile's port unless they name one`() {
        assertEquals(listOf(InetSocketAddress.createUnresolved("db", 9042)), CassandraClient.contactPoints("db", "db", 9042))
        assertEquals(
            listOf(InetSocketAddress.createUnresolved("a", 9042), InetSocketAddress.createUnresolved("b", 9043), InetSocketAddress.createUnresolved("::1", 9044)),
            CassandraClient.contactPoints("a, b:9043, [::1]:9044", "a", 9042),
        )
    }

    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = true)

    private fun server(property: String, type: DatabaseType, readOnly: Boolean = false, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), readOnly = readOnly)
            RecordStore.connect(config, null, drivers = drivers).use { test(it) }
        }

    @Test
    fun `cassandra browses, pages and writes rows`() = rows("it.cassandra", DatabaseType.Cassandra, "Apache Cassandra 5")

    @Test
    fun `scylladb browses, pages and writes rows`() = rows("it.scylladb", DatabaseType.ScyllaDB, "ScyllaDB 6")

    private fun rows(property: String, type: DatabaseType, expected: String) = server(property, type) { db ->
        assertTrue(db.server().startsWith(expected), db.server())
        db.execute(
            """
            CREATE KEYSPACE IF NOT EXISTS tg_shop WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
            DROP TABLE IF EXISTS tg_shop.orders;
            CREATE TYPE IF NOT EXISTS tg_shop.address (street text, city text);
            CREATE TABLE tg_shop.orders (
              customer int, placed timestamp, id uuid, total decimal, tags set<text>, lines map<text, int>, ship frozen<address>, note text, "Mixed" text,
              PRIMARY KEY ((customer), placed, id)
            ) WITH CLUSTERING ORDER BY (placed DESC, id ASC);
            """.trimIndent(),
        )
        repeat(3) { customer ->
            repeat(25) { n ->
                db.execute(
                    "INSERT INTO tg_shop.orders (customer, placed, id, total, tags, lines, ship, note, \"Mixed\") VALUES " +
                        "($customer, '2024-01-${"%02d".format(n + 1)} 10:00:00+0000', uuid(), ${n}.50, {'a', 'b'}, {'apple': $n}, {street: 'Main $n', city: 'Hanoi'}, 'it''s $n', 'm')",
                )
            }
        }

        assertTrue(db.containers().any { it.name == "tg_shop" && it.hasChildren && !it.browsable })
        assertTrue(db.containers().none { it.name.startsWith("system") }, "system keyspaces are hidden")
        val table = db.containers(listOf("tg_shop")).single { it.name == "orders" }
        assertEquals("key ((customer), placed, id)", table.detail)
        assertTrue("CREATE TABLE tg_shop.orders" in db.definition(listOf("tg_shop", "orders")))
        assertTrue(db.canWrite(listOf("tg_shop", "orders")))

        // Paging state carries from page to page, and every row comes once.
        val path = listOf("tg_shop", "orders")
        val seen = mutableSetOf<JsonObject>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = db.page(path, "", cursor, 20)
            page.records.forEach { assertTrue(seen.add(JsonObject(it.key!! - CassandraClient.VersionField))) }
            cursor = page.cursor
            pages++
        } while (cursor != null)
        assertEquals(75, seen.size)
        assertTrue(pages >= 4)

        val partition = db.page(path, "customer = 1", null, 100).records
        assertEquals(25, partition.size)
        assertEquals("2024-01-25 10:00:00.000Z", partition.first().document["placed"]!!.jsonPrimitive.content, "clustering order is the table's")
        assertEquals(5, db.page(path, "customer = 1 LIMIT 5", null, 100).records.size)
        assertEquals(1, db.page(path, "SELECT customer, placed, id, note FROM tg_shop.orders WHERE customer = 2 LIMIT 1", null, 100).records.size)
        assertEquals(3, db.page(path, "note = 'it''s 7' ALLOW FILTERING", null, 100).records.size)

        // Edit one row: collections and the user type round-trip through Cassandra's own JSON.
        val row = partition[3]
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), row.document["tags"])
        assertEquals("Hanoi", (row.document["ship"] as JsonObject)["city"]!!.jsonPrimitive.content)
        assertNotNull(row.document["\"Mixed\""], "a case-sensitive column keeps its quotes")
        val edited = JsonObject(row.document + ("note" to JsonPrimitive("changed")) + ("lines" to buildJsonObject { put("pear", 2) }))
        val newKey = db.put(path, row.key, edited)
        val reread = db.page(path, "customer = 1", null, 100).records.single { (it.key!! - CassandraClient.VersionField) == (row.key!! - CassandraClient.VersionField) }
        assertEquals("changed", reread.document["note"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("pear", 2) }, reread.document["lines"])
        assertEquals(newKey, reread.key)
        // The page read before the edit is stale now.
        assertFailsWith<SQLException> { db.put(path, row.key, row.document) }
        assertFailsWith<SQLException> { db.delete(path, listOf(row.key!!)) }
        assertFailsWith<SQLException>("a key column can't change") { db.put(path, reread.key, JsonObject(reread.document + ("customer" to JsonPrimitive(9)))) }

        // A new row, without and then with a clash.
        val fresh = buildJsonObject {
            put("customer", 7)
            put("placed", "2025-05-05 05:05:05.000Z")
            put("id", "5b6962dd-3f90-4c93-8f61-eabfa4a803e2")
            put("note", "new")
            put("total", JsonPrimitive(null as String?))
        }
        val key = db.put(path, null, fresh)
        assertEquals(JsonPrimitive(7), key["customer"])
        assertEquals(1, db.page(path, "customer = 7", null, 10).records.size)
        assertFailsWith<SQLException> { db.put(path, null, fresh) }
        assertFailsWith<SQLException>("the key is required") { db.put(path, null, buildJsonObject { put("customer", 8) }) }

        // Deleting by the keys of a fresh page.
        val toDelete = db.page(path, "customer = 7", null, 10).records.map { it.key!! } + db.page(path, "customer = 2 LIMIT 2", null, 10).records.map { it.key!! }
        assertEquals(3L, db.delete(path, toDelete))
        assertEquals(0L, db.delete(path, toDelete), "already gone")
        assertEquals(23, db.page(path, "customer = 2", null, 100).records.size)

        // The console: rows for SELECT, text for the rest.
        val rows = db.execute("SELECT customer, total, tags, ship FROM tg_shop.orders WHERE customer = 0 LIMIT 3;") as ConsoleReply.Table
        assertEquals(listOf("customer", "total", "tags", "ship"), rows.rows.columns.map { it.name })
        assertEquals(3, rows.rows.rows.size)
        assertEquals("{'a','b'}", rows.rows.rows[0][2])
        assertTrue(db.execute("UPDATE tg_shop.orders SET note = 'x' WHERE customer = 0 AND placed = '2024-01-01 10:00:00+0000' AND id = 5b6962dd-3f90-4c93-8f61-eabfa4a803e2") is ConsoleReply.Text)
        assertFailsWith<Exception> { db.execute("SELECT * FROM tg_shop.nope") }
        db.execute("DROP KEYSPACE tg_shop")
    }

    @Test
    fun `a read-only profile only reads`() = server("it.cassandra", DatabaseType.Cassandra, readOnly = true) { db ->
        assertTrue(db.execute("SELECT release_version FROM system.local") is ConsoleReply.Table)
        assertFailsWith<ReadOnlyViolationException> { db.execute("CREATE KEYSPACE tg_ro WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}") }
        assertFalse(db.canWrite(listOf("system", "local")))
        assertNull(db.containers().firstOrNull { it.name == "system" })
    }
}

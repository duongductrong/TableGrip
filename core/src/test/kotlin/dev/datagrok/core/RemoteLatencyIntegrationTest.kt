package dev.datagrok.core

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.driver.Database
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import java.net.ServerSocket
import java.net.Socket
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Table viewer loads against a MySQL server behind a proxy adding [Rtt] ms per round trip, as a remote server would.
 * Opt-in: `-Pit.mysql=localhost:53306` (root password `pw`), with both MySQL and MariaDB Connector/J.
 */
class RemoteLatencyIntegrationTest {
    private val target = System.getProperty("it.mysql")
    private val proxy = target?.let { LatencyProxy(it, Rtt / 2) }
    private val namespace = Namespace("latency", null)

    @AfterTest fun close() = proxy?.close() ?: Unit

    private fun admin() = DriverManager.getConnection("jdbc:mysql://$target/?user=root&password=pw")

    private fun seed() =
        admin().use { c ->
            val s = c.createStatement()
            s.execute("DROP DATABASE IF EXISTS latency")
            s.execute("CREATE DATABASE latency")
            s.execute("USE latency")
            s.execute("CREATE TABLE tenant (id VARCHAR(64) PRIMARY KEY)")
            for (i in 1..20) {
                s.execute(
                    "CREATE TABLE t$i (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), status VARCHAR(20), created_at DATETIME, " +
                        "INDEX (status), FOREIGN KEY (tenant_id) REFERENCES tenant(id))"
                )
            }
            s.execute("CREATE TABLE label (id VARCHAR(64) PRIMARY KEY, name VARCHAR(100))")
            s.execute(
                "INSERT INTO label SELECT CONCAT('CRM-label-', UUID()), CONCAT('label ', n) " +
                    "FROM (WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 200) SELECT n FROM r) x"
            )
        }

    private fun connect(driver: DriverId): Database {
        val config =
            ConnectionConfig(
                id = "latency-$driver", name = "latency", type = DatabaseType.MySQL, host = "127.0.0.1", port = proxy!!.port,
                database = "latency", user = "root", driver = driver,
            )
        return runBlocking { Database.connect(config, password = "pw") }
    }

    private fun waitForPool() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val open = admin().use { c ->
                c.createStatement().executeQuery("SELECT COUNT(*) FROM information_schema.processlist WHERE db = 'latency'").use { it.next(); it.getInt(1) }
            }
            if (open >= 4) return Thread.sleep(4 * Rtt)
            Thread.sleep(100)
        }
        error("The pool never opened its connections")
    }

    private fun millis(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        runBlocking { block() }
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Statements the server received from other connections while [block] ran, from the general log. */
    private fun statements(block: () -> Unit): List<String> =
        admin().use { c ->
            val s = c.createStatement()
            s.execute("SET GLOBAL log_output = 'TABLE'")
            s.execute("TRUNCATE mysql.general_log")
            s.execute("SET GLOBAL general_log = 'ON'")
            block()
            s.execute("SET GLOBAL general_log = 'OFF'")
            s.executeQuery("SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log WHERE command_type = 'Query' AND thread_id <> CONNECTION_ID()")
                .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    @Test
    fun `table loads stay a few round trips even after closing many tabs`() {
        if (target == null) return
        seed()
        for (driver in listOf(DriverId.MySQL, DriverId.MariaDB)) {
            connect(driver).use { database ->
                val label = TableRef(namespace, "label")
                // The pool opens its connections in the background after connecting (as when the explorer connects);
                // wait for all of them, so their setup statements don't mix with what's measured.
                waitForPool()
                // Drivers look up a few session settings once per connection on first use; get that out of the way too.
                runBlocking {
                    coroutineScope {
                        repeat(8) {
                            launch(Dispatchers.Default) {
                                database.tableColumns(TableRef(namespace, "t20"))
                                database.fetchPage(label, null, emptyList(), 200, 0)
                            }
                        }
                    }
                }

                // A page is one statement: no session round trips around it.
                assertEquals(
                    listOf("SELECT * FROM `latency`.`label` LIMIT 201 OFFSET 0"),
                    statements { runBlocking { database.fetchPage(label, null, emptyList(), 200, 0) } },
                    "$driver",
                )

                // A table viewer reads only columns and key, not indexes and foreign keys (MariaDB Connector/J's run
                // SHOW CREATE TABLE for the table and each one it references).
                val reads = statements { runBlocking { assertEquals(listOf("id"), database.tableColumns(TableRef(namespace, "t1")).primaryKey) } }
                assertTrue(reads.size <= 2, "$driver: ${reads.size} statements: $reads")

                // Opening 20 tabs and closing all but 3: the closed tabs' reads that hadn't started never reach the server
                // (one in flight per pool connection may finish), so the remaining tab isn't queued behind them.
                var afterClosing = 0L
                val sent = statements {
                    afterClosing = millis {
                        coroutineScope {
                            val tabs = (1..20).map { i ->
                                launch(Dispatchers.Default) {
                                    val table = TableRef(namespace, "t$i")
                                    val columns = async { database.tableColumns(table) }
                                    database.fetchPage(table, null, emptyList(), 200, 0)
                                    columns.await()
                                }
                            }
                            delay(Rtt / 2)
                            tabs.drop(3).forEach { it.cancel() }
                            database.fetchPage(label, null, emptyList(), 200, 0)
                        }
                    }
                }
                val closedTabReads = sent.filter { sql -> TableName.findAll(sql).any { it.groupValues[1].toInt() > 3 } }
                assertTrue(closedTabReads.size <= 4, "$driver: closed tabs still sent ${closedTabReads.size} statements")
                assertTrue(afterClosing < 20 * Rtt, "$driver: the whole scenario took $afterClosing ms")
            }
        }
    }

    private companion object {
        const val Rtt = 40L
        val TableName = Regex("""\bt(\d+)\b""")
    }
}

/** Forwards TCP connections to [target], delaying each chunk by [oneWayMs] in each direction. */
private class LatencyProxy(target: String, private val oneWayMs: Long) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int = server.localPort

    init {
        val (host, targetPort) = target.split(":")
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket(host, targetPort.toInt())
                client.tcpNoDelay = true
                upstream.tcpNoDelay = true
                pipe(client, upstream)
                pipe(upstream, client)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pipe(from: Socket, to: Socket) =
        Thread {
            val buffer = ByteArray(65_536)
            runCatching {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    Thread.sleep(oneWayMs)
                    output.write(buffer, 0, n)
                    output.flush()
                }
            }
            runCatching { to.shutdownOutput() }
        }.apply { isDaemon = true }.start()

    override fun close() = server.close()
}

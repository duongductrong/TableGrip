package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.driver.etcd.EtcdClient
import dev.datagrok.core.driver.etcd.Glob
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.drivers.DriverResolution
import dev.datagrok.core.drivers.DriverSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * etcd over its HTTP API. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-etcd -p 52379:2379 quay.io/coreos/etcd:v3.5.17 etcd --advertise-client-urls http://0.0.0.0:2379 --listen-client-urls http://0.0.0.0:2379
 * -Pit.etcd=localhost:52379, and for authentication -Pit.etcdauth=localhost:52380 (a second server with user root, password Secret-2026, auth enabled)
 * ```
 */
class EtcdClientTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `globs become a prefix range and a matcher`() {
        assertEquals("user:", Glob("user:*").prefix)
        assertEquals("", Glob("*:config").prefix)
        assertEquals("a*b", Glob("a\\*b?").prefix)
        assertTrue(Glob("svc/*/port").matches("svc/api/port"))
        assertFalse(Glob("svc/*/port").matches("svc/api/host"))
        assertTrue(Glob("node[12]").matches("node2") && !Glob("node[12]").matches("node3"))
        assertContentEquals(byteArrayOf('a'.code.toByte(), 'c'.code.toByte()), EtcdClient.prefixEnd("ab".toByteArray()))
        assertContentEquals(byteArrayOf(0), EtcdClient.prefixEnd(ByteArray(0)), "no prefix ranges over everything")
        assertEquals("7587898534549041416", EtcdClient.leaseId("694da183c780b108"))
    }

    @Test
    fun `etcd needs no download and reads are told from writes`() {
        assertEquals(DriverResolution.Ready(DriverId.Etcd, null, DriverSource.BuiltIn), drivers.resolve(DriverId.Etcd))
        listOf("get a --prefix", "member list", "endpoint status", "lease timetolive 1f", "version").forEach {
            assertTrue(KeyValueStore.isEtcdRead(KeyValueStore.splitArguments(it)), it)
        }
        listOf("put a b", "del a --prefix", "lease grant 60", "lease revoke 1f", "defrag").forEach {
            assertFalse(KeyValueStore.isEtcdRead(KeyValueStore.splitArguments(it)), it)
        }
    }

    private fun server(property: String, user: String = "", password: String? = null, readOnly: Boolean = false, test: suspend (KeyValueConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            val config = ConnectionConfig(id = property, name = property, type = DatabaseType.Etcd, host = host, port = port.toInt(), user = user, readOnly = readOnly)
            KeyValueStore.connect(config, password, drivers = drivers).use { test(it) }
        }

    @Test
    fun `keys page, edit, expire and rename on a real server`() = server("it.etcd") { etcd ->
        assertTrue(etcd.server().startsWith("etcd 3.5"), etcd.server())
        etcd.command(0, listOf("del", "dg/", "--prefix"))
        repeat(250) { etcd.edit(0, "dg/svc/$it/port", KeyEdit.SetText("${8000 + it}")) }
        etcd.edit(0, "dg/config", KeyEdit.SetText("{\"debug\": true}"))
        assertTrue((etcd.keyspaces().single().keys ?: 0) >= 251)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = etcd.scan(0, "dg/svc/*/port", cursor, count = 40)
            seen += page.keys.map { it.name }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals(250, seen.toSet().size)
        assertEquals(250, seen.size, "no key twice across pages")
        assertEquals(listOf("dg/config"), etcd.scan(0, "dg/c*", null).keys.map { it.name })

        assertEquals("{\"debug\": true}", (etcd.read(0, "dg/config") as KeyValue.Text).value)
        assertNull(etcd.read(0, "dg/missing"))

        // A TTL is a lease; editing keeps it; it can be removed.
        etcd.expire(0, "dg/config", 120_000)
        assertTrue((etcd.read(0, "dg/config")!!.ttlMillis ?: 0) > 100_000)
        etcd.edit(0, "dg/config", KeyEdit.SetText("{}"))
        assertTrue(etcd.read(0, "dg/config")!!.ttlMillis != null, "editing keeps the lease")
        etcd.expire(0, "dg/config", null)
        assertNull(etcd.read(0, "dg/config")!!.ttlMillis)

        etcd.rename(0, "dg/config", "dg/settings")
        assertNull(etcd.read(0, "dg/config"))
        assertFailsWith<IllegalStateException> { etcd.rename(0, "dg/settings", "dg/svc/1/port") }
        assertEquals(2L, etcd.delete(0, listOf("dg/settings", "dg/svc/0/port", "dg/missing")))
        assertFailsWith<IllegalArgumentException> { etcd.edit(0, "dg/x", KeyEdit.SetField("a", "b")) }

        // etcdctl-style console.
        assertEquals("OK", etcd.command(0, listOf("put", "dg/a", "1")))
        assertEquals("dg/a\n1", etcd.command(0, listOf("get", "dg/a")))
        assertEquals("dg/svc/1/port\ndg/svc/10/port", etcd.command(0, listOf("get", "dg/svc/", "--prefix", "--keys-only", "--limit=2")))
        val grant = etcd.command(0, listOf("lease", "grant", "60"))
        assertTrue(Regex("lease [0-9a-f]+ granted with TTL\\(60s\\)").matches(grant), grant)
        assertTrue(etcd.command(0, listOf("lease", "timetolive", grant.split(" ")[1])).contains("remaining"))
        assertTrue(etcd.command(0, listOf("member", "list")).contains("default"))
        assertTrue(etcd.command(0, listOf("endpoint", "status")).contains("version 3.5"))
        assertTrue(etcd.command(0, listOf("frobnicate")).startsWith("Error:"))
        assertEquals("249", etcd.command(0, listOf("del", "dg/svc/", "--prefix")))
        etcd.command(0, listOf("del", "dg/", "--prefix"))
    }

    @Test
    fun `read-only profiles refuse writes`() = server("it.etcd", readOnly = true) { etcd ->
        assertFailsWith<ReadOnlyViolationException> { etcd.edit(0, "dg/ro", KeyEdit.SetText("x")) }
        assertFailsWith<ReadOnlyViolationException> { etcd.command(0, listOf("put", "dg/ro", "x")) }
        assertEquals("", etcd.command(0, listOf("get", "dg/ro")))
    }

    @Test
    fun `users authenticate for a token`() {
        server("it.etcdauth", user = "root", password = "Secret-2026") { etcd ->
            assertEquals("OK", etcd.command(0, listOf("put", "dg/auth", "yes")))
            assertEquals("yes", (etcd.read(0, "dg/auth") as KeyValue.Text).value)
        }
        if (System.getProperty("it.etcdauth") == null) return
        val failure = runCatching { server("it.etcdauth", user = "root", password = "wrong") {} }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("authentication failed"), failure?.message)
    }
}

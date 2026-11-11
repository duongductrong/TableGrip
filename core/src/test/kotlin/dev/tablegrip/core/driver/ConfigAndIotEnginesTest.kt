package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.timeseries.TdengineClient
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Nacos and TDengine over their HTTP APIs. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-nacos -p 58848:8848 -e MODE=standalone nacos/nacos-server:v2.4.3
 * docker run -d --name dg-it-tdengine -p 56041:6041 tdengine/tdengine:3.3.5.0
 * -Pit.nacos=localhost:58848 -Pit.tdengine=localhost:56041
 * ```
 */
class ConfigAndIotEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `tdengine reads are told from writes`() {
        assertTrue(TdengineClient.isReadSql("SELECT * FROM power.meters"))
        assertTrue(TdengineClient.isReadSql("SHOW DATABASES;"))
        assertFalse(TdengineClient.isReadSql("INSERT INTO power.d1 VALUES (now, 1)"))
        assertFalse(TdengineClient.isReadSql("SELECT 1; DROP DATABASE power"))
        assertEquals("`power`.`meters`", TdengineClient.name(listOf("power", "meters")))
    }

    private fun server(property: String, type: DatabaseType, test: suspend (RecordConnection) -> Unit) =
        runBlocking<Unit> {
            val (host, port) = System.getProperty(property)?.split(":") ?: return@runBlocking
            RecordStore.connect(ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt()), null, drivers = drivers).use { test(it) }
        }

    @Test
    fun `nacos lists configurations and publishes with md5 checks`() = server("it.nacos", DatabaseType.Nacos) { nacos ->
        assertTrue(nacos.server().startsWith("Nacos 2."), nacos.server())
        val path = listOf("public")
        nacos.page(path, "tg-", null, 100).records.mapNotNull { it.key }.let { if (it.isNotEmpty()) nacos.delete(path, it) }
        repeat(25) { nacos.put(path, null, buildJsonObject { put("dataId", "tg-app-$it.yaml"); put("group", if (it % 5 == 0) "TG_EDGE" else "DEFAULT_GROUP"); put("type", "yaml"); put("content", "port: ${8000 + it}") }) }
        assertTrue(nacos.containers().any { it.name == "public" })
        val first = nacos.page(path, "tg-app", null, 20)
        assertEquals(25L, first.total)
        assertEquals(5, nacos.page(path, "tg-app", first.cursor, 20).records.size)
        assertEquals(5, nacos.page(path, "TG_EDGE:tg-app", null, 50).records.size)

        val record = nacos.page(path, "tg-app-1.yaml", null, 5).records.single()
        nacos.put(path, record.key, JsonObject(record.document + ("content" to JsonPrimitive("port: 9999"))))
        assertEquals("port: 9999", nacos.page(path, "tg-app-1.yaml", null, 5).records.single().document["content"]!!.jsonPrimitive.content)
        assertFailsWith<IOException>("a stale md5 is refused") { nacos.put(path, record.key, record.document) }
        assertFailsWith<IOException> { nacos.put(path, null, buildJsonObject { put("dataId", "tg-app-2.yaml"); put("content", "x") }) }
        val doomed = nacos.page(path, "TG_EDGE:tg-app", null, 50).records.map { it.key!! }
        assertEquals(5L, nacos.delete(path, doomed))
        assertEquals(0L, nacos.delete(path, doomed))
        assertTrue(nacos.execute("GET /v1/cs/configs?search=blur&dataId=tg-*&group=&pageNo=1&pageSize=5") is ConsoleReply.Table)
        nacos.delete(path, nacos.page(path, "tg-app", null, 100).records.map { it.key!! })
    }

    @Test
    fun `tdengine lists supertables and pages rows`() = server("it.tdengine", DatabaseType.TDengine) { td ->
        assertTrue(td.server().startsWith("TDengine 3."), td.server())
        td.execute("DROP DATABASE IF EXISTS tg_power")
        td.execute("CREATE DATABASE tg_power")
        td.execute("CREATE STABLE tg_power.meters (ts TIMESTAMP, current FLOAT, voltage INT) TAGS (location VARCHAR(32), group_id INT)")
        td.execute("CREATE TABLE tg_power.notes (ts TIMESTAMP, line VARCHAR(20))")
        val values = (0 until 30).joinToString(" ") { "(now - ${it}s, ${it}.5, ${200 + it})" }
        td.execute("INSERT INTO tg_power.d1 USING tg_power.meters TAGS ('Hanoi', 1) VALUES $values")
        td.execute("INSERT INTO tg_power.d2 USING tg_power.meters TAGS ('Hue', 2) VALUES (now, 1.5, 220)")
        assertTrue(td.containers().any { it.name == "tg_power" && it.hasChildren })
        assertEquals(listOf("meters" to "supertable", "notes" to "table"), td.containers(listOf("tg_power")).map { it.name to it.kind })
        assertTrue("CREATE STABLE" in td.definition(listOf("tg_power", "meters")))
        val path = listOf("tg_power", "meters")
        val first = td.page(path, "", null, 20)
        assertEquals(20, first.records.size)
        assertEquals("ts", first.records.first().document.keys.first())
        assertEquals(11, td.page(path, "", first.cursor, 20).records.size)
        assertEquals(30, td.page(path, "location = 'Hanoi'", null, 50).records.size)
        val count = td.execute("SELECT count(*) FROM tg_power.meters") as ConsoleReply.Table
        assertEquals("31", count.rows.rows.single().single())
        assertFalse(td.canWrite(path))
        td.execute("DROP DATABASE tg_power")
    }
}

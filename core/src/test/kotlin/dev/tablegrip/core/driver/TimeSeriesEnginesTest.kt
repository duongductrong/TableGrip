package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.driver.timeseries.InfluxClient
import dev.tablegrip.core.driver.timeseries.PromClient
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * InfluxDB 2 and VictoriaMetrics, browsed as records. The server tests are skipped unless the properties are passed:
 * ```
 * docker run -d --name dg-it-influx -p 58086:8086 -e DOCKER_INFLUXDB_INIT_MODE=setup -e DOCKER_INFLUXDB_INIT_USERNAME=admin \
 *   -e DOCKER_INFLUXDB_INIT_PASSWORD=tablegrip123 -e DOCKER_INFLUXDB_INIT_ORG=tg -e DOCKER_INFLUXDB_INIT_BUCKET=metrics \
 *   -e DOCKER_INFLUXDB_INIT_ADMIN_TOKEN=tablegrip-token influxdb:2.7.11
 * docker run -d --name dg-it-vm -p 58428:8428 victoriametrics/victoria-metrics:v1.108.1 -retentionPeriod=1y
 * docker run -d --name dg-it-prom -p 59090:9090 prom/prometheus:v3.1.0
 * -Pit.influxdb=localhost:58086 -Pit.victoriametrics=localhost:58428 -Pit.prometheus=localhost:59090
 * ```
 */
class TimeSeriesEnginesTest {
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)

    @Test
    fun `flux csv tables become rows with their types`() {
        val csv = "#datatype,string,long,dateTime:RFC3339,double,string,boolean\n,result,table,_time,_value,host,ok\n,_result,0,2026-01-01T00:00:00Z,1.5,\"a,b\",true\n\n" +
            "#datatype,string,long,string\n,result,table,name\n,_result,0,\"say \"\"hi\"\"\"\n"
        val rows = InfluxClient.FluxCsv.parse(csv)
        assertEquals(2, rows.size)
        assertEquals("""{"result":"_result","table":0,"_time":"2026-01-01T00:00:00Z","_value":1.5,"host":"a,b","ok":true}""", rows[0].toString())
        assertEquals("say \"hi\"", rows[1]["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `flux that writes is told apart`() {
        assertTrue(InfluxClient.isReadFlux("from(bucket: \"a\") |> range(start: -1h) |> filter(fn: (r) => r.to == \"x\")"))
        assertFalse(InfluxClient.isReadFlux("from(bucket: \"a\") |> range(start: -1h) |> to(bucket: \"b\")"))
        assertFalse(InfluxClient.isReadFlux("import \"experimental\"\nfrom(bucket: \"a\") |> experimental.to(bucket: \"b\")"))
        assertFalse(InfluxClient.isReadFlux(""))
        assertEquals("up", PromClient.selector("up"))
        assertEquals("{__name__=\"weird-name\"}", PromClient.selector("weird-name"))
    }

    @Test
    fun `influxdb lists buckets and measurements and pages points`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.influxdb")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "influx", name = "influx", type = DatabaseType.InfluxDB, host = host, port = port.toInt(), database = "tg")
        // Points to read: written with line protocol, as collectors do.
        val now = System.currentTimeMillis() / 1000
        val lines = (0 until 60).joinToString("\n") { i -> "tg_cpu,host=web-${i % 3} usage=${i}.5,idle=${100 - i}i ${now - i * 10}" }
        val api = RestClient("InfluxDB", config, host, port.toInt(), authorization = "Token tablegrip-token")
        // An earlier run's points go first, so the counts below hold however often this runs.
        api.send("POST", "/api/v2/delete?org=tg&bucket=metrics", """{"start": "1970-01-01T00:00:00Z", "stop": "2100-01-01T00:00:00Z", "predicate": "_measurement=\"tg_cpu\""}""")
        val write = api.send("POST", "/api/v2/write?org=tg&bucket=metrics&precision=s", lines, "text/plain")
        assertTrue(write.ok, write.body)

        RecordStore.connect(config, "tablegrip-token", drivers = drivers).use { influx ->
            assertTrue(influx.server().startsWith("InfluxDB 2.7"), influx.server())
            assertTrue(influx.containers().any { it.name == "metrics" && it.hasChildren })
            assertTrue(influx.containers().none { it.name.startsWith("_") })
            assertTrue(influx.containers(listOf("metrics")).any { it.name == "tg_cpu" })
            val definition = influx.definition(listOf("metrics", "tg_cpu"))
            assertTrue("\"host\"" in definition && "\"usage\"" in definition, definition)

            val path = listOf("metrics", "tg_cpu")
            val first = influx.page(path, "", null, 25)
            assertEquals(25, first.records.size)
            assertEquals(listOf("_time", "host", "idle", "usage"), first.records.first().document.keys.toList())
            assertNull(first.records.first().key, "points are read-only")
            val second = influx.page(path, "", first.cursor, 25)
            val third = influx.page(path, "", second.cursor, 25)
            assertEquals(10, third.records.size)
            assertNull(third.cursor)
            assertEquals(20, influx.page(path, "|> filter(fn: (r) => r.host == \"web-1\")", null, 100).records.size)
            assertFalse(influx.canWrite(path))

            val reply = influx.execute("from(bucket: \"metrics\") |> range(start: -1h) |> filter(fn: (r) => r._measurement == \"tg_cpu\" and r._field == \"idle\") |> group() |> count()") as ConsoleReply.Table
            assertEquals("60", reply.rows.rows.single()[reply.rows.columns.indexOfFirst { it.name == "_value" }])
            assertFailsWith<IOException> { influx.execute("from(bucket: \"nope\") |> range(start: -1h)") }
        }
        val wrong = ConnectionConfig(id = "w", name = "w", type = DatabaseType.InfluxDB, host = host, port = port.toInt(), database = "tg")
        assertFailsWith<IOException> { RecordStore.connect(wrong, "wrong-token", drivers = drivers) }
        val readOnly = config.copy(readOnly = true)
        RecordStore.connect(readOnly, "tablegrip-token", drivers = drivers).use {
            assertFailsWith<ReadOnlyViolationException> { it.execute("from(bucket: \"metrics\") |> range(start: -1h) |> to(bucket: \"metrics\")") }
        }
    }

    @Test
    fun `victoriametrics lists metrics and evaluates promql`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.victoriametrics")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "vm", name = "vm", type = DatabaseType.VictoriaMetrics, host = host, port = port.toInt())
        // VictoriaMetrics hides the last 30 seconds from queries (-search.latencyOffset), so the newest sample is older.
        val now = System.currentTimeMillis() - 60_000
        // Samples of the last ten minutes for three series, in VictoriaMetrics' JSON line import format.
        val import = (0 until 3).joinToString("\n") { s ->
            """{"metric":{"__name__":"tg_requests_total","job":"api","instance":"node-$s"},"values":[${(0 until 20).joinToString { "${it * 10 + s}" }}],"timestamps":[${(0 until 20).joinToString { "${now - (19 - it) * 30_000}" }}]}"""
        }
        val rest = RestClient("VictoriaMetrics", config, host, port.toInt())
        rest.send("POST", "/api/v1/admin/tsdb/delete_series?match[]=tg_requests_total")
        assertTrue(rest.send("POST", "/api/v1/import", import, "application/json").ok)
        rest.send("GET", "/internal/force_flush")

        RecordStore.connect(config, null, drivers = drivers).use { vm ->
            assertTrue(vm.server().startsWith("VictoriaMetrics"), vm.server())
            assertTrue(vm.containers().any { it.name == "tg_requests_total" })
            assertTrue("\"instance\"" in vm.definition(listOf("tg_requests_total")))

            val path = listOf("tg_requests_total")
            val raw = vm.page(path, "", null, 50)
            assertEquals(60L, raw.total, "every raw sample of the last hour")
            assertEquals(50, raw.records.size)
            assertEquals(10, vm.page(path, "", raw.cursor, 50).records.size)
            assertEquals(listOf("time", "__name__", "instance", "job", "value"), raw.records.first().document.keys.toList())

            val series = vm.page(path, "sum by (instance) (tg_requests_total)", null, 50).records
            assertEquals(3, series.size)
            assertEquals(JsonPrimitive(192), series.single { it.document["instance"]!!.jsonPrimitive.content == "node-2" }.document["value"]!!.jsonPrimitive.content.toInt().let(::JsonPrimitive))
            val reply = vm.execute("count(tg_requests_total)") as ConsoleReply.Table
            assertEquals("3", reply.rows.rows.single().last())
            assertFailsWith<IOException> { vm.execute("sum(") }
            assertTrue(vm.isRead("anything"))
            assertFalse(vm.canWrite(path))
        }
    }

    @Test
    fun `prometheus lists its metrics and evaluates promql`() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.prometheus")?.split(":") ?: return@runBlocking
        val config = ConnectionConfig(id = "prom", name = "prom", type = DatabaseType.Prometheus, host = host, port = port.toInt())
        RecordStore.connect(config, null, drivers = drivers).use { prom ->
            assertTrue(prom.server().startsWith("Prometheus (3."), prom.server())
            assertTrue(prom.containers().any { it.name == "prometheus_build_info" })
            val up = prom.page(listOf("up"), "up", null, 10).records
            assertEquals("prometheus", up.single().document["job"]!!.jsonPrimitive.content)
            assertTrue(prom.page(listOf("up"), "", null, 10).records.isNotEmpty(), "the last hour's raw samples")
            assertTrue(prom.execute("count(up)") is ConsoleReply.Table)
        }
    }
}

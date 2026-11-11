package dev.tablegrip.core.completion

import dev.tablegrip.core.connection.EngineFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeSeriesCompletersTest {
    private val prom =
        FakeCatalog(
            containers = mapOf(emptyList<String>() to listOf("http_requests_total", "node_cpu_seconds_total")),
            fields = mapOf(listOf("http_requests_total") to listOf("job", "status", "instance")),
        )
    private val influx =
        FakeCatalog(
            containers = mapOf(emptyList<String>() to listOf("metrics", "logs"), listOf("metrics") to listOf("cpu", "mem")),
            fields = mapOf(listOf("metrics", "cpu") to listOf("host", "usage_user")),
        )

    @Test
    fun `PromQL metrics and functions, labels in selectors and groupings`() {
        val start = PromQlCompleter.labels("|", prom)
        assertTrue(start.containsAll(listOf("http_requests_total", "rate()", "sum()")), "$start")
        assertEquals("rate(|)", PromQlCompleter.accept("ra|", prom, "rate()"))
        assertEquals(listOf("job", "status", "instance"), PromQlCompleter.labels("http_requests_total{|", prom))
        assertEquals(listOf("job", "status", "instance"), PromQlCompleter.labels("sum by (|) (rate(http_requests_total[5m]))", prom))
        assertTrue(PromQlCompleter.labels("rate(http_requests_total[|", prom).contains("5m"))
        assertTrue(PromQlCompleter.labels("sum(rate(http_requests_total[5m])) |", prom).containsAll(listOf("by", "without")))
    }

    @Test
    fun `Flux sources, pipes, buckets, measurements and fields`() {
        assertTrue(FluxCompleter.labels("‸", influx).contains("from()"))
        assertEquals("from(bucket: \"‸\")", FluxCompleter.accept("fr‸", influx))
        assertEquals(listOf("metrics", "logs"), FluxCompleter.labels("from(bucket: \"‸\")", influx))
        assertTrue(FluxCompleter.labels("from(bucket: \"metrics\")\n  |> ‸", influx).containsAll(listOf("range()", "filter()", "aggregateWindow()", "to()")))
        assertFalse(FluxCompleter.labels("from(bucket: \"metrics\")\n  |> ‸", influx, isRead = { true }).contains("to()"))
        assertEquals(listOf("cpu", "mem"), FluxCompleter.labels("from(bucket: \"metrics\") |> filter(fn: (r) => r._measurement == \"‸\")", influx))
        assertEquals(
            listOf("host", "usage_user"),
            FluxCompleter.labels("from(bucket: \"metrics\") |> filter(fn: (r) => r._measurement == \"cpu\" and r._field == \"‸\")", influx),
        )
        assertTrue(FluxCompleter.labels("from(bucket: \"metrics\") |> filter(fn: (r) => r.‸", influx).containsAll(listOf("_measurement", "_value")))
    }

    @Test
    fun `SQL consoles complete databases, tables and columns, and only reads for a read-only profile`() {
        val tdengine = StatementCompleter.of(EngineFamily.TDengine)!!
        val catalog =
            FakeCatalog(
                containers = mapOf(emptyList<String>() to listOf("power"), listOf("power") to listOf("meters")),
                fields = mapOf(listOf("power", "meters") to listOf("ts", "current", "voltage")),
            )
        assertTrue(tdengine.labels("SELECT * FROM |", catalog).contains("power"))
        assertEquals(listOf("meters"), tdengine.labels("SELECT * FROM power.|", catalog))
        assertTrue(tdengine.labels("SELECT | FROM power.meters", catalog).containsAll(listOf("ts", "current", "voltage")))
        val read = tdengine.labels("|", catalog, isRead = { it.startsWith("SELECT") || it.startsWith("SHOW") })
        assertTrue("SELECT" in read && "INSERT INTO" !in read && "DELETE FROM" !in read, "$read")
        val sqlite = StatementCompleter.of(EngineFamily.SqliteHttp)!!
        assertEquals(listOf("orders"), sqlite.labels("SELECT * FROM or|", FakeCatalog(containers = mapOf(emptyList<String>() to listOf("orders")))))
    }
}

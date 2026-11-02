package dev.tablegrip.core.chart

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SqlDialect
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartQueryTest {
    private val byMonth = ChartSpec(ChartType.Line, "created_at", listOf(Measure("total", Aggregate.Sum)), series = "region", bucket = TimeBucket.Month)

    @Test
    fun `wraps the query in a group by, with a spec for its result`() {
        val aggregate = ChartQuery.aggregate(SqlDialect.of(DatabaseType.PostgreSQL), "SELECT * FROM orders WHERE total > 0;", byMonth)!!
        assertEquals(
            """
            SELECT date_trunc('month', t."created_at") AS "created_at", t."region" AS "region", SUM(t."total") AS "sum(total)"
            FROM (
            SELECT * FROM orders WHERE total > 0
            ) t
            GROUP BY date_trunc('month', t."created_at"), t."region"
            ORDER BY date_trunc('month', t."created_at"), t."region"
            """.trimIndent(),
            aggregate.sql,
        )
        assertEquals(ChartSpec(ChartType.Line, "created_at", listOf(Measure("sum(total)")), series = "region"), aggregate.spec)
    }

    @Test
    fun `only single selects that aggregate, on engines that can round dates`() {
        val postgres = SqlDialect.of(DatabaseType.PostgreSQL)
        assertNull(ChartQuery.aggregate(postgres, "SELECT 1; SELECT 2", byMonth))
        assertNull(ChartQuery.aggregate(postgres, "DELETE FROM orders", byMonth))
        assertNull(ChartQuery.aggregate(postgres, "SELECT * FROM orders", byMonth.copy(y = listOf(Measure("total")), bucket = null)))
        assertNull(ChartQuery.aggregate(SqlDialect.Generic, "SELECT * FROM orders", byMonth))
    }

    @Test
    fun `every dialect rounds dates down to the bucket`() {
        val dialects = listOf(SqlDialect.PostgreSQL, SqlDialect.MySQL, SqlDialect.SQLite, SqlDialect.SqlServer, SqlDialect.ClickHouse, SqlDialect.DuckDB)
        for (dialect in dialects) for (bucket in TimeBucket.entries) {
            val expression = dialect.timeBucket("x", bucket)
            assertEquals(true, expression?.contains("x"), "$dialect $bucket")
        }
    }

    @Test
    fun `the aggregate runs on SQLite and buckets match the client's`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE orders (created_at TEXT, region TEXT, total INTEGER)")
                statement.execute(
                    "INSERT INTO orders VALUES ('2024-08-15 13:45:00', 'north', 1), ('2024-08-18 09:00:00', 'north', 2), " +
                        "('2024-08-19 09:00:00', 'south', 4), ('2024-11-02 00:00:00', 'south', 8)"
                )
            }
            val dialect = SqlDialect.SQLite
            val expected =
                mapOf(
                    TimeBucket.Hour to "2024-08-15 13:00:00",
                    TimeBucket.Day to "2024-08-15",
                    TimeBucket.Week to "2024-08-12",
                    TimeBucket.Month to "2024-08-01",
                    TimeBucket.Quarter to "2024-07-01",
                    TimeBucket.Year to "2024-01-01",
                )
            for ((bucket, first) in expected) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT ${dialect.timeBucket("'2024-08-15 13:45:00'", bucket)}").use { rs ->
                        rs.next()
                        assertEquals(first, rs.getString(1), "$bucket")
                    }
                }
            }
            val aggregate = ChartQuery.aggregate(dialect, "SELECT * FROM orders", byMonth.copy(series = null))!!
            val rows =
                connection.createStatement().use { statement ->
                    statement.executeQuery(aggregate.sql).use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2))) } }
                }
            assertEquals(listOf(listOf("2024-08-01", "7"), listOf("2024-11-01", "8")), rows)
            // The result charts as dates, drawn as they are.
            val profiles = ColumnProfiler.profile(listOf(dev.tablegrip.core.driver.ResultColumn("created_at", "", Types.VARCHAR), dev.tablegrip.core.driver.ResultColumn("sum(total)", "", Types.INTEGER)), rows)
            assertEquals(emptyList(), aggregate.spec.validate(profiles))
            assertEquals(listOf(7.0, 8.0), ChartData.build(rows, profiles, aggregate.spec).points.map { it.y })
        }
    }
}

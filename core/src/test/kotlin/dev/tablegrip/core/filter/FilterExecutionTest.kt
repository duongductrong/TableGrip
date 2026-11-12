package dev.tablegrip.core.filter

import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.metadata.TableRef
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Compiled filters run against a real (SQLite) database, through the same paging and counting the table viewer uses. */
class FilterExecutionTest {
    private val config = ConnectionConfig(id = UUID.randomUUID().toString(), name = "test", type = DatabaseType.SQLite, database = ":memory:")
    private val database = runBlocking { Database.connect(config, password = null) }
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 28, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @AfterTest fun close() = database.close()

    private val table: TableRef =
        runBlocking {
            database.openSession().use { session ->
                session.execute(
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, total NUMERIC, paid BOOLEAN, placed DATE, created_at DATETIME)"
                )
                session.execute(
                    """
                    INSERT INTO orders VALUES
                      (1, 'Alice',     10.5, 1, '2026-10-28', '2026-10-28 09:15:00'),
                      (2, 'bob',       99,   0, '2026-10-20', '2026-10-20 23:59:59'),
                      (3, 'Carol 50%', 250,  1, '2026-09-30', '2026-09-30 00:00:00'),
                      (4, NULL,        0,    0, NULL,         NULL),
                      (5, '',          7.25, 1, '2026-10-27', '2026-10-27 18:00:00')
                    """.trimIndent()
                )
            }
            TableRef(database.namespaces().single(), "orders")
        }

    private var ids = 0L

    private fun cond(column: String, op: FilterOperator, value: String = "", value2: String = "", caseSensitive: Boolean = false) =
        FilterCondition(++ids, column, op, value, value2, caseSensitive)

    private fun ids(combinator: Combinator = Combinator.All, vararg nodes: FilterNode): List<Long> = runBlocking {
        val details = database.tableDetails(table)
        val columns = details.columns.map { FilterColumn(it.name, it.typeName, ValueKind.of(it.typeName, it.jdbcType, it.size)) }
        val compiled = FilterCompiler(database.dialect, columns, clock).compile(FilterGroup(0, combinator, nodes.toList()))
        assertEquals(emptyMap(), compiled.issues)
        val page = database.fetchPage(table, compiled.filter, listOf(SortColumn("id", SortDirection.Asc)), limit = 100, offset = 0)
        assertEquals(page.rows.size.toLong(), database.countRows(table, compiled.filter))
        page.rows.map { it[0]!!.toLong() }
    }

    private fun ids(vararg nodes: FilterNode) = ids(Combinator.All, *nodes)

    @Test
    fun `classifies the declared column types`() = runBlocking {
        val kinds = database.tableDetails(table).columns.associate { it.name to ValueKind.of(it.typeName, it.jdbcType, it.size) }
        assertEquals(
            mapOf(
                "id" to ValueKind.Number,
                "customer" to ValueKind.Text,
                "total" to ValueKind.Number,
                "paid" to ValueKind.Boolean,
                "placed" to ValueKind.Date,
                "created_at" to ValueKind.DateTime,
            ),
            kinds,
        )
    }

    @Test
    fun `text matching`() {
        assertEquals(listOf(1L), ids(cond("customer", FilterOperator.Contains, "ALI")))
        assertEquals(emptyList(), ids(cond("customer", FilterOperator.Contains, "ALI", caseSensitive = true)))
        assertEquals(listOf(3L), ids(cond("customer", FilterOperator.Contains, "50%")))
        assertEquals(listOf(2L), ids(cond("customer", FilterOperator.StartsWith, "b", caseSensitive = true)))
        assertEquals(listOf(4L, 5L), ids(cond("customer", FilterOperator.IsEmpty)))
        assertEquals(listOf(1L, 2L, 4L), ids(cond("customer", FilterOperator.In, "Alice, bob, NULL")))
        assertEquals(listOf(1L, 3L), ids(cond("customer", FilterOperator.Like, "_%l%")))
    }

    @Test
    fun `numbers and booleans`() {
        assertEquals(listOf(2L, 3L), ids(cond("total", FilterOperator.Greater, "10.5")))
        assertEquals(listOf(1L, 2L), ids(cond("total", FilterOperator.Between, "10", "100")))
        assertEquals(listOf(1L, 3L, 5L), ids(cond("paid", FilterOperator.IsTrue)))
        assertEquals(listOf(2L), ids(cond("id", FilterOperator.StartsWith, "2")))
    }

    @Test
    fun `dates and date-times`() {
        assertEquals(listOf(1L), ids(cond("created_at", FilterOperator.Today)))
        assertEquals(listOf(5L), ids(cond("placed", FilterOperator.Yesterday)))
        assertEquals(listOf(1L, 2L, 5L), ids(cond("created_at", FilterOperator.ThisMonth)))
        assertEquals(listOf(2L), ids(cond("created_at", FilterOperator.Equals, "2026-10-20")))
        assertEquals(listOf(1L, 5L), ids(cond("placed", FilterOperator.LastDays, "7")))
        assertEquals(listOf(1L, 5L), ids(cond("created_at", FilterOperator.GreaterOrEqual, "today-1d")))
        assertEquals(listOf(2L, 3L), ids(cond("created_at", FilterOperator.LessOrEqual, "2026-10-20")))
        assertEquals(listOf(2L, 3L), ids(cond("placed", FilterOperator.In, "2026-10-20, 2026-09-30")))
    }

    @Test
    fun `groups and raw SQL`() {
        val cheapOrUnpaid = FilterGroup(100, Combinator.Any, listOf(cond("total", FilterOperator.Less, "10"), cond("paid", FilterOperator.IsFalse)))
        assertEquals(listOf(2L, 4L, 5L), ids(cheapOrUnpaid))
        assertEquals(listOf(2L), ids(cheapOrUnpaid, RawCondition(101, "length(customer) = 3")))
        assertEquals(listOf(1L, 3L), ids(FilterGroup(102, children = listOf(cheapOrUnpaid), negated = true)))
    }

    @Test
    fun `charts group every matching row on the server, with the filter's parameters bound`() = runBlocking {
        val details = database.tableDetails(table)
        val columns = details.columns.map { FilterColumn(it.name, it.typeName, ValueKind.of(it.typeName, it.jdbcType, it.size)) }
        val filter = FilterCompiler(database.dialect, columns, clock).compile(FilterGroup(0, children = listOf(cond("total", FilterOperator.GreaterOrEqual, "10")))).filter!!
        assertEquals(1, filter.parameters.size)
        val spec = ChartSpec(ChartType.Bar, "paid", listOf(Measure("total", Aggregate.Sum)))
        val chart = database.aggregateChart(table, filter, spec, maxRows = 100)!!
        assertEquals(ChartSpec(ChartType.Bar, "paid", listOf(Measure("sum(total)"))), chart.spec)
        assertEquals(listOf("0" to 99.0, "1" to 260.5), chart.rows.rows.map { it[0]!! to it[1]!!.toDouble() })
        assertNull(database.aggregateChart(table, filter, spec.copy(type = ChartType.Scatter, y = listOf(Measure("total"))), maxRows = 100))
    }
}

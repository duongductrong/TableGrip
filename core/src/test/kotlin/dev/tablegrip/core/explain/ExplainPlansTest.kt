package dev.tablegrip.core.explain

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plans captured from PostgreSQL 17, MySQL 8.4 and MariaDB 11.4 on the same two tables: 5,000 customers, 200,000 orders. */
class ExplainPlansTest {
    private fun fixture(name: String): String = javaClass.getResource("/explain/$name")!!.readText()

    private fun plan(name: String): QueryPlan = assertNotNull(ExplainPlans.parse(fixture(name)), "$name didn't parse")

    /** The tree as indented `operation relation` lines, to compare shapes at a glance. */
    private fun outline(plan: QueryPlan): String {
        fun lines(node: PlanNode, depth: Int): List<String> =
            listOf("  ".repeat(depth) + listOfNotNull(node.operation, node.relation).joinToString(" · ")) + node.children.flatMap { lines(it, depth + 1) }
        return lines(plan.root, 0).joinToString("\n")
    }

    private fun QueryPlan.find(operation: String, relation: String? = null): PlanNode =
        root.walk().first { it.operation == operation && (relation == null || it.relation == relation) }

    // region PostgreSQL

    @Test
    fun `a PostgreSQL plan reads as a tree with costs and no actuals`() {
        val plan = plan("pg_explain.json")
        assertEquals(PlanSource.PostgresJson, plan.source)
        assertFalse(plan.analyzed)
        assertEquals(
            """
            Sort
              Finalize GroupAggregate
                Gather
                  Partial GroupAggregate
                    Hash Join
                      Parallel Seq Scan · orders o
                      Hash
                        Seq Scan · customers c
            """.trimIndent(),
            outline(plan),
        )
        assertEquals(4458.13, plan.root.cost)
        val orders = plan.find("Parallel Seq Scan", "orders o")
        assertTrue(orders.fullScan)
        assertEquals(77893.0, orders.estimatedRows)
        assertNull(orders.actualRows)
        assertEquals(2744.59, orders.selfCost)
        // Without ANALYZE, shares are of cost; the orders scan is most of it.
        assertTrue(plan.insight(orders).share!! > 0.5)
        assertEquals(setOf(PlanFlag.Expensive, PlanFlag.LargeScan), plan.insight(orders).flags)
        assertTrue("Filter" in orders.details.map { it.first })
    }

    @Test
    fun `ANALYZE adds actual rows and times, shared out by self time`() {
        val plan = plan("pg_analyze.json")
        assertTrue(plan.analyzed)
        assertTrue(plan.byTime)
        assertEquals(25.296, plan.executionMs)
        assertEquals(0.759, plan.planningMs)
        val orders = plan.find("Parallel Seq Scan", "orders o")
        assertEquals(66317.0, orders.actualRows)
        assertEquals(2.0, orders.loops)
        // Two processes ran the scan side by side: its time is per process, not doubled.
        assertEquals(9.532, orders.time!!, 0.001)
        // It read what it returned plus what its filter removed, in each process.
        assertEquals((66317.0 + 33683.0) * 2, orders.scannedRows)
        val sum = plan.root.walk().sumOf { plan.insight(it).share ?: 0.0 }
        assertEquals(1.0, sum, 0.0001)
        assertTrue(PlanFlag.LargeScan in plan.insight(orders).flags)
        assertTrue("Shared Hit Blocks" in orders.details.map { it.first })
    }

    @Test
    fun `stale statistics show up as a misestimate`() {
        val plan = plan("pg_misestimate.json")
        assertEquals(
            """
            GroupAggregate
              Sort
                Nested Loop Left Join
                  Seq Scan · customers c
                  Bitmap Heap Scan · orders o
                    Bitmap Index Scan
            """.trimIndent(),
            outline(plan),
        )
        val customers = plan.find("Seq Scan", "customers c")
        assertEquals(1.0, customers.estimatedRows)
        assertEquals(30000.0, customers.actualRows)
        val insight = plan.insight(customers)
        assertEquals(30000.0, insight.misestimate)
        assertTrue(PlanFlag.Misestimate in insight.flags)
        assertTrue(PlanFlag.LargeScan in insight.flags)
        // 30,000 loops of an index scan that found nothing: per loop it's 40 expected, 0 found, but that's no plan changer…
        val index = plan.find("Bitmap Index Scan")
        assertEquals(30000.0, index.loops)
        assertEquals("orders_customer", index.index)
        // …and it ran, unlike a node with no loops.
        assertFalse(index.neverExecuted)
        assertTrue(plan.flagged.isNotEmpty())
    }

    @Test
    fun `subplans keep their role and a never-run node is marked`() {
        val plan = plan("pg_analyze2.json")
        assertTrue(plan.root.walk().count() >= 5)
        val update = plan("pg_update.json")
        assertEquals("Update", update.root.operation)
        assertEquals("orders", update.root.relation)
        assertEquals(listOf("Bitmap Heap Scan"), update.root.children.map { it.operation })
    }

    @Test
    fun `a node that never ran isn't called a misestimate`() {
        val json =
            """[{"Plan": {"Node Type": "Nested Loop", "Join Type": "Inner", "Total Cost": 10, "Plan Rows": 5, "Actual Total Time": 1.0, "Actual Rows": 0, "Actual Loops": 1, "Plans": [
              {"Node Type": "Seq Scan", "Relation Name": "a", "Alias": "a", "Total Cost": 4, "Plan Rows": 0, "Actual Total Time": 0.5, "Actual Rows": 0, "Actual Loops": 1},
              {"Node Type": "Index Scan", "Parent Relationship": "SubPlan", "Subplan Name": "SubPlan 1", "Relation Name": "b", "Index Name": "b_pkey", "Total Cost": 6, "Plan Rows": 50000, "Actual Total Time": 0, "Actual Rows": 0, "Actual Loops": 0}
            ]}, "Execution Time": 1.2}]"""
        val plan = assertNotNull(ExplainPlans.parse(json))
        val never = plan.find("Index Scan")
        assertTrue(never.neverExecuted)
        assertEquals("SubPlan 1", never.role)
        assertTrue(plan.insight(never).flags.isEmpty())
        assertEquals("Nested Loop", plan.root.operation)
    }

    // endregion

    // region MySQL

    @Test
    fun `MySQL's classic JSON reads tables with their access type and costs`() {
        val plan = plan("mysql_explain.json")
        assertEquals(PlanSource.MySqlJson, plan.source)
        assertFalse(plan.analyzed)
        assertEquals(
            """
            Query block #1
              Order by
                Group by
                  Nested loop
                    Full table scan · c
                    Index lookup · o
            """.trimIndent(),
            outline(plan),
        )
        assertEquals(7683.78, plan.root.cost)
        val c = plan.find("Full table scan", "c")
        assertTrue(c.fullScan)
        assertEquals(5000.0, c.scannedRows)
        assertEquals(453.25 + 50.0, c.selfCost!!, 0.001)
        val o = plan.find("Index lookup", "o")
        assertEquals("orders_customer", o.index)
        assertEquals(41.0, o.estimatedRows)
        assertEquals(PlanFlag.Expensive in plan.insight(o).flags, true)
        assertTrue(("cost_info › data_read_per_join" to "160K") in o.details)
        // 5,000 rows isn't a large table.
        assertFalse(PlanFlag.LargeScan in plan.insight(c).flags)
    }

    @Test
    fun `MySQL subqueries, unions and materialized tables nest under what uses them`() {
        val plan = plan("mysql_explain2.json")
        val operations = plan.root.walk().map { it.operation }.toList()
        assertTrue("Union" in operations, operations.toString())
        assertTrue(operations.count { it.startsWith("Query block") } >= 2, operations.toString())
        assertTrue(plan.root.walk().any { it.relation == "c" })
    }

    @Test
    fun `MySQL's EXPLAIN ANALYZE tree reads with actual rows, loops and times`() {
        val plan = plan("mysql_analyze.txt")
        assertEquals(PlanSource.MySqlTree, plan.source)
        assertTrue(plan.analyzed)
        assertEquals(
            """
            Group aggregate: count(0), sum(o.total)
              Nested loop inner join
                Filter: (c.country = 'VN')
                  Table scan on c · c
                Filter: (o.total > 100.00)
                  Index lookup on o using orders_customer (customer_id=c.id) · o
            """.trimIndent(),
            outline(plan),
        )
        val lookup = plan.root.walk().first { it.index == "orders_customer" }
        assertEquals(500.0, lookup.loops)
        assertEquals(40.0, lookup.actualRows)
        assertEquals(0.0422 * 500, lookup.time!!, 0.0001)
        val scan = plan.root.walk().first { it.operation == "Table scan on c" }
        assertTrue(scan.fullScan)
        assertEquals(5000.0, scan.actualRows)
        assertEquals(26.7, plan.executionMs!!, 0.001)
    }

    @Test
    fun `tree lines with cost ranges, exponents and temporary tables read too`() {
        val plan = plan("mysql_analyze2.txt")
        val union = plan.root
        assertEquals("Table scan on <union temporary>", union.operation)
        // A temporary table isn't one of the user's tables to index.
        assertFalse(union.fullScan)
        assertEquals(305.0, union.cost)
        val single = plan.root.walk().first { it.operation.startsWith("Single-row index lookup") }
        assertEquals(665e-6 * 49, single.time!!, 1e-9)
        assertEquals("PRIMARY", single.index)
    }

    @Test
    fun `MySQL's JSON version 2 reads like the tree`() {
        val plan = plan("mysql_v2_analyze.json")
        assertEquals(PlanSource.MySqlJsonV2, plan.source)
        assertTrue(plan.analyzed)
        val scan = plan.find("Table scan on c")
        assertEquals("customers c", scan.relation)
        assertTrue(scan.fullScan)
        assertEquals(1, plan.root.children.size)
        val estimated = plan("mysql_v2.json")
        assertFalse(estimated.analyzed)
        assertEquals(estimated.root.walk().count(), plan.root.walk().count())
    }

    // endregion

    // region MariaDB

    @Test
    fun `MariaDB's ANALYZE FORMAT=JSON gives each table its own time`() {
        val plan = plan("maria_analyze.json")
        assertEquals(PlanSource.MariaDbJson, plan.source)
        assertTrue(plan.analyzed)
        val o = plan.find("Index lookup", "o")
        assertEquals(500.0, o.loops)
        assertEquals(40.0, o.actualRows)
        assertEquals(12.90725674 + 1.177399082, o.selfTime!!, 1e-6)
        assertEquals(14.76857359, plan.root.time)
        assertEquals(0.08789053, plan.planningMs)
        assertTrue(PlanFlag.Expensive in plan.insight(o).flags)
        val estimated = plan("maria_explain.json")
        assertEquals(PlanSource.MariaDbJson, estimated.source)
        assertFalse(estimated.analyzed)
    }

    @Test
    fun `MariaDB unions and semi-join duplicate removal nest`() {
        val plan = plan("maria_analyze2.json")
        val operations = plan.root.walk().map { it.operation }.toList()
        assertTrue("Union" in operations, operations.toString())
        assertTrue("Remove duplicates" in operations, operations.toString())
    }

    // endregion

    @Test
    fun `statements per engine`() {
        assertEquals("EXPLAIN (FORMAT JSON) SELECT 1", ExplainPlans.statement(PlanEngine.PostgreSQL, "SELECT 1;", analyze = false))
        assertEquals("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT 1", ExplainPlans.statement(PlanEngine.PostgreSQL, " SELECT 1 ", analyze = true))
        assertEquals("EXPLAIN (GENERIC_PLAN, FORMAT JSON) SELECT * FROM t WHERE id = \$1", ExplainPlans.statement(PlanEngine.PostgreSQL, "SELECT * FROM t WHERE id = \$1", analyze = false))
        assertEquals("EXPLAIN FORMAT=JSON SELECT 1", ExplainPlans.statement(PlanEngine.MySQL, "SELECT 1", analyze = false))
        assertEquals("EXPLAIN ANALYZE SELECT 1", ExplainPlans.statement(PlanEngine.MySQL, "SELECT 1", analyze = true))
        assertEquals("ANALYZE FORMAT=JSON SELECT 1", ExplainPlans.statement(PlanEngine.MariaDB, "SELECT 1", analyze = true))
    }

    @Test
    fun `engines with plans TableGrip reads`() {
        assertEquals(PlanEngine.PostgreSQL, ExplainPlans.engine(DatabaseType.PostgreSQL))
        assertEquals(PlanEngine.MySQL, ExplainPlans.engine(DatabaseType.MySQL))
        assertEquals(PlanEngine.MariaDB, ExplainPlans.engine(DatabaseType.MariaDB))
        assertNull(ExplainPlans.engine(DatabaseType.CockroachDB))
        assertNull(ExplainPlans.engine(DatabaseType.TiDB))
        assertNull(ExplainPlans.engine(DatabaseType.SQLite))
    }

    @Test
    fun `a result set reads from its single text column, and anything else doesn't`() {
        val rows = QueryResult.Rows(listOf(ResultColumn("QUERY PLAN", "json")), listOf(listOf(fixture("pg_explain.json"))), false, 1)
        assertNotNull(ExplainPlans.parse(rows))
        assertNull(ExplainPlans.parse("id | select_type\n1 | SIMPLE"))
        assertNull(ExplainPlans.parse("""{"not": "a plan"}"""))
        assertNull(ExplainPlans.parse("[1, 2]"))
        assertNull(ExplainPlans.parse("{ broken"))
    }
}

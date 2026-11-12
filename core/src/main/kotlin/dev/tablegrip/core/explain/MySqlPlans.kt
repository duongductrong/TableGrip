package dev.tablegrip.core.explain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads MySQL's `EXPLAIN FORMAT=JSON` (the classic format, and version 2 that 8.3 added), MariaDB's `EXPLAIN` /
 * `ANALYZE FORMAT=JSON`, and the text tree MySQL's `EXPLAIN ANALYZE` prints.
 */
internal object MySqlPlans {
    // region Classic JSON (MySQL 5.7+, MariaDB)

    /** Steps that wrap others, with what to call them. */
    private val Containers =
        mapOf(
            "ordering_operation" to "Order by",
            "grouping_operation" to "Group by",
            "duplicates_removal" to "Remove duplicates",
            "windowing" to "Window",
            "union_result" to "Union",
            "except_result" to "Except",
            "intersect_result" to "Intersect",
            "materialized_from_subquery" to "Materialize",
            "materialized" to "Materialize",
            "filesort" to "Filesort",
            "temporary_table" to "Temporary table",
            "read_sorted_file" to "Read sorted file",
            "block-nl-join" to "Block nested loop join",
            "nested_loop" to "Nested loop",
        )

    /** Lists of subqueries, each with a query block. */
    private val Subqueries =
        setOf(
            "attached_subqueries", "subqueries", "having_subqueries", "optimized_away_subqueries", "order_by_subqueries",
            "group_by_subqueries", "select_list_subqueries", "update_value_subqueries", "query_specifications",
        )

    private val AccessTypes =
        mapOf(
            "ALL" to "Full table scan",
            "index" to "Full index scan",
            "range" to "Index range scan",
            "ref" to "Index lookup",
            "eq_ref" to "Unique index lookup",
            "ref_or_null" to "Index lookup or null",
            "const" to "Constant row",
            "system" to "System row",
            "fulltext" to "Full-text search",
            "index_merge" to "Index merge",
            "unique_subquery" to "Unique subquery lookup",
            "index_subquery" to "Index subquery lookup",
            "hash" to "Hash join",
        )

    fun classic(text: String, top: JsonObject): QueryPlan? {
        if ("query_block" !in top) return null
        val mariaDb = top.values.any { it is JsonObject && ("r_loops" in it || "cost" in it) } || "query_optimization" in top
        val nodes = nodesIn(top.filterKeys { it != "query_optimization" }.let(::JsonObject))
        val root = nodes.singleOrNull() ?: PlanNode("Query", children = nodes)
        val analyzed = root.walk().any { it.actualRows != null || it.loops != null }
        return QueryPlan(
            root = root,
            analyzed = analyzed,
            source = if (mariaDb) PlanSource.MariaDbJson else PlanSource.MySqlJson,
            text = text,
            planningMs = (top["query_optimization"] as? JsonObject)?.number("r_total_time_ms"),
            executionMs = root.time?.takeIf { analyzed },
        )
    }

    /** The plan steps among [obj]'s members, in order. */
    private fun nodesIn(obj: JsonObject): List<PlanNode> =
        obj.entries.flatMap { (key, value) ->
            when {
                key == "query_block" && value is JsonObject -> listOf(container(value["select_id"]?.let { "Query block #${(it as JsonPrimitive).content}" } ?: "Query block", value))
                key == "table" && value is JsonObject -> listOf(table(value))
                key in Containers -> {
                    val label = Containers.getValue(key)
                    when (value) {
                        is JsonObject -> listOf(container(filesortLabel(label, value), value))
                        // A nested loop of one table is just the table.
                        is JsonArray -> value.filterIsInstance<JsonObject>().flatMap(::nodesIn).let { if (it.size == 1) it else listOf(PlanNode(label, children = it)) }
                        else -> emptyList()
                    }
                }
                key in Subqueries && value is JsonArray -> value.filterIsInstance<JsonObject>().map { container(if (key == "query_specifications") "Select" else "Subquery", it) }
                else -> emptyList()
            }
        }

    private fun isStructure(key: String, value: JsonElement): Boolean =
        key == "query_block" && value is JsonObject || key == "table" && value is JsonObject || key in Containers || key in Subqueries && value is JsonArray

    private fun filesortLabel(label: String, value: JsonObject): String = if ((value["using_filesort"] as? JsonPrimitive)?.content == "true") "$label (filesort)" else label

    private fun container(label: String, obj: JsonObject): PlanNode {
        val costInfo = obj["cost_info"] as? JsonObject
        val children = nodesIn(obj)
        // MySQL's query cost covers the block's tables; MariaDB's times cover what's under them.
        return PlanNode(
            operation = label,
            loops = obj.number("r_loops"),
            cost = costInfo?.number("query_cost") ?: obj.number("cost")?.takeIf { label.startsWith("Query block") },
            ownCost = costInfo?.number("sort_cost"),
            time = obj.number("r_total_time_ms"),
            details = detailsOf(obj),
            children = children,
        )
    }

    private fun table(obj: JsonObject): PlanNode {
        val access = obj.text("access_type")
        val costInfo = obj["cost_info"] as? JsonObject
        val ownCost =
            costInfo?.let { info -> listOfNotNull(info.number("read_cost"), info.number("eval_cost")).takeIf { it.isNotEmpty() }?.sum() }
                ?: obj.number("cost")
        val ownTime = obj.number("r_total_time_ms") ?: listOfNotNull(obj.number("r_table_time_ms"), obj.number("r_other_time_ms")).takeIf { it.isNotEmpty() }?.sum()
        // Rows examined per scan: the whole table, for a full scan.
        val rows = obj.number("rows_examined_per_scan") ?: obj.number("rows")
        val name = obj.text("table_name")
        return PlanNode(
            operation = access?.let { AccessTypes[it] ?: it } ?: obj.text("message") ?: "Table",
            relation = name,
            index = obj.text("key"),
            estimatedRows = rows,
            actualRows = obj.number("r_rows"),
            loops = obj.number("r_loops"),
            ownCost = ownCost,
            ownTime = ownTime,
            fullScan = access == "ALL" && name?.startsWith("<") == false,
            scannedRows = rows,
            neverExecuted = obj.number("r_loops") == 0.0,
            details = detailsOf(obj),
            children = nodesIn(obj),
        )
    }

    private fun detailsOf(obj: JsonObject): List<Pair<String, String>> = obj.filter { (key, value) -> !isStructure(key, value) }.flatMap { (key, value) -> details(key, value) }

    // endregion

    // region JSON version 2 (MySQL 8.3+, explain_json_format_version = 2)

    fun version2(text: String, top: JsonObject): QueryPlan? {
        if ("operation" !in top) return null
        val root = v2Node(top)
        return QueryPlan(
            root = root,
            analyzed = "actual_loops" in top,
            source = PlanSource.MySqlJsonV2,
            text = text,
            executionMs = root.time?.takeIf { "actual_loops" in top },
        )
    }

    private fun v2Node(obj: JsonObject): PlanNode {
        val loops = obj.number("actual_loops")
        val table = obj.text("table_name")
        val alias = obj.text("alias")
        val fullScan = obj.text("access_type") == "table" && table != null && !table.startsWith("<")
        val children = obj.values.filterIsInstance<JsonArray>().flatMap { array -> array.filterIsInstance<JsonObject>().filter { "operation" in it } }
        return PlanNode(
            operation = obj.text("operation") ?: "?",
            relation = table?.let { if (alias != null && alias != it) "$it $alias" else it },
            index = obj.text("index_name"),
            estimatedRows = obj.number("estimated_rows"),
            actualRows = obj.number("actual_rows"),
            loops = loops,
            cost = obj.number("estimated_total_cost"),
            time = obj.number("actual_last_row_ms")?.let { it * (loops ?: 1.0) },
            fullScan = fullScan,
            scannedRows = obj.number("estimated_rows"),
            neverExecuted = loops == 0.0,
            details = obj.filter { (_, value) -> !(value is JsonArray && value.any { it is JsonObject && "operation" in it }) }.flatMap { (key, value) -> details(key, value) },
            children = children.map(::v2Node),
        )
    }

    // endregion

    // region Text tree (EXPLAIN ANALYZE, EXPLAIN FORMAT=TREE)

    /** A number as the tree prints it: `503`, `0.0856`, `647e-6`. */
    private const val Num = """[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"""

    private val Line =
        Regex(
            """^(\s*)-> (.*?)""" +
                """(?:\s+\(cost=($Num)(?:\.\.($Num))? rows=($Num)\))?""" +
                """(?:\s+\(actual time=($Num)\.\.($Num) rows=($Num) loops=(\d+)\)|\s+\((never executed)\))?\s*$"""
        )

    /** `Table scan on c`, `Index lookup on o using orders_customer (…)`, `Index range scan on orders using PRIMARY over (…)`. */
    private val Access = Regex("""^[^(:]*?(?:scan|lookup|search) on (\S+)(?: using (\S+))?""")

    private class TreeLine(val depth: Int, val node: (List<PlanNode>) -> PlanNode, val children: MutableList<PlanNode> = mutableListOf())

    fun tree(text: String): QueryPlan? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty() || !lines.first().trimStart().startsWith("->")) return null
        var analyzed = false
        val roots = mutableListOf<PlanNode>()
        val stack = ArrayDeque<TreeLine>()
        fun close(until: Int) {
            while (stack.isNotEmpty() && stack.last().depth >= until) {
                val done = stack.removeLast()
                val node = done.node(done.children)
                if (stack.isEmpty()) roots += node else stack.last().children += node
            }
        }
        val pending = mutableListOf<Pair<Int, String>>()
        // An operation's text can run over several lines; only those starting with -> begin a step.
        for (line in lines) {
            if (line.trimStart().startsWith("->")) pending += (line.length - line.trimStart().length) to line
            else if (pending.isNotEmpty()) pending[pending.lastIndex] = pending.last().let { it.first to it.second + " " + line.trim() }
        }
        for ((indent, line) in pending) {
            val match = Line.matchEntire(line) ?: return null
            val g = match.groupValues
            val operation = g[2]
            val access = Access.find(operation)
            val loops = g[9].toDoubleOrNull()
            val never = g[10].isNotEmpty()
            if (loops != null || never) analyzed = true
            val estimated = g[5].toDoubleOrNull()
            val relation = access?.groupValues?.get(1)
            val fullScan = operation.startsWith("Table scan on ") && relation?.startsWith("<") == false
            val cost = (g[4].ifEmpty { g[3] }).toDoubleOrNull()
            val details = buildList {
                add("Operation" to operation)
                if (g[3].isNotEmpty()) add("Estimated cost" to if (g[4].isNotEmpty()) "${g[3]}..${g[4]}" else g[3])
                if (estimated != null) add("Estimated rows" to g[5])
                if (loops != null) {
                    add("Actual time (ms)" to "${g[6]}..${g[7]}")
                    add("Actual rows" to g[8])
                    add("Loops" to g[9])
                }
                if (never) add("Executed" to "never")
            }
            val depth = indent / 4
            close(depth)
            stack.addLast(
                TreeLine(depth, { children ->
                    PlanNode(
                        operation = operation,
                        relation = relation,
                        index = access?.groupValues?.get(2)?.ifEmpty { null },
                        estimatedRows = estimated,
                        actualRows = g[8].toDoubleOrNull(),
                        loops = loops ?: if (never) 0.0 else null,
                        cost = cost,
                        time = g[7].toDoubleOrNull()?.let { it * (loops ?: 1.0) },
                        fullScan = fullScan,
                        scannedRows = estimated,
                        neverExecuted = never,
                        details = details,
                        children = children,
                    )
                })
            )
        }
        close(0)
        val root = roots.singleOrNull() ?: PlanNode("Query", children = roots)
        return QueryPlan(root, analyzed, PlanSource.MySqlTree, text, executionMs = root.time?.takeIf { analyzed })
    }

    // endregion
}

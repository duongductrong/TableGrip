package dev.tablegrip.core.explain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads PostgreSQL's `EXPLAIN (FORMAT JSON)`, with or without ANALYZE and BUFFERS. */
internal object PostgresPlans {
    fun parse(text: String, json: JsonElement): QueryPlan? {
        val top = ((json as? JsonArray)?.firstOrNull() ?: json) as? JsonObject ?: return null
        val plan = top["Plan"] as? JsonObject ?: return null
        val analyzed = "Actual Loops" in plan
        return QueryPlan(
            root = node(plan, analyzed, parallel = 1.0),
            analyzed = analyzed,
            source = PlanSource.PostgresJson,
            text = text,
            planningMs = top.number("Planning Time"),
            executionMs = top.number("Execution Time"),
            details = top.filterKeys { it != "Plan" && it != "Planning Time" && it != "Execution Time" }.flatMap { (key, value) -> details(key, value) },
        )
    }

    /**
     * [parallel] is how many processes share the loops of a node under a Gather: its reported loops count the workers
     * too, and they run at the same time, so time over all loops is divided by it.
     */
    private fun node(plan: JsonObject, analyzed: Boolean, parallel: Double): PlanNode {
        val type = plan.text("Node Type") ?: "?"
        val loops = plan.number("Actual Loops")
        val actualRows = plan.number("Actual Rows")
        val workers = plan.number("Workers Launched") ?: plan.number("Workers Planned")
        val childParallel = if (type.startsWith("Gather") && workers != null) workers + 1 else parallel
        val fullScan = type == "Seq Scan"
        val scanned =
            if (!fullScan) null
            else if (analyzed && loops != null && actualRows != null) (actualRows + (plan.number("Rows Removed by Filter") ?: 0.0)) * loops
            else plan.number("Plan Rows")
        val relation = plan.text("Relation Name")?.let { name -> plan.text("Alias")?.takeIf { it != name }?.let { "$name $it" } ?: name }
        return PlanNode(
            operation = operation(type, plan),
            relation = relation ?: plan.text("CTE Name") ?: plan.text("Function Name"),
            index = plan.text("Index Name"),
            role = plan.text("Subplan Name") ?: plan.text("Parent Relationship")?.takeIf { it == "InitPlan" || it == "SubPlan" },
            estimatedRows = plan.number("Plan Rows"),
            actualRows = actualRows,
            loops = loops,
            cost = plan.number("Total Cost"),
            time = plan.number("Actual Total Time")?.let { it * (loops ?: 1.0) / parallel },
            fullScan = fullScan,
            scannedRows = scanned,
            neverExecuted = analyzed && loops == 0.0,
            details = plan.filterKeys { it != "Plans" }.flatMap { (key, value) -> details(key, value) },
            children = (plan["Plans"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { child -> node(child, analyzed, childParallel) } },
        )
    }

    /** The node's name as text EXPLAIN shows it: `HashAggregate`, `Hash Left Join`, `Index Scan Backward`, `Update`. */
    private fun operation(type: String, plan: JsonObject): String {
        val partial = when (plan.text("Partial Mode")) {
            "Partial" -> "Partial "
            "Finalize" -> "Finalize "
            else -> ""
        }
        val name =
            when (type) {
                "Aggregate" -> when (plan.text("Strategy")) {
                    "Hashed" -> "HashAggregate"
                    "Sorted" -> "GroupAggregate"
                    "Mixed" -> "MixedAggregate"
                    else -> "Aggregate"
                }
                "ModifyTable" -> plan.text("Operation") ?: type
                "SetOp" -> listOfNotNull(if (plan.text("Strategy") == "Hashed") "HashSetOp" else "SetOp", plan.text("Command")).joinToString(" ")
                else -> {
                    val join = plan.text("Join Type")?.takeIf { it != "Inner" && ("Join" in type || type == "Nested Loop") }
                    when {
                        join == null -> type
                        type == "Nested Loop" -> "Nested Loop $join Join"
                        else -> type.removeSuffix("Join") + "$join Join"
                    }
                }
            }
        val backward = if (plan.text("Scan Direction") == "Backward") " Backward" else ""
        val parallel = if (plan["Parallel Aware"].isTrue()) "Parallel " else ""
        return partial + parallel + name + backward
    }

    private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.content == "true"
}

/** [value] as one or more details: nested objects (JIT's timing, for one) flatten to `JIT › Timing › Total`. */
internal fun details(key: String, value: JsonElement): List<Pair<String, String>> =
    when (value) {
        is JsonPrimitive -> listOf(key to value.content)
        is JsonArray ->
            if (value.all { it is JsonPrimitive }) listOf(key to value.joinToString(", ") { (it as JsonPrimitive).content })
            else value.flatMapIndexed { i, item -> details("$key › ${i + 1}", item) }
        is JsonObject -> value.flatMap { (k, v) -> details("$key › $k", v) }
    }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

/** A number, also when MySQL sends it as a string (`"453.25"`). */
internal fun JsonObject.number(key: String): Double? = text(key)?.toDoubleOrNull()

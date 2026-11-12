package dev.tablegrip.core.explain

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One step of a query plan, as the engine reported it. Rows are per loop, as engines report them; [cost] and [time] cover
 * the step and everything under it, [time] over all its loops. Engines that report a step's own share instead (MariaDB)
 * give [ownCost] / [ownTime], and the totals are summed up from the children.
 */
class PlanNode(
    /** What the step does: `Seq Scan`, `Hash Join`, `Full table scan`… */
    val operation: String,
    /** The table it reads, with its alias when that differs. */
    val relation: String? = null,
    val index: String? = null,
    /** Why it's there, when that's not obvious from the tree: `InitPlan 1`, `SubPlan 2`. */
    val role: String? = null,
    val estimatedRows: Double? = null,
    val actualRows: Double? = null,
    val loops: Double? = null,
    cost: Double? = null,
    time: Double? = null,
    private val ownCost: Double? = null,
    private val ownTime: Double? = null,
    /** Reads every row of a table rather than going through an index. */
    val fullScan: Boolean = false,
    /** Rows a [fullScan] reads; null when the engine doesn't say. */
    val scannedRows: Double? = null,
    /** Analyzed, but the engine never ran this step. */
    val neverExecuted: Boolean = false,
    /** Everything the engine said about the step, as label and value, in its order. */
    val details: List<Pair<String, String>> = emptyList(),
    val children: List<PlanNode> = emptyList(),
) {
    /** Planner cost of this step and its children, in the engine's own units. */
    val cost: Double? = cost ?: sumWithChildren(ownCost) { it.cost }

    /** Milliseconds spent in this step and its children, over all loops. */
    val time: Double? = time ?: sumWithChildren(ownTime) { it.time }

    /** Cost of this step alone. */
    val selfCost: Double? = ownCost ?: this.cost?.let { total -> (total - children.sumOf { it.cost ?: 0.0 }).coerceIn(0.0, total) }

    /** Milliseconds spent in this step alone. */
    val selfTime: Double? = ownTime ?: this.time?.let { total -> (total - children.sumOf { it.time ?: 0.0 }).coerceIn(0.0, total) }

    /** This node and every node under it, parents first. */
    fun walk(): Sequence<PlanNode> = sequence {
        yield(this@PlanNode)
        children.forEach { yieldAll(it.walk()) }
    }

    private fun sumWithChildren(own: Double?, of: (PlanNode) -> Double?): Double? {
        val known = children.mapNotNull(of)
        if (own == null && known.isEmpty()) return null
        return (own ?: 0.0) + known.sum()
    }

    override fun toString(): String = listOfNotNull(operation, relation, index?.let { "using $it" }).joinToString(" ")
}

/** Why a node deserves a look. */
enum class PlanFlag {
    /** Takes a large share of the plan's time, or of its cost when it wasn't run. */
    Expensive,
    /** Reads a whole table with many rows. */
    LargeScan,
    /** Returned far more or far fewer rows than the planner expected. */
    Misestimate,
}

/** What the plan says about one node. [share] is of the whole plan's time when analyzed, else of its cost. */
class NodeInsight(val share: Double?, val flags: Set<PlanFlag>, val misestimate: Double?)

/** Which engine's EXPLAIN output a plan was read from, and in which of its formats. */
enum class PlanSource(val label: String) {
    PostgresJson("PostgreSQL JSON"),
    MySqlJson("MySQL JSON"),
    MySqlJsonV2("MySQL JSON (version 2)"),
    MySqlTree("MySQL tree"),
    MariaDbJson("MariaDB JSON"),
}

/**
 * A plan read from EXPLAIN's output. [analyzed] when the statement was run (EXPLAIN ANALYZE), so actual rows and times
 * are known. [details] are about the whole statement (triggers, JIT, settings); [text] is the output it was read from.
 */
class QueryPlan(
    val root: PlanNode,
    val analyzed: Boolean,
    val source: PlanSource,
    val text: String,
    val planningMs: Double? = null,
    val executionMs: Double? = null,
    val details: List<Pair<String, String>> = emptyList(),
) {
    private val nodes = root.walk().toList()

    /** Nodes under a LIMIT, which stop once it has its rows: fewer rows than expected is no misestimate there. */
    private val limited: Set<PlanNode> =
        java.util.Collections.newSetFromMap(IdentityHashMap<PlanNode, Boolean>()).also { set ->
            nodes.filter { it.operation.startsWith("Limit") }.forEach { limit -> limit.children.forEach { child -> set += child.walk() } }
        }

    /** Whether shares are of time (analyzed and timed) or of cost. */
    val byTime: Boolean = analyzed && nodes.any { (it.selfTime ?: 0.0) > 0.0 }

    private val total: Double = nodes.sumOf { (if (byTime) it.selfTime else it.selfCost) ?: 0.0 }

    private val insights = IdentityHashMap<PlanNode, NodeInsight>().also { map -> nodes.forEach { map[it] = analyze(it) } }

    fun insight(node: PlanNode): NodeInsight = insights[node] ?: NodeInsight(null, emptySet(), null)

    /** Nodes with any [PlanFlag], most expensive first. */
    val flagged: List<PlanNode>
        get() = nodes.filter { insight(it).flags.isNotEmpty() }.sortedByDescending { insight(it).share ?: 0.0 }

    private fun analyze(node: PlanNode): NodeInsight {
        val own = if (byTime) node.selfTime else node.selfCost
        val share = own?.takeIf { total > 0 }?.let { it / total }
        val misestimate = misestimate(node)
        val flags = buildSet {
            if (share != null && share >= ExpensiveShare && nodes.size > 1) add(PlanFlag.Expensive)
            if (node.fullScan && (node.scannedRows ?: 0.0) >= LargeScanRows) add(PlanFlag.LargeScan)
            if (misestimate != null) add(PlanFlag.Misestimate)
        }
        return NodeInsight(share, flags, misestimate)
    }

    /** How many times more (or fewer) rows came back than expected, when that's far off and matters. */
    private fun misestimate(node: PlanNode): Double? {
        if (!analyzed || node.neverExecuted) return null
        val actual = node.actualRows ?: return null
        val estimated = node.estimatedRows ?: return null
        val loops = node.loops ?: 1.0
        if (actual < estimated && node in limited) return null
        val factor = max(actual, estimated) / max(min(actual, estimated), 1.0)
        return factor.takeIf { it >= MisestimateFactor && abs(actual - estimated) * loops >= MisestimateRows }
    }

    companion object {
        /** A node taking this much of the plan is worth a look. */
        const val ExpensiveShare = 0.3
        const val LargeScanRows = 10_000.0
        const val MisestimateFactor = 10.0
        /** Fewer rows off than this, over all loops, don't change a plan. */
        const val MisestimateRows = 1_000.0
    }
}

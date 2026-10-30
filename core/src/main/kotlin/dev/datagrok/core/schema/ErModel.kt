package dev.datagrok.core.schema

import dev.datagrok.core.ddl.ColumnTypes
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableRef
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** A box of the ER diagram: where it is and how big, in diagram units (1 unit = 1 dp at 100%). */
data class ErBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float
        get() = x + width
    val bottom: Float
        get() = y + height

    fun contains(px: Float, py: Float) = px in x..right && py in y..bottom
}

/** A foreign key drawn as a line from [from] (the referencing table) to [to]. */
data class ErEdge(val from: TableRef, val fromColumns: List<String>, val to: TableRef, val toColumns: List<String>, val optional: Boolean)

/**
 * Places tables in layers by their foreign keys: tables nothing else depends on at the left, the tables that reference
 * them to their right, so most lines run one way. Tables without any foreign key in or out are packed in a grid below.
 */
object ErLayout {
    const val HeaderHeight = 36f
    const val RowHeight = 22f
    /** Space between the header and the first row, and after the last. */
    const val RowPadding = 4f
    /** Columns shown per table; the rest fold into a "… N more" row. */
    const val MaxRows = 18

    private const val LayerGap = 90f
    private const val BoxGap = 28f
    private const val MaxColumnHeight = 1400f

    fun edges(tables: List<TableDetails>): List<ErEdge> {
        val known = tables.associateBy { it.table.name.lowercase() }
        return tables.flatMap { table ->
            table.foreignKeys.mapNotNull { fk ->
                val target = known[fk.referencedTable.name.lowercase()]?.table ?: return@mapNotNull null
                val optional = fk.columns.any { name -> table.columns.firstOrNull { it.name == name }?.nullable != false }
                ErEdge(table.table, fk.columns, target, fk.referencedColumns, optional)
            }
        }
    }

    fun rowCount(table: TableDetails): Int = if (table.columns.size > MaxRows) MaxRows + 1 else table.columns.size

    fun height(table: TableDetails): Float = HeaderHeight + RowPadding * 2 + rowCount(table) * RowHeight

    /** Box positions for [tables]; [width] gives each table's box width (it depends on text measurement). */
    fun layout(tables: List<TableDetails>, width: (TableDetails) -> Float): Map<TableRef, ErBox> {
        if (tables.isEmpty()) return emptyMap()
        val edges = edges(tables).filter { it.from != it.to }
        val connected = (edges.map { it.from } + edges.map { it.to }).toSet()
        val parents = edges.groupBy({ it.from }, { it.to })

        // Layer = length of the longest chain of references below a table; cycles are cut where they're found.
        val layer = mutableMapOf<TableRef, Int>()
        val visiting = mutableSetOf<TableRef>()
        fun depth(ref: TableRef): Int {
            layer[ref]?.let { return it }
            if (!visiting.add(ref)) return 0
            val value = parents[ref].orEmpty().filter { it != ref }.maxOfOrNull { depth(it) + 1 } ?: 0
            visiting.remove(ref)
            layer[ref] = value
            return value
        }
        val graphTables = tables.filter { it.table in connected }
        graphTables.forEach { depth(it.table) }
        // Pull referenced tables right, next to the closest table that references them, so their lines stay short
        // instead of crossing the layers in between. Children first, so each one is where it ends up.
        val children = edges.groupBy({ it.to }, { it.from })
        for (ref in graphTables.map { it.table }.sortedByDescending { layer.getValue(it) }) {
            val closest = children[ref].orEmpty().filter { it != ref }.minOfOrNull { layer.getValue(it) } ?: continue
            if (closest - 1 > layer.getValue(ref)) layer[ref] = closest - 1
        }

        val boxes = mutableMapOf<TableRef, ErBox>()
        val layers = graphTables.groupBy { layer.getValue(it.table) }.toSortedMap()
        // Order within a layer by the average position of what each table references, a few sweeps (barycenters).
        val order = mutableMapOf<TableRef, Float>()
        layers.values.forEach { members -> members.sortedBy { it.table.name.lowercase() }.forEachIndexed { i, t -> order[t.table] = i.toFloat() } }
        repeat(3) {
            for ((_, members) in layers) {
                val ranked =
                    members.sortedWith(
                        compareBy<TableDetails> { table ->
                            val neighbours = parents[table.table].orEmpty() + edges.filter { it.to == table.table }.map { it.from }
                            neighbours.mapNotNull { order[it] }.takeIf { it.isNotEmpty() }?.average() ?: order.getValue(table.table).toDouble()
                        }.thenBy { it.table.name.lowercase() },
                    )
                ranked.forEachIndexed { i, t -> order[t.table] = i.toFloat() }
            }
        }

        var x = 0f
        var graphBottom = 0f
        val columns = mutableListOf<List<TableDetails>>()
        for ((_, members) in layers) {
            // A tall layer wraps into several columns so the diagram stays roughly as wide as it is high.
            var current = mutableListOf<TableDetails>()
            var height = 0f
            for (table in members.sortedBy { order.getValue(it.table) }) {
                val h = height(table)
                if (current.isNotEmpty() && height + h > MaxColumnHeight) {
                    columns += current
                    current = mutableListOf()
                    height = 0f
                }
                current += table
                height += h + BoxGap
            }
            if (current.isNotEmpty()) columns += current
        }
        val tallest = columns.maxOfOrNull { column -> column.sumOf { (height(it) + BoxGap).toDouble() }.toFloat() - BoxGap } ?: 0f
        for (column in columns) {
            val columnWidth = column.maxOf { width(it) }
            val total = column.sumOf { (height(it) + BoxGap).toDouble() }.toFloat() - BoxGap
            var y = (tallest - total) / 2
            for (table in column) {
                boxes[table.table] = ErBox(x, y, columnWidth, height(table))
                y += height(table) + BoxGap
            }
            graphBottom = max(graphBottom, y)
            x += columnWidth + LayerGap
        }

        // Unrelated tables: a grid under the graph (or on its own), shortest first so the rows line up.
        val loose = tables.filter { it.table !in connected }.sortedBy { it.table.name.lowercase() }
        if (loose.isNotEmpty()) {
            val perRow = max(1, ceil(sqrt(loose.size.toDouble() * 1.6)).toInt()).let { if (boxes.isNotEmpty()) max(it, (x / 260f).toInt()) else it }
            var top = if (boxes.isEmpty()) 0f else graphBottom + LayerGap
            loose.chunked(perRow).forEach { row ->
                var left = 0f
                for (table in row) {
                    val w = width(table)
                    boxes[table.table] = ErBox(left, top, w, height(table))
                    left += w + BoxGap * 1.5f
                }
                top += row.maxOf { height(it) } + BoxGap * 1.5f
            }
        }
        return boxes
    }

    /** The tables within one foreign key of [focus], for a diagram around one table. */
    fun neighbourhood(tables: List<TableDetails>, focus: TableRef): List<TableDetails> {
        val edges = edges(tables)
        val near = edges.filter { it.from == focus || it.to == focus }.flatMap { listOf(it.from, it.to) }.toSet() + focus
        return tables.filter { it.table in near }
    }
}

/** Writes a Mermaid `erDiagram` of [tables], for docs and READMEs. */
object Mermaid {
    fun erDiagram(tables: List<TableDetails>, dialect: SqlDialect): String = buildString {
        appendLine("erDiagram")
        for (table in tables.sortedBy { it.table.name.lowercase() }) {
            val foreignKeyColumns = table.foreignKeys.flatMap { it.columns }.toSet()
            val uniqueColumns = table.indexes.filter { it.unique && it.columns.size == 1 }.map { it.columns.single() }.toSet()
            appendLine("    ${entity(table.table.name)} {")
            for (column in table.columns) {
                val keys =
                    listOfNotNull(
                        "PK".takeIf { column.primaryKey },
                        "FK".takeIf { column.name in foreignKeyColumns },
                        "UK".takeIf { !column.primaryKey && column.name in uniqueColumns },
                    ).joinToString(", ")
                val type = ColumnTypes.declaredType(column, dialect).first
                append("        ${token(type)} ${token(column.name)}")
                if (keys.isNotEmpty()) append(" $keys")
                if (!column.nullable && !column.primaryKey) append(" \"not null\"")
                appendLine()
            }
            appendLine("    }")
        }
        for (edge in ErLayout.edges(tables)) {
            // Parent side: exactly one, or zero-or-one when the key is nullable; child side: zero or more.
            val parent = if (edge.optional) "|o" else "||"
            appendLine("    ${entity(edge.to.name)} $parent--o{ ${entity(edge.from.name)} : \"${edge.fromColumns.joinToString(", ").replace("\"", "'")}\"")
        }
    }.trimEnd() + "\n"

    /** Entity names outside Mermaid's plain-word syntax are quoted. */
    private fun entity(name: String): String = if (PlainName.matches(name)) name else "\"${name.replace("\"", "'")}\""

    /** Attribute types and names are single tokens: spaces and commas would end them early. */
    private fun token(text: String): String = text.trim().replace(Regex("\\s+"), "_").replace(",", "-").replace("\"", "").ifEmpty { "unknown" }

    private val PlainName = Regex("[A-Za-z_][A-Za-z0-9_-]*")
}

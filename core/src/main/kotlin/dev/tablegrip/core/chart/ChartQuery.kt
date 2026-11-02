package dev.tablegrip.core.chart

import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.StatementKind

/** A statement that aggregates on the server what a chart would otherwise aggregate from the rows fetched, and the spec for its result. */
data class ChartAggregate(val sql: String, val spec: ChartSpec)

/**
 * Wraps a query in the GROUP BY a spec describes, so a chart covers all rows rather than the first ones fetched. The
 * query runs unchanged as a subquery: its own filters and limits still apply.
 */
object ChartQuery {
    /** Null when [spec] doesn't aggregate, [sql] isn't a single SELECT, or the engine can't round dates to [ChartSpec.bucket]. */
    fun aggregate(dialect: SqlDialect, sql: String, spec: ChartSpec): ChartAggregate? {
        if (!spec.aggregates) return null
        val statements = SqlSplitter(dialect.syntax).split(sql)
        val statement = statements.singleOrNull()?.text?.trim()?.removeSuffix(";")?.trim() ?: return null
        val info = SqlClassifier(dialect.syntax).classify(statement)
        if (info.kind != StatementKind.Read || info.verb != "SELECT") return null

        fun column(name: String) = "t.${dialect.quoteIdentifier(name)}"
        val x = column(spec.x).let { if (spec.bucket == null) it else dialect.timeBucket(it, spec.bucket) ?: return null }
        val keys = listOfNotNull(x to spec.x, spec.series?.let { column(it) to it })
        val measures =
            spec.y.map { measure ->
                // A measure without an aggregate still needs one once rows are grouped: the sum is what a chart would show.
                val aggregate = measure.aggregate.takeIf { it != Aggregate.None } ?: Aggregate.Sum
                val alias = Measure(measure.column, aggregate).label
                "${aggregate.sql}(${column(measure.column)})" to alias
            }
        val select = (keys + measures).joinToString(", ") { (expression, alias) -> "$expression AS ${dialect.quoteIdentifier(alias)}" }
        val groupBy = keys.joinToString(", ") { it.first }
        val text = "SELECT $select\nFROM (\n$statement\n) t\nGROUP BY $groupBy\nORDER BY $groupBy"
        // The result's columns hold the aggregates already: drawn as they are, keeping the type, x and series.
        val aggregated = spec.copy(y = measures.map { Measure(it.second) }, bucket = null)
        return ChartAggregate(text, aggregated)
    }
}

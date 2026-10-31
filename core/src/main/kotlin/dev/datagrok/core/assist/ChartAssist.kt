package dev.datagrok.core.assist

import dev.datagrok.core.chart.Aggregate
import dev.datagrok.core.chart.ChartSpec
import dev.datagrok.core.chart.ChartType
import dev.datagrok.core.chart.ColumnProfile
import dev.datagrok.core.chart.ColumnProfiler
import dev.datagrok.core.chart.Measure
import dev.datagrok.core.chart.TimeBucket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A chart an agent proposes, with what it assumed (which column is "revenue", what "recent" means) so the user can check. */
data class ChartProposal(val spec: ChartSpec, val explanation: String, val assumptions: List<String>)

/**
 * Asks an agent for a chart of a result, described in words ("revenue by month, split by region"). It gets the query
 * and the result's columns with their kinds and value counts; values only in [samples], which the caller fills only
 * when the connection shares data. The answer is a [ChartSpec], checked against the columns like one the user edits.
 */
class ChartSpecTask(
    private val request: String,
    private val engine: String,
    private val sql: String,
    private val profiles: List<ColumnProfile>,
    /** A few values per column name; empty unless the connection shares data. */
    private val samples: Map<String, List<String>> = emptyMap(),
    /** Whether the rows are the first ones only, so grouping them would miss the rest. */
    private val truncated: Boolean = false,
) : AssistTask<ChartProposal> {
    override val name = "chart-spec"

    override fun system() =
        """
        You choose charts for query results in Datagrok, a desktop database client.

        # Rules
        - Use only the columns listed, by their exact names. Never invent a column or compute a new one.
        - Pick the chart type that answers the request: line or area for trends over dates or numbers, bar for comparing categories, pie for shares of a whole (at most 8 slices, one measure), scatter for the relation between two numbers.
        - y measures must be numeric columns, except with "count", which counts any column's non-null values.
        - "bucket" groups dates (hour, day, week, month, quarter, year) and only applies when x is a date column; then every measure needs an aggregate (sum, avg, min, max, count).
        - "series" splits one measure by a category column with few values.
        - Say in "assumptions" what you inferred that the user didn't say, e.g. which column you took for "revenue".
        - If the columns can't answer the request, still return the closest chart and say what's missing in "explanation".
        """.trimIndent()

    override fun prompt(): String = buildString {
        append("Request: ${request.trim()}\n\n")
        append("The result comes from this $engine query:\n```sql\n${sql.trim()}\n```\n\n")
        append("# Columns\n")
        for (column in profiles) {
            append("- ${column.name}: ${column.kind.name.lowercase()} (${column.typeName.ifBlank { "unknown type" }}), ")
            append(if (column.distinct >= ColumnProfiler.DistinctCap) "many distinct values" else "${column.distinct} distinct values")
            if (column.nulls > 0) append(", ${column.nulls} nulls")
            samples[column.name]?.takeIf { it.isNotEmpty() }?.let { values -> append(", e.g. ${values.joinToString { "\"$it\"" }}") }
            append('\n')
        }
        append("${profiles.firstOrNull()?.rows ?: 0} rows")
        if (truncated) append(", the first ones only: the query returns more")
        append(".\n\nReply with only this JSON object:\n")
        append(
            """
            ```json
            {"type": "line|area|bar|scatter|pie", "x": "column", "y": [{"column": "column", "aggregate": "none|sum|avg|min|max|count"}], "series": "column or null", "bucket": "hour|day|week|month|quarter|year or null", "horizontal": false, "explanation": "one short sentence", "assumptions": ["…"]}
            ```
            """.trimIndent()
        )
    }

    override fun parse(json: JsonObject): ChartProposal {
        val reasons = mutableListOf<String>()
        val type = json.text("type")?.let(ChartType::of)
        if (type == null) reasons += "\"type\" must be one of ${ChartType.entries.joinToString { it.name.lowercase() }}."
        val x = json.text("x")
        if (x == null) reasons += "\"x\" must name a column."
        val y =
            (json["y"] as? JsonArray)?.mapNotNull { element ->
                val measure = element as? JsonObject
                val column = measure?.text("column")
                if (column == null) null.also { reasons += "Each \"y\" entry needs a \"column\"." }
                else {
                    val name = measure.text("aggregate")
                    val aggregate = if (name == null) Aggregate.None else Aggregate.of(name)
                    if (aggregate == null) reasons += "Unknown aggregate \"$name\"; use none, sum, avg, min, max or count."
                    Measure(column, aggregate ?: Aggregate.None)
                }
            } ?: emptyList<Measure>().also { reasons += "\"y\" must be a list of measures." }
        val bucketName = optional(json, "bucket")
        val bucket = bucketName?.let(TimeBucket::of)
        if (bucketName != null && bucket == null) reasons += "Unknown bucket \"$bucketName\"; use hour, day, week, month, quarter or year."
        if (reasons.isNotEmpty() || type == null || x == null) throw AssistInvalid(reasons)
        val spec = ChartSpec(type, x, y, optional(json, "series"), bucket, (json["horizontal"] as? JsonPrimitive)?.booleanOrNull ?: false)
        spec.validate(profiles).takeIf { it.isNotEmpty() }?.let { throw AssistInvalid(it) }
        val assumptions = (json["assumptions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }.orEmpty()
        return ChartProposal(spec, json.text("explanation").orEmpty(), assumptions)
    }

    /** A string field where null, blank and "null" (which agents write for "none") all mean absent. */
    private fun optional(json: JsonObject, key: String): String? =
        json.takeUnless { it[key] is JsonNull }?.text(key)?.takeUnless { it.equals("null", ignoreCase = true) || it.equals("none", ignoreCase = true) }
}

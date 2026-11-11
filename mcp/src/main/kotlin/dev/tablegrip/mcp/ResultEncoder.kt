package dev.tablegrip.mcp

import dev.tablegrip.core.driver.QueryResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Query results as agents read them: column names and types, rows as arrays, and an explicit note whenever something
 * was cut, so the agent narrows the query instead of trusting a partial answer.
 */
object ResultEncoder {
    /** Characters kept of one value. */
    const val MaxCell = 2_000

    fun rows(result: QueryResult.Rows, budget: Int, maxRows: Int): JsonElement {
        var used = 0
        var cutForSize = false
        val kept = ArrayList<JsonArray>()
        for (row in result.rows) {
            if (kept.size >= maxRows) break
            val encoded = JsonArray(row.map(::cell))
            val size = encoded.toString().length
            if (kept.isNotEmpty() && used + size > budget) {
                cutForSize = true
                break
            }
            used += size
            kept += encoded
        }
        val moreRows = result.truncated || result.rows.size > kept.size
        return buildJsonObject {
            put("columns", buildJsonArray { result.columns.forEach { column -> add(buildJsonObject { put("name", column.name); put("type", column.typeName) }) } })
            put("rows", JsonArray(kept))
            put("rowCount", kept.size)
            put("truncated", moreRows)
            if (moreRows) {
                put(
                    "note",
                    when {
                        cutForSize -> "Only the first ${kept.size} rows fit in the result size limit. Select fewer columns, filter, or aggregate."
                        else -> "Only the first ${kept.size} rows are shown (the limit per call). Filter, aggregate or page with LIMIT/OFFSET for the rest."
                    },
                )
            }
            put("elapsedMs", result.elapsedMillis)
        }
    }

    fun update(result: QueryResult.Update): JsonElement =
        buildJsonObject {
            put("affectedRows", result.affectedRows)
            put("elapsedMs", result.elapsedMillis)
        }

    fun result(result: QueryResult, budget: Int, maxRows: Int): JsonElement =
        when (result) {
            is QueryResult.Rows -> rows(result, budget, maxRows)
            is QueryResult.Update -> update(result)
        }

    fun cell(value: String?): JsonElement = value?.let { JsonPrimitive(clip(it)) } ?: JsonNull

    fun clip(value: String, max: Int = MaxCell): String =
        if (value.length <= max) value else value.take(max) + "… [${value.length - max} more characters]"
}

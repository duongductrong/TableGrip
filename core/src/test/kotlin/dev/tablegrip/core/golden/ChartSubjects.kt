package dev.tablegrip.core.golden

import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartData
import dev.tablegrip.core.chart.ChartPoint
import dev.tablegrip.core.chart.ChartQuery
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartSuggest
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.ChartValues
import dev.tablegrip.core.chart.ColumnKind
import dev.tablegrip.core.chart.ColumnProfile
import dev.tablegrip.core.chart.ColumnProfiler
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `core/chart`. A case that holds rows names its columns as `"name:JDBC_TYPE"` (a [Types] constant) and gives the rows
 * as text or null, or generates them: `"gen": {"count": n, "cells": ["{i}", "s{i%12}"]}` makes `n` rows, where `{i}`
 * is the row number and `{i%k}` its remainder by `k`. Profiles are read from the rows, or given outright as `profiles`.
 */
internal val chartSubjects: Map<String, Subject> =
    mapOf(
        "chart-values" to { case ->
            val text = case.stringOrNull("text")
            buildJsonObject {
                put("number", ChartValues.number(text)?.let(::JsonPrimitive) ?: JsonNull)
                put("epochMillis", ChartValues.epochMillis(text)?.let(::JsonPrimitive) ?: JsonNull)
                case.stringOrNull("bucket")?.let { name ->
                    val time = ChartValues.dateTime(text)
                    put("truncated", time?.let { ChartValues.epochMillis(ChartData.truncate(it, TimeBucket.valueOf(name))) }?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        },
        "chart-profile" to { case ->
            val (columns, rows) = chartRows(case)
            profilesJson(ColumnProfiler.profile(columns, rows))
        },
        "chart-suggest" to { case ->
            val profiles = chartProfiles(case)
            buildJsonObject {
                put("specs", JsonArray(ChartSuggest.suggest(profiles).map(::specJson)))
                // The bucket for the first column that holds dates, whether or not a chart was suggested for it.
                profiles.firstOrNull { it.kind == ColumnKind.Temporal }?.let { put("bucket", ChartSuggest.bucketFor(it).name) }
            }
        },
        "chart-spec" to { case ->
            buildJsonObject {
                case["spec"]?.let { json ->
                    val profiles = chartProfiles(case)
                    val spec = specOf(json.jsonObject)
                    putJsonArray("validate") { spec.validate(profiles).forEach { add(it) } }
                    put("adjusted", specJson(spec.adjusted(profiles)))
                    put("aggregates", spec.aggregates)
                    putJsonArray("columns") { spec.columns.forEach { add(it) } }
                    putJsonArray("labels") { spec.y.forEach { add(it.label) } }
                }
                case["parse"]?.let { parse ->
                    val words = parse.jsonObject
                    putJsonArray("type") { words.strings("type").forEach { add(ChartType.of(it)?.name) } }
                    putJsonArray("aggregate") { words.strings("aggregate").forEach { add(Aggregate.of(it)?.name) } }
                    putJsonArray("bucket") { words.strings("bucket").forEach { add(TimeBucket.of(it)?.name) } }
                }
            }
        },
        "chart-data" to { case ->
            val (columns, rows) = chartRows(case)
            val profiles = case["profiles"]?.let { profilesOf(it.jsonArray) } ?: ColumnProfiler.profile(columns, rows)
            val spec = specOf(case.obj("spec"))
            val frame = ChartData.build(rows, profiles, spec)
            buildJsonObject {
                put("xKind", frame.xKind.name)
                if (case.bool("summary")) {
                    putJsonObject("points") {
                        put("count", frame.points.size)
                        put("first", frame.points.firstOrNull()?.let(::pointJson) ?: JsonNull)
                        put("last", frame.points.lastOrNull()?.let(::pointJson) ?: JsonNull)
                        put("sumY", frame.points.sumOf { it.y })
                    }
                } else {
                    put("points", JsonArray(frame.points.map(::pointJson)))
                }
                putJsonArray("groups") { frame.groups.forEach { add(it) } }
                putJsonArray("categories") { frame.categories.forEach { add(it) } }
                put("skippedRows", frame.skippedRows)
                put("omittedCategories", frame.omittedCategories)
                put("mergedSeries", frame.mergedSeries)
                put("downsampled", frame.downsampled)
                put("xLabel", frame.xLabel)
                put("yLabel", frame.yLabel)
            }
        },
        "chart-lttb" to { case ->
            val peak = case.intOrNull("peakAt")
            val points =
                (0 until case.int("count")).map { i ->
                    val y = if (peak != null) (if (i == peak) 1000.0 else 0.0) else ((i.toLong() * 7919) % 101).toDouble()
                    ChartPoint(i.toDouble(), "g", y)
                }
            buildJsonArray { ChartData.lttb(points, case.int("threshold")).forEach { add(it.x as Double) } }
        },
        "chart-query" to { case ->
            val spec = specOf(case.obj("spec"))
            buildJsonObject {
                for (type in DatabaseType.entries) {
                    val dialect = type.dialect ?: continue
                    val aggregate = ChartQuery.aggregate(dialect, case.string("sql"), spec)
                    put(
                        type.id,
                        if (aggregate == null) JsonNull
                        else buildJsonObject {
                            put("sql", aggregate.sql)
                            put("spec", specJson(aggregate.spec))
                        },
                    )
                }
            }
        },
    )

private fun jdbcType(name: String): Int = Types::class.java.getField(name).getInt(null)

private fun chartRows(case: JsonObject): Pair<List<ResultColumn>, List<List<String?>>> {
    val columns =
        case.strings("columns").map { column ->
            val (name, type) = column.split(':').let { it[0] to it[1] }
            ResultColumn(name, "", jdbcType(type))
        }
    val gen = case["gen"]?.jsonObject
    val rows =
        if (gen != null) {
            val cells = gen.strings("cells")
            (0 until gen.int("count")).map { i -> cells.map { template(it, i) } }
        } else {
            case.array("rows").map { row -> row.jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content } }
        }
    return columns to rows
}

/** `{i}` and `{i%k}` replaced by the row number and its remainder. */
private fun template(text: String, i: Int): String =
    Regex("\\{i(?:%(\\d+))?}").replace(text) { m -> m.groupValues[1].takeIf { it.isNotEmpty() }?.let { (i % it.toInt()).toString() } ?: i.toString() }

private fun chartProfiles(case: JsonObject): List<ColumnProfile> =
    case["profiles"]?.let { profilesOf(it.jsonArray) } ?: chartRows(case).let { (columns, rows) -> ColumnProfiler.profile(columns, rows) }

private fun profilesOf(json: JsonArray): List<ColumnProfile> =
    json.mapIndexed { index, element ->
        val p = element.jsonObject
        ColumnProfile(
            index = index,
            name = p.string("name"),
            typeName = "",
            kind = ColumnKind.valueOf(p.string("kind")),
            distinct = p.int("distinct"),
            nulls = p.intOrNull("nulls") ?: 0,
            rows = p.int("rows"),
            min = (p["min"] as? JsonPrimitive)?.doubleOrNull,
            max = (p["max"] as? JsonPrimitive)?.doubleOrNull,
        )
    }

private fun profilesJson(profiles: List<ColumnProfile>): JsonElement =
    buildJsonArray {
        for (p in profiles) {
            addJsonObject {
                put("index", p.index)
                put("name", p.name)
                put("typeName", p.typeName)
                put("kind", p.kind.name)
                put("distinct", p.distinct)
                put("nulls", p.nulls)
                put("rows", p.rows)
                put("min", p.min?.let(::JsonPrimitive) ?: JsonNull)
                put("max", p.max?.let(::JsonPrimitive) ?: JsonNull)
                put("unique", p.unique)
            }
        }
    }

internal fun specOf(json: JsonObject): ChartSpec =
    ChartSpec(
        type = ChartType.valueOf(json.string("type")),
        x = json.string("x"),
        y = json.array("y").map { Measure(it.jsonObject.string("column"), it.jsonObject.stringOrNull("aggregate")?.let(Aggregate::valueOf) ?: Aggregate.None) },
        series = json.stringOrNull("series"),
        bucket = json.stringOrNull("bucket")?.let(TimeBucket::valueOf),
        horizontal = json.bool("horizontal"),
    )

internal fun specJson(spec: ChartSpec): JsonObject =
    buildJsonObject {
        put("type", spec.type.name)
        put("x", spec.x)
        putJsonArray("y") {
            for (m in spec.y) {
                addJsonObject {
                    put("column", m.column)
                    put("aggregate", m.aggregate.name)
                }
            }
        }
        put("series", spec.series)
        put("bucket", spec.bucket?.name)
        put("horizontal", spec.horizontal)
    }

private fun pointJson(p: ChartPoint): JsonObject =
    buildJsonObject {
        when (val x = p.x) {
            is Double -> put("x", x)
            else -> put("x", x as String)
        }
        put("group", p.group)
        put("y", p.y)
    }

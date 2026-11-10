package dev.tablegrip.core.golden

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.Combinator
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.FilterCompiler
import dev.tablegrip.core.filter.FilterCondition
import dev.tablegrip.core.filter.FilterGroup
import dev.tablegrip.core.filter.FilterNode
import dev.tablegrip.core.filter.FilterOperator
import dev.tablegrip.core.filter.FilterValueException
import dev.tablegrip.core.filter.FilterValues
import dev.tablegrip.core.filter.Projection
import dev.tablegrip.core.filter.ProjectionCompiler
import dev.tablegrip.core.filter.RawCondition
import dev.tablegrip.core.filter.SelectExpression
import dev.tablegrip.core.filter.SelectField
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.SelectItem
import dev.tablegrip.core.filter.TemporalValue
import dev.tablegrip.core.filter.ValueKind
import java.sql.Types
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `filter/`: value parsing, operators per kind, the filter compiler and the projection compiler. */
internal val filterSubjects: Map<String, Subject> =
    mapOf(
        "filter-values" to ::filterValues,
        "filter-operators" to { _ ->
            buildJsonObject {
                for (kind in ValueKind.entries) {
                    putJsonObject(kind.name) {
                        put("label", kind.label)
                        put("isTemporal", kind.isTemporal)
                        put("default", FilterOperator.defaultFor(kind).name)
                        for (regex in listOf(true, false)) {
                            putJsonArray("forKind regex=$regex") {
                                FilterOperator.forKind(kind, regex).forEach { section -> add(buildJsonArray { section.forEach { add(it.name) } }) }
                            }
                        }
                        putJsonObject("labels") { FilterOperator.entries.forEach { put(it.name, it.label(kind)) } }
                    }
                }
                putJsonObject("operators") {
                    for (op in FilterOperator.entries) {
                        putJsonObject(op.name) {
                            put("arity", op.arity.name)
                            put("matchesText", op.matchesText)
                        }
                    }
                }
            }
        },
        "filter-compiler" to ::filterCompiler,
        "projection" to ::projection,
    )

internal fun dialectNamed(case: JsonObject): SqlDialect = DatabaseType.byId(case.stringOrNull("dialect") ?: "PostgreSQL")!!.dialect!!

private fun errorJson(e: FilterValueException): JsonObject = buildJsonObject { put("error", e.message) }

private fun filterValues(case: JsonObject): JsonElement =
    try {
        when (val op = case.string("op")) {
            "valueKind" -> JsonPrimitive(ValueKind.of(case.string("typeName"), case.intOrNull("jdbcType") ?: Types.OTHER, case.intOrNull("size")).name)
            "temporal" -> {
                val now = LocalDateTime.parse(case.stringOrNull("now") ?: "2026-10-28T13:45")
                val value = FilterValues.temporal(case.string("text"), now, ZoneOffset.of(case.stringOrNull("zone") ?: "Z"))
                buildJsonObject {
                    when (value) {
                        is TemporalValue.Day -> { put("type", "Day"); put("value", FilterValues.format(value.date)) }
                        is TemporalValue.Moment -> { put("type", "Moment"); put("value", FilterValues.format(value.dateTime)) }
                        is TemporalValue.TimeOfDay -> { put("type", "TimeOfDay"); put("value", FilterValues.format(value.time)) }
                    }
                }
            }
            "parseList" -> buildJsonArray { FilterValues.parseList(case.string("text")).forEach { add(it) } }
            "number" -> JsonPrimitive(FilterValues.number(case.string("text")).toPlainString())
            "uuid" -> JsonPrimitive(FilterValues.uuid(case.string("text")).toString())
            "boolean" -> JsonPrimitive(FilterValues.boolean(case.string("text")))
            "format" -> JsonPrimitive(FilterValues.format(sqlParam(case.obj("param")).value))
            else -> error("unknown filter-values op $op")
        }
    } catch (e: FilterValueException) {
        errorJson(e)
    }

/** The columns of FilterCompilerTest, unless the case lists its own. */
private val filterColumns =
    listOf(
        FilterColumn("name", "text", ValueKind.Text),
        FilterColumn("total", "numeric", ValueKind.Number),
        FilterColumn("qty", "int4", ValueKind.Number),
        FilterColumn("active", "bool", ValueKind.Boolean),
        FilterColumn("born", "date", ValueKind.Date),
        FilterColumn("created_at", "timestamptz", ValueKind.DateTime),
        FilterColumn("opens", "time", ValueKind.Time),
        FilterColumn("token", "uuid", ValueKind.Uuid),
        FilterColumn("payload", "jsonb", ValueKind.Json),
        FilterColumn("mood", "mood", ValueKind.Other),
        FilterColumn("photo", "bytea", ValueKind.Binary),
    )

private fun columns(case: JsonObject, default: List<FilterColumn>): List<FilterColumn> =
    if ("columns" in case) case.array("columns").map { it.jsonObject }.map { FilterColumn(it.string("name"), it.string("typeName"), ValueKind.valueOf(it.string("kind"))) }
    else default

internal fun filterNode(json: JsonObject): FilterNode =
    when (val type = json.string("type")) {
        "cond" ->
            FilterCondition(
                json.int("id").toLong(),
                json.string("column"),
                FilterOperator.valueOf(json.string("op")),
                json.stringOrNull("value") ?: "",
                json.stringOrNull("value2") ?: "",
                json.bool("caseSensitive"),
                json.bool("enabled", default = true),
            )
        "group" -> filterGroup(json)
        "raw" -> RawCondition(json.int("id").toLong(), json.string("sql"), json.bool("enabled", default = true))
        else -> error("unknown node type $type")
    }

internal fun filterGroup(json: JsonObject): FilterGroup =
    FilterGroup(
        json.intOrNull("id")?.toLong() ?: 0,
        Combinator.valueOf(json.stringOrNull("combinator") ?: "All"),
        json.array("children").map { filterNode(it.jsonObject) },
        json.bool("negated"),
        json.bool("enabled", default = true),
    )

private fun filterCompiler(case: JsonObject): JsonElement {
    val now = LocalDateTime.parse(case.stringOrNull("now") ?: "2026-10-28T13:45:30")
    val zone = ZoneOffset.of(case.stringOrNull("zone") ?: "Z")
    val clock = Clock.fixed(now.toInstant(zone), zone)
    val previous = Locale.getDefault()
    // ThisWeek starts the week where the default locale does.
    Locale.setDefault(Locale.forLanguageTag(case.stringOrNull("weekLocale") ?: "en-GB"))
    val compiled =
        try {
            FilterCompiler(dialectNamed(case), columns(case, filterColumns), clock).compile(filterGroup(case.obj("root")))
        } finally {
            Locale.setDefault(previous)
        }
    return buildJsonObject {
        val filter = compiled.filter
        if (filter == null) put("filter", JsonNull)
        else putJsonObject("filter") {
            put("sql", filter.sql)
            put("preview", filter.preview)
            putJsonArray("parameters") { filter.parameters.forEach { add(paramJson(it)) } }
        }
        putJsonObject("issues") { compiled.issues.forEach { (id, message) -> put(id.toString(), message) } }
    }
}

/** The columns of ProjectionTest, unless the case lists its own. */
private val projectionColumns =
    listOf(
        FilterColumn("id", "int4", ValueKind.Number),
        FilterColumn("name", "text", ValueKind.Text),
        FilterColumn("total", "float8", ValueKind.Number),
        FilterColumn("created_at", "timestamptz", ValueKind.DateTime),
        FilterColumn("photo", "bytea", ValueKind.Binary),
    )

private fun selectItem(json: JsonObject): SelectItem =
    when (val type = json.string("type")) {
        "field" ->
            SelectField(
                json.int("id").toLong(),
                json.string("column"),
                SelectFunction.valueOf(json.stringOrNull("function") ?: "None"),
                json.stringOrNull("argument") ?: "",
                json.stringOrNull("alias") ?: "",
                json.bool("enabled", default = true),
            )
        "expr" -> SelectExpression(json.int("id").toLong(), json.string("sql"), json.stringOrNull("alias") ?: "", json.bool("enabled", default = true))
        else -> error("unknown item type $type")
    }

private fun projection(case: JsonObject): JsonElement {
    val dialect = dialectNamed(case)
    return when (val op = case.stringOrNull("op") ?: "compile") {
        "forKind" ->
            buildJsonObject {
                for (kind in ValueKind.entries) {
                    putJsonArray(kind.name) {
                        SelectFunction.forKind(kind, dialect).forEach { section -> add(buildJsonArray { section.forEach { add(it.name) } }) }
                    }
                }
            }
        "compile" -> {
            val compiler = ProjectionCompiler(dialect, columns(case, projectionColumns))
            val items = case.array("items").map { selectItem(it.jsonObject) }
            val compiled = compiler.compile(Projection(items, case.bool("distinct")))
            buildJsonObject {
                put("clause", compiled.projection?.clause)
                put("editable", compiled.editable)
                putJsonObject("issues") { compiled.issues.forEach { (id, message) -> put(id.toString(), message) } }
                putJsonArray("outputNames") { items.forEach { add(compiler.outputName(it)) } }
            }
        }
        else -> error("unknown projection op $op")
    }
}

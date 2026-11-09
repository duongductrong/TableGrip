package dev.tablegrip.core.golden

import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.SqlParam
import dev.tablegrip.core.filter.SqlProjection
import dev.tablegrip.core.filter.ValueKind
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.sql.SqlSyntax
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `dialect/SqlDialect.kt`: each case names an operation and its arguments, and the output maps every SQL engine's
 * [DatabaseType] id to what its dialect returns, so a case covers every engine at once.
 */
internal val dialectSubjects: Map<String, Subject> =
    mapOf(
        "sql-dialect" to { case ->
            buildJsonObject {
                for (type in DatabaseType.entries) {
                    val dialect = type.dialect ?: continue
                    put(type.id, dialectOp(dialect, case))
                }
            }
        },
    )

internal fun tableRef(json: JsonObject): TableRef =
    TableRef(Namespace(json.stringOrNull("catalog"), json.stringOrNull("schema")), json.string("name"))

internal fun JsonObject.projectionOrNull(key: String): SqlProjection? =
    (this[key] as? JsonObject)?.let { SqlProjection(it.string("columns"), it.bool("distinct")) }

/** A typed parameter: `{"type": "Long", "value": "5", "kind": "Number"}`. */
internal fun sqlParam(json: JsonObject): SqlParam {
    val text = json.string("value")
    val value: Any =
        when (val type = json.string("type")) {
            "Text" -> text
            "Long" -> text.toLong()
            "Decimal" -> BigDecimal(text)
            "Bool" -> text.toBoolean()
            "Uuid" -> UUID.fromString(text)
            "Date" -> LocalDate.parse(text)
            "DateTime" -> LocalDateTime.parse(text)
            "Time" -> LocalTime.parse(text)
            else -> error("unknown param type $type")
        }
    return SqlParam(value, ValueKind.valueOf(json.stringOrNull("kind") ?: "Text"))
}

/** A parameter as JSON, the inverse of [sqlParam]. */
internal fun paramJson(param: SqlParam): JsonObject =
    buildJsonObject {
        val (type, text) =
            when (val v = param.value) {
                is String -> "Text" to v
                is Long -> "Long" to v.toString()
                is BigDecimal -> "Decimal" to v.toPlainString()
                is Boolean -> "Bool" to v.toString()
                is UUID -> "Uuid" to v.toString()
                is LocalDate -> "Date" to v.toString()
                is LocalDateTime -> "DateTime" to v.toString()
                is LocalTime -> "Time" to v.toString()
                else -> error("unexpected param ${v::class}")
            }
        put("type", type)
        put("value", text)
        put("kind", param.kind.name)
    }

internal fun syntaxJson(syntax: SqlSyntax): JsonObject =
    buildJsonObject {
        put("backtickIdentifiers", syntax.backtickIdentifiers)
        put("doubleQuotedStrings", syntax.doubleQuotedStrings)
        put("hashComments", syntax.hashComments)
        put("dollarQuotedStrings", syntax.dollarQuotedStrings)
        put("backslashEscapes", syntax.backslashEscapes)
        put("delimiterCommand", syntax.delimiterCommand)
        put("bracketIdentifiers", syntax.bracketIdentifiers)
        put("batchSeparator", syntax.batchSeparator)
    }

private fun pair(p: Pair<String, String>?): JsonElement = if (p == null) JsonNull else buildJsonArray { add(p.first); add(p.second) }

private fun strings(list: List<String>): JsonArray = buildJsonArray { list.forEach { add(it) } }

/** A [DatabaseMetaData] whose schemas and catalogs are [schemas] and [catalogs]; null ones fail with an SQLException. */
private fun metaData(schemas: List<String?>?, catalogs: List<String?>?): DatabaseMetaData {
    fun rows(names: List<String?>?, column: String): ResultSet {
        if (names == null) throw SQLException("not supported")
        var row = -1
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            when (method.name) {
                "next" -> ++row < names.size
                "getString" -> if (args[0] == column) names[row] else error("unexpected column ${args[0]}")
                "close" -> null
                else -> error("unexpected ResultSet.${method.name}")
            }
        } as ResultSet
    }
    return Proxy.newProxyInstance(DatabaseMetaData::class.java.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
        when (method.name) {
            "getSchemas" -> rows(schemas, "TABLE_SCHEM")
            "getCatalogs" -> rows(catalogs, "TABLE_CAT")
            else -> error("unexpected DatabaseMetaData.${method.name}")
        }
    } as DatabaseMetaData
}

private fun JsonObject.nullableStrings(key: String): List<String?>? =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content }

private fun dialectOp(dialect: SqlDialect, case: JsonObject): JsonElement {
    val table = { tableRef(case.obj("table")) }
    val orderBy = {
        case.array("orderBy").map { SortColumn(it.jsonArray[0].jsonPrimitive.content, SortDirection.valueOf(it.jsonArray[1].jsonPrimitive.content)) }
    }
    return when (val op = case.string("op")) {
        "properties" ->
            buildJsonObject {
                put("name", dialect.toString())
                put("syntax", syntaxJson(dialect.syntax))
                putJsonObject("tableTypes") { dialect.tableTypes.forEach { (k, v) -> put(k, v) } }
                put("supportsRegex", dialect.supportsRegex)
                put("regexHonoursCase", dialect.regexHonoursCase)
                put("functions", strings(dialect.functions))
            }
        "quoteIdentifier" -> JsonPrimitive(dialect.quoteIdentifier(case.string("value")))
        "literal" -> JsonPrimitive(dialect.literal(case.stringOrNull("value"), case.bool("numeric")))
        "literalParam" -> JsonPrimitive(dialect.literal(sqlParam(case.obj("param"))))
        "textMatch" -> pair(dialect.textMatch(case.string("expression"), case.bool("caseSensitive"), case.bool("negated")))
        "regexMatch" -> pair(dialect.regexMatch(case.string("expression"), case.bool("caseSensitive"), case.bool("negated")))
        "textPattern" -> JsonPrimitive(dialect.textPattern(case.string("value"), case.bool("caseSensitive")))
        "defaultNamespace" -> JsonPrimitive(dialect.defaultNamespace(case.strings("names"), case.string("database")))
        "explain" -> strings(dialect.explain(case.string("sql")))
        "plainIdentifier" -> buildJsonArray { case.strings("names").forEach { add(dialect.plainIdentifier.matches(it)) } }
        "qualifiedName" -> JsonPrimitive(dialect.qualifiedName(table()))
        "selectPage" ->
            JsonPrimitive(
                dialect.selectPage(table(), case.stringOrNull("filter"), orderBy(), case.int("limit"), case.getValue("offset").jsonPrimitive.content.toLong(), case.projectionOrNull("projection"))
            )
        "selectAll" -> JsonPrimitive(dialect.selectAll(table(), case.stringOrNull("filter"), orderBy(), case.projectionOrNull("projection")))
        "countRows" -> JsonPrimitive(dialect.countRows(table(), case.stringOrNull("filter"), case.projectionOrNull("projection")))
        "castToText" -> JsonPrimitive(dialect.castToText(case.string("expression")))
        "samplePattern" -> JsonPrimitive(dialect.samplePattern(case.string("value")))
        "sampleValues" -> JsonPrimitive(dialect.sampleValues(table(), case.string("column"), case.int("limit"), case.int("scan")))
        "selectFunction" ->
            buildJsonObject {
                for (function in SelectFunction.entries) {
                    val args = case.obj("arguments").strings(function.name)
                    put(function.name, dialect.selectFunction(function, case.string("expression"), args))
                }
            }
        "timeBucket" -> buildJsonObject { TimeBucket.entries.forEach { put(it.name, dialect.timeBucket(case.string("expression"), it)) } }
        "listNamespaces" ->
            // A dialect that doesn't tolerate a failed read propagates it.
            runCatching { dialect.listNamespaces(metaData(case.nullableStrings("schemas"), case.nullableStrings("catalogs"))) }.fold({ namespaces ->
            buildJsonArray {
                for (ns in namespaces) {
                    addJsonObject {
                        put("catalog", ns.catalog)
                        put("schema", ns.schema)
                    }
                }
            }
            }, { JsonPrimitive("error") })
        else -> error("unknown dialect op $op")
    }
}

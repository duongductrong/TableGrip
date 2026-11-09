package dev.tablegrip.core.golden

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.ddl.ColumnTypes
import dev.tablegrip.core.ddl.DdlGenerator
import dev.tablegrip.core.ddl.DdlScript
import dev.tablegrip.core.ddl.DesignColumn
import dev.tablegrip.core.ddl.TableChange
import dev.tablegrip.core.ddl.TableDesign
import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.ConstraintInfo
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.ForeignKeyInfo
import dev.tablegrip.core.metadata.IndexInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.metadata.TriggerInfo
import dev.tablegrip.core.schema.ErLayout
import dev.tablegrip.core.schema.Mermaid
import dev.tablegrip.core.schema.SchemaDiff
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `ddl/` and `schema/`: declared types, DDL scripts, table designs, schema compare and migration, ER layout, Mermaid. */
internal val schemaSubjects: Map<String, Subject> =
    mapOf(
        "ddl-column-types" to ::columnTypes,
        "ddl-script" to { case -> perDialect(case) { type -> scriptJson(DdlGenerator(type.dialect!!).script(case.array("changes").map { change(it.jsonObject) })) } },
        "ddl-design" to { case ->
            perDialect(case) { type ->
                designJson(
                    TableDesign.from(
                        details(case.obj("details")),
                        type.dialect!!,
                        case.array("constraints").map { constraint(it.jsonObject) },
                        case.array("triggers").map { trigger(it.jsonObject) },
                    )
                )
            }
        },
        "schema-compare" to ::schemaCompare,
        "er-layout" to ::erLayout,
        "mermaid" to { case -> perDialect(case) { type -> JsonPrimitive(Mermaid.erDiagram(case.array("tables").map { details(it.jsonObject) }, type.dialect!!)) } },
    )

/** The engines DDL cases run on unless they name their own: one per dialect. */
private val ddlEngines = listOf("PostgreSQL", "CockroachDB", "MySQL", "SQLite", "SqlServer", "ClickHouse", "DuckDB", "H2")

private fun perDialect(case: JsonObject, run: (DatabaseType) -> JsonElement): JsonElement =
    buildJsonObject {
        val engines = if ("dialects" in case) case.strings("dialects") else ddlEngines
        for (id in engines) put(id, run(DatabaseType.byId(id)!!))
    }

private fun namespace(json: JsonObject?): Namespace = Namespace(json?.stringOrNull("catalog"), json?.stringOrNull("schema"))

internal fun ref(json: JsonObject): TableRef = TableRef(namespace(json), json.string("name"))

internal fun refJson(ref: TableRef): JsonObject =
    buildJsonObject {
        put("catalog", ref.namespace.catalog)
        put("schema", ref.namespace.schema)
        put("name", ref.name)
    }

internal fun columnInfo(json: JsonObject): ColumnInfo =
    ColumnInfo(
        json.string("name"),
        json.stringOrNull("typeName") ?: "integer",
        json.intOrNull("size"),
        json.bool("nullable", default = true),
        json.stringOrNull("defaultValue"),
        json.bool("autoIncrement"),
        json.bool("primaryKey"),
        json.intOrNull("jdbcType") ?: Types.OTHER,
        json.intOrNull("scale"),
    )

private fun index(json: JsonObject) = IndexInfo(json.string("name"), json.strings("columns"), json.bool("unique"))

private fun indexJson(index: IndexInfo) =
    buildJsonObject {
        put("name", index.name)
        putJsonArray("columns") { index.columns.forEach { add(it) } }
        put("unique", index.unique)
    }

private fun foreignKey(json: JsonObject) =
    ForeignKeyInfo(json.stringOrNull("name") ?: "", json.strings("columns"), ref(json.obj("referencedTable")), json.strings("referencedColumns"), json.stringOrNull("onUpdate"), json.stringOrNull("onDelete"))

private fun foreignKeyJson(fk: ForeignKeyInfo) =
    buildJsonObject {
        put("name", fk.name)
        putJsonArray("columns") { fk.columns.forEach { add(it) } }
        put("referencedTable", refJson(fk.referencedTable))
        putJsonArray("referencedColumns") { fk.referencedColumns.forEach { add(it) } }
        put("onUpdate", fk.onUpdate)
        put("onDelete", fk.onDelete)
    }

internal fun details(json: JsonObject) =
    TableDetails(
        ref(json.obj("table")),
        json.array("columns").map { columnInfo(it.jsonObject) },
        json.array("indexes").map { index(it.jsonObject) },
        json.array("foreignKeys").map { foreignKey(it.jsonObject) },
        json.stringOrNull("primaryKeyName"),
    )

private fun constraint(json: JsonObject) =
    ConstraintInfo(json.stringOrNull("name") ?: "", ConstraintKind.valueOf(json.string("kind")), json.strings("columns"), json.stringOrNull("definition") ?: "")

private fun trigger(json: JsonObject) =
    TriggerInfo(json.string("name"), json.stringOrNull("timing") ?: "AFTER", json.stringOrNull("events") ?: "INSERT", json.bool("enabled", default = true), json.string("definition"))

private fun designColumn(json: JsonObject) =
    DesignColumn(
        json.string("name"),
        json.string("type"),
        json.bool("nullable", default = true),
        json.stringOrNull("defaultValue"),
        json.bool("autoIncrement"),
        json.stringOrNull("originalName"),
    )

private fun designColumnJson(c: DesignColumn) =
    buildJsonObject {
        put("name", c.name)
        put("type", c.type)
        put("nullable", c.nullable)
        put("defaultValue", c.defaultValue)
        put("autoIncrement", c.autoIncrement)
        put("originalName", c.originalName)
    }

internal fun design(json: JsonObject) =
    TableDesign(
        ref(json.obj("table")),
        json.array("columns").map { designColumn(it.jsonObject) },
        json.strings("primaryKey"),
        json.array("indexes").map { index(it.jsonObject) },
        json.array("foreignKeys").map { foreignKey(it.jsonObject) },
        json.stringOrNull("primaryKeyName"),
        json.array("uniqueConstraints").map { index(it.jsonObject) },
        json.strings("checks"),
        json.strings("triggers"),
    )

private fun designJson(d: TableDesign) =
    buildJsonObject {
        put("table", refJson(d.table))
        putJsonArray("columns") { d.columns.forEach { add(designColumnJson(it)) } }
        putJsonArray("primaryKey") { d.primaryKey.forEach { add(it) } }
        putJsonArray("indexes") { d.indexes.forEach { add(indexJson(it)) } }
        putJsonArray("foreignKeys") { d.foreignKeys.forEach { add(foreignKeyJson(it)) } }
        put("primaryKeyName", d.primaryKeyName)
        putJsonArray("uniqueConstraints") { d.uniqueConstraints.forEach { add(indexJson(it)) } }
        putJsonArray("checks") { d.checks.forEach { add(it) } }
        putJsonArray("triggers") { d.triggers.forEach { add(it) } }
    }

private fun change(json: JsonObject) =
    TableChange((json["original"] as? JsonObject)?.let(::design), (json["target"] as? JsonObject)?.let(::design))

private fun scriptJson(script: DdlScript) =
    buildJsonObject {
        putJsonArray("statements") {
            script.statements.forEach {
                addJsonObject {
                    put("sql", it.sql)
                    put("phase", it.phase.name)
                }
            }
        }
        putJsonArray("warnings") { script.warnings.forEach { add(it) } }
        put("transactional", script.transactional)
        put("text", script.text)
    }

private fun columnTypes(case: JsonObject): JsonElement =
    when (val op = case.string("op")) {
        "declaredType" ->
            perDialect(case) { type ->
                val (declared, identity) = ColumnTypes.declaredType(columnInfo(case.obj("column")), type.dialect!!)
                buildJsonArray { add(declared); add(identity) }
            }
        "defaultExpression" -> perDialect(case) { type -> JsonPrimitive(ColumnTypes.defaultExpression(columnInfo(case.obj("column")), type.dialect!!)) }
        "normalize" -> buildJsonArray { case.strings("values").forEach { add(ColumnTypes.normalize(it)) } }
        "normalizeDefault" -> buildJsonArray { case.array("values").forEach { add(ColumnTypes.normalizeDefault((it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content)) } }
        "commonTypes" -> perDialect(case) { type -> buildJsonArray { ColumnTypes.commonTypes(type.dialect!!).forEach { add(it) } } }
        else -> error("unknown column-types op $op")
    }

private fun schemaCompare(case: JsonObject): JsonElement {
    val comparison = SchemaDiff.compare(case.array("source").map { design(it.jsonObject) }, case.array("target").map { design(it.jsonObject) }, namespace(case["targetNamespace"] as? JsonObject))
    val selection = if ("selection" in case) case.strings("selection").toSet() else comparison.defaultSelection()
    return buildJsonObject {
        putJsonArray("diffs") {
            for (diff in comparison.diffs) {
                addJsonObject {
                    put("name", diff.name)
                    put("kind", diff.kind.name)
                    put("id", diff.id)
                    putJsonArray("items") {
                        for (item in diff.items) {
                            addJsonObject {
                                put("type", item::class.simpleName)
                                put("id", item.id)
                                put("key", diff.itemKey(item))
                                put("label", item.label)
                                put("destructive", item.destructive)
                            }
                        }
                    }
                }
            }
        }
        put("identical", comparison.identical)
        putJsonArray("defaultSelection") { comparison.defaultSelection().sorted().forEach { add(it) } }
        put("migration", perDialect(case) { type -> scriptJson(SchemaDiff.migration(comparison, selection, DdlGenerator(type.dialect!!))) })
    }
}

private fun erLayout(case: JsonObject): JsonElement {
    val tables = case.array("tables").map { details(it.jsonObject) }
    val widths = case["widths"]?.jsonObject?.mapValues { it.value.toString().toFloat() }.orEmpty()
    val boxes = ErLayout.layout(tables) { widths[it.table.name] ?: 200f }
    return buildJsonObject {
        putJsonArray("edges") {
            for (edge in ErLayout.edges(tables)) {
                addJsonObject {
                    put("from", edge.from.name)
                    putJsonArray("fromColumns") { edge.fromColumns.forEach { add(it) } }
                    put("to", edge.to.name)
                    putJsonArray("toColumns") { edge.toColumns.forEach { add(it) } }
                    put("optional", edge.optional)
                }
            }
        }
        putJsonObject("boxes") {
            for ((ref, box) in boxes) {
                // Bit patterns: the JDK doesn't always print the shortest decimal that reads back as the same number.
                put(ref.name, buildJsonArray { listOf(box.x, box.y, box.width, box.height).forEach { add(it.toRawBits()) } })
            }
        }
        case.stringOrNull("focus")?.let { focus ->
            val ref = tables.first { it.table.name == focus }.table
            putJsonArray("neighbourhood") { ErLayout.neighbourhood(tables, ref).forEach { add(it.table.name) } }
        }
    }
}

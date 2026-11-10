package dev.tablegrip.core.golden

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.edit.BoundValue
import dev.tablegrip.core.edit.ChangeSql
import dev.tablegrip.core.edit.RowChange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `edit/ChangeSet.kt`: a case lists row changes against a table; the output maps every SQL engine's [DatabaseType] id to
 * the statements [ChangeSql] makes and their previews. Columns are `[name, value or null, jdbcType, numeric, isDefault]`
 * lists so their order survives.
 */
internal val editSubjects: Map<String, Subject> =
    mapOf(
        "edit-changes" to { case ->
            val table = tableRef(case.obj("table"))
            val changes = case.array("changes").map { change(it.jsonObject) }
            buildJsonObject {
                for (type in DatabaseType.entries) {
                    val dialect = type.dialect ?: continue
                    putJsonArray(type.id) {
                        for (statement in ChangeSql.statements(dialect, table, changes)) {
                            addJsonObject {
                                put("sql", statement.sql)
                                putJsonArray("parameters") {
                                    for (p in statement.parameters) {
                                        addJsonObject {
                                            put("value", p.value)
                                            put("jdbcType", p.jdbcType)
                                            put("numeric", p.numeric)
                                            put("isDefault", p.isDefault)
                                        }
                                    }
                                }
                                if (statement.expectedRows == null) put("expectedRows", JsonNull) else put("expectedRows", statement.expectedRows)
                                put("preview", statement.preview(dialect))
                            }
                        }
                    }
                }
            }
        },
    )

private fun columns(case: JsonObject, key: String): Map<String, BoundValue> =
    case.array(key).associate { entry ->
        val parts = entry.jsonArray
        val name = (parts[0] as kotlinx.serialization.json.JsonPrimitive).content
        val value = (parts[1] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it != JsonNull }?.content
        val jdbcType = (parts[2] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        val numeric = (parts[3] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
        val isDefault = parts.getOrNull(4)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toBoolean() } ?: false
        name to BoundValue(value, jdbcType, numeric, isDefault)
    }

private fun change(json: JsonObject): RowChange =
    when (val op = json.string("op")) {
        "update" -> RowChange.Update(columns(json, "key"), columns(json, "values"))
        "insert" -> RowChange.Insert(columns(json, "values"))
        "delete" -> RowChange.Delete(columns(json, "key"))
        else -> error("unknown op $op")
    }

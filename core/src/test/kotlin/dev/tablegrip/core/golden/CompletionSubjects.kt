package dev.tablegrip.core.golden

import dev.tablegrip.core.completion.ColumnEntry
import dev.tablegrip.core.completion.CompletionResult
import dev.tablegrip.core.completion.DocumentCompleter
import dev.tablegrip.core.completion.DocumentField
import dev.tablegrip.core.completion.DocumentQuery
import dev.tablegrip.core.completion.Relation
import dev.tablegrip.core.completion.SchemaProvider
import dev.tablegrip.core.completion.SqlCompleter
import dev.tablegrip.core.completion.TableEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `completion/`: a case's `text` holds the caret as `|`. The SQL schema is SqlCompleterTest's unless the case gives its
 * own (`schema`); the document fields DocumentCompleterTest's unless it gives `fields`.
 */
internal val completionSubjects: Map<String, Subject> =
    mapOf(
        "sql-completion" to ::sqlCompletion,
        "document-completion" to { case ->
            val (text, caret) = caretText(case.string("text"))
            val fields = if ("fields" in case) case.array("fields").map { field(it.jsonObject) } else documentFields
            resultJson(DocumentCompleter.complete(text, caret, DocumentQuery.valueOf(case.stringOrNull("query") ?: "Filter"), fields))
        },
    )

private fun caretText(marked: String): Pair<String, Int> {
    val caret = marked.indexOf('|')
    return marked.removeRange(caret, caret + 1) to caret
}

private fun resultJson(result: CompletionResult): JsonObject =
    buildJsonObject {
        put("replaceStart", result.replaceStart)
        put("replaceEnd", result.replaceEnd)
        put("prefix", result.prefix)
        putJsonArray("items") {
            for (item in result.items) {
                addJsonObject {
                    put("label", item.label)
                    put("insertText", item.insertText)
                    put("kind", item.kind.name)
                    put("detail", item.detail)
                    put("caretOffset", item.caretOffset)
                }
            }
        }
        val value = result.value
        if (value == null) put("value", JsonNull)
        else putJsonObject("value") {
            put("namespace", value.namespace)
            put("table", value.table)
            put("column", value.column)
            put("prefix", value.prefix)
            put("quoted", value.quoted)
        }
        putJsonArray("pendingTables") {
            result.pendingTables.sortedBy { "${it.first}.${it.second}" }.forEach { (ns, name) -> add(buildJsonArray { add(ns); add(name) }) }
        }
    }

/** A schema from JSON: tables by namespace (a null namespace reads the default one), columns by table name, relations. */
private class JsonSchema(json: JsonObject) : SchemaProvider {
    override val defaultNamespace: String? = json.stringOrNull("defaultNamespace")
    private val namespaces = json.strings("namespaces")
    private val tables = json["tables"]?.jsonObject?.mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonArray).map { TableEntry(it.jsonObject.string("name"), it.jsonObject.bool("view")) } }.orEmpty()
    private val columns =
        json["columns"]?.jsonObject?.mapValues { (_, v) ->
            (v as kotlinx.serialization.json.JsonArray).map { ColumnEntry(it.jsonObject.string("name"), it.jsonObject.string("type"), it.jsonObject.bool("primaryKey")) }
        }.orEmpty()
    private val relations =
        (json["relations"] as? kotlinx.serialization.json.JsonArray)?.map {
            val r = it.jsonObject
            Relation(r.string("fromTable"), r.strings("fromColumns"), r.string("toTable"), r.strings("toColumns"))
        }

    override fun namespaces() = namespaces

    override fun tables(namespace: String?) = tables[namespace ?: defaultNamespace].orEmpty()

    override fun columns(namespace: String?, table: String) = columns[table]

    override fun relations(namespace: String?, table: String) = relations?.filter { it.fromTable == table || it.toTable == table }
}

private val defaultSchema: JsonObject =
    goldenJson.parseToJsonElement(
        """
        {
          "defaultNamespace": "public",
          "namespaces": ["public", "audit"],
          "tables": {
            "public": [{"name": "users"}, {"name": "orders"}, {"name": "teams"}, {"name": "order_items"}, {"name": "active_users", "view": true}],
            "audit": [{"name": "events"}]
          },
          "columns": {
            "users": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "name", "type": "text"}, {"name": "email", "type": "text"}, {"name": "team_id", "type": "int4"}],
            "orders": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "user_id", "type": "int4"}, {"name": "total", "type": "numeric"}, {"name": "status", "type": "text"}],
            "teams": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "name", "type": "text"}],
            "order_items": [{"name": "id", "type": "int4", "primaryKey": true}, {"name": "order_id", "type": "int4"}],
            "events": [{"name": "id", "type": "int8", "primaryKey": true}, {"name": "payload", "type": "jsonb"}]
          },
          "relations": [
            {"fromTable": "orders", "fromColumns": ["user_id"], "toTable": "users", "toColumns": ["id"]},
            {"fromTable": "users", "fromColumns": ["team_id"], "toTable": "teams", "toColumns": ["id"]},
            {"fromTable": "order_items", "fromColumns": ["order_id"], "toTable": "orders", "toColumns": ["id"]}
          ]
        }
        """
    ).jsonObject

private fun sqlCompletion(case: JsonObject): JsonElement {
    val completer = SqlCompleter(dialectNamed(case))
    return when (val op = case.stringOrNull("op") ?: "complete") {
        "complete" -> {
            val (text, caret) = caretText(case.string("text"))
            val schema = JsonSchema(if ("schema" in case) case.obj("schema") else defaultSchema)
            resultJson(completer.complete(text, caret, schema, case.intOrNull("maxItems") ?: 60))
        }
        "tablesIn" -> buildJsonArray { completer.tablesIn(case.string("text")).forEach { (ns, name) -> add(buildJsonArray { add(ns); add(name) }) } }
        "quote" -> buildJsonArray { case.strings("names").forEach { add(completer.quote(it)) } }
        else -> error("unknown sql-completion op $op")
    }
}

private fun field(json: JsonObject) = DocumentField(json.string("path"), json.strings("types"), json.strings("values"))

private val documentFields =
    listOf(
        DocumentField("_id", listOf("objectId"), listOf("ObjectId('65f0c0ffee0000000000abcd')")),
        DocumentField("status", listOf("string"), listOf("'A'", "'B'", "'archived'")),
        DocumentField("total", listOf("number"), listOf("30", "10")),
        DocumentField("tags", listOf("array"), listOf("'pen'", "'ink'")),
        DocumentField("items", listOf("array")),
        DocumentField("items.sku", listOf("string"), listOf("'X-1'")),
        DocumentField("items.qty", listOf("number")),
        DocumentField("address", listOf("object")),
        DocumentField("address.city", listOf("string"), listOf("'London'")),
        DocumentField("first name", listOf("string")),
    )

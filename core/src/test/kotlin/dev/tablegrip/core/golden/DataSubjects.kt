package dev.tablegrip.core.golden

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.export.ClipboardTable
import dev.tablegrip.core.export.CopyFormat
import dev.tablegrip.core.export.ResultFormats
import dev.tablegrip.core.importer.TypeInference
import java.sql.Types
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * `core/importer/TypeInference` and `core/export` (the parts without file I/O). Values are text or null; a case that
 * needs many gives `"gen": {"count": n, "cells": ["{i}"]}` (`{i}` is the row number, `{i%k}` its remainder by `k`).
 */
internal val dataSubjects: Map<String, Subject> =
    mapOf(
        "data-inference" to { case ->
            val values =
                case["gen"]?.let { gen ->
                    val template = gen as JsonObject
                    (0 until template.int("count")).map { i -> template.strings("cells").single().let { dataTemplate(it, i) } }
                } ?: textList(case.array("values"))
            val inferred = TypeInference.infer(values)
            buildJsonObject {
                put("kind", inferred.kind.name)
                put("maxLength", inferred.maxLength)
                put("nullable", inferred.nullable)
            }
        },
        "data-clipboard" to { case ->
            when (val op = case.string("op")) {
                "parse" -> {
                    val rows = ClipboardTable.parse(case.string("text"))
                    buildJsonArray { rows.forEach { row -> add(buildJsonArray { row.forEach { add(it) } }) } }
                }
                "html" -> {
                    val header = case["header"]?.takeIf { it != JsonNull }?.let { textList(it.jsonArray).map { cell -> cell!! } }
                    JsonPrimitive(ClipboardTable.html(header, rows(case)))
                }
                "roundtrip" -> {
                    val rows = rows(case)
                    val columns = List(rows.maxOf { it.size }) { ResultColumn("c$it", "text", Types.VARCHAR) }
                    val tsv = ResultFormats.format(CopyFormat.Tsv, columns, rows, includeHeader = false)
                    buildJsonObject {
                        put("tsv", tsv)
                        put("parsed", buildJsonArray { ClipboardTable.parse(tsv).forEach { row -> add(buildJsonArray { row.forEach { add(it) } }) } })
                    }
                }
                else -> error("unknown clipboard op $op")
            }
        },
        "data-formats" to { case ->
            val format = CopyFormat.valueOf(case.string("format"))
            val columns =
                case.strings("columns").map { column ->
                    val (name, type) = column.split(':').let { it[0] to it[1] }
                    ResultColumn(name, "", Types::class.java.getField(type).getInt(null))
                }
            val rows = rows(case)
            val header = case.bool("includeHeader", default = true)
            val tableName = case.stringOrNull("tableName")
            // JSON refuses a number that doesn't fit a double (1e400); that is the one way formatting fails.
            fun format(dialect: dev.tablegrip.core.dialect.SqlDialect?): JsonPrimitive =
                try {
                    JsonPrimitive(ResultFormats.format(format, columns, rows, header, dialect, tableName))
                } catch (_: kotlinx.serialization.SerializationException) {
                    JsonPrimitive("error")
                }
            if (format == CopyFormat.SqlInsert || format == CopyFormat.SqlInList) {
                buildJsonObject {
                    for (type in DatabaseType.entries) {
                        val dialect = type.dialect ?: continue
                        put(type.id, format(dialect))
                    }
                }
            } else {
                format(null)
            }
        },
    )

private fun textList(array: JsonArray): List<String?> = array.map { (it as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.content }

private fun rows(case: JsonObject): List<List<String?>> = case.array("rows").map { textList(it.jsonArray) }

private fun dataTemplate(text: String, i: Int): String =
    Regex("\\{i(?:%(\\d+))?}").replace(text) { m -> m.groupValues[1].takeIf { it.isNotEmpty() }?.let { (i % it.toInt()).toString() } ?: i.toString() }

package dev.tablegrip.core.golden

import dev.tablegrip.core.sql.KeywordCase
import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.SqlFormatter
import dev.tablegrip.core.sql.SqlKeywords
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlParameters
import dev.tablegrip.core.sql.SqlSplitter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** `core/sql`: lexer, splitter, classifier, formatter, parameters, keywords. */
internal val sqlSubjects: Map<String, Subject> =
    mapOf(
        "sql-lexer" to { case ->
            val sql = case.string("sql")
            buildJsonArray {
                for (t in SqlLexer(syntaxOf(case)).tokenize(sql)) {
                    addJsonObject {
                        put("type", t.type.name)
                        put("start", t.start)
                        put("end", t.end)
                        put("text", t.text(sql))
                    }
                }
            }
        },
        "sql-splitter" to { case ->
            val sql = case.string("sql")
            val splitter = SqlSplitter(syntaxOf(case))
            buildJsonObject {
                putJsonArray("statements") {
                    for (s in splitter.split(sql)) {
                        addJsonObject {
                            put("text", s.text)
                            put("start", s.start)
                            put("end", s.end)
                            put("delimiterEnd", s.delimiterEnd)
                        }
                    }
                }
                case["at"]?.let { offsets ->
                    putJsonArray("statementAt") {
                        for (offset in offsets.jsonArray) {
                            val s = splitter.statementAt(sql, offset.jsonPrimitive.content.toInt())
                            if (s == null) add(JsonNull)
                            else addJsonObject {
                                put("start", s.start)
                                put("end", s.end)
                            }
                        }
                    }
                }
            }
        },
        "sql-classifier" to { case ->
            val info = SqlClassifier(syntaxOf(case)).classify(case.string("sql"))
            buildJsonObject {
                put("kind", info.kind.name)
                put("verb", info.verb)
                put("danger", info.danger)
                put("changesData", info.changesData)
            }
        },
        "sql-formatter" to { case ->
            val formatter =
                SqlFormatter(
                    syntaxOf(case),
                    indent = case.stringOrNull("indent") ?: "    ",
                    keywordCase = KeywordCase.of(case.stringOrNull("keywordCase") ?: "Upper"),
                )
            JsonPrimitive(formatter.format(case.string("sql")))
        },
        "sql-parameters" to { case ->
            val sql = case.string("sql")
            val parameters = SqlParameters(syntaxOf(case))
            buildJsonObject {
                putJsonArray("find") { parameters.find(sql).forEach { add(it) } }
                if ("values" in case) put("substitute", parameters.substitute(sql, case.stringMap("values")))
            }
        },
        "sql-keywords" to { _ -> buildJsonArray { SqlKeywords.all.sorted().forEach { add(it) } } },
    )

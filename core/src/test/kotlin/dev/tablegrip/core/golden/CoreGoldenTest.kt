package dev.tablegrip.core.golden

import dev.tablegrip.core.sql.SqlClassifier
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.SqlSyntax
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
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

/**
 * Records what the Kotlin core returns for the inputs in `conformance/core/inputs/<subject>.json`, into
 * `conformance/core/golden/<subject>.json`, so the Rust port (docs/plans/rust-migration.md) can be held to it after the
 * Kotlin code is gone.
 *
 * An input file is `{"subject": "...", "cases": [{"name": "...", ...}]}`; each subject below turns one case into its
 * output. Offsets are UTF-16 code units, as Kotlin counts them, and every span also carries its text so the Rust side
 * can check either. `-Pconformance.record=true` rewrites the golden files; without it the test fails when they drift,
 * when an input names a subject this test doesn't know, or when a golden file has no input.
 */
class CoreGoldenTest {
    private val record = System.getProperty("conformance.record") == "true"
    private val pretty = Json { prettyPrint = true }

    private val subjects: Map<String, (JsonObject) -> JsonElement> =
        mapOf(
            "sql-lexer" to { case ->
                val sql = case.string("sql")
                buildJsonArray {
                    for (t in SqlLexer(syntax(case)).tokenize(sql)) {
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
                val splitter = SqlSplitter(syntax(case))
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
                val info = SqlClassifier(syntax(case)).classify(case.string("sql"))
                buildJsonObject {
                    put("kind", info.kind.name)
                    put("verb", info.verb)
                    put("danger", info.danger)
                    put("changesData", info.changesData)
                }
            },
        )

    @Test
    fun golden() {
        val inputs = File(root, "inputs").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        val out = File(root, "golden")
        val problems = mutableListOf<String>()
        val written = mutableSetOf<String>()

        for (input in inputs) {
            val json = Json.parseToJsonElement(input.readText()).jsonObject
            val subject = json.string("subject")
            val run = subjects[subject]
            if (run == null) {
                problems += "${input.name}: unknown subject \"$subject\""
                continue
            }
            val cases = json.getValue("cases").jsonArray.map { it.jsonObject }
            val names = cases.map { it.string("name") }
            names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "${input.name}: duplicate case \"$it\"" }

            val golden = buildJsonObject {
                put("subject", subject)
                putJsonArray("cases") {
                    for (case in cases) {
                        addJsonObject {
                            put("name", case.string("name"))
                            put("output", run(case))
                        }
                    }
                }
            }
            val file = File(out, input.name)
            written += input.name
            if (record) {
                out.mkdirs()
                file.writeText(pretty.encodeToString(JsonObject.serializer(), golden) + "\n")
            } else if (!file.isFile || Json.parseToJsonElement(file.readText()) != golden) {
                problems += "${input.name}: golden differs"
            }
        }

        val stale = out.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.filter { it !in written }
        if (record) stale.forEach { File(out, it).delete() } else stale.forEach { problems += "stale golden: $it" }
        assertEquals(emptyList(), problems, "Re-record with -Pconformance.record=true if the change is intended.")
    }

    /** A deliberately wrong golden must fail, so the comparison can't pass vacuously. */
    @Test
    fun comparisonIsNotVacuous() {
        val case = buildJsonObject {
            put("sql", "select 1")
            put("syntax", "Standard")
        }
        val actual = subjects.getValue("sql-classifier")(case)
        val wrong = buildJsonObject {
            put("kind", "Write")
            put("verb", "SELECT")
            put("danger", JsonNull)
            put("changesData", true)
        }
        kotlin.test.assertNotEquals<JsonElement>(wrong, actual)
    }

    private fun syntax(case: JsonObject): SqlSyntax =
        when (val name = case["syntax"]?.jsonPrimitive?.content ?: "Standard") {
            "PostgreSQL" -> SqlSyntax.PostgreSQL
            "MySQL" -> SqlSyntax.MySQL
            "SQLite" -> SqlSyntax.SQLite
            "TSql" -> SqlSyntax.TSql
            "ClickHouse" -> SqlSyntax.ClickHouse
            "Standard" -> SqlSyntax.Standard
            else -> error("unknown syntax $name")
        }

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

    private companion object {
        /** `conformance/core`, found from Gradle's working directory (the module) or the repository root. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "conformance/core") }
                .firstOrNull { it.isDirectory }
                ?: error("conformance/core not found above ${File("").absolutePath}")
        }
    }
}

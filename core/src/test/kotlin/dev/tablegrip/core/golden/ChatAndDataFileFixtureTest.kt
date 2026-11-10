package dev.tablegrip.core.golden

import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.ChatStore
import dev.tablegrip.core.agent.SavedActivity
import dev.tablegrip.core.agent.SavedChat
import dev.tablegrip.core.agent.SavedChatMessage
import dev.tablegrip.core.agent.SavedMention
import dev.tablegrip.core.agent.SavedRun
import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.datafile.ColumnKind
import dev.tablegrip.core.datafile.DataFileFormats
import dev.tablegrip.core.datafile.OutputColumn
import dev.tablegrip.core.datafile.OutputTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Writes agent chats with the Kotlin [ChatStore] into `conformance/core/fixtures/chats/`, and data files with what the
 * Kotlin formats read from them and write back into `conformance/core/fixtures/datafiles/`, so the Rust port (tg-agent,
 * tg-data) can be held to reading and writing the same. `<name>.read.json` is what a format reads from `<name>`;
 * `<name>.written.<ext>` is what it writes for the tables of [edited].
 *
 * `-Pconformance.record=true` rewrites the fixture; without it the test fails when Kotlin would write something else.
 * Workbooks are compared part by part: their zip entries carry the time they were written.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ChatAndDataFileFixtureTest {
    private val record = System.getProperty("conformance.record") == "true"

    @Test
    fun chats() = check("fixtures/chats") { dir -> chats(dir) }

    @Test
    fun dataFiles() = check("fixtures/datafiles") { dir -> dataFiles(dir) }

    private fun check(path: String, generate: (Path) -> Unit) {
        val generated = Files.createTempDirectory("fixture")
        try {
            generate(generated)
            val committed = File(root, path).toPath()
            if (record) {
                if (Files.exists(committed)) committed.deleteRecursively()
                committed.createDirectories()
                for (file in generated.walk().filter { it.isRegularFile() }) {
                    val target = committed.resolve(file.relativeTo(generated).toString())
                    target.parent.createDirectories()
                    Files.copy(file, target)
                }
            } else {
                assertEquals(files(generated), files(committed), "Re-record with -Pconformance.record=true if the change is intended.")
            }
        } finally {
            generated.deleteRecursively()
        }
    }

    /** Text by relative path; a workbook is listed as its parts, `book.xlsx!xl/workbook.xml`. */
    private fun files(dir: Path): Map<String, String> =
        if (!Files.isDirectory(dir)) emptyMap()
        else
            dir.walk().filter { it.isRegularFile() }.flatMap { file ->
                val name = file.relativeTo(dir).toString().replace('\\', '/')
                if (name.endsWith(".xlsx")) {
                    ZipFile(file.toFile()).use { zip -> zip.entries().toList().map { "$name!${it.name}" to String(zip.getInputStream(it).readAllBytes()) } }
                } else {
                    listOf(name to String(file.readBytes(), Charsets.ISO_8859_1))
                }
            }.toMap()

    private fun chats(dir: Path) {
        val store = ChatStore(dir)
        store.save(SavedChat("chat-empty", "Nothing yet", createdAt = 1_761_900_000_000, updatedAt = 1_761_900_000_000))
        store.save(
            SavedChat(
                "7d0c-41aa",
                "Orders per customer — “top 10”",
                createdAt = 1_761_903_600_000,
                updatedAt = 1_761_907_200_123,
                agent = "claude-code",
                messages =
                    listOf(
                        SavedChatMessage(
                            AgentMessage.Role.User,
                            "How many rows in  and ?\nTab\there, emoji 🙂",
                            mentions =
                                linkedMapOf(
                                    "" to SavedMention("orders", "PostgreSQL table public.orders on connection shop:\nid int, total numeric"),
                                    "" to SavedMention("console 1:3-4", "PostgreSQL SQL from console 1, lines 3-4:\n```sql\nselect 1\n```"),
                                ),
                        ),
                        SavedChatMessage(
                            AgentMessage.Role.Assistant,
                            "Run this:\n```sql\nSELECT count(*) FROM orders\n```",
                            reasoning = "The user wants a count.",
                            thoughtSeconds = 3,
                            activities =
                                listOf(
                                    SavedActivity("a1", "Read schema", AgentEvent.Activity.State.Done),
                                    SavedActivity("a2", "Ran \"psql\"", AgentEvent.Activity.State.Failed),
                                ),
                            runs = listOf(SavedRun(0, SavedRun.State.Done, "1 row(s) sent to the agent"), SavedRun(1, SavedRun.State.NotShared)),
                        ),
                        SavedChatMessage(AgentMessage.Role.User, "count = 42", note = true, label = "Result shared", origin = 1, originBlock = 0),
                        SavedChatMessage(AgentMessage.Role.Assistant, "", error = "The agent stopped: exit code 1"),
                    ),
            )
        )
    }

    private val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun dataFiles(dir: Path) {
        // windows-1252, semicolons, a quoted field with a line break and an empty cell.
        dir.resolve("people.csv").writeBytes("name;city;note;age\r\nRené;Köln;\"line one\r\nline two\";41\r\nZoë;;\"say \"\"hi\"\"\";\r\n".toByteArray(Charsets.ISO_8859_1))
        dir.resolve("plain.csv").writeText("id,label\n1,a\n2,\n")
        dir.resolve("orders.json").writeText(
            """[{"id":1,"customer":{"name":"Ann","tags":["a","b"]},"total":12.50,"paid":true,"note":null},""" +
                """{"id":2,"customer":{"name":"Bo"},"total":1e3,"paid":false,"extra":"only here"}]"""
        )
        dir.resolve("nested.json").writeText("""{"meta":{"v":1},"rows":[{"a":1},{"a":2,"b":"x"}],"other":[1,2]}""")
        workbook(dir.resolve("book.xlsx"))
        val options = DataFileOptions()
        for (name in listOf("people.csv", "plain.csv", "orders.json", "nested.json", "book.xlsx")) {
            val path = dir.resolve(name)
            val format = DataFileFormats.detect(path) ?: error(name)
            format.open(path, options).use { document ->
                val tables = document.tables.map { table -> Triple(table.name, table.columns, table.rows().toList()) }
                val read =
                    buildJsonObject {
                        put("multiTable", document.multiTable)
                        put("readOnlyReason", document.readOnlyReason?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("notes") { document.notes.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("tables") {
                            tables.forEach { (table, columns, rows) ->
                                add(
                                    buildJsonObject {
                                        put("name", table)
                                        putJsonArray("columns") { columns.forEach { add(JsonPrimitive(it)) } }
                                        putJsonArray("rows") {
                                            rows.forEach { row -> add(kotlinx.serialization.json.JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull })) }
                                        }
                                    }
                                )
                            }
                        }
                    }
                dir.resolve("$name.read.json").writeText(read.toString() + "\n")
                if (document.readOnlyReason != null) return@use
                val extension = name.substringAfterLast('.')
                val output = tables.mapIndexed { i, (table, columns, rows) -> edited(table, columns, rows, changed = i == 0) }
                val added = if (document.multiTable) listOf(OutputTable("Added", null, true, listOf(OutputColumn("n", ColumnKind.Integer))) { sequenceOf(listOf(1L), listOf(null)) }) else emptyList()
                document.write(dir.resolve("${name.substringBeforeLast('.')}.written.$extension"), output + added, emptySet())
            }
        }
    }

    /**
     * What is written back: the table as read with one more row, of a value per kind cycled across its columns, and its
     * last column typed by kind. An unchanged table is passed as read.
     */
    companion object {
        fun edited(name: String, columns: List<String>, rows: List<List<String?>>, changed: Boolean): OutputTable {
            if (!changed) return OutputTable(name, name, false, columns.map { OutputColumn(it, ColumnKind.Text) }) { rows.asSequence() }
            val values = listOf<Any?>(42L, 2.5, "new \"row\", ü\nnext", null, -7L, 1.0E-7, "")
            val extra = columns.indices.map { values[it % values.size] }
            val kinds = columns.indices.map { i -> if (i == columns.lastIndex) ColumnKind.Integer else ColumnKind.Text }
            val lastAsNumber = rows.map { row -> row.mapIndexed { i, v -> if (i == columns.lastIndex) v?.toLongOrNull() ?: v else v } }
            return OutputTable(name, name, true, columns.zip(kinds) { c, k -> OutputColumn(c, k) }) { (lastAsNumber + listOf(extra)).asSequence() }
        }

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "conformance/core") }.firstOrNull { it.isDirectory }
                ?: error("conformance/core not found above ${File("").absolutePath}")
        }
    }

    /** A workbook as Excel lays it out: shared strings, a date column, a frozen header, a formula sheet and a calc chain. */
    private fun workbook(file: Path) {
        val parts =
            linkedMapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                    """<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                    """<Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/></Types>""",
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$main" xmlns:r="$rel"><sheets>""" +
                    """<sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/>""" +
                    """<Relationship Id="rId3" Type="$rel/styles" Target="styles.xml"/><Relationship Id="rId4" Type="$rel/sharedStrings" Target="sharedStrings.xml"/>""" +
                    """<Relationship Id="rId5" Type="$rel/calcChain" Target="calcChain.xml"/></Relationships>""",
                "xl/styles.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$main"><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/><xf numFmtId="0" fontId="1"/></cellXfs></styleSheet>""",
                "xl/sharedStrings.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$main"><si><t>item</t></si><si><t>sold</t></si><si><t>qty</t></si><si><t>Pen &amp; nib</t></si><si><r><t>In</t></r><r><t>k</t></r></si></sst>""",
                "xl/worksheets/sheet1.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main" xmlns:r="$rel"><dimension ref="A1:C3"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews>""" +
                    """<cols><col min="1" max="1" width="30" customWidth="1"/></cols><sheetData>""" +
                    """<row r="1"><c r="A1" s="2" t="s"><v>0</v></c><c r="B1" s="2" t="s"><v>1</v></c><c r="C1" s="2" t="s"><v>2</v></c></row>""" +
                    """<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>45292</v></c><c r="C2"><v>3</v></c></row>""" +
                    """<row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3" s="1"><v>45293.5</v></c><c r="C3"><v>10.25</v></c></row>""" +
                    """</sheetData><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>""",
                "xl/worksheets/sheet2.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main"><sheetData>""" +
                    """<row r="1"><c r="A1" t="inlineStr"><is><t>total</t></is></c><c r="B1" t="b"><v>1</v></c></row><row r="2"><c r="A2"><f>SUM(Sales!C2:C3)</f><v>13.25</v></c></row>""" +
                    """</sheetData></worksheet>""",
                "xl/calcChain.xml" to """<?xml version="1.0" encoding="UTF-8"?><calcChain xmlns="$main"><c r="A2" i="2"/></calcChain>""",
            )
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 1_761_900_000_000 })
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
}

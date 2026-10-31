package dev.datagrok.core.datafile

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DataFileOptions
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.metadata.TableRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DataFileStoreTest {
    private val dir: Path = createTempDirectory("datafiles")
    private val drivers = DriverLibrary(Files.createTempDirectory("drivers"))
    private var previousHome: String? = null

    @BeforeTest
    fun home() {
        previousHome = System.getProperty("datagrok.home")
        System.setProperty("datagrok.home", dir.resolve("home").toString())
    }

    @AfterTest
    fun cleanUp() {
        previousHome?.let { System.setProperty("datagrok.home", it) } ?: System.clearProperty("datagrok.home")
        dir.toFile().deleteRecursively()
    }

    private fun config(file: Path, type: DatabaseType, options: DataFileOptions? = null, readOnly: Boolean = false) =
        ConnectionConfig(id = UUID.randomUUID().toString(), name = file.fileName.toString(), type = type, host = "", database = file.toString(), dataFile = options, readOnly = readOnly)

    private fun open(file: Path, type: DatabaseType, options: DataFileOptions? = null, readOnly: Boolean = false, block: suspend (DataFileConnection) -> Unit) =
        runBlocking { DataFileStore.connect(config(file, type, options, readOnly), drivers).use { block(it) } }

    private suspend fun DataFileConnection.sql(sql: String): QueryResult = openSession().use { it.execute(sql) }

    private suspend fun DataFileConnection.rows(sql: String): List<List<String?>> = (sql(sql) as QueryResult.Rows).rows

    // region CSV

    @Test
    fun `a CSV keeps its delimiter, encoding, line endings and text when a cell changes`() {
        val file = dir.resolve("orders.csv")
        val original = "id;code;price;note\r\n1;007;1.50;\"semi;colon\"\r\n2;12;3;\r\n3;;2.25;\"\"\r\n"
        file.writeBytes(original.toByteArray(Charsets.ISO_8859_1))
        open(file, DatabaseType.CsvFile) { connection ->
            assertEquals(listOf("orders"), connection.tables(connection.namespaces().first()).map { it.ref.name })
            // "007" and "1.50" don't read back the same as numbers, so their columns stay text; id is a number.
            val types = connection.rows("SELECT name, type FROM pragma_table_info('orders')").associate { it[0]!! to it[1]!! }
            assertEquals(mapOf("_row" to "INTEGER", "id" to "INTEGER", "code" to "TEXT", "price" to "TEXT", "note" to "TEXT"), types)
            connection.sync.save()
            assertContentEquals(original.toByteArray(Charsets.ISO_8859_1), file.readBytes(), "an unchanged table isn't written")

            connection.sql("UPDATE orders SET note = 'café' WHERE id = 2")
            connection.sync.save()
            assertIs<DataFileState.InSync>(connection.sync.state.value)
        }
        val expected = "id;code;price;note\r\n1;007;1.50;\"semi;colon\"\r\n2;12;3;café\r\n3;;2.25;\"\"\r\n"
        assertEquals(expected, file.readText())
    }

    @Test
    fun `rows added, edited and deleted through SQL are written in order, without the row key`() {
        val file = dir.resolve("people.csv")
        file.writeText("name,age\nAda,36\nBob,41\n")
        open(file, DatabaseType.CsvFile) { connection ->
            connection.sql("INSERT INTO people (name, age) VALUES ('Cy', 7)")
            connection.sql("DELETE FROM people WHERE name = 'Ada'")
            connection.sql("UPDATE people SET age = age + 1 WHERE name = 'Bob'")
            connection.sync.save()
        }
        assertEquals("name,age\nBob,42\nCy,7\n", file.readText())
    }

    @Test
    fun `console changes are saved by polling, and a read-only profile never writes`() {
        val file = dir.resolve("poll.csv")
        file.writeText("a\n1\n")
        open(file, DatabaseType.CsvFile) { connection ->
            connection.sql("INSERT INTO poll (a) VALUES (2)")
            withTimeout(5_000) { while (!file.readText().contains("2")) delay(50) }
        }
        assertEquals("a\n1\n2\n", file.readText())
        open(file, DatabaseType.CsvFile, readOnly = true) { connection ->
            assertFailsWith<Exception> { connection.sql("INSERT INTO poll (a) VALUES (3)") }
        }
        assertEquals("a\n1\n2\n", file.readText())
    }

    @Test
    fun `a change on disk is read in when nothing is unsaved, and held when something is`() {
        val file = dir.resolve("live.csv")
        file.writeText("k,v\na,1\n")
        open(file, DatabaseType.CsvFile) { connection ->
            file.writeText("k,v\na,1\nb,2\n")
            withTimeout(5_000) { while (connection.sync.revision.value == 0L) delay(50) }
            assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), connection.rows("SELECT k, v FROM live ORDER BY k"))

            // Hold a change made here against one made on disk.
            connection.sync.save()
            Thread.sleep(20)
            file.writeText("k,v\nz,9\n")
            connection.sql("UPDATE live SET v = 5 WHERE k = 'a'")
            withTimeout(5_000) { while (connection.sync.state.value !is DataFileState.Held) delay(50) }
            assertEquals(DataFileState.Held.Reason.ChangedOnDisk, (connection.sync.state.value as DataFileState.Held).reason)
            assertEquals("k,v\nz,9\n", file.readText(), "held changes don't overwrite the file")

            connection.sync.save(force = true)
            assertEquals("k,v\na,5\nb,2\n", file.readText())
            assertIs<DataFileState.InSync>(connection.sync.state.value)
        }
    }

    @Test
    fun `a file without a header gets numbered columns and is written without one`() {
        val file = dir.resolve("raw.csv")
        file.writeText("x,1\ny,2\n")
        open(file, DatabaseType.CsvFile, DataFileOptions(hasHeader = false)) { connection ->
            assertEquals(listOf(listOf("x", "1"), listOf("y", "2")), connection.rows("SELECT column_1, column_2 FROM raw ORDER BY _row"))
            connection.sql("UPDATE raw SET column_2 = 3 WHERE column_1 = 'y'")
            connection.sync.save()
        }
        assertEquals("x,1\ny,3\n", file.readText())
    }

    @Test
    fun `tables beyond the file's one stay in the session`() {
        val file = dir.resolve("one.csv")
        file.writeText("a\n1\n")
        open(file, DatabaseType.CsvFile) { connection ->
            connection.sql("CREATE TABLE scratch (b TEXT)")
            connection.sync.save()
            assertEquals(listOf("scratch"), (connection.sync.state.value as DataFileState.InSync).sessionOnly)
        }
        assertEquals("a\n1\n", file.readText())
    }

    @Test
    fun `an unreadable file fails with what's wrong and leaves no working copy behind`() {
        val file = dir.resolve("bad.csv")
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
        assertFailsWith<DataFileException> { runBlocking { DataFileStore.connect(config(file, DatabaseType.CsvFile, DataFileOptions(charset = "UTF-8")), drivers) } }
        assertFailsWith<DataFileException> { runBlocking { DataFileStore.connect(config(dir.resolve("missing.csv"), DatabaseType.CsvFile), drivers) } }
        val copies = dir.resolve("home/cache/datafiles").toFile().listFiles().orEmpty()
        assertTrue(copies.isEmpty(), copies.joinToString())
    }

    // endregion

    // region Excel

    private val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** A workbook as Excel lays it out: a data sheet with a date column and frozen panes, a formula sheet and a calc chain. */
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
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$main" xmlns:r="$rel"><bookViews><workbookView activeTab="1"/></bookViews><sheets>""" +
                    """<sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/></sheets>""" +
                    """<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Sales!${'$'}A${'$'}1:${'$'}C${'$'}3</definedName></definedNames></workbook>""",
                "xl/_rels/workbook.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/>""" +
                    """<Relationship Id="rId3" Type="$rel/styles" Target="styles.xml"/><Relationship Id="rId4" Type="$rel/sharedStrings" Target="sharedStrings.xml"/>""" +
                    """<Relationship Id="rId5" Type="$rel/calcChain" Target="calcChain.xml"/></Relationships>""",
                "xl/styles.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$main"><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/><xf numFmtId="0" fontId="1"/></cellXfs></styleSheet>""",
                "xl/sharedStrings.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$main"><si><t>item</t></si><si><t>sold</t></si><si><t>qty</t></si><si><t>Pen</t></si><si><t>Ink</t></si></sst>""",
                "xl/worksheets/sheet1.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main" xmlns:r="$rel"><dimension ref="A1:C3"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews>""" +
                    """<cols><col min="1" max="1" width="30" customWidth="1"/></cols><sheetData>""" +
                    """<row r="1"><c r="A1" s="2" t="s"><v>0</v></c><c r="B1" s="2" t="s"><v>1</v></c><c r="C1" s="2" t="s"><v>2</v></c></row>""" +
                    """<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>45292</v></c><c r="C2"><v>3</v></c></row>""" +
                    """<row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3" s="1"><v>45293</v></c><c r="C3"><v>10</v></c></row>""" +
                    """</sheetData><autoFilter ref="A1:C3"/><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>""",
                "xl/worksheets/sheet2.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main"><sheetData>""" +
                    """<row r="1"><c r="A1" t="inlineStr"><is><t>total</t></is></c></row><row r="2"><c r="A2"><f>SUM(Sales!C2:C3)</f><v>13</v></c></row>""" +
                    """</sheetData></worksheet>""",
                "xl/calcChain.xml" to """<?xml version="1.0" encoding="UTF-8"?><calcChain xmlns="$main"><c r="A2" i="2"/></calcChain>""",
            )
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun part(file: Path, name: String): String? = ZipFile(file.toFile()).use { zip -> zip.getEntry(name)?.let { String(zip.getInputStream(it).readAllBytes()) } }

    @Test
    fun `an edited sheet keeps its layout, styles and dates, and untouched sheets are copied as they were`() {
        val file = dir.resolve("book.xlsx")
        workbook(file)
        val summary = part(file, "xl/worksheets/sheet2.xml")
        open(file, DatabaseType.ExcelFile) { connection ->
            assertEquals(listOf("Sales", "Summary"), connection.tables(connection.namespaces().first()).map { it.ref.name })
            assertEquals(listOf(listOf("Pen", "2024-01-01", "3"), listOf("Ink", "2024-01-02", "10")), connection.rows("SELECT item, sold, qty FROM Sales ORDER BY _row"))
            connection.sql("UPDATE Sales SET qty = 4, sold = '2024-02-29' WHERE item = 'Pen'")
            connection.sql("INSERT INTO Sales (item, sold, qty) VALUES ('Nib', '2024-03-01', 1)")
            connection.sync.save()
            assertIs<DataFileState.InSync>(connection.sync.state.value)
        }
        val sales = part(file, "xl/worksheets/sheet1.xml")!!
        assertTrue("""<pane ySplit="1" topLeftCell="A2" state="frozen"/>""" in sales && """<col min="1" max="1" width="30" customWidth="1"/>""" in sales, sales)
        assertTrue("""<autoFilter ref="A1:C4"/>""" in sales, "the filter grows with the new row: $sales")
        assertFalse("<dimension" in sales, "the stale dimension is dropped")
        // Header keeps its style; dates go back as serials with the date style; numbers as numbers.
        assertTrue("""<c r="A1" s="2" t="inlineStr"><is><t>item</t></is></c>""" in sales, sales)
        assertTrue("""<c r="B2" s="1"><v>45351</v></c>""" in sales && """<c r="C2"><v>4</v></c>""" in sales, sales)
        assertTrue("""<row r="4"><c r="A4" t="inlineStr"><is><t>Nib</t></is></c><c r="B4" s="1"><v>45352</v></c><c r="C4"><v>1</v></c></row>""" in sales, sales)
        assertEquals(summary, part(file, "xl/worksheets/sheet2.xml"), "the formula sheet wasn't touched")
        assertNull(part(file, "xl/calcChain.xml"), "Excel rebuilds the calc chain")
        assertFalse("calcChain" in part(file, "[Content_Types].xml")!! || "calcChain" in part(file, "xl/_rels/workbook.xml.rels")!!)

        // And it reads back the same.
        open(file, DatabaseType.ExcelFile) { connection ->
            assertEquals(
                listOf(listOf("Pen", "2024-02-29", "4"), listOf("Ink", "2024-01-02", "10"), listOf("Nib", "2024-03-01", "1")),
                connection.rows("SELECT item, sold, qty FROM Sales ORDER BY _row"),
            )
        }
    }

    @Test
    fun `data rows keep their own look when a blank-looking row sits above the styled header`() {
        val file = dir.resolve("banner.xlsx")
        workbook(file)
        // Row 1 only holds a blank shared string (Excel leaves these behind); the styled header is row 2.
        val sheet =
            """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main"><sheetData>""" +
                """<row r="1"><c r="A1" t="s"><v>5</v></c></row>""" +
                """<row r="2"><c r="A2" s="2" t="s"><v>0</v></c><c r="B2" s="2" t="s"><v>2</v></c></row>""" +
                """<row r="3"><c r="A3" t="s"><v>3</v></c><c r="B3"><v>3</v></c></row>""" +
                """</sheetData></worksheet>"""
        rewrite(file, mapOf("xl/worksheets/sheet1.xml" to sheet, "xl/sharedStrings.xml" to
            """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$main"><si><t>item</t></si><si><t>sold</t></si><si><t>qty</t></si><si><t>Pen</t></si><si><t>Ink</t></si><si><t xml:space="preserve"> </t></si></sst>"""))
        open(file, DatabaseType.ExcelFile) { connection ->
            assertEquals(listOf(listOf("Pen", "3")), connection.rows("SELECT item, qty FROM Sales"))
            connection.sql("UPDATE Sales SET qty = 4")
            connection.sync.save()
        }
        val sales = part(file, "xl/worksheets/sheet1.xml")!!
        assertTrue("""<row r="2"><c r="A2" s="2" t="inlineStr"><is><t>item</t></is></c>""" in sales, "the header stays on row 2 with its style: $sales")
        assertTrue(sales.contains("""<sheetData><row r="1"><c r="A1" t="s"><v>5</v></c></row><row r="2">"""), "the row above the header is kept: $sales")
        assertTrue("""<row r="3"><c r="A3" t="inlineStr"><is><t>Pen</t></is></c><c r="B3"><v>4</v></c></row>""" in sales, "data rows don't take the header's style: $sales")
    }

    /** Replaces parts of a workbook. */
    private fun rewrite(file: Path, parts: Map<String, String>) {
        val entries = ZipFile(file.toFile()).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readAllBytes() } }
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(parts[name]?.toByteArray() ?: bytes)
                out.closeEntry()
            }
        }
    }

    @Test
    fun `changing a sheet with formulas asks first, then writes their values`() {
        val file = dir.resolve("formulas.xlsx")
        workbook(file)
        open(file, DatabaseType.ExcelFile) { connection ->
            assertEquals(1, connection.sync.notes.size)
            connection.sql("UPDATE Summary SET total = 14")
            connection.sync.save()
            val held = assertIs<DataFileState.Held>(connection.sync.state.value)
            assertEquals(DataFileState.Held.Reason.LosesContent, held.reason)
            assertTrue("<f>" in part(file, "xl/worksheets/sheet2.xml")!!)
            connection.sync.save(force = true)
            assertIs<DataFileState.InSync>(connection.sync.state.value)
        }
        assertEquals(false, "<f>" in part(file, "xl/worksheets/sheet2.xml")!!)
    }

    @Test
    fun `tables created, renamed and dropped with SQL become sheets, renames and removals`() {
        val file = dir.resolve("structure.xlsx")
        workbook(file)
        open(file, DatabaseType.ExcelFile) { connection ->
            connection.sql("CREATE TABLE Notes (id INTEGER, text TEXT)")
            connection.sql("INSERT INTO Notes VALUES (1, 'a < b & c')")
            connection.sql("ALTER TABLE Sales RENAME TO Orders")
            connection.sql("DROP TABLE Summary")
            connection.sync.save()
            assertIs<DataFileState.InSync>(connection.sync.state.value, connection.sync.state.value.toString())
        }
        val workbook = part(file, "xl/workbook.xml")!!
        assertTrue("""name="Orders"""" in workbook && """name="Notes"""" in workbook && "Summary" !in workbook, workbook)
        assertTrue("Orders!" in workbook, "defined names follow the rename: $workbook")
        assertTrue("""activeTab="0"""" in workbook)
        assertTrue(workbook.indexOf("Orders") < workbook.indexOf("Notes"), "the new sheet goes last")
        assertNull(part(file, "xl/worksheets/sheet2.xml"))
        open(file, DatabaseType.ExcelFile) { connection ->
            assertEquals(setOf("Orders", "Notes"), connection.tables(connection.namespaces().first()).map { it.ref.name }.toSet())
            assertEquals(listOf(listOf("1", "a < b & c")), connection.rows("SELECT id, text FROM Notes"))
        }
    }

    @Test
    fun `a table name a sheet can't have is refused with the reason`() {
        val file = dir.resolve("names.xlsx")
        workbook(file)
        val before = file.readBytes()
        open(file, DatabaseType.ExcelFile) { connection ->
            connection.sql("CREATE TABLE \"a/b\" (x TEXT)")
            connection.sync.save()
            val failed = assertIs<DataFileState.Failed>(connection.sync.state.value)
            assertTrue("a/b" in failed.message, failed.message)
        }
        assertContentEquals(before, file.readBytes())
    }

    // endregion

    // region JSON

    @Test
    fun `JSON keeps its shape, indentation, value types and missing keys`() {
        val file = dir.resolve("items.json")
        file.writeText(
            """
            [
              {
                "id": 1,
                "name": "pen",
                "zip": "007",
                "ok": true,
                "tags": ["a", "b"]
              },
              {
                "id": 2,
                "name": "ink",
                "zip": "123",
                "ok": false,
                "tags": [],
                "extra": null
              }
            ]
            """.trimIndent() + "\n",
        )
        open(file, DatabaseType.JsonFile) { connection ->
            connection.sql("UPDATE items SET name = 'nib', ok = 'true' WHERE id = 2")
            connection.sync.save()
        }
        val written = file.readText()
        assertEquals(
            """
            [
              {
                "id": 1,
                "name": "pen",
                "zip": "007",
                "ok": true,
                "tags": ["a", "b"],
                "extra": null
              },
              {
                "id": 2,
                "name": "nib",
                "zip": "123",
                "ok": true,
                "tags": [],
                "extra": null
              }
            ]
            """.trimIndent().replace("[\"a\", \"b\"]", "[\n      \"a\",\n      \"b\"\n    ]") + "\n",
            written,
        )
    }

    @Test
    fun `NDJSON stays one record per line and leaves out keys that were missing`() {
        val file = dir.resolve("events.ndjson")
        file.writeText("{\"t\":1,\"msg\":\"a\"}\n{\"t\":2}\n")
        open(file, DatabaseType.JsonFile) { connection ->
            connection.sql("INSERT INTO events (t) VALUES (3)")
            connection.sync.save()
        }
        assertEquals("{\"t\":1,\"msg\":\"a\"}\n{\"t\":2}\n{\"t\":3}\n", file.readText())
    }

    @Test
    fun `JSON it can't write back opens read-only`() {
        val file = dir.resolve("wrapped.json")
        file.writeText("""{"data": [{"a": 1}, {"a": 2}]}""")
        open(file, DatabaseType.JsonFile) { connection ->
            assertEquals(2, connection.rows("SELECT * FROM wrapped").size)
            assertFailsWith<Exception> { connection.sql("DELETE FROM wrapped") }
            assertTrue(connection.sync.readOnlyReason!!.isNotBlank())
        }
    }

    // endregion

    @Test
    fun `edits through the table viewer's path are saved before it returns`() {
        val file = dir.resolve("grid.csv")
        file.writeText("id,v\n1,a\n")
        open(file, DatabaseType.CsvFile) { connection ->
            val table = TableRef(connection.namespaces().first(), "grid")
            val columns = connection.tableColumns(table).columns
            connection.insertRows(table, columns.filter { it.name != "_row" }, sequenceOf(listOf("2", "b")), values = { it })
            assertEquals("id,v\n1,a\n2,b\n", file.readText())
        }
    }

    @Test
    fun `profiles for dropped files are found by extension`() {
        assertEquals(DatabaseType.ExcelFile, DataFileStore.profileFor(Path.of("/x/Book.XLSX"), "id")?.type)
        assertEquals(DatabaseType.CsvFile, DataFileStore.profileFor(Path.of("/x/a.tsv"), "id")?.type)
        assertNull(DataFileStore.profileFor(Path.of("/x/a.db"), "id"))
    }

    @Test
    fun `numbers only become numbers when they read back unchanged`() {
        assertTrue(DataValues.isInteger("42") && DataValues.isInteger("-7"))
        assertFalse(DataValues.isInteger("007") || DataValues.isInteger("+5") || DataValues.isInteger("-0") || DataValues.isInteger(" 1"))
        assertTrue(DataValues.isReal("1.5") && DataValues.isReal("0.1") && DataValues.isReal("3") && DataValues.isReal("0.0000001"))
        assertFalse(DataValues.isReal("1.50") || DataValues.isReal("1e5") || DataValues.isReal("NaN") || DataValues.isReal("12345678901234567890"))
        assertEquals("45351", XlsxFormat.excelSerial("2024-02-29", date1904 = false))
        assertEquals("1", XlsxFormat.excelSerial("1900-01-01", date1904 = false))
        assertEquals("61", XlsxFormat.excelSerial("1900-03-01", date1904 = false))
        assertEquals("0.5", XlsxFormat.excelSerial("12:00:00", date1904 = false))
        assertEquals("AA", XlsxFormat.columnLetters(26))
    }
}

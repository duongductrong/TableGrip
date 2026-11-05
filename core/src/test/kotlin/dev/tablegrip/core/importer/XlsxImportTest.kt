package dev.tablegrip.core.importer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XlsxImportTest {
    private val dir = createTempDirectory("xlsx-import").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** The minimal parts Excel itself writes, with sheets given as `<sheetData>` bodies in order. */
    private fun workbook(sheets: List<Pair<String, String>>, sharedStrings: List<String> = emptyList(), date1904: Boolean = false): File {
        val file = File(dir, "book.xlsx")
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts =
            linkedMapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$main" xmlns:r="$rel"><workbookPr${if (date1904) " date1904=\"1\"" else ""}/><sheets>""" +
                    sheets.indices.joinToString("") { """<sheet name="${sheets[it].first}" sheetId="${it + 1}" r:id="rIdS${it + 1}"/>""" } +
                    "</sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    // Sheet targets mix relative and absolute forms, as different writers do.
                    sheets.indices.joinToString("") {
                        val target = if (it == 0) "worksheets/sheet1.xml" else "/xl/worksheets/sheet${it + 1}.xml"
                        """<Relationship Id="rIdS${it + 1}" Type="$rel/worksheet" Target="$target"/>"""
                    } +
                    """<Relationship Id="rIdSt" Type="$rel/styles" Target="styles.xml"/><Relationship Id="rIdSs" Type="$rel/sharedStrings" Target="sharedStrings.xml"/>""" +
                    "</Relationships>",
                "xl/styles.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$main">""" +
                    """<numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd\ hh:mm"/><numFmt numFmtId="165" formatCode="0.0&quot; days&quot;;[Red]\-0.0"/></numFmts>""" +
                    // A named style using a date format must not make cell style 0 a date.
                    """<cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>""" +
                    """<cellXfs count="5"><xf numFmtId="0" xfId="0"/><xf numFmtId="14" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" xfId="0"/>""" +
                    """<xf numFmtId="165" xfId="0"/><xf numFmtId="20" xfId="0"/></cellXfs>""" +
                    """<dxfs count="1"><dxf><numFmt numFmtId="165" formatCode="yyyy"/></dxf></dxfs></styleSheet>""",
                "xl/sharedStrings.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">""" +
                    sharedStrings.joinToString("") { "<si>$it</si>" } + "</sst>",
            )
        sheets.forEachIndexed { i, (_, data) ->
            parts["xl/worksheets/sheet${i + 1}.xml"] =
                """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main" xmlns:r="$rel"><dimension ref="A1:Z99"/><sheetData>$data</sheetData></worksheet>"""
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val strings =
        listOf(
            "<t>name</t>",
            "<t>born</t>",
            "<t>total</t>",
            // Rich text runs, with a phonetic hint that isn't part of the text.
            """<r><rPr><b/></rPr><t>A</t></r><r><t xml:space="preserve">da </t></r><rPh sb="0" eb="1"><t>ignored</t></rPh>""",
            "<t>Bob_x000D_\nSmith</t>",
            "<t></t>",
        )

    private val people =
        """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>active</t></is></c>""" +
            """<c r="D1" t="s"><v>2</v></c><c r="E1" t="inlineStr"><is><r><t>no</t></r><r><t>te</t></r></is></c></row>""" +
            """<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>36526</v></c><c r="C2" t="b"><v>1</v></c>""" +
            """<c r="D2"><f>1+2</f><v>3</v></c><c r="E2" s="3"><v>2.5</v></c></row>""" +
            // Row 3 is missing; row 4 is sparse, with a formatting-only cell far to the right.
            """<row r="4"><c r="A4" t="s"><v>4</v></c><c r="B4" s="2"><v>36526.5</v></c><c r="C4" t="e"><v>#N/A</v></c>""" +
            """<c r="D4"><v>1.50</v></c><c r="H4" s="1"/></row>""" +
            // Cells without references follow the previous one.
            """<row><c t="str"><f>"x"&amp;"y"</f><v>xy</v></c><c t="b"><v>0</v></c><c><v>1E-3</v></c><c><v>12345678901234567890</v></c>""" +
            """<c t="s"><v>5</v></c></row>"""

    @Test
    fun `reads strings, numbers, dates, booleans, formulas and sparse rows`() {
        val file = workbook(listOf("People" to people, "Other" to """<row r="1"><c r="A1" t="inlineStr"><is><t>k</t></is></c></row><row r="2"><c r="A2"><v>1</v></c></row>"""), strings)
        ImportReader.open(file, ImportOptions(ImportFormat.Xlsx)).use { source ->
            assertEquals(listOf("People", "Other"), source.sheets)
            assertEquals("People", source.options.sheet)
            assertEquals(listOf("name", "born", "active", "total", "note"), source.columns)
            val rows = source.rows().toList()
            assertEquals(listOf(2L, 4L, 5L), rows.map { it.line })
            assertEquals(listOf("Ada ", "2000-01-01", "true", "3", "2.5"), rows[0].values)
            assertEquals(listOf("Bob\r\nSmith", "2000-01-01 12:00:00", null, "1.5", null), rows[1].values)
            assertEquals(listOf("xy", "false", "0.001", "12345678901234567890", null), rows[2].values)
        }
    }

    @Test
    fun `second sheet, no header and skip rows`() {
        val file = workbook(listOf("A" to people, "Other" to """<row r="1"><c r="A1" t="inlineStr"><is><t>title</t></is></c></row><row r="2"><c r="A2"><v>1</v></c><c r="C2"><v>3</v></c></row>"""))
        ImportReader.open(file, ImportOptions(ImportFormat.Xlsx, sheet = "Other", hasHeader = false, skipRows = 1)).use { source ->
            assertEquals(listOf("column_1", "column_2", "column_3"), source.columns)
            assertEquals(listOf(listOf("1", null, "3")), source.rows().map { it.values }.toList())
        }
        val missing = assertFailsWith<ImportException> { ImportReader.open(file, ImportOptions(ImportFormat.Xlsx, sheet = "Nope")) }
        assertEquals("There's no sheet named \"Nope\"; the workbook has \"A\", \"Other\"", missing.message)
    }

    @Test
    fun `data wider than the header gets generated names and preview sizes from its rows`() {
        val data = """<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c></row><row r="2"><c r="A2"><v>1</v></c><c r="B2"><v>2</v></c></row>""" +
            """<row r="3"><c r="D3"><v>4</v></c></row>"""
        val file = workbook(listOf("S" to data))
        ImportReader.open(file, ImportOptions(ImportFormat.Xlsx)).use { assertEquals(listOf("a", "column_2", "column_3", "column_4"), it.columns) }
        val preview = ImportReader.preview(file, ImportOptions(ImportFormat.Xlsx), limit = 1)
        assertEquals(listOf("a", "column_2"), preview.columns)
        assertEquals(listOf("1", "2"), preview.rows.single().values)
        assertEquals(listOf("S"), preview.sheets)
    }

    @Test
    fun `time formats and the 1904 date system`() {
        val data = """<row r="1"><c r="A1" s="4"><v>0.75</v></c><c r="B1" s="1"><v>0</v></c><c r="C1" s="1"><v>59</v></c><c r="D1" s="1"><v>61</v></c></row>"""
        ImportReader.open(workbook(listOf("S" to data)), ImportOptions(ImportFormat.Xlsx, hasHeader = false)).use {
            assertEquals(listOf("18:00:00", "1899-12-31", "1900-02-28", "1900-03-01"), it.rows().single().values)
        }
        ImportReader.open(workbook(listOf("S" to data), date1904 = true), ImportOptions(ImportFormat.Xlsx, hasHeader = false)).use {
            assertEquals("1904-01-01", it.rows().single().values[1])
        }
    }

    @Test
    fun `empty cells can be kept as empty strings`() {
        val data = """<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c><c r="B1" t="inlineStr"><is><t>b</t></is></c></row>""" +
            """<row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2"><v>1</v></c></row>"""
        val file = workbook(listOf("S" to data), listOf("<t></t>"))
        ImportReader.open(file, ImportOptions(ImportFormat.Xlsx, emptyAsNull = false)).use { assertEquals(listOf("", "1"), it.rows().single().values) }
        ImportReader.open(file, ImportOptions(ImportFormat.Xlsx)).use { assertNull(it.rows().single().values[0]) }
    }

    @Test
    fun `not a workbook`() {
        val file = File(dir, "fake.xlsx").apply { writeText("a,b\n1,2\n") }
        val error = assertFailsWith<ImportException> { ImportReader.open(file, ImportOptions(ImportFormat.Xlsx)) }
        assertEquals("fake.xlsx isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)", error.message)
    }

    @Test
    fun `custom format classification`() {
        assertNull(excelFormatKind("0.0\" days\""))
        assertNull(excelFormatKind("#,##0;[Red]-#,##0"))
        assertNull(excelFormatKind("General"))
        assertNull(excelFormatKind("0.00E+00"))
        assertEquals(XlsxSource.DateKind.Date, excelFormatKind("dd/mm/yyyy;@"))
        assertEquals(XlsxSource.DateKind.Date, excelFormatKind("[\$-409]mmm-yy"))
        assertEquals(XlsxSource.DateKind.Time, excelFormatKind("[h]:mm:ss"))
        assertEquals(XlsxSource.DateKind.DateTime, excelFormatKind("m/d/yy h:mm AM/PM"))
    }
}

package dev.tablegrip.core.importer

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CsvImportTest {
    private val dir = createTempDirectory("csv-import").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(text: String, name: String = "data.csv", charset: Charset = StandardCharsets.UTF_8, bom: ByteArray = ByteArray(0)) =
        File(dir, name).apply { writeBytes(bom + text.toByteArray(charset)) }

    private fun read(file: File, options: ImportOptions = ImportOptions(ImportFormat.Csv)): Pair<ImportSource, List<ImportRow>> {
        val source = ImportReader.open(file, options)
        return source.use { it to it.rows().toList() }
    }

    @Test
    fun `quotes, doubled quotes and embedded delimiters and newlines`() {
        val (source, rows) = read(file("id,text\r\n1,\"a,b\"\r\n2,\"say \"\"hi\"\"\"\r\n3,\"two\r\nlines\nand\rmore\"\r\n4,\"  padded  \"\r\n5,last"))
        assertEquals(listOf("id", "text"), source.columns)
        assertEquals(listOf("a,b", "say \"hi\"", "two\r\nlines\nand\rmore", "  padded  ", "last"), rows.map { it.values[1] })
        // Lines are physical: record 3 spans lines 4-7 (CRLF, LF and CR each end a line).
        assertEquals(listOf(2L, 3L, 4L, 8L, 9L), rows.map { it.line })
    }

    @Test
    fun `stray quotes are tolerated and empty fields are null unless quoted`() {
        val (_, rows) = read(file("a,b,c\n5\" screen,\"x\"y, \"z\" \n,\"\",\n"))
        assertEquals(listOf("5\" screen", "xy", "z"), rows[0].values)
        assertEquals(listOf(null, "", null), rows[1].values)
        val (_, keep) = read(file("a,b\n,x\n"), ImportOptions(ImportFormat.Csv, emptyAsNull = false))
        assertEquals(listOf("", "x"), keep.single().values)
    }

    @Test
    fun `bom is stripped and utf-16 is detected`() {
        val (source, rows) = read(file("name,city\nZoë,Hà Nội\n", bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertEquals(listOf("name", "city"), source.columns)
        assertEquals(StandardCharsets.UTF_8, source.options.charset)
        assertEquals(listOf("Zoë", "Hà Nội"), rows.single().values)

        val (utf16, utf16Rows) = read(file("a;b\n1;ü\n", charset = StandardCharsets.UTF_16LE, bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals(StandardCharsets.UTF_16LE, utf16.options.charset)
        assertEquals(listOf("a", "b"), utf16.columns)
        assertEquals(listOf("1", "ü"), utf16Rows.single().values)
    }

    @Test
    fun `non utf-8 falls back to windows-1252`() {
        val (source, rows) = read(file("name\ncafé – €5\n", charset = Charset.forName("windows-1252")))
        assertEquals("windows-1252", source.options.charset!!.name())
        assertEquals("café – €5", rows.single().values.single())
    }

    @Test
    fun `invalid utf-8 when utf-8 is forced names the line`() {
        val bytes = "a\nok\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\n".toByteArray()
        val f = File(dir, "bad.csv").apply { writeBytes(bytes) }
        val error = assertFailsWith<ImportException> { read(f, ImportOptions(ImportFormat.Csv, charset = StandardCharsets.UTF_8)) }
        assertEquals(3L, error.line)
    }

    @Test
    fun `delimiter detection respects quotes and prefers consistency`() {
        // Decimal commas: the comma split is ragged (header has one), the semicolon split is consistent.
        val (semicolon, rows) = read(file("name;price\n\"a;b\";1,5\nc;2,25\nd;3\n"))
        assertEquals(';', semicolon.options.delimiter)
        assertEquals(listOf("a;b", "1,5"), rows[0].values)

        val (tab, _) = read(file("a\tb\tc\n1, 2\t3\t4\n"))
        assertEquals('\t', tab.options.delimiter)
        val (pipe, _) = read(file("a|b\n1|2\n"))
        assertEquals('|', pipe.options.delimiter)
        val (comma, _) = read(file("a,b\n\"x;y;z\",2\n"))
        assertEquals(',', comma.options.delimiter)
        val (single, singleRows) = read(file("only\nvalue one\nvalue two\n"))
        assertEquals(listOf("only"), single.columns)
        assertEquals(2, singleRows.size)
    }

    @Test
    fun `ragged rows are padded, trailing delimiters tolerated, longer rows rejected`() {
        val (_, rows) = read(file("a,b,c\n1\n1,2,3,\n"))
        assertEquals(listOf("1", null, null), rows[0].values)
        assertEquals(listOf("1", "2", "3"), rows[1].values)

        val source = ImportReader.open(file("a,b\n1,2\n\n1,2,3\n"), ImportOptions(ImportFormat.Csv, delimiter = ','))
        val error = source.use { s -> assertFailsWith<ImportException> { s.rows().toList() } }
        assertEquals(4L, error.line)
        assertEquals("Line 4 has 3 fields but there are only 2 columns", error.message)
    }

    @Test
    fun `header names are trimmed, de-duplicated and blanks named`() {
        val (source, _) = read(file(" id ,Name,name,,id,column_4\n"))
        assertEquals(listOf("id", "Name", "name_2", "column_4", "id_2", "column_4_2"), source.columns)
    }

    @Test
    fun `no header names columns by the widest early record and skip rows drops a title`() {
        val (source, rows) = read(file("Sales report\nexported today\n1,2\n3,4,5\n"), ImportOptions(ImportFormat.Csv, hasHeader = false, skipRows = 2))
        assertEquals(listOf("column_1", "column_2", "column_3"), source.columns)
        assertEquals(listOf(listOf("1", "2", null), listOf("3", "4", "5")), rows.map { it.values })
        assertEquals(listOf(3L, 4L), rows.map { it.line })
    }

    @Test
    fun `unclosed quote reports where the record started`() {
        val error = assertFailsWith<ImportException> { read(file("a,b\n1,2\n3,\"oops\n4,5\n")) }
        assertEquals(3L, error.line)
    }

    @Test
    fun `empty and header-only files`() {
        val (empty, emptyRows) = read(file(""))
        assertEquals(emptyList(), empty.columns)
        assertEquals(emptyList(), emptyRows)
        val (header, headerRows) = read(file("a,b\r\n"))
        assertEquals(listOf("a", "b"), header.columns)
        assertEquals(emptyList(), headerRows)
    }

    @Test
    fun `preview infers types and rows can only be read once`() {
        val preview = ImportReader.preview(file("id,zip,amount,ok,day\n1,007,1.5,true,2024-01-31\n2,010,2,false,\n"), ImportOptions(ImportFormat.Csv))
        assertEquals(
            listOf(InferredType.Kind.Integer, InferredType.Kind.Text, InferredType.Kind.Decimal, InferredType.Kind.Boolean, InferredType.Kind.Date),
            preview.types.map { it.kind },
        )
        assertEquals(true, preview.types[4].nullable)
        assertNull(preview.rows[1].values[4])

        val source = ImportReader.open(file("a\n1\n"), ImportOptions(ImportFormat.Csv))
        source.use { s ->
            s.rows().toList()
            assertFailsWith<IllegalStateException> { s.rows() }
        }
    }

    @Test
    fun `large files stream`() {
        val big = File(dir, "big.csv")
        big.bufferedWriter().use { out ->
            out.write("id,text\n")
            repeat(200_000) { out.write("$it,\"row $it, with comma\"\n") }
        }
        ImportReader.open(big, ImportOptions(ImportFormat.Csv)).use { source ->
            var count = 0
            var last: ImportRow? = null
            source.rows().forEach { count++; last = it }
            assertEquals(200_000, count)
            assertEquals(listOf("199999", "row 199999, with comma"), last!!.values)
        }
    }

    @Test
    fun `format detection by extension`() {
        assertEquals(ImportFormat.Csv, ImportFormat.detect(File("a.TSV")))
        assertEquals(ImportFormat.Json, ImportFormat.detect(File("a.jsonl")))
        assertEquals(ImportFormat.Xlsx, ImportFormat.detect(File("a.xlsx")))
        assertNull(ImportFormat.detect(File("a.xls")))
    }
}

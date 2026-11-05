package dev.tablegrip.core.importer

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonImportTest {
    private val dir = createTempDirectory("json-import").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(text: String, name: String = "data.json") = File(dir, name).apply { writeText(text) }

    private fun read(file: File): Pair<List<String>, List<ImportRow>> =
        ImportReader.open(file, ImportOptions(ImportFormat.Json)).use { it.columns to it.rows().toList() }

    @Test
    fun `array of objects with the union of keys`() {
        val (columns, rows) = read(file("""[{"id": 1, "name": "Ann"}, {"id": 2, "extra": true, "name": null}, {"id": 3.50, "name": "Bo \"B\""}]"""))
        assertEquals(listOf("id", "name", "extra"), columns)
        assertEquals(listOf(listOf("1", "Ann", null), listOf("2", null, "true"), listOf("3.50", "Bo \"B\"", null)), rows.map { it.values })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.line })
    }

    @Test
    fun `nested values become compact json`() {
        val (_, rows) = read(file("""[ {"a": {"b": [1, 2], "c": "x"}, "d": [] } ]"""))
        assertEquals(listOf("""{"b":[1,2],"c":"x"}""", "[]"), rows.single().values)
    }

    @Test
    fun `ndjson with blank lines and crlf`() {
        val (columns, rows) = read(file("{\"a\":1}\r\n\r\n{\"b\":\"x\"}\r\n{\"a\":3,\"b\":\"y\"}\r\n", "data.ndjson"))
        assertEquals(listOf("a", "b"), columns)
        assertEquals(listOf(listOf("1", null), listOf(null, "x"), listOf("3", "y")), rows.map { it.values })
    }

    @Test
    fun `object wrapping the rows`() {
        val (columns, rows) = read(file("""{"meta": {"count": 2}, "data": [{"x": 1}, {"x": 2}]}"""))
        assertEquals(listOf("x"), columns)
        assertEquals(listOf("1", "2"), rows.map { it.values.single() })
        // Pretty-printed too.
        val (_, pretty) = read(file("{\n  \"rows\": [\n    {\"x\": 1}\n  ]\n}\n"))
        assertEquals("1", pretty.single().values.single())
    }

    @Test
    fun `a single object without an array is one record`() {
        val (columns, rows) = read(file("""{"id": 1, "tags": ["a"]}"""))
        assertEquals(listOf("id", "tags"), columns)
        assertEquals(listOf("1", """["a"]"""), rows.single().values)
    }

    @Test
    fun `scalars in an array land in a value column`() {
        val (columns, rows) = read(file("""[1, "two", null]"""))
        assertEquals(listOf("value"), columns)
        assertEquals(listOf("1", "two", null), rows.map { it.values.single() })
    }

    @Test
    fun `utf-16 with bom`() {
        val f = File(dir, "u16.json").apply { writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + """[{"name":"Hà Nội 😀"}]""".toByteArray(StandardCharsets.UTF_16BE)) }
        assertEquals("Hà Nội 😀", read(f).second.single().values.single())
    }

    @Test
    fun `preview takes columns from the preview rows only`() {
        val preview = ImportReader.preview(file("""[{"a": 1}, {"a": 2}, {"a": 3, "late": "x"}]"""), ImportOptions(ImportFormat.Json), limit = 2)
        assertEquals(listOf("a"), preview.columns)
        assertEquals(2, preview.rows.size)
        assertEquals(InferredType.Kind.Integer, preview.types.single().kind)
    }

    @Test
    fun `errors name the record or line`() {
        val broken = assertFailsWith<ImportException> { read(file("""[{"a": 1}, {"a": 2,}]""")) }
        assertEquals(2L, broken.line)
        val ndjson = assertFailsWith<ImportException> { read(file("{\"a\":1}\n{\"a\":2}\n{oops}\n", "x.ndjson")) }
        assertEquals(3L, ndjson.line)
        assertFailsWith<ImportException> { read(file("hello")) }
    }

    @Test
    fun `empty array and empty file`() {
        assertEquals(emptyList<String>() to emptyList(), read(file("[]")))
        assertEquals(emptyList<String>() to emptyList(), read(file("")))
    }
}

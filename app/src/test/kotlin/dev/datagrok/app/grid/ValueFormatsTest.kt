package dev.datagrok.app.grid

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class ValueFormatsTest {
    @Test
    fun `json lines show only open containers`() {
        val root = Json.parseToJsonElement("""{"a":1,"b":{"c":[true,null]},"d":"x"}""")
        assertEquals(listOf("$", "$.a", "$.b", "$.d"), jsonLines(root, emptySet()).map { it.path })
        val open = jsonLines(root, setOf("$.b", "$.b.c"))
        assertEquals(listOf("$", "$.a", "$.b", "$.b.c", "$.b.c[0]", "$.b.c[1]", "$.d"), open.map { it.path })
        assertEquals("{1}", open.first { it.path == "$.b" }.summary)
        assertEquals("\"x\"", open.last().summary)
        assertEquals(setOf("$", "$.b", "$.b.c"), jsonContainerPaths(root))
    }

    @Test
    fun `hex dump shows offsets and printable text`() {
        val dump = hexDump("Hello, binary world".toByteArray())
        assertEquals("00000000  48 65 6c 6c 6f 2c 20 62  69 6e 61 72 79 20 77 6f  Hello, binary wo", dump.lines()[0])
        assertEquals("00000010  72 6c 64", dump.lines()[1].trimEnd().substringBefore("  rld").trimEnd())
    }

    @Test
    fun `file signatures are recognised`() {
        assertEquals("PNG image", binaryKind(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertNull(binaryKind(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `timestamps parse with and without offsets`() {
        assertEquals(Instant.parse("2026-10-28T06:45:00Z"), parseTimestamp("2026-10-28 13:45:00+07")!!.instant)
        assertEquals(Instant.parse("2026-10-28T06:45:00.123Z"), parseTimestamp("2026-10-28 13:45:00.123+07:00")!!.instant)
        assertEquals(Instant.parse("2026-10-28T06:45:00Z"), parseTimestamp("2026-10-28T06:45:00Z")!!.instant)
        val local = parseTimestamp("2026-10-28 13:45:00")!!
        assertEquals(false, local.hadOffset)
        assertEquals(Instant.parse("2026-10-28T13:45:00Z"), local.instant)
        assertNull(parseTimestamp("yesterday"))
        val rows = timestampConversions(parseTimestamp("2026-10-28 13:45:00+07")!!, ZoneId.of("Asia/Ho_Chi_Minh")).toMap()
        assertEquals("2026-10-28 06:45:00.000", rows["UTC"])
        assertEquals("2026-10-28 13:45:00.000", rows["Local (Asia/Ho_Chi_Minh)"])
        assertEquals("1793169900", rows["Unix"])
    }
}

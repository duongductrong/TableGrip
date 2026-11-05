package dev.tablegrip.core.driver

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryTextTest {
    @Test
    fun `short values round-trip`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val text = BinaryText.encode(bytes)
        assertEquals("0x007F80FF", text)
        val preview = BinaryText.decode(text)!!
        assertContentEquals(bytes, preview.bytes)
        assertTrue(preview.complete)
    }

    @Test
    fun `long values keep their start and size`() {
        val text = BinaryText.encode(ByteArray(10) { it.toByte() }, limit = 4)
        assertEquals("0x00010203… (10 bytes)", text)
        val preview = BinaryText.decode(text)!!
        assertEquals(4, preview.bytes.size)
        assertEquals(10, preview.totalSize)
        assertFalse(preview.complete)
    }

    @Test
    fun `other text is not binary`() {
        assertNull(BinaryText.decode("hello"))
        assertNull(BinaryText.decode("0x123"))
    }
}

package dev.tablegrip.core.export

import dev.tablegrip.core.driver.ResultColumn
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardTableTest {
    @Test
    fun `reads back what tsv copy writes`() {
        val columns = listOf(ResultColumn("a", "text", Types.VARCHAR), ResultColumn("b", "text", Types.VARCHAR))
        val rows = listOf(listOf("1", "O'Brien"), listOf("tab\there", "say \"hi\""), listOf("two\nlines", ""))
        val text = ResultFormats.format(CopyFormat.Tsv, columns, rows, includeHeader = false)
        assertEquals(rows, ClipboardTable.parse(text))
    }

    @Test
    fun `reads spreadsheet text`() {
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), ClipboardTable.parse("1\t2\r\n3\t4\r\n"))
        assertEquals(listOf(listOf("x")), ClipboardTable.parse("x\n"))
        assertEquals(listOf(listOf("a", "", "c"), listOf("d")), ClipboardTable.parse("a\t\tc\nd"))
        assertEquals(emptyList(), ClipboardTable.parse(""))
    }

    @Test
    fun `quotes that don't wrap a whole cell stay text`() {
        assertEquals(listOf(listOf("\"open", "x")), ClipboardTable.parse("\"open\tx"))
        assertEquals(listOf(listOf("\"a\"b")), ClipboardTable.parse("\"a\"b"))
        assertEquals(listOf(listOf("5\" pipe")), ClipboardTable.parse("5\" pipe"))
    }

    @Test
    fun `html keeps rows, escapes text and leaves NULLs empty`() {
        assertEquals(
            "<table><tr><th>id</th><th>note</th></tr><tr><td>1</td><td>a &lt;b&gt; &amp; c<br>d</td></tr><tr><td>2</td><td></td></tr></table>",
            ClipboardTable.html(listOf("id", "note"), listOf(listOf("1", "a <b> & c\nd"), listOf("2", null))),
        )
    }
}

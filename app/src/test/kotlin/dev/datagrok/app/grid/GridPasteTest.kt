package dev.datagrok.app.grid

import dev.datagrok.app.ui.TextClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GridPasteTest {
    private val names = listOf("id", "name", "qty")

    private fun cell(row: Int, column: Int) = GridSelection(GridCell(row, column), GridCell(row, column))

    private fun range(top: Int, left: Int, bottom: Int, right: Int) = GridSelection(GridCell(top, left), GridCell(bottom, right))

    private fun plan(rows: List<List<String?>>, selection: GridSelection, rowCount: Int = 5, canAddRows: Boolean = true, header: List<String>? = null) =
        planPaste(ClipboardBlock(rows, header), selection, names, rowCount, canAddRows)!!

    @Test
    fun `a block starts at the selected cell`() {
        val plan = plan(listOf(listOf("a", "b"), listOf("c", "d")), cell(1, 1))
        assertEquals(listOf(GridCell(1, 1) to "a", GridCell(1, 2) to "b", GridCell(2, 1) to "c", GridCell(2, 2) to "d"), plan.writes)
        assertEquals(range(1, 1, 2, 2), plan.range)
        assertEquals(0, plan.newRows)
    }

    @Test
    fun `one value fills the selection, and a block repeats over a multiple of its size`() {
        assertEquals(6, plan(listOf(listOf("x")), range(0, 0, 1, 2)).writes.count { it.second == "x" })
        val tiled = plan(listOf(listOf("a"), listOf("b")), range(0, 2, 3, 2)).writes.map { it.second }
        assertEquals(listOf("a", "b", "a", "b"), tiled)
    }

    @Test
    fun `a block that doesn't divide the selection starts at its corner`() {
        val plan = plan(listOf(listOf("a", "b")), range(0, 0, 2, 2))
        assertEquals(listOf(GridCell(0, 0) to "a", GridCell(0, 1) to "b"), plan.writes)
    }

    @Test
    fun `rows past the end are added, columns past the last are cut off`() {
        val plan = plan(listOf(listOf("1", "2"), listOf("3", "4"), listOf("5", "6")), cell(4, 2))
        assertEquals(2, plan.newRows)
        assertEquals(1, plan.cutColumns)
        assertEquals(listOf(4, 5, 6), plan.writes.map { it.first.row })

        val readOnly = plan(listOf(listOf("1"), listOf("2")), cell(4, 0), canAddRows = false)
        assertEquals(1, readOnly.cutRows)
        assertEquals(listOf(GridCell(4, 0) to "1"), readOnly.writes)
    }

    @Test
    fun `a header row sends values to the columns it names`() {
        val plan = plan(listOf(listOf("qty", "id", "colour"), listOf("3", "9", "red")), cell(2, 0))
        assertEquals(listOf(GridCell(2, 2) to "3", GridCell(2, 0) to "9"), plan.writes)
        assertEquals(1, plan.cutColumns)

        val copied = plan(listOf(listOf(null, "ada")), cell(0, 2), header = listOf("id", "name"))
        assertEquals(listOf(GridCell(0, 0) to null, GridCell(0, 1) to "ada"), copied.writes)
    }

    @Test
    fun `the grid's own copy keeps its NULLs while it's on the clipboard`() {
        val clipboard = object : TextClipboard {
            var text: String? = "1\t"
            override fun get() = text
            override fun set(text: String) { this.text = text }
        }
        GridClipboard.last = CopiedBlock("1\t", null, listOf(listOf("1", null)))
        assertEquals(listOf(listOf("1", null)), GridClipboard.read(clipboard)!!.rows)
        clipboard.text = "1\t"
        GridClipboard.last = null
        assertEquals(listOf(listOf("1", "")), GridClipboard.read(clipboard)!!.rows)
        clipboard.text = ""
        assertNull(GridClipboard.read(clipboard))
    }
}

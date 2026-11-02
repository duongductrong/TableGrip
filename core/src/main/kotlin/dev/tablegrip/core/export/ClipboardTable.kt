package dev.tablegrip.core.export

/** A block of cells on the clipboard: read from tab-separated text, and written as an HTML table for spreadsheets. */
object ClipboardTable {
    /**
     * Splits tab-separated [text] into rows of cells, as [ResultWriter] and spreadsheets write it: a cell in double quotes
     * may hold tabs, line breaks and doubled quotes, while a quote that doesn't close the cell is kept as text. The one
     * line break spreadsheets add at the end is dropped. Rows may differ in length.
     */
    fun parse(text: String): List<List<String>> {
        val body = text.removeSuffix("\n").removeSuffix("\r")
        if (body.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        while (true) {
            if (i < body.length && body[i] == '"') {
                val end = closingQuote(body, i)
                if (end >= 0) {
                    cell.append(body.substring(i + 1, end).replace("\"\"", "\""))
                    i = end + 1
                }
            }
            while (i < body.length && body[i] != '\t' && body[i] != '\n' && body[i] != '\r') cell.append(body[i++])
            row += cell.toString()
            cell.clear()
            if (i >= body.length) break
            when (body[i]) {
                '\t' -> i++
                else -> {
                    i += if (body[i] == '\r' && i + 1 < body.length && body[i + 1] == '\n') 2 else 1
                    rows += row
                    row = mutableListOf()
                }
            }
        }
        rows += row
        return rows
    }

    /** Where the quoted cell starting at [start] ends: a quote followed by a separator or the end; -1 when there's none. */
    private fun closingQuote(text: String, start: Int): Int {
        var j = start + 1
        while (j < text.length) {
            if (text[j] == '"') {
                if (j + 1 < text.length && text[j + 1] == '"') {
                    j += 2
                    continue
                }
                return if (j + 1 == text.length || text[j + 1] == '\t' || text[j + 1] == '\n' || text[j + 1] == '\r') j else -1
            }
            j++
        }
        return -1
    }

    /** [rows] as an HTML table, with [header] as its first row; NULLs are empty cells and line breaks stay in their cell. */
    fun html(header: List<String>?, rows: List<List<String?>>): String =
        buildString {
            append("<table>")
            header?.let { names -> append("<tr>").append(names.joinToString("") { "<th>${escape(it)}</th>" }).append("</tr>") }
            rows.forEach { row -> append("<tr>").append(row.joinToString("") { "<td>${escape(it.orEmpty())}</td>" }).append("</tr>") }
            append("</table>")
        }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>")
}

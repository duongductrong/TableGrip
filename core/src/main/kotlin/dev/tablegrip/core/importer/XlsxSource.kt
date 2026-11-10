package dev.tablegrip.core.importer

import dev.tablegrip.core.i18n.tr
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.math.floor

/**
 * An `.xlsx` sheet, read with StAX straight from the zip: no POI, and the sheet XML is never held in memory (shared strings
 * are). Opening scans the sheet once to find its width (the last column holding a value), then [rows] streams it again;
 * in preview mode the first rows are buffered instead and size the columns on their own.
 */
internal class XlsxSource(file: File, requested: ImportOptions, previewRows: Int?) : ImportSource {
    private val zip =
        try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw ImportException(tr("{0} isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)", file.name), cause = e)
        }
    private val workbook: Workbook
    private val sheetPath: String
    private val buffered: List<ImportRow>?
    private var reader: SheetReader? = null
    private var consumed = false

    override val options: ImportOptions
    override val columns: List<String>
    override val sheets: List<String>

    /** The sheet row the header was read from, when there is one. */
    internal var headerLine: Long? = null
        private set

    init {
        try {
            workbook = Workbook(zip)
            sheets = workbook.sheets.map { it.first }
            if (sheets.isEmpty()) throw ImportException(tr("The workbook has no worksheets"))
            val sheet = requested.sheet ?: sheets.first()
            sheetPath =
                workbook.sheets.firstOrNull { it.first == sheet }?.second
                    ?: throw ImportException(tr("There's no sheet named \"{0}\"; the workbook has {1}", sheet, sheets.joinToString { "\"$it\"" }))
            options = requested.copy(sheet = sheet)

            val header: List<String?>?
            val width: Int
            if (previewRows != null) {
                val rows = ArrayList<ImportRow>()
                SheetReader(sheetPath).use { sheetReader ->
                    val wanted = previewRows + if (requested.hasHeader) 1 else 0
                    while (rows.size < wanted) rows += sheetReader.nextDataRow() ?: break
                }
                val headerRow = if (requested.hasHeader) rows.removeFirstOrNull() else null
                headerLine = headerRow?.line
                header = headerRow?.values
                width = maxOf(header?.let(::usedWidth) ?: 0, rows.maxOfOrNull { usedWidth(it.values) } ?: 0)
                buffered = rows
            } else {
                var first: List<String?>? = null
                var widest = 0
                SheetReader(sheetPath).use { sheetReader ->
                    while (true) {
                        val row = sheetReader.nextDataRow() ?: break
                        if (first == null) {
                            first = row.values
                            if (requested.hasHeader) headerLine = row.line
                        }
                        widest = maxOf(widest, usedWidth(row.values))
                    }
                }
                header = if (requested.hasHeader) first else null
                width = widest
                buffered = null
            }
            columns = if (header != null) normalizeColumnNames(header + arrayOfNulls<String>(maxOf(0, width - header.size))).take(width) else generatedColumnNames(width)
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    override fun rows(): Sequence<ImportRow> {
        check(!consumed) { "rows() can only be read once" }
        consumed = true
        buffered?.let { rows -> return rows.asSequence().map { fitRow(it.line, it.values, columns.size, tr("Row")) } }
        val sheetReader = SheetReader(sheetPath).also { reader = it }
        return sequence {
            if (options.hasHeader) sheetReader.nextDataRow()
            while (true) {
                val row = sheetReader.nextDataRow() ?: break
                // The width scan saw every row, so nothing is longer than [columns] beyond empty cells.
                yield(ImportRow(row.line, (row.values + arrayOfNulls<String>(maxOf(0, columns.size - row.values.size))).subList(0, columns.size)))
            }
        }
    }

    override fun close() {
        reader?.close()
        zip.close()
    }

    private fun usedWidth(values: List<String?>) = values.indexOfLast { !it.isNullOrEmpty() } + 1

    /** Streams one worksheet's rows, skipping [ImportOptions.skipRows] and rows without a value. */
    private inner class SheetReader(path: String) : Closeable {
        private val stream: InputStream = zip.open(path)
        private val xml: XMLStreamReader = xmlReader(stream, path)
        private var lastRow = 0L

        fun nextDataRow(): ImportRow? {
            while (true) {
                val row = nextRow() ?: return null
                if (row.line <= options.skipRows) continue
                if (row.values.all { it.isNullOrBlank() }) continue
                return row
            }
        }

        private fun nextRow(): ImportRow? =
            xmlGuard({ tr("Couldn't read row {0} of sheet \"{1}\"", lastRow + 1, options.sheet) }, lastRow + 1) {
                while (xml.hasNext()) {
                    if (xml.next() == START_ELEMENT && xml.localName == "row") return@xmlGuard readRow()
                }
                null
            }

        private fun readRow(): ImportRow {
            val rowNumber = xml.getAttributeValue(null, "r")?.toLongOrNull() ?: (lastRow + 1)
            lastRow = rowNumber
            val cells = ArrayList<String?>()
            var lastColumn = -1
            while (true) {
                when (xml.next()) {
                    START_ELEMENT ->
                        if (xml.localName == "c") {
                            val column = xml.getAttributeValue(null, "r")?.let(::columnIndex) ?: (lastColumn + 1)
                            lastColumn = column
                            val value = readCell(rowNumber)
                            if (value != null) {
                                while (cells.size <= column) cells.add(null)
                                cells[column] = value
                            }
                        } else {
                            skipElement(xml)
                        }
                    END_ELEMENT -> if (xml.localName == "row") return ImportRow(rowNumber, cells)
                }
            }
        }

        /** The cell's text as the user saw it, or null for an empty or error cell. */
        private fun readCell(row: Long): String? {
            val type = xml.getAttributeValue(null, "t")
            val style = xml.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
            var raw: String? = null
            var inline: String? = null
            while (true) {
                when (xml.next()) {
                    START_ELEMENT ->
                        when (xml.localName) {
                            "v" -> raw = xml.elementText
                            "is" -> inline = readRichText(xml, "is")
                            else -> skipElement(xml) // <f>: the cached <v> holds the result
                        }
                    END_ELEMENT -> if (xml.localName == "c") break
                }
            }
            // A cell element with no value is just formatting.
            if (raw == null && inline == null) return null
            val text =
                when (type) {
                    "s" -> raw?.trim()?.toIntOrNull()?.let { workbook.sharedStrings.getOrNull(it) ?: throw ImportException(tr("Row {0} refers to a missing shared string", row), row) }
                    "inlineStr" -> inline ?: raw
                    "str" -> raw
                    "b" -> raw?.trim()?.let { if (it == "1" || it.equals("true", ignoreCase = true)) "true" else "false" }
                    "e" -> return null
                    "d" -> raw // ISO 8601 already (strict OOXML)
                    else -> raw?.trim()?.takeIf { it.isNotEmpty() }?.let { number(it, style) }
                }
            return if (text.isNullOrEmpty() && options.emptyAsNull) null else text.orEmpty()
        }

        private fun number(raw: String, style: Int): String {
            val kind = workbook.dateKind(style)
            if (kind != null) raw.toDoubleOrNull()?.let { serial -> excelDate(serial, kind, workbook.date1904)?.let { return it } }
            return raw.toBigDecimalOrNull()?.let(::plainNumber) ?: raw
        }

        override fun close() {
            runCatching { xml.close() }
            stream.close()
        }
    }

    internal enum class DateKind { Date, DateTime, Time }

    /** The workbook-level parts: sheet names -> paths, shared strings, date styles and the date system. */
    internal class Workbook(private val zip: ZipFile) {
        val sheets = ArrayList<Pair<String, String>>()
        var date1904 = false
            private set
        val sharedStrings: List<String>
        private val styleKinds: List<DateKind?>

        init {
            val workbookPath = relationships(zip, "_rels/.rels", "").entries.firstOrNull { it.value.first.endsWith("/officeDocument") }?.value?.second ?: "xl/workbook.xml"
            val directory = workbookPath.substringBeforeLast('/', "")
            val rels = relationships(zip, "${directory.withSlash()}_rels/${workbookPath.substringAfterLast('/')}.rels", directory)
            val sheetIds = ArrayList<Pair<String, String>>()
            parse(zip, workbookPath, required = true) { xml ->
                while (xml.hasNext()) {
                    if (xml.next() != START_ELEMENT) continue
                    when (xml.localName) {
                        "workbookPr" -> date1904 = xml.getAttributeValue(null, "date1904").let { it == "1" || it.equals("true", ignoreCase = true) }
                        "sheet" -> sheetIds += xml.getAttributeValue(null, "name").orEmpty() to xml.getAttributeValue(null, "id").orEmpty()
                    }
                }
            }
            for ((name, id) in sheetIds) {
                val (type, target) = rels[id] ?: continue
                // Chartsheets and dialog sheets have no cells to import.
                if (type.endsWith("/worksheet") && findEntry(zip, target) != null) sheets += name to target
            }
            val sharedPath = rels.values.firstOrNull { it.first.endsWith("/sharedStrings") }?.second ?: "xl/sharedStrings.xml"
            sharedStrings = readSharedStrings(zip, sharedPath)
            val stylesPath = rels.values.firstOrNull { it.first.endsWith("/styles") }?.second ?: "xl/styles.xml"
            styleKinds = readStyles(zip, stylesPath)
        }

        fun dateKind(style: Int): DateKind? = styleKinds.getOrNull(style)
    }

    internal companion object {
        val Factory: XMLInputFactory =
            XMLInputFactory.newFactory().apply {
                // Workbooks come from anywhere: no DTDs, no external entities (XXE).
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }

        val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun xmlReader(stream: InputStream, path: String): XMLStreamReader =
            try {
                Factory.createXMLStreamReader(stream)
            } catch (e: XMLStreamException) {
                throw ImportException(tr("The workbook part {0} isn't valid XML", path), cause = e)
            }

        inline fun <T> xmlGuard(message: () -> String, line: Long?, block: () -> T): T =
            try {
                block()
            } catch (e: XMLStreamException) {
                throw ImportException(tr("{0}: the workbook XML is malformed ({1})", message(), e.message), line, e)
            }

        fun String.withSlash() = if (isEmpty()) "" else "$this/"

        /** Zip names are case-sensitive but some writers disagree with their own relationships on case. */
        fun findEntry(zip: ZipFile, path: String): ZipEntry? =
            zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }

        fun ZipFile.open(path: String): InputStream {
            val entry = findEntry(this, path) ?: throw ImportException(tr("The workbook is missing {0}", path))
            return getInputStream(entry)
        }

        fun parse(zip: ZipFile, path: String, required: Boolean, block: (XMLStreamReader) -> Unit) {
            val entry = findEntry(zip, path) ?: if (required) throw ImportException(tr("The workbook is missing {0}; is it really an .xlsx file?", path)) else return
            zip.getInputStream(entry).use { stream ->
                val xml = xmlReader(stream, path)
                try {
                    xmlGuard({ tr("Couldn't read {0}", path) }, null) { block(xml) }
                } finally {
                    xml.close()
                }
            }
        }

        /** Relationship id -> (type, zip path), with targets resolved against [base] (the source part's directory). */
        fun relationships(zip: ZipFile, path: String, base: String): Map<String, Pair<String, String>> {
            val result = HashMap<String, Pair<String, String>>()
            parse(zip, path, required = false) { xml ->
                while (xml.hasNext()) {
                    if (xml.next() != START_ELEMENT || xml.localName != "Relationship") continue
                    if (xml.getAttributeValue(null, "TargetMode") == "External") continue
                    val id = xml.getAttributeValue(null, "Id") ?: continue
                    val target = xml.getAttributeValue(null, "Target") ?: continue
                    result[id] = xml.getAttributeValue(null, "Type").orEmpty() to resolve(base, target)
                }
            }
            return result
        }

        fun resolve(base: String, target: String): String {
            val path = target.replace('\\', '/')
            val parts = ArrayList<String>()
            val joined = if (path.startsWith("/")) path.drop(1) else base.withSlash() + path
            for (part in joined.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }

        fun readSharedStrings(zip: ZipFile, path: String): List<String> {
            val strings = ArrayList<String>()
            parse(zip, path, required = false) { xml ->
                while (xml.hasNext()) {
                    if (xml.next() != START_ELEMENT) continue
                    when (xml.localName) {
                        "sst" -> xml.getAttributeValue(null, "uniqueCount")?.toIntOrNull()?.let { strings.ensureCapacity(minOf(it, 1_000_000)) }
                        "si" -> strings += readRichText(xml, "si")
                    }
                }
            }
            return strings
        }

        /** The text of a plain (`<t>`) or rich (`<r><t>…`) string up to [end], leaving out phonetic hints (`<rPh>`). */
        fun readRichText(xml: XMLStreamReader, end: String): String {
            val text = StringBuilder()
            while (true) {
                when (xml.next()) {
                    START_ELEMENT ->
                        when (xml.localName) {
                            "t" -> text.append(xml.elementText)
                            "rPh", "phoneticPr" -> skipElement(xml)
                        }
                    END_ELEMENT -> if (xml.localName == end) return unescapeOoxml(text.toString())
                }
            }
        }

        /** Characters XML can't hold are written as `_xHHHH_` (Excel writes CR as `_x000D_`); `_x005F_` escapes a literal underscore. */
        fun unescapeOoxml(text: String): String {
            if (!text.contains("_x")) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '_' && i + 6 < text.length && text[i + 1] == 'x' && text[i + 6] == '_') {
                    val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 7
                        continue
                    }
                }
                out.append(text[i++])
            }
            return out.toString()
        }

        /** Per `cellXfs` index, whether its number format shows a date/time. `cellStyleXfs` (named styles) are ignored. */
        fun readStyles(zip: ZipFile, path: String): List<DateKind?> {
            val customFormats = HashMap<Int, String>()
            val formatIds = ArrayList<Int>()
            parse(zip, path, required = false) { xml ->
                // numFmt also appears under <dxfs> (conditional formatting); only <numFmts> defines what cells use.
                var section = ""
                while (xml.hasNext()) {
                    when (xml.next()) {
                        START_ELEMENT ->
                            when (xml.localName) {
                                "numFmts", "cellXfs", "cellStyleXfs", "dxfs" -> section = xml.localName
                                "numFmt" ->
                                    if (section == "numFmts") {
                                        xml.getAttributeValue(null, "numFmtId")?.toIntOrNull()?.let { customFormats[it] = xml.getAttributeValue(null, "formatCode").orEmpty() }
                                    }
                                "xf" -> if (section == "cellXfs") formatIds += xml.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                            }
                        END_ELEMENT -> if (xml.localName == section) section = ""
                    }
                }
            }
            return formatIds.map { id -> customFormats[id]?.let(::excelFormatKind) ?: builtinKind(id) }
        }

        fun builtinKind(id: Int): DateKind? =
            when (id) {
                in 14..17 -> DateKind.Date
                in 18..21, in 45..47 -> DateKind.Time
                22 -> DateKind.DateTime
                else -> null
            }

        /** 0-based column of a cell reference like `C7` or `AA12`, or null when it's malformed. */
        fun columnIndex(reference: String): Int? {
            var column = 0
            var letters = 0
            for (c in reference) {
                val upper = c.uppercaseChar()
                if (upper !in 'A'..'Z') break
                column = column * 26 + (upper - 'A' + 1)
                letters++
            }
            return if (letters in 1..3) column - 1 else null
        }

        fun skipElement(xml: XMLStreamReader) {
            var depth = 1
            while (depth > 0) {
                when (xml.next()) {
                    START_ELEMENT -> depth++
                    END_ELEMENT -> depth--
                }
            }
        }

        /**
         * A serial date as ISO text, or null when out of range (then it's shown as the number). The 1900 system counts
         * Lotus's phantom 1900-02-29 as day 60, so serials from 61 on are one day ahead of a plain count.
         */
        fun excelDate(serial: Double, kind: DateKind, date1904: Boolean): String? {
            if (serial.isNaN() || serial < 0 || serial >= 2_958_466) return null
            var days = floor(serial).toLong()
            var seconds = Math.round((serial - days) * 86_400)
            if (seconds >= 86_400) {
                days++
                seconds -= 86_400
            }
            val time = LocalTime.ofSecondOfDay(seconds).format(TimeFormat)
            if (kind == DateKind.Time && days == 0L) return time
            val date =
                when {
                    date1904 -> LocalDate.of(1904, 1, 1).plusDays(days)
                    days < 61 -> LocalDate.of(1899, 12, 31).plusDays(days)
                    else -> LocalDate.of(1899, 12, 30).plusDays(days)
                }
            return if (kind == DateKind.Date && seconds == 0L) date.toString() else "$date $time"
        }

        /** Integers without `.0`, everything else as plain digits (no exponent). */
        fun plainNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    }
}

/** Classifies an Excel custom number format by its d/m/y/h/s tokens, ignoring quoted text, `[…]` (colors, locales, elapsed time) and escapes. */
internal fun excelFormatKind(code: String): XlsxSource.DateKind? {
    val tokens = StringBuilder()
    var i = 0
    while (i < code.length) {
        when (val c = code[i]) {
            '"' -> i = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
            '[' -> i = code.indexOf(']', i + 1).let { if (it < 0) code.length else it }
            '\\', '_', '*' -> i++
            else -> tokens.append(c.lowercaseChar())
        }
        i++
    }
    val hasTime = tokens.contains('h') || tokens.contains('s')
    val hasDate = tokens.contains('d') || tokens.contains('y') || (tokens.contains('m') && !hasTime)
    return when {
        hasDate && hasTime -> XlsxSource.DateKind.DateTime
        hasDate -> XlsxSource.DateKind.Date
        hasTime -> XlsxSource.DateKind.Time
        else -> null
    }
}

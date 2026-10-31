package dev.datagrok.core.datafile

import dev.datagrok.core.connection.DataFileOptions
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.importer.ImportException
import dev.datagrok.core.importer.ImportFormat
import dev.datagrok.core.importer.ImportOptions
import dev.datagrok.core.importer.ImportReader
import dev.datagrok.core.importer.ImportSource
import dev.datagrok.core.importer.XlsxSource
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamException

/**
 * Excel workbooks (`.xlsx`, `.xlsm`): a table per worksheet, named after it. Reading is the importer's XLSX reader.
 *
 * Writing patches the workbook rather than generating a new one. A sheet whose table is unchanged is copied byte for
 * byte; a changed one keeps everything around its cells (column widths, frozen panes, filters, merged cells, drawings,
 * page setup) and has only its `<sheetData>` replaced. New cells take each column's style from the row they replace, so
 * dates stay dates, currencies stay currencies and the header keeps its look. Tables created with SQL become new sheets;
 * dropped ones are removed; renamed ones rename their sheet. Formulas in a rewritten sheet become their values, which
 * [DataFileDocument.notes] says up front.
 */
object XlsxFormat : DataFileFormat {
    override val type: DatabaseType = DatabaseType.ExcelFile
    override val extensions: Set<String> = setOf("xlsx", "xlsm")

    override fun open(path: Path, options: DataFileOptions): DataFileDocument {
        val workbook =
            try {
                ZipFile(path.toFile()).use { zip -> Workbook.read(zip) }
            } catch (e: ZipException) {
                throw DataFileException("${path.fileName} isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)", e)
            } catch (e: ImportException) {
                throw DataFileException(e.message ?: "Couldn't read ${path.fileName}", e)
            }
        return Document(path, options.hasHeader, workbook)
    }

    private class Document(private val path: Path, private val hasHeader: Boolean, private val workbook: Workbook) : DataFileDocument {
        private val open = ArrayList<ImportSource>()

        /**
         * Where each sheet's header and first data row are, by lower-case sheet name, as the reader found them. A save
         * styles new cells after these rows: guessing them separately could pick a different row (one holding only a
         * blank string, say) and paint the data with the header's look.
         */
        private val layouts = HashMap<String, RowLayout>()

        override val multiTable = true

        override val tables: List<SourceTable> =
            workbook.sheets.filter { it.isWorksheet && it.hasValues }.map { sheet ->
                object : SourceTable {
                    private val source by lazy { openSheet(sheet.name) }
                    override val name = sheet.name
                    override val columns: List<String>
                        get() = source.columns

                    override fun rows(): Sequence<List<String?>> =
                        sequence {
                            try {
                                var first = true
                                for (row in source.rows()) {
                                    if (first) {
                                        layouts[sheet.name.lowercase()] = RowLayout((source as? XlsxSource)?.headerLine, row.line)
                                        first = false
                                    }
                                    yield(row.values)
                                }
                                if (first) layouts[sheet.name.lowercase()] = RowLayout((source as? XlsxSource)?.headerLine, null)
                            } catch (e: ImportException) {
                                throw DataFileException(e.message ?: "Couldn't read sheet \"${sheet.name}\"", e)
                            } finally {
                                source.close()
                            }
                        }
                }
            }

        override val notes: List<String> =
            workbook.sheets.filter { it.hasFormulas && it.isWorksheet && it.hasValues }.map {
                "Sheet \"${it.name}\" has formulas: saving a change to it writes their current values instead."
            }

        override fun confirmBeforeWriting(tables: Set<String>): String? {
            val sheets = workbook.sheets.filter { it.hasFormulas && tables.any { name -> name.equals(it.name, ignoreCase = true) } }.map { it.name }
            if (sheets.isEmpty()) return null
            val list = sheets.joinToString { "\"$it\"" }
            return "${if (sheets.size == 1) "Sheet $list has" else "Sheets $list have"} formulas. Saving your changes writes their current values in place of the formulas."
        }

        private fun openSheet(name: String): ImportSource =
            try {
                ImportReader.open(path.toFile(), ImportOptions(ImportFormat.Xlsx, hasHeader = hasHeader, sheet = name)).also { open += it }
            } catch (e: ImportException) {
                throw DataFileException(e.message ?: "Couldn't read sheet \"$name\"", e)
            }

        override fun close() {
            open.forEach { runCatching { it.close() } }
            open.clear()
        }

        override fun write(target: Path, tables: List<OutputTable>, removed: Set<String>) {
            if (tables.isEmpty()) throw DataFileException("A workbook needs at least one sheet; it can't be saved without tables.")
            tables.forEach { validateSheetName(it.name) }
            ZipFile(path.toFile()).use { zip -> Patch(zip, Workbook.read(zip), hasHeader, tables, removed, layouts).writeTo(target) }
            // What was written is laid out from the header row down, with no gaps.
            for (table in tables) {
                val before = table.source?.let { layouts[it.lowercase()] }
                val first = before?.header ?: before?.data ?: 1L
                if (table.source != null) layouts.remove(table.source.lowercase())
                layouts[table.name.lowercase()] = RowLayout(first.takeIf { hasHeader }, if (hasHeader) first + 1 else first)
            }
        }
    }

    // region Workbook structure

    private class Sheet(
        val name: String,
        val relationId: String,
        /** Zip path of the part; null when the relationship points nowhere. */
        val path: String?,
        val isWorksheet: Boolean,
        val hasValues: Boolean,
        val hasFormulas: Boolean,
    )

    /** What patching needs to know about a workbook's parts. */
    private class Workbook(
        val workbookPath: String,
        val relationshipsPath: String,
        /** Relationship id -> (type, zip path). */
        val relationships: Map<String, Pair<String, String>>,
        /** Every `<sheet>` of the workbook, chart sheets too, in tab order (`localSheetId` counts them all). */
        val sheets: List<Sheet>,
        val date1904: Boolean,
        /** Per cell style, whether its number format shows a date or time. */
        val dateStyles: List<XlsxSource.DateKind?>,
    ) {
        val directory: String
            get() = workbookPath.substringBeforeLast('/', "")

        companion object {
            fun read(zip: ZipFile): Workbook {
                val workbookPath =
                    XlsxSource.relationships(zip, "_rels/.rels", "").values.firstOrNull { it.first.endsWith("/officeDocument") }?.second ?: "xl/workbook.xml"
                val directory = workbookPath.substringBeforeLast('/', "")
                val relationshipsPath = "${if (directory.isEmpty()) "" else "$directory/"}_rels/${workbookPath.substringAfterLast('/')}.rels"
                val relationships = XlsxSource.relationships(zip, relationshipsPath, directory)
                var date1904 = false
                val declared = ArrayList<Pair<String, String>>()
                XlsxSource.parse(zip, workbookPath, required = true) { xml ->
                    while (xml.hasNext()) {
                        if (xml.next() != START_ELEMENT) continue
                        when (xml.localName) {
                            "workbookPr" -> date1904 = xml.getAttributeValue(null, "date1904").let { it == "1" || it.equals("true", ignoreCase = true) }
                            "sheet" -> declared += xml.getAttributeValue(null, "name").orEmpty() to relationshipId(xml)
                        }
                    }
                }
                val sheets =
                    declared.map { (name, id) ->
                        val (type, target) = relationships[id] ?: ("" to "")
                        val path = target.takeIf { it.isNotEmpty() && XlsxSource.findEntry(zip, it) != null }?.let { XlsxSource.findEntry(zip, it)!!.name }
                        val worksheet = type.endsWith("/worksheet") && path != null
                        val (values, formulas) = if (worksheet) scanSheet(zip, path!!) else false to false
                        Sheet(name, id, path, worksheet, values, formulas)
                    }
                val stylesPath = relationships.values.firstOrNull { it.first.endsWith("/styles") }?.second ?: "xl/styles.xml"
                return Workbook(workbookPath, relationshipsPath, relationships, sheets, date1904, XlsxSource.readStyles(zip, stylesPath))
            }

            /** The `r:id` attribute, whatever prefix the relationships namespace has. */
            private fun relationshipId(xml: javax.xml.stream.XMLStreamReader): String =
                (0 until xml.attributeCount).firstOrNull { xml.getAttributeLocalName(it) == "id" && xml.getAttributeNamespace(it)?.endsWith("/relationships") == true }
                    ?.let(xml::getAttributeValue)
                    ?: xml.getAttributeValue(null, "id").orEmpty()

            /** Whether the sheet has any value, and any formula: one pass that stops once both are known. */
            private fun scanSheet(zip: ZipFile, path: String): Pair<Boolean, Boolean> {
                var values = false
                var formulas = false
                XlsxSource.parse(zip, path, required = false) { xml ->
                    while (xml.hasNext() && !(values && formulas)) {
                        if (xml.next() != START_ELEMENT) continue
                        when (xml.localName) {
                            "v", "is" -> values = true
                            "f" -> formulas = true
                        }
                    }
                }
                return values to formulas
            }
        }
    }

    // endregion

    // region Patching

    /** One save: copies the workbook into [Patch.writeTo]'s target with the tables' changes applied. */
    private class Patch(
        private val zip: ZipFile,
        private val workbook: Workbook,
        private val hasHeader: Boolean,
        private val tables: List<OutputTable>,
        private val removed: Set<String>,
        private val layouts: Map<String, RowLayout>,
    ) {
        private val sheetsByName = workbook.sheets.associateBy { it.name.lowercase() }
        private val droppedSheets = workbook.sheets.filter { it.name.lowercase() in removed.map(String::lowercase) && it.isWorksheet }
        private val droppedPaths = droppedSheets.mapNotNull { it.path }.flatMap { listOf(it, relsPathOf(it)) }.map(String::lowercase).toSet()

        /**
         * The worksheet a table is written to: the one it came from, else one of its name that held no values (so it was
         * never a table), else none and it becomes a new sheet.
         */
        private fun targetOf(table: OutputTable): Sheet? =
            table.source?.let { sheetsByName[it.lowercase()] }
                ?: sheetsByName[table.name.lowercase()]?.takeIf { it.isWorksheet && it !in droppedSheets && tables.none { other -> other.source.equals(it.name, ignoreCase = true) } }

        /** Tables written over an existing sheet, by that sheet's part path. */
        private val rewritten: Map<String, OutputTable> =
            tables.mapNotNull { table ->
                val sheet = targetOf(table) ?: return@mapNotNull null
                if (table.changed || table.source == null) sheet.path!!.lowercase() to table else null
            }.toMap()

        private val renamed: Map<Sheet, String> =
            tables.mapNotNull { table -> targetOf(table)?.takeIf { it.name != table.name }?.let { it to table.name } }.toMap()

        private val added: List<NewSheet>
        private val calcChainPath = workbook.relationships.values.firstOrNull { it.first.endsWith("/calcChain") }?.second

        private class NewSheet(val table: OutputTable, val path: String, val relationId: String, val sheetId: Int)

        init {
            val names = zip.entries().asSequence().map { it.name.lowercase() }.toMutableSet()
            val ids = workbook.relationships.keys.toMutableSet()
            var sheetNumber = 1
            var relationNumber = 1
            var sheetId = workbookSheetIds().maxOrNull() ?: 0
            val directory = workbook.directory.let { if (it.isEmpty()) "" else "$it/" }
            added =
                tables.filter { table -> targetOf(table) == null }.map { table ->
                    while ("${directory}worksheets/sheet$sheetNumber.xml".lowercase() in names) sheetNumber++
                    val path = "${directory}worksheets/sheet$sheetNumber.xml"
                    names += path.lowercase()
                    while ("rId$relationNumber" in ids) relationNumber++
                    val id = "rId$relationNumber"
                    ids += id
                    NewSheet(table, path, id, ++sheetId)
                }
        }

        private val touched: Boolean
            get() = rewritten.isNotEmpty() || added.isNotEmpty() || droppedSheets.isNotEmpty()

        fun writeTo(target: Path) {
            ZipOutputStream(Files.newOutputStream(target)).use { out ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    val lower = name.lowercase()
                    when {
                        lower in droppedPaths -> continue
                        // Excel rebuilds the calculation chain; a stale one makes it "repair" the workbook.
                        touched && calcChainPath != null && lower == calcChainPath.lowercase() -> continue
                        lower in rewritten -> {
                            out.putNextEntry(ZipEntry(name))
                            val table = rewritten.getValue(lower)
                            val template = Template.read(zip, name, layouts[(table.source ?: table.name).lowercase()], hasHeader)
                            zip.getInputStream(entry).use { input -> splice(input, out.writer(), template, table) }
                            out.closeEntry()
                        }
                        lower == workbook.workbookPath.lowercase() -> putText(out, name, patchWorkbook(readText(entry)))
                        lower == workbook.relationshipsPath.lowercase() -> putText(out, name, patchRelationships(readText(entry)))
                        lower == "[content_types].xml" -> putText(out, name, patchContentTypes(readText(entry)))
                        else -> {
                            out.putNextEntry(ZipEntry(name))
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
                for (sheet in added) {
                    out.putNextEntry(ZipEntry(sheet.path))
                    val writer = out.writer()
                    writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n")
                    writer.write("""<worksheet xmlns="$MainNamespace" xmlns:r="$RelationshipsNamespace">""")
                    writeSheetData(writer, "", Template.Empty, sheet.table)
                    writer.write("</worksheet>")
                    writer.flush()
                    out.closeEntry()
                }
            }
        }

        private fun ZipOutputStream.writer(): Writer = java.io.OutputStreamWriter(NonClosing(this), Charsets.UTF_8).buffered(64 * 1024)

        private fun readText(entry: ZipEntry): String = zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }

        private fun putText(out: ZipOutputStream, name: String, text: String) {
            out.putNextEntry(ZipEntry(name))
            out.write(text.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }

        private fun workbookSheetIds(): List<Int> =
            zip.getInputStream(XlsxSource.findEntry(zip, workbook.workbookPath)!!).use { String(it.readAllBytes(), Charsets.UTF_8) }
                .let { text -> SheetElement.findAll(text).mapNotNull { attribute(it.value, "sheetId")?.toIntOrNull() }.toList() }

        /** Sheet list, defined names and the active tab, edited as text: the rest of workbook.xml stays exactly as it was. */
        private fun patchWorkbook(text: String): String {
            if (droppedSheets.isEmpty() && renamed.isEmpty() && added.isEmpty()) return text
            val droppedIds = droppedSheets.map { it.relationId }.toSet()
            val kept = workbook.sheets.filter { it.relationId !in droppedIds }
            val newIndex = workbook.sheets.indices.associateWith { old -> kept.indexOf(workbook.sheets[old]).takeIf { it >= 0 } }
            var result =
                SheetElement.replace(text) { match ->
                    val element = match.value
                    val id = relationIdAttribute(element)
                    when {
                        id in droppedIds -> ""
                        else -> renamed.entries.firstOrNull { it.key.relationId == id }?.let { setAttribute(element, "name", escapeAttribute(it.value)) } ?: element
                    }
                }
            if (added.isNotEmpty()) {
                val close = SheetsClose.find(result) ?: throw DataFileException("The workbook's sheet list is malformed; it can't take a new sheet.")
                val prefix = close.groupValues[1]
                val relationPrefix = Regex("""\s(\w+):id\s*=""").find(SheetElement.find(text)?.value.orEmpty())?.groupValues?.get(1) ?: "r"
                val elements =
                    added.joinToString("") { """<${prefix}sheet name="${escapeAttribute(it.table.name)}" sheetId="${it.sheetId}" $relationPrefix:id="${it.relationId}"/>""" }
                result = result.substring(0, close.range.first) + elements + result.substring(close.range.first)
                if (relationPrefix == "r" && "xmlns:r=" !in result) {
                    result = result.replaceFirst(Regex("""<(\w+:)?workbook\b"""), "$0 xmlns:r=\"$RelationshipsNamespace\"")
                }
            }
            result =
                DefinedName.replace(result) { match ->
                    var element = match.value
                    val local = attribute(element, "localSheetId")?.toIntOrNull()
                    if (local != null) {
                        val moved = newIndex[local] ?: return@replace ""
                        element = setAttribute(element, "localSheetId", moved.toString())
                    }
                    val body = match.groupValues[2]
                    if (droppedSheets.any { references(body, it.name) }) return@replace ""
                    var newBody = body
                    renamed.forEach { (sheet, name) -> newBody = renameReferences(newBody, sheet.name, name) }
                    if (newBody != body) element = element.replace(">$body<", ">$newBody<")
                    element
                }
            if (droppedSheets.isNotEmpty()) {
                result = result.replace(Regex("""\b(activeTab|firstSheet)="\d+""""), "$1=\"0\"")
            }
            return result
        }

        private fun patchRelationships(text: String): String {
            var result = text
            val removeIds = droppedSheets.map { it.relationId }.toSet() + if (touched) workbook.relationships.filter { it.value.first.endsWith("/calcChain") }.keys else emptySet()
            if (removeIds.isNotEmpty()) {
                result = RelationshipElement.replace(result) { match -> if (attribute(match.value, "Id") in removeIds) "" else match.value }
            }
            if (added.isNotEmpty()) {
                val close = RelationshipsClose.find(result) ?: throw DataFileException("The workbook's relationships are malformed; it can't take a new sheet.")
                val prefix = close.groupValues[1]
                val base = workbook.directory.let { if (it.isEmpty()) "" else "$it/" }
                val elements =
                    added.joinToString("") {
                        """<${prefix}Relationship Id="${it.relationId}" Type="$WorksheetRelationship" Target="${it.path.removePrefix(base)}"/>"""
                    }
                result = result.substring(0, close.range.first) + elements + result.substring(close.range.first)
            }
            return result
        }

        private fun patchContentTypes(text: String): String {
            var result = text
            val removeParts = droppedSheets.mapNotNull { it.path }.map { "/$it".lowercase() }.toMutableSet()
            if (touched && calcChainPath != null) removeParts += "/$calcChainPath".lowercase()
            if (removeParts.isNotEmpty()) {
                result = OverrideElement.replace(result) { match -> if (attribute(match.value, "PartName")?.lowercase() in removeParts) "" else match.value }
            }
            if (added.isNotEmpty()) {
                val close = TypesClose.find(result) ?: throw DataFileException("The workbook's content types are malformed; it can't take a new sheet.")
                val prefix = close.groupValues[1]
                val elements = added.joinToString("") { """<${prefix}Override PartName="/${it.path}" ContentType="$WorksheetContentType"/>""" }
                result = result.substring(0, close.range.first) + elements + result.substring(close.range.first)
            }
            return result
        }

        /** Copies a sheet part, replacing `<sheetData>` with the table's rows and dropping `<dimension>` (Excel recomputes it). */
        private fun splice(input: InputStream, writer: Writer, template: Template, table: OutputTable) {
            val reader = BufferedReader(input.reader(Charsets.UTF_8), 64 * 1024)
            var skipping: String? = null
            var lastRow: Long? = null
            while (true) {
                val c = reader.read()
                if (c == -1) break
                if (c != '<'.code) {
                    if (skipping == null) writer.write(c)
                    continue
                }
                val tag = readTag(reader)
                if (skipping != null) {
                    if (tag.isEnd && tag.localName == skipping) skipping = null
                    continue
                }
                when {
                    tag.isStart && tag.localName == "dimension" -> if (!tag.selfClosing) skipping = "dimension"
                    tag.isStart && tag.localName == "sheetData" -> {
                        var ended = tag.selfClosing
                        lastRow = writeSheetData(writer, tag.prefix, template, table) { if (!ended) ended = copyRowsAbove(reader, writer, template.firstRow) }
                        if (!ended) skipping = "sheetData"
                    }
                    // A filter over the table grows and shrinks with it.
                    tag.isStart && tag.localName == "autoFilter" && lastRow != null -> writer.write(resizeFilter(tag.text, template.firstRow, lastRow!!))
                    else -> writer.write(tag.text)
                }
            }
            writer.flush()
        }

        /**
         * Copies the original rows above [firstRow] (blank-looking rows the reader skipped, which may still carry
         * formatting) and stops at the first row of the table. Returns whether it reached the end of `<sheetData>`.
         */
        private fun copyRowsAbove(reader: Reader, writer: Writer, firstRow: Long): Boolean {
            var copying = false
            while (true) {
                val c = reader.read()
                if (c == -1) return true
                if (c != '<'.code) {
                    if (copying) writer.write(c)
                    continue
                }
                val tag = readTag(reader)
                if (copying) {
                    writer.write(tag.text)
                    if (tag.isEnd && tag.localName == "row") copying = false
                    continue
                }
                when {
                    tag.isEnd && tag.localName == "sheetData" -> return true
                    tag.isStart && tag.localName == "row" -> {
                        val number = attribute(tag.text, "r")?.toLongOrNull()
                        if (number == null || number >= firstRow) return false
                        writer.write(tag.text)
                        if (!tag.selfClosing) copying = true
                    }
                }
            }
        }

        /** Writes the rows after [above]'s; returns the last row number written. */
        private fun writeSheetData(writer: Writer, prefix: String, template: Template, table: OutputTable, above: () -> Unit = {}): Long {
            if (table.columns.size > MaxColumns) throw DataFileException("\"${table.name}\" has ${table.columns.size} columns; a sheet holds at most $MaxColumns.")
            val letters = table.columns.indices.map(::columnLetters)
            var rowNumber = template.firstRow
            writer.write("<${prefix}sheetData>")
            above()
            fun row(cells: () -> Unit) {
                if (rowNumber > MaxRows) throw DataFileException("\"${table.name}\" has more rows than a sheet holds ($MaxRows).")
                writer.write("<${prefix}row r=\"$rowNumber\">")
                cells()
                writer.write("</${prefix}row>")
                rowNumber++
            }
            if (hasHeader) {
                row { table.columns.forEachIndexed { i, column -> inlineString(writer, prefix, "${letters[i]}$rowNumber", template.headerStyles.getOrNull(i), column.name, table) } }
            }
            for (values in table.rows()) {
                row {
                    values.forEachIndexed { i, value -> cell(writer, prefix, "${letters[i]}$rowNumber", template.data.getOrNull(i), value, table) }
                }
            }
            writer.write("</${prefix}sheetData>")
            return maxOf(rowNumber - 1, template.firstRow)
        }

        /** Sets the end row of a filter's `ref` that starts at the table's first row; any other filter stays. */
        private fun resizeFilter(tag: String, firstRow: Long, lastRow: Long): String {
            val ref = attribute(tag, "ref") ?: return tag
            val match = Regex("""^([A-Z]+)(\d+):([A-Z]+)(\d+)$""").find(ref) ?: return tag
            if (match.groupValues[2].toLong() != firstRow) return tag
            return setAttribute(tag, "ref", "${match.groupValues[1]}$firstRow:${match.groupValues[3]}$lastRow")
        }

        private fun cell(writer: Writer, prefix: String, reference: String, template: CellTemplate?, value: Any?, table: OutputTable) {
            val style = template?.style?.takeIf { it != 0 }
            val styleAttribute = style?.let { " s=\"$it\"" }.orEmpty()
            when (value) {
                null -> if (style != null) writer.write("<${prefix}c r=\"$reference\"$styleAttribute/>")
                is Long, is Int -> writer.write("<${prefix}c r=\"$reference\"$styleAttribute><${prefix}v>$value</${prefix}v></${prefix}c>")
                is Double, is Float -> writer.write("<${prefix}c r=\"$reference\"$styleAttribute><${prefix}v>${DataValues.real((value as Number).toDouble())}</${prefix}v></${prefix}c>")
                else -> {
                    val text = DataValues.text(value)!!
                    val dateKind = style?.let { workbook.dateStyles.getOrNull(it) }
                    val serial = dateKind?.let { excelSerial(text, workbook.date1904) }
                    when {
                        template?.type == "b" && (text == "true" || text == "false") ->
                            writer.write("<${prefix}c r=\"$reference\"$styleAttribute t=\"b\"><${prefix}v>${if (text == "true") 1 else 0}</${prefix}v></${prefix}c>")
                        serial != null -> writer.write("<${prefix}c r=\"$reference\"$styleAttribute><${prefix}v>$serial</${prefix}v></${prefix}c>")
                        template != null && template.numeric && (DataValues.isInteger(text) || DataValues.isReal(text)) ->
                            writer.write("<${prefix}c r=\"$reference\"$styleAttribute><${prefix}v>$text</${prefix}v></${prefix}c>")
                        else -> inlineString(writer, prefix, reference, style, text, table)
                    }
                }
            }
        }

        private fun inlineString(writer: Writer, prefix: String, reference: String, style: Int?, text: String, table: OutputTable) {
            if (text.length > MaxCellText) throw DataFileException("A value in \"${table.name}\" (cell $reference) is ${text.length} characters long; a cell holds at most $MaxCellText.")
            val styleAttribute = style?.takeIf { it != 0 }?.let { " s=\"$it\"" }.orEmpty()
            val space = if (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace())) " xml:space=\"preserve\"" else ""
            writer.write("<${prefix}c r=\"$reference\"$styleAttribute t=\"inlineStr\"><${prefix}is><${prefix}t$space>${escapeText(text)}</${prefix}t></${prefix}is></${prefix}c>")
        }
    }

    /** How a column's cells look in the sheet being replaced: the header row's styles and the first data row's. */
    private class Template(val firstRow: Long, val headerStyles: List<Int?>, val data: List<CellTemplate?>) {
        companion object {
            val Empty = Template(1, emptyList(), emptyList())

            /**
             * The styles of [layout]'s header and first data row in [path]. Without a layout (the sheet had no table
             * before), the first row of the sheet is where writing starts and nothing is styled.
             */
            fun read(zip: ZipFile, path: String, layout: RowLayout?, hasHeader: Boolean): Template {
                if (layout == null) return Empty
                val firstRow = layout.header ?: layout.data ?: return Empty
                val wanted = setOfNotNull(layout.header.takeIf { hasHeader }, layout.data)
                val rows = HashMap<Long, Map<Int, CellTemplate>>()
                try {
                    XlsxSource.parse(zip, path, required = true) { xml ->
                        var lastRow = 0L
                        while (xml.hasNext() && rows.size < wanted.size) {
                            if (xml.next() != START_ELEMENT || xml.localName != "row") continue
                            val number = xml.getAttributeValue(null, "r")?.toLongOrNull() ?: (lastRow + 1)
                            lastRow = number
                            if (number > wanted.max()) break
                            val cells = HashMap<Int, CellTemplate>()
                            var lastColumn = -1
                            loop@ while (xml.hasNext()) {
                                when (xml.next()) {
                                    START_ELEMENT ->
                                        if (xml.localName == "c") {
                                            val column = xml.getAttributeValue(null, "r")?.let(XlsxSource::columnIndex) ?: (lastColumn + 1)
                                            lastColumn = column
                                            cells[column] = CellTemplate(xml.getAttributeValue(null, "s")?.toIntOrNull() ?: 0, xml.getAttributeValue(null, "t"))
                                        }
                                    END_ELEMENT -> if (xml.localName == "row") break@loop
                                }
                            }
                            if (number in wanted) rows[number] = cells
                        }
                    }
                } catch (e: XMLStreamException) {
                    throw DataFileException("Sheet part $path is malformed: ${e.message}", e)
                }
                fun <T> dense(map: Map<Int, T>): List<T?> = (0..(map.keys.maxOrNull() ?: -1)).map { map[it] }
                val header = layout.header?.takeIf { hasHeader }?.let { rows[it] }.orEmpty()
                val data = layout.data?.let { rows[it] }.orEmpty()
                return Template(firstRow, dense(header).map { it?.style }, dense(data))
            }
        }
    }

    /** Sheet rows of a table's header (null without one) and first data row (null when it had none). */
    private class RowLayout(val header: Long?, val data: Long?)

    private class CellTemplate(val style: Int, val type: String?) {
        val numeric: Boolean
            get() = type == null || type == "n"
    }

    // endregion

    // region XML helpers

    private class Tag(val text: String, val isStart: Boolean, val isEnd: Boolean, val selfClosing: Boolean, val prefix: String, val localName: String)

    /** Reads a tag after its `<`: comments, processing instructions and CDATA come back whole with no name. */
    private fun readTag(reader: Reader): Tag {
        val text = StringBuilder("<")
        var quote = 0
        while (true) {
            val c = reader.read()
            if (c == -1) break
            text.append(c.toChar())
            if (text.length == 4 && text.startsWith("<!--")) return Tag(readUntil(reader, text, "-->"), false, false, false, "", "")
            if (text.length == 9 && text.startsWith("<![CDATA[")) return Tag(readUntil(reader, text, "]]>"), false, false, false, "", "")
            if (quote != 0) {
                if (c == quote) quote = 0
            } else if (c == '"'.code || c == '\''.code) {
                quote = c
            } else if (c == '>'.code) {
                break
            }
        }
        val body = text.toString()
        if (body.startsWith("<?") || body.startsWith("<!")) return Tag(body, false, false, false, "", "")
        val isEnd = body.startsWith("</")
        val name = body.removePrefix("</").removePrefix("<").takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
        val selfClosing = body.endsWith("/>")
        return Tag(body, !isEnd, isEnd, selfClosing, name.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }, name.substringAfter(':'))
    }

    private fun readUntil(reader: Reader, text: StringBuilder, end: String): String {
        while (!text.endsWith(end)) {
            val c = reader.read()
            if (c == -1) break
            text.append(c.toChar())
        }
        return text.toString()
    }

    private val SheetElement = Regex("""<(?:\w+:)?sheet\b[^>]*?/>""")
    private val SheetsClose = Regex("""</(\w+:)?sheets>""")
    private val DefinedName = Regex("""<((?:\w+:)?definedName)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val RelationshipElement = Regex("""<(?:\w+:)?Relationship\b[^>]*?/>""")
    private val RelationshipsClose = Regex("""</(\w+:)?Relationships>""")
    private val OverrideElement = Regex("""<(?:\w+:)?Override\b[^>]*?/>""")
    private val TypesClose = Regex("""</(\w+:)?Types>""")

    private fun attribute(element: String, name: String): String? =
        Regex("""\s$name\s*=\s*("([^"]*)"|'([^']*)')""").find(element)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }?.let(::unescapeAttribute)

    private fun relationIdAttribute(element: String): String? =
        Regex("""\s\w+:id\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)

    /** [value] must already be escaped. */
    private fun setAttribute(element: String, name: String, value: String): String =
        Regex("""(\s$name\s*=\s*)("[^"]*"|'[^']*')""").replace(element) { "${it.groupValues[1]}\"$value\"" }

    /** Whether a formula (as stored in a defined name, XML-escaped) refers to sheet [name]. */
    private fun references(formula: String, name: String): Boolean = renameReferences(formula, name, "\u0000") != formula

    private fun renameReferences(formula: String, from: String, to: String): String {
        val quoted = escapeText("'${from.replace("'", "''")}'!")
        val target = if (to.all { it.isLetterOrDigit() || it == '_' || it == '.' } && to.firstOrNull()?.isLetter() == true) "$to!" else "'${to.replace("'", "''")}'!"
        var result = formula.replace(quoted, escapeText(target))
        if (from.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
            result = Regex("""(?<![\w.'])${Regex.escape(from)}!""").replace(result) { escapeText(target) }
        }
        return result
    }

    private fun escapeAttribute(text: String): String = escapeText(text).replace("\"", "&quot;")

    private fun unescapeAttribute(text: String): String =
        text.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * Escapes text for XML, writing characters XML can't hold as `_xHHHH_` (Excel's own escape) and protecting literal
     * `_xHHHH_` sequences with `_x005F_`, the inverse of what the reader unescapes.
     */
    internal fun escapeText(text: String): String {
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c == '_' && i + 6 < text.length && text[i + 1] == 'x' && text[i + 6] == '_' && text.substring(i + 2, i + 6).toIntOrNull(16) != null -> out.append("_x005F_")
                (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c == '￾' || c == '￿' -> out.append("_x%04X_".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun relsPathOf(part: String): String = "${part.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }}_rels/${part.substringAfterLast('/')}.rels"

    /** `A`, `B`… `Z`, `AA`… for a 0-based column. */
    internal fun columnLetters(index: Int): String {
        var n = index + 1
        val letters = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            letters.append('A' + rem)
            n = (n - 1) / 26
        }
        return letters.reverse().toString()
    }

    /** Sheet names Excel accepts: 1–31 characters, none of `[]:*?/\`, not starting or ending with an apostrophe. */
    internal fun validateSheetName(name: String) {
        val problem =
            when {
                name.isBlank() -> "is blank"
                name.length > 31 -> "is longer than 31 characters"
                name.any { it in "[]:*?/\\" } -> "contains one of [ ] : * ? / \\"
                name.startsWith("'") || name.endsWith("'") -> "starts or ends with an apostrophe"
                name.equals("History", ignoreCase = true) -> "is reserved by Excel"
                else -> null
            }
        if (problem != null) throw DataFileException("Table \"$name\" can't be a sheet: its name $problem. Rename the table to save the workbook.")
    }

    /**
     * The serial number Excel stores for an ISO date, date-time or time as the reader writes them (`2024-01-31`,
     * `2024-01-31 13:45:00`, `13:45:00`), or null when [text] isn't one. The inverse of the reader's conversion,
     * including the 1900 system's phantom leap day.
     */
    internal fun excelSerial(text: String, date1904: Boolean): String? {
        val dateTime: LocalDateTime =
            runCatching { LocalDateTime.parse(text.replace(' ', 'T')) }.getOrNull()
                ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
                ?: runCatching { LocalTime.parse(text) }.getOrNull()?.let { return DataValues.real(it.toSecondOfDay() / 86_400.0) }
                ?: return null
        val epoch =
            when {
                date1904 -> LocalDate.of(1904, 1, 1)
                dateTime.toLocalDate() < LocalDate.of(1900, 3, 1) -> LocalDate.of(1899, 12, 31)
                else -> LocalDate.of(1899, 12, 30)
            }
        val days = ChronoUnit.DAYS.between(epoch, dateTime.toLocalDate())
        if (days < 0) return null
        val seconds = Duration.between(dateTime.toLocalDate().atStartOfDay(), dateTime).seconds
        return DataValues.real(days + seconds / 86_400.0)
    }

    /** Lets a writer be flushed into a zip entry without closing the zip stream. */
    private class NonClosing(private val out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = flush()
    }

    private const val MainNamespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val RelationshipsNamespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WorksheetRelationship = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    private const val WorksheetContentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    private const val MaxRows = 1_048_576L
    private const val MaxColumns = 16_384
    private const val MaxCellText = 32_767

    // endregion
}

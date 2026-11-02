package dev.tablegrip.core.datafile

import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.importer.ImportException
import dev.tablegrip.core.importer.ImportFormat
import dev.tablegrip.core.importer.ImportOptions
import dev.tablegrip.core.importer.ImportReader
import dev.tablegrip.core.importer.ImportSource
import dev.tablegrip.core.importer.bomCharset
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.nameWithoutExtension

/**
 * CSV and TSV: one table, named after the file. Reading is the importer's CSV reader; writing keeps the file's encoding,
 * byte-order mark, delimiter and line ending, and quotes only fields that need it. An empty string is written `""` so it
 * reads back as empty, not as a missing value.
 */
object CsvFormat : DataFileFormat {
    override val type: DatabaseType = DatabaseType.CsvFile
    override val extensions: Set<String> = setOf("csv", "tsv", "tab", "txt")

    override fun open(path: Path, options: DataFileOptions): DataFileDocument {
        val charset =
            options.charset?.let { name ->
                runCatching { Charset.forName(name) }.getOrElse { throw DataFileException("Unknown encoding \"$name\"") }
            }
        val importOptions =
            ImportOptions(
                ImportFormat.Csv,
                delimiter = options.delimiter ?: if (path.fileName.toString().lowercase().let { it.endsWith(".tsv") || it.endsWith(".tab") }) '\t' else null,
                hasHeader = options.hasHeader,
                charset = charset,
            )
        val source = openSource(path, importOptions)
        val head = path.inputStream().use { it.readNBytes(LINE_ENDING_SCAN) }
        val detected = source.options.charset ?: StandardCharsets.UTF_8
        val layout =
            Layout(
                charset = detected,
                bom = bomCharset(head)?.first == detected,
                delimiter = source.options.delimiter ?: ',',
                quote = source.options.quote,
                hasHeader = options.hasHeader,
                lineEnding = if (String(head, detected).contains("\r\n")) "\r\n" else "\n",
            )
        return Document(path.nameWithoutExtension.ifBlank { "data" }, source, layout)
    }

    private fun openSource(path: Path, options: ImportOptions): ImportSource =
        try {
            ImportReader.open(path.toFile(), options)
        } catch (e: ImportException) {
            throw DataFileException(e.message ?: "Couldn't read ${path.fileName}", e)
        }

    private data class Layout(val charset: Charset, val bom: Boolean, val delimiter: Char, val quote: Char, val hasHeader: Boolean, val lineEnding: String)

    private class Document(name: String, private val source: ImportSource, private val layout: Layout) : DataFileDocument {
        override val multiTable = false

        override val tables: List<SourceTable> =
            listOf(
                object : SourceTable {
                    override val name = name
                    override val columns = source.columns

                    override fun rows(): Sequence<List<String?>> =
                        sequence {
                            source.use {
                                try {
                                    for (row in it.rows()) yield(row.values)
                                } catch (e: ImportException) {
                                    throw DataFileException(e.message ?: "Couldn't read the file", e)
                                }
                            }
                        }
                },
            )

        override fun close() = source.close()

        override fun write(target: Path, tables: List<OutputTable>, removed: Set<String>) {
            val table = tables.single()
            val encoder = layout.charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            Files.newOutputStream(target).use { stream ->
                if (layout.bom) stream.write(bomBytes(layout.charset))
                BufferedWriter(OutputStreamWriter(stream, encoder), 64 * 1024).use { out ->
                    var line = 0L
                    fun record(values: List<String?>) {
                        line++
                        values.forEachIndexed { i, value ->
                            if (i > 0) out.write(layout.delimiter.code)
                            if (value != null) out.write(field(value))
                        }
                        out.write(layout.lineEnding)
                    }
                    try {
                        if (layout.hasHeader) record(table.columns.map { it.name })
                        for (row in table.rows()) record(row.map(DataValues::text))
                        out.flush()
                    } catch (e: CharacterCodingException) {
                        throw DataFileException("Line $line has characters ${layout.charset.displayName()} can't hold. Change the connection's encoding to UTF-8.", e)
                    }
                }
            }
        }

        private fun field(value: String): String {
            val quote = layout.quote
            val needsQuotes = value.isEmpty() || value.any { it == layout.delimiter || it == quote || it == '\n' || it == '\r' }
            return if (needsQuotes) "$quote${value.replace(quote.toString(), "$quote$quote")}$quote" else value
        }
    }

    private fun bomBytes(charset: Charset): ByteArray =
        when (charset) {
            StandardCharsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            StandardCharsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            else -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        }

    private const val LINE_ENDING_SCAN = 64 * 1024
}

package dev.tablegrip.core.importer

import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/** File formats rows can be imported from. */
enum class ImportFormat(val label: String, val extensions: Set<String>) {
    Csv("CSV / TSV", setOf("csv", "tsv", "txt")),
    Json("JSON / NDJSON", setOf("json", "ndjson", "jsonl")),
    Xlsx("Excel workbook", setOf("xlsx", "xlsm"));

    companion object {
        /** The format [file]'s extension implies, or null when it isn't one TableGrip reads (e.g. legacy `.xls`). */
        fun detect(file: File): ImportFormat? {
            val extension = file.extension.lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/**
 * How to read a file. Null fields are detected when the file is opened; [ImportSource.options] has them filled in so the
 * UI can show (and let the user override) what was picked.
 */
data class ImportOptions(
    val format: ImportFormat,
    /** CSV: null = detect from the first records among `,` `;` tab and `|`. */
    val delimiter: Char? = null,
    val quote: Char = '"',
    /** CSV and XLSX; when false columns are named column_1, column_2… */
    val hasHeader: Boolean = true,
    /** CSV and JSON: null = detect (BOM for UTF-8/UTF-16, else UTF-8 if it decodes cleanly, else windows-1252). */
    val charset: Charset? = null,
    /** XLSX: null = first sheet. */
    val sheet: String? = null,
    /** Empty unquoted CSV field / empty cell -> null. Quoted `""` and JSON `""` stay empty strings. */
    val emptyAsNull: Boolean = true,
    /** Lines (CSV) or sheet rows (XLSX) to skip before the header, e.g. a report title. */
    val skipRows: Int = 0,
) {
    init {
        require(skipRows >= 0) { "skipRows must not be negative" }
        require(delimiter != quote) { "The delimiter and the quote must differ" }
        require(delimiter != '\n' && delimiter != '\r' && quote != '\n' && quote != '\r') { "Line breaks can't be delimiters or quotes" }
    }
}

/** One data row; [line] is 1-based position in the source for error messages (CSV physical line where the record starts, XLSX row number, JSON record index). */
data class ImportRow(val line: Long, val values: List<String?>)

/** An open file. Rows are read lazily; rows shorter than columns are padded with null, longer ones are an error naming the line. */
interface ImportSource : AutoCloseable {
    /** Header names, trimmed, de-duplicated case-insensitively (name, name_2), blanks -> column_N. */
    val columns: List<String>

    /** The options with detected delimiter/charset/sheet filled in. */
    val options: ImportOptions

    /** XLSX sheet names in workbook order, else empty. */
    val sheets: List<String>

    /** The data rows, read as the sequence is iterated. Single pass: a second call throws. Errors are [ImportException]s. */
    fun rows(): Sequence<ImportRow>
}

/** What the preview grid shows: the first rows, and per column the type [TypeInference] guessed from them. */
data class ImportPreview(
    val columns: List<String>,
    val rows: List<ImportRow>,
    val types: List<InferredType>,
    val options: ImportOptions,
    val sheets: List<String>,
)

/** A file that can't be read as asked; [line] (same meaning as [ImportRow.line]) points at the offending spot when known. */
class ImportException(message: String, val line: Long? = null, cause: Throwable? = null) : Exception(message, cause)

/** Entry point: turns a user's file into [ImportRow]s for "Import data into a table". */
object ImportReader {
    /**
     * Opens [file] for reading all its rows. Cost before the first row: CSV reads up to a few hundred KB to detect the
     * encoding and delimiter; XLSX scans the sheet once to find its width; a JSON array or NDJSON is scanned once for the
     * union of keys (a JSON object wrapping the rows is read into memory, up to [JsonSource.MAX_DOCUMENT_BYTES]).
     */
    fun open(file: File, options: ImportOptions): ImportSource = openSource(file, options, previewRows = null)

    /** Up to [limit] rows plus the inferred type of each column (from those rows), for the preview grid. */
    fun preview(file: File, options: ImportOptions, limit: Int = 200): ImportPreview {
        require(limit >= 0) { "limit must not be negative" }
        openSource(file, options, previewRows = limit).use { source ->
            val rows = guard { source.rows().take(limit).toList() }
            val types = source.columns.indices.map { i -> TypeInference.infer(rows.map { it.values[i] }) }
            return ImportPreview(source.columns, rows, types, source.options, source.sheets)
        }
    }

    /** [previewRows] lets a format read only what the preview needs (XLSX/JSON then size columns from those rows alone). */
    private fun openSource(file: File, options: ImportOptions, previewRows: Int?): ImportSource {
        if (!file.isFile) throw ImportException("${file.path} doesn't exist or isn't a file")
        if (!file.canRead()) throw ImportException("${file.path} can't be read; check its permissions")
        return guard {
            when (options.format) {
                ImportFormat.Csv -> CsvSource(file, options)
                ImportFormat.Json -> JsonSource(file, options, previewRows)
                ImportFormat.Xlsx -> XlsxSource(file, options, previewRows)
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw ImportException("Couldn't read the file: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
}

/** Trims header names, names blanks column_N (1-based position), and suffixes repeats _2, _3… (case-insensitively, as SQL would). */
internal fun normalizeColumnNames(raw: List<String?>): List<String> {
    val used = HashSet<String>()
    return raw.mapIndexed { i, name ->
        val base = name?.trim().orEmpty().ifEmpty { "column_${i + 1}" }
        var unique = base
        var suffix = 2
        while (!used.add(unique.lowercase())) unique = "${base}_${suffix++}"
        unique
    }
}

internal fun generatedColumnNames(count: Int): List<String> = List(count) { "column_${it + 1}" }

/**
 * Pads [values] to [width] with nulls. Extra values are an error, except blank ones: a trailing delimiter (`a,b,`) is
 * too common in exported files to reject.
 */
internal fun fitRow(line: Long, values: List<String?>, width: Int, unit: String): ImportRow {
    if (values.size == width) return ImportRow(line, values)
    if (values.size < width) return ImportRow(line, values + arrayOfNulls<String>(width - values.size))
    if (values.subList(width, values.size).all { it.isNullOrBlank() }) return ImportRow(line, values.subList(0, width).toList())
    throw ImportException("$unit $line has ${values.size} fields but there are only $width columns", line)
}

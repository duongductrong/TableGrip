package dev.tablegrip.core.datafile

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.connection.DatabaseType
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * A kind of data file TableGrip opens as a connection: how to read its tables, and how to write them back. Formats know
 * nothing about SQL or connections: [DataFileStore] loads what they read into a working copy and hands them the tables
 * to write. Adding a format is an implementation of this, an entry in [DataFileFormats] and its [DatabaseType].
 */
interface DataFileFormat {
    /** The profile type that opens this format. */
    val type: DatabaseType

    /** Lower-case extensions this format claims, without the dot. */
    val extensions: Set<String>

    /**
     * Reads [path]. The returned document remembers how the file is laid out (encoding, delimiter, sheets…) so that
     * [DataFileDocument.write] can write it back the same way. Throws [DataFileException] when the file can't be read.
     */
    fun open(path: Path, options: DataFileOptions): DataFileDocument
}

/** One read of a data file: its tables, and how to write them back in the file's own layout. Close it when done reading. */
interface DataFileDocument : AutoCloseable {
    /** In file order. Each table's rows can be read once. */
    val tables: List<SourceTable>

    /** Whether the file can hold more than one table (an Excel workbook's sheets); a CSV holds exactly one. */
    val multiTable: Boolean

    /** Why changes can't be written back to this file, which then opens read-only; null when they can. */
    val readOnlyReason: String?
        get() = null

    /** Things the user should know before editing, e.g. that saving a sheet replaces its formulas with their values. */
    val notes: List<String>
        get() = emptyList()

    /**
     * Why writing changes to [tables] (names as read from this document) loses something the file has, so the user is
     * asked first; null when writing them loses nothing. Asked once per table per session.
     */
    fun confirmBeforeWriting(tables: Set<String>): String? = null

    /**
     * Writes [tables] (every table the file should now hold, in order) to [target], a fresh file next to the original.
     * [removed] names the tables of this document that are gone. A single-table document gets exactly one table.
     */
    fun write(target: Path, tables: List<OutputTable>, removed: Set<String>)

    /** Releases what reading holds open; [write] still works after it. */
    override fun close() {}
}

/** A table as read from a file. Values are text as the user would see it, null for an empty cell. */
interface SourceTable {
    val name: String

    /** Unique (case-insensitively), non-blank names. */
    val columns: List<String>

    /** Every data row, as wide as [columns]. Single pass. */
    fun rows(): Sequence<List<String?>>
}

/** What a column holds, decided by the working copy from every value in it. */
enum class ColumnKind(val sqlType: String) {
    /** Every value is an integer written the way [DataValues.integer] writes it back. */
    Integer("INTEGER"),

    /** Every value is a number written the way [DataValues.real] writes it back. */
    Real("REAL"),
    Text("TEXT"),
}

data class OutputColumn(val name: String, val kind: ColumnKind)

/**
 * A table to write. [source] is the name it had in the file when it came from there (renamed tables keep pointing at
 * their sheet); null for a table that's new. Unchanged tables are passed too, so a format that rewrites the whole file
 * has them, but [changed] false lets one that patches parts leave theirs alone.
 */
class OutputTable(
    val name: String,
    val source: String?,
    val changed: Boolean,
    val columns: List<OutputColumn>,
    /** The rows, read when called: [Long], [Double], [String], [ByteArray] or null per value. */
    val rows: () -> Sequence<List<Any?>>,
)

/** A data file that can't be read or written as asked. */
class DataFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The formats TableGrip opens, and which one a profile or file is. */
object DataFileFormats {
    val all: List<DataFileFormat> = listOf(CsvFormat, XlsxFormat, JsonFormat)

    fun of(type: DatabaseType): DataFileFormat = all.firstOrNull { it.type == type } ?: throw IllegalArgumentException(tr("{0} isn't a data file", type.displayName))

    /** The format [path]'s extension names, or null. */
    fun detect(path: Path): DataFileFormat? {
        val extension = path.extension.lowercase()
        return all.firstOrNull { extension in it.extensions }
    }
}

/**
 * The one way numbers are written back. A column is typed as a number only when every value in the file already reads
 * exactly like this, so loading and saving an untouched file never changes a character of it ("007", "1.50" and "1e5"
 * stay text).
 */
object DataValues {
    fun integer(value: Long): String = value.toString()

    fun real(value: Double): String =
        when {
            !value.isFinite() -> value.toString()
            value == Math.floor(value) && Math.abs(value) < 1e15 -> value.toLong().toString()
            else -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }

    fun isInteger(text: String): Boolean = text.toLongOrNull()?.let { integer(it) == text } == true

    fun isReal(text: String): Boolean = text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { real(it) == text } == true

    /** A value from the working copy as text, the way the file holds it. */
    fun text(value: Any?): String? =
        when (value) {
            null -> null
            is Long -> integer(value)
            is Int -> value.toString()
            is Double -> real(value)
            is Float -> real(value.toDouble())
            is ByteArray -> value.joinToString("") { "%02x".format(it) }
            else -> value.toString()
        }
}

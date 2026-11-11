package dev.tablegrip.core.connection

import dev.tablegrip.core.i18n.tr
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/** Recognises database files by their content, never by their name: `.db` means nothing, and many files lack one. */
object DatabaseFiles {
    private val SqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val DuckDBMagic = "DUCK".toByteArray(Charsets.US_ASCII)

    /** After four bytes of version: `.mdb` files (Jet) and `.accdb` ones (ACE). */
    private val AccessMagics = listOf("Standard Jet DB", "Standard ACE DB").map { it.toByteArray(Charsets.US_ASCII) }

    /** The engine whose file [path] is, or null if it's none TableGrip opens, is empty, or can't be read. */
    fun detect(path: Path): DatabaseType? {
        val header = runCatching { if (path.isRegularFile()) path.inputStream().use { it.readNBytes(19) } else null }.getOrNull() ?: return null
        return when {
            header.size >= SqliteHeader.size && header.copyOf(SqliteHeader.size).contentEquals(SqliteHeader) -> DatabaseType.SQLite
            // DuckDB's main header: a checksum, then the magic bytes.
            header.size >= 12 && header.copyOfRange(8, 12).contentEquals(DuckDBMagic) -> DatabaseType.DuckDB
            header.size >= 19 && AccessMagics.any { header.copyOfRange(4, 19).contentEquals(it) } -> DatabaseType.Access
            else -> null
        }
    }

    /**
     * Why [path] can't be opened as [type], or null if it can. A missing or empty file is fine: SQLite creates the
     * database in it.
     */
    fun mismatch(path: Path, type: DatabaseType): String? {
        val size = runCatching { if (path.isRegularFile()) path.toFile().length() else 0L }.getOrDefault(0L)
        if (size == 0L) return null
        val detected = detect(path)
        return when (detected) {
            type -> null
            null -> tr("{0} isn't a {1} database: its header doesn't match.", path.fileName, type.displayName)
            else -> tr("{0} is a {1} database, not {2}.", path.fileName, detected.displayName, type.displayName)
        }
    }
}

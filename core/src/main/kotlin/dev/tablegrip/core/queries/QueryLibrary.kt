package dev.tablegrip.core.queries

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.TableGripPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * A saved query: a `.sql` file under the library's root. [folder] is its directory relative to the root (`""` at the top,
 * `reports/monthly` nested). [connection] is the profile it runs on, by name, from its `-- connection: …` first line;
 * null runs it on the current connection.
 */
data class SavedQuery(val file: Path, val name: String, val folder: String, val connection: String?, val sql: String)

/** Saved queries on disk: one `.sql` file each, folders as plain directories. */
class QueryLibrary(val root: Path = TableGripPaths.queries) {
    /** Every saved query, by folder then name. Unreadable files are skipped. */
    fun list(): List<SavedQuery> {
        if (!root.exists()) return emptyList()
        return root.walk()
            .filter { it.isRegularFile() && it.extension.equals("sql", ignoreCase = true) }
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            .sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
            .toList()
    }

    fun read(file: Path): SavedQuery {
        val text = file.readText()
        val folder = file.parent.relativeTo(root).invariantSeparatorsPathString
        return SavedQuery(file, file.nameWithoutExtension, folder, connectionOf(text), text)
    }

    /** Where a query named [name] in [folder] lives; names become safe file names. */
    fun fileFor(name: String, folder: String = ""): Path {
        val directory = folder.split('/', '\\').map(::safeName).filter { it.isNotEmpty() }.fold(root) { dir, part -> dir.resolve(part) }
        return directory.resolve(safeName(name).ifEmpty { "query" } + ".sql")
    }

    /**
     * Writes [sql] as [name] in [folder], replacing an existing file of that name. With a [connection], the file starts
     * with its `-- connection:` line (replacing any there was); null removes it.
     */
    fun save(name: String, folder: String, sql: String, connection: String?): SavedQuery {
        val file = fileFor(name, folder)
        file.parent.createDirectories()
        file.writeText(withConnection(sql, connection))
        return read(file)
    }

    fun rename(query: SavedQuery, name: String, folder: String = query.folder): SavedQuery {
        val target = fileFor(name, folder)
        if (target == query.file) return query
        require(!target.exists()) { tr("A query named {0} already exists there", target.nameWithoutExtension) }
        target.parent.createDirectories()
        query.file.moveTo(target)
        return read(target)
    }

    fun delete(query: SavedQuery) {
        query.file.deleteIfExists()
    }

    companion object {
        private val ConnectionLine = Regex("""^--\s*connection:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

        /** The connection named on [sql]'s first line, if any. */
        fun connectionOf(sql: String): String? = ConnectionLine.find(sql.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)

        /** [sql] with its first-line `-- connection:` set to [connection], or removed when null. */
        fun withConnection(sql: String, connection: String?): String {
            val body = if (connectionOf(sql) != null) sql.substringAfter('\n', "") else sql
            return if (connection == null) body else "-- connection: $connection\n$body"
        }

        private fun safeName(name: String): String = name.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim('.', ' ')
    }
}

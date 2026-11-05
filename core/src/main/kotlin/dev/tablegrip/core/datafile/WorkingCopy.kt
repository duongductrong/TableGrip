package dev.tablegrip.core.datafile

import dev.tablegrip.core.TableGripPaths
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A table of the working copy: its columns as the file holds them (the row key left out), and a digest of its content. */
internal class TableState(val name: String, val columns: List<OutputColumn>, val fingerprint: String)

/**
 * The SQLite database a data file is edited in. The explorer, table viewers and consoles use it like any SQLite database
 * (through their own pool); this side loads the file's tables into it and reads them back out, over a connection of its
 * own that [DataFileSync] owns.
 *
 * Every loaded table gets a leading [RowKey] column, `INTEGER PRIMARY KEY`: it's what lets the table viewer edit and
 * delete rows, and it keeps rows in file order. It's never written to the file.
 */
internal class WorkingCopy(val path: Path, private val connection: Connection) : AutoCloseable {
    /** The row key column of each table loaded from the file, by lower-case table name. */
    private val keys = HashMap<String, String>()

    init {
        connection.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
    }

    /** Changes when another connection commits to the database (SQLite's `data_version`). */
    fun dataVersion(): Long = connection.createStatement().use { st -> st.executeQuery("PRAGMA data_version").use { rs -> rs.next(); rs.getLong(1) } }

    /**
     * Replaces [replacing] (tables that came from the file) with [tables], in one transaction: nothing changes when a
     * table fails to load. [onProgress] gets the rows loaded so far.
     */
    fun load(tables: List<SourceTable>, replacing: Collection<String> = emptyList(), onProgress: (Long) -> Unit = {}) {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val existing = tableNames().associateBy { it.lowercase() }
            for (name in replacing + tables.map { it.name }) {
                existing[name.lowercase()]?.let { table -> connection.createStatement().use { it.execute("DROP TABLE IF EXISTS ${quote(table)}") } }
            }
            var loaded = 0L
            val newKeys = HashMap<String, String>()
            for (table in tables) {
                val key = loadTable(table) { loaded++; if (loaded % 10_000 == 0L) onProgress(loaded) }
                newKeys[table.name.lowercase()] = key
            }
            connection.commit()
            replacing.forEach { keys.remove(it.lowercase()) }
            keys.putAll(newKeys)
            onProgress(loaded)
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    /**
     * Loads one table: first as untyped text into a staging table while each column's kind is worked out, then into the
     * typed table. A column is numeric only when every value already reads exactly as it would be written back, so the
     * round trip never changes the file's text.
     */
    private fun loadTable(table: SourceTable, onRow: () -> Unit): String {
        val columns = table.columns
        if (columns.isEmpty()) throw DataFileException("\"${table.name}\" has no columns")
        val staging = "_tablegrip_load_${UUID.randomUUID().toString().replace("-", "")}"
        val stagingColumns = columns.indices.map { "c$it" }
        connection.createStatement().use { it.execute("CREATE TEMP TABLE ${quote(staging)} (${stagingColumns.joinToString { quote(it) }})") }
        val integer = BooleanArray(columns.size) { true }
        val real = BooleanArray(columns.size) { true }
        val placeholders = columns.joinToString { "?" }
        connection.prepareStatement("INSERT INTO temp.${quote(staging)} VALUES ($placeholders)").use { insert ->
            var batched = 0
            for (row in table.rows()) {
                for (i in columns.indices) {
                    val value = row.getOrNull(i)
                    if (value == null) {
                        insert.setNull(i + 1, java.sql.Types.VARCHAR)
                    } else {
                        insert.setString(i + 1, value)
                        if (integer[i] && !DataValues.isInteger(value)) integer[i] = false
                        if (real[i] && !DataValues.isReal(value)) real[i] = false
                    }
                }
                insert.addBatch()
                onRow()
                if (++batched == BatchSize) {
                    insert.executeBatch()
                    batched = 0
                }
            }
            if (batched > 0) insert.executeBatch()
        }
        // An all-empty column stays text: there's nothing to say it's a number.
        val nonEmpty = columns.indices.map { i -> scalarLong("SELECT count(*) FROM temp.${quote(staging)} WHERE ${quote(stagingColumns[i])} IS NOT NULL") > 0 }
        val kinds =
            columns.indices.map { i ->
                when {
                    !nonEmpty[i] -> ColumnKind.Text
                    integer[i] -> ColumnKind.Integer
                    real[i] -> ColumnKind.Real
                    else -> ColumnKind.Text
                }
            }
        val key = generateSequence(RowKey) { if (it == RowKey) "${RowKey}_2" else "${RowKey}_${it.substringAfterLast('_').toInt() + 1}" }
            .first { candidate -> columns.none { it.equals(candidate, ignoreCase = true) } }
        val definitions = listOf("${quote(key)} INTEGER PRIMARY KEY") + columns.mapIndexed { i, name -> "${quote(name)} ${kinds[i].sqlType}" }
        connection.createStatement().use { st ->
            st.execute("CREATE TABLE ${quote(table.name)} (${definitions.joinToString()})")
            st.execute(
                "INSERT INTO ${quote(table.name)} (${columns.joinToString { quote(it) }}) " +
                    "SELECT ${stagingColumns.joinToString { quote(it) }} FROM temp.${quote(staging)} ORDER BY rowid",
            )
            st.execute("DROP TABLE temp.${quote(staging)}")
        }
        return key
    }

    /** The user's tables, in the order they were created. */
    fun tableNames(): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY rowid").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    /** Runs [block] in one read transaction, so every table it reads is from the same moment. */
    fun <T> read(block: () -> T): T {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            return block()
        } finally {
            runCatching { connection.rollback() }
            connection.autoCommit = autoCommit
        }
    }

    /** Every table with its file columns and content digest. Call inside [read]. */
    fun states(): List<TableState> = tableNames().map { name -> state(name) }

    private fun state(name: String): TableState {
        val columns = fileColumns(name)
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) = digest.update(text.toByteArray(Charsets.UTF_8))
        columns.forEach { add("${it.name}\u0000${it.kind}\u0001") }
        add("\u0002")
        for (row in rows(name, columns)) {
            for (value in row) {
                when (value) {
                    null -> add("\u0003")
                    is ByteArray -> {
                        add("b")
                        digest.update(value)
                    }
                    else -> add("${value.javaClass.simpleName.first()}${DataValues.text(value)}")
                }
                add("\u0000")
            }
            add("\u0001")
        }
        return TableState(name, columns, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** [name]'s columns minus the row key TableGrip added, with each one's kind from its declared type. */
    private fun fileColumns(name: String): List<OutputColumn> {
        val key = keys[name.lowercase()]
        return connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(${quote(name)})").use { rs ->
                buildList {
                    while (rs.next()) {
                        val column = rs.getString("name")
                        val type = rs.getString("type").orEmpty().uppercase()
                        val primaryKey = rs.getInt("pk") > 0
                        if (primaryKey && type == "INTEGER" && (column == key || column.matches(RowKeyPattern))) continue
                        add(OutputColumn(column, kindOf(type)))
                    }
                }
            }
        }
    }

    /** The rows of [table], in file order, streamed while the sequence is read. Call inside [read]. */
    fun rows(table: String, columns: List<OutputColumn>): Sequence<List<Any?>> =
        sequence {
            val select = "SELECT ${columns.joinToString { quote(it.name) }} FROM ${quote(table)}"
            val statement = connection.createStatement()
            try {
                // Tables created WITHOUT ROWID have no rowid to order by; they come back in key order.
                val rs = try { statement.executeQuery("$select ORDER BY rowid") } catch (_: SQLException) { statement.executeQuery(select) }
                rs.use {
                    while (rs.next()) yield(columns.indices.map { rs.getObject(it + 1) })
                }
            } finally {
                statement.close()
            }
        }

    private fun scalarLong(sql: String): Long = connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    override fun close() {
        runCatching { connection.close() }
    }

    companion object {
        /** The row key column; `_row_2`, `_row_3`… when the file has a column of that name. */
        const val RowKey = "_row"
        private val RowKeyPattern = Regex("_row(_\\d+)?")
        private const val BatchSize = 1_000

        /** Deletes a working copy and its WAL files; call once every connection to it is closed. */
        fun delete(path: Path) {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix -> runCatching { path.resolveSibling(path.name + suffix).deleteIfExists() } }
        }

        fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""

        fun kindOf(declaredType: String): ColumnKind =
            when {
                "INT" in declaredType -> ColumnKind.Integer
                listOf("REAL", "FLOA", "DOUB", "NUM", "DEC").any { it in declaredType } -> ColumnKind.Real
                else -> ColumnKind.Text
            }

        /**
         * A fresh path for [id]'s working copy. Its name starts with this process's id, so copies left behind by a crash are
         * recognised (their process is gone) and deleted here.
         */
        fun newPath(id: String): Path {
            val directory = TableGripPaths.workingCopies
            Files.createDirectories(directory)
            cleanUp(directory)
            val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            return directory.resolve("${ProcessHandle.current().pid()}-$safeId-${UUID.randomUUID().toString().take(8)}.sqlite")
        }

        private fun cleanUp(directory: Path) {
            val entries = runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
            for (entry in entries) {
                val pid = entry.name.substringBefore('-').toLongOrNull() ?: continue
                if (pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).isEmpty) runCatching { entry.deleteIfExists() }
            }
        }
    }
}

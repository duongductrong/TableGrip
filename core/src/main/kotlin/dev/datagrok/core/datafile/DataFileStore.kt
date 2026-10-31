package dev.datagrok.core.datafile

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DataFileOptions
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.ddl.DdlScript
import dev.datagrok.core.driver.Database
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.RelationalConnection
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.edit.ChangeStatement
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.TableRef
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A data file open as a [RelationalConnection]. Everything reads and runs on the [WorkingCopy] through an ordinary SQLite
 * [Database], so tables, SQL consoles, filters, editing and imports work as on any database; writes that the app makes
 * through this interface are saved to the file as soon as they commit, and [sync] catches the rest (consoles).
 */
class DataFileConnection internal constructor(
    override val config: ConnectionConfig,
    private val database: Database,
    /** The file's side: its state, saving, reloading. */
    val sync: DataFileSync,
    private val copy: WorkingCopy,
) : RelationalConnection by database {
    override suspend fun applyChanges(statements: List<ChangeStatement>): Int = database.applyChanges(statements).also { sync.save() }

    override suspend fun executeScript(script: DdlScript, onProgress: (Int) -> Unit): Int = database.executeScript(script, onProgress).also { sync.save() }

    override suspend fun <T> insertRows(
        table: TableRef,
        columns: List<ColumnInfo>,
        rows: Sequence<T>,
        values: (T) -> List<String?>,
        describe: (index: Long, row: T) -> String,
        onProgress: (Long) -> Unit,
    ): Long = database.insertRows(table, columns, rows, values, describe, onProgress).also { sync.save() }

    /** The tables' names in the file's own order (a workbook's sheets as its tabs list them), then those added since. */
    suspend fun tableOrder(): List<String> =
        openSession().use { session ->
            val result = session.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY rowid")
            (result as? QueryResult.Rows)?.rows?.mapNotNull { it.firstOrNull() }.orEmpty()
        }

    override suspend fun backupInto(target: Path): Unit = throw UnsupportedOperationException("${config.type.displayName} is a file of its own; copy it to back it up")

    override fun close() {
        try {
            // Saves what consoles committed since the last tick, before the working copy goes.
            sync.close()
        } finally {
            database.close()
            WorkingCopy.delete(copy.path)
        }
    }
}

/** Opens data files as connections: reads the file into a fresh working copy, then serves it like a SQLite database. */
object DataFileStore {
    /**
     * Reads [config]'s file into a working copy and connects to it. Throws [DataFileException] when the file can't be
     * read, and [dev.datagrok.core.drivers.DriverNotInstalledException] when the SQLite driver isn't on this machine yet.
     */
    suspend fun connect(config: ConnectionConfig, drivers: DriverLibrary = DriverLibrary.shared): DataFileConnection =
        withContext(Dispatchers.IO) {
            val format = DataFileFormats.of(config.type)
            val file = fileOf(config)
            val options = config.dataFile ?: DataFileOptions()
            val path = WorkingCopy.newPath(config.id)
            // Fails fast, before reading the file, when the SQLite driver still has to be downloaded.
            val driver = drivers.load(workingCopyConfig(config, path, readOnly = false))
            // The stamp is taken before reading, so a change made while the file is read is noticed afterwards.
            val stamp = FileStamp.of(file)
            val document = format.open(file, options)
            var copy: WorkingCopy? = null
            var database: Database? = null
            try {
                val readOnlyReason = document.readOnlyReason ?: if (config.readOnly) "The connection is read-only." else null
                val inner = workingCopyConfig(config, path, readOnly = readOnlyReason != null)
                val connection =
                    driver.connect("jdbc:sqlite:$path", Properties().apply { putAll(WorkingCopyProperties) })
                        ?: throw DataFileException("The SQLite driver didn't open the working copy")
                copy = WorkingCopy(path, connection)
                copy.load(document.tables)
                val sync = DataFileSync(file, format, options, copy, document, readOnlyReason, stamp)
                sync.initialize()
                database = Database.connect(inner, password = null, drivers = drivers)
                sync.start()
                DataFileConnection(config, database, sync, copy)
            } catch (e: Throwable) {
                document.close()
                database?.close()
                copy?.close()
                WorkingCopy.delete(path)
                throw e
            }
        }

    /** Reads the file's tables without loading them, e.g. for "Test Connection"; describes what it found. */
    suspend fun test(config: ConnectionConfig): String =
        withContext(Dispatchers.IO) {
            val file = fileOf(config)
            DataFileFormats.of(config.type).open(file, config.dataFile ?: DataFileOptions()).use { document ->
                val tables = document.tables
                val summary =
                    when (tables.size) {
                        0 -> "no tables yet"
                        1 -> "${tables[0].columns.size} columns"
                        else -> "${tables.size} tables: ${tables.joinToString { it.name }}"
                    }
                val readOnly = document.readOnlyReason?.let { " Opens read-only: $it" }.orEmpty()
                "${file.name} (${config.type.displayName}), $summary.$readOnly"
            }
        }

    /** The profile's file; it must exist. */
    fun fileOf(config: ConnectionConfig): Path {
        if (config.database.isBlank()) throw DataFileException("Choose the ${config.type.displayName} to open.")
        val file = pathOf(config.database)
        if (!file.isRegularFile()) throw DataFileException("$file doesn't exist or isn't a file.")
        return file
    }

    /** A profile's file path as typed, with `~` expanded, absolute and normalized. */
    fun pathOf(database: String): Path {
        val raw = database.trim()
        val expanded = if (raw.startsWith("~/")) System.getProperty("user.home") + raw.substring(1) else raw
        return Path.of(expanded).toAbsolutePath().normalize()
    }

    /** A profile for a dropped or chosen file: its format from the extension, named after it. Null if no format claims it. */
    fun profileFor(file: Path, id: String, group: String = ""): ConnectionConfig? {
        val format = DataFileFormats.detect(file) ?: return null
        return ConnectionConfig(id = id, name = file.name, type = format.type, host = "", database = file.toAbsolutePath().toString(), environment = Environment.Local, group = group)
    }

    /** What the SQLite [Database] over the working copy is opened with. */
    private fun workingCopyConfig(config: ConnectionConfig, path: Path, readOnly: Boolean) =
        ConnectionConfig(
            id = "${config.id}#working-copy",
            name = config.name,
            type = DatabaseType.SQLite,
            host = "",
            database = path.toString(),
            driver = DriverId.SQLite,
            readOnly = readOnly,
            properties = WorkingCopyProperties,
        )

    /** WAL lets the sync read a consistent snapshot while a console writes; the timeout rides out each other's locks. */
    private val WorkingCopyProperties = mapOf("journal_mode" to "WAL", "busy_timeout" to "10000")
}

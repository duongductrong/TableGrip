package dev.datagrok.app.schema

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.ddl.DdlGenerator
import dev.datagrok.core.ddl.DdlScript
import dev.datagrok.core.ddl.DdlStatement
import dev.datagrok.core.ddl.DdlPhase
import dev.datagrok.core.ddl.DesignColumn
import dev.datagrok.core.ddl.TableChange
import dev.datagrok.core.ddl.TableDesign
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.filter.ValueKind
import dev.datagrok.core.importer.ImportException
import dev.datagrok.core.importer.ImportFormat
import dev.datagrok.core.importer.ImportOptions
import dev.datagrok.core.importer.ImportPreview
import dev.datagrok.core.importer.ImportReader
import dev.datagrok.core.importer.ImportRow
import dev.datagrok.core.importer.InferredType
import dev.datagrok.core.metadata.ColumnInfo
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportStep { Source, Target, Running, Done }

/** A source column going into a new table: whether it's kept, and the column it becomes. */
class NewColumnPlan(val source: Int, val sourceName: String, inferred: InferredType, type: String) {
    var include by mutableStateOf(true)
    val name = TextFieldState(cleanName(sourceName))
    val type = TextFieldState(type)
    val inferred by mutableStateOf(inferred)
}

/** An existing table's column and the source column (or none) that fills it. */
class ExistingColumnPlan(val column: ColumnInfo, source: Int?) {
    var source by mutableStateOf(source)
}

/**
 * Imports a CSV, Excel or JSON file into a new table of [namespace], or into [existing]. The rows are read twice: a
 * preview to set things up, then the whole file streamed into batched inserts in one transaction.
 */
class ImportWizard(private val app: AppState, val config: ConnectionConfig, val namespace: Namespace, val existing: TableRef?) {
    val dialect: SqlDialect = SqlDialect.of(config.type)
    var step by mutableStateOf(ImportStep.Source)
        private set

    var file by mutableStateOf<File?>(null)
        private set
    var options by mutableStateOf<ImportOptions?>(null)
        private set
    var preview by mutableStateOf<ImportPreview?>(null)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set
    var loadingPreview by mutableStateOf(false)
        private set

    /** New table or existing one; starts on [existing] when the import came from a table. */
    var intoExisting by mutableStateOf(existing != null)
    var targetTable by mutableStateOf(existing)
    val newTableName = TextFieldState()
    val newColumns = mutableStateListOf<NewColumnPlan>()
    val existingColumns = mutableStateListOf<ExistingColumnPlan>()
    var tables by mutableStateOf<List<TableRef>>(emptyList())
        private set
    var targetError by mutableStateOf<String?>(null)
        private set

    var imported by mutableStateOf(0L)
        private set
    var runError by mutableStateOf<String?>(null)
        private set
    /** The table the rows went into once done. */
    var result by mutableStateOf<TableRef?>(null)
        private set
    private var job: Job? = null

    init {
        app.scope.launch {
            runCatching { app.databases.database(config).tables(namespace).filterNot { it.isView }.map { it.ref } }.onSuccess { tables = it }
        }
        existing?.let(::loadExisting)
    }

    // region Source

    fun chooseFile() {
        val chosen = chooseOpenFile("Import Data", ImportFormat.entries.flatMap { it.extensions }.toSet()) ?: return
        open(chosen)
    }

    fun open(chosen: File) {
        val format = ImportFormat.detect(chosen)
        if (format == null) {
            previewError = "${chosen.name}: Datagrok imports CSV/TSV, Excel (.xlsx) and JSON files."
            return
        }
        file = chosen
        if (newTableName.text.isEmpty()) newTableName.setTextAndPlaceCursorAtEnd(cleanName(chosen.nameWithoutExtension))
        update(ImportOptions(format))
    }

    /** Re-reads the preview with changed options, e.g. another delimiter or sheet. */
    fun update(next: ImportOptions) {
        val source = file ?: return
        options = next
        loadingPreview = true
        previewError = null
        app.scope.launch {
            try {
                val read = withContext(Dispatchers.IO) { ImportReader.preview(source, next) }
                preview = read
                // Detection fills in the delimiter, charset and sheet; keep them so the pickers show what was used.
                options = read.options
                planColumns(read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preview = null
                previewError = describe(e)
            } finally {
                loadingPreview = false
            }
        }
    }

    private fun planColumns(read: ImportPreview) {
        newColumns.clear()
        read.columns.forEachIndexed { i, name -> newColumns += NewColumnPlan(i, name, read.types[i], columnType(read.types[i])) }
        if (existingColumns.isNotEmpty()) matchExisting()
    }

    val canContinue: Boolean
        get() = preview != null && !loadingPreview && preview!!.columns.isNotEmpty()

    fun next() {
        if (canContinue) step = ImportStep.Target
    }

    fun back() {
        if (step == ImportStep.Target) step = ImportStep.Source
    }

    // endregion

    // region Target

    fun pickExisting(table: TableRef) {
        targetTable = table
        loadExisting(table)
    }

    private fun loadExisting(table: TableRef) {
        existingColumns.clear()
        app.scope.launch {
            try {
                val details = app.databases.database(config).tableColumns(table)
                existingColumns.clear()
                details.columns.forEach { existingColumns += ExistingColumnPlan(it, null) }
                matchExisting()
                targetError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                targetError = "Couldn't read ${table.name}: ${e.message}"
            }
        }
    }

    /** Pairs table columns with source columns of the same name, ignoring case, spaces and underscores. */
    private fun matchExisting() {
        val sources = preview?.columns.orEmpty().map(::comparable)
        existingColumns.forEach { plan ->
            if (plan.source == null || plan.source!! >= sources.size) plan.source = sources.indexOf(comparable(plan.column.name)).takeIf { it >= 0 }
        }
    }

    /** What blocks the import, or null when it can run. */
    val targetProblem: String?
        get() =
            if (intoExisting) {
                when {
                    targetTable == null -> "Choose the table to import into."
                    existingColumns.none { it.source != null } -> "Match at least one column of ${targetTable!!.name} with a column of the file."
                    else -> existingColumns.firstOrNull { it.source == null && !it.column.nullable && it.column.defaultValue == null && !it.column.autoIncrement }
                        ?.let { "${it.column.name} is NOT NULL without a default: match it with a column of the file." }
                }
            } else {
                val name = newTableName.text.toString().trim()
                val included = newColumns.filter { it.include }
                val names = included.map { it.name.text.toString().trim().lowercase() }
                when {
                    name.isEmpty() -> "Name the new table."
                    tables.any { it.name.equals(name, ignoreCase = true) } -> "A table named $name already exists: pick a new name, or import into it."
                    included.isEmpty() -> "Keep at least one column."
                    names.any { it.isEmpty() } -> "Every kept column needs a name."
                    names.size != names.toSet().size -> "Two columns have the same name."
                    included.any { it.type.text.isBlank() } -> "Every kept column needs a type."
                    else -> null
                }
            }

    /** The CREATE TABLE for a new table, for the preview and to run. */
    fun createScript(): DdlScript? {
        if (intoExisting) return null
        val design =
            TableDesign(
                TableRef(namespace, newTableName.text.toString().trim()),
                newColumns.filter { it.include }.map { DesignColumn(it.name.text.toString().trim(), it.type.text.toString().trim(), nullable = true) },
            )
        if (design.table.name.isEmpty() || design.columns.isEmpty()) return null
        return runCatching { DdlGenerator(dialect).script(TableChange(null, design)) }.getOrNull()
    }

    // endregion

    // region Running

    fun start() {
        if (targetProblem != null || config.readOnly) return
        val source = file ?: return
        val opts = options ?: return
        step = ImportStep.Running
        imported = 0
        runError = null
        job =
            app.scope.launch {
                val database = app.databases.database(config)
                var created: TableRef? = null
                try {
                    val (table, columns, indices) =
                        if (intoExisting) {
                            val mapped = existingColumns.filter { it.source != null }
                            Triple(targetTable!!, mapped.map { it.column }, mapped.map { it.source!! })
                        } else {
                            val script = createScript() ?: error("Nothing to create")
                            database.executeScript(script)
                            val table = TableRef(namespace, newTableName.text.toString().trim())
                            created = table
                            // The engine's own view of the new columns, with the JDBC types to bind with.
                            val details = database.tableColumns(table)
                            val kept = newColumns.filter { it.include }
                            Triple(table, kept.map { plan -> details.columns.first { it.name.equals(plan.name.text.toString().trim(), ignoreCase = true) } }, kept.map { it.source })
                        }
                    val kinds = columns.map { ValueKind.of(it.typeName, it.jdbcType, it.size) }
                    val count =
                        withContext(Dispatchers.IO) {
                            ImportReader.open(source, opts).use { reader ->
                                database.insertRows(
                                    table,
                                    columns,
                                    reader.rows(),
                                    values = { row -> indices.mapIndexed { i, index -> normalize(row.values.getOrNull(index), kinds[i]) } },
                                    describe = { _, row -> describeRow(opts.format, row) },
                                    onProgress = { imported = it },
                                )
                            }
                        }
                    imported = count
                    result = table
                    step = ImportStep.Done
                    app.status = "Imported %,d rows into ${table.name}".format(count)
                    if (created != null) app.schemaChanged(config, namespace, listOf(table))
                    else app.workspace.tabs.filterIsInstance<TableDataTab>().filter { it.connectionId == config.id && it.table == table && !it.hasUnsavedWork }.forEach { it.reload() }
                } catch (e: Throwable) {
                    // Nothing of a failed or cancelled import stays: the inserts rolled back, and a table made for it goes.
                    created?.let { table ->
                        withContext(NonCancellable) {
                            runCatching { database.executeScript(DdlScript(listOf(DdlStatement("DROP TABLE ${dialect.qualifiedName(table)}", DdlPhase.DropTables)), emptyList(), false)) }
                        }
                    }
                    if (e is CancellationException) {
                        step = ImportStep.Target
                        app.status = "Import cancelled; nothing was imported"
                        throw e
                    }
                    runError = describe(e)
                    step = ImportStep.Target
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    // endregion

    /** The dialect's type for what a file column holds; text is sized where engines want a length. */
    fun columnType(type: InferredType): String {
        val length = type.maxLength
        fun sized(n: Int, max: Int, unbounded: String, name: String) = if (n > max) unbounded else "$name(${listOf(32, 64, 255, 1000, 4000, max).first { it >= n.coerceAtLeast(1) }})"
        return when (dialect) {
            is SqlDialect.PostgreSQLDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer -> if (length > 9) "bigint" else "integer"
                    InferredType.Kind.BigInteger, InferredType.Kind.Decimal -> "numeric"
                    InferredType.Kind.Boolean -> "boolean"
                    InferredType.Kind.Date -> "date"
                    InferredType.Kind.Timestamp -> "timestamp"
                    InferredType.Kind.Json -> "jsonb"
                    InferredType.Kind.Text -> "text"
                }
            is SqlDialect.MySQLDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer -> if (length > 9) "BIGINT" else "INT"
                    InferredType.Kind.BigInteger -> "DECIMAL(65,0)"
                    InferredType.Kind.Decimal -> "DECIMAL(38,10)"
                    InferredType.Kind.Boolean -> "BOOLEAN"
                    InferredType.Kind.Date -> "DATE"
                    InferredType.Kind.Timestamp -> "DATETIME"
                    InferredType.Kind.Json -> "JSON"
                    InferredType.Kind.Text -> sized(length, 16_383, "TEXT", "VARCHAR")
                }
            is SqlDialect.SQLiteDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer, InferredType.Kind.BigInteger -> "INTEGER"
                    InferredType.Kind.Decimal -> "NUMERIC"
                    InferredType.Kind.Boolean -> "BOOLEAN"
                    InferredType.Kind.Date -> "DATE"
                    InferredType.Kind.Timestamp -> "DATETIME"
                    InferredType.Kind.Json, InferredType.Kind.Text -> "TEXT"
                }
            is SqlDialect.SqlServerDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer -> if (length > 9) "bigint" else "int"
                    InferredType.Kind.BigInteger -> "decimal(38,0)"
                    InferredType.Kind.Decimal -> "decimal(38,10)"
                    InferredType.Kind.Boolean -> "bit"
                    InferredType.Kind.Date -> "date"
                    InferredType.Kind.Timestamp -> "datetime2"
                    InferredType.Kind.Json -> "nvarchar(max)"
                    InferredType.Kind.Text -> sized(length, 4000, "nvarchar(max)", "nvarchar")
                }
            is SqlDialect.ClickHouseDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer -> "Int64"
                    InferredType.Kind.BigInteger -> "Int256"
                    InferredType.Kind.Decimal -> "Decimal(38, 10)"
                    InferredType.Kind.Boolean -> "Bool"
                    InferredType.Kind.Date -> "Date"
                    InferredType.Kind.Timestamp -> "DateTime64(3)"
                    InferredType.Kind.Json, InferredType.Kind.Text -> "String"
                }
            is SqlDialect.DuckDBDialect ->
                when (type.kind) {
                    InferredType.Kind.Integer -> if (length > 9) "BIGINT" else "INTEGER"
                    InferredType.Kind.BigInteger -> "HUGEINT"
                    InferredType.Kind.Decimal -> "DOUBLE"
                    InferredType.Kind.Boolean -> "BOOLEAN"
                    InferredType.Kind.Date -> "DATE"
                    InferredType.Kind.Timestamp -> "TIMESTAMP"
                    InferredType.Kind.Json -> "JSON"
                    InferredType.Kind.Text -> "VARCHAR"
                }
            else ->
                when (type.kind) {
                    InferredType.Kind.Integer -> if (length > 9) "BIGINT" else "INTEGER"
                    InferredType.Kind.BigInteger -> "DECIMAL(38,0)"
                    InferredType.Kind.Decimal -> "DECIMAL(38,10)"
                    InferredType.Kind.Boolean -> "BOOLEAN"
                    InferredType.Kind.Date -> "DATE"
                    InferredType.Kind.Timestamp -> "TIMESTAMP"
                    InferredType.Kind.Json, InferredType.Kind.Text -> sized(length, 4000, "CLOB", "VARCHAR")
                }
        }
    }

    /**
     * A file value as the column's engine reads it: yes/no/t/f become what boolean columns take (MySQL's are numbers),
     * and ISO timestamps lose the `T` and `Z` that MySQL and SQL Server reject.
     */
    private fun normalize(value: String?, kind: ValueKind): String? {
        if (value == null) return null
        return when (kind) {
            ValueKind.Boolean -> {
                val truthy = value.trim().lowercase()
                val bool = when (truthy) {
                    "true", "t", "yes", "y", "1" -> true
                    "false", "f", "no", "n", "0" -> false
                    else -> return value
                }
                if (dialect is SqlDialect.PostgreSQLDialect || dialect is SqlDialect.DuckDBDialect || dialect is SqlDialect.ClickHouseDialect) bool.toString() else if (bool) "1" else "0"
            }
            ValueKind.DateTime ->
                if (dialect is SqlDialect.MySQLDialect || dialect is SqlDialect.SqlServerDialect || dialect is SqlDialect.SQLiteDialect) {
                    value.replace('T', ' ').removeSuffix("Z")
                } else {
                    value
                }
            else -> value
        }
    }

    private fun describeRow(format: ImportFormat, row: ImportRow): String =
        when (format) {
            ImportFormat.Csv -> "line ${row.line}"
            ImportFormat.Xlsx -> "row ${row.line} of the sheet"
            ImportFormat.Json -> "record ${row.line}"
        }

    private fun describe(e: Throwable): String =
        when (e) {
            is ImportException -> e.message ?: "The file couldn't be read."
            else -> e.message ?: e.toString()
        }

    val formatLabel: String
        get() = options?.format?.label.orEmpty()

    fun setDelimiter(delimiter: Char) = options?.let { update(it.copy(delimiter = delimiter)) }

    fun setHeader(header: Boolean) = options?.let { update(it.copy(hasHeader = header)) }

    fun setSheet(sheet: String) = options?.let { update(it.copy(sheet = sheet)) }

    fun setCharset(charset: Charset) = options?.let { update(it.copy(charset = charset)) }
}

/** A column name engines take unquoted: lower snake case, letters, digits and underscores. */
internal fun cleanName(name: String): String {
    val snake =
        name.trim()
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
            .trim('_')
    return when {
        snake.isEmpty() -> "column"
        snake.first().isDigit() -> "c_$snake"
        else -> snake
    }
}

private fun comparable(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

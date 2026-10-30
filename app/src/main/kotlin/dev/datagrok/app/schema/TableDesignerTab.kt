package dev.datagrok.app.schema

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.ConnectionStatus
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.ddl.ColumnTypes
import dev.datagrok.core.ddl.DdlGenerator
import dev.datagrok.core.ddl.DdlScript
import dev.datagrok.core.ddl.DesignColumn
import dev.datagrok.core.ddl.TableChange
import dev.datagrok.core.ddl.TableDesign
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.metadata.ForeignKeyInfo
import dev.datagrok.core.metadata.IndexInfo
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class DesignerSection(val label: String) { Columns("Columns"), Indexes("Indexes"), ForeignKeys("Foreign Keys") }

/** Referential actions offered for foreign keys; `NO ACTION` is stored as null, like the metadata reports it. */
internal val ReferentialActions = listOf("NO ACTION", "CASCADE", "SET NULL", "SET DEFAULT", "RESTRICT")

/**
 * A column row of the designer. Indexes and foreign keys refer to columns by [id], so renaming a column renames it
 * everywhere it's used.
 */
class DesignerColumn(val id: Long, val original: DesignColumn?, primaryKey: Boolean) {
    val name = TextFieldState(original?.name.orEmpty())
    val type = TextFieldState(original?.type.orEmpty())
    val defaultValue = TextFieldState(original?.defaultValue.orEmpty())
    var nullable by mutableStateOf(original?.nullable ?: true)
    var autoIncrement by mutableStateOf(original?.autoIncrement ?: false)
    var primaryKey by mutableStateOf(primaryKey)
    /** Existing columns are marked for dropping rather than removed, so the change stays visible and undoable. */
    var dropped by mutableStateOf(false)

    val isNew: Boolean
        get() = original == null

    fun toDesign(): DesignColumn =
        DesignColumn(
            name = name.text.toString().trim(),
            type = type.text.toString().trim(),
            nullable = nullable && !primaryKey,
            defaultValue = defaultValue.text.toString().trim().ifEmpty { null },
            autoIncrement = autoIncrement,
            originalName = original?.name,
        )

    val isModified: Boolean
        get() {
            val original = original ?: return false
            val now = toDesign()
            return now.name != original.name || ColumnTypes.normalize(now.type) != ColumnTypes.normalize(original.type) || now.nullable != original.nullable ||
                ColumnTypes.normalizeDefault(now.defaultValue) != ColumnTypes.normalizeDefault(original.defaultValue) || now.autoIncrement != original.autoIncrement
        }
}

class DesignerIndex(val id: Long, val original: IndexInfo?, columns: List<Long>) {
    val name = TextFieldState(original?.name.orEmpty())
    var unique by mutableStateOf(original?.unique ?: false)
    val columns = mutableStateListOf<Long>().apply { addAll(columns) }
    var dropped by mutableStateOf(false)
}

class DesignerForeignKey(val id: Long, val original: ForeignKeyInfo?, columns: List<Long>) {
    val name = TextFieldState(original?.name.orEmpty())
    val columns = mutableStateListOf<Long>().apply { addAll(columns) }
    var referencedTable by mutableStateOf(original?.referencedTable)
    val referencedColumns = mutableStateListOf<String>().apply { original?.referencedColumns?.let(::addAll) }
    var onUpdate by mutableStateOf(original?.onUpdate ?: "NO ACTION")
    var onDelete by mutableStateOf(original?.onDelete ?: "NO ACTION")
    var dropped by mutableStateOf(false)
}

/** A problem that blocks applying, shown on its [section]'s row [rowId] (null: the table as a whole). */
data class DesignIssue(val section: DesignerSection?, val rowId: Long?, val message: String)

/**
 * Designs a new table in [namespace], or changes [existing]. Edits stay in the tab until applied; the DDL they amount to
 * is shown as they're made.
 */
class TableDesignerTab(private val app: AppState, val config: ConnectionConfig, val namespace: Namespace, existing: TableRef?) : WorkspaceTab {
    override val connectionId: String = config.id
    val dialect: SqlDialect = SqlDialect.of(config.type)
    private val generator = DdlGenerator(dialect)

    /** The table as read from the database; null while designing a new one. */
    var original by mutableStateOf<TableDesign?>(null)
        private set
    /** The table this tab edits once it exists: [existing], or the new table after it's created. */
    var tableRef by mutableStateOf(existing)
        private set

    override val title: String
        get() = tableRef?.let { "Design: ${it.name}" } ?: "New Table"

    val tableName = TextFieldState(existing?.name ?: "")
    val columns = mutableStateListOf<DesignerColumn>()
    val indexes = mutableStateListOf<DesignerIndex>()
    val foreignKeys = mutableStateListOf<DesignerForeignKey>()
    var section by mutableStateOf(DesignerSection.Columns)

    var loading by mutableStateOf(existing != null)
        private set
    var error by mutableStateOf<String?>(null)
    val connectionStatus: ConnectionStatus
        get() = app.databases.status(config.id)

    /** Tables of the namespace, for foreign keys' targets. */
    var tables by mutableStateOf<List<TableRef>>(emptyList())
        private set
    private val referencedColumnCache = mutableStateMapOf<TableRef, List<String>>()

    internal var pendingRun by mutableStateOf<ScriptRun?>(null)
    /** Closing with unapplied changes waits for the user's answer here. */
    var pendingClose by mutableStateOf<(() -> Unit)?>(null)
    /** A row to focus once composed, e.g. a column just added. */
    var focusRow by mutableStateOf<Long?>(null)

    private var nextId = 0L

    val editorFontSize: Int
        get() = app.settings.editorFontSize
    val commonTypes: List<String> = ColumnTypes.commonTypes(dialect)

    init {
        if (existing == null) {
            startNew()
        } else {
            load()
        }
        loadTables()
    }

    private fun startNew() {
        val id = DesignerColumn(nextId++, null, primaryKey = true).apply {
            name.setTextAndPlaceCursorAtEnd("id")
            type.setTextAndPlaceCursorAtEnd(defaultIdType())
            nullable = false
            autoIncrement = dialect !is SqlDialect.DuckDBDialect && dialect !is SqlDialect.ClickHouseDialect
        }
        columns += id
        loading = false
    }

    private fun defaultIdType(): String =
        when (dialect) {
            is SqlDialect.PostgreSQLDialect, is SqlDialect.SqlServerDialect -> "bigint"
            is SqlDialect.MySQLDialect -> "BIGINT"
            is SqlDialect.ClickHouseDialect -> "UInt64"
            else -> "INTEGER"
        }

    /** Reads the table and replaces every edit with what the database has. */
    fun load() {
        val ref = tableRef ?: return
        loading = true
        app.scope.launch {
            try {
                val structure = app.databases.database(config).tableStructure(ref)
                val design = TableDesign.from(structure.details, dialect, structure.constraints, structure.triggers)
                reset(design)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't read ${ref.name}: ${e.message ?: e}"
            } finally {
                loading = false
            }
        }
    }

    private fun loadTables() {
        app.scope.launch {
            runCatching { app.databases.database(config).tables(namespace).filterNot { it.isView }.map { it.ref } }.onSuccess { tables = it }
        }
    }

    private fun reset(design: TableDesign) {
        original = design
        tableName.setTextAndPlaceCursorAtEnd(design.table.name)
        columns.clear()
        design.columns.forEach { columns += DesignerColumn(nextId++, it, it.name in design.primaryKey) }
        fun ids(names: List<String>) = names.mapNotNull { name -> columns.firstOrNull { it.original?.name == name }?.id }
        indexes.clear()
        design.indexes.forEach { indexes += DesignerIndex(nextId++, it, ids(it.columns)) }
        foreignKeys.clear()
        design.foreignKeys.forEach { foreignKeys += DesignerForeignKey(nextId++, it, ids(it.columns)) }
    }

    /** Puts every edit back to what was read. */
    fun revert() {
        original?.let(::reset) ?: run {
            columns.clear()
            indexes.clear()
            foreignKeys.clear()
            startNew()
        }
        error = null
    }

    // region Editing

    fun addColumn(): DesignerColumn {
        val column = DesignerColumn(nextId++, null, primaryKey = false).apply {
            name.setTextAndPlaceCursorAtEnd(uniqueName("new_column", columns.map { it.name.text.toString() }))
            type.setTextAndPlaceCursorAtEnd(commonTypes.firstOrNull { it.lowercase().startsWith("varchar") } ?: commonTypes.first())
        }
        columns += column
        section = DesignerSection.Columns
        focusRow = column.id
        return column
    }

    /** New columns go away; existing ones are marked for dropping (and come back with [restoreColumn]). */
    fun removeColumn(column: DesignerColumn) {
        if (column.isNew) {
            columns.remove(column)
            indexes.forEach { it.columns.remove(column.id) }
            foreignKeys.forEach { it.columns.remove(column.id) }
        } else {
            column.dropped = true
            column.primaryKey = false
        }
    }

    fun restoreColumn(column: DesignerColumn) {
        column.dropped = false
        column.primaryKey = original?.primaryKey?.contains(column.original?.name) == true
    }

    fun moveColumn(column: DesignerColumn, by: Int) {
        val from = columns.indexOf(column)
        val to = (from + by).coerceIn(0, columns.lastIndex)
        if (from != to) columns.add(to, columns.removeAt(from))
    }

    fun addIndex() {
        val tableName = tableName.text.toString().ifBlank { "table" }
        indexes += DesignerIndex(nextId++, null, emptyList()).apply { name.setTextAndPlaceCursorAtEnd(uniqueName("${tableName}_idx", indexes.map { it.name.text.toString() })) }
        section = DesignerSection.Indexes
        focusRow = indexes.last().id
    }

    fun removeIndex(index: DesignerIndex) {
        if (index.original == null) indexes.remove(index) else index.dropped = !index.dropped
    }

    fun addForeignKey() {
        val tableName = tableName.text.toString().ifBlank { "table" }
        foreignKeys += DesignerForeignKey(nextId++, null, emptyList()).apply { name.setTextAndPlaceCursorAtEnd(uniqueName("${tableName}_fk", foreignKeys.map { it.name.text.toString() })) }
        section = DesignerSection.ForeignKeys
        focusRow = foreignKeys.last().id
    }

    fun removeForeignKey(fk: DesignerForeignKey) {
        if (fk.original == null) foreignKeys.remove(fk) else fk.dropped = !fk.dropped
    }

    /** Points [fk] at [table], guessing its columns: the referenced table's primary key, else `id`. */
    fun setReferencedTable(fk: DesignerForeignKey, table: TableRef) {
        fk.referencedTable = table
        fk.referencedColumns.clear()
        app.scope.launch {
            runCatching { app.databases.database(config).tableColumns(table) }.onSuccess { details ->
                referencedColumnCache[table] = details.columns.map { it.name }
                val key = details.primaryKey.ifEmpty { details.columns.filter { it.name.equals("id", ignoreCase = true) }.map { it.name } }
                if (fk.referencedTable == table && fk.referencedColumns.isEmpty()) fk.referencedColumns.addAll(key.take(fk.columns.size.coerceAtLeast(1)))
            }
        }
    }

    /** Columns of [table] for a foreign key's target, loading them the first time. */
    fun referencedColumns(table: TableRef): List<String> {
        referencedColumnCache[table]?.let { return it }
        app.scope.launch {
            runCatching { app.databases.database(config).tableColumns(table) }.onSuccess { referencedColumnCache[table] = it.columns.map { c -> c.name } }
        }
        return emptyList()
    }

    fun columnName(id: Long): String = columns.firstOrNull { it.id == id }?.name?.text?.toString().orEmpty()

    // endregion

    // region The design and its DDL

    /** The table as currently designed; reads the edit state, so it recomposes as the user types. */
    fun design(): TableDesign {
        val name = tableName.text.toString().trim()
        val live = columns.filterNot { it.dropped }
        val liveIds = live.map { it.id }.toSet()
        fun names(ids: List<Long>) = ids.filter { it in liveIds }.map(::columnName)
        val base = original
        return TableDesign(
            table = TableRef(namespace, name),
            columns = live.map { it.toDesign() },
            primaryKey = live.filter { it.primaryKey }.map { it.name.text.toString().trim() },
            indexes = indexes.filterNot { it.dropped }.map { IndexInfo(it.name.text.toString().trim(), names(it.columns), it.unique) },
            foreignKeys =
                foreignKeys.filterNot { it.dropped }.mapNotNull { fk ->
                    val target = fk.referencedTable ?: return@mapNotNull null
                    ForeignKeyInfo(
                        fk.name.text.toString().trim(),
                        names(fk.columns),
                        target,
                        fk.referencedColumns.toList(),
                        onUpdate = fk.onUpdate.takeUnless { it == "NO ACTION" },
                        onDelete = fk.onDelete.takeUnless { it == "NO ACTION" },
                    )
                },
            primaryKeyName = base?.primaryKeyName,
            uniqueConstraints = base?.uniqueConstraints.orEmpty().filter { unique -> unique.columns.all { name -> live.any { it.original?.name == name } } }
                .map { unique -> unique.copy(columns = unique.columns.map { name -> live.first { it.original?.name == name }.name.text.toString().trim() }) },
            checks = base?.checks.orEmpty(),
            triggers = base?.triggers.orEmpty(),
        )
    }

    /** What would stop the design from applying, row by row. */
    fun issues(): List<DesignIssue> = buildList {
        if (tableName.text.isBlank()) add(DesignIssue(null, null, "The table needs a name."))
        else if (original == null || tableName.text.toString().trim() != original?.table?.name) {
            if (tables.any { it.name.equals(tableName.text.toString().trim(), ignoreCase = true) && it != tableRef }) add(DesignIssue(null, null, "A table named ${tableName.text.trim()} already exists."))
        }
        val live = columns.filterNot { it.dropped }
        if (live.isEmpty()) add(DesignIssue(DesignerSection.Columns, null, "The table needs at least one column."))
        val seen = mutableSetOf<String>()
        for (column in live) {
            val name = column.name.text.toString().trim()
            when {
                name.isEmpty() -> add(DesignIssue(DesignerSection.Columns, column.id, "Column needs a name."))
                !seen.add(name.lowercase()) -> add(DesignIssue(DesignerSection.Columns, column.id, "Another column is already named $name."))
            }
            if (column.type.text.isBlank()) add(DesignIssue(DesignerSection.Columns, column.id, "${name.ifEmpty { "Column" }} needs a type."))
            if (column.autoIncrement && dialect is SqlDialect.SQLiteDialect && (!column.primaryKey || live.count { it.primaryKey } > 1)) {
                add(DesignIssue(DesignerSection.Columns, column.id, "SQLite only auto-increments a single-column INTEGER primary key."))
            }
        }
        val indexNames = mutableSetOf<String>()
        for (index in indexes.filterNot { it.dropped }) {
            val name = index.name.text.toString().trim()
            when {
                name.isEmpty() -> add(DesignIssue(DesignerSection.Indexes, index.id, "Index needs a name."))
                !indexNames.add(name.lowercase()) -> add(DesignIssue(DesignerSection.Indexes, index.id, "Another index is already named $name."))
            }
            if (index.columns.none { id -> live.any { it.id == id } }) add(DesignIssue(DesignerSection.Indexes, index.id, "${name.ifEmpty { "Index" }} needs at least one column."))
        }
        for (fk in foreignKeys.filterNot { it.dropped }) {
            val name = fk.name.text.toString().trim().ifEmpty { "Foreign key" }
            // SQLite's foreign keys have no names; everyone else needs one to drop it later.
            if (fk.name.text.isBlank() && dialect !is SqlDialect.SQLiteDialect) add(DesignIssue(DesignerSection.ForeignKeys, fk.id, "Foreign key needs a name."))
            if (fk.columns.none { id -> live.any { it.id == id } }) add(DesignIssue(DesignerSection.ForeignKeys, fk.id, "$name needs at least one column."))
            if (fk.referencedTable == null) add(DesignIssue(DesignerSection.ForeignKeys, fk.id, "$name needs a table to reference."))
            else if (fk.referencedColumns.size != fk.columns.size) add(DesignIssue(DesignerSection.ForeignKeys, fk.id, "$name: pick as many referenced columns as columns."))
        }
    }

    /** The DDL for the edits so far; empty when nothing changed. */
    fun script(): DdlScript {
        val design = design()
        if (design.columns.isEmpty()) return DdlScript(emptyList(), emptyList(), generator.transactionalDdl)
        return runCatching { generator.script(TableChange(original, design)) }.getOrElse { DdlScript(emptyList(), listOf(it.message ?: it.toString()), generator.transactionalDdl) }
    }

    val hasChanges: Boolean
        get() = original == null || !script().isEmpty

    override val hasUnsavedWork: Boolean
        get() = !loading && (if (original == null) columns.size > 1 || tableName.text.isNotBlank() else !script().isEmpty)

    override fun confirmClose(close: () -> Unit) {
        pendingClose = close
    }

    /** Asks to apply the edits; the dialog runs them. */
    fun requestApply() {
        if (issues().isNotEmpty()) return
        val script = script()
        if (script.isEmpty) return
        val design = design()
        val creating = original == null
        pendingRun =
            ScriptRun(
                title = if (creating) "Create Table ${design.table.name}" else "Apply Changes to ${original!!.table.name}",
                config = config,
                script = script,
                runLabel = if (creating) "Create" else "Apply",
            ) {
                val before = original?.table
                tableRef = design.table
                app.status = if (creating) "Created ${design.table.name}" else "Applied ${script.statements.size} change(s) to ${design.table.name}"
                app.schemaChanged(config, namespace, listOfNotNull(before, design.table))
                load()
                loadTables()
            }
    }

    // endregion

    override fun dispose() = Unit

    private fun uniqueName(base: String, taken: List<String>): String {
        val names = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        return generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it.lowercase() !in names }
    }
}

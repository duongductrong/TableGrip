package dev.datagrok.app.table

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.export.ExportRequest
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.edit.ChangeSql
import dev.datagrok.core.filter.CompiledFilter
import dev.datagrok.core.filter.FilterColumn
import dev.datagrok.core.filter.FilterCompiler
import dev.datagrok.core.filter.FilterGroup
import dev.datagrok.core.filter.SqlFilter
import dev.datagrok.core.filter.ValueKind
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableRef
import dev.datagrok.core.metadata.TableStructure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class TableView { Data, Structure }

/** The pickers of a builder condition that a shortcut can open. */
enum class FilterPicker { Column, Operator }

enum class StructureSection(val label: String) {
    Columns("Columns"),
    Indexes("Indexes"),
    ForeignKeys("Foreign Keys"),
    Constraints("Constraints"),
    Triggers("Triggers"),
    Ddl("DDL"),
}

/** Paged, sortable, filterable and editable view of one table's rows, and its structure. */
class TableDataTab(private val app: AppState, val config: ConnectionConfig, val table: TableRef) : WorkspaceTab {
    override val connectionId: String = config.id
    override val title: String = table.name
    val dialect: SqlDialect = SqlDialect.of(config.type)

    val grid = DataGridState()

    /** Columns, operators and real values for the WHERE filter. */
    val filterCompletion by lazy { app.schemaCache(config).let { filterCompletion(dialect, table, it, it) } }

    val schemaVersion: Int
        get() = app.schemaCache(config).version

    /** Whether the filter bar shows the condition builder or a SQL WHERE field; remembered across tabs. */
    var filterMode by mutableStateOf(runCatching { FilterMode.valueOf(app.settings.filterMode) }.getOrDefault(FilterMode.Builder))
        private set
    /** Structured conditions, applied on Enter or Apply. */
    val builder = FilterBuilderState()
    /** Raw SQL condition typed by the user, applied on Enter. */
    val filter = TextFieldState()

    /**
     * The filter the rows reflect: the builder's tree as applied, or applied SQL. The tree is compiled on every load, so
     * relative dates like `today` stay current on reload.
     */
    var appliedTree by mutableStateOf<FilterGroup?>(null)
        private set
    var appliedSql by mutableStateOf<String?>(null)
        private set
    /** A builder row to focus once it's composed, e.g. a condition just added. */
    var focusFilterNode by mutableStateOf<Long?>(null)
    /** Set to move focus into the SQL filter field once it's composed. */
    var focusSqlFilter by mutableStateOf(false)
    /** A builder row's picker to open, from its shortcut. */
    var openFilterPicker by mutableStateOf<Pair<Long, FilterPicker>?>(null)
    /** Problems are shown once the user tries to apply; until then only values already typed are checked. */
    var showFilterIssues by mutableStateOf(false)
        private set

    val hasAppliedFilter: Boolean
        get() = appliedSql != null || appliedTree?.children?.isNotEmpty() == true

    /** Columns the builder offers, typed from the table's metadata (or the loaded rows when that's unavailable). */
    val filterColumns: List<FilterColumn>
        get() =
            details?.columns?.map { FilterColumn(it.name, it.typeName, ValueKind.of(it.typeName, it.jdbcType, it.size)) }
                ?: model?.columns?.map { FilterColumn(it.name, it.typeName, ValueKind.of(it.typeName, it.jdbcType)) }
                ?: emptyList()

    /** The builder's current conditions, compiled; read from composition, so the bar stays in sync while typing. */
    fun compileDraft(): CompiledFilter = FilterCompiler(dialect, filterColumns).compile(builder.toNode())

    /** The bar's content differs from what the rows show. */
    val isFilterDirty: Boolean
        get() =
            when (filterMode) {
                FilterMode.Builder -> builder.toNode() != (appliedTree ?: FilterGroup(builder.root.id)) || appliedSql != null
                FilterMode.Sql -> {
                    val text = filter.text.toString().trim().ifEmpty { null }
                    // SQL carried over from the builder shows the same rows as the builder's applied conditions.
                    text != appliedSql && (appliedSql != null || text != appliedTree?.let { FilterCompiler(dialect, filterColumns).compile(it).filter?.preview })
                }
            }
    var sort by mutableStateOf<List<SortColumn>>(emptyList())
        private set
    var page by mutableStateOf(0)
        private set
    val pageSize: Int
        get() = app.settings.pageSize
    val editorFontSize: Int
        get() = app.settings.editorFontSize

    var model by mutableStateOf<TableEditModel?>(null)
        private set
    var details by mutableStateOf<TableDetails?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set
    var hasNextPage by mutableStateOf(false)
        private set
    var totalRows by mutableStateOf<Long?>(null)
        private set
    var showValuePanel by mutableStateOf(false)

    var view by mutableStateOf(TableView.Data)
        private set
    var section by mutableStateOf(StructureSection.Columns)
    var structure by mutableStateOf<TableStructure?>(null)
        private set
    var structureError by mutableStateOf<String?>(null)
    var structureLoading by mutableStateOf(false)
        private set
    /** One grid per structure section, so each keeps its own selection and column widths. */
    val structureGrids = StructureSection.entries.associateWith { DataGridState() }

    /** Navigation waiting for the user to confirm discarding unsaved edits. */
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null)
    /** SQL of the pending changes while the preview dialog is open. */
    var preview by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
        private set

    val firstRowNumber: Long
        get() = page.toLong() * pageSize + 1

    val hasChanges: Boolean
        get() = model?.hasChanges == true

    override val hasUnsavedWork: Boolean
        get() = hasChanges

    /** Navigation to resume once the pending edits are saved, when the user chose to submit them first. */
    private var afterSubmit: (() -> Unit)? = null

    private var loadJob: Job? = null

    fun reload() = guard { load() }

    /** Reloads whichever view is showing. */
    fun refresh() = if (view == TableView.Structure) loadStructure() else reload()

    /** Rows load the first time the data view is shown, so opening a huge table's structure doesn't query it. */
    fun showData() {
        view = TableView.Data
        if (model == null && !loading) load()
    }

    fun showStructure(section: StructureSection? = null) {
        view = TableView.Structure
        section?.let { this.section = it }
        if (structure == null && !structureLoading) loadStructure()
    }

    fun loadStructure() {
        app.scope.launch {
            structureLoading = true
            try {
                structure = app.databases.database(config).tableStructure(table)
                structureError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                structureError = e.message ?: e.toString()
            } finally {
                structureLoading = false
            }
        }
    }

    fun openDdlInConsole() {
        val ddl = structure?.ddl ?: return
        app.workspace.openConsole(config, ddl)
    }

    private fun load() {
        loadJob?.cancel()
        val job = app.scope.launch {
            loading = true
            try {
                val database = app.databases.database(config)
                // Columns and key load alongside the first page instead of before it, a round trip apart on a remote
                // server; only a builder filter needs the column types before the rows can be queried.
                val columns = if (details == null) async { runCatching { database.tableColumns(table) }.getOrNull() } else null
                if (columns != null && appliedTree != null) details = columns.await()
                val result =
                    database.fetchPage(table, resolveFilter(), sort, pageSize, page.toLong() * pageSize, app.settings.queryTimeoutSeconds)
                columns?.let { details = it.await() }
                hasNextPage = result.truncated
                model = TableEditModel(result, details?.primaryKey.orEmpty(), readOnly = config.readOnly)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                // A load replaced by a newer one leaves the indicator to it.
                if (loadJob == coroutineContext[Job]) loading = false
            }
        }
        loadJob = job
    }

    /** The applied filter as SQL; fails with the builder's first problem if the table changed under it. */
    private fun resolveFilter(): SqlFilter? {
        appliedSql?.let { return SqlFilter.raw(it) }
        val tree = appliedTree ?: return null
        val compiled = FilterCompiler(dialect, filterColumns).compile(tree)
        return compiled.filter ?: compiled.issues.values.firstOrNull()?.let { throw IllegalStateException("Filter: $it") }
    }

    /** Applies the bar's content, unless the builder has conditions it can't run; those get flagged instead. */
    fun applyFilter() {
        if (filterMode == FilterMode.Builder) {
            val compiled = compileDraft()
            if (!compiled.isValid) {
                showFilterIssues = true
                return
            }
            guard {
                showFilterIssues = false
                appliedTree = builder.toNode()
                appliedSql = null
                reloadFiltered()
            }
        } else {
            guard {
                appliedSql = filter.text.toString().trim().ifEmpty { null }
                appliedTree = null
                reloadFiltered()
            }
        }
    }

    fun clearFilter() = guard {
        builder.clear()
        filter.setTextAndPlaceCursorAtEnd("")
        showFilterIssues = false
        appliedTree = null
        appliedSql = null
        reloadFiltered()
    }

    private fun reloadFiltered() {
        page = 0
        totalRows = null
        load()
    }

    /**
     * Switches the bar between builder and SQL without losing work: the SQL field starts from the builder's conditions,
     * and SQL typed there comes back to the builder as a SQL condition.
     */
    fun switchFilterMode(mode: FilterMode) {
        if (mode == filterMode) return
        if (mode == FilterMode.Sql) {
            val sql = compileDraft().filter?.preview
            if (filter.text.isBlank() && sql != null) filter.setTextAndPlaceCursorAtEnd(sql)
        } else {
            val sql = filter.text.toString().trim()
            if (sql.isNotEmpty() && !builder.hasRaw(sql) && sql != compileDraft().filter?.preview) builder.addRaw(builder.root, sql)
        }
        filterMode = mode
        app.updateSettings { it.copy(filterMode = mode.name) }
    }

    /** Adds `column = value` (or `IS NULL`) to the filter, like DataGrip's "Filter by this value"; or `≠` to [exclude] it. */
    fun filterByValue(column: String, value: String?, exclude: Boolean = false) {
        if (filterMode == FilterMode.Builder) {
            val target = filterColumns.firstOrNull { it.name == column } ?: return
            builder.addFromValue(target, value, exclude)
            applyFilter()
            return
        }
        val numeric = model?.columns?.firstOrNull { it.name == column }?.isNumeric == true
        val condition =
            dialect.quoteIdentifier(column) +
                when {
                    value == null -> if (exclude) " IS NOT NULL" else " IS NULL"
                    else -> (if (exclude) " <> " else " = ") + dialect.literal(value, numeric)
                }
        val current = filter.text.toString().trim()
        filter.setTextAndPlaceCursorAtEnd(if (current.isEmpty()) condition else "($current) AND $condition")
        applyFilter()
    }

    /** New conditions start on the column under the grid's cursor, which is usually the one being looked at. */
    fun defaultFilterColumn(): FilterColumn? {
        val columns = filterColumns
        val focused = grid.selection?.let { model?.columns?.getOrNull(grid.modelColumn(it.focus.column))?.name }
        return columns.firstOrNull { it.name == focused } ?: columns.firstOrNull()
    }

    /** Puts the caret in the filter: the first condition, a new one on the grid's current column if there are none, or the SQL field. */
    fun focusFilter() {
        showData()
        if (filterMode == FilterMode.Sql) {
            focusSqlFilter = true
            return
        }
        val first = builder.rows().firstOrNull() ?: builder.addCondition(builder.root, defaultFilterColumn())
        focusFilterNode = first.id
    }

    /** Distinct values of [column] starting with [prefix], for the builder's value suggestions; empty on any failure. */
    suspend fun suggestValues(column: String, prefix: String): List<String> =
        try {
            app.databases.database(config).sampleValues(table, column, prefix, limit = 20)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    fun setSort(column: String, direction: SortDirection?, additive: Boolean) = guard {
        val others = if (additive) sort.filterNot { it.column == column } else emptyList()
        val existing = sort.indexOfFirst { it.column == column }
        sort =
            when {
                direction == null -> sort.filterNot { it.column == column }
                additive && existing >= 0 -> sort.map { if (it.column == column) SortColumn(column, direction) else it }
                else -> others + SortColumn(column, direction)
            }
        page = 0
        load()
    }

    fun goToPage(newPage: Int) {
        if (newPage < 0 || newPage == page) return
        guard {
            page = newPage
            load()
        }
    }

    fun setPageSize(size: Int) = guard {
        app.updateSettings { it.copy(pageSize = size) }
        page = 0
        load()
    }

    /** Counting can be slow on large tables, so it only runs on request. */
    fun countRows() {
        app.scope.launch {
            runCatching { app.databases.database(config).countRows(table, resolveFilter(), app.settings.queryTimeoutSeconds) }
                .onSuccess { totalRows = it }
                .onFailure { error = it.message }
        }
    }

    fun export() {
        val model = model ?: return
        val filter = runCatching { resolveFilter() }.getOrElse { error = it.message; return }
        val order = sort
        app.exportRequest =
            ExportRequest(
                title = table.name,
                fileName = table.name,
                columns = model.columns,
                loadedRows = (0 until model.rowCount).map { row -> model.columns.indices.map { model.value(row, it) } },
                dialect = dialect,
                tableName = dialect.qualifiedName(table),
                streamAll = { writer, progress -> app.databases.database(config).exportTable(table, filter, order, writer, progress) },
            )
    }

    fun openPreview() {
        val model = model ?: return
        preview = ChangeSql.statements(dialect, table, model.toChanges()).joinToString(";\n", postfix = ";") { it.preview(dialect) }
    }

    /** Connections that confirm writes show the SQL first; [submitNow] runs it. */
    fun submit() {
        if (!hasChanges) return
        if (config.confirmWrites && preview == null) openPreview() else submitNow()
    }

    /** Answers [pendingDiscard] with "submit": saves the edits, then carries on with the navigation that asked. */
    fun submitAndContinue() {
        afterSubmit = pendingDiscard ?: return
        pendingDiscard = null
        submit()
    }

    fun closePreview() {
        preview = null
        afterSubmit = null
    }

    fun submitNow() {
        val model = model?.takeIf { it.hasChanges } ?: return
        preview = null
        app.scope.launch {
            submitting = true
            try {
                val statements = ChangeSql.statements(dialect, table, model.toChanges())
                val affected = app.databases.database(config).applyChanges(statements)
                app.status = "Saved ${statements.size} change(s) to ${table.name} ($affected row(s))"
                error = null
                totalRows = null
                // The pending navigation reloads (or closes the tab) itself.
                val next = afterSubmit
                afterSubmit = null
                if (next != null) next() else load()
            } catch (e: CancellationException) {
                afterSubmit = null
                throw e
            } catch (e: Exception) {
                // Keep the edits so the user can fix them and retry; the navigation that asked is abandoned.
                afterSubmit = null
                error = e.message ?: e.toString()
            } finally {
                submitting = false
            }
        }
    }

    fun revert() {
        model?.revertAll()
        error = null
    }

    /** Runs [action] now, or after the user agrees to discard unsaved edits. */
    fun guard(action: () -> Unit) {
        if (hasChanges) pendingDiscard = action else action()
    }

    fun confirmDiscard() {
        val action = pendingDiscard ?: return
        pendingDiscard = null
        model?.revertAll()
        action()
    }

    override fun confirmClose(close: () -> Unit) = guard(close)

    override fun dispose() {
        loadJob?.cancel()
    }
}

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
import dev.datagrok.core.metadata.TableDetails
import dev.datagrok.core.metadata.TableRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Paged, sortable, filterable and editable view of one table's rows. */
class TableDataTab(private val app: AppState, val config: ConnectionConfig, val table: TableRef) : WorkspaceTab {
    override val connectionId: String = config.id
    override val title: String = table.name
    val dialect: SqlDialect = SqlDialect.of(config.type)

    val grid = DataGridState()

    /** Columns, operators and real values for the WHERE filter. */
    val filterCompletion by lazy { app.schemaCache(config).let { filterCompletion(dialect, table, it, it) } }

    val schemaVersion: Int
        get() = app.schemaCache(config).version

    /** Raw SQL condition typed by the user, applied on Enter. */
    val filter = TextFieldState()
    var appliedFilter by mutableStateOf<String?>(null)
        private set
    var sort by mutableStateOf<List<SortColumn>>(emptyList())
        private set
    var page by mutableStateOf(0)
        private set
    val pageSize: Int
        get() = app.settings.pageSize

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

    private fun load() {
        loadJob?.cancel()
        loadJob = app.scope.launch {
            loading = true
            try {
                val database = app.databases.database(config)
                if (details == null) details = runCatching { database.tableDetails(table) }.getOrNull()
                val result =
                    database.fetchPage(table, appliedFilter, sort, pageSize, page.toLong() * pageSize, app.settings.queryTimeoutSeconds)
                hasNextPage = result.truncated
                model = TableEditModel(result, details?.primaryKey.orEmpty(), readOnly = config.readOnly)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun applyFilter() = guard {
        appliedFilter = filter.text.toString().trim().ifEmpty { null }
        page = 0
        totalRows = null
        load()
    }

    /** Adds `column = value` (or `IS NULL`) to the filter, like DataGrip's "Filter by this value". */
    fun filterByValue(column: String, value: String?) {
        val numeric = model?.columns?.firstOrNull { it.name == column }?.isNumeric == true
        val condition = dialect.quoteIdentifier(column) + if (value == null) " IS NULL" else " = " + dialect.literal(value, numeric)
        val current = filter.text.toString().trim()
        filter.setTextAndPlaceCursorAtEnd(if (current.isEmpty()) condition else "($current) AND $condition")
        applyFilter()
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
            runCatching { app.databases.database(config).countRows(table, appliedFilter, app.settings.queryTimeoutSeconds) }
                .onSuccess { totalRows = it }
                .onFailure { error = it.message }
        }
    }

    fun export() {
        val model = model ?: return
        val filter = appliedFilter
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

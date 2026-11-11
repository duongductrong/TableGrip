package dev.tablegrip.app.records

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.ResultGridModel
import dev.tablegrip.app.workspace.WorkspaceTab
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.session.SessionTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One container's records (an index's documents, a table's rows, a label's nodes), a page at a time, in a grid or as
 * JSON, with the container's definition beside them. The query field takes the engine's own language and applies on
 * Enter. The record panel shows the selected record as JSON to edit and save, or a new one; every write goes through the
 * engine's own checks, so a record changed by someone else since it was read is never overwritten.
 */
class RecordBrowserTab(
    private val app: AppState,
    val config: ConnectionConfig,
    val container: RecordContainer,
    definitionFirst: Boolean = false,
    initialQuery: String = "",
) : WorkspaceTab {
    override val connectionId = config.id
    /** `shop.orders`; a tree whose root is `/` (ZooKeeper, Consul) reads as a path: `/services/api`. */
    override val title = if (container.path.firstOrNull() == "/") "/" + container.path.drop(1).joinToString("/") else container.path.joinToString(".")

    val path: List<String> = container.path

    enum class View { Grid, Json, Definition }

    var view by mutableStateOf(if (definitionFirst) View.Definition else View.Grid)

    val query = TextFieldState(initialQuery)

    /** The query the records reflect. */
    var appliedQuery by mutableStateOf(initialQuery.trim())
        private set

    val isQueryDirty: Boolean
        get() = query.text.toString().trim() != appliedQuery

    /** The engine's query language, once connected. */
    var language by mutableStateOf<RecordLanguage?>(null)
        private set

    /** Cursors of the pages seen so far: the first page's is null. */
    private var cursors = listOf<String?>(null)
    var pageIndex by mutableIntStateOf(0)
        private set
    private var nextCursor: String? = null

    val hasMore: Boolean
        get() = nextCursor != null

    var records by mutableStateOf<List<Record>>(emptyList())
        private set
    var total by mutableStateOf<Long?>(null)
        private set
    var totalIsLowerBound by mutableStateOf(false)
        private set

    /** Rows before the page shown, for the grid's row numbers. */
    var firstRow by mutableStateOf(0L)
        private set

    var model by mutableStateOf<GridModel>(ResultGridModel(JsonTables.records(emptyList())))
        private set
    val grid = DataGridState()

    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set

    val connectionStatus: ConnectionStatus
        get() = app.databases.status(config.id)

    var definition by mutableStateOf<String?>(null)
        private set

    data class Message(val text: String, val isError: Boolean)

    var message by mutableStateOf<Message?>(null)

    /** Whether records can be added, changed and deleted here: the profile isn't read-only and the engine allows it. */
    var canWrite by mutableStateOf(false)
        private set

    val pageSize: Int
        get() = app.settings.pageSize

    // region The record panel

    var showRecord by mutableStateOf(false)

    /** The record in the panel: one of the page's, or a new one (null key) until it is saved. */
    var editing by mutableStateOf<Record?>(null)
        private set
    var editingIsNew by mutableStateOf(false)
        private set
    val editor = TextFieldState("")
    private var editorOriginal = ""

    val editorDirty: Boolean
        get() = editor.text.toString() != editorOriginal

    /** Records waiting for the user to confirm their deletion. */
    var pendingDelete by mutableStateOf<List<Record>?>(null)

    // endregion

    private val jobs = mutableListOf<Job>()
    private var loadJob: Job? = null

    init {
        load(0, listOf(null))
        if (definitionFirst) loadDefinition()
    }

    private suspend fun connection(): RecordConnection = app.databases.records(config)

    fun reload() {
        appliedQuery = query.text.toString().trim()
        total = null
        load(0, listOf(null))
    }

    fun clearQuery() {
        query.setTextAndPlaceCursorAtEnd("")
        reload()
    }

    fun refresh() = load(pageIndex, cursors)

    fun nextPage() {
        val next = nextCursor ?: return
        if (loading) return
        load(pageIndex + 1, cursors.take(pageIndex + 1) + next)
    }

    fun previousPage() {
        if (pageIndex == 0 || loading) return
        load(pageIndex - 1, cursors)
    }

    fun firstPage() {
        if (pageIndex == 0 || loading) return
        load(0, cursors)
    }

    fun setPageSize(size: Int) {
        app.updateSettings { it.copy(pageSize = size) }
        load(0, listOf(null))
    }

    /** Loads page [index], whose cursor is in [pages]; paging moves only once it has loaded. */
    private fun load(index: Int, pages: List<String?>) {
        loadJob?.cancel()
        val job = launch {
            loading = true
            try {
                val store = connection()
                language = store.queryLanguage
                canWrite = store.canWrite(path)
                val page = store.page(path, appliedQuery, pages[index], pageSize)
                cursors = pages
                pageIndex = index
                nextCursor = page.cursor
                firstRow = index.toLong() * pageSize
                records = page.records
                total = page.total
                totalIsLowerBound = page.totalIsLowerBound
                model = ResultGridModel(JsonTables.records(page.records))
                // An error from an earlier attempt (before connecting, say) no longer applies.
                if (message?.isError == true) message = null
                grid.selection = null
                grid.verticalScroll.requestScrollToItem(0)
                // The panel follows the record it showed, if it is still on the page and untouched.
                val shown = editing
                if (shown != null && !editingIsNew && !editorDirty) {
                    page.records.firstOrNull { it.key != null && it.key == shown.key || sameRecord(it, shown) }?.let(::show) ?: closeRecord()
                }
            } finally {
                if (loadJob === currentCoroutineContext()[Job]) loading = false
            }
        }
        loadJob = job
    }

    /** Version fields in a key change with every write; the rest of it names the record. */
    private fun sameRecord(a: Record, b: Record): Boolean =
        a.key != null && b.key != null && a.key!!.filterKeys { it !in VersionFields } == b.key!!.filterKeys { it !in VersionFields }

    fun showDefinition() {
        view = View.Definition
        if (definition == null) loadDefinition()
    }

    private fun loadDefinition() = launch { definition = connection().definition(path) }

    fun reloadDefinition() {
        definition = null
        loadDefinition()
    }

    // region Records

    /** The page's record at [row] of the grid. */
    fun recordAt(row: Int): Record? = records.getOrNull(row)

    /** Shows [record] in the panel, unless the panel holds unsaved edits. */
    fun open(row: Int) {
        val record = recordAt(row) ?: return
        if (editorDirty && editing != record) {
            message = Message(tr("Save or revert the record being edited first."), isError = true)
            return
        }
        show(record)
        showRecord = true
    }

    private fun show(record: Record) {
        editing = record
        editingIsNew = false
        editorOriginal = JsonTables.pretty(record.document)
        editor.setTextAndPlaceCursorAtEnd(editorOriginal)
    }

    /** A new record in the panel, started from the container's fields. */
    fun newRecord() = launch {
        if (editorDirty) {
            message = Message(tr("Save or revert the record being edited first."), isError = true)
            return@launch
        }
        val template = connection().template(path)
        editing = Record(null, template)
        editingIsNew = true
        editorOriginal = JsonTables.pretty(template)
        editor.setTextAndPlaceCursorAtEnd(editorOriginal)
        showRecord = true
    }

    fun revertRecord() {
        editor.setTextAndPlaceCursorAtEnd(editorOriginal)
    }

    fun closeRecord() {
        editing = null
        editingIsNew = false
        editorOriginal = ""
        editor.setTextAndPlaceCursorAtEnd("")
    }

    /** Writes the panel's JSON: a new record, or the one shown, as long as nobody changed it since it was read. */
    fun saveRecord() {
        val record = editing ?: return
        if (saving) return
        val document =
            runCatching { Json.parseToJsonElement(editor.text.toString()) as? JsonObject ?: error(tr("A record is a JSON object.")) }.getOrElse {
                message = Message(tr("Can't save: {0}", it.message), isError = true)
                return
            }
        launch {
            saving = true
            try {
                val key = connection().put(path, if (editingIsNew) null else record.key, document)
                message = Message(if (editingIsNew) tr("Added the record.") else tr("Saved the record."), isError = false)
                app.status = tr("Saved a record to {0}.", title)
                editing = Record(key, document)
                editingIsNew = false
                editorOriginal = editor.text.toString()
                refresh()
            } finally {
                saving = false
            }
        }
    }

    /** The grid's selected records, or the one in the panel. */
    val selectedRecords: List<Record>
        get() = grid.selection?.rows?.mapNotNull(::recordAt)?.takeIf { it.isNotEmpty() } ?: listOfNotNull(editing?.takeIf { !editingIsNew })

    fun requestDelete() {
        val chosen = selectedRecords.filter { it.key != null }
        if (chosen.isNotEmpty()) pendingDelete = chosen
    }

    fun confirmDelete() {
        val chosen = pendingDelete ?: return
        pendingDelete = null
        launch {
            saving = true
            try {
                val deleted = connection().delete(path, chosen.mapNotNull { it.key })
                message = Message(tr("Deleted {0} of {1}.", deleted, chosen.size), isError = deleted < chosen.size)
                app.status = tr("Deleted {0} record(s) from {1}.", deleted, title)
                if (editing != null && chosen.any { sameRecord(it, editing!!) }) closeRecord()
                refresh()
            } finally {
                saving = false
            }
        }
    }

    /** The page's documents as one JSON array, for the JSON view. */
    val pageJson: String
        get() = JsonTables.pretty(JsonArray(records.map { JsonObject(it.meta + it.document) }))

    /** Opens a console on this container with the engine's sample statement. */
    fun openConsole() = app.workspace.openConsole(config, records = path)

    // endregion

    override fun toSession(): SessionTab = SessionTab.Records(config.id, path, container.kind, appliedQuery, definition = view == View.Definition)

    override val activeGrid: Pair<GridModel, DataGridState>?
        get() = if (view == View.Grid) model to grid else null

    override val hasUnsavedWork: Boolean
        get() = editorDirty

    private fun launch(block: suspend () -> Unit): Job =
        app.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = Message(e.message ?: e.toString(), isError = true)
            }
        }.also { job ->
            jobs += job
            job.invokeOnCompletion { app.scope.launch { jobs -= job } }
        }

    override fun dispose() {
        jobs.toList().forEach { it.cancel() }
    }

    companion object {
        /** Key fields that hold the version a record was read at, not its identity. */
        private val VersionFields = setOf("_seq_no", "_primary_term", "_rev", "_version")

        /** A record's name for the panel's title: its first meta field, else its key without versions. */
        internal fun describe(record: Record): String? =
            (record.meta.entries.take(1).ifEmpty { null } ?: record.key?.entries?.filter { it.key !in VersionFields })
                ?.joinToString(", ") { (name, value) -> "$name: ${JsonTables.cell(value)}" }
    }
}

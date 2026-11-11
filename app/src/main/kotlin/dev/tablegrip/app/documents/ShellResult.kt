package dev.tablegrip.app.documents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.app.export.ExportRequest
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.DocumentStore
import dev.tablegrip.core.driver.ShellReply
import dev.tablegrip.core.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** How a console result's documents are shown. */
enum class DocumentView(val label: String) {
    Grid(tr("Grid")),
    Json(tr("JSON")),
    Tree(tr("Tree")),
}

/**
 * The documents one console command returned, in a tab of their own: a grid with a column per top-level field, JSON or a
 * tree, as the document browser shows a collection. Documents `find` and `findOne` read as stored are editable there,
 * and submitting writes each edit on its own, then runs the command again to show what's stored now.
 */
class ShellResult(
    private val app: AppState,
    val config: ConnectionConfig,
    val database: String,
    val command: String,
    reply: ShellReply,
    millis: Long,
) {
    /** The collection the documents are stored in, when they are its documents as stored. */
    val collection: String? = reply.collection

    val title: String = collection ?: MethodName.findAll(command).lastOrNull()?.groupValues?.get(1) ?: tr("Result")

    var documents by mutableStateOf(parse(reply))
        private set

    /** More documents matched than `find` returned without a `.limit()`. */
    var hasMore by mutableStateOf(reply.hasMore)
        private set

    var millis by mutableLongStateOf(millis)
        private set

    internal var model by mutableStateOf(newModel(documents))
        private set

    val grid = DataGridState()

    /** One document that isn't stored as such (`runCommand`, `stats`…) reads best as a tree. */
    var view by mutableStateOf(if (collection == null && documents.size == 1) DocumentView.Tree else DocumentView.Grid)
    var showValuePanel by mutableStateOf(false)

    /** Pinned results survive the next run, to compare before and after. */
    var pinned by mutableStateOf(false)

    var message by mutableStateOf<DocumentBrowserTab.Message?>(null)

    /** The writes about to run, while the review is open. */
    var preview by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    val hasChanges: Boolean
        get() = model.hasChanges

    val editable: Boolean
        get() = model.editor != null

    /** Why the documents can't be edited here, or null when they can. */
    val whyReadOnly: String?
        get() = when {
            config.readOnly -> tr("Read-only connection")
            collection == null -> tr("Read-only: these documents are computed, not stored; edit them with find()")
            documents.any { "_id" !in it } -> tr("Read-only: documents projected without their _id can't be written back")
            else -> null
        }

    /** The command only reads, so it can run again to refresh the documents. */
    val canRefresh: Boolean = DocumentStore.isRead(command)

    private var job: Job? = null

    private fun newModel(documents: List<JsonObject>) = DocumentEditModel(documents, readOnly = readOnlyFor(documents))

    private fun readOnlyFor(documents: List<JsonObject>) = config.readOnly || collection == null || documents.any { "_id" !in it }

    private fun parse(reply: ShellReply): List<JsonObject> =
        reply.documents.orEmpty().map { text -> runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(mapOf("value" to JsonPrimitive(text))) } }

    /** Whether Submit shows the writes first: the user's choice, unless the connection confirms writes. */
    private val reviewsChanges: Boolean get() = config.confirmWrites || app.settings.reviewChanges

    val canSkipReview: Boolean get() = !config.confirmWrites

    fun submit() {
        if (!hasChanges || submitting || !readyToSave()) return
        if (reviewsChanges && preview == null) openPreview() else submitNow()
    }

    fun submitReviewed(skipReviewNextTime: Boolean) {
        if (skipReviewNextTime && canSkipReview) app.updateSettings { it.copy(reviewChanges = false) }
        submitNow()
    }

    fun openPreview() {
        val collection = collection ?: return
        if (!readyToSave()) return
        preview = model.preview(collection)
    }

    fun closePreview() {
        preview = null
    }

    fun revert() {
        model.revertAll()
        message = null
    }

    private fun readyToSave(): Boolean {
        val problem = model.firstProblem() ?: return true
        message = DocumentBrowserTab.Message(tr("Can't save yet. {0}", problem), isError = true)
        return false
    }

    /** Writes the edits, then runs the command again; edits that failed stay pending over what it reads back. */
    fun submitNow() {
        val collection = collection ?: return
        val current = model.takeIf { it.hasChanges } ?: return
        if (!readyToSave()) return
        preview = null
        val changes = runCatching { current.toChanges() }.getOrElse {
            message = DocumentBrowserTab.Message(it.message ?: it.toString(), isError = true)
            return
        }
        launch {
            submitting = true
            try {
                val outcome = writeDocumentChanges(app.databases.documents(config), database, collection, changes)
                val (text, isError) = outcome.message()
                app.status = tr("Saved {0} change(s) to {1}.{2}", outcome.saved, database, collection)
                reload(current.pending(outcome.retry).takeIf { !it.isEmpty })
                // After the reload, which clears the message on success.
                message = DocumentBrowserTab.Message(text, isError)
            } finally {
                submitting = false
            }
        }
    }

    /** Runs the command again, unless that would lose edits. */
    fun refresh() {
        if (hasChanges || loading || submitting || !canRefresh) return
        launch { reload(null) }
    }

    private suspend fun reload(carry: Pending?) {
        loading = true
        try {
            val started = System.nanoTime()
            val reply = app.databases.documents(config).shell(database, command)
            if (reply.isError || reply.documents == null) {
                // The edits are written; what's on screen no longer says what's stored.
                model.revertAll()
                message = DocumentBrowserTab.Message(tr("Couldn't read the documents again: {0}", reply.text), isError = true)
                return
            }
            millis = (System.nanoTime() - started) / 1_000_000
            val parsed = parse(reply)
            val next = newModel(parsed)
            val lost = carry?.let(next::restore) ?: 0
            model = next
            documents = parsed
            hasMore = reply.hasMore
            grid.selection = null
            message =
                if (lost > 0) DocumentBrowserTab.Message(tr("{0} unsaved {1} on this page any more; their edits were dropped.", lost, if (lost == 1) tr("document isn't") else tr("documents aren't")), isError = true)
                else null
        } finally {
            loading = false
        }
    }

    private fun launch(block: suspend () -> Unit) {
        job = app.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = DocumentBrowserTab.Message(e.message ?: e.toString(), isError = true)
            }
        }
    }

    /** Opens the export dialog for the documents, a column per top-level field. */
    fun export() {
        val rows = DocumentBrowserTab.rows(documents)
        app.exportRequest = ExportRequest(title, title, rows.columns, rows.rows, SqlDialect.Generic, collection ?: "documents")
    }

    fun dispose() {
        job?.cancel()
    }

    private companion object {
        /** The methods a command calls, e.g. `aggregate` or `runCommand`; the last names the result. */
        val MethodName = Regex("""\.\s*([A-Za-z_$][\w$]*)\s*\(""")
    }
}

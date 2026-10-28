package dev.datagrok.app.documents

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.grid.ResultGridModel
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.DocumentConnection
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import java.sql.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One collection's documents, a page at a time: a grid with a column per top-level field, and the selected document as
 * JSON to edit. Filter and sort are shell-style Extended JSON, as in `find()`.
 */
class DocumentBrowserTab(private val app: AppState, val config: ConnectionConfig, val database: String, val collection: String) : WorkspaceTab {
    override val connectionId = config.id
    override val title = "$database.$collection"

    val readOnly: Boolean
        get() = config.readOnly

    val filter = TextFieldState("{}")
    val sort = TextFieldState("{}")

    var skip by mutableLongStateOf(0)
        private set

    var hasMore by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    /** Documents matching the filter, counted on request: counting can be slow on large collections. */
    var total by mutableStateOf<Long?>(null)
        private set

    /** The page's documents, parsed; [raw] keeps the server's text. */
    var documents by mutableStateOf<List<JsonObject>>(emptyList())
        private set

    var model by mutableStateOf(ResultGridModel(QueryResult.Rows(emptyList(), emptyList(), truncated = false, elapsedMillis = 0)))
        private set

    val grid = DataGridState()

    /** The document shown in the editor: an index into [documents], or null for a new one or none. */
    var shown by mutableStateOf<Int?>(null)
        private set

    var creating by mutableStateOf(false)
        private set

    /** The shown document as pretty JSON, editable. */
    val editor = TextFieldState()

    var message by mutableStateOf<Message?>(null)

    data class Message(val text: String, val isError: Boolean)

    private val jobs = mutableListOf<Job>()

    init {
        reload()
    }

    private suspend fun connection(): DocumentConnection = app.databases.documents(config)

    /** Runs the filter again from the first page. */
    fun reload() {
        skip = 0
        total = null
        load()
    }

    fun nextPage() {
        if (!hasMore || loading) return
        skip += PageSize
        load()
    }

    fun previousPage() {
        if (skip == 0L || loading) return
        skip = (skip - PageSize).coerceAtLeast(0)
        load()
    }

    private fun load() =
        launch {
            loading = true
            try {
                val page = connection().find(database, collection, filter.text.toString(), sort.text.toString(), skip, PageSize)
                documents = page.documents.map { Json.parseToJsonElement(it).jsonObject }
                hasMore = page.hasMore
                model = ResultGridModel(rows(documents))
                grid.selection = null
                shown = null
                if (!creating) editor.setTextAndPlaceCursorAtEnd("")
            } finally {
                loading = false
            }
        }

    fun count() = launch { total = connection().count(database, collection, filter.text.toString()) }

    /** Shows document [index] of the page in the editor. */
    fun show(index: Int?) {
        if (index == shown && !creating) return
        creating = false
        shown = index?.takeIf { it in documents.indices }
        editor.setTextAndPlaceCursorAtEnd(shown?.let { Pretty.encodeToString(JsonObject.serializer(), documents[it]) }.orEmpty())
    }

    fun startNew() {
        creating = true
        shown = null
        editor.setTextAndPlaceCursorAtEnd("{\n  \n}")
    }

    /** Inserts the editor's document, or replaces the shown one with it. */
    fun save() =
        launch {
            val text = editor.text.toString()
            if (creating) {
                val id = connection().insert(database, collection, text)
                creating = false
                message = Message("Inserted $id.", isError = false)
            } else {
                val index = shown ?: return@launch
                connection().replace(database, collection, idOf(documents[index]), text)
                message = Message("Saved.", isError = false)
            }
            val keep = shown
            load().join()
            show(keep)
        }

    /** Deletes the documents of the selected grid rows. */
    fun deleteSelected() {
        val rows = grid.selection?.rows?.filter { it in documents.indices } ?: shown?.let { listOf(it) } ?: return
        val ids = rows.map { idOf(documents[it]) }
        launch {
            val deleted = connection().delete(database, collection, ids)
            message = Message("Deleted $deleted ${if (deleted == 1L) "document" else "documents"}.", isError = false)
            total = total?.minus(deleted)
            load()
        }
    }

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
        const val PageSize = 100

        private val Pretty = Json { prettyPrint = true }

        /** The `_id` as Extended JSON, the form the connection takes it back in. */
        internal fun idOf(document: JsonObject): String = document["_id"]?.toString() ?: throw IllegalStateException("This document has no _id.")

        /** A column per top-level field, `_id` first, in the order fields first appear. */
        internal fun rows(documents: List<JsonObject>): QueryResult.Rows {
            val fields = LinkedHashSet<String>().apply {
                if (documents.any { "_id" in it }) add("_id")
                documents.forEach { addAll(it.keys) }
            }.toList()
            val columns = fields.map { field -> ResultColumn(field, typeLabel(documents.mapNotNull { it[field] }), Types.VARCHAR) }
            val rows = documents.map { document -> fields.map { cell(document[it], missing = it !in document) } }
            return QueryResult.Rows(columns, rows, truncated = false, elapsedMillis = 0)
        }

        /**
         * A field as the grid shows it: strings without quotes, Extended JSON wrappers by their value (`ObjectId(…)`,
         * the date), nested documents and arrays as compact JSON. A missing field is blank; an explicit null is NULL.
         */
        internal fun cell(value: JsonElement?, missing: Boolean = false): String? =
            when {
                missing -> ""
                value == null || value is JsonNull -> null
                value is JsonPrimitive -> value.content
                value is JsonObject && value.size == 1 -> {
                    val (key, inner) = value.entries.single()
                    when {
                        key == "\$oid" && inner is JsonPrimitive -> "ObjectId(${inner.content})"
                        key == "\$date" -> (inner as? JsonPrimitive)?.content ?: cell(inner)
                        key in WrappedNumbers && inner is JsonPrimitive -> inner.content
                        else -> value.toString()
                    }
                }
                else -> value.toString()
            }

        /** The BSON type a column holds, from its values: `string`, `objectId`, `date`…, or `mixed`. */
        internal fun typeLabel(values: List<JsonElement>): String {
            val types =
                values.filter { it !is JsonNull }.map { value ->
                    when (value) {
                        is JsonPrimitive -> if (value.isString) "string" else if (value.content == "true" || value.content == "false") "bool" else "number"
                        is JsonArray -> "array"
                        is JsonObject ->
                            when (value.keys.singleOrNull()) {
                                "\$oid" -> "objectId"
                                "\$date" -> "date"
                                in WrappedNumbers -> "number"
                                else -> "object"
                            }
                    }
                }.toSet()
            return types.singleOrNull() ?: if (types.isEmpty()) "null" else "mixed"
        }

        private val WrappedNumbers = setOf("\$numberLong", "\$numberDecimal", "\$numberInt", "\$numberDouble")
    }
}

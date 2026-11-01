package dev.datagrok.app.documents

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.grid.DataGridState
import dev.datagrok.app.grid.GridModel
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.completion.DocumentCompleter
import dev.datagrok.core.completion.DocumentField
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.driver.DocumentConnection
import dev.datagrok.core.driver.DocumentPage
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import java.sql.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import dev.datagrok.app.connections.ConnectionStatus

/**
 * One collection's documents, a page at a time: an editable grid with a column per top-level field, or the documents as
 * JSON. Filter, sort and projection are shell-style Extended JSON, as in `find()`, with skip and limit beside them; they
 * apply on Enter, and paging keeps to what was applied, not to what's half typed in the fields. Pages move within the
 * documents that skip and limit leave.
 */
class DocumentBrowserTab(private val app: AppState, val config: ConnectionConfig, val database: String, val collection: String) : WorkspaceTab {
    override val connectionId = config.id
    override val title = "$database.$collection"

    val readOnly: Boolean
        get() = config.readOnly

    val filter = TextFieldState("{}")
    val sort = TextFieldState("{}")
    val projection = TextFieldState("{}")
    /** Blank for none, like `0`. */
    val skipField = TextFieldState("")
    val limitField = TextFieldState("")

    /** Shows the projection, skip and limit fields; open whenever any of them is applied. */
    var showOptions by mutableStateOf(false)

    /** The filter, sort and projection the rows reflect, normalized: braces added when left out. */
    var appliedFilter by mutableStateOf("{}")
        private set
    var appliedSort by mutableStateOf("{}")
        private set
    var appliedProjection by mutableStateOf("{}")
        private set
    /** Matching documents passed over before the first page. */
    var appliedSkip by mutableLongStateOf(0)
        private set
    /** At most this many documents over all pages; null for no limit. */
    var appliedLimit by mutableStateOf<Long?>(null)
        private set

    /** Documents before the page shown, after [appliedSkip]; changes only once a page has loaded. */
    var skip by mutableLongStateOf(0)
        private set

    val pageSize: Int
        get() = app.settings.pageSize

    var hasMore by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set
    val connectionStatus: ConnectionStatus
        get() = app.databases.status(config.id)

    /** Documents matching the filter, within skip and limit, counted on request: counting can be slow on large collections. */
    var total by mutableStateOf<Long?>(null)
        private set

    /** The page's documents, parsed. */
    var documents by mutableStateOf<List<JsonObject>>(emptyList())
        private set

    /** The page as an editable grid, with any unsaved edits. */
    internal var model by mutableStateOf(DocumentEditModel(emptyList(), readOnly = true))
        private set

    val grid = DataGridState()

    /** Fields seen so far, for completing the filter and sort. */
    internal val schema = DocumentSchema()

    val completionFields: List<DocumentField>
        get() = schema.version.let { schema.fields() }

    /** The query as rows and chips, kept in step with the filter, sort and projection fields. */
    internal val builder = QueryBuilder()
    var showBuilder by mutableStateOf(false)
        private set
    /** Fields dragged from the builder's list or the grid's headers onto the builder. */
    internal val fieldDrag = FieldDragState(onStart = { openBuilder() }, onDrop = ::dropField)
    /** The text the builder last wrote or read, so a change from the builder isn't read back into it. */
    private var builderText: Triple<String, String, String>? = null

    /** Shows the page's documents as JSON instead of the grid. */
    var showJson by mutableStateOf(false)
    var showValuePanel by mutableStateOf(false)

    /** The query fields differ from what the documents show. */
    val isFilterDirty: Boolean
        get() =
            normalize(filter.text.toString()) != appliedFilter || normalize(sort.text.toString()) != appliedSort ||
                normalize(projection.text.toString()) != appliedProjection ||
                parseCount(skipField.text.toString()) != appliedSkip || parseCount(limitField.text.toString())?.takeIf { it > 0 } != appliedLimit

    val hasAppliedFilter: Boolean
        get() = appliedFilter.replace(" ", "") != "{}" || appliedSort.replace(" ", "") != "{}" || hasAppliedOptions

    /** A projection, skip or limit is applied. */
    val hasAppliedOptions: Boolean
        get() = appliedProjection.replace(" ", "") != "{}" || appliedSkip > 0 || appliedLimit != null

    var message by mutableStateOf<Message?>(null)

    data class Message(val text: String, val isError: Boolean)

    /** Navigation waiting for the user to confirm discarding unsaved edits. */
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null)
    /** The writes about to run, while the preview is open (connections that confirm writes). */
    var preview by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
        private set

    val hasChanges: Boolean
        get() = model.hasChanges

    override val hasUnsavedWork: Boolean
        get() = hasChanges

    override val activeGrid: Pair<GridModel, DataGridState>
        get() = model to grid

    /** The applied sort as the grid's header arrows show it, when it is a plain `{field: ±1, …}`. */
    val sortColumns: List<SortColumn>
        get() = parseSort(appliedSort)

    private val jobs = mutableListOf<Job>()
    private var loadJob: Job? = null
    /** Edits that failed to save, to put back onto the page once it reloads. */
    private var carry: Pending? = null
    private var sampled = false

    init {
        load(0)
    }

    private suspend fun connection(): DocumentConnection = app.databases.documents(config)

    /** Applies the query fields and loads the first page; a skip or limit that isn't a count stops it. */
    fun reload() = guard {
        val skipValue = parseCount(skipField.text.toString())
        val limitValue = parseCount(limitField.text.toString())
        if (skipValue == null || limitValue == null) {
            message = Message("Skip and limit take a whole number of documents, or nothing.", isError = true)
            return@guard
        }
        appliedFilter = normalize(filter.text.toString())
        appliedSort = normalize(sort.text.toString())
        appliedProjection = normalize(projection.text.toString())
        appliedSkip = skipValue
        // As in find(), a limit of 0 is no limit.
        appliedLimit = limitValue.takeIf { it > 0 }
        total = null
        load(0)
    }

    /** Empties the query fields and shows every document. */
    fun clearFilter() = guard {
        filter.setTextAndPlaceCursorAtEnd("{}")
        sort.setTextAndPlaceCursorAtEnd("{}")
        projection.setTextAndPlaceCursorAtEnd("{}")
        skipField.setTextAndPlaceCursorAtEnd("")
        limitField.setTextAndPlaceCursorAtEnd("")
        appliedFilter = "{}"
        appliedSort = "{}"
        appliedProjection = "{}"
        appliedSkip = 0
        appliedLimit = null
        total = null
        load(0)
    }

    /** Loads the current page again, with what's applied. */
    fun refresh() = guard { load(skip) }

    fun nextPage() {
        if (!hasMore || loading) return
        guard { load(skip + pageSize) }
    }

    fun previousPage() {
        if (skip == 0L || loading) return
        guard { load((skip - pageSize).coerceAtLeast(0)) }
    }

    fun firstPage() {
        if (skip == 0L || loading) return
        guard { load(0) }
    }

    fun setPageSize(size: Int) = guard {
        app.updateSettings { it.copy(pageSize = size) }
        load(0)
    }

    /** Loads the page starting at [target]; [skip] moves only once it's there, so a failed load leaves paging intact. */
    private fun load(target: Long): Job {
        loadJob?.cancel()
        val job = launch {
            loading = true
            try {
                // The last page stops at the limit.
                val size = appliedLimit?.let { (it - target).coerceIn(0, pageSize.toLong()).toInt() } ?: pageSize
                val page =
                    if (size == 0) DocumentPage(emptyList(), hasMore = false)
                    else connection().find(database, collection, appliedFilter, appliedSort, appliedSkip + target, size, appliedProjection)
                val parsed = page.documents.map { Json.parseToJsonElement(it).jsonObject }
                val restore = carry
                carry = null
                // Documents projected without their _id can't be written back.
                val noIds = parsed.any { "_id" !in it }
                val next = DocumentEditModel(parsed, readOnly = readOnly || noIds, extraFields = restore?.fields.orEmpty())
                val lost = restore?.let(next::restore) ?: 0
                // The model first: whatever watches documents finds the matching grid.
                model = next
                skip = target
                hasMore = page.hasMore && appliedLimit.let { it == null || target + size < it }
                documents = parsed
                schema.add(parsed)
                grid.selection = null
                grid.verticalScroll.requestScrollToItem(0)
                if (lost > 0) message = Message("$lost unsaved ${if (lost == 1) "document isn't" else "documents aren't"} on this page any more; their edits were dropped.", isError = true)
                if (!sampled) {
                    sampled = true
                    launch { schema.add(connection().sample(database, collection, SampleSize).map { Json.parseToJsonElement(it).jsonObject }) }
                }
            } finally {
                // A load replaced by a newer one leaves the indicator to it.
                if (loadJob === currentCoroutineContext()[Job]) loading = false
            }
        }
        loadJob = job
        return job
    }

    fun count() = launch {
        val matching = (connection().count(database, collection, appliedFilter) - appliedSkip).coerceAtLeast(0)
        total = appliedLimit?.let { minOf(it, matching) } ?: matching
    }

    // region Grid edits

    /** Saves the grid's edits, after showing them first on connections that confirm writes. */
    fun submit() {
        if (!hasChanges || submitting || !readyToSave()) return
        if (config.confirmWrites && preview == null) openPreview() else submitNow()
    }

    fun openPreview() {
        if (!readyToSave()) return
        preview = model.preview(collection)
    }

    fun closePreview() {
        preview = null
        afterSubmit = null
    }

    /** Navigation to resume once the edits are saved, when the user chose to submit them first. */
    private var afterSubmit: (() -> Unit)? = null

    /** A value that can't be converted to its field's type stops the save before anything is written. */
    private fun readyToSave(): Boolean {
        val problem = model.firstProblem() ?: return true
        message = Message("Can't save yet. $problem", isError = true)
        return false
    }

    /**
     * Writes the edits document by document: each one stands alone, so one that fails (changed by someone else since it
     * was loaded, a duplicate `_id`…) doesn't stop the others. Failed edits stay pending on the reloaded page.
     */
    fun submitNow() {
        val current = model.takeIf { it.hasChanges } ?: return
        if (!readyToSave()) return
        preview = null
        val changes = runCatching { current.toChanges() }.getOrElse {
            message = Message(it.message ?: it.toString(), isError = true)
            return
        }
        launch {
            submitting = true
            try {
                val mongo = connection()
                val failed = linkedMapOf<Int, String>()
                for (change in changes) {
                    try {
                        when (change) {
                            is DocumentEditModel.Change.Delete -> if (mongo.delete(database, collection, listOf(change.id)) == 0L) failed[change.row] = "already deleted"
                            is DocumentEditModel.Change.Update ->
                                if (!mongo.update(database, collection, change.id, change.set, change.unset, change.expected)) {
                                    failed[change.row] = "changed or deleted by someone else since it was loaded"
                                }
                            is DocumentEditModel.Change.Insert -> mongo.insert(database, collection, change.document)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed[change.row] = e.message ?: e.toString()
                    }
                }
                val saved = changes.size - failed.size
                // A deletion that found nothing has nothing left to retry.
                val retry = failed.filterValues { it != "already deleted" }.keys
                carry = current.pending(retry).takeIf { !it.isEmpty }
                total = null
                message =
                    if (failed.isEmpty()) Message("Saved $saved ${if (saved == 1) "change" else "changes"}.", isError = false)
                    else Message(
                        "Saved $saved of ${changes.size}. " + failed.entries.take(3).joinToString("; ") { (row, why) -> "Row ${skip + row + 1}: $why" } +
                            (if (failed.size > 3) "; …" else "") + if (retry.isNotEmpty()) ". Those edits are still pending, over the current values." else "",
                        isError = true,
                    )
                app.status = "Saved $saved change(s) to $database.$collection"
                val next = afterSubmit.takeIf { failed.isEmpty() }
                afterSubmit = null
                if (next != null) {
                    carry = null
                    next()
                } else load(skip)
            } finally {
                submitting = false
            }
        }
    }

    fun revert() {
        model.revertAll()
        message = null
    }

    /** Runs [action] now, or after the user agrees to discard unsaved edits. */
    fun guard(action: () -> Unit) {
        if (hasChanges) pendingDiscard = action else action()
    }

    fun confirmDiscard() {
        val action = pendingDiscard ?: return
        pendingDiscard = null
        model.revertAll()
        action()
    }

    /** Answers [pendingDiscard] with "submit": saves the edits, then carries on with the navigation that asked. */
    fun submitAndContinue() {
        afterSubmit = pendingDiscard ?: return
        pendingDiscard = null
        submit()
    }

    override fun confirmClose(close: () -> Unit) = guard(close)

    /** Sorts by a header click: `{field: 1}`, or added to the sort with Shift. */
    fun setSort(column: String, direction: SortDirection?, additive: Boolean) = guard {
        val current = if (additive) sortColumns.filterNot { it.column == column } else emptyList()
        val next = if (direction == null) sortColumns.filterNot { it.column == column } else current + SortColumn(column, direction)
        val text = if (next.isEmpty()) "{}" else next.joinToString(", ", "{", "}") { "${DocumentCompleter.quoteKey(it.column)}: ${if (it.direction == SortDirection.Asc) 1 else -1}" }
        sort.setTextAndPlaceCursorAtEnd(text)
        appliedSort = text
        load(0)
    }

    /** Adds `field: value` (or `$ne`) to the filter, typed like the column's values; like "Filter by This Value". */
    fun filterByValue(column: String, value: String?, exclude: Boolean = false) {
        val index = model.fields.indexOf(column).takeIf { it >= 0 } ?: return
        val kind = documents.firstNotNullOfOrNull { it[column]?.let(BsonKind::of)?.takeIf { k -> k != BsonKind.Null } } ?: model.kind(0, index)
        val literal = if (value == null) "null" else (convert(kind, value) as? Converted.Ok)?.literal ?: return
        val condition = "{${DocumentCompleter.quoteKey(column)}: ${if (exclude) "{\$ne: $literal}" else literal}}"
        val current = normalize(filter.text.toString())
        filter.setTextAndPlaceCursorAtEnd(if (current.replace(" ", "") == "{}") condition else "{\$and: [$current, $condition]}")
        reload()
    }

    // endregion

    // region Query builder

    /** The BSON type [field] holds, when the documents seen agree on one. */
    internal fun fieldKind(field: String): BsonKind? = completionFields.firstOrNull { it.path == field }?.types?.let(::kindOf)

    private fun queryText() = Triple(filter.text.toString(), sort.text.toString(), projection.text.toString())

    fun toggleBuilder() = if (showBuilder) showBuilder = false else openBuilder()

    fun openBuilder() {
        if (showBuilder) return
        readIntoBuilder()
        showBuilder = true
    }

    /** Reads the fields into the builder, unless the builder wrote them. */
    internal fun readIntoBuilder(force: Boolean = true) {
        val text = queryText()
        if (!force && text == builderText) return
        builder.load(text.first, text.second, text.third, ::fieldKind)
        builderText = text
    }

    /** Writes what the builder holds into the fields; the user applies it as any typed query. */
    internal fun writeFromBuilder(rendered: QueryBuilder.Rendered) {
        val (filterText, sortText, projectionText) = rendered.query
        if (filter.text.toString() != filterText) filter.setTextAndPlaceCursorAtEnd(filterText)
        if (sort.text.toString() != sortText) sort.setTextAndPlaceCursorAtEnd(sortText)
        if (projection.text.toString() != projectionText) projection.setTextAndPlaceCursorAtEnd(projectionText)
        if (projectionText != "{}") showOptions = true
        builderText = queryText()
        builder.unreadable = false
    }

    /** A field dropped on the builder: a condition to fill in, a sort, or a field to show. */
    internal fun dropField(field: String, zone: FieldZone) {
        when (zone) {
            FieldZone.Filter -> builder.focus = builder.addCondition(field, if (fieldKind(field) == BsonKind.Array) FilterOperator.In else FilterOperator.Eq)
            FieldZone.Sort -> builder.addSort(field)
            FieldZone.Projection -> builder.addProjection(field)
        }
    }

    // endregion

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
        const val SampleSize = 300

        private val Pretty = Json { prettyPrint = true }

        /** A filter or sort as `find()` takes it: blank is `{}`, and braces left out are added. */
        internal fun normalize(text: String): String {
            val trimmed = text.trim()
            return when {
                trimmed.isEmpty() -> "{}"
                trimmed.startsWith("{") -> trimmed
                else -> "{$trimmed}"
            }
        }

        /** A skip or limit as typed: blank is 0; null when it isn't a whole, non-negative number. */
        internal fun parseCount(text: String): Long? = text.trim().ifEmpty { "0" }.toLongOrNull()?.takeIf { it >= 0 }

        private val SortEntry = Regex("""(['"]?)([^'",:{}\s]+)\1\s*:\s*(-?1)\b""")

        /** A plain sort's fields and directions, for the grid's header arrows; empty for anything fancier. */
        internal fun parseSort(text: String): List<SortColumn> {
            val body = text.trim().removePrefix("{").removeSuffix("}")
            if (body.isBlank() || '{' in body) return emptyList()
            return SortEntry.findAll(body).map { SortColumn(it.groupValues[2], if (it.groupValues[3] == "1") SortDirection.Asc else SortDirection.Desc) }.toList()
        }

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

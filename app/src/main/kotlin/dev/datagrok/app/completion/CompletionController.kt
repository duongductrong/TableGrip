package dev.datagrok.app.completion

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.datagrok.core.completion.CompletionItem
import dev.datagrok.core.completion.CompletionKind
import dev.datagrok.core.completion.CompletionResult
import dev.datagrok.core.dialect.SqlDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where suggestions come from: a synchronous completer plus optional async value lookups from the database. */
class CompletionSource(
    val complete: (text: String, caret: Int) -> CompletionResult,
    val dialect: SqlDialect,
    val cache: SchemaCache?,
)

/**
 * The open completion popup for one text field: current result, highlighted row and value suggestions that arrive
 * asynchronously. Shared by the SQL editor and the table filter.
 */
class CompletionController(private val scope: CoroutineScope) {
    var result by mutableStateOf<CompletionResult?>(null)
        private set
    var selected by mutableIntStateOf(0)
        private set
    private var values by mutableStateOf<List<CompletionItem>>(emptyList())
    private var valueJob: Job? = null

    /** Suggestions shown: data values first when a value is expected, then the rest. */
    val items: List<CompletionItem>
        get() = values + (result?.items ?: emptyList())

    val isOpen: Boolean
        get() = result != null && items.isNotEmpty()

    /** Recomputes for the caret; [explicit] (⌃Space) also opens with an empty prefix. */
    fun update(state: TextFieldState, source: CompletionSource, explicit: Boolean) {
        val caret = state.selection.start
        if (!state.selection.collapsed) return close()
        val next = source.complete(state.text.toString(), caret)
        source.cache?.request(next.pendingTables)
        val typed = next.prefix.isNotEmpty() || explicit || next.value != null || isAfterDot(state, next)
        if (!typed) return close()
        val previousKey = result?.let { it.replaceStart to it.prefix }
        result = next
        if (previousKey != (next.replaceStart to next.prefix)) selected = 0
        loadValues(next, source)
    }

    private fun isAfterDot(state: TextFieldState, result: CompletionResult) = state.text.getOrNull(result.replaceStart - 1) == '.'

    private fun loadValues(result: CompletionResult, source: CompletionSource) {
        valueJob?.cancel()
        val context = result.value
        val cache = source.cache
        if (context == null || cache == null) {
            values = emptyList()
            return
        }
        valueJob = scope.launch {
            delay(120) // Let fast typing settle before querying.
            val numeric = cache.isNumeric(context.namespace, context.table, context.column)
            values =
                cache.values(context).map { value ->
                    val literal = if (numeric && !context.quoted && value.toBigDecimalOrNull() != null) value else source.dialect.literal(value, numeric = false)
                    CompletionItem(value, literal, CompletionKind.Value, "${context.column} value")
                }
        }
    }

    fun move(delta: Int) {
        val count = items.size
        if (count > 0) selected = (selected + delta).mod(count)
    }

    fun select(index: Int) {
        selected = index
    }

    /** Inserts the highlighted (or given) item over the typed prefix. */
    fun accept(state: TextFieldState, index: Int = selected) {
        val result = result ?: return
        val item = items.getOrNull(index) ?: return
        var end = result.replaceEnd
        // Completing inside 'pa|' replaces the whole literal, including an auto-inserted closing quote.
        if (item.kind == CompletionKind.Value && result.value?.quoted == true && state.text.getOrNull(end) == '\'') end++
        state.edit {
            replace(result.replaceStart, end, item.insertText)
            selection = TextRange(result.replaceStart + item.caretOffset)
        }
        close()
    }

    fun close() {
        result = null
        values = emptyList()
        valueJob?.cancel()
    }
}

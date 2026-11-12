package dev.tablegrip.app.chart

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import dev.tablegrip.core.assist.ChartProposal
import dev.tablegrip.core.chart.ChartData
import dev.tablegrip.core.chart.ChartFrame
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartSuggest
import dev.tablegrip.core.chart.ColumnProfile
import dev.tablegrip.core.chart.ColumnProfiler
import dev.tablegrip.core.driver.ChartRows
import dev.tablegrip.core.driver.QueryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Where charts are computed: off the UI thread, and on at most two cores, so several heavy charts can't starve the
 * rest of the app's background work (completion, metadata, queries).
 */
internal val ChartDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Stops a long loop once the coroutine running it is cancelled, e.g. because the spec changed again. */
internal fun CoroutineScope.checkpoint(): () -> Unit = { coroutineContext.ensureActive() }

/** Asking an agent for a chart, one request at a time per result. */
sealed interface ChartAssistState {
    data class Asking(val agent: String?) : ChartAssistState

    /** Shown for review; nothing changes until it is applied. */
    data class Proposed(val proposal: ChartProposal, val agent: String) : ChartAssistState

    data class Failed(val message: String) : ChartAssistState
}

/**
 * The chart view of one result: the columns' profiles (read once, off the UI thread), the spec being drawn and the
 * frame drawn from it. A spec carried over from the previous run of the statement wins over the suggestion when it
 * still fits the columns.
 */
class ChartState(
    val rows: QueryResult.Rows,
    /**
     * Groups a spec on the server over every row rather than [rows], which are only the first ones fetched; null when
     * the source can't. Asked for only while [rows] are truncated and the spec aggregates.
     */
    private val server: (suspend (ChartSpec) -> ChartRows?)? = null,
) {
    var profiles by mutableStateOf<List<ColumnProfile>?>(null)
        private set
    var suggestions by mutableStateOf(emptyList<ChartSpec>())
        private set
    var spec by mutableStateOf<ChartSpec?>(null)
        private set
    /** Rebuilt from [spec] off the UI thread; null while there is none, or the spec is invalid. */
    var frame by mutableStateOf<ChartFrame?>(null)
        private set

    /** Whether [frame] was grouped on the server from every row rather than drawn from [rows]. */
    var onServer by mutableStateOf(false)
        private set
    /** The server is grouping the current spec; [frame] shows the rows fetched meanwhile. */
    var grouping by mutableStateOf(false)
        private set
    /** Why grouping on the server failed; the chart falls back to [rows]. */
    var serverError by mutableStateOf<String?>(null)
        private set
    /** The server returned more groups than were read, so the chart leaves the rest out. */
    var serverTruncated by mutableStateOf(false)
        private set

    /** Whether the chart covers only the first rows fetched rather than all of them. */
    val partial: Boolean
        get() = rows.truncated && !onServer

    /** Problems with [spec] against the columns, which take the chart's place while there are any. */
    val problems: List<String>
        get() = spec?.let { spec -> profiles?.let(spec::validate) }.orEmpty()

    /** The chart's size on screen in dp, which exported images take. */
    var drawnSize = DefaultSize

    /** What to ask the agent for, kept while switching views. */
    val request = TextFieldState()
    var assist by mutableStateOf<ChartAssistState?>(null)
    internal var assistJob: Job? = null

    /** Profiles the columns and picks the first suggestion unless a spec was already set. */
    suspend fun prepare() {
        if (profiles != null) return
        val read = withContext(ChartDispatcher) { ColumnProfiler.profile(rows.columns, rows.rows, checkpoint()) }
        suggestions = ChartSuggest.suggest(read)
        profiles = read
        val current = spec
        if (current == null || current.validate(read).isNotEmpty()) spec = suggestions.firstOrNull()
    }

    /** Draws [next], adjusted to the columns (see [ChartSpec.adjusted]) once they are known. */
    fun show(next: ChartSpec) {
        spec = profiles?.let(next::adjusted) ?: next
    }

    fun edit(change: (ChartSpec) -> ChartSpec) {
        spec?.let { show(change(it)) }
    }

    /**
     * Draws [spec] from [rows] at once, then, when they are only the first rows and the spec aggregates, regroups it on
     * the server once edits settle. Cancelled and restarted when the spec changes.
     */
    suspend fun rebuild() {
        val profiles = profiles ?: return
        val spec = spec
        if (spec == null || spec.validate(profiles).isNotEmpty()) {
            frame = null
            onServer = false
            return
        }
        val server = server?.takeIf { rows.truncated && spec.aggregates }
        frame = withContext(ChartDispatcher) { ChartData.build(rows.rows, profiles, spec, checkpoint()) }
        onServer = false
        if (server == null) {
            serverError = null
            return
        }
        grouping = true
        try {
            delay(ServerDelayMillis)
            val grouped = server(spec)
            if (grouped == null) return
            frame =
                withContext(ChartDispatcher) {
                    val read = ColumnProfiler.profile(grouped.rows.columns, grouped.rows.rows, checkpoint())
                    ChartData.build(grouped.rows.rows, read, grouped.spec, checkpoint())
                }
            onServer = true
            serverTruncated = grouped.rows.truncated
            serverError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverError = e.message ?: e.toString()
        } finally {
            grouping = false
        }
    }

    /** Draws the agent's proposal under review. */
    fun applyProposal() {
        val proposed = assist as? ChartAssistState.Proposed ?: return
        show(proposed.proposal.spec)
        dismissAssist()
    }

    fun dismissAssist() {
        assistJob?.cancel()
        assistJob = null
        assist = null
    }

    private companion object {
        val DefaultSize = IntSize(800, 450)
        /** Edits in a row (picking a column, then its aggregate) regroup on the server once, not per edit. */
        const val ServerDelayMillis = 300L
    }
}

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
import dev.tablegrip.core.driver.QueryResult
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
class ChartState(val rows: QueryResult.Rows) {
    var profiles by mutableStateOf<List<ColumnProfile>?>(null)
        private set
    var suggestions by mutableStateOf(emptyList<ChartSpec>())
        private set
    var spec by mutableStateOf<ChartSpec?>(null)
        private set
    /** Rebuilt from [spec] off the UI thread; null while there is none, or the spec is invalid. */
    var frame by mutableStateOf<ChartFrame?>(null)
        private set

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

    suspend fun rebuild() {
        val profiles = profiles ?: return
        val spec = spec
        frame = if (spec == null || spec.validate(profiles).isNotEmpty()) null else withContext(ChartDispatcher) { ChartData.build(rows.rows, profiles, spec, checkpoint()) }
    }

    private companion object {
        val DefaultSize = IntSize(800, 450)
    }
}

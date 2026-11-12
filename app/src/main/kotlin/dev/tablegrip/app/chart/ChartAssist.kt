package dev.tablegrip.app.chart

import dev.tablegrip.app.AppState
import dev.tablegrip.core.assist.AssistResult
import dev.tablegrip.core.assist.ChartSpecTask
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.i18n.tr
import kotlinx.coroutines.launch

/** Values per column a chart request shows the agent, when the connection shares data, and their length. */
private const val ChartSamples = 5
private const val SampleLength = 40

/**
 * Asks the chat's agent for a chart of [chart]'s rows matching [request]. It gets [origin], the statement the rows come
 * from, and the columns' kinds; a few values per column only when the connection shares data. The answer is proposed,
 * and only drawn once applied. Callers check the connection may be described to an agent at all.
 */
internal fun AppState.askAgentForChart(config: ConnectionConfig, chart: ChartState, origin: String, request: String) {
    val profiles = chart.profiles ?: return
    if (request.isBlank()) return
    chart.assistJob?.cancel()
    chart.assist = ChartAssistState.Asking(agent.selected?.descriptor?.name)
    chart.assistJob =
        scope.launch {
            val assistant = agent.assistAgent()
            if (assistant == null) {
                chart.assist = ChartAssistState.Failed(tr("No agent is available. Install a command-line agent or set up an API provider in Settings › Agent."))
                return@launch
            }
            chart.assist = ChartAssistState.Asking(assistant.descriptor.name)
            val rows = chart.rows
            val samples =
                if (!aiAccess(config).sharesData) emptyMap()
                else profiles.associate { column ->
                    column.name to rows.rows.asSequence().mapNotNull { it[column.index]?.take(SampleLength) }.distinct().take(ChartSamples).toList()
                }
            val task = ChartSpecTask(request, config.type.displayName, origin, profiles, samples, rows.truncated)
            chart.assist =
                when (val result = agent.assist.run(assistant, task)) {
                    is AssistResult.Ok -> ChartAssistState.Proposed(result.value, result.agent)
                    is AssistResult.Invalid -> ChartAssistState.Failed(tr("{0} didn't return a usable chart: {1}", assistant.descriptor.name, result.reasons.first()))
                    is AssistResult.Failed -> ChartAssistState.Failed(result.message)
                }
        }
}

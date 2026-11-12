package dev.tablegrip.app.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.tablegrip.app.chart.ChartView
import dev.tablegrip.app.chart.ChartWarning
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** A console result drawn as a chart; when it holds only the first rows, it offers to rerun the statement grouped. */
@Composable
internal fun ResultChartView(tab: ConsoleTab, view: ResultSetView, modifier: Modifier = Modifier) {
    ChartView(view.chart, onAsk = { request: String -> tab.askForChart(view, request) }.takeIf { tab.canAskAgent }, modifier) {
        // Splits and classifies the statement: once per spec, not on every recomposition.
        val canAggregate = remember(view.chart.spec) { tab.canAggregateChart(view) }
        if (view.rows.truncated) {
            ChartWarning(tr("Drawn from the first {0} rows fetched; the query returns more.", view.rows.rows.size)) {
                when {
                    canAggregate -> Link(tr("Aggregate on server"), onClick = { tab.aggregateChart(view) }, enabled = !tab.running)
                    view.chart.spec?.aggregates == false -> Text(tr("Sum or count the measures to aggregate on the server."), color = JewelTheme.globalColors.text.info)
                }
            }
        }
    }
}

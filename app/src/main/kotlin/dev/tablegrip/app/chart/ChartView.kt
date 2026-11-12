package dev.tablegrip.app.chart

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.app.ui.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.ColumnKind
import dev.tablegrip.core.chart.ColumnProfile
import dev.tablegrip.core.chart.ColumnProfiler
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.chart.TimeBucket
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Rows drawn as a chart, with what was left out said above it and the axes chosen on the right. [notice] leads the
 * notes, for what the source knows about its rows (e.g. that only the first ones were fetched). [onAsk] sends a
 * description to the agent; null hides the field.
 */
@Composable
internal fun ChartView(chart: ChartState, onAsk: ((String) -> Unit)?, modifier: Modifier = Modifier, notice: @Composable () -> Unit = {}) {
    LaunchedEffect(chart) { chart.prepare() }
    LaunchedEffect(chart, chart.spec, chart.profiles) { chart.rebuild() }
    val info = JewelTheme.globalColors.text.info
    val density = LocalDensity.current
    Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            notice()
            ChartNotes(chart)
            Box(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { size -> with(density) { chart.drawnSize = IntSize(size.width.toDp().value.toInt(), size.height.toDp().value.toInt()) } },
                contentAlignment = Alignment.Center,
            ) {
                val frame = chart.frame
                when {
                    chart.profiles == null -> CircularProgressIndicator()
                    chart.spec == null -> Text(tr("No chart fits these columns: it takes a number, a date or a few categories."), color = info)
                    chart.problems.isNotEmpty() ->
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            chart.problems.forEach { Text(it, color = JewelTheme.globalColors.text.error) }
                        }
                    frame == null -> CircularProgressIndicator()
                    frame.points.isEmpty() -> Text(tr("Nothing to draw: no row has values for both axes."), color = info)
                    else -> CanvasChartRenderer.Chart(frame, ResultChartColors.current(), Modifier.fillMaxSize().padding(8.dp))
                }
            }
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        ChartEditor(chart, onAsk, Modifier.width(260.dp).fillMaxHeight())
    }
}

/** A warning line above the chart, e.g. that it covers only the rows fetched, with an optional action after it. */
@Composable
internal fun ChartWarning(text: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AllIconsKeys.General.Warning, null, Modifier.size(14.dp))
        Text(text, Modifier.weight(1f, fill = false))
        action()
    }
}

/** What the chart doesn't show (rows without values, categories and series left out, points thinned) and where it was grouped. */
@Composable
private fun ChartNotes(chart: ChartState) {
    val info = JewelTheme.globalColors.text.info
    val frame = chart.frame
    chart.serverError?.let { ChartWarning(tr("Couldn't group every row on the server, so this shows the rows fetched: {0}", it)) }
    val notes =
        buildList {
            if (chart.grouping) add(tr("grouping every row on the server…"))
            else if (chart.onServer) add(if (chart.serverTruncated) tr("grouped on the server; only the first groups are shown") else tr("grouped on the server from every row"))
            if (frame == null) return@buildList
            if (frame.skippedRows > 0) add(tr("{0} rows without a value for x or y left out", frame.skippedRows))
            if (frame.omittedCategories > 0) add(tr("{0} smaller categories not shown", frame.omittedCategories))
            if (frame.mergedSeries > 0) add(tr("{0} smaller series merged into Other", frame.mergedSeries))
            if (frame.downsampled) add(tr("thinned out to keep drawing fast, keeping the shape"))
        }
    if (notes.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chart.grouping) CircularProgressIndicator(Modifier.size(12.dp))
            Text(notes.joinToString(" · ").replaceFirstChar(Char::uppercase), color = info)
        }
    }
}

/** The spec as fields: type, x, its date grouping, measures, series. Edits draw at once; the agent's answer is reviewed first. */
@Composable
private fun ChartEditor(chart: ChartState, onAsk: ((String) -> Unit)?, modifier: Modifier) {
    val profiles = chart.profiles
    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onAsk != null && profiles != null) ChartAsk(chart, onAsk)
        val spec = chart.spec
        if (profiles == null || spec == null) {
            if (profiles != null) Text(tr("Pick a chart once the result has a number, a date or a few categories."), color = JewelTheme.globalColors.text.info)
            return@Column
        }
        val plottable = profiles.filter { it.kind != ColumnKind.Other }
        val names = plottable.map { it.name }

        Label(tr("Chart"))
        Combo(ChartType.entries.map { it.label }, ChartType.entries.indexOf(spec.type)) { index -> chart.edit { it.copy(type = ChartType.entries[index]) } }

        Label(tr("X axis"))
        Combo(names, names.indexOf(spec.x)) { index -> chart.edit { it.copy(x = names[index]) } }
        val xKind = profiles.firstOrNull { it.name == spec.x }?.kind
        if (xKind == ColumnKind.Temporal && spec.type != ChartType.Scatter) {
            Label(tr("Group dates by"))
            val buckets = listOf<TimeBucket?>(null) + TimeBucket.entries
            Combo(buckets.map { it?.label ?: tr("None") }, buckets.indexOf(spec.bucket)) { index -> chart.edit { it.copy(bucket = buckets[index]) } }
        }

        Label(tr("Y axis"))
        spec.y.forEachIndexed { position, measure ->
            MeasureRow(measure, names, removable = spec.y.size > 1, onChange = { changed -> chart.edit { it.copy(y = it.y.toMutableList().also { list -> list[position] = changed }) } }) {
                chart.edit { it.copy(y = it.y.filterIndexed { i, _ -> i != position }) }
            }
        }
        if (spec.type != ChartType.Pie && spec.series == null && spec.y.size < ChartSpec.MaxMeasures) {
            val next = profiles.firstOrNull { p -> p.kind == ColumnKind.Numeric && spec.y.none { it.column == p.name } } ?: profiles.firstOrNull { it.kind == ColumnKind.Numeric }
            if (next != null) Link(tr("Add measure"), onClick = { chart.edit { it.copy(y = it.y + Measure(next.name, it.y.last().aggregate)) } })
        }

        if (spec.type != ChartType.Pie) {
            Label(tr("Split by"))
            val series = listOf<ColumnProfile?>(null) + plottable.filter { it.name != spec.x && it.kind != ColumnKind.Temporal }
            Combo(series.map { it?.let(::seriesLabel) ?: tr("None") }, series.indexOfFirst { it?.name == spec.series }.coerceAtLeast(0)) { index ->
                chart.edit { it.copy(series = series[index]?.name) }
            }
            if (spec.y.size > 1) Text(tr("Splitting keeps the first measure."), color = JewelTheme.globalColors.text.info)
        }
        if (spec.type == ChartType.Bar) CheckboxRow(tr("Horizontal bars"), spec.horizontal, onCheckedChange = { checked -> chart.edit { it.copy(horizontal = checked) } })

        val others = chart.suggestions.filter { it != spec }
        if (others.isNotEmpty()) {
            Label(tr("Suggested"))
            others.forEach { suggestion -> Link(describe(suggestion), onClick = { chart.show(suggestion) }) }
        }
    }
}

@Composable
private fun MeasureRow(measure: Measure, columns: List<String>, removable: Boolean, onChange: (Measure) -> Unit, onRemove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Combo(columns, columns.indexOf(measure.column)) { onChange(measure.copy(column = columns[it])) } }
        Box(Modifier.width(92.dp)) { Combo(Aggregate.entries.map { it.label }, Aggregate.entries.indexOf(measure.aggregate)) { onChange(measure.copy(aggregate = Aggregate.entries[it])) } }
        if (removable) IconButton(onClick = onRemove) { Icon(AllIconsKeys.General.Remove, tr("Remove measure")) }
    }
}

/** A description of a chart for the agent's request: typed, sent with Enter, its answer shown for review. */
@Composable
private fun ChartAsk(chart: ChartState, onAsk: (String) -> Unit) {
    val info = JewelTheme.globalColors.text.info
    val asking = chart.assist is ChartAssistState.Asking
    TextField(
        chart.request,
        Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
            if (enter && !asking) onAsk(chart.request.text.toString())
            enter
        },
        placeholder = { Text(tr("Describe a chart, e.g. revenue by month")) },
        leadingIcon = { Icon(AppIcons.Agent, null, Modifier.size(14.dp).padding(end = 2.dp)) },
    )
    when (val assist = chart.assist) {
        null -> Unit
        is ChartAssistState.Asking ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp))
                Text(tr("Asking {0}…", assist.agent ?: tr("the agent")), Modifier.weight(1f), color = info)
                Link(tr("Cancel"), onClick = { chart.dismissAssist() })
            }
        is ChartAssistState.Failed ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(assist.message, color = JewelTheme.globalColors.text.error)
                Link(tr("Dismiss"), onClick = { chart.dismissAssist() })
            }
        is ChartAssistState.Proposed -> {
            val islands = LocalIslands.current
            val shape = RoundedCornerShape(6.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(islands.frame).border(1.dp, islands.accent, shape).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(describe(assist.proposal.spec), fontWeight = FontWeight.SemiBold)
                if (assist.proposal.explanation.isNotBlank()) Text(assist.proposal.explanation)
                assist.proposal.assumptions.forEach { Text("• $it", color = info) }
                Text(tr("from {0}", assist.agent), color = info)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    OutlinedButton(onClick = { chart.dismissAssist() }) { Text(tr("Discard")) }
                    DefaultButton(onClick = { chart.applyProposal() }) { Text(tr("Apply")) }
                }
            }
        }
    }
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 4.dp), color = JewelTheme.globalColors.text.info)
}

@Composable
private fun Combo(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    ListComboBox(items = items, selectedIndex = selected.coerceAtLeast(0), onSelectedItemChange = { if (it != selected) onSelect(it) }, modifier = Modifier.fillMaxWidth())
}

private fun seriesLabel(column: ColumnProfile): String = if (column.distinct >= ColumnProfiler.DistinctCap) column.name else "${column.name} (${column.distinct})"

/** "Line of sum(total) by created_at per month, split by region". */
internal fun describe(spec: ChartSpec): String = buildString {
    append(tr("{0} of {1} by {2}", spec.type.label, spec.y.joinToString { it.label }, spec.x))
    spec.bucket?.let { append(tr(" per {0}", it.label.lowercase())) }
    spec.series?.let { append(tr(", split by {0}", it)) }
}

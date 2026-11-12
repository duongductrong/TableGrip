package dev.tablegrip.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.core.explain.PlanFlag
import dev.tablegrip.core.explain.PlanNode
import dev.tablegrip.core.explain.QueryPlan
import dev.tablegrip.core.i18n.tr
import java.text.DecimalFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** One line of the plan tree: a node and how deep it is. */
private class PlanRow(val node: PlanNode, val depth: Int)

/** The nodes shown, parents first, skipping the children of collapsed ones. */
private fun visibleRows(view: ResultSetView, plan: QueryPlan): List<PlanRow> = buildList {
    fun add(node: PlanNode, depth: Int) {
        add(PlanRow(node, depth))
        if (view.collapsed[node] != true) node.children.forEach { add(it, depth + 1) }
    }
    add(plan.root, 0)
}

/**
 * A query plan as an expandable tree, each step with its share of the plan's time (or cost, when it wasn't run), its
 * rows expected and returned, and what's worth a look; the selected step's details on the right.
 */
@Composable
internal fun PlanView(view: ResultSetView, modifier: Modifier = Modifier) {
    val plan = view.plan ?: return
    Column(modifier) {
        PlanSummary(plan)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val detailsWidth = if (maxWidth < 700.dp) 220.dp else if (maxWidth < 900.dp) 280.dp else 360.dp
            Row(Modifier.fillMaxSize()) {
                PlanTree(view, plan, Modifier.weight(1f).fillMaxHeight())
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                NodeDetails(plan, view.selectedNode ?: plan.root, Modifier.width(detailsWidth).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun PlanSummary(plan: QueryPlan) {
    val info = JewelTheme.globalColors.text.info
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (plan.analyzed) tr("Explain Analyze") else tr("Estimated plan"), fontWeight = FontWeight.SemiBold)
        plan.executionMs?.let { Text(tr("Execution {0}", millis(it))) }
        plan.planningMs?.let { Text(tr("Planning {0}", millis(it)), color = info) }
        if (!plan.analyzed) plan.root.cost?.let { Text(tr("Cost {0}", number(it)), color = info) }
        val flagged = plan.flagged.size
        if (flagged > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Warning, null, Modifier.size(12.dp))
                Text(tr("{0} step(s) worth a look", flagged))
            }
        }
        Box(Modifier.weight(1f))
        Text(
            if (plan.byTime) tr("Shares are of time spent in each step") else tr("Shares are of the planner's cost"),
            color = info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(plan.source.label, color = info, maxLines = 1)
    }
}

@Composable
private fun PlanTree(view: ResultSetView, plan: QueryPlan, modifier: Modifier) = BoxWithConstraints(modifier) {
    val rows = visibleRows(view, plan)
    val selectedBackground = JewelTheme.treeStyle.colors.backgroundSelectedActive
    // The step's name comes first; the numbers make room for it in a narrow panel (the details have them all).
    val showRows = maxWidth >= 640.dp
    val showTime = maxWidth >= 460.dp
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        items(rows) { row ->
            val node = row.node
            val insight = plan.insight(node)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (node === view.selectedNode) selectedBackground else Color.Transparent)
                    .clickable { view.selectedNode = node }
                    .padding(start = (8 + row.depth * 16).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    if (node.children.isNotEmpty()) {
                        val collapsed = view.collapsed[node] == true
                        Icon(
                            if (collapsed) AllIconsKeys.General.ChevronRight else AllIconsKeys.General.ChevronDown,
                            if (collapsed) tr("Expand") else tr("Collapse"),
                            Modifier.size(12.dp).clickable { if (collapsed) view.collapsed.remove(node) else view.collapsed[node] = true },
                        )
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    node.role?.let { Text(it, color = JewelTheme.globalColors.text.info) }
                    Text(
                        node.operation,
                        fontWeight = if (PlanFlag.Expensive in insight.flags) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (node.neverExecuted) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    node.relation?.takeIf { it !in node.operation }?.let { Text(it, color = TableColor, maxLines = 1) }
                    node.index?.takeIf { it !in node.operation }?.let { Text(tr("using {0}", it), color = JewelTheme.globalColors.text.info, maxLines = 1) }
                    if (node.neverExecuted) Text(tr("never executed"), color = JewelTheme.globalColors.text.info)
                    insight.flags.forEach { FlagChip(it, node, insight.misestimate) }
                }
                if (showRows) Text(rowsText(node, plan.analyzed), Modifier.width(140.dp), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showTime) {
                    Text(
                        if (plan.byTime) node.selfTime?.let(::millis).orEmpty() else node.selfCost?.let(::number).orEmpty(),
                        Modifier.width(72.dp),
                        color = JewelTheme.globalColors.text.info,
                        maxLines = 1,
                    )
                }
                ShareBar(insight.share)
            }
        }
    }
}

/** How much of the plan the step takes, as a bar and a percentage. */
@Composable
private fun ShareBar(share: Double?) {
    Row(Modifier.width(88.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val fraction = (share ?: 0.0).toFloat().coerceIn(0f, 1f)
        Box(Modifier.width(40.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(JewelTheme.globalColors.borders.normal)) {
            if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(shareColor(fraction)))
        }
        Text(share?.let { percent(it) }.orEmpty(), maxLines = 1)
    }
}

@Composable
private fun FlagChip(flag: PlanFlag, node: PlanNode, misestimate: Double?) {
    val (label, tooltip) =
        when (flag) {
            PlanFlag.Expensive -> tr("slow") to tr("This step takes a large share of the plan")
            PlanFlag.LargeScan -> tr("full scan") to tr("Reads every row of {0}: about {1} rows. An index on the filtered columns may help.", node.relation ?: "?", number(node.scannedRows ?: 0.0))
            PlanFlag.Misestimate -> tr("rows ×{0}", number(misestimate ?: 0.0)) to
                tr("The planner expected {0} row(s) but got {1}: its statistics may be stale (ANALYZE the table) or the condition hard to estimate.", number(node.estimatedRows ?: 0.0), number(node.actualRows ?: 0.0))
        }
    Tooltip({ Text(tooltip) }) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(4.dp)).background(flagColor(flag).copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp),
            color = flagColor(flag),
            maxLines = 1,
        )
    }
}

@Composable
private fun NodeDetails(plan: QueryPlan, node: PlanNode, modifier: Modifier) {
    val info = JewelTheme.globalColors.text.info
    val insight = plan.insight(node)
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(node.operation, fontWeight = FontWeight.SemiBold)
        node.relation?.let { Text(it, color = TableColor) }
        Spacer(Modifier.height(6.dp))
        insight.share?.let { Detail(if (plan.byTime) tr("Share of time") else tr("Share of cost"), percent(it)) }
        node.selfTime?.let { Detail(tr("Time in this step"), millis(it)) }
        node.time?.let { Detail(tr("Time with children"), millis(it)) }
        node.selfCost?.let { Detail(tr("Cost of this step"), number(it)) }
        node.cost?.let { Detail(tr("Cost with children"), number(it)) }
        node.estimatedRows?.let { Detail(tr("Rows expected"), number(it)) }
        node.actualRows?.let { Detail(tr("Rows returned"), number(it)) }
        node.loops?.let { Detail(tr("Loops"), number(it)) }
        if (insight.flags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { insight.flags.forEach { FlagChip(it, node, insight.misestimate) } }
        }
        Spacer(Modifier.height(8.dp))
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(tr("Reported by the server"), color = info)
        node.details.forEach { (label, value) -> Detail(label, value) }
        if (node === plan.root && plan.details.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tr("Statement"), color = info)
            plan.details.forEach { (label, value) -> Detail(label, value) }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(130.dp), color = JewelTheme.globalColors.text.info)
        Text(value, Modifier.weight(1f), style = JewelTheme.editorTextStyle)
    }
}

/** EXPLAIN's output as the server sent it, JSON indented. */
@Composable
internal fun PlanTextView(view: ResultSetView, modifier: Modifier = Modifier) {
    val plan = view.plan ?: return
    val text = remember(plan) { TextFieldState(pretty(plan.text)) }
    TextArea(text, modifier.fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
}

private val PrettyJson = Json { prettyPrint = true }

private fun pretty(text: String): String =
    if (!text.trimStart().startsWith("[") && !text.trimStart().startsWith("{")) text
    else runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)

private fun rowsText(node: PlanNode, analyzed: Boolean): String {
    val estimated = node.estimatedRows?.let(::number)
    val actual = node.actualRows?.let(::number)
    val loops = node.loops?.takeIf { it > 1 }?.let { " ×" + number(it) }.orEmpty()
    return when {
        analyzed && actual != null -> tr("{0} of {1} rows{2}", actual, estimated ?: "?", loops)
        estimated != null -> tr("{0} rows", estimated)
        else -> ""
    }
}

private fun number(value: Double): String = if (value >= 100 || value == Math.rint(value)) Whole.format(value) else Fraction.format(value)

private fun millis(value: Double): String = if (value >= 100) tr("{0} ms", Whole.format(value)) else tr("{0} ms", Fraction.format(value))

private fun percent(value: Double): String = if (value < 0.001) "0%" else if (value < 0.1) Percent1.format(value) else Percent0.format(value)

private val Whole = DecimalFormat("#,##0")
private val Fraction = DecimalFormat("#,##0.###")
private val Percent0 = DecimalFormat("0%")
private val Percent1 = DecimalFormat("0.0%")

private val TableColor = Color(0xFF4A9FD8)

private fun flagColor(flag: PlanFlag): Color =
    when (flag) {
        PlanFlag.Expensive -> Color(0xFFE5534B)
        PlanFlag.LargeScan -> Color(0xFFD9822B)
        PlanFlag.Misestimate -> Color(0xFFA371F7)
    }

/** Green for a small share, through amber, to red for most of the plan. */
private fun shareColor(fraction: Float): Color =
    when {
        fraction >= 0.3f -> Color(0xFFE5534B)
        fraction >= 0.1f -> Color(0xFFD9A62B)
        else -> Color(0xFF57AB5A)
    }

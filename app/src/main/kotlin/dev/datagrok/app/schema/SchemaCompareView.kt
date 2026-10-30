package dev.datagrok.app.schema

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.AppState
import dev.datagrok.app.console.SqlEditor
import dev.datagrok.app.table.ErrorBanner
import dev.datagrok.app.ui.EnvironmentChip
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.schema.DiffItem
import dev.datagrok.core.schema.TableDiff
import dev.datagrok.core.schema.TableDiffKind
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.TriStateCheckbox
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun SchemaCompareView(app: AppState, tab: SchemaCompareTab) {
    Column(Modifier.fillMaxSize()) {
        CompareBar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }
        val comparison = tab.comparison
        when {
            tab.comparing && comparison == null -> Box(Modifier.fillMaxSize()) { Progress(tab, Modifier.align(Alignment.Center)) }
            comparison == null ->
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        "Compare two schemas",
                        "Pick the source — the schema as it should be — and the target to bring in line, e.g. staging → production. " +
                            "Nothing changes until you review the migration script and run it.",
                        Modifier.align(Alignment.Center),
                    ) {
                        DefaultButton(onClick = tab::compare, enabled = tab.canCompare) { Text("Compare") }
                    }
                }
            comparison.isEmpty ->
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        "The schemas match",
                        "${comparison.identical} table(s) have the same columns, keys and indexes on both sides.",
                        Modifier.align(Alignment.Center),
                    ) { OutlinedButton(onClick = tab::compare) { Text("Compare Again") } }
                }
            else ->
                HorizontalSplitLayout(
                    first = { DiffList(tab) },
                    second = { MigrationPane(tab) },
                    modifier = Modifier.fillMaxSize(),
                    firstPaneMinWidth = 300.dp,
                    secondPaneMinWidth = 320.dp,
                    state = rememberSplitLayoutState(0.48f),
                )
        }
    }
    tab.pendingRun?.let { run -> ScriptDialog(app, run, onClose = { tab.pendingRun = null }) }
}

@Composable
private fun CompareBar(tab: SchemaCompareTab) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SidePicker("Source", tab.source, tab)
        Tooltip({ Text("Swap source and target") }) {
            IconButton(onClick = tab::swap, enabled = !tab.comparing) { Icon(AllIconsKeys.Diff.ArrowLeftRight, "Swap") }
        }
        SidePicker("Target", tab.target, tab)
        Box(Modifier.weight(1f))
        if (tab.comparing) {
            Progress(tab, Modifier)
            OutlinedButton(onClick = tab::cancel) { Text("Cancel") }
        } else {
            if (tab.isStale) Text("Target changed since the comparison", color = WarningColor)
            DefaultButton(onClick = tab::compare, enabled = tab.canCompare) { Text(if (tab.comparison == null) "Compare" else "Compare Again") }
        }
    }
}

@Composable
private fun SidePicker(label: String, side: CompareSide, tab: SchemaCompareTab) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = JewelTheme.globalColors.text.info)
        Picker(
            label = side.config?.name ?: "Choose connection",
            placeholder = side.config == null,
            options = tab.candidates.map { it.name to it },
            onPick = { side.pick(it) },
            modifier = Modifier.width(170.dp),
        )
        side.config?.let { EnvironmentChip(it) }
        if (side.namespaces.size > 1 || side.loadingNamespaces) {
            Picker(
                label = side.namespace?.displayName ?: if (side.loadingNamespaces) "Loading…" else "Choose schema",
                placeholder = side.namespace == null,
                options = side.namespaces.map { it.displayName to it },
                onPick = { side.namespace = it },
                modifier = Modifier.width(130.dp),
            )
        }
        side.error?.let { message ->
            Tooltip({ Text(message) }) { Icon(AllIconsKeys.General.Error, message) }
        }
    }
}

@Composable
private fun Progress(tab: SchemaCompareTab, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        fun part(name: String, side: CompareSide) = side.progress?.let { (done, total) -> "$name $done/$total" }
        val detail = listOfNotNull(part("source", tab.source), part("target", tab.target)).joinToString(" · ")
        Text(if (detail.isEmpty()) "Reading schemas…" else "Reading tables: $detail", color = JewelTheme.globalColors.text.info)
    }
}

// region Differences

@Composable
private fun DiffList(tab: SchemaCompareTab) {
    val comparison = tab.comparison ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${comparison.diffs.size} table(s) differ", fontWeight = FontWeight.SemiBold)
            if (comparison.identical > 0) Text("${comparison.identical} identical", color = JewelTheme.globalColors.text.info)
            Box(Modifier.weight(1f))
            Text("Select", color = JewelTheme.globalColors.text.info)
            Tooltip({ Text("Everything except drops") }) { Link("Safe", onClick = { tab.selectAll(on = true, includeDrops = false) }) }
            Tooltip({ Text("Including drops, which lose data") }) { Link("All", onClick = { tab.selectAll(on = true, includeDrops = true) }) }
            Link("None", onClick = { tab.selectAll(on = false, includeDrops = true) })
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            for (kind in TableDiffKind.entries) {
                val diffs = comparison.diffs.filter { it.kind == kind }
                if (diffs.isEmpty()) continue
                item(key = "header:$kind") { GroupHeader(kind, diffs.size) }
                items(diffs, key = { it.id }) { diff ->
                    Column {
                        TableRow(tab, diff)
                        if (diff.kind == TableDiffKind.Changed && diff.id in tab.expanded) diff.items.forEach { ItemRow(tab, diff, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(kind: TableDiffKind, count: Int) {
    val (title, hint) =
        when (kind) {
            TableDiffKind.Added -> "Only in source" to "created in the target"
            TableDiffKind.Changed -> "Different" to "altered in the target"
            TableDiffKind.Removed -> "Only in target" to "dropped from the target if selected"
        }
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = JewelTheme.globalColors.text.info)
        Text("$count · $hint", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun TableRow(tab: SchemaCompareTab, diff: TableDiff) {
    val selected = tab.selectedCount(diff)
    val total = if (diff.kind == TableDiffKind.Changed) diff.items.size else 1
    val state =
        when (selected) {
            0 -> ToggleableState.Off
            total -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    Row(
        Modifier.fillMaxWidth().height(30.dp).clickable(enabled = diff.kind == TableDiffKind.Changed) { tab.toggleExpanded(diff) }.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (diff.kind == TableDiffKind.Changed) Icon(if (diff.id in tab.expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, null, Modifier.size(12.dp))
        }
        TriStateCheckbox(state, onClick = { tab.toggleTable(diff, state != ToggleableState.On) })
        Icon(AllIconsKeys.Nodes.DataTables, null)
        Text(diff.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (diff.kind) {
            TableDiffKind.Added -> Tag("CREATE", AddedColor)
            TableDiffKind.Removed -> Tag("DROP", RemovedColor)
            TableDiffKind.Changed -> Tag("${diff.items.size} CHANGE${if (diff.items.size == 1) "" else "S"}", ChangedColor)
        }
    }
}

@Composable
private fun ItemRow(tab: SchemaCompareTab, diff: TableDiff, item: DiffItem) {
    val key = diff.itemKey(item)
    val (sign, color) =
        when (item) {
            is DiffItem.ColumnAdded, is DiffItem.IndexAdded, is DiffItem.ForeignKeyAdded -> "+" to AddedColor
            is DiffItem.ColumnRemoved, is DiffItem.IndexRemoved, is DiffItem.ForeignKeyRemoved -> "−" to RemovedColor
            else -> "~" to ChangedColor
        }
    Row(
        Modifier.fillMaxWidth().clickable { tab.toggle(key, !tab.isSelected(key)) }.padding(start = 34.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(tab.isSelected(key), { tab.toggle(key, it) })
        Text(sign, Modifier.width(10.dp), color = color, fontWeight = FontWeight.Bold)
        Text(item.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.destructive) Tooltip({ Text("Loses data or structure; not selected by default") }) { Icon(AllIconsKeys.General.Warning, "Destructive", Modifier.size(14.dp)) }
    }
}

// endregion

@Composable
private fun MigrationPane(tab: SchemaCompareTab) {
    val script by remember(tab) { derivedStateOf { tab.script() } }
    val dialect = tab.targetDialect ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Migration for ${tab.target.label}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${script.statements.size} statement(s)", color = JewelTheme.globalColors.text.info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        script.warnings.forEach { Notice(it, NoticeKind.Warning, Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
        val text = remember { TextFieldState() }
        LaunchedEffect(script) { text.setTextAndPlaceCursorAtEnd(script.text) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (script.isEmpty) {
                Text("Select differences on the left to build the migration.", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
            } else {
                SqlEditor(text, dialect.syntax, tab.editorFontSize, Modifier.fillMaxSize(), readOnly = true)
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(transactionNote(script, dialect), Modifier.weight(1f), color = JewelTheme.globalColors.text.info, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            OutlinedButton(onClick = { copyToClipboard(script.text) }, enabled = !script.isEmpty) { Text("Copy") }
            OutlinedButton(onClick = { chooseSaveFile("migration.sql")?.let(tab::saveScript) }, enabled = !script.isEmpty) { Text("Save…") }
            OutlinedButton(onClick = tab::openInConsole, enabled = !script.isEmpty) { Text("Open in Console") }
            DefaultButton(onClick = tab::requestApply, enabled = !script.isEmpty && !tab.isStale && tab.target.config?.readOnly != true) { Text("Run…") }
        }
    }
}

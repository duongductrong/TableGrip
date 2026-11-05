package dev.tablegrip.app.table

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.filter.CompiledProjection
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.FunctionArgument
import dev.tablegrip.core.filter.ProjectionCompiler
import dev.tablegrip.core.filter.SelectFunction
import dev.tablegrip.core.filter.ValueKind
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** What every row of the select editor needs: the tab, its typed columns and the live compile result. */
private class SelectContext(val tab: TableDataTab, val columns: List<FilterColumn>, val draft: CompiledProjection) {
    val select: SelectBuilderState
        get() = tab.select

    /** A problem is shown once the user tried to apply, or as soon as there's something typed it's about. */
    fun issue(item: SelectItemState): String? {
        val issue = draft.issues[item.id] ?: return null
        val typed =
            when (item) {
                is SelectFieldState -> item.argument.text.isNotEmpty() || item.alias.text.isNotEmpty()
                is SelectExpressionState -> item.sql.text.isNotBlank()
            }
        return issue.takeIf { tab.showSelectIssues || typed }
    }
}

/**
 * The table viewer's SELECT: a summary line with Apply while a select list is applied or being edited, and below it,
 * when open, one row per column to show, hide, reorder, pass through a function and rename.
 */
@Composable
internal fun SelectBar(tab: TableDataTab) {
    val draft by remember(tab) { derivedStateOf { tab.compileSelectDraft() } }
    val context = SelectContext(tab, tab.filterColumns, draft)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = BarInset),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.Nodes.DataColumn, null)
            Tooltip({ Text(if (tab.showSelect) "Hide the column editor" else "Choose columns and functions") }) {
                ViewChip("Columns", tab.showSelect, onClick = tab::toggleSelect)
            }
            Separator()
            SelectPreview(draft, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SelectActions(tab, draft)
        }
        if (tab.showSelect) {
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            SelectBody(context)
        }
    }
}

/** The select list the editor compiles to; copyable. */
@Composable
private fun SelectPreview(draft: CompiledProjection, modifier: Modifier) {
    val info = JewelTheme.globalColors.text.info
    val clause = draft.projection?.clause
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val text = "SELECT " + (clause ?: "*")
        Tooltip({ Text(text, style = JewelTheme.editorTextStyle) }, Modifier.weight(1f, fill = false)) {
            Text(text, color = info, maxLines = 1, overflow = TextOverflow.Ellipsis, style = JewelTheme.editorTextStyle, fontSize = 11.sp)
        }
        if (clause != null) {
            Tooltip({ Text("Copy select list") }) { IconButton(onClick = { copyToClipboard(clause) }) { Icon(AllIconsKeys.Actions.Copy, "Copy") } }
        }
    }
}

/** The select list's state, then Reset and Apply, laid out like the filter's. */
@Composable
private fun SelectActions(tab: TableDataTab, draft: CompiledProjection) {
    val invalid = !draft.isValid && tab.showSelectIssues
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (invalid) {
            val count = draft.issues.size
            FilterStatus(if (count == 1) "1 column needs attention" else "$count columns need attention", JewelTheme.globalColors.text.error)
        } else if (tab.isSelectDirty) {
            FilterStatus("Not applied", LocalIslands.current.accent, JewelTheme.globalColors.text.info)
        } else if (!tab.selectEditable) {
            Tooltip({ Text("Functions, new names and DISTINCT show values that aren't the table's own, so rows can't be edited") }) {
                FilterStatus("Read-only", JewelTheme.globalColors.text.info)
            }
        }
        if (tab.appliedProjection != null || tab.isSelectDirty) {
            Tooltip({ Text("Show every column again (SELECT *)") }) { Link("Reset", onClick = tab::resetSelect) }
        }
        Tooltip({ Text("Apply columns (⏎ in any field)") }) {
            DefaultButton(onClick = tab::applySelect, enabled = tab.isSelectDirty || invalid) { Text("Apply") }
        }
    }
}

@Composable
private fun SelectBody(context: SelectContext) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(BarInset)) {
            SelectCard(context)
        }
        VerticalScrollbar(scroll, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp, horizontal = 2.dp))
    }
}

/** The select list in a card like the Filter's: a title with what's shown and what can be added, then the rows. */
@Composable
private fun SelectCard(context: SelectContext) {
    val select = context.select
    val tab = context.tab
    val islands = LocalIslands.current
    val shape = RoundedCornerShape(6.dp)
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, islands.divider, shape)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.Nodes.DataColumn, null)
            Text("Select", fontWeight = FontWeight.SemiBold)
            CountBadge(select.items.count { it.enabled })
            Spacer(Modifier.width(4.dp))
            Tooltip({ Text("Only unique rows (SELECT DISTINCT); rows become read-only") }) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(select.distinct, { select.distinct = it })
                    Text("Distinct rows", color = info)
                }
            }
            Box(Modifier.weight(1f))
            Link("Show all", onClick = { select.showAll(true) })
            Link("Hide all", onClick = { select.showAll(false) })
            Tooltip({ Text("Another column, e.g. to show one both as is and through a function") }) {
                Link("+ Column", onClick = { select.addField(tab.defaultFilterColumn()) })
            }
            Tooltip({ Text("A hand-written SQL expression, e.g. price * quantity or CASE WHEN … END") }) {
                Link("+ SQL", onClick = { select.addExpression() })
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (select.items.isEmpty()) Hint("No columns listed: every column is shown. Add one, or Reset to list them all.")
            context.draft.issues[0L]?.takeIf { tab.showSelectIssues || select.items.isNotEmpty() }?.let { IssueText(it) }
            select.items.forEachIndexed { index, item ->
                key(item.id) {
                    when (item) {
                        is SelectFieldState -> FieldRow(context, item, index)
                        is SelectExpressionState -> ExpressionRow(context, item, index)
                    }
                }
            }
            if (select.items.isNotEmpty()) {
                Hint("Hidden columns stay listed, so they can be shown again. Editing rows needs plain columns with their own names.")
            }
        }
    }
}

@Composable
private fun FieldRow(context: SelectContext, field: SelectFieldState, index: Int) {
    val tab = context.tab
    val column = context.columns.firstOrNull { it.name == field.column }
    val kind = column?.kind ?: ValueKind.Text
    val issue = context.issue(field)
    var columnOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(field.enabled, { field.enabled = it })
            MoveButtons(context, field, index)
            Row(
                Modifier.weight(1f).alpha(if (field.enabled) 1f else 0.5f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColumnPicker(
                    context.columns,
                    field.column,
                    error = issue != null && column == null,
                    open = columnOpen,
                    onOpenChange = { columnOpen = it },
                    onDone = {},
                    Modifier.width(200.dp),
                ) {
                    context.select.setColumn(field, it, tab.dialect)
                }
                FunctionPicker(kind, field.function, tab, Modifier.width(130.dp)) { context.select.setFunction(field, it) }
                field.function.argument?.let { ArgumentEditor(tab, field, it, error = issue != null && column != null) }
                Text("AS", color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
                TextField(
                    field.alias,
                    Modifier.weight(1f),
                    textStyle = JewelTheme.editorTextStyle,
                    placeholder = { Text(field.function.outputName(field.column.ifEmpty { "column" }), maxLines = 1) },
                    onKeyboardAction = KeyboardActionHandler { tab.applySelect() },
                )
            }
            Tooltip({ Text("Duplicate, e.g. to show this column through another function too") }) {
                IconButton(onClick = { context.select.duplicate(field) }) { Icon(AllIconsKeys.Actions.Copy, "Duplicate") }
            }
            RemoveButton("Remove from the list") { context.select.remove(field) }
        }
        issue?.let { IssueText(it, Modifier.padding(start = 28.dp)) }
    }
}

@Composable
private fun ExpressionRow(context: SelectContext, expression: SelectExpressionState, index: Int) {
    val tab = context.tab
    val issue = context.issue(expression)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(expression.enabled, { expression.enabled = it })
            MoveButtons(context, expression, index)
            Row(
                Modifier.weight(1f).alpha(if (expression.enabled) 1f else 0.5f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tag("SQL", Color(0xFFCF8E6D))
                TextField(
                    expression.sql,
                    Modifier.weight(2f),
                    textStyle = JewelTheme.editorTextStyle,
                    outline = if (issue != null) Outline.Error else Outline.None,
                    placeholder = { Text("any SQL expression, e.g. price * quantity") },
                    onKeyboardAction = KeyboardActionHandler { tab.applySelect() },
                )
                Text("AS", color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
                TextField(
                    expression.alias,
                    Modifier.weight(1f),
                    textStyle = JewelTheme.editorTextStyle,
                    placeholder = { Text(ProjectionCompiler.defaultAlias(expression.sql.text.toString()).ifEmpty { "name" }, maxLines = 1) },
                    onKeyboardAction = KeyboardActionHandler { tab.applySelect() },
                )
            }
            RemoveButton("Remove from the list") { context.select.remove(expression) }
        }
        issue?.let { IssueText(it, Modifier.padding(start = 28.dp)) }
    }
}

@Composable
private fun MoveButtons(context: SelectContext, item: SelectItemState, index: Int) {
    val last = context.select.items.lastIndex
    Row {
        Tooltip({ Text("Move left in the grid") }) {
            IconButton(onClick = { context.select.move(item, -1) }, enabled = index > 0) { Icon(AllIconsKeys.General.ArrowUp, "Move up") }
        }
        Tooltip({ Text("Move right in the grid") }) {
            IconButton(onClick = { context.select.move(item, 1) }, enabled = index < last) { Icon(AllIconsKeys.General.ArrowDown, "Move down") }
        }
    }
}

/** Functions for the column's type, in sections, as in the filter's operator picker. */
@Composable
private fun FunctionPicker(kind: ValueKind, selected: SelectFunction, tab: TableDataTab, modifier: Modifier, onSelect: (SelectFunction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PickerButton(error = false, modifier, onClick = { open = true }) {
            Text(selected.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(AllIconsKeys.General.ChevronDown, null)
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                SelectFunction.forKind(kind, tab.dialect).forEachIndexed { index, section ->
                    if (index > 0) separator()
                    section.forEach { function ->
                        selectableItem(selected = function == selected, onClick = { onSelect(function); open = false }) { Text(function.label) }
                    }
                }
            }
        }
    }
}

/** The function's argument: a unit to pick for truncation, else typed. */
@Composable
private fun ArgumentEditor(tab: TableDataTab, field: SelectFieldState, argument: FunctionArgument, error: Boolean) {
    Text(argument.label, color = JewelTheme.globalColors.text.info)
    if (argument == FunctionArgument.Bucket) {
        var open by remember { mutableStateOf(false) }
        val current = TimeBucket.of(field.argument.text.toString()) ?: TimeBucket.Day
        Box {
            PickerButton(error, Modifier.width(100.dp), onClick = { open = true }) {
                Text(current.label.lowercase(), Modifier.weight(1f), maxLines = 1)
                Icon(AllIconsKeys.General.ChevronDown, null)
            }
            if (open) {
                PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                    TimeBucket.entries.forEach { bucket ->
                        selectableItem(selected = bucket == current, onClick = {
                            field.argument.setTextAndPlaceCursorAtEnd(bucket.name.lowercase())
                            open = false
                        }) { Text(bucket.label.lowercase()) }
                    }
                }
            }
        }
    } else {
        TextField(
            field.argument,
            Modifier.width(110.dp),
            textStyle = JewelTheme.editorTextStyle,
            outline = if (error) Outline.Error else Outline.None,
            placeholder = { Text(argument.placeholder) },
            onKeyboardAction = KeyboardActionHandler { tab.applySelect() },
        )
    }
}

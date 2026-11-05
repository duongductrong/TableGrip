package dev.tablegrip.app.schema

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.console.SqlEditor
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.core.metadata.TableRef
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/** Columns, indexes and foreign keys of a table as editable lists, with the DDL they amount to below. */
@Composable
internal fun TableDesignerView(app: AppState, tab: TableDesignerTab) {
    val issues by remember(tab) { derivedStateOf { tab.issues() } }
    val script by remember(tab) { derivedStateOf { tab.script() } }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // ⌘S / Ctrl+S applies, like saving a file.
            val apply = event.type == KeyEventType.KeyDown && event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed)
            if (apply) tab.requestApply()
            apply
        },
    ) {
        DesignerToolbar(tab, issues.isEmpty(), !script.isEmpty)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }
        if (tab.loading) {
            Box(Modifier.fillMaxSize()) {
                ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, "Reading ${tab.tableRef?.name}…", Modifier.align(Alignment.Center))
            }
            return@Column
        }
        VerticalSplitLayout(
            first = { DesignerLists(tab, issues) },
            second = { DdlPreview(app, tab, script.text, script.warnings, issues) },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = 120.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.62f),
        )
    }
    tab.pendingRun?.let { run -> ScriptDialog(app, run, onClose = { tab.pendingRun = null }) }
    tab.pendingClose?.let { close -> DiscardDesignDialog(tab, close) }
}

@Composable
private fun DesignerToolbar(tab: TableDesignerTab, valid: Boolean, changed: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Table", color = JewelTheme.globalColors.text.info)
        TextField(
            tab.tableName,
            Modifier.width(220.dp),
            placeholder = { Text("table_name") },
            outline = if (tab.tableName.text.isBlank()) Outline.Error else Outline.None,
        )
        Separator()
        ToolButton(AllIconsKeys.General.Add, "Add column") { tab.addColumn() }
        Separator()
        ToolButton(AllIconsKeys.Actions.Rollback, "Revert all changes", enabled = changed && tab.original != null) { tab.revert() }
        ToolButton(AllIconsKeys.Actions.Refresh, "Reload from the database", enabled = tab.original != null) { tab.load() }
        Box(Modifier.weight(1f))
        if (tab.config.readOnly) Text("read-only connection", color = JewelTheme.globalColors.text.info)
        EnvironmentChip(tab.config)
        Text(
            when {
                !changed && tab.original != null -> "No changes"
                !valid -> "Fix the marked problems to apply"
                else -> ""
            },
            color = JewelTheme.globalColors.text.info,
        )
        DefaultButton(onClick = tab::requestApply, enabled = valid && changed && !tab.config.readOnly) {
            Text(if (tab.original == null) "Create Table" else "Apply…")
        }
    }
}

@Composable
private fun DesignerLists(tab: TableDesignerTab, issues: List<DesignIssue>) {
    Column(Modifier.fillMaxSize()) {
        TabStrip(
            tabs =
                DesignerSection.entries.map { section ->
                    val count =
                        when (section) {
                            DesignerSection.Columns -> tab.columns.count { !it.dropped }
                            DesignerSection.Indexes -> tab.indexes.count { !it.dropped }
                            DesignerSection.ForeignKeys -> tab.foreignKeys.count { !it.dropped }
                        }
                    val problems = issues.count { it.section == section }
                    TabData.Default(
                        selected = section == tab.section,
                        content = { state -> SimpleTabContent(if (problems > 0) "${section.label} $count  ⚠ $problems" else "${section.label} $count", state) },
                        closable = false,
                        onClick = { tab.section = section },
                    )
                },
            style = JewelTheme.defaultTabStyle,
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        val rowIssues = issues.filter { it.rowId != null }.groupBy { it.rowId }
        when (tab.section) {
            DesignerSection.Columns -> ColumnsList(tab, rowIssues)
            DesignerSection.Indexes -> IndexesList(tab, rowIssues)
            DesignerSection.ForeignKeys -> ForeignKeysList(tab, rowIssues)
        }
    }
}

// region Columns

@Composable
private fun ColumnsList(tab: TableDesignerTab, issues: Map<Long?, List<DesignIssue>>) {
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        ListHeader {
            Box(Modifier.width(MarkerWidth))
            HeaderCell("Name", Modifier.weight(1.3f))
            HeaderCell("Type", Modifier.weight(1.2f))
            HeaderCell("Default", Modifier.weight(1f))
            HeaderCell("Null", Modifier.width(CheckWidth))
            HeaderCell("PK", Modifier.width(CheckWidth))
            HeaderCell("Auto", Modifier.width(CheckWidth))
            Box(Modifier.width(ActionsWidth))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            items(tab.columns, key = { it.id }) { column -> ColumnRow(tab, column, issues[column.id].orEmpty()) }
            item { AddRowLink("Add column", onClick = { tab.addColumn() }, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp)) }
        }
    }
    // Scroll to a column just added.
    LaunchedEffect(tab.focusRow) {
        val index = tab.columns.indexOfFirst { it.id == tab.focusRow }
        if (index >= 0) state.animateScrollToItem(index)
    }
}

@Composable
private fun ColumnRow(tab: TableDesignerTab, column: DesignerColumn, issues: List<DesignIssue>) {
    val marker =
        when {
            column.dropped -> RemovedColor
            column.isNew -> AddedColor
            column.isModified -> ChangedColor
            else -> Color.Transparent
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(RowHeight).padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(MarkerWidth).fillMaxHeight().padding(vertical = 4.dp).background(marker))
            if (column.dropped) {
                Text(
                    column.original?.name.orEmpty(),
                    Modifier.weight(1.3f).padding(horizontal = 6.dp),
                    textDecoration = TextDecoration.LineThrough,
                    color = JewelTheme.globalColors.text.info,
                )
                Text(column.original?.type.orEmpty(), Modifier.weight(2.2f), color = JewelTheme.globalColors.text.info, textDecoration = TextDecoration.LineThrough)
                Text("will be dropped", color = RemovedColor)
                Link("Restore", onClick = { tab.restoreColumn(column) })
                Box(Modifier.width(8.dp))
                return@Row
            }
            val focus = remember { FocusRequester() }
            LaunchedEffect(tab.focusRow) {
                if (tab.focusRow == column.id) {
                    focus.requestFocus()
                    tab.focusRow = null
                }
            }
            val nameProblem = issues.any { "name" in it.message || "named" in it.message }
            TextField(column.name, Modifier.weight(1.3f).focusRequester(focus), outline = if (nameProblem) Outline.Error else Outline.None, placeholder = { Text("name") })
            TypeField(column.type, tab.commonTypes, Modifier.weight(1.2f), error = issues.any { "type" in it.message })
            TextField(column.defaultValue, Modifier.weight(1f), placeholder = { Text("none", color = JewelTheme.globalColors.text.info) })
            CheckCell(column.nullable && !column.primaryKey, enabled = !column.primaryKey, tooltip = "Allows NULL") { column.nullable = it }
            CheckCell(column.primaryKey, tooltip = "Part of the primary key") {
                column.primaryKey = it
                if (it) column.nullable = false
            }
            CheckCell(column.autoIncrement, enabled = column.isNew || tab.original == null, tooltip = if (column.isNew) "Auto-increment" else "Auto-increment can only be set on new columns") { column.autoIncrement = it }
            Row(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End) {
                SmallIcon(AllIconsKeys.General.ArrowUp, "Move up") { tab.moveColumn(column, -1) }
                SmallIcon(AllIconsKeys.General.ArrowDown, "Move down") { tab.moveColumn(column, 1) }
                SmallIcon(AllIconsKeys.General.Delete, if (column.isNew) "Remove" else "Drop column") { tab.removeColumn(column) }
            }
        }
        IssueLines(issues)
    }
}

/**
 * A type field with the engine's common types a click away: the chevron sits inside the field, like a combo box, and the
 * list opens under the field at its width.
 */
@Composable
private fun TypeField(state: TextFieldState, types: List<String>, modifier: Modifier, error: Boolean) {
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { fieldWidth = it.width }) {
        TextField(
            state,
            Modifier.fillMaxWidth(),
            outline = if (error) Outline.Error else Outline.None,
            placeholder = { Text("type") },
            trailingIcon = {
                Tooltip({ Text("Common types") }) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { open = !open },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AllIconsKeys.General.ChevronDown, "Common types", Modifier.size(12.dp))
                    }
                }
            },
        )
        if (open) {
            PopupMenu(
                onDismissRequest = { open = false; true },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.widthIn(min = with(density) { fieldWidth.toDp() }),
            ) {
                types.forEach { type ->
                    selectableItem(selected = state.text.toString().equals(type, ignoreCase = true), onClick = {
                        open = false
                        state.setTextAndPlaceCursorAtEnd(type)
                    }) { Text(type) }
                }
            }
        }
    }
}

// endregion

// region Indexes

@Composable
private fun IndexesList(tab: TableDesignerTab, issues: Map<Long?, List<DesignIssue>>) {
    Column(Modifier.fillMaxSize()) {
        if (tab.indexes.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("No indexes", "Indexes speed up lookups and can enforce uniqueness.", Modifier.align(Alignment.Center)) {
                    OutlinedButton(onClick = tab::addIndex) { Text("Add Index") }
                }
            }
            return
        }
        ListHeader {
            Box(Modifier.width(MarkerWidth))
            HeaderCell("Name", Modifier.weight(1f))
            HeaderCell("Columns", Modifier.weight(1.6f))
            HeaderCell("Unique", Modifier.width(56.dp))
            Box(Modifier.width(ActionsWidth))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(tab.indexes, key = { it.id }) { index ->
                val changed = index.original != null && (index.original.name != index.name.text.toString() || index.original.unique != index.unique)
                Column {
                    Row(Modifier.fillMaxWidth().height(RowHeight).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(MarkerWidth).fillMaxHeight().padding(vertical = 4.dp).background(rowMarker(index.original == null, index.dropped, changed)))
                        if (index.dropped) {
                            Text(index.original?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 6.dp), textDecoration = TextDecoration.LineThrough, color = JewelTheme.globalColors.text.info)
                            Text("will be dropped", Modifier.weight(1.6f), color = RemovedColor)
                            Link("Restore", onClick = { index.dropped = false })
                            return@Row
                        }
                        TextField(index.name, Modifier.weight(1f), outline = if (issues[index.id]?.any { "name" in it.message } == true) Outline.Error else Outline.None)
                        ColumnChips(
                            selected = index.columns.map(tab::columnName),
                            options = tab.columns.filter { !it.dropped && it.id !in index.columns }.map { it.name.text.toString() to it.id },
                            onAdd = { index.columns += it },
                            onRemove = { index.columns.removeAt(it) },
                            modifier = Modifier.weight(1.6f),
                        )
                        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Checkbox(index.unique, { index.unique = it }) }
                        Row(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End) {
                            SmallIcon(AllIconsKeys.General.Delete, if (index.original == null) "Remove" else "Drop index") { tab.removeIndex(index) }
                        }
                    }
                    IssueLines(issues[index.id].orEmpty())
                }
            }
            item { AddRowLink("Add index", onClick = tab::addIndex, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp)) }
        }
    }
}

// endregion

// region Foreign keys

@Composable
private fun ForeignKeysList(tab: TableDesignerTab, issues: Map<Long?, List<DesignIssue>>) {
    Column(Modifier.fillMaxSize()) {
        if (tab.foreignKeys.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("No foreign keys", "A foreign key makes sure a column only holds values that exist in another table.", Modifier.align(Alignment.Center)) {
                    OutlinedButton(onClick = tab::addForeignKey) { Text("Add Foreign Key") }
                }
            }
            return
        }
        ListHeader {
            Box(Modifier.width(MarkerWidth))
            HeaderCell("Name", Modifier.weight(1f))
            HeaderCell("Columns", Modifier.weight(1f))
            HeaderCell("References", Modifier.weight(0.9f))
            HeaderCell("Referenced columns", Modifier.weight(1f))
            HeaderCell("On update", Modifier.width(ActionWidth))
            HeaderCell("On delete", Modifier.width(ActionWidth))
            Box(Modifier.width(ActionsWidth))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(tab.foreignKeys, key = { it.id }) { fk -> ForeignKeyRow(tab, fk, issues[fk.id].orEmpty()) }
            item { AddRowLink("Add foreign key", onClick = tab::addForeignKey, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp)) }
        }
    }
}

@Composable
private fun ForeignKeyRow(tab: TableDesignerTab, fk: DesignerForeignKey, issues: List<DesignIssue>) {
    Column {
        Row(Modifier.fillMaxWidth().height(RowHeight).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val original = fk.original
            val changed =
                original != null &&
                    (original.name != fk.name.text.toString() || original.referencedTable != fk.referencedTable || original.referencedColumns != fk.referencedColumns.toList() ||
                        (original.onUpdate ?: "NO ACTION") != fk.onUpdate || (original.onDelete ?: "NO ACTION") != fk.onDelete)
            Box(Modifier.width(MarkerWidth).fillMaxHeight().padding(vertical = 4.dp).background(rowMarker(original == null, fk.dropped, changed)))
            if (fk.dropped) {
                Text(original?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 6.dp), textDecoration = TextDecoration.LineThrough, color = JewelTheme.globalColors.text.info)
                Text("will be dropped", Modifier.weight(2.9f), color = RemovedColor)
                Link("Restore", onClick = { fk.dropped = false })
                return@Row
            }
            TextField(fk.name, Modifier.weight(1f), outline = if (issues.any { "name" in it.message }) Outline.Error else Outline.None, placeholder = { Text("unnamed", color = JewelTheme.globalColors.text.info) })
            ColumnChips(
                selected = fk.columns.map(tab::columnName),
                options = tab.columns.filter { !it.dropped && it.id !in fk.columns }.map { it.name.text.toString() to it.id },
                onAdd = { fk.columns += it },
                onRemove = { fk.columns.removeAt(it) },
                modifier = Modifier.weight(1f),
            )
            Picker(
                label = fk.referencedTable?.name ?: "Choose table",
                placeholder = fk.referencedTable == null,
                options = tab.tables.map { it.name to it },
                onPick = { tab.setReferencedTable(fk, it) },
                modifier = Modifier.weight(0.9f),
            )
            val target = fk.referencedTable
            ColumnChips(
                selected = fk.referencedColumns.toList(),
                options = target?.let { tab.referencedColumns(it) }.orEmpty().filter { it !in fk.referencedColumns }.map { it to it },
                onAdd = { fk.referencedColumns += it },
                onRemove = { fk.referencedColumns.removeAt(it) },
                modifier = Modifier.weight(1f),
            )
            Picker(fk.onUpdate, placeholder = false, options = ReferentialActions.map { it to it }, onPick = { fk.onUpdate = it }, modifier = Modifier.width(ActionWidth))
            Picker(fk.onDelete, placeholder = false, options = ReferentialActions.map { it to it }, onPick = { fk.onDelete = it }, modifier = Modifier.width(ActionWidth))
            Row(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End) {
                SmallIcon(AllIconsKeys.General.Delete, if (original == null) "Remove" else "Drop foreign key") { tab.removeForeignKey(fk) }
            }
        }
        IssueLines(issues)
    }
}

// endregion

@Composable
private fun DdlPreview(app: AppState, tab: TableDesignerTab, ddl: String, warnings: List<String>, issues: List<DesignIssue>) {
    Column(Modifier.fillMaxSize()) {
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SQL preview", fontWeight = FontWeight.SemiBold)
            Text(transactionNote(tab.script(), tab.dialect), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Link("Open in Console", onClick = { app.workspace.openConsole(tab.config, ddl) }, enabled = ddl.isNotBlank())
        }
        // Table-wide problems; row problems show on their rows.
        issues.filter { it.rowId == null }.forEach { Notice(it.message, NoticeKind.Error, Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
        warnings.forEach { Notice(it, NoticeKind.Warning, Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
        val text = remember { TextFieldState() }
        LaunchedEffect(ddl) { if (text.text.toString() != ddl) text.setTextAndPlaceCursorAtEnd(ddl) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ddl.isBlank()) {
                Text(
                    if (tab.original == null) "Add columns to see the CREATE TABLE statement." else "No changes yet. Edit a column, index or foreign key to see the statements.",
                    Modifier.align(Alignment.Center),
                    color = JewelTheme.globalColors.text.info,
                )
            } else {
                SqlEditor(text, tab.dialect.syntax, tab.editorFontSize, Modifier.fillMaxSize(), readOnly = true)
            }
        }
    }
}

@Composable
private fun DiscardDesignDialog(tab: TableDesignerTab, close: () -> Unit) {
    AppDialog(onCloseRequest = { tab.pendingClose = null }, size = DpSize(460.dp, 170.dp), title = "Unapplied Changes", resizable = false) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${tab.title} has changes that haven't been applied to the database. Discard them?")
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingClose = null }) { Text("Keep Editing") }
                DefaultButton(onClick = {
                    tab.pendingClose = null
                    close()
                }) { Text("Discard") }
            }
        }
    }
}

// region Pieces

@Composable
private fun ListHeader(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CheckCell(checked: Boolean, enabled: Boolean = true, tooltip: String, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(CheckWidth), contentAlignment = Alignment.Center) {
        Tooltip({ Text(tooltip) }) { Checkbox(checked, onChange, enabled = enabled) }
    }
}

@Composable
private fun SmallIcon(icon: org.jetbrains.jewel.ui.icon.IconKey, tooltip: String, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) { IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) { Icon(icon, tooltip, Modifier.size(14.dp)) } }
}

@Composable
private fun IssueLines(issues: List<DesignIssue>) {
    issues.forEach { issue ->
        Text(issue.message, Modifier.padding(start = MarkerWidth + 10.dp, bottom = 4.dp), color = JewelTheme.globalColors.text.error, fontSize = 12.sp)
    }
}

private fun rowMarker(new: Boolean, dropped: Boolean, changed: Boolean): Color =
    when {
        dropped -> RemovedColor
        new -> AddedColor
        changed -> ChangedColor
        else -> Color.Transparent
    }

/** Chosen columns as removable chips, and a + that offers the rest. */
@Composable
private fun <T> ColumnChips(selected: List<String>, options: List<Pair<String, T>>, onAdd: (T) -> Unit, onRemove: (Int) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected.isEmpty()) Text("none", color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(start = 4.dp))
        selected.forEachIndexed { i, name ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.35f)).padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name.ifEmpty { "?" }, maxLines = 1)
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).clickable { onRemove(i) }, contentAlignment = Alignment.Center) {
                    Icon(AllIconsKeys.Actions.Close, "Remove $name", Modifier.size(10.dp))
                }
            }
        }
        if (options.isNotEmpty()) {
            Box {
                Tooltip({ Text("Add column") }) {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(22.dp)) { Icon(AllIconsKeys.General.Add, "Add column", Modifier.size(12.dp)) }
                }
                if (open) {
                    PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                        options.forEach { (label, value) ->
                            selectableItem(selected = false, onClick = {
                                open = false
                                onAdd(value)
                            }) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

/** A flat dropdown: the current choice and a chevron; the list opens below. */
@Composable
internal fun <T> Picker(label: String, placeholder: Boolean, options: List<Pair<String, T>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(4.dp))
                .background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.18f))
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (placeholder) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal)
            Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(10.dp))
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start, modifier = Modifier.widthIn(min = 160.dp)) {
                if (options.isEmpty()) passiveItem { Text("Nothing to choose", Modifier.padding(8.dp), color = JewelTheme.globalColors.text.info) }
                options.forEach { (text, value) ->
                    selectableItem(selected = text == label, onClick = {
                        open = false
                        onPick(value)
                    }) { Text(text) }
                }
            }
        }
    }
}

private val RowHeight = 36.dp
private val MarkerWidth = 3.dp
private val CheckWidth = 44.dp
private val ActionsWidth = 80.dp
private val ActionWidth = 110.dp

// endregion

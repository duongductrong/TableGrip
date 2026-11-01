package dev.datagrok.app.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import dev.datagrok.app.completion.CompletionController
import dev.datagrok.app.completion.CompletionPopup
import dev.datagrok.app.completion.handleCompletionKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.app.grid.DataGrid
import dev.datagrok.app.grid.GridActions
import dev.datagrok.app.grid.RecordEditorHost
import dev.datagrok.app.grid.ResultJsonView
import dev.datagrok.app.grid.ValuePanel
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.copyToClipboard
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import dev.datagrok.app.connections.ConnectionLoadingPlaceholder

internal val PageSizes = listOf(50, 100, 200, 500, 1000)

@Composable
internal fun TableDataView(tab: TableDataTab) {
    // Tabs reopened from the last session load the first time they're shown.
    LaunchedEffect(tab, tab.view) {
        if (tab.view == TableView.Data && tab.model == null && !tab.loading && tab.error == null) tab.showData()
        if (tab.view == TableView.Structure && tab.structure == null && !tab.structureLoading && tab.structureError == null) tab.loadStructure()
    }
    if (tab.view == TableView.Structure) {
        Column(Modifier.fillMaxSize()) {
            StructureToolbar(tab)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            tab.structureError?.let { ErrorBanner(it, onDismiss = { tab.structureError = null }) }
            StructureView(tab, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        DataView(tab)
    }
    tab.pendingDiscard?.let { DiscardDialog(tab) }
    tab.preview?.let { PreviewDialog(tab, it) }
    tab.model?.let { RecordEditorHost(it, tab.grid, tab.dialect.qualifiedName(tab.table)) }
}

@Composable
private fun DataView(tab: TableDataTab) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        DataContent(tab)
        ColumnDragGhost(tab.columnDrag, tab.builder.root, origin)
    }
}

@Composable
private fun DataContent(tab: TableDataTab) {
    // Filter keys the grid and the bar left alone; the window handles them too, for focus outside the table.
    Column(Modifier.fillMaxSize().onKeyEvent { it.type == KeyEventType.KeyDown && handleFilterShortcut(tab, it) }) {
        Toolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        FilterBar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }

        val model = tab.model
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model == null) {
                if (tab.error == null) ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, "Loading ${tab.table.name}…", Modifier.align(Alignment.Center))
                return@Box
            }
            val grid =
                @Composable {
                    DataGrid(
                        model = model,
                        state = tab.grid,
                        sort = tab.sort,
                        firstRowNumber = tab.firstRowNumber,
                        actions =
                            GridActions(
                                onSort = tab::setSort,
                                onFilterByValue = tab::filterByValue,
                                onSubmit = tab::submit,
                                dialect = tab.dialect,
                                tableName = tab.dialect.qualifiedName(tab.table),
                                linkedColumns = tab.linkedColumns,
                                onFollowLink = tab::followLink,
                                relatedRows = tab::referencingRows,
                                onMention = tab::mentionCells.takeIf { tab.canMention },
                                onColumnDragOut = tab.columnDrag.takeIf { tab.filterMode == FilterMode.Builder },
                            ),
                    )
                }
            if (tab.showJson) {
                ResultJsonView(model, tab.grid)
            } else if (tab.showValuePanel) {
                HorizontalSplitLayout(
                    first = { grid() },
                    second = { ValuePanel(model, tab.grid) },
                    modifier = Modifier.fillMaxSize(),
                    firstPaneMinWidth = 300.dp,
                    secondPaneMinWidth = 220.dp,
                    state = rememberSplitLayoutState(0.72f),
                )
            } else {
                grid()
            }
        }
        StatusLine(tab)
    }
}

/** Switches the tab between its rows and its structure; leads both toolbars so it stays in one place. */
@Composable
private fun ViewSwitch(tab: TableDataTab) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ViewChip("Data", tab.view == TableView.Data, onClick = tab::showData)
        ViewChip("Structure", tab.view == TableView.Structure, onClick = { tab.showStructure() })
    }
    Separator()
}

@Composable
internal fun ViewChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val islands = LocalIslands.current
    val shape = RoundedCornerShape(5.dp)
    Text(
        label,
        Modifier.clip(shape)
            .background(if (selected) islands.accent.copy(alpha = if (JewelTheme.isDark) 0.22f else 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        color = if (selected) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
    )
}

/** Frames mutually exclusive [ViewChip]s as one segmented control, so they read as a single switch. */
@Composable
internal fun ViewChipGroup(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.border(1.dp, JewelTheme.globalColors.borders.normal, shape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun StructureToolbar(tab: TableDataTab) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewSwitch(tab)
        ToolButton(AllIconsKeys.Actions.Refresh, "Reload structure (${Shortcuts.Reload})") { tab.loadStructure() }
        val ddl = tab.structure?.ddl
        ToolButton(AllIconsKeys.Actions.Copy, "Copy DDL", enabled = ddl != null) { ddl?.let(::copyToClipboard) }
        ToolButton(AllIconsKeys.Nodes.Console, "Open DDL in new console", enabled = ddl != null) { tab.openDdlInConsole() }
        if (!tab.config.readOnly) ToolButton(AllIconsKeys.Actions.Edit, "Edit structure") { tab.editStructure() }
        ToolButton(AllIconsKeys.FileTypes.Diagram, "Show in ER diagram") { tab.showInDiagram() }
        Box(Modifier.weight(1f))
        if (tab.structureLoading) Text("Loading…", Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        Text(tab.dialect.qualifiedName(tab.table), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun Toolbar(tab: TableDataTab) {
    val model = tab.model
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewSwitch(tab)
        ToolButton(AllIconsKeys.Actions.Play_first, "First page", enabled = tab.page > 0) { tab.goToPage(0) }
        ToolButton(AllIconsKeys.Actions.Play_back, "Previous page", enabled = tab.page > 0) { tab.goToPage(tab.page - 1) }
        val shown = (model?.rowCount ?: 0)
        Text(if (shown == 0) "0 rows" else "${tab.firstRowNumber}–${tab.firstRowNumber + shown - 1}", Modifier.padding(horizontal = 4.dp))
        Text("of", color = JewelTheme.globalColors.text.info)
        val total = tab.totalRows
        if (total == null) Link("?", onClick = tab::countRows) else Text(total.toString())
        ToolButton(AllIconsKeys.Actions.Play_forward, "Next page", enabled = tab.hasNextPage) { tab.goToPage(tab.page + 1) }
        ListComboBox(
            items = PageSizes.map { "$it rows" },
            selectedIndex = PageSizes.indexOf(tab.pageSize).coerceAtLeast(0),
            onSelectedItemChange = { tab.setPageSize(PageSizes[it]) },
            modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
        )
        ToolButton(AllIconsKeys.Actions.Refresh, "Reload (${Shortcuts.Reload})") { tab.reload() }
        Separator()

        val editable = model?.editor != null
        ToolButton(AllIconsKeys.General.Add, "Add row (${Shortcuts.AddRow})", enabled = editable) {
            model?.let { tab.grid.revealEditable(it, it.addRow()) }
        }
        ToolButton(AllIconsKeys.General.Remove, "Delete selected rows (${Shortcuts.DeleteRows})", enabled = editable && tab.grid.selection != null) {
            tab.grid.selection?.rows?.let { model?.deleteRows(it.toList()) }
            model?.let { tab.grid.clamp(it.rowCount) }
        }
        ToolButton(AllIconsKeys.Actions.Rollback, "Revert all changes", enabled = tab.hasChanges) { tab.revert() }
        ToolButton(AllIconsKeys.Actions.Preview, "Preview pending SQL", enabled = tab.hasChanges) { tab.openPreview() }
        ToolButton(AllIconsKeys.Actions.Commit, "Submit changes (${Shortcuts.Submit})", enabled = tab.hasChanges && !tab.submitting) { tab.submit() }
        if (tab.hasChanges) Text("${model?.changeCount} pending", Modifier.padding(start = 4.dp), color = JewelTheme.globalColors.text.info)

        Box(Modifier.weight(1f))
        if (tab.loading || tab.submitting) Text(if (tab.submitting) "Saving…" else "Loading…", Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        ViewChipGroup {
            ViewChip("Grid", !tab.showJson) { tab.showJson = false }
            ViewChip("JSON", tab.showJson) { tab.showJson = true }
        }
        Separator()
        ToolButton(AllIconsKeys.Actions.Download, "Export…", enabled = model != null) { tab.export() }
        ToolButton(
            AllIconsKeys.Actions.PreviewDetails,
            if (tab.showValuePanel) "Hide value panel" else "Show value panel",
            enabled = !tab.showJson,
        ) {
            tab.showValuePanel = !tab.showValuePanel
        }
    }
}

@Composable
internal fun ToolButton(icon: IconKey, tooltip: String, enabled: Boolean = true, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) { IconButton(onClick = onClick, enabled = enabled) { Icon(icon, tooltip) } }
}

@Composable
internal fun Separator() {
    Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 4.dp))
}

@Composable
private fun FilterBar(tab: TableDataTab) {
    if (tab.filterMode == FilterMode.Builder) BuilderFilterBar(tab) else SqlFilterBar(tab)
}

@Composable
private fun SqlFilterBar(tab: TableDataTab) {
    val scope = rememberCoroutineScope()
    val controller = remember(tab) { CompletionController(scope) }
    var trigger by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val charWidth = rememberTextMeasurer().let { measurer ->
        val style = JewelTheme.editorTextStyle
        remember(style, density) { measurer.measure("0123456789", style).size.width / 10f }
    }

    LaunchedEffect(tab) {
        snapshotFlow { tab.filter.text.toString() to tab.filter.selection }.collect {
            if (trigger || controller.isActive) controller.update(tab.filter, tab.filterCompletion, explicit = false)
            trigger = false
        }
    }
    LaunchedEffect(tab.schemaVersion) { if (controller.isActive) controller.update(tab.filter, tab.filterCompletion, explicit = false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(tab.focusSqlFilter) {
        if (tab.focusSqlFilter) {
            tab.focusSqlFilter = false
            runCatching { focus.requestFocus() }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).onPreviewKeyEvent { handleFilterShortcut(tab, it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Filter, null)
        Spacer(Modifier.width(6.dp))
        FilterModeSwitch(tab)
        Text("WHERE", Modifier.padding(horizontal = 8.dp), color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
        Box(Modifier.weight(1f).onSizeChanged { fieldWidth = it.width }) {
            TextField(
                tab.filter,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (handleCompletionKey(controller, event.key) { controller.accept(tab.filter) }) return@onPreviewKeyEvent true
                    if (Shortcuts.ApplyFilter.matches(event)) {
                        tab.applyFilter()
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Escape) {
                        tab.grid.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                    if (event.isCtrlPressed && event.key == Key.Spacebar) {
                        controller.update(tab.filter, tab.filterCompletion, explicit = true)
                        return@onPreviewKeyEvent true
                    }
                    val code = event.utf16CodePoint
                    if (code > 0 && !event.isMetaPressed && !event.isCtrlPressed) {
                        val char = code.toChar()
                        if (char.isLetterOrDigit() || char in "_.' ") trigger = true
                    }
                    if (event.key == Key.Backspace) trigger = controller.isOpen
                    false
                },
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text("status = 'active' AND created_at > '2026-01-01'   ${Shortcuts.Completion} for suggestions, ⏎ to apply") },
                onKeyboardAction = KeyboardActionHandler {
                    controller.close()
                    tab.applyFilter()
                },
            )
            // The field scrolls long filters; keep the popup within it.
            val x = with(density) { 10.dp.toPx() } + charWidth * (controller.result?.replaceStart ?: 0)
            CompletionPopup(controller, IntOffset(x.toInt().coerceAtMost((fieldWidth - 200).coerceAtLeast(0)), with(density) { 30.dp.roundToPx() })) {
                controller.accept(tab.filter, it)
            }
        }
        Spacer(Modifier.width(12.dp))
        FilterActions(tab)
    }
}

@Composable
internal fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(JewelTheme.globalColors.text.error.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Error, null)
        Text(message, Modifier.weight(1f), color = JewelTheme.globalColors.text.error, maxLines = 4)
        Link("Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun StatusLine(tab: TableDataTab) {
    val model = tab.model ?: return
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = JewelTheme.globalColors.text.info
        val selection = tab.grid.selection
        if (selection != null) {
            val columns = selection.columns(tab.grid.visibleColumns.size)
            Text("${selection.rows.count()} × ${columns.count()} selected", color = info)
        }
        when {
            tab.config.readOnly -> Text("Read-only connection", color = info)
            !model.hasKey -> Text("No primary key: existing rows are read-only", color = info)
        }
        Box(Modifier.weight(1f))
        Text(tab.dialect.qualifiedName(tab.table), color = info)
    }
}

@Composable
private fun DiscardDialog(tab: TableDataTab) {
    AppDialog(
        onCloseRequest = { tab.pendingDiscard = null },
        size = DpSize(420.dp, 170.dp),
        title = "Unsaved Changes",
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${tab.model?.changeCount ?: 0} change(s) to ${tab.table.name} haven't been submitted. Submit them before continuing?")
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingDiscard = null }) { Text("Keep Editing") }
                OutlinedButton(onClick = tab::confirmDiscard) { Text("Discard") }
                DefaultButton(onClick = tab::submitAndContinue) { Text("Submit") }
            }
        }
    }
}

@Composable
private fun PreviewDialog(tab: TableDataTab, sql: String) {
    AppDialog(
        onCloseRequest = tab::closePreview,
        size = DpSize(720.dp, 420.dp),
        title = "Pending Changes",
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val text = remember(sql) { TextFieldState(sql) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Text("Runs in one transaction; nothing is saved if any statement fails or hits an unexpected number of rows.", color = JewelTheme.globalColors.text.info)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = tab::closePreview) { Text("Close") }
                DefaultButton(onClick = tab::submitNow) { Text("Submit") }
            }
        }
    }
}

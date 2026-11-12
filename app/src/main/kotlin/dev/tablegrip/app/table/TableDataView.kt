package dev.tablegrip.app.table

import dev.tablegrip.core.i18n.tr
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
import dev.tablegrip.app.completion.CompletionController
import dev.tablegrip.app.completion.CompletionPopup
import dev.tablegrip.app.completion.handleCompletionKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.ReviewChangesPopup
import dev.tablegrip.app.console.rememberHighlightedSql
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridModel
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.grid.RecordEditorHost
import dev.tablegrip.app.grid.ResultJsonView
import dev.tablegrip.app.grid.ValuePanel
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.copyToClipboard
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
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder

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
    var height by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().onSizeChanged { height = it.height }.onKeyEvent { it.type == KeyEventType.KeyDown && handleFilterShortcut(tab, it) }) {
        Toolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        // The select list's bar shows while it's being edited or differs from SELECT *.
        if (tab.showSelect || tab.appliedProjection != null) {
            SelectBar(tab)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        }
        // The filter can be dragged taller, but always leaves the grid room for a few rows.
        FilterBar(tab, maxBodyHeight = with(LocalDensity.current) { height.toDp() } - GridReserve)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }

        val model = tab.model
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model == null) {
                if (tab.error == null) ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, tr("Loading {0}…", tab.table.name), Modifier.align(Alignment.Center))
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
        ViewChip(tr("Data"), tab.view == TableView.Data, onClick = tab::showData)
        ViewChip(tr("Structure"), tab.view == TableView.Structure, onClick = { tab.showStructure() })
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
        maxLines = 1,
        softWrap = false,
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
        ToolButton(AllIconsKeys.Actions.Refresh, tr("Reload structure ({0})", Shortcuts.Reload)) { tab.loadStructure() }
        val ddl = tab.structure?.ddl
        ToolButton(AllIconsKeys.Actions.Copy, tr("Copy DDL"), enabled = ddl != null) { ddl?.let(::copyToClipboard) }
        ToolButton(AllIconsKeys.Nodes.Console, tr("Open DDL in new console"), enabled = ddl != null) { tab.openDdlInConsole() }
        if (!tab.config.readOnly) ToolButton(AllIconsKeys.Actions.Edit, tr("Edit structure")) { tab.editStructure() }
        ToolButton(AllIconsKeys.FileTypes.Diagram, tr("Show in ER diagram")) { tab.showInDiagram() }
        Box(Modifier.weight(1f))
        if (tab.structureLoading) Text(tr("Loading…"), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
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
        ToolButton(AllIconsKeys.Actions.Play_first, tr("First page"), enabled = tab.page > 0) { tab.goToPage(0) }
        ToolButton(AllIconsKeys.Actions.Play_back, tr("Previous page"), enabled = tab.page > 0) { tab.goToPage(tab.page - 1) }
        val shown = (model?.rowCount ?: 0)
        Text(if (shown == 0) tr("0 rows") else "${tab.firstRowNumber}–${tab.firstRowNumber + shown - 1}", Modifier.padding(horizontal = 4.dp))
        Text(tr("of"), color = JewelTheme.globalColors.text.info)
        val total = tab.totalRows
        if (total == null) Link("?", onClick = tab::countRows) else Text(total.toString())
        ToolButton(AllIconsKeys.Actions.Play_forward, tr("Next page"), enabled = tab.hasNextPage) { tab.goToPage(tab.page + 1) }
        ToolButton(AllIconsKeys.Actions.Play_last, tr("Last page"), enabled = tab.hasNextPage) { tab.goToLastPage() }
        ListComboBox(
            items = PageSizes.map { tr("{0} rows", it) },
            selectedIndex = PageSizes.indexOf(tab.pageSize).coerceAtLeast(0),
            onSelectedItemChange = { tab.setPageSize(PageSizes[it]) },
            modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
        )
        ToolButton(AllIconsKeys.Actions.Refresh, tr("Reload ({0})", Shortcuts.Reload)) { tab.reload() }
        Separator()

        val editable = model?.editor != null
        ToolButton(AllIconsKeys.General.Add, tr("Add row ({0})", Shortcuts.AddRow), enabled = editable) {
            model?.let { tab.grid.revealEditable(it, it.addRow()) }
        }
        ToolButton(AllIconsKeys.General.Remove, tr("Delete selected rows ({0})", Shortcuts.DeleteRows), enabled = editable && tab.grid.selection != null) {
            tab.grid.selection?.rows?.let { model?.deleteRows(it.toList()) }
            model?.let { tab.grid.clamp(it.rowCount) }
        }
        UndoRedoButtons(tab.grid, model)
        ToolButton(AllIconsKeys.Actions.Rollback, tr("Revert all changes"), enabled = tab.hasChanges) { tab.revert() }
        ToolButton(AllIconsKeys.Actions.Preview, tr("Preview pending SQL"), enabled = tab.hasChanges) { tab.openPreview() }
        Box {
            ToolButton(AllIconsKeys.Actions.Commit, tr("Submit changes ({0})", Shortcuts.Submit), enabled = tab.hasChanges && !tab.submitting) { tab.submit() }
            tab.preview?.let { ReviewPopup(tab, it) }
        }
        if (tab.hasChanges) Text(tr("{0} pending", model?.changeCount), Modifier.padding(start = 4.dp), color = JewelTheme.globalColors.text.info)

        Box(Modifier.weight(1f))
        if (tab.loading || tab.submitting) Text(if (tab.submitting) tr("Saving…") else tr("Loading…"), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        ViewChipGroup {
            ViewChip(tr("Grid"), !tab.showJson) { tab.showJson = false }
            ViewChip(tr("JSON"), tab.showJson) { tab.showJson = true }
        }
        Separator()
        ToolButton(AllIconsKeys.Nodes.DataColumn, if (tab.showSelect) tr("Hide column editor") else tr("Choose columns and functions (SELECT)")) { tab.toggleSelect() }
        ToolButton(AllIconsKeys.Actions.Download, tr("Export…"), enabled = model != null) { tab.export() }
        ToolButton(
            AllIconsKeys.Actions.PreviewDetails,
            if (tab.showValuePanel) tr("Hide value panel") else tr("Show value panel"),
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

/** Undo and redo of [model]'s pending edits, naming what each would undo or redo. */
@Composable
internal fun UndoRedoButtons(grid: DataGridState, model: GridModel?) {
    val history = model?.editor?.history
    val undo = history?.nextUndo
    val redo = history?.nextRedo
    ToolButton(AllIconsKeys.Actions.Undo, undo?.let { tr("Undo {0} ({1})", it.label, Shortcuts.Undo) } ?: tr("Undo ({0})", Shortcuts.Undo), enabled = undo != null) {
        model?.let(grid::undo)
    }
    ToolButton(AllIconsKeys.Actions.Redo, redo?.let { tr("Redo {0} ({1})", it.label, Shortcuts.Redo) } ?: tr("Redo ({0})", Shortcuts.Redo), enabled = redo != null) {
        model?.let(grid::redo)
    }
}

@Composable
internal fun Separator() {
    Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 4.dp))
}

@Composable
private fun FilterBar(tab: TableDataTab, maxBodyHeight: Dp) {
    if (tab.filterMode == FilterMode.Builder) BuilderFilterBar(tab, maxBodyHeight) else SqlFilterBar(tab)
}

/** Height kept below the filter body for the toolbars, the grid's header and a few rows, and the status line. */
private val GridReserve = 260.dp

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
                placeholder = { Text(tr("status = 'active' AND created_at > '2026-01-01'   {0} for suggestions, ⏎ to apply", Shortcuts.Completion)) },
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
        Link(tr("Dismiss"), onClick = onDismiss)
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
            Text(tr("{0} × {1} selected", selection.rows.count(), columns.count()), color = info)
        }
        when {
            tab.config.readOnly -> Text(tr("Read-only connection"), color = info)
            !tab.selectEditable -> Text(tr("Computed columns: rows are read-only"), color = info)
            !model.hasKey && tab.details?.primaryKey?.isNotEmpty() == true -> Text(tr("Primary key not selected: existing rows are read-only"), color = info)
            !model.hasKey -> Text(tr("No primary key: existing rows are read-only"), color = info)
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
        title = tr("Unsaved Changes"),
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("{0} change(s) to {1} haven't been submitted. Submit them before continuing?", tab.model?.changeCount ?: 0, tab.table.name))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingDiscard = null }) { Text(tr("Keep Editing")) }
                OutlinedButton(onClick = tab::confirmDiscard) { Text(tr("Discard")) }
                DefaultButton(onClick = tab::submitAndContinue) { Text(tr("Submit")) }
            }
        }
    }
}

/** The pending SQL under the Submit button, to check before it runs. */
@Composable
private fun ReviewPopup(tab: TableDataTab, sql: String) {
    ReviewChangesPopup(
        changeCount = tab.model?.changeCount ?: 0,
        target = "${tab.table.name} · ${tab.config.name}",
        statements = rememberHighlightedSql(sql, tab.dialect.syntax),
        note = tr("Runs in one transaction; nothing is saved if any statement fails or hits an unexpected number of rows."),
        canSkip = tab.canSkipReview,
        onCancel = tab::closePreview,
        onSubmit = tab::submitReviewed,
    )
}

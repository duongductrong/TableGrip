package dev.tablegrip.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.PageSizes
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.i18n.tr
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Laid out like a table's data view: toolbar, query bar, the records (grid, JSON or definition) and a status line. */
@Composable
internal fun RecordBrowserView(tab: RecordBrowserTab) {
    // The record panel follows the grid's selection.
    LaunchedEffect(tab) {
        snapshotFlow { tab.grid.selection?.focus?.row to tab.showRecord }.distinctUntilChanged().collect { (row, shown) -> if (row != null && shown) tab.open(row) }
    }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Toolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        QueryBar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.message?.let { message ->
            if (message.isError) ErrorBanner(message.text, onDismiss = { tab.message = null })
            else Text(message.text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = JewelTheme.globalColors.text.info, maxLines = 2)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val content = @Composable { Records(tab) }
            if (tab.showRecord && tab.view != RecordBrowserTab.View.Definition) {
                HorizontalSplitLayout(
                    first = { content() },
                    second = { RecordPanel(tab) },
                    modifier = Modifier.fillMaxSize(),
                    firstPaneMinWidth = 300.dp,
                    secondPaneMinWidth = 260.dp,
                    state = rememberSplitLayoutState(0.62f),
                )
            } else content()
        }
        StatusLine(tab)
    }
    tab.pendingDelete?.let { DeleteDialog(tab, it.size) }
}

@Composable
private fun Records(tab: RecordBrowserTab) {
    Box(Modifier.fillMaxSize()) {
        when {
            tab.view == RecordBrowserTab.View.Definition -> TextPane(tab.definition, tr("Loading the definition…"))
            tab.loading && tab.records.isEmpty() ->
                ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, tr("Loading {0}…", tab.container.name), Modifier.align(Alignment.Center))
            tab.records.isEmpty() -> Text(if (tab.appliedQuery.isEmpty()) tr("No records.") else tr("No records match."), Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
            tab.view == RecordBrowserTab.View.Json -> TextPane(tab.pageJson, "")
            else ->
                DataGrid(
                    tab.model,
                    tab.grid,
                    firstRowNumber = tab.firstRow + 1,
                    actions = GridActions(onSubmit = { tab.grid.selection?.focus?.row?.let { tab.open(it) } }),
                )
        }
    }
}

/** Read-only text that can be selected and copied: the definition, or the page as JSON. */
@Composable
private fun TextPane(text: String?, loading: String) {
    if (text == null) {
        Text(loading, Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
        return
    }
    SelectionContainer {
        Text(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), style = JewelTheme.editorTextStyle)
    }
}

@Composable
private fun QueryBar(tab: RecordBrowserTab) {
    val info = JewelTheme.globalColors.text.info
    val language = tab.language
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Tooltip({ Text(language?.help ?: "") }) { Text(language?.name ?: tr("Query"), Modifier.padding(end = 8.dp), color = info, style = JewelTheme.editorTextStyle) }
        TextField(
            tab.query,
            Modifier.weight(1f).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    Shortcuts.ApplyFilter.matches(event) || ((event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed) -> {
                        tab.reload()
                        true
                    }
                    event.key == Key.Escape -> {
                        tab.grid.requestFocus()
                        true
                    }
                    else -> false
                }
            },
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text(language?.placeholder.orEmpty()) },
        )
        Spacer(Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.isQueryDirty) Text(tr("Not applied"), color = info)
            if (tab.appliedQuery.isNotEmpty() || tab.isQueryDirty) Link(tr("Clear"), onClick = tab::clearQuery)
            DefaultButton(onClick = tab::reload, enabled = tab.isQueryDirty) { Text(tr("Apply")) }
        }
    }
}

@Composable
private fun Toolbar(tab: RecordBrowserTab) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        val idle = !tab.loading
        val browsing = tab.view != RecordBrowserTab.View.Definition
        ToolButton(AllIconsKeys.Actions.Play_first, tr("First page"), enabled = idle && tab.pageIndex > 0) { tab.firstPage() }
        ToolButton(AllIconsKeys.Actions.Play_back, tr("Previous page"), enabled = idle && tab.pageIndex > 0) { tab.previousPage() }
        Text(if (tab.records.isEmpty()) tr("0 records") else "${tab.firstRow + 1}–${tab.firstRow + tab.records.size}", Modifier.padding(horizontal = 4.dp))
        tab.total?.let {
            Text(tr("of"), color = JewelTheme.globalColors.text.info)
            Text(if (tab.totalIsLowerBound) "$it+" else it.toString())
        }
        ToolButton(AllIconsKeys.Actions.Play_forward, tr("Next page"), enabled = idle && tab.hasMore) { tab.nextPage() }
        ListComboBox(
            items = PageSizes.map { tr("{0} records", it) },
            selectedIndex = PageSizes.indexOf(tab.pageSize).coerceAtLeast(0),
            onSelectedItemChange = { tab.setPageSize(PageSizes[it]) },
            modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
        )
        ToolButton(AllIconsKeys.Actions.Refresh, tr("Reload ({0})", Shortcuts.Reload)) { if (browsing) tab.refresh() else tab.reloadDefinition() }
        Separator()
        ToolButton(AllIconsKeys.General.Add, tr("Add record"), enabled = tab.canWrite && browsing) { tab.newRecord() }
        ToolButton(AllIconsKeys.General.Remove, tr("Delete selected records"), enabled = tab.canWrite && browsing && tab.selectedRecords.any { it.key != null } && !tab.saving) {
            tab.requestDelete()
        }
        ToolButton(AllIconsKeys.Actions.Edit, tr("Show the selected record"), enabled = browsing) {
            if (tab.showRecord) tab.showRecord = false else tab.grid.selection?.focus?.row?.let { tab.open(it) } ?: run { tab.showRecord = true }
        }
        Separator()
        ToolButton(AllIconsKeys.Nodes.Console, tr("Open a console here")) { tab.openConsole() }
        Box(Modifier.weight(1f))
        if (tab.loading || tab.saving) Text(if (tab.saving) tr("Saving…") else tr("Loading…"), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        ViewChipGroup {
            ViewChip(tr("Grid"), tab.view == RecordBrowserTab.View.Grid) { tab.view = RecordBrowserTab.View.Grid }
            ViewChip(tr("JSON"), tab.view == RecordBrowserTab.View.Json) { tab.view = RecordBrowserTab.View.Json }
            ViewChip(tr("Definition"), tab.view == RecordBrowserTab.View.Definition) { tab.showDefinition() }
        }
    }
}

/** The selected (or new) record as JSON, to edit and save, revert or delete. */
@Composable
private fun RecordPanel(tab: RecordBrowserTab) {
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val record = tab.editing
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    record == null -> tr("No record selected")
                    tab.editingIsNew -> tr("New record")
                    else -> RecordBrowserTab.describe(record) ?: tr("Record")
                },
                Modifier.weight(1f),
                maxLines = 1,
            )
            ToolButton(AllIconsKeys.Actions.Close, tr("Close the record panel")) { tab.showRecord = false }
        }
        if (record == null) {
            Text(tr("Select a row to see its record, or add one."), color = info)
            return@Column
        }
        TextArea(
            tab.editor,
            Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (Shortcuts.Save.matches(event) || Shortcuts.Submit.matches(event))) {
                    tab.saveRecord()
                    true
                } else false
            },
            textStyle = JewelTheme.editorTextStyle,
            readOnly = !tab.canWrite || (record.key == null && !tab.editingIsNew),
        )
        if (tab.editingIsNew) Text(tr("Fill in the fields and save; the engine's own rules decide its key."), color = info, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DefaultButton(onClick = tab::saveRecord, enabled = tab.canWrite && !tab.saving && (tab.editorDirty || tab.editingIsNew)) { Text(tr("Save")) }
            OutlinedButton(onClick = tab::revertRecord, enabled = tab.editorDirty) { Text(tr("Revert")) }
            Box(Modifier.weight(1f))
            if (!tab.editingIsNew && record.key != null) OutlinedButton(onClick = { tab.pendingDelete = listOf(record) }, enabled = tab.canWrite && !tab.saving) { Text(tr("Delete")) }
        }
    }
}

@Composable
private fun StatusLine(tab: RecordBrowserTab) {
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = JewelTheme.globalColors.text.info
        val selection = tab.grid.selection
        if (selection != null && tab.view == RecordBrowserTab.View.Grid) Text(tr("{0} selected", selection.rows.count()), color = info)
        if (tab.config.readOnly) Text(tr("Read-only connection"), color = info)
        Box(Modifier.weight(1f))
        Text("${tab.config.type.displayName} · ${tab.title}", color = info)
    }
}

@Composable
private fun DeleteDialog(tab: RecordBrowserTab, count: Int) {
    AppDialog(onCloseRequest = { tab.pendingDelete = null }, size = DpSize(420.dp, 160.dp), title = tr("Delete Records"), resizable = false) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Delete {0} record(s) from {1}? This can't be undone.", count, tab.title))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingDelete = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = tab::confirmDelete) { Text(tr("Delete")) }
            }
        }
    }
}

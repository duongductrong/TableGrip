package dev.datagrok.app.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.datagrok.app.completion.CompletionController
import dev.datagrok.app.completion.CompletionPopup
import dev.datagrok.app.completion.CompletionSource
import dev.datagrok.app.completion.handleCompletionKey
import dev.datagrok.app.connections.ConnectionLoadingPlaceholder
import dev.datagrok.app.grid.DataGrid
import dev.datagrok.app.grid.GridActions
import dev.datagrok.app.grid.GridCell
import dev.datagrok.app.table.PageSizes
import dev.datagrok.app.table.Separator
import dev.datagrok.app.table.ToolButton
import dev.datagrok.app.table.ErrorBanner
import dev.datagrok.app.table.ViewChip
import dev.datagrok.app.table.ViewChipGroup
import dev.datagrok.app.grid.ResultJsonView
import dev.datagrok.app.grid.ValuePanel
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.core.completion.DocumentCompleter
import dev.datagrok.core.completion.DocumentQuery
import dev.datagrok.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Laid out like a table's data view: toolbar, filter bar, the grid or JSON, and a status line. */
@Composable
internal fun DocumentBrowserView(tab: DocumentBrowserTab) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Toolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        FilterBar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.message?.let { message ->
            if (message.isError) ErrorBanner(message.text, onDismiss = { tab.message = null })
            else Text(message.text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = JewelTheme.globalColors.text.info, maxLines = 2)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val model = tab.model
            when {
                tab.loading && tab.documents.isEmpty() ->
                    ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, "Loading ${tab.collection}…", Modifier.align(Alignment.Center))
                tab.showJson -> ResultJsonView(model, tab.grid)
                model.rowCount == 0 -> Text("No documents match.", Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
                else -> {
                    val grid =
                        @Composable {
                            DataGrid(
                                model,
                                tab.grid,
                                sort = tab.sortColumns,
                                firstRowNumber = tab.skip + 1,
                                actions = GridActions(onSort = tab::setSort, onFilterByValue = tab::filterByValue, onSubmit = tab::submit),
                            )
                        }
                    if (tab.showValuePanel) {
                        HorizontalSplitLayout(
                            first = { grid() },
                            second = { ValuePanel(model, tab.grid) },
                            modifier = Modifier.fillMaxSize(),
                            firstPaneMinWidth = 300.dp,
                            secondPaneMinWidth = 220.dp,
                            state = rememberSplitLayoutState(0.72f),
                        )
                    } else grid()
                }
            }
        }
        StatusLine(tab)
    }
    tab.pendingDiscard?.let { DiscardDialog(tab) }
    tab.preview?.let { PreviewDialog(tab, it) }
}

/**
 * Filter and sort as shell-style JSON, each completing fields, operators and values from the documents seen so far;
 * Enter or [Shortcuts.ApplyFilter] applies both.
 */
@Composable
private fun FilterBar(tab: DocumentBrowserTab) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AllIconsKeys.General.Filter, null)
        Text("Filter", Modifier.padding(horizontal = 8.dp), color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
        QueryField(tab, tab.filter, DocumentQuery.Filter, "{status: 'A', total: {\$gt: 10}}   ${Shortcuts.Completion} for suggestions, ⏎ to apply", Modifier.weight(1f))
        Text("Sort", Modifier.padding(horizontal = 8.dp), color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
        QueryField(tab, tab.sort, DocumentQuery.Sort, "{total: -1}", Modifier.width(220.dp))
        Spacer(Modifier.width(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.isFilterDirty) Text("Not applied", color = JewelTheme.globalColors.text.info)
            if (tab.hasAppliedFilter || tab.isFilterDirty) Link("Clear", onClick = tab::clearFilter)
            Tooltip({ Text("Apply filter and sort (⏎ in a field, ${Shortcuts.ApplyFilter})") }) {
                DefaultButton(onClick = tab::reload, enabled = tab.isFilterDirty) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun QueryField(tab: DocumentBrowserTab, state: TextFieldState, query: DocumentQuery, placeholder: String, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val controller = remember(tab, state) { CompletionController(scope) }
    val source = remember(tab, query) {
        CompletionSource(complete = { text, caret -> DocumentCompleter.complete(text, caret, query, tab.completionFields) }, dialect = SqlDialect.Generic, cache = null)
    }
    // A pending trigger from the last key: null for none, else whether to open even with nothing typed yet.
    var trigger by remember { mutableStateOf<Boolean?>(null) }
    var fieldWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val charWidth = rememberTextMeasurer().let { measurer ->
        val style = JewelTheme.editorTextStyle
        remember(style, density) { measurer.measure("0123456789", style).size.width / 10f }
    }
    LaunchedEffect(controller) {
        snapshotFlow { state.text.toString() to state.selection }.collect {
            val explicit = trigger
            if (explicit != null || controller.isActive) controller.update(state, source, explicit = explicit == true)
            trigger = null
        }
    }
    // Fields learnt from a newly loaded page or the sample show up in a list that's already open.
    LaunchedEffect(tab.schema.version) { if (controller.isActive) controller.update(state, source, explicit = false) }

    /** Inserts the highlighted item and, after a key or an opening bracket, goes straight on to what can follow it. */
    fun accept(index: Int = controller.selected) {
        controller.accept(state, index)
        val before = state.text.substring(0, state.selection.start)
        // The text change about to be observed reopens the list, even with nothing typed yet.
        if (before.endsWith(": ") || before.endsWith("[") || before.endsWith("{")) trigger = true
    }

    Box(modifier.onSizeChanged { fieldWidth = it.width }) {
        TextField(
            state,
            Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // ⌘Enter always applies; plain Enter picks the highlighted suggestion first, if any.
                if (Shortcuts.ApplyFilter.matches(event)) {
                    controller.close()
                    tab.reload()
                    return@onPreviewKeyEvent true
                }
                if (handleCompletionKey(controller, event.key) { accept() }) return@onPreviewKeyEvent true
                if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed && !event.isAltPressed) {
                    controller.close()
                    tab.reload()
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.Escape) {
                    tab.grid.requestFocus()
                    return@onPreviewKeyEvent true
                }
                if (event.isCtrlPressed && event.key == Key.Spacebar) {
                    controller.update(state, source, explicit = true)
                    return@onPreviewKeyEvent true
                }
                val code = event.utf16CodePoint
                if (code > 0 && !event.isMetaPressed && !event.isCtrlPressed) {
                    val char = code.toChar()
                    val previous = state.text.getOrNull(state.selection.start - 1)
                    when {
                        char in "{[,:" || (char == ' ' && previous in listOf(':', ',', '{', '[')) -> trigger = true
                        char.isLetterOrDigit() || char in "_$.'\"-" -> trigger = false
                    }
                }
                if (event.key == Key.Backspace && controller.isOpen) trigger = false
                false
            },
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text(placeholder) },
        )
        // The field scrolls long text; keep the popup within it.
        val x = with(density) { 10.dp.toPx() } + charWidth * (controller.result?.replaceStart ?: 0)
        CompletionPopup(controller, IntOffset(x.toInt().coerceAtMost((fieldWidth - 200).coerceAtLeast(0)), with(density) { 30.dp.roundToPx() })) { accept(it) }
    }
}

/** In the order of a table's toolbar: paging, editing, then the views. */
@Composable
private fun Toolbar(tab: DocumentBrowserTab) {
    val model = tab.model
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val idle = !tab.loading
        ToolButton(AllIconsKeys.Actions.Play_first, "First page", enabled = idle && tab.skip > 0) { tab.firstPage() }
        ToolButton(AllIconsKeys.Actions.Play_back, "Previous page", enabled = idle && tab.skip > 0) { tab.previousPage() }
        Text(if (tab.documents.isEmpty()) "0 docs" else "${tab.skip + 1}–${tab.skip + tab.documents.size}", Modifier.padding(horizontal = 4.dp))
        Text("of", color = JewelTheme.globalColors.text.info)
        if (tab.total == null) Link("?", onClick = { tab.count() }) else Text(tab.total.toString())
        ToolButton(AllIconsKeys.Actions.Play_forward, "Next page", enabled = idle && tab.hasMore) { tab.nextPage() }
        ListComboBox(
            items = PageSizes.map { "$it docs" },
            selectedIndex = PageSizes.indexOf(tab.pageSize).coerceAtLeast(0),
            onSelectedItemChange = { tab.setPageSize(PageSizes[it]) },
            modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
        )
        ToolButton(AllIconsKeys.Actions.Refresh, "Reload (${Shortcuts.Reload})") { tab.refresh() }
        Separator()

        val editable = model.editor != null && !tab.showJson
        ToolButton(AllIconsKeys.General.Add, "Add document (${Shortcuts.AddRow})", enabled = editable) { tab.grid.reveal(GridCell(model.addRow(), 0)) }
        ToolButton(AllIconsKeys.General.Remove, "Delete selected documents (${Shortcuts.DeleteRows})", enabled = editable && tab.grid.selection != null) {
            tab.grid.selection?.rows?.let { model.deleteRows(it.toList()) }
            tab.grid.clamp(model.rowCount)
        }
        AddField(tab, enabled = editable)
        ToolButton(AllIconsKeys.Actions.Rollback, "Revert all changes", enabled = tab.hasChanges) { tab.revert() }
        ToolButton(AllIconsKeys.Actions.Preview, "Preview pending writes", enabled = tab.hasChanges) { tab.openPreview() }
        ToolButton(AllIconsKeys.Actions.Commit, "Submit changes (${Shortcuts.Submit})", enabled = tab.hasChanges && !tab.submitting) { tab.submit() }
        if (tab.hasChanges) Text("${model.changeCount} pending", Modifier.padding(start = 4.dp), color = JewelTheme.globalColors.text.info)

        Box(Modifier.weight(1f))
        if (tab.loading || tab.submitting) Text(if (tab.submitting) "Saving…" else "Loading…", Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        ViewChipGroup {
            ViewChip("Grid", !tab.showJson) { tab.showJson = false }
            ViewChip("JSON", tab.showJson) { tab.showJson = true }
        }
        Separator()
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
private fun StatusLine(tab: DocumentBrowserTab) {
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = JewelTheme.globalColors.text.info
        val selection = tab.grid.selection
        if (selection != null && !tab.showJson) {
            val columns = selection.columns(tab.grid.visibleColumns.size)
            Text("${selection.rows.count()} × ${columns.count()} selected", color = info)
        }
        if (tab.readOnly) Text("Read-only connection", color = info)
        Box(Modifier.weight(1f))
        Text("${tab.database}.${tab.collection}", color = info)
    }
}

/** A column for a field no document on the page has yet, so values can be typed into it. */
@Composable
private fun AddField(tab: DocumentBrowserTab, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val name = remember { TextFieldState() }
    if (!open) {
        ToolButton(AllIconsKeys.General.InlineAdd, "Add field", enabled = enabled) { open = true }
        return
    }
    // The box opens focused, so typing and ⌘V go into it rather than to the grid that had focus when its button was pressed.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        name,
        Modifier.width(160.dp).focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                open = false
                true
            } else false
        },
        placeholder = { Text("New field name") },
        onKeyboardAction = KeyboardActionHandler {
            val problem = tab.model.addField(name.text.toString())
            if (problem == null) {
                name.clearText()
                open = false
            } else tab.message = DocumentBrowserTab.Message(problem, isError = true)
        },
    )
}

@Composable
private fun DiscardDialog(tab: DocumentBrowserTab) {
    AppDialog(
        onCloseRequest = { tab.pendingDiscard = null },
        size = DpSize(440.dp, 170.dp),
        title = "Unsaved Changes",
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${tab.model.changeCount} document change(s) in ${tab.collection} haven't been submitted. Submit them before continuing?")
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
private fun PreviewDialog(tab: DocumentBrowserTab, commands: String) {
    AppDialog(
        onCloseRequest = tab::closePreview,
        size = DpSize(760.dp, 420.dp),
        title = "Pending Changes",
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val text = remember(commands) { TextFieldState(commands) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Text(
                "Each document is written on its own. An update only applies while the fields still hold the values they had when loaded; " +
                    "one that doesn't stays pending.",
                color = JewelTheme.globalColors.text.info,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = tab::closePreview) { Text("Close") }
                DefaultButton(onClick = tab::submitNow) { Text("Submit") }
            }
        }
    }
}

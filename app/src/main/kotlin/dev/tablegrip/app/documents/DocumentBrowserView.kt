package dev.tablegrip.app.documents

import dev.tablegrip.core.i18n.tr
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
import androidx.compose.ui.text.AnnotatedString
import dev.tablegrip.app.ui.ReviewChangesPopup
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.alpha
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
import dev.tablegrip.app.completion.CompletionController
import dev.tablegrip.app.completion.CompletionPopup
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.completion.handleCompletionKey
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.grid.RecordEditorHost
import dev.tablegrip.app.table.PageSizes
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.UndoRedoButtons
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.grid.ResultJsonView
import dev.tablegrip.app.grid.ValuePanel
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.completion.DocumentCompleter
import dev.tablegrip.core.completion.DocumentQuery
import dev.tablegrip.core.dialect.SqlDialect
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Laid out like a table's data view: toolbar, filter bar, the grid or JSON, and a status line. */
@Composable
internal fun DocumentBrowserView(tab: DocumentBrowserTab) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Content(tab)
        FieldDragGhost(tab.fieldDrag, origin)
    }
    tab.pendingDiscard?.let { DiscardDialog(tab) }
    RecordEditorHost(tab.model, tab.grid, "${tab.database}.${tab.collection}")
}

@Composable
private fun Content(tab: DocumentBrowserTab) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Toolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        FilterBar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        if (tab.showBuilder) {
            QueryBuilderPanel(tab)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        }
        tab.message?.let { message ->
            if (message.isError) ErrorBanner(message.text, onDismiss = { tab.message = null })
            else Text(message.text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = JewelTheme.globalColors.text.info, maxLines = 2)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val model = tab.model
            when {
                tab.loading && tab.documents.isEmpty() ->
                    ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, tr("Loading {0}…", tab.collection), Modifier.align(Alignment.Center))
                tab.showJson -> ResultJsonView(model, tab.grid)
                model.rowCount == 0 -> Text(tr("No documents match."), Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
                else -> {
                    val grid =
                        @Composable {
                            DataGrid(
                                model,
                                tab.grid,
                                sort = tab.sortColumns,
                                firstRowNumber = tab.skip + 1,
                                actions = GridActions(onSort = tab::setSort, onFilterByValue = tab::filterByValue, onSubmit = tab::submit, onColumnDragOut = tab.fieldDrag),
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
}

/**
 * Filter and sort as shell-style JSON, each completing fields, operators and values from the documents seen so far;
 * the options row below adds projection, skip and limit. Enter or [Shortcuts.ApplyFilter] applies them all.
 */
@Composable
private fun FilterBar(tab: DocumentBrowserTab) {
    val label = Modifier.padding(horizontal = 8.dp)
    val info = JewelTheme.globalColors.text.info
    val expanded = tab.showOptions || tab.hasAppliedOptions
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AllIconsKeys.General.Filter, null)
            Text(tr("Filter"), label, color = info, style = JewelTheme.editorTextStyle)
            QueryField(tab, tab.filter, DocumentQuery.Filter, tr("{status: 'A', total: {\$gt: 10}}   {0} for suggestions, ⏎ to apply", Shortcuts.Completion), Modifier.weight(1f))
            Text(tr("Sort"), label, color = info, style = JewelTheme.editorTextStyle)
            QueryField(tab, tab.sort, DocumentQuery.Sort, "{total: -1}", Modifier.width(220.dp))
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab.isFilterDirty) Text(tr("Not applied"), color = info)
                if (tab.hasAppliedFilter || tab.isFilterDirty) Link(tr("Clear"), onClick = tab::clearFilter)
                Tooltip({ Text((if (tab.showBuilder) tr("Hide the query builder") else tr("Build the query by dragging fields; drag a column header up to add it")) + " (${Shortcuts.ToggleFilterMode})") }) {
                    ViewChipGroup { ViewChip(tr("Builder"), tab.showBuilder) { tab.toggleBuilder() } }
                }
                ToolButton(
                    if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown,
                    if (expanded) tr("Hide projection, skip and limit") else tr("Projection, skip and limit"),
                    enabled = !tab.hasAppliedOptions,
                ) {
                    tab.showOptions = !tab.showOptions
                }
                Tooltip({ Text(tr("Apply the query (⏎ in a field, {0})", Shortcuts.ApplyFilter)) }) {
                    DefaultButton(onClick = tab::reload, enabled = tab.isFilterDirty) { Text(tr("Apply")) }
                }
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Filter, null, Modifier.alpha(0f))
                Text(tr("Project"), label, color = info, style = JewelTheme.editorTextStyle)
                QueryField(tab, tab.projection, DocumentQuery.Projection, tr("{customer: 1, total: 1}  or  {items: 0}"), Modifier.weight(1f))
                Text(tr("Skip"), label, color = info, style = JewelTheme.editorTextStyle)
                CountField(tab, tab.skipField, "0")
                Text(tr("Limit"), label, color = info, style = JewelTheme.editorTextStyle)
                CountField(tab, tab.limitField, tr("none"))
            }
        }
    }
}

/** A number of documents for skip or limit; Enter applies the query. */
@Composable
private fun CountField(tab: DocumentBrowserTab, state: TextFieldState, placeholder: String) {
    TextField(
        state,
        Modifier.width(90.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                Shortcuts.ApplyFilter.matches(event) || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
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
        placeholder = { Text(placeholder) },
    )
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
        ToolButton(AllIconsKeys.Actions.Play_first, tr("First page"), enabled = idle && tab.skip > 0) { tab.firstPage() }
        ToolButton(AllIconsKeys.Actions.Play_back, tr("Previous page"), enabled = idle && tab.skip > 0) { tab.previousPage() }
        Text(if (tab.documents.isEmpty()) tr("0 docs") else "${tab.skip + 1}–${tab.skip + tab.documents.size}", Modifier.padding(horizontal = 4.dp))
        Text(tr("of"), color = JewelTheme.globalColors.text.info)
        if (tab.total == null) Link("?", onClick = { tab.count() }) else Text(tab.total.toString())
        ToolButton(AllIconsKeys.Actions.Play_forward, tr("Next page"), enabled = idle && tab.hasMore) { tab.nextPage() }
        ListComboBox(
            items = PageSizes.map { tr("{0} docs", it) },
            selectedIndex = PageSizes.indexOf(tab.pageSize).coerceAtLeast(0),
            onSelectedItemChange = { tab.setPageSize(PageSizes[it]) },
            modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
        )
        ToolButton(AllIconsKeys.Actions.Refresh, tr("Reload ({0})", Shortcuts.Reload)) { tab.refresh() }
        Separator()

        val editable = model.editor != null && !tab.showJson
        ToolButton(AllIconsKeys.General.Add, tr("Add document ({0})", Shortcuts.AddRow), enabled = editable) { tab.grid.revealEditable(model, model.addRow()) }
        ToolButton(AllIconsKeys.General.Remove, tr("Delete selected documents ({0})", Shortcuts.DeleteRows), enabled = editable && tab.grid.selection != null) {
            tab.grid.selection?.rows?.let { model.deleteRows(it.toList()) }
            tab.grid.clamp(model.rowCount)
        }
        AddField(tab, enabled = editable)
        UndoRedoButtons(tab.grid, model)
        ToolButton(AllIconsKeys.Actions.Rollback, tr("Revert all changes"), enabled = tab.hasChanges) { tab.revert() }
        ToolButton(AllIconsKeys.Actions.Preview, tr("Preview pending writes"), enabled = tab.hasChanges) { tab.openPreview() }
        Box {
            ToolButton(AllIconsKeys.Actions.Commit, tr("Submit changes ({0})", Shortcuts.Submit), enabled = tab.hasChanges && !tab.submitting) { tab.submit() }
            tab.preview?.let { ReviewPopup(tab, it) }
        }
        if (tab.hasChanges) Text(tr("{0} pending", model.changeCount), Modifier.padding(start = 4.dp), color = JewelTheme.globalColors.text.info)

        Box(Modifier.weight(1f))
        if (tab.loading || tab.submitting) Text(if (tab.submitting) tr("Saving…") else tr("Loading…"), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
        ViewChipGroup {
            ViewChip(tr("Grid"), !tab.showJson) { tab.showJson = false }
            ViewChip(tr("JSON"), tab.showJson) { tab.showJson = true }
        }
        Separator()
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
private fun StatusLine(tab: DocumentBrowserTab) {
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = JewelTheme.globalColors.text.info
        val selection = tab.grid.selection
        if (selection != null && !tab.showJson) {
            val columns = selection.columns(tab.grid.visibleColumns.size)
            Text(tr("{0} × {1} selected", selection.rows.count(), columns.count()), color = info)
        }
        if (tab.readOnly) Text(tr("Read-only connection"), color = info)
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
        ToolButton(AllIconsKeys.General.InlineAdd, tr("Add field"), enabled = enabled) { open = true }
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
        placeholder = { Text(tr("New field name")) },
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
        title = tr("Unsaved Changes"),
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("{0} document change(s) in {1} haven't been submitted. Submit them before continuing?", tab.model.changeCount, tab.collection))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingDiscard = null }) { Text(tr("Keep Editing")) }
                OutlinedButton(onClick = tab::confirmDiscard) { Text(tr("Discard")) }
                DefaultButton(onClick = tab::submitAndContinue) { Text(tr("Submit")) }
            }
        }
    }
}

/** The pending writes under the Submit button, to check before they run. */
@Composable
private fun ReviewPopup(tab: DocumentBrowserTab, commands: String) {
    ReviewChangesPopup(
        changeCount = tab.model.changeCount,
        target = "${tab.collection} · ${tab.config.name}",
        statements = AnnotatedString(commands),
        note =
            tr("Each document is written on its own. An update only applies while the fields still hold the values they had when loaded; ") +
                tr("one that doesn't stays pending."),
        canSkip = tab.canSkipReview,
        onCancel = tab::closePreview,
        onSubmit = tab::submitReviewed,
    )
}

package dev.tablegrip.app.console

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.ContextMenuDataProvider
import dev.tablegrip.core.connection.Capability
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.ShortcutHints
import dev.tablegrip.app.ui.Shortcuts
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.TextArea
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.chart.ChartExport
import dev.tablegrip.app.chart.CanvasChartRenderer
import dev.tablegrip.app.chart.ResultChartColors
import dev.tablegrip.app.export.ExportRequest
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.grid.GridMention
import dev.tablegrip.app.grid.ResultJsonView
import dev.tablegrip.app.table.PageSizes
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.queries.ParametersDialog
import dev.tablegrip.core.dialect.SqlDialect
import java.time.Instant
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

@Composable
internal fun ConsoleView(app: AppState, tab: ConsoleTab) {
    val editorActions = remember(tab) { SqlEditorActions() }
    Column(Modifier.fillMaxSize()) {
        Toolbar(tab, editorActions)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        VerticalSplitLayout(
            first = {
                // Added to the editor's own Cut / Copy / Paste menu.
                ContextMenuDataProvider(
                    items = { if (tab.canAskAgent) listOf(ContextMenuItem(tr("Add to Chat ({0})", Shortcuts.AddToChat)) { tab.mentionInChat() }) else emptyList() }
                ) {
                    SqlEditor(
                        state = tab.editor,
                        syntax = tab.syntax,
                        fontSize = app.settings.editorFontSize,
                        modifier = Modifier.fillMaxSize(),
                        completion = tab.completion,
                        completionVersion = app.schemaCache(tab.config).version,
                        actions = editorActions,
                        onShortcut = { handleConsoleShortcut(tab, it) },
                    )
                }
            },
            second = { ResultPanel(app, tab) },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.6f),
        )
    }
    tab.pendingParameters?.let { ParametersDialog(app, tab, it) }
    tab.pendingConfirmation?.let { ConfirmWriteDialog(tab, it) }
    if (tab.pendingClose != null) OpenTransactionDialog(tab)
    if (tab.pendingScriptClose != null) UnsavedScriptDialog(tab)
}

/**
 * Console actions, from the editor and — via the window — from anywhere else in the tab (e.g. the result grid), so a
 * query can be re-run or cancelled without clicking back into the editor.
 */
internal fun handleConsoleShortcut(tab: ConsoleTab, event: KeyEvent): Boolean {
    fun done(block: () -> Unit) = true.also { block() }
    return when {
        Shortcuts.RunStatement.matches(event) -> done { tab.run(RunMode.Current) }
        Shortcuts.RunScript.matches(event) -> done { tab.run(RunMode.All) }
        Shortcuts.Cancel.matches(event) -> done { if (tab.running) tab.cancel() }
        Shortcuts.Explain.matches(event) -> done { tab.explain() }
        Shortcuts.Save.matches(event) -> done { tab.save() }
        Shortcuts.SaveAs.matches(event) -> done { tab.save(saveAs = true) }
        else -> false
    }
}

@Composable
private fun Toolbar(tab: ConsoleTab, editor: SqlEditorActions) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tooltip({ Text(tr("Execute statement at caret or selection ({0})", Shortcuts.RunStatement)) }) {
            IconButton(onClick = { tab.run(RunMode.Current) }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.Execute, tr("Execute")) }
        }
        Tooltip({ Text(tr("Execute script ({0})", Shortcuts.RunScript)) }) {
            IconButton(onClick = { tab.run(RunMode.All) }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.RunAll, tr("Execute script")) }
        }
        Tooltip({ Text(tr("Cancel running query ({0})", Shortcuts.Cancel)) }) {
            IconButton(onClick = tab::cancel, enabled = tab.running) { Icon(AllIconsKeys.Actions.Suspend, tr("Cancel")) }
        }
        if (Capability.Explain in tab.config.type.capabilities) {
            Tooltip({ Text(tr("Explain plan for the statement at caret ({0})", Shortcuts.Explain)) }) {
                IconButton(onClick = tab::explain, enabled = !tab.running) { Icon(AllIconsKeys.Actions.Preview, tr("Explain")) }
            }
        }
        Tooltip({ Text(tr("Reformat SQL ({0})", Shortcuts.Format)) }) {
            IconButton(onClick = { editor.format() }) { Icon(AllIconsKeys.Actions.ShowCode, tr("Format")) }
        }
        Tooltip({ Text(tr("Find ({0}) · Replace ({1})", Shortcuts.Find, Shortcuts.Replace)) }) {
            IconButton(onClick = { editor.openFind() }) { Icon(AllIconsKeys.Actions.Search, tr("Find")) }
        }
        Tooltip({ Text(tr("Save query ({0}) · Save to file ({1})", Shortcuts.Save, Shortcuts.SaveAs)) }) {
            IconButton(onClick = { tab.save() }) { Icon(AllIconsKeys.Actions.MenuSaveall, tr("Save")) }
        }
        if (Capability.Transactions in tab.config.type.capabilities) {
            Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 6.dp))
            ListComboBox(
                items = listOf(tr("Auto-commit"), tr("Manual commit")),
                selectedIndex = if (tab.autoCommit) 0 else 1,
                onSelectedItemChange = { tab.changeAutoCommit(it == 0) },
                modifier = Modifier.width(140.dp),
                enabled = !tab.running && !tab.config.readOnly,
            )
            if (!tab.autoCommit) {
                Tooltip({ Text(tr("Commit transaction")) }) {
                    IconButton(onClick = tab::commit, enabled = tab.uncommitted > 0 && !tab.running) { Icon(AllIconsKeys.Actions.Commit, tr("Commit")) }
                }
                Tooltip({ Text(tr("Roll back transaction")) }) {
                    IconButton(onClick = tab::rollback, enabled = tab.uncommitted > 0 && !tab.running) { Icon(AllIconsKeys.Actions.Rollback, tr("Rollback")) }
                }
                if (tab.uncommitted > 0) Text(tr("{0} uncommitted", tab.uncommitted), Modifier.padding(start = 4.dp), color = PendingColor)
            }
        }
        Text(if (tab.running) tr("Executing…") else "", Modifier.padding(start = 8.dp), color = JewelTheme.globalColors.text.info)
        Box(Modifier.weight(1f))
        if (tab.config.readOnly) Text(tr("read-only"), Modifier.padding(end = 6.dp), color = JewelTheme.globalColors.text.info)
        EnvironmentChip(tab.config)
        Text("${tab.config.name} · ${tab.config.type.displayName}", Modifier.padding(horizontal = 8.dp), color = JewelTheme.globalColors.text.info)
    }
}

private val PendingColor = Color(0xFFD9822B)

@Composable
private fun ConfirmWriteDialog(tab: ConsoleTab, confirmation: WriteConfirmation) {
    AppDialog(
        onCloseRequest = { tab.pendingConfirmation = null },
        size = DpSize(620.dp, 380.dp),
        title = tr("Run Statements?"),
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Warning, null)
                Text(tr("Please confirm"), fontWeight = FontWeight.SemiBold)
                EnvironmentChip(tab.config)
            }
            confirmation.reasons.forEach { Text("• $it") }
            val text = remember(confirmation) { TextFieldState(confirmation.statements.joinToString(";\n", postfix = ";")) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingConfirmation = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = tab::confirmPending) { Text(tr("Run")) }
            }
        }
    }
}

@Composable
private fun OpenTransactionDialog(tab: ConsoleTab) {
    AppDialog(
        onCloseRequest = { tab.pendingClose = null },
        size = DpSize(460.dp, 180.dp),
        title = tr("Uncommitted Transaction"),
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("{0} has {1} uncommitted statement(s). What should happen to them?", tab.title, tab.uncommitted))
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingClose = null }) { Text(tr("Keep Open")) }
                OutlinedButton(onClick = { tab.closeAfter(commit = false) }) { Text(tr("Roll Back & Close")) }
                DefaultButton(onClick = { tab.closeAfter(commit = true) }) { Text(tr("Commit & Close")) }
            }
        }
    }
}

@Composable
private fun UnsavedScriptDialog(tab: ConsoleTab) {
    AppDialog(
        onCloseRequest = { tab.pendingScriptClose = null },
        size = DpSize(460.dp, 170.dp),
        title = tr("Unsaved Script"),
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("{0} has unsaved changes. Save the script before closing?", tab.title))
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingScriptClose = null }) { Text(tr("Cancel")) }
                OutlinedButton(onClick = { tab.closeAfterScript(save = false) }) { Text(tr("Don't Save")) }
                DefaultButton(onClick = { tab.closeAfterScript(save = true) }) { Text(if (tab.file == null) tr("Save…") else tr("Save")) }
            }
        }
    }
}

@Composable
private fun ResultPanel(app: AppState, tab: ConsoleTab) {
    val historyIndex = tab.results.size + 1
    Column(Modifier.fillMaxSize()) {
        val titles = listOf(tr("Output")) + tab.results.map { it.title } + tr("History")
        TabStrip(
            tabs =
                titles.mapIndexed { index, title ->
                    val view = tab.results.getOrNull(index - 1)
                    TabData.Default(
                        selected = index == tab.selectedResultTab,
                        content = { state ->
                            if (view == null) {
                                SimpleTabContent(title, state)
                            } else {
                                ContextMenuArea(
                                    items = {
                                        listOf(
                                            ContextMenuItem(if (view.pinned) tr("Unpin Tab") else tr("Pin Tab")) { view.pinned = !view.pinned },
                                            ContextMenuItem(tr("Close Other Unpinned Tabs")) { tab.results.filter { it !== view && !it.pinned }.forEach(tab::closeResult) },
                                        )
                                    }
                                ) {
                                    SimpleTabContent(title, state, iconKey = AllIconsKeys.Actions.PinTab.takeIf { view.pinned })
                                }
                            }
                        },
                        closable = view != null,
                        onClose = { view?.let(tab::closeResult) },
                        onClick = { tab.selectedResultTab = index },
                    )
                },
            style = JewelTheme.defaultTabStyle,
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        val selected = tab.selectedResultTab.coerceIn(0, historyIndex)
        when (selected) {
            0 -> Column(Modifier.fillMaxSize()) {
                tab.fix?.let { FixCard(tab, it) }
                Box(Modifier.weight(1f)) { OutputLog(app, tab) }
            }
            historyIndex -> HistoryList(tab)
            else -> {
                val view = tab.results[selected - 1]
                Column(Modifier.fillMaxSize()) {
                    LaunchedEffect(view, view.page) { view.grid.verticalScroll.scrollToItem(0) }
                    when (view.view) {
                        ResultView.Json -> ResultJsonView(view.model, view.grid, Modifier.weight(1f))
                        ResultView.Chart -> ResultChartView(tab, view, Modifier.weight(1f))
                        ResultView.Grid -> DataGrid(
                        model = view.model,
                        state = view.grid,
                        modifier = Modifier.weight(1f),
                        actions =
                            GridActions(
                                dialect = SqlDialect.of(tab.config.type),
                                onMention = { cells: GridMention -> tab.mentionCells(view, cells) }.takeIf { tab.canAskAgent },
                            ),
                        firstRowNumber = view.firstRow + 1L,
                    )
                    }
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    ResultFooter(app, tab, view)
                }
            }
        }
    }
}

/** Paging on the left like table viewers' toolbar (row counts alone for a chart, which draws them all); view switch, pin and export on the right. */
@Composable
private fun ResultFooter(app: AppState, tab: ConsoleTab, view: ResultSetView) {
    val info = JewelTheme.globalColors.text.info
    val total = view.rows.rows.size
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (view.view == ResultView.Chart) Text(if (view.rows.truncated) tr("{0}+ rows", total) else tr("{0} rows", total), Modifier.padding(horizontal = 4.dp))
        else Pager(app, view)
        Separator()
        val limited =
            if (view.rows.truncated) tr(" · first {0} fetched (Settings → Max result rows)", total) else ""
        Text(
            tr("{0} ms{1}", view.rows.elapsedMillis, limited),
            Modifier.weight(1f).padding(horizontal = 4.dp),
            color = info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ViewChipGroup {
            ResultView.entries.forEach { mode -> ViewChip(mode.label, view.view == mode) { view.view = mode } }
        }
        Separator()
        ToolButton(
            if (view.pinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
            if (view.pinned) tr("Unpin tab") else tr("Pin tab: keep this result when the console runs again"),
        ) { view.pinned = !view.pinned }
        if (view.view == ResultView.Chart) {
            val frame = view.chart.frame
            val colors = ResultChartColors.current()
            ToolButton(AllIconsKeys.Actions.Copy, tr("Copy chart as image"), enabled = frame != null) {
                frame ?: return@ToolButton
                val size = view.chart.drawnSize
                app.status = tr("Copying chart…")
                app.scope.launch {
                    runCatching { ChartExport.copy(CanvasChartRenderer, frame, colors, size.width, size.height) }
                        .onSuccess { app.status = tr("Chart copied as an image") }
                        .onFailure { app.status = tr("Could not copy the chart: {0}", it.message) }
                }
            }
            ToolButton(AllIconsKeys.Actions.MenuSaveall, tr("Save chart as PNG or SVG…"), enabled = frame != null) {
                frame ?: return@ToolButton
                val size = view.chart.drawnSize
                val file = ChartExport.chooseFile(view.title) ?: return@ToolButton
                app.status = tr("Saving chart…")
                app.scope.launch {
                    runCatching { ChartExport.save(CanvasChartRenderer, frame, colors, size.width, size.height, file) }
                        .onSuccess { app.status = tr("Saved {0}", file.path) }
                        .onFailure { app.status = tr("Could not save the chart: {0}", it.message) }
                }
            }
        }
        ToolButton(AllIconsKeys.Actions.Download, tr("Export result…")) {
            val dialect = SqlDialect.of(tab.config.type)
            app.exportRequest = ExportRequest(view.title, "result", view.rows.columns, view.rows.rows, dialect, "table_name")
        }
    }
}

/** Page buttons, the rows shown of the result's, and the page size. */
@Composable
private fun Pager(app: AppState, view: ResultSetView) {
    val total = view.rows.rows.size
    ToolButton(AllIconsKeys.Actions.Play_first, tr("First page"), enabled = view.page > 0) { view.goToPage(0) }
    ToolButton(AllIconsKeys.Actions.Play_back, tr("Previous page"), enabled = view.page > 0) { view.goToPage(view.page - 1) }
    val shown = view.model.rowCount
    Text(if (shown == 0) tr("0 rows") else "${view.firstRow + 1}–${view.firstRow + shown}", Modifier.padding(horizontal = 4.dp))
    Text(tr("of"), color = JewelTheme.globalColors.text.info)
    Text(if (view.rows.truncated) "$total+" else "$total", Modifier.padding(start = 4.dp))
    val last = view.pageCount - 1
    ToolButton(AllIconsKeys.Actions.Play_forward, tr("Next page"), enabled = view.page < last) { view.goToPage(view.page + 1) }
    ToolButton(AllIconsKeys.Actions.Play_last, tr("Last page"), enabled = view.page < last) { view.goToPage(last) }
    ListComboBox(
        items = PageSizes.map { tr("{0} rows", it) },
        selectedIndex = PageSizes.indexOf(view.pageSize).coerceAtLeast(0),
        onSelectedItemChange = { index ->
            view.changePageSize(PageSizes[index])
            app.updateSettings { it.copy(pageSize = PageSizes[index]) }
        },
        modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
    )
}

@Composable
private fun OutputLog(app: AppState, tab: ConsoleTab) {
    if (tab.output.isEmpty()) {
        ShortcutHints(
            listOf(
                tr("Execute statement") to Shortcuts.RunStatement,
                tr("Execute script") to Shortcuts.RunScript,
                tr("Code completion") to Shortcuts.Completion,
                tr("All shortcuts") to Shortcuts.ShowShortcuts,
            )
        )
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(tab.output.size) { listState.animateScrollToItem(tab.output.lastIndex) }
    // Only the latest failure offers fixes: an older one is likely fixed already, or edited away.
    val latestFailure = tab.output.lastOrNull { it.failure != null }?.failure
    LazyColumn(Modifier.fillMaxSize().padding(8.dp), state = listState) {
        items(tab.output) { line ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line.time, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
                    Text(
                        line.text,
                        color = if (line.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                        style = JewelTheme.editorTextStyle,
                    )
                }
                line.failure?.takeIf { it === latestFailure && !tab.running }?.let { FailureActions(app, tab, it) }
            }
        }
    }
}

@Composable
private fun HistoryList(tab: ConsoleTab) {
    // Re-read whenever a run finishes, so new entries show up.
    val entries = remember(tab.running, tab.output.size) { tab.recentHistory() }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("No history yet"), color = JewelTheme.globalColors.text.info) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries) { entry ->
            Row(
                Modifier.fillMaxWidth().clickable { tab.insert(entry.sql) }.padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(HistoryTime.format(Instant.ofEpochMilli(entry.executedAt)), color = JewelTheme.globalColors.text.info)
                Text(
                    entry.sql.replace(Regex("\\s+"), " "),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (entry.error != null) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                    style = JewelTheme.editorTextStyle,
                )
                Text(tr("{0} ms", entry.elapsedMillis), color = JewelTheme.globalColors.text.info)
            }
        }
    }
}

private val HistoryTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

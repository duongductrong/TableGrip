package dev.datagrok.app.console

import dev.datagrok.core.connection.Capability
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.app.ui.EnvironmentChip
import dev.datagrok.app.ui.ShortcutHints
import dev.datagrok.app.ui.Shortcuts
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.TextArea
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
import dev.datagrok.app.AppState
import dev.datagrok.app.export.ExportRequest
import dev.datagrok.app.grid.DataGrid
import dev.datagrok.app.grid.GridActions
import dev.datagrok.core.dialect.SqlDialect
import java.time.Instant
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
            },
            second = { ResultPanel(app, tab) },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.6f),
        )
    }
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
        Tooltip({ Text("Execute statement at caret or selection (${Shortcuts.RunStatement})") }) {
            IconButton(onClick = { tab.run(RunMode.Current) }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.Execute, "Execute") }
        }
        Tooltip({ Text("Execute script (${Shortcuts.RunScript})") }) {
            IconButton(onClick = { tab.run(RunMode.All) }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.RunAll, "Execute script") }
        }
        Tooltip({ Text("Cancel running query (${Shortcuts.Cancel})") }) {
            IconButton(onClick = tab::cancel, enabled = tab.running) { Icon(AllIconsKeys.Actions.Suspend, "Cancel") }
        }
        if (Capability.Explain in tab.config.type.capabilities) {
            Tooltip({ Text("Explain plan for the statement at caret (${Shortcuts.Explain})") }) {
                IconButton(onClick = tab::explain, enabled = !tab.running) { Icon(AllIconsKeys.Actions.Preview, "Explain") }
            }
        }
        Tooltip({ Text("Reformat SQL (${Shortcuts.Format})") }) {
            IconButton(onClick = { editor.format() }) { Icon(AllIconsKeys.Actions.ShowCode, "Format") }
        }
        Tooltip({ Text("Find (${Shortcuts.Find}) · Replace (${Shortcuts.Replace})") }) {
            IconButton(onClick = { editor.openFind() }) { Icon(AllIconsKeys.Actions.Search, "Find") }
        }
        Tooltip({ Text("Save script (${Shortcuts.Save}) · Save as (${Shortcuts.SaveAs})") }) {
            IconButton(onClick = { tab.save() }) { Icon(AllIconsKeys.Actions.MenuSaveall, "Save") }
        }
        if (Capability.Transactions in tab.config.type.capabilities) {
            Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 6.dp))
            ListComboBox(
                items = listOf("Auto-commit", "Manual commit"),
                selectedIndex = if (tab.autoCommit) 0 else 1,
                onSelectedItemChange = { tab.changeAutoCommit(it == 0) },
                modifier = Modifier.width(140.dp),
                enabled = !tab.running && !tab.config.readOnly,
            )
            if (!tab.autoCommit) {
                Tooltip({ Text("Commit transaction") }) {
                    IconButton(onClick = tab::commit, enabled = tab.uncommitted > 0 && !tab.running) { Icon(AllIconsKeys.Actions.Commit, "Commit") }
                }
                Tooltip({ Text("Roll back transaction") }) {
                    IconButton(onClick = tab::rollback, enabled = tab.uncommitted > 0 && !tab.running) { Icon(AllIconsKeys.Actions.Rollback, "Rollback") }
                }
                if (tab.uncommitted > 0) Text("${tab.uncommitted} uncommitted", Modifier.padding(start = 4.dp), color = PendingColor)
            }
        }
        Text(if (tab.running) "Executing…" else "", Modifier.padding(start = 8.dp), color = JewelTheme.globalColors.text.info)
        Box(Modifier.weight(1f))
        if (tab.config.readOnly) Text("read-only", Modifier.padding(end = 6.dp), color = JewelTheme.globalColors.text.info)
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
        title = "Run Statements?",
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Warning, null)
                Text("Please confirm", fontWeight = FontWeight.SemiBold)
                EnvironmentChip(tab.config)
            }
            confirmation.reasons.forEach { Text("• $it") }
            val text = remember(confirmation) { TextFieldState(confirmation.statements.joinToString(";\n", postfix = ";")) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingConfirmation = null }) { Text("Cancel") }
                DefaultButton(onClick = tab::confirmPending) { Text("Run") }
            }
        }
    }
}

@Composable
private fun OpenTransactionDialog(tab: ConsoleTab) {
    AppDialog(
        onCloseRequest = { tab.pendingClose = null },
        size = DpSize(460.dp, 180.dp),
        title = "Uncommitted Transaction",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${tab.title} has ${tab.uncommitted} uncommitted statement(s). What should happen to them?")
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingClose = null }) { Text("Keep Open") }
                OutlinedButton(onClick = { tab.closeAfter(commit = false) }) { Text("Roll Back & Close") }
                DefaultButton(onClick = { tab.closeAfter(commit = true) }) { Text("Commit & Close") }
            }
        }
    }
}

@Composable
private fun UnsavedScriptDialog(tab: ConsoleTab) {
    AppDialog(
        onCloseRequest = { tab.pendingScriptClose = null },
        size = DpSize(460.dp, 170.dp),
        title = "Unsaved Script",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${tab.title} has unsaved changes. Save the script before closing?")
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingScriptClose = null }) { Text("Cancel") }
                OutlinedButton(onClick = { tab.closeAfterScript(save = false) }) { Text("Don't Save") }
                DefaultButton(onClick = { tab.closeAfterScript(save = true) }) { Text(if (tab.file == null) "Save…" else "Save") }
            }
        }
    }
}

@Composable
private fun ResultPanel(app: AppState, tab: ConsoleTab) {
    val historyIndex = tab.results.size + 1
    Column(Modifier.fillMaxSize()) {
        val titles = listOf("Output") + tab.results.map { it.title } + "History"
        TabStrip(
            tabs =
                titles.mapIndexed { index, title ->
                    val view = tab.results.getOrNull(index - 1)
                    TabData.Default(
                        selected = index == tab.selectedResultTab,
                        content = { state -> SimpleTabContent(title, state) },
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
            0 -> OutputLog(tab)
            historyIndex -> HistoryList(tab)
            else -> {
                val view = tab.results[selected - 1]
                Column(Modifier.fillMaxSize()) {
                    DataGrid(
                        model = view.model,
                        state = view.grid,
                        modifier = Modifier.weight(1f),
                        actions = GridActions(dialect = SqlDialect.of(tab.config.type)),
                    )
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    val suffix = if (view.rows.truncated) " (limited)" else ""
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${view.rows.rows.size} row(s)$suffix · ${view.rows.elapsedMillis} ms",
                            Modifier.weight(1f),
                            color = JewelTheme.globalColors.text.info,
                        )
                        Tooltip({ Text("Export result…") }) {
                            IconButton(onClick = {
                                val dialect = SqlDialect.of(tab.config.type)
                                app.exportRequest =
                                    ExportRequest(view.title, "result", view.rows.columns, view.rows.rows, dialect, "table_name")
                            }) { Icon(AllIconsKeys.Actions.Download, "Export") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputLog(tab: ConsoleTab) {
    if (tab.output.isEmpty()) {
        ShortcutHints(
            listOf(
                "Execute statement" to Shortcuts.RunStatement,
                "Execute script" to Shortcuts.RunScript,
                "Code completion" to Shortcuts.Completion,
                "All shortcuts" to Shortcuts.ShowShortcuts,
            )
        )
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(tab.output.size) { listState.animateScrollToItem(tab.output.lastIndex) }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp), state = listState) {
        items(tab.output) { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line.time, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
                Text(
                    line.text,
                    color = if (line.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                    style = JewelTheme.editorTextStyle,
                )
            }
        }
    }
}

@Composable
private fun HistoryList(tab: ConsoleTab) {
    // Re-read whenever a run finishes, so new entries show up.
    val entries = remember(tab.running, tab.output.size) { tab.recentHistory() }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No history yet", color = JewelTheme.globalColors.text.info) }
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
                Text("${entry.elapsedMillis} ms", color = JewelTheme.globalColors.text.info)
            }
        }
    }
}

private val HistoryTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

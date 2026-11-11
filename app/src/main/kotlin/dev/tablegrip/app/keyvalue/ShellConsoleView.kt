package dev.tablegrip.app.keyvalue

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.ui.unit.DpSize
import dev.tablegrip.app.console.EditorLanguage
import dev.tablegrip.app.documents.ShellResultView
import dev.tablegrip.app.ui.AppDialog
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import dev.tablegrip.app.console.SqlEditor
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.LocalSettings
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A document store's console, laid out like the SQL console: a Mongo shell script in the editor (highlighting, bracket
 * pairing, find, and completion of collections, methods, fields and operators), the signature of the method being
 * called under it, and the replies below. ⌘⏎ runs the command at the caret or the selected ones, ⇧⌘⏎ the whole script.
 */
@Composable
internal fun ShellConsoleView(tab: CommandConsoleTab) {
    tab.pendingClose?.let { UnsubmittedDialog(tab) }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        ShellToolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        VerticalSplitLayout(
            first = {
                Column(Modifier.fillMaxSize()) {
                    SqlEditor(
                        state = tab.script,
                        syntax = EditorLanguage.MongoShellSyntax,
                        fontSize = LocalSettings.current.editorFontSize,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        completion = tab.scriptCompletion,
                        completionVersion = tab.completionVersion,
                        onShortcut = { handleShellShortcut(tab, it) },
                        language = EditorLanguage.MongoShell,
                    )
                    val hint = tab.hint(tab.script.text.toString(), tab.script.selection.start)
                    if (hint != null) {
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        Text(
                            hint,
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            style = JewelTheme.editorTextStyle.copy(fontSize = JewelTheme.editorTextStyle.fontSize * 0.9f),
                            color = JewelTheme.globalColors.text.info,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            second = { OutputPanel(tab) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.45f),
        )
    }
}

private fun handleShellShortcut(tab: CommandConsoleTab, event: KeyEvent): Boolean =
    when {
        Shortcuts.RunStatement.matches(event) -> true.also { tab.runScript() }
        Shortcuts.RunScript.matches(event) -> true.also { tab.runScript(all = true) }
        Shortcuts.Cancel.matches(event) -> true.also { if (tab.running) tab.cancel() }
        else -> false
    }

@Composable
private fun ShellToolbar(tab: CommandConsoleTab) {
    val info = JewelTheme.globalColors.text.info
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tooltip({ Text(tr("Execute statement at caret or selection ({0})", Shortcuts.RunStatement)) }) {
            IconButton(onClick = { tab.runScript() }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.Execute, tr("Execute")) }
        }
        Tooltip({ Text(tr("Execute script ({0})", Shortcuts.RunScript)) }) {
            IconButton(onClick = { tab.runScript(all = true) }, enabled = !tab.running) { Icon(AllIconsKeys.Actions.RunAll, tr("Execute script")) }
        }
        Tooltip({ Text(tr("Cancel running query ({0})", Shortcuts.Cancel)) }) {
            IconButton(onClick = tab::cancel, enabled = tab.running) { Icon(AllIconsKeys.Actions.Suspend, tr("Cancel")) }
        }
        Tooltip({ Text(tr("Clear output")) }) {
            IconButton(onClick = tab::clearOutput, enabled = tab.entries.isNotEmpty()) { Icon(AllIconsKeys.Actions.GC, tr("Clear output")) }
        }
        Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 6.dp))
        Text(tab.prompt.removeSuffix(">"), color = info)
        Text(if (tab.running) tr("Executing…") else "", Modifier.padding(start = 8.dp), color = info)
        Box(Modifier.weight(1f))
        if (tab.config.readOnly) Text(tr("read-only"), Modifier.padding(end = 6.dp), color = info)
        EnvironmentChip(tab.config)
        Text("${tab.config.name} · ${tab.config.type.displayName}", Modifier.padding(horizontal = 8.dp), color = info)
    }
}

/** The log of commands run, then a tab per result of documents, as the SQL console lays out its results. */
@Composable
private fun OutputPanel(tab: CommandConsoleTab) {
    Column(Modifier.fillMaxSize()) {
        val titles = listOf(tr("Output")) + tab.results.map { it.title }
        TabStrip(
            tabs =
                titles.mapIndexed { index, title ->
                    val result = tab.results.getOrNull(index - 1)
                    TabData.Default(
                        selected = index == tab.selectedOutput,
                        content = { state ->
                            if (result == null) SimpleTabContent(title, state)
                            else {
                                ContextMenuArea(
                                    items = {
                                        listOf(
                                            ContextMenuItem(if (result.pinned) tr("Unpin Tab") else tr("Pin Tab")) { result.pinned = !result.pinned },
                                            ContextMenuItem(tr("Close Other Unpinned Tabs")) {
                                                tab.results.filter { it !== result && !it.pinned && !it.hasChanges }.forEach(tab::closeResult)
                                            },
                                        )
                                    }
                                ) {
                                    val label = if (result.hasChanges) "$title *" else title
                                    SimpleTabContent(label, state, iconKey = AllIconsKeys.Actions.PinTab.takeIf { result.pinned })
                                }
                            }
                        },
                        closable = result != null && !result.hasChanges,
                        onClose = { result?.let(tab::closeResult) },
                        onClick = { tab.selectedOutput = index },
                    )
                },
            style = JewelTheme.defaultTabStyle,
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        val result = tab.results.getOrNull(tab.selectedOutput - 1)
        if (result == null) Output(tab) else ShellResultView(result)
    }
}

/** Closing the console with results whose edits haven't been submitted. */
@Composable
private fun UnsubmittedDialog(tab: CommandConsoleTab) {
    AppDialog(
        onCloseRequest = { tab.pendingClose = null },
        size = DpSize(440.dp, 170.dp),
        title = tr("Unsaved Changes"),
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val count = tab.results.sumOf { it.model.changeCount }
            Text(tr("{0} document change(s) in this console's results haven't been submitted. Discard them?", count))
            Box(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pendingClose = null }) { Text(tr("Keep Editing")) }
                DefaultButton(onClick = tab::confirmPendingClose) { Text(tr("Discard")) }
            }
        }
    }
}

@Composable
private fun Output(tab: CommandConsoleTab) {
    val listState = rememberLazyListState()
    LaunchedEffect(tab.entries.size) { if (tab.entries.isNotEmpty()) listState.animateScrollToItem(tab.entries.size - 1) }
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab.entries.isEmpty()) {
                item { Text(tab.help, color = JewelTheme.globalColors.text.info) }
            }
            items(tab.entries) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${entry.prompt} ${entry.command}", Modifier.weight(1f, fill = false), style = JewelTheme.editorTextStyle, color = JewelTheme.globalColors.text.info)
                        entry.millis?.let { Text(tr("{0} ms", it), color = JewelTheme.globalColors.text.info) }
                    }
                    val result = entry.result
                    if (result != null) {
                        // The documents themselves are in their tab; the log says how many.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val count = result.documents.size
                            Text(if (result.hasMore) tr("{0}+ documents", count) else tr("{0} document(s)", count), style = JewelTheme.editorTextStyle)
                            if (result in tab.results) Link(tr("Show {0}", result.title), onClick = { tab.selectResult(result) })
                        }
                    } else {
                        Text(
                            entry.reply,
                            style = JewelTheme.editorTextStyle,
                            color = if (entry.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
                        )
                    }
                }
            }
        }
    }
}

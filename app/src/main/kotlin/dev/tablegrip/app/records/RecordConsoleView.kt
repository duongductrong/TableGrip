package dev.tablegrip.app.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun RecordConsoleView(tab: RecordConsoleTab) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(AllIconsKeys.Actions.Execute, tr("Run the selection, or everything ({0})", Shortcuts.Submit), enabled = !tab.running) { tab.run() }
            ToolButton(AllIconsKeys.Actions.Suspend, tr("Cancel"), enabled = tab.running) { tab.cancel() }
            Text(tab.language?.name.orEmpty(), Modifier.padding(horizontal = 6.dp), color = info)
            Box(Modifier.weight(1f))
            if (tab.running) Text(tr("Running…"), Modifier.padding(end = 8.dp), color = info)
            if (tab.config.readOnly) Text(tr("Read-only: only statements that read run"), Modifier.padding(end = 8.dp), color = info)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        VerticalSplitLayout(
            first = {
                TextArea(
                    tab.editor,
                    Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && Shortcuts.Submit.matches(event)) {
                            tab.run()
                            true
                        } else false
                    },
                    textStyle = JewelTheme.editorTextStyle,
                    placeholder = { Text(tab.language?.placeholder.orEmpty(), style = JewelTheme.editorTextStyle) },
                )
            },
            second = { Result(tab) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.4f),
        )
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val reply = tab.reply) {
                is ConsoleReply.Table -> Text(reply.note ?: tr("{0} row(s)", reply.rows.rows.size), color = info)
                is ConsoleReply.Text -> Text(tab.ran.orEmpty(), color = info, maxLines = 1)
                null -> Unit
            }
            tab.reply?.let { Text(tr("{0} ms", it.elapsedMillis), color = info) }
            Box(Modifier.weight(1f))
            Text(tab.config.type.displayName, color = info)
        }
    }
}

@Composable
private fun Result(tab: RecordConsoleTab) {
    val error = tab.error
    val model = tab.model
    val reply = tab.reply
    when {
        error != null ->
            SelectionContainer {
                Text(error, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), color = JewelTheme.globalColors.text.error, style = JewelTheme.editorTextStyle)
            }
        model != null -> DataGrid(model, tab.grid)
        reply is ConsoleReply.Text ->
            SelectionContainer { Text(reply.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), style = JewelTheme.editorTextStyle) }
        else -> Text(tab.help, Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
    }
}

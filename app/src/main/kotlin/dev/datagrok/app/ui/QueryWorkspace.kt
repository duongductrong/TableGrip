package dev.datagrok.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.QueryTab
import dev.datagrok.core.driver.QueryResult
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.editorTabStyle

/** Tabbed SQL consoles with an editor on top and the result grid below. */
@Composable
internal fun QueryWorkspace(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabStrip(
                tabs =
                    state.tabs.mapIndexed { index, tab ->
                        TabData.Default(
                            selected = index == state.selectedTab,
                            content = { tabState -> SimpleTabContent(tab.title, tabState) },
                            onClick = { state.selectedTab = index },
                            onClose = { state.closeTab(index) },
                        )
                    },
                style = JewelTheme.editorTabStyle,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = state::newTab, Modifier.padding(horizontal = 4.dp)) { Icon(AllIconsKeys.General.Add, "New console") }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())

        val tab = state.tabs[state.selectedTab]
        VerticalSplitLayout(
            first = { SqlEditor(state, tab) },
            second = { ResultPane(tab) },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = 80.dp,
            secondPaneMinWidth = 80.dp,
            state = rememberSplitLayoutState(0.45f),
        )
    }
}

@Composable
private fun SqlEditor(state: AppState, tab: QueryTab) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultButton(onClick = { state.run(tab) }, enabled = !tab.running) { Text(if (tab.running) "Running…" else "Run") }
            Text("⌘/Ctrl + Enter", color = JewelTheme.globalColors.text.info)
        }
        TextArea(
            state = tab.editor,
            modifier =
                Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 8.dp).onPreviewKeyEvent {
                    val runShortcut =
                        it.type == KeyEventType.KeyDown && it.key == Key.Enter && (it.isMetaPressed || it.isCtrlPressed)
                    if (runShortcut) state.run(tab)
                    runShortcut
                },
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text("SELECT * FROM …") },
        )
    }
}

@Composable
private fun ResultPane(tab: QueryTab) {
    Box(Modifier.fillMaxSize()) {
        val error = tab.error
        val result = tab.result
        when {
            error != null -> Text(error, Modifier.padding(8.dp), color = JewelTheme.globalColors.text.error)
            result == null -> Text("Run a query to see results", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
            result is QueryResult.Update ->
                Text("${result.affectedRows} row(s) affected in ${result.elapsedMillis} ms", Modifier.padding(8.dp))
            result is QueryResult.Rows -> ResultGrid(result)
        }
    }
}

package dev.datagrok.app.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datagrok.app.grid.DataGrid
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import dev.datagrok.app.connections.ConnectionLoadingPlaceholder
import androidx.compose.foundation.layout.Box

@Composable
internal fun DocumentBrowserView(tab: DocumentBrowserTab) {
    // The grid's focused row is the document the editor shows.
    val focusedRow = tab.grid.selection?.focus?.row
    LaunchedEffect(focusedRow) { if (focusedRow != null) tab.show(focusedRow) }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filter")
            TextField(
                tab.filter,
                Modifier.weight(1f),
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text("{status: 'A', total: {\$gt: 10}}") },
                onKeyboardAction = KeyboardActionHandler { tab.reload() },
            )
            Text("Sort")
            TextField(tab.sort, Modifier.width(180.dp), textStyle = JewelTheme.editorTextStyle, onKeyboardAction = KeyboardActionHandler { tab.reload() })
            Tooltip({ Text("Find (Enter in a field)") }) { IconButton(onClick = tab::reload) { Icon(AllIconsKeys.Actions.Execute, "Find") } }
            Divider(Orientation.Vertical, Modifier.height(18.dp).padding(horizontal = 4.dp))
            Tooltip({ Text("Previous page") }) { IconButton(onClick = tab::previousPage, enabled = tab.skip > 0) { Icon(AllIconsKeys.Actions.Back, "Previous page") } }
            val first = if (tab.documents.isEmpty()) 0 else tab.skip + 1
            Text("$first–${tab.skip + tab.documents.size}")
            Tooltip({ Text("Next page") }) { IconButton(onClick = tab::nextPage, enabled = tab.hasMore) { Icon(AllIconsKeys.Actions.Forward, "Next page") } }
            Text("of")
            if (tab.total == null) Link("?", onClick = { tab.count() }) else Text(tab.total.toString())
        }
        tab.message?.let {
            Text(
                it.text,
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                color = if (it.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info,
                maxLines = 3,
            )
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (tab.loading && tab.documents.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, "Loading ${tab.collection}…")
                    }
                }
                else if (tab.documents.isEmpty()) Text("No documents match.", Modifier.padding(10.dp), color = JewelTheme.globalColors.text.info)
                else DataGrid(tab.model, tab.grid, Modifier.fillMaxSize(), firstRowNumber = tab.skip + 1)
            }
            Divider(Orientation.Vertical, Modifier.fillMaxHeight())
            Inspector(tab, Modifier.width(400.dp).fillMaxHeight().padding(8.dp))
        }
    }
}

/** The shown document as JSON: edited in place and saved as a whole, as `replaceOne` does. */
@Composable
private fun Inspector(tab: DocumentBrowserTab, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    tab.creating -> "New document"
                    tab.shown != null -> "Document ${tab.skip + tab.shown!! + 1}"
                    else -> "Select a document"
                },
                color = JewelTheme.globalColors.text.info,
            )
            Spacer(Modifier.weight(1f))
            if (!tab.readOnly) Link("New", onClick = tab::startNew)
        }
        TextArea(
            tab.editor,
            Modifier.weight(1f).fillMaxWidth(),
            textStyle = JewelTheme.editorTextStyle,
            readOnly = tab.readOnly || (tab.shown == null && !tab.creating),
        )
        if (!tab.readOnly && (tab.shown != null || tab.creating)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = { tab.save() }) { Text(if (tab.creating) "Insert" else "Save") }
                if (tab.creating) OutlinedButton(onClick = { tab.show(null) }) { Text("Cancel") }
                else OutlinedButton(onClick = tab::deleteSelected) { Text("Delete") }
            }
        }
    }
}

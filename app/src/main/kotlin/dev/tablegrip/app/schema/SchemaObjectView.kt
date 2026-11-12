package dev.tablegrip.app.schema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.console.SqlEditor
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.metadata.SchemaObjectKind
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A function's, procedure's or sequence's properties above its source, which is read-only like a table's DDL. */
@Composable
internal fun SchemaObjectView(tab: SchemaObjectTab, modifier: Modifier = Modifier) {
    val definition = tab.definition
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(AllIconsKeys.Actions.Refresh, tr("Reload ({0})", Shortcuts.Reload)) { tab.load() }
            ToolButton(AllIconsKeys.Actions.Copy, tr("Copy source"), enabled = definition != null) { tab.copySource() }
            ToolButton(AllIconsKeys.Nodes.Console, tr("Open in new console"), enabled = definition != null) { tab.openInConsole() }
            Box(Modifier.weight(1f))
            if (tab.loading) Text(tr("Loading…"), Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info)
            Text(tab.qualifiedName, Modifier.padding(end = 8.dp), color = JewelTheme.globalColors.text.info, maxLines = 1)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }
        if (definition == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab.error == null) ConnectionLoadingPlaceholder(tab.config, tab.connectionStatus, tr("Loading {0}…", tab.ref.name), Modifier.align(Alignment.Center))
            }
            return@Column
        }
        val properties = definition.properties.filter { it.second != null }
        if (properties.isNotEmpty()) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    properties.forEach { (label, value) ->
                        Row {
                            Text(label, Modifier.width(160.dp), color = JewelTheme.globalColors.text.info, maxLines = 1)
                            Text(value.orEmpty(), maxLines = if (tab.ref.kind == SchemaObjectKind.Sequence) 1 else 3)
                        }
                    }
                }
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        }
        // Keyed on the source so a reload replaces the text.
        val text = remember(definition.source) { TextFieldState(definition.source) }
        SqlEditor(text, tab.dialect.syntax, tab.editorFontSize, Modifier.weight(1f).fillMaxWidth(), readOnly = true)
    }
}

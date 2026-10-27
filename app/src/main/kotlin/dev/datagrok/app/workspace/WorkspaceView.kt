package dev.datagrok.app.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.ConsoleView
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.table.TableDataView
import dev.datagrok.app.ui.LocalIslands
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun WorkspaceView(app: AppState) {
    val workspace = app.workspace
    val selected = workspace.selected
    if (selected == null) {
        EmptyWorkspace(app)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workspace.tabs.forEach { tab ->
                PillTab(
                    title = tab.title,
                    isConsole = tab is ConsoleTab,
                    dirty = tab is TableDataTab && tab.hasChanges,
                    selected = tab == selected,
                    onClick = { workspace.selected = tab },
                    onClose = { workspace.requestClose(tab) },
                )
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        // Keyed so each tab keeps its own scroll positions and split sizes.
        key(selected) {
            when (selected) {
                is ConsoleTab -> ConsoleView(app, selected)
                is TableDataTab -> TableDataView(selected)
                else -> Unit
            }
        }
    }
}

/** A rounded editor tab: filled with the accent when selected; middle-click closes it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PillTab(title: String, isConsole: Boolean, dirty: Boolean, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(30.dp)
            .clip(shape)
            .background(
                when {
                    selected -> islands.accent.copy(alpha = if (JewelTheme.isDark) 0.22f else 0.12f)
                    hovered -> islands.chipHover
                    else -> Color.Transparent
                }
            )
            .border(1.dp, if (selected) islands.accent.copy(alpha = 0.55f) else Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { if (it.buttons.isTertiaryPressed) onClose() }
            .padding(start = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (isConsole) AllIconsKeys.Nodes.Console else AllIconsKeys.Nodes.DataTables, null)
        Text(if (dirty) "$title •" else title, maxLines = 1)
        // Keeps the tab width stable: the close button only becomes visible on hover or selection.
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            if (selected || hovered) Icon(AllIconsKeys.General.CloseSmall, "Close")
        }
    }
}

@Composable
private fun EmptyWorkspace(app: AppState) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val info = JewelTheme.globalColors.text.info
        Text("Double-click a table to browse its data", color = info)
        Text("Right-click a connection → New Console to write SQL", color = info)
        app.currentConnection?.let { config ->
            OutlinedButton(onClick = { app.workspace.openConsole(config) }, Modifier.padding(top = 8.dp)) { Text("New Console for ${config.name}") }
        }
    }
}


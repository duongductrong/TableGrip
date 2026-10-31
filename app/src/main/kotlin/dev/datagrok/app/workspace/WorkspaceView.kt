package dev.datagrok.app.workspace

import dev.datagrok.app.documents.DocumentBrowserTab
import dev.datagrok.app.documents.DocumentBrowserView
import dev.datagrok.app.keyvalue.CommandConsoleTab
import dev.datagrok.app.keyvalue.CommandConsoleView
import dev.datagrok.app.keyvalue.KeyBrowserTab
import dev.datagrok.app.schema.ErDiagramTab
import dev.datagrok.app.schema.ErDiagramView
import dev.datagrok.app.schema.SchemaCompareTab
import dev.datagrok.app.schema.SchemaCompareView
import dev.datagrok.app.schema.TableDesignerTab
import dev.datagrok.app.schema.TableDesignerView
import dev.datagrok.app.keyvalue.KeyBrowserView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.datagrok.app.AppState
import dev.datagrok.app.connections.DataFileBanner
import dev.datagrok.app.activity.ActivityTab
import dev.datagrok.app.activity.ActivityView
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.ConsoleView
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.table.TableDataView
import dev.datagrok.app.ui.KeyCap
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.app.ui.Shortcuts
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
        TabStrip(workspace, selected)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        DataFileBanner(app, selected.connectionId)
        // Keyed so each tab keeps its own scroll positions and split sizes.
        key(selected) {
            when (selected) {
                is ConsoleTab -> ConsoleView(app, selected)
                is TableDataTab -> TableDataView(selected)
                is KeyBrowserTab -> KeyBrowserView(selected)
                is DocumentBrowserTab -> DocumentBrowserView(selected)
                is CommandConsoleTab -> CommandConsoleView(selected)
                is TableDesignerTab -> TableDesignerView(app, selected)
                is SchemaCompareTab -> SchemaCompareView(app, selected)
                is ErDiagramTab -> ErDiagramView(app, selected)
                is ActivityTab -> ActivityView(app, selected)
                else -> Unit
            }
        }
    }
}

/** The row of open tabs; dragging a tab sideways reorders it. */
@Composable
private fun TabStrip(workspace: WorkspaceViewModel, selected: WorkspaceTab) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val drag = remember(listState) { TabDragState(listState, scope, density, workspace::move) }
    LaunchedEffect(drag.scrollSpeed != 0f) { drag.autoScroll() }
    LazyRow(
        Modifier.fillMaxWidth().height(42.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(workspace.tabs, key = workspace::keyOf) { tab ->
            val key = workspace.keyOf(tab)
            val dragged = drag.draggedKey == key
            PillTab(
                title = tab.title,
                isConsole = tab is ConsoleTab || tab is CommandConsoleTab,
                dirty = tab.hasUnsavedWork,
                selected = tab == selected,
                lifted = dragged,
                onClick = { workspace.selected = tab },
                onClose = { workspace.requestClose(tab) },
                // The dragged tab follows the pointer above the others, which slide aside to make room for it.
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = if (dragged) null else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold),
                    )
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer { if (dragged) translationX = drag.offset }
                    .pointerInput(key) {
                        detectDragGestures(
                            onDragStart = {
                                workspace.selected = tab
                                drag.start(key)
                            },
                            onDragEnd = drag::end,
                            onDragCancel = drag::end,
                        ) { change, amount ->
                            change.consume()
                            drag.drag(amount.x)
                        }
                    },
            )
        }
    }
}

/** A rounded editor tab: filled with the accent when selected; middle-click closes it. [lifted] while being dragged. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PillTab(
    title: String,
    isConsole: Boolean,
    dirty: Boolean,
    selected: Boolean,
    lifted: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(30.dp)
            // Opaque underneath the translucent fill so tabs it passes over don't show through.
            .then(if (lifted) Modifier.shadow(6.dp, shape).background(islands.island, shape) else Modifier)
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
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyCap(Shortcuts.NewConsole.label)
            Text("new console", color = info)
            Box(Modifier.size(12.dp))
            KeyCap(Shortcuts.ShowShortcuts.label)
            Text("all shortcuts", color = info)
        }
    }
}


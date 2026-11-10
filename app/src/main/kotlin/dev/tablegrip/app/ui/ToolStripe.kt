package dev.tablegrip.app.ui

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.LeftTool
import dev.tablegrip.core.settings.Settings
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * A button the tool stripes can show; the user may hide it or move it within its [group] (Settings → Startup & Layout).
 * Settings itself is always there, so the way back is never hidden.
 */
enum class StripeItem(val label: String, val group: StripeGroup) {
    Explorer(tr("Connections"), StripeGroup.Tools),
    History(tr("Query History"), StripeGroup.Tools),
    Queries(tr("Saved Queries"), StripeGroup.Tools),
    GoTo(tr("Go to Anything"), StripeGroup.Actions),
    NewConnection(tr("New Connection"), StripeGroup.Actions),
    Shortcuts(tr("Keyboard Shortcuts"), StripeGroup.Bottom),
    Agent(tr("Agent"), StripeGroup.Right);

    companion object {
        /** [group]'s items in the user's order (unlisted ones keep their place after), hidden ones included. */
        fun ordered(group: StripeGroup, settings: Settings): List<StripeItem> =
            entries.filter { it.group == group }.sortedBy { item -> settings.stripeOrder.indexOf(item.name).let { if (it < 0) Int.MAX_VALUE else it } }

        fun visible(group: StripeGroup, settings: Settings): List<StripeItem> = ordered(group, settings).filter { it.name !in settings.stripeHidden }
    }
}

enum class StripeGroup { Tools, Actions, Bottom, Right }

/** The thin tool-window stripe on the frame's left edge, like the new JetBrains UI. */
@Composable
internal fun ToolStripe(app: AppState, modifier: Modifier = Modifier) {
    val tools = StripeItem.visible(StripeGroup.Tools, app.settings)
    val actions = StripeItem.visible(StripeGroup.Actions, app.settings)
    Column(
        modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tools.forEach { StripeItemButton(app, it) }
        if (tools.isNotEmpty() && actions.isNotEmpty()) Divider(Orientation.Horizontal, Modifier.width(20.dp).padding(vertical = 2.dp))
        actions.forEach { StripeItemButton(app, it) }
        Spacer(Modifier.weight(1f))
        StripeItem.visible(StripeGroup.Bottom, app.settings).forEach { StripeItemButton(app, it) }
        StripeButton(AllIconsKeys.General.Settings, tr("Settings ({0})", Shortcuts.Settings), selected = app.settingsOpen) { app.settingsOpen = !app.settingsOpen }
    }
}

/** Whether the right stripe has anything to show; without it the frame ends at the editor's island. */
internal fun hasRightStripe(app: AppState): Boolean = StripeItem.visible(StripeGroup.Right, app.settings).isNotEmpty()

/** The right edge's stripe, mirroring the left one: tool windows that sit beside the editor rather than before it. */
@Composable
internal fun RightToolStripe(app: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StripeItem.visible(StripeGroup.Right, app.settings).forEach { StripeItemButton(app, it) }
    }
}

@Composable
private fun StripeItemButton(app: AppState, item: StripeItem) {
    val (icon, tooltip, selected) =
        when (item) {
            StripeItem.Explorer -> Triple(AllIconsKeys.Nodes.DataSchema, tr("Connections ({0})", Shortcuts.Explorer), !app.settingsOpen && app.leftTool == LeftTool.Explorer)
            StripeItem.History -> Triple(AllIconsKeys.Vcs.History, tr("Query History ({0})", Shortcuts.History), !app.settingsOpen && app.leftTool == LeftTool.History)
            StripeItem.Queries -> Triple(AllIconsKeys.Nodes.Bookmark, tr("Saved Queries ({0})", Shortcuts.Queries), !app.settingsOpen && app.leftTool == LeftTool.Queries)
            StripeItem.GoTo -> Triple(AllIconsKeys.Actions.Search, tr("Go to Anything ({0})", Shortcuts.GoToAnything), app.goToOpen)
            StripeItem.NewConnection -> Triple(AllIconsKeys.General.Add, tr("New Connection"), false)
            StripeItem.Shortcuts -> Triple(AllIconsKeys.General.Keyboard, tr("Keyboard Shortcuts ({0})", Shortcuts.ShowShortcuts), app.shortcutsOpen)
            StripeItem.Agent -> Triple(AppIcons.Agent, tr("Agent ({0})", Shortcuts.Agent), app.agentOpen)
        }
    ContextMenuArea(items = { listOf(ContextMenuItem(tr("Hide {0}", item.label)) { app.updateSettings { it.copy(stripeHidden = it.stripeHidden + item.name) } }) }) {
        StripeButton(icon, tooltip, selected) {
            when (item) {
                StripeItem.Explorer -> app.toggleTool(LeftTool.Explorer)
                StripeItem.History -> app.toggleTool(LeftTool.History)
                StripeItem.Queries -> app.toggleTool(LeftTool.Queries)
                StripeItem.GoTo -> app.goToOpen = true
                StripeItem.NewConnection -> app.connections.openNew()
                StripeItem.Shortcuts -> app.shortcutsOpen = true
                StripeItem.Agent -> app.agentOpen = !app.agentOpen
            }
        }
    }
}

@Composable
private fun StripeButton(icon: IconKey, tooltip: String, selected: Boolean, onClick: () -> Unit) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip({ Text(tooltip) }) {
        Box(
            Modifier.size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        selected -> islands.accent
                        hovered -> islands.stripeHover
                        else -> Color.Transparent
                    }
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(key = icon, contentDescription = tooltip, tint = if (selected) Color.White else Color.Unspecified)
        }
    }
}


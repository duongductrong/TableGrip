package dev.datagrok.app.ui

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
import dev.datagrok.app.AppState
import dev.datagrok.app.LeftTool
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The thin tool-window stripe on the frame's left edge, like the new JetBrains UI. */
@Composable
internal fun ToolStripe(app: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StripeButton(AllIconsKeys.Nodes.DataSchema, "Connections (${Shortcuts.Explorer})", selected = !app.settingsOpen && app.leftTool == LeftTool.Explorer) {
            app.toggleTool(LeftTool.Explorer)
        }
        StripeButton(AllIconsKeys.Vcs.History, "Query History (${Shortcuts.History})", selected = !app.settingsOpen && app.leftTool == LeftTool.History) {
            app.toggleTool(LeftTool.History)
        }
        Divider(Orientation.Horizontal, Modifier.width(20.dp).padding(vertical = 2.dp))
        StripeButton(AllIconsKeys.General.Add, "New Connection", selected = false, onClick = app.connections::openNew)
        Spacer(Modifier.weight(1f))
        StripeButton(AllIconsKeys.General.Keyboard, "Keyboard Shortcuts (${Shortcuts.ShowShortcuts})", selected = app.shortcutsOpen) { app.shortcutsOpen = true }
        StripeButton(AllIconsKeys.General.Settings, "Settings (${Shortcuts.Settings})", selected = app.settingsOpen) { app.settingsOpen = !app.settingsOpen }
    }
}

/** The right edge's stripe, mirroring the left one: tool windows that sit beside the editor rather than before it. */
@Composable
internal fun RightToolStripe(app: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier.width(44.dp).fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StripeButton(AllIconsKeys.Toolwindows.ToolWindowAskAI, "Agent (${Shortcuts.Agent})", selected = app.agentOpen) { app.agentOpen = !app.agentOpen }
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


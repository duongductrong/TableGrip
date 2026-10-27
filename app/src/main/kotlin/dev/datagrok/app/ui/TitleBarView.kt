package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.AppState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.RunMode
import dev.datagrok.app.grid.isMac
import dev.datagrok.core.connection.ConnectionConfig
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.utils.clientRegion

/**
 * Islands-style header: plain frame colour (no project gradient) so it flows into the tool stripe below, with a
 * connection switcher on the left and the main actions on the right.
 */
@Composable
internal fun DecoratedWindowScope.TitleBarView(app: AppState) {
    val current = app.currentConnection

    TitleBar(Modifier.newFullscreenControls()) {
        Row(Modifier.align(Alignment.Start).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ConnectionWidget(app, current)
        }

        Text(app.workspace.selected?.title ?: title, color = JewelTheme.globalColors.text.info)

        Row(Modifier.align(Alignment.End).padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val console = app.workspace.selected as? ConsoleTab
            val primary = if (isMac) "⌘" else "Ctrl+"
            HeaderButton("new_console", AllIconsKeys.Nodes.Console, "New console", enabled = current != null) {
                current?.let(app.workspace::openConsole)
            }
            if (console?.running == true) {
                HeaderButton("stop", AllIconsKeys.Actions.Suspend, "Cancel query") { console.cancel() }
            } else {
                HeaderButton("run", AllIconsKeys.Actions.Execute, "Execute statement (${primary}Enter)", enabled = console != null) {
                    console?.run(RunMode.Current)
                }
            }
            HeaderButton("theme", if (app.theme.isDark()) AllIconsKeys.Actions.Lightning else AllIconsKeys.Actions.ProfileBlue, "Toggle light/dark") {
                app.toggleTheme()
            }
            HeaderButton("settings", AllIconsKeys.General.Settings, "Settings") { app.settingsOpen = true }
        }
    }
}

@Composable
private fun ConnectionWidget(app: AppState, current: ConnectionConfig?) {
    Dropdown(
        modifier = Modifier.height(30.dp).clientRegion("connection_widget"),
        menuContent = {
            app.connections.profiles.forEach { config ->
                selectableItem(selected = config.id == current?.id, onClick = { app.pickConnection(config) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(config, size = 18)
                        Text(config.name)
                        EnvironmentChip(config)
                        Text(config.type.displayName, color = JewelTheme.globalColors.text.info)
                    }
                }
            }
            if (app.connections.profiles.isNotEmpty()) separator()
            selectableItem(selected = false, iconKey = AllIconsKeys.General.Add, onClick = app.connections::openNew) { Text("New Connection…") }
            if (current != null) {
                selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Edit, onClick = { app.connections.openEdit(current) }) {
                    Text("Edit “${current.name}”…")
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current == null) {
                Text("No connection", color = JewelTheme.globalColors.text.info)
            } else {
                Badge(current, size = 20)
                Text(current.name, fontWeight = FontWeight.Medium)
                EnvironmentChip(current)
            }
        }
    }
}

/** Rounded square with the connection's initials, like a project badge. */
@Composable
internal fun Badge(config: ConnectionConfig, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(connectionColor(config)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(config.name), color = Color.White, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A small coloured PROD/STG/DEV/LOCAL tag; nothing for connections without an environment. */
@Composable
internal fun EnvironmentChip(config: ConnectionConfig) {
    val tag = environmentTag(config.environment) ?: return
    val color = connectionColor(config)
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tag, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeaderButton(region: String, icon: IconKey, tooltip: String, enabled: Boolean = true, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp).padding(3.dp).clientRegion(region)) {
            Icon(icon, tooltip)
        }
    }
}

package dev.tablegrip.app.ui

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.activity.ActivityTab
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.connections.connectingText
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.grid.SelectionStats
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.core.connection.Capability
import dev.tablegrip.core.connection.ConnectionConfig
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Amber for work that isn't saved yet: uncommitted statements, unsubmitted edits. */
private val PendingColor = Color(0xFFD9822B)
private val ConnectedColor = Color(0xFF5FB865)
private val ConnectingColor = Color(0xFFE0A33A)
private val FailedColor = Color(0xFFDB4B4B)

/**
 * The window's status bar, on the frame like IntelliJ's: where you are (connection › schema › table) and the latest
 * message on the left; on the right, small widgets for the tab in front — what is running, the selection, the caret,
 * the transaction mode, read-only — and the connection itself, each with its details in a tooltip and its actions in
 * a menu.
 */
@Composable
internal fun StatusBar(app: AppState) {
    val info = JewelTheme.globalColors.text.info
    val tab = app.workspace.selected?.takeIf { !app.settingsOpen }
    val connection = app.currentConnection
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(start = 10.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: breadcrumbs, then the latest message taking what room is left.
        if (app.settings.statusBreadcrumbs) Breadcrumbs(app, connection)
        Tooltip({ Text(app.status) }, Modifier.weight(1f)) {
            Text(app.status, Modifier.padding(horizontal = 4.dp), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Right: widgets of the tab in front, then app-wide ones, then the connection.
        RunningWidget(tab)
        if (app.settings.statusSelectionStats) SelectionWidget(tab)
        (tab as? TableDataTab)?.let { PendingEditsWidget(it) }
        (tab as? ConsoleTab)?.let {
            CaretWidget(it)
            TransactionWidget(it)
        }
        val tabConfig = (tab as? ConsoleTab)?.config ?: (tab as? TableDataTab)?.config ?: connection
        if (tabConfig?.readOnly == true) ReadOnlyWidget()
        ZoomControl(app)
        BlockedSessionsWidget(app)
        connection?.let {
            StatusDivider()
            ConnectionWidget(app, it)
        }
    }
}

/** Connection, then catalog/schema and table or console of the tab in front. */
@Composable
private fun Breadcrumbs(app: AppState, connection: ConnectionConfig?) {
    val info = JewelTheme.globalColors.text.info
    val crumbs =
        buildList {
            when (val tab = app.workspace.selected?.takeIf { !app.settingsOpen }) {
                is TableDataTab -> {
                    add(tab.config.name)
                    listOfNotNull(tab.table.namespace.catalog, tab.table.namespace.schema).forEach(::add)
                    add(tab.table.name)
                }
                is ConsoleTab -> {
                    add(tab.config.name)
                    if (tab.title != tab.config.name) add(tab.title)
                }
                else -> connection?.let { add(it.name) }
            }
        }
    if (crumbs.isEmpty()) return
    Row(
        Modifier.widthIn(max = 480.dp).padding(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connection != null) Badge(connection, size = 14)
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Icon(AllIconsKeys.General.ChevronRight, null, Modifier.size(12.dp), tint = info)
            Text(
                crumb,
                color = if (index == crumbs.lastIndex) JewelTheme.globalColors.text.normal else info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    StatusDivider()
}

/** A query running in the console in front, or a table page loading. */
@Composable
private fun RunningWidget(tab: Any?) {
    val text =
        when {
            tab is ConsoleTab && tab.running -> tr("Executing…")
            tab is TableDataTab && tab.loading -> tr("Loading rows…")
            else -> return
        }
    StatusWidget(tooltip = text) {
        CircularProgressIndicator(Modifier.size(12.dp))
        Text(text, color = JewelTheme.globalColors.text.info, maxLines = 1)
    }
}

/** Like a spreadsheet: count, sum and average of the selected cells, when there are several. */
@Composable
private fun SelectionWidget(tab: Any?) {
    val grid = (tab as? dev.tablegrip.app.workspace.WorkspaceTab)?.activeGrid ?: return
    val stats = SelectionStats.of(grid.first, grid.second) ?: return
    StatusWidget(tooltip = tr("{0} cell(s) selected · {1} numeric", stats.cells, stats.numbers)) {
        Text(stats.summary, color = JewelTheme.globalColors.text.normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Edits in a table viewer that haven't been submitted yet. */
@Composable
private fun PendingEditsWidget(tab: TableDataTab) {
    val changes = tab.model?.changeCount ?: 0
    if (changes == 0) return
    StatusWidget(tooltip = tr("{0} change(s) to {1} not submitted yet", changes, tab.table.name)) {
        Dot(PendingColor)
        Text(tr("{0} pending", changes), color = PendingColor, maxLines = 1)
    }
}

/** Line and column of the caret, and the size of the selection, like an editor's. */
@Composable
private fun CaretWidget(tab: ConsoleTab) {
    val text = tab.editor.text
    val selection = tab.editor.selection
    val caret = selection.end.coerceIn(0, text.length)
    val (line, column) =
        remember(text, caret) {
            var line = 1
            var lineStart = 0
            for (i in 0 until caret) if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
            line to caret - lineStart + 1
        }
    val selected = selection.length
    val lines = if (selected == 0) 0 else remember(text, selection) { text.subSequence(selection.min, selection.max).count { it == '\n' } + 1 }
    val tooltip = tr("Line {0}, column {1}", line, column) + if (selected > 0) tr(" · {0} character(s) selected on {1} line(s)", selected, lines) else ""
    StatusWidget(tooltip = tooltip) {
        Text(
            "$line:$column" + when {
                selected == 0 -> ""
                lines > 1 -> tr(" ({0} chars, {1} lines)", selected, lines)
                else -> tr(" ({0} chars)", selected)
            },
            color = JewelTheme.globalColors.text.info,
            maxLines = 1,
        )
    }
}

/** Auto-commit or manual commit, with the uncommitted count; its menu switches modes and ends the transaction. */
@Composable
private fun TransactionWidget(tab: ConsoleTab) {
    if (Capability.Transactions !in tab.config.type.capabilities) return
    val info = JewelTheme.globalColors.text.info
    var open by remember { mutableStateOf(false) }
    val enabled = !tab.running && !tab.config.readOnly
    Box {
        StatusWidget(
            tooltip =
                when {
                    tab.config.readOnly -> tr("Read-only connection: auto-commit")
                    tab.autoCommit -> tr("Each statement is committed as it runs. Click to switch to manual commit.")
                    tab.uncommitted > 0 -> tr("{0} statement(s) waiting for Commit or Roll Back", tab.uncommitted)
                    else -> tr("Changes stay pending until you commit them")
                },
            onClick = if (enabled) ({ open = true }) else null,
        ) {
            if (tab.hasOpenTransaction) Dot(PendingColor)
            Text(if (tab.autoCommit) tr("Auto-commit") else tr("Manual"), color = info, maxLines = 1)
            if (tab.uncommitted > 0) Text(tr("· {0} pending", tab.uncommitted), color = PendingColor, maxLines = 1)
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                selectableItem(selected = tab.autoCommit, onClick = { open = false; tab.changeAutoCommit(true) }) { Text(tr("Auto-commit")) }
                selectableItem(selected = !tab.autoCommit, onClick = { open = false; tab.changeAutoCommit(false) }) { Text(tr("Manual commit")) }
                if (!tab.autoCommit) {
                    separator()
                    selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Commit, enabled = tab.uncommitted > 0, onClick = { open = false; tab.commit() }) {
                        Text(tr("Commit"))
                    }
                    selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Rollback, enabled = tab.uncommitted > 0, onClick = { open = false; tab.rollback() }) {
                        Text(tr("Roll Back"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyWidget() {
    StatusWidget(tooltip = tr("Read-only connection: writes and DDL are rejected")) {
        Icon(AllIconsKeys.Nodes.Padlock, null, Modifier.size(12.dp), tint = JewelTheme.globalColors.text.info)
        Text(tr("Read-only"), color = JewelTheme.globalColors.text.info, maxLines = 1)
    }
}

/** Sessions stuck behind a lock on a server whose Activity tab is open; clicking shows them. */
@Composable
private fun BlockedSessionsWidget(app: AppState) {
    val tab = app.workspace.tabs.filterIsInstance<ActivityTab>().filter { it.stuck > 0 }.maxByOrNull { it.connectionId == app.currentConnectionId } ?: return
    StatusWidget(tooltip = tr("Sessions on {0} waiting on a lock for more than 5 s. Click to show them.", tab.config.name), onClick = tab::showBlocked) {
        Icon(AllIconsKeys.General.Warning, null, Modifier.size(12.dp))
        Text(tr("{0} blocked", tab.stuck), color = FailedColor, maxLines = 1)
    }
}

/**
 * The connection of the tab in front: a dot for its state, the engine and where it points. The tooltip has the
 * details; the menu connects, disconnects and edits it.
 */
@Composable
private fun ConnectionWidget(app: AppState, config: ConnectionConfig) {
    val info = JewelTheme.globalColors.text.info
    val status = app.databases.status(config.id)
    var open by remember { mutableStateOf(false) }
    val (dotColor, stateText) =
        when (status) {
            is ConnectionStatus.Connected -> ConnectedColor to tr("Connected")
            is ConnectionStatus.Connecting -> ConnectingColor to connectingText(config, status)
            is ConnectionStatus.Failed -> FailedColor to tr("Failed: {0}", status.message)
            ConnectionStatus.Disconnected -> null to tr("Not connected")
        }
    Box {
        StatusWidget(
            tooltip = { ConnectionTooltip(config, stateText) },
            onClick = { open = true },
        ) {
            if (status is ConnectionStatus.Connecting) CircularProgressIndicator(Modifier.size(10.dp)) else Dot(dotColor, hollow = dotColor == null)
            Text(config.type.displayName, color = JewelTheme.globalColors.text.normal, maxLines = 1)
            endpoint(config)?.let { Text(it, Modifier.widthIn(max = 220.dp), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (open) {
            val close = { open = false }
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                when (status) {
                    is ConnectionStatus.Connected -> {
                        selectableItem(selected = false, onClick = { close(); reconnect(app, config) }) { Text(tr("Reconnect")) }
                        selectableItem(selected = false, onClick = { close(); app.explorer.disconnect(config.id) }) { Text(tr("Disconnect")) }
                    }
                    is ConnectionStatus.Connecting -> selectableItem(selected = false, enabled = false, onClick = close) { Text(tr("Connecting…")) }
                    else -> selectableItem(selected = false, onClick = { close(); connect(app, config) }) { Text(tr("Connect")) }
                }
                separator()
                selectableItem(selected = false, iconKey = AllIconsKeys.Actions.Edit, onClick = { close(); app.connections.openEdit(config) }) {
                    Text(tr("Edit “{0}”…", config.name))
                }
            }
        }
    }
}

@Composable
private fun ConnectionTooltip(config: ConnectionConfig, state: String) {
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(config.name)
        Text(state, color = info)
        val rows =
            buildList {
                add(tr("Engine") to config.type.displayName)
                when {
                    config.type.isFile -> add(tr("File") to config.database)
                    config.url != null -> add(tr("URL") to config.url!!)
                    else -> {
                        add(tr("Host") to "${config.host}${(config.port ?: config.type.defaultPort)?.let { ":$it" }.orEmpty()}")
                        if (config.database.isNotBlank()) add(tr("Database") to config.database)
                    }
                }
                if (config.user.isNotBlank() && !config.type.isFile) add(tr("User") to config.user)
                config.ssh?.let { add(tr("SSH") to it.host) }
                environmentTag(config.environment)?.let { _ -> add(tr("Environment") to config.environment.label) }
                if (config.readOnly) add(tr("Mode") to tr("Read-only"))
            }
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.width(80.dp), color = info)
                Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where the connection points, short: `host:port/db`, a file's name, or nothing for a URL profile. */
private fun endpoint(config: ConnectionConfig): String? =
    when {
        config.type.isFile -> config.database.substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
        config.url != null -> null
        else -> {
            val port = (config.port ?: config.type.defaultPort)?.let { ":$it" }.orEmpty()
            val db = config.database.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
            "${config.host}$port$db"
        }
    }

private fun connect(app: AppState, config: ConnectionConfig) {
    app.scope.launch {
        runCatching { app.databases.connection(config) }
            .onSuccess { app.status = tr("Connected to {0}", config.name) }
            .onFailure { app.status = tr("Couldn't connect to {0}: {1}", config.name, it.message) }
    }
}

private fun reconnect(app: AppState, config: ConnectionConfig) =
    app.confirmDataFiles(listOf(config.id)) {
        app.explorer.disconnectNow(config.id)
        connect(app, config)
    }

/**
 * One status bar widget: compact, flat, with a hover highlight only when it does something on click, like the
 * widgets of IntelliJ's status bar.
 */
@Composable
private fun StatusWidget(tooltip: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) =
    StatusWidget(tooltip = { Text(tooltip) }, onClick = onClick, content = content)

@Composable
private fun StatusWidget(tooltip: @Composable () -> Unit, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip(tooltip) {
        Row(
            Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(if (onClick != null && hovered) Modifier.background(islands.chipHover) else Modifier)
                .hoverable(interaction)
                .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** A thin vertical rule between the left and right halves, and before the connection. */
@Composable
private fun StatusDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(12.dp).background(JewelTheme.globalColors.borders.normal))
}

/** A small state dot; hollow when there is no state to show (e.g. not connected). */
@Composable
private fun Dot(color: Color?, hollow: Boolean = false) {
    val outline = JewelTheme.globalColors.text.info
    Canvas(Modifier.size(7.dp)) {
        val radius = size.minDimension / 2
        if (hollow || color == null) drawCircle(outline, radius - 0.5.dp.toPx(), center = Offset(radius, radius), style = Stroke(1.dp.toPx()))
        else drawCircle(color, radius, center = Offset(radius, radius))
    }
}

package dev.tablegrip.app.activity

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.schema.EmptyState
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.activity.ActivitySupport
import dev.tablegrip.core.activity.SessionActivity
import dev.tablegrip.core.activity.SessionState
import java.time.Duration
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun ActivityView(app: AppState, tab: ActivityTab) {
    val support = tab.support
    if (support is ActivitySupport.NotYet) {
        Box(Modifier.fillMaxSize()) { EmptyState("Activity isn't available", support.reason, Modifier.align(Alignment.Center)) }
        return
    }
    // Refreshes only while the tab is in front: leaving it disposes this effect and stops the loop.
    LaunchedEffect(tab, tab.interval) { tab.watch() }
    Column(Modifier.fillMaxSize()) {
        ActivityToolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }
        if (tab.missingPrivileges.isNotEmpty()) PrivilegesBanner(tab)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.snapshot == null) {
                if (tab.error == null) ConnectionLoadingPlaceholder(tab.config, app.databases.status(tab.config.id), "Reading sessions…", Modifier.align(Alignment.Center))
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val detailsWidth = if (maxWidth < 1000.dp) 300.dp else 380.dp
                    Row(Modifier.fillMaxSize()) {
                        SessionList(tab, Modifier.weight(1f).fillMaxHeight())
                        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
                        SessionDetails(tab, Modifier.width(detailsWidth).fillMaxHeight())
                    }
                }
            }
        }
        StatusLine(tab)
    }
    tab.pending?.let { ConfirmSignalDialog(tab, it) }
}

@Composable
private fun ActivityToolbar(tab: ActivityTab) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(AllIconsKeys.Actions.Refresh, "Refresh now", enabled = !tab.loading) { tab.refreshNow() }
        Text("Every", color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
        for (seconds in listOf(2, 5, 10)) ViewChip("${seconds}s", tab.interval == seconds) { tab.interval = seconds }
        ViewChip("Paused", tab.interval == 0) { tab.interval = 0 }
        Separator()
        for (filter in ActivityFilter.entries) ViewChip(filter.label, tab.filter == filter) { tab.filter = filter }
        Separator()
        ViewChip("Hide TableGrip", tab.hideTableGrip) { tab.hideTableGrip = !tab.hideTableGrip }
        Spacer(Modifier.width(6.dp))
        // Shrinks before anything else when the tab is narrow.
        Box(Modifier.weight(1f)) {
            TextField(
                tab.search,
                Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                placeholder = { Text("Filter by user, database, query…", maxLines = 1) },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 4.dp)) },
            )
        }
        if (tab.config.readOnly) Text("read-only", Modifier.padding(end = 6.dp), color = JewelTheme.globalColors.text.info)
        EnvironmentChip(tab.config)
    }
}

@Composable
private fun PrivilegesBanner(tab: ActivityTab) {
    Row(
        Modifier.fillMaxWidth().background(WarningColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Warning, null)
        Column(Modifier.weight(1f)) {
            Text("${tab.config.user.ifBlank { "This user" }} lacks some privileges, so the list may be incomplete:")
            tab.missingPrivileges.forEach { Text("• $it", color = JewelTheme.globalColors.text.info) }
        }
        Link("Dismiss", onClick = { tab.missingPrivileges = emptyList() })
    }
}

/** List columns, with the order they give way in when the list is narrow; the query takes what is left. */
private enum class ListColumn(val title: String, val width: Dp, val dropOrder: Int) {
    Pid("PID", 90.dp, Int.MAX_VALUE),
    User("User", 110.dp, 3),
    Database("Database", 110.dp, 2),
    Application("Application", 130.dp, 0),
    State("State", 150.dp, Int.MAX_VALUE),
    Time("Time", 90.dp, Int.MAX_VALUE),
    Wait("Waiting for", 150.dp, 1),
}

/** The query column's least width before other columns give way. */
private val MinQueryWidth = 220.dp

private fun columnsFitting(width: Dp): Set<ListColumn> {
    val shown = ListColumn.entries.toMutableSet()
    for (column in ListColumn.entries.filter { it.dropOrder != Int.MAX_VALUE }.sortedBy { it.dropOrder }) {
        if (shown.sumOf { it.width.value.toDouble() }.dp + MinQueryWidth <= width) break
        shown -= column
    }
    return shown
}

private fun ListColumn.cell(): Modifier = Modifier.width(width).padding(end = 8.dp)

@Composable
private fun SessionList(tab: ActivityTab, modifier: Modifier) {
    val rows = tab.rows()
    BoxWithConstraints(modifier) {
        val columns = columnsFitting(maxWidth - 16.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (column in ListColumn.entries) if (column in columns) Text(column.title, column.cell(), fontWeight = FontWeight.SemiBold, color = JewelTheme.globalColors.text.info, maxLines = 1)
                Text("Query", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = JewelTheme.globalColors.text.info, maxLines = 1)
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    val detail =
                        when (tab.filter) {
                            ActivityFilter.Blocked -> "No session is waiting on a lock."
                            ActivityFilter.Running -> "Every session is idle. Choose All to list them too."
                            ActivityFilter.All -> "No sessions match."
                        }
                    EmptyState("Nothing to show", detail, Modifier.align(Alignment.Center))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.session.id }) { row -> SessionRow(tab, row, columns) }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(tab: ActivityTab, row: ActivityRow, columns: Set<ListColumn>) {
    val session = row.session
    val islands = LocalIslands.current
    val selected = tab.selectedId == session.id
    val info = JewelTheme.globalColors.text.info
    ContextMenuArea(items = { contextMenu(tab, session) }) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) islands.accent.copy(alpha = if (JewelTheme.isDark) 0.25f else 0.14f) else Color.Transparent)
                .clickable { tab.selectedId = session.id }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(ListColumn.Pid.cell(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(14.dp * row.depth))
                if (row.depth > 0) Text("└ ", color = info)
                Text(session.id, maxLines = 1, fontWeight = if (row.blocking > 0) FontWeight.SemiBold else null)
            }
            if (ListColumn.User in columns) Text(session.user.orEmpty(), ListColumn.User.cell(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ListColumn.Database in columns) Text(session.database.orEmpty(), ListColumn.Database.cell(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ListColumn.Application in columns) {
                Text(
                    if (session.own) "this monitor" else session.application.orEmpty(),
                    ListColumn.Application.cell(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (session.own) info else Color.Unspecified,
                )
            }
            Row(ListColumn.State.cell(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(session.state)))
                // The full label is in the details panel; the list keeps it short so "blocks N" fits.
                val label = if (session.state == SessionState.IdleInTransaction) "idle in tx" else session.state.label
                Text(if (row.blocking > 0) "$label · blocks ${row.blocking}" else label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val running = tab.runningFor(session)
            Text(running?.let(::formatDuration).orEmpty(), ListColumn.Time.cell(), maxLines = 1, color = durationColor(running, session.state))
            if (ListColumn.Wait in columns) Text(session.wait.orEmpty(), ListColumn.Wait.cell(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = info)
            Text(
                session.query?.replace(Regex("\\s+"), " ").orEmpty(),
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = JewelTheme.editorTextStyle,
                color = if (session.state == SessionState.Idle) info else Color.Unspecified,
            )
        }
    }
}

private fun contextMenu(tab: ActivityTab, session: SessionActivity): List<ContextMenuItem> =
    listOfNotNull(
        ContextMenuItem("Open Query in Console") { tab.openInConsole(session) }.takeIf { session.query != null },
        ContextMenuItem("Copy Query") { copyToClipboard(session.query.orEmpty()) }.takeIf { session.query != null },
        ContextMenuItem("Copy PID") { copyToClipboard(session.id) },
        ContextMenuItem("Cancel Query") { tab.requestSignal(session, terminate = false) }.takeIf { tab.signalsAllowed && !session.own },
        ContextMenuItem("Terminate Session…") { tab.requestSignal(session, terminate = true) }.takeIf { tab.signalsAllowed && !session.own },
    )

@Composable
private fun SessionDetails(tab: ActivityTab, modifier: Modifier) {
    val session = tab.selected
    if (session == null) {
        Box(modifier) { EmptyState("No session selected", "Select a session to see its query and act on it.", Modifier.align(Alignment.Center).padding(16.dp)) }
        return
    }
    val info = JewelTheme.globalColors.text.info
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(session.state)))
            Text("Session ${session.id}", fontWeight = FontWeight.SemiBold)
            Text(session.state.label, color = info)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Detail("User", session.user)
            Detail("Database", session.database)
            Detail("Client", session.client)
            Detail("Application", if (session.own) "this monitor" else session.application)
            Detail("Waiting for", session.wait)
            Detail("Blocked by", session.blockedBy.joinToString().ifEmpty { null })
            Detail("Query running", tab.runningFor(session)?.takeIf { session.state != SessionState.IdleInTransaction }?.let(::formatDuration))
            Detail("Transaction open", tab.snapshot?.let { snap -> session.transactionStart?.let { formatDuration(Duration.between(it, snap.serverTime)) } })
        }
        val query = remember(session.id, session.query) { TextFieldState(session.query.orEmpty()) }
        TextArea(query, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { tab.openInConsole(session) }, enabled = session.query != null) { Text("Open in Console") }
            OutlinedButton(onClick = { copyToClipboard(session.query.orEmpty()) }, enabled = session.query != null) { Text("Copy") }
        }
        val reason =
            when {
                session.own -> "This is the monitor's own session."
                !tab.signalsAllowed -> "The connection is read-only."
                else -> null
            }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalButton("Cancel Query", reason ?: "Stop the running query; the session stays connected.", enabled = reason == null && session.state != SessionState.Idle) {
                tab.requestSignal(session, terminate = false)
            }
            SignalButton("Terminate…", reason ?: "End the session and roll back its transaction.", enabled = reason == null) {
                tab.requestSignal(session, terminate = true)
            }
        }
    }
}

@Composable
private fun SignalButton(label: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) { OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) } }
}

@Composable
private fun Detail(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text(label, Modifier.width(120.dp), color = JewelTheme.globalColors.text.info)
        Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusLine(tab: ActivityTab) {
    val sessions = tab.snapshot?.sessions.orEmpty()
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val info = JewelTheme.globalColors.text.info
        Text("${sessions.size} session(s)", color = info)
        Text("${sessions.count { it.state == SessionState.Active }} active", color = info)
        val blocked = sessions.count { it.state == SessionState.Blocked }
        Text("$blocked blocked", color = if (blocked > 0) BlockedColor else info)
        Text("${sessions.count { it.state == SessionState.IdleInTransaction }} idle in transaction", color = info)
        Box(Modifier.weight(1f))
        Text(if (tab.interval == 0) "Paused" else if (tab.loading) "Refreshing…" else "Every ${tab.interval}s", color = info)
    }
}

@Composable
private fun ConfirmSignalDialog(tab: ActivityTab, signal: PendingSignal) {
    val session = signal.session
    val title = if (signal.terminate) "Terminate Session ${session.id}?" else "Cancel Query in Session ${session.id}?"
    AppDialog(onCloseRequest = { tab.pending = null }, size = DpSize(560.dp, 360.dp), title = title) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.General.Warning, null)
                Text("${session.user ?: "?"} on ${session.database ?: "?"} · ${session.state.label}", fontWeight = FontWeight.SemiBold)
                EnvironmentChip(tab.config)
            }
            signal.reasons.forEach { Text("• $it") }
            val text = remember(session) { TextFieldState(session.query.orEmpty()) }
            TextArea(text, Modifier.weight(1f).fillMaxWidth(), readOnly = true, textStyle = JewelTheme.editorTextStyle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { tab.pending = null }) { Text("Keep Running") }
                DefaultButton(onClick = tab::confirmPending) { Text(if (signal.terminate) "Terminate" else "Cancel Query") }
            }
        }
    }
}

private val WarningColor = Color(0xFFD9822B)
private val BlockedColor = Color(0xFFDB4B4B)

private fun stateColor(state: SessionState): Color =
    when (state) {
        SessionState.Active -> Color(0xFF4CA35A)
        SessionState.Blocked -> BlockedColor
        SessionState.IdleInTransaction -> WarningColor
        SessionState.Idle -> Color(0xFF8C8C8C)
        SessionState.Other -> Color(0xFF3574F0)
    }

/** Amber past 10 s, red past a minute: long queries and long-open transactions are what people come here to find. */
@Composable
private fun durationColor(duration: Duration?, state: SessionState): Color =
    when {
        duration == null || state == SessionState.Idle -> JewelTheme.globalColors.text.info
        duration.seconds >= 60 -> BlockedColor
        duration.seconds >= 10 -> WarningColor
        else -> Color.Unspecified
    }

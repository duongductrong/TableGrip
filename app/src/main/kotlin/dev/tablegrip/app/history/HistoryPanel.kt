package dev.tablegrip.app.history

import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.core.history.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val Time = I18n.dates("MMM d, HH:mm").withZone(ZoneId.systemDefault())

/** Every executed statement across connections; clicking one sends it to a console of its connection. */
@Composable
internal fun HistoryPanel(app: AppState) {
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<HistoryEntry>()) }
    val search = rememberTextFieldState()
    LaunchedEffect(refresh) { entries = withContext(Dispatchers.IO) { runCatching { app.history.recent(limit = 500) }.getOrDefault(emptyList()) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Query History"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Tooltip({ Text(tr("Refresh")) }) { IconButton(onClick = { refresh++ }) { Icon(AllIconsKeys.Actions.Refresh, tr("Refresh")) } }
        }
        TextField(search, Modifier.fillMaxWidth().padding(horizontal = 8.dp), placeholder = { Text(tr("Search queries")) })
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(top = 6.dp))

        val query = search.text.toString().trim()
        val shown = if (query.isEmpty()) entries else entries.filter { it.sql.contains(query, ignoreCase = true) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (entries.isEmpty()) tr("Queries you run show up here") else tr("No matches"), color = JewelTheme.globalColors.text.info)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { entry -> HistoryRow(app, entry) }
        }
    }
}

@Composable
private fun HistoryRow(app: AppState, entry: HistoryEntry) {
    val config = app.connections.byId(entry.connectionId)
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = config != null) {
                val console = app.workspace.selected as? ConsoleTab
                if (console != null && console.connectionId == entry.connectionId) console.insert(entry.sql)
                else app.workspace.openConsole(config!!, entry.sql + ";")
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            entry.sql.replace(Regex("\\s+"), " "),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = JewelTheme.editorTextStyle,
            color = if (entry.error != null) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val info = JewelTheme.globalColors.text.info
            if (config != null) {
                Badge(config, size = 14)
                Text(config.name, Modifier.weight(1f, fill = false), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                tr("· {0} · {1} ms", Time.format(Instant.ofEpochMilli(entry.executedAt)), entry.elapsedMillis),
                color = info,
                maxLines = 1,
                softWrap = false,
            )
            // Statements an AI app ran through `tablegrip mcp`, named after the app.
            entry.source?.takeIf { it.startsWith("mcp:") }?.let { source ->
                Text(
                    tr("· MCP: {0}", source.removePrefix("mcp:")),
                    color = JewelTheme.globalColors.text.warning,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

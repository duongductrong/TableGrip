package dev.tablegrip.app.search

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import dev.tablegrip.app.ui.Island
import dev.tablegrip.app.ui.KeyCap
import dev.tablegrip.app.ui.LocalIslands
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.tablegrip.app.AppState
import dev.tablegrip.app.LeftTool
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.console.ConsoleTab
import dev.tablegrip.app.table.StructureSection
import dev.tablegrip.app.table.TableDataTab
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.app.ui.Shortcut
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.activity.ActivitySupport
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Paradigm
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** Groups of results, in the order they rank when scores tie. */
enum class GoToKind(val heading: String) {
    Tab(tr("Open tabs")),
    Table(tr("Tables")),
    View(tr("Views")),
    Query(tr("Saved queries")),
    Connection(tr("Connections")),
    Action(tr("Actions")),
    History(tr("Recent queries")),
}

/**
 * One result. [open] runs on Enter or click; [openAlt] on ⇧Enter, e.g. a table's structure instead of its data.
 * [connection] shows its badge; [shortcut] is shown for actions.
 */
class GoToItem(
    val kind: GoToKind,
    val label: String,
    val detail: String? = null,
    val icon: IconKey? = null,
    val connection: ConnectionConfig? = null,
    val shortcut: Shortcut? = null,
    val altLabel: String? = null,
    val openAlt: (() -> Unit)? = null,
    val open: () -> Unit,
)

/** Results for [query], best first; a blank query lists open tabs, connections and actions. */
fun rankGoTo(items: List<GoToItem>, query: String, limit: Int = 80): List<GoToItem> {
    if (query.isBlank()) return items.filter { it.kind != GoToKind.History && it.kind != GoToKind.Table && it.kind != GoToKind.View }.take(limit)
    val scored =
        items.mapNotNull { item ->
            val score = fuzzyScore(item.label, query) ?: item.detail?.let { fuzzyScore("${it} ${item.label}", query) }?.minus(20) ?: return@mapNotNull null
            item to score
        }
    // One section per kind, so headings don't repeat: the section with the best match comes first, best first within.
    return scored
        .groupBy { it.first.kind }
        .entries
        .sortedWith(compareByDescending<Map.Entry<GoToKind, List<Pair<GoToItem, Int>>>> { entry -> entry.value.maxOf { it.second } }.thenBy { it.key.ordinal })
        .flatMap { entry -> entry.value.sortedByDescending { it.second } }
        .take(limit)
        .map { it.first }
}

/** Everything go-to-anything can reach right now; tables come from connections already connected. */
fun goToItems(app: AppState): List<GoToItem> = buildList {
    val workspace = app.workspace
    workspace.tabs.forEach { tab ->
        val config = app.connections.byId(tab.connectionId)
        add(
            GoToItem(
                GoToKind.Tab,
                tab.title,
                detail = config?.name,
                icon = if (tab is ConsoleTab) AllIconsKeys.Nodes.Console else AllIconsKeys.Nodes.DataTables,
                connection = config,
            ) { workspace.selected = tab }
        )
    }
    app.savedQueries.queries.forEach { query ->
        val config = query.connection?.let { name -> app.connections.profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        add(
            GoToItem(GoToKind.Query, query.name, detail = query.folder.ifEmpty { query.connection }, icon = AllIconsKeys.FileTypes.Text, connection = config) {
                app.savedQueries.open(query)
            }
        )
    }
    app.connections.profiles.forEach { config ->
        add(GoToItem(GoToKind.Connection, config.name, detail = config.type.displayName, connection = config) { app.pickConnection(config) })
        val connected = app.databases.status(config.id) is ConnectionStatus.Connected
        if (connected && config.type.paradigm == Paradigm.Relational) {
            app.schemaCache(config).allTables().forEach { (ref, isView) ->
                val namespace = ref.namespace.displayName
                add(
                    GoToItem(
                        if (isView) GoToKind.View else GoToKind.Table,
                        ref.name,
                        detail = listOf(config.name, namespace).filter { it.isNotEmpty() }.joinToString(" · "),
                        icon = AllIconsKeys.Nodes.DataTables,
                        connection = config,
                        altLabel = tr("structure"),
                        openAlt = { workspace.openTable(config, ref, StructureSection.Columns) },
                    ) { workspace.openTable(config, ref) }
                )
            }
        }
    }
    actions(app).forEach(::add)
    // Recent statements of the current connection, deduplicated; others are a click away in Query History.
    val current = app.currentConnection
    if (current != null) {
        runCatching { app.history.recent(current.id, limit = 300) }.getOrDefault(emptyList())
            .filter { it.error == null }
            .distinctBy { it.sql.trim() }
            .take(100)
            .forEach { entry ->
                val oneLine = entry.sql.replace(Regex("\\s+"), " ").trim()
                add(GoToItem(GoToKind.History, oneLine, detail = current.name, icon = AllIconsKeys.Vcs.History, connection = current) {
                    app.workspace.openConsole(current, entry.sql)
                })
            }
    }
}

private fun actions(app: AppState): List<GoToItem> {
    val workspace = app.workspace
    fun action(label: String, shortcut: Shortcut? = null, icon: IconKey? = null, run: () -> Unit) =
        GoToItem(GoToKind.Action, label, icon = icon ?: AllIconsKeys.Actions.Lightning, shortcut = shortcut, open = run)
    return listOfNotNull(
        app.currentConnection?.let { config -> action(tr("New Console"), Shortcuts.NewConsole, AllIconsKeys.Nodes.Console) { workspace.openConsole(config) } },
        action(tr("New Connection…"), icon = AllIconsKeys.General.Add) { app.connections.openNew() },
        action(tr("Import Connections…"), icon = AllIconsKeys.ToolbarDecorator.Import) { app.connections.openImport() },
        (workspace.selected as? ConsoleTab)?.let { tab -> action(tr("Save Query…"), Shortcuts.Save, AllIconsKeys.Actions.MenuSaveall) { tab.save() } },
        action(tr("Saved Queries"), Shortcuts.Queries) { app.toggleTool(LeftTool.Queries) },
        app.currentConnection?.takeUnless { ActivitySupport.of(it.type) == ActivitySupport.NotApplicable }?.let { config ->
            action(tr("Activity: {0}", config.name), Shortcuts.Activity) { workspace.openActivity(config) }
        },
        action(tr("Connections"), Shortcuts.Explorer) { app.toggleTool(LeftTool.Explorer) },
        action(tr("Query History"), Shortcuts.History) { app.toggleTool(LeftTool.History) },
        action(tr("Show / Hide Sidebar"), Shortcuts.ToggleSidebar) { app.toggleSidebar() },
        action(tr("Agent"), Shortcuts.Agent) { app.agentOpen = !app.agentOpen },
        action(tr("Settings"), Shortcuts.Settings, AllIconsKeys.General.Settings) { app.settingsOpen = true },
        action(tr("Keyboard Shortcuts"), Shortcuts.ShowShortcuts, AllIconsKeys.General.Keyboard) { app.shortcutsOpen = true },
        app.updater.takeIf { it.unavailableReason == null }?.let { updater -> action(tr("Check for Updates…"), icon = AllIconsKeys.Actions.CheckOut) { updater.checkNow() } },
        (workspace.selected as? TableDataTab)?.let { tab -> action(tr("Reload Table"), Shortcuts.Reload, AllIconsKeys.Actions.Refresh) { tab.refresh() } },
        workspace.selected?.let { tab -> action(tr("Close Tab"), Shortcuts.CloseTab) { workspace.requestClose(tab) } },
    )
}

/**
 * Go to anything (⌘P, or ⇧⇧): one field that finds tables,
 * open tabs, saved and recent queries, connections and actions. ↑↓ choose, Enter opens, ⇧Enter opens a table's structure.
 */
@Composable
internal fun GoToAnythingPopup(app: AppState) {
    val query = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    var selected by remember { mutableIntStateOf(0) }
    val needle = query.text.toString()
    // Re-reads when schema metadata arrives, so tables of a connection that just loaded show up while typing.
    val versions = app.connections.profiles.filter { app.databases.status(it.id) is ConnectionStatus.Connected }.sumOf { app.schemaCache(it).version }
    val all = remember(versions, app.workspace.tabs.size, app.savedQueries.queries.size) { goToItems(app) }
    val results = remember(all, needle) { rankGoTo(all, needle) }
    LaunchedEffect(needle) { selected = 0 }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun close() {
        app.goToOpen = false
    }

    fun accept(index: Int, alt: Boolean) {
        val item = results.getOrNull(index) ?: return
        close()
        if (alt && item.openAlt != null) item.openAlt.invoke() else item.open()
    }

    // MainScreen dims the window behind it; clicking outside the panel closes it.
    val offset = with(LocalDensity.current) { IntOffset(0, 72.dp.roundToPx()) }
    Popup(alignment = Alignment.TopCenter, offset = offset, onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
        val islands = LocalIslands.current
        val outer = RoundedCornerShape(PanelCornerRadius)
        // Built like the app's dialogs: an island floating on the frame colour, so it matches the main window.
        Box(Modifier.padding(24.dp)) {
            Box(Modifier.width(660.dp).shadow(20.dp, outer).clip(outer).background(islands.frame).padding(PanelInset)) {
                Island(shape = RoundedCornerShape(PanelCornerRadius - PanelInset)) {
                    Column(Modifier.fillMaxWidth()) {
                        TextField(
                            query,
                            Modifier.fillMaxWidth().padding(8.dp).focusRequester(focus).onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> selected = (selected + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                    Key.DirectionUp -> selected = (selected - 1).coerceAtLeast(0)
                                    Key.PageDown -> selected = (selected + 8).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                    Key.PageUp -> selected = (selected - 8).coerceAtLeast(0)
                                    Key.Enter, Key.NumPadEnter -> accept(selected, alt = event.isShiftPressed)
                                    Key.Escape -> close()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                            placeholder = { Text(tr("Search tables, tabs, queries, connections and actions")) },
                            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
                        )
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        val listState = rememberLazyListState()
                        LaunchedEffect(selected) {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            if (visible.none { it.index == selected && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
                                listState.scrollToItem((selected - 4).coerceAtLeast(0))
                            }
                        }
                        if (results.isEmpty()) {
                            Text(tr("Nothing found"), Modifier.padding(16.dp), color = JewelTheme.globalColors.text.info)
                        } else {
                            LazyColumn(Modifier.heightIn(max = 420.dp).padding(4.dp), state = listState) {
                                itemsIndexed(results) { index, item ->
                                    val previous = results.getOrNull(index - 1)
                                    if (previous == null || previous.kind != item.kind) {
                                        Text(
                                            item.kind.heading,
                                            Modifier.padding(start = 8.dp, top = if (previous == null) 4.dp else 10.dp, bottom = 2.dp),
                                            color = JewelTheme.globalColors.text.info,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    ResultRow(item, index == selected) { accept(index, alt = false) }
                                }
                            }
                        }
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("↑↓" to tr("choose"), "⏎" to tr("open"), "⇧⏎" to tr("open structure"), "Esc" to tr("close")).forEach { (keys, label) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    KeyCap(keys)
                                    Text(label, color = JewelTheme.globalColors.text.info)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Like the app's dialogs: the island sits [PanelInset] inside the frame, its corners concentric with the panel's. */
private val PanelCornerRadius = 12.dp
private val PanelInset = 4.dp

@Composable
private fun ResultRow(item: GoToItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                item.icon != null -> Icon(item.icon, null)
                item.connection != null -> Badge(item.connection, size = 16)
            }
        }
        Text(
            item.label,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (item.kind == GoToKind.History) JewelTheme.editorTextStyle else JewelTheme.defaultTextStyle,
        )
        item.detail?.let { Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
        if (item.icon != null && item.connection != null) Badge(item.connection, size = 14)
        item.shortcut?.let { Text(it.label, color = JewelTheme.globalColors.text.info, fontSize = 11.sp) }
    }
}

/**
 * Spots a double tap of Shift with nothing in between, like IntelliJ's Search Everywhere. Feed it every key event the
 * window sees; it returns true on the second release.
 */
class DoubleShiftDetector(private val window: Long = 400, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastRelease = 0L
    private var cleanPress = false

    fun onKey(key: Key, type: KeyEventType): Boolean {
        val isShift = key == Key.ShiftLeft || key == Key.ShiftRight
        if (!isShift) {
            cleanPress = false
            lastRelease = 0
            return false
        }
        when (type) {
            KeyEventType.KeyDown -> cleanPress = true
            KeyEventType.KeyUp -> {
                if (!cleanPress) return false
                cleanPress = false
                val now = clock()
                if (lastRelease != 0L && now - lastRelease <= window) {
                    lastRelease = 0
                    return true
                }
                lastRelease = now
            }
        }
        return false
    }
}

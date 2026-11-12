package dev.tablegrip.app.welcome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionStatus
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.app.ui.AppDialogs
import dev.tablegrip.app.ui.AppIcon
import dev.tablegrip.app.ui.AppIcons
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.app.ui.FollowThemeAppearance
import dev.tablegrip.app.ui.KeyCap
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.TitleBarHitTestEffect
import dev.tablegrip.app.ui.TitleBarRegions
import dev.tablegrip.app.ui.Zoomed
import dev.tablegrip.app.ui.interactiveRegion
import dev.tablegrip.app.ui.rememberCustomTitleBar
import dev.tablegrip.app.update.AppVersion
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.i18n.tr
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Size of the Welcome window: room for the brand panel and about eight connections, without crowding the screen. */
private val WelcomeSize = DpSize(840.dp, 540.dp)

/** Width of the brand panel on the left. */
private val BrandWidth = 300.dp

/** Height of the band at the top the window is dragged by; the traffic lights sit in it. */
private val TitleBandHeight = 44.dp

/** How many recently opened connections are listed apart, above all the others. */
private const val RecentLimit = 6

/**
 * The window TableGrip opens with: the app's mark on the left with the ways to start, and the saved connections on the
 * right, recently opened ones first. Picking one opens it in the main window; "Open TableGrip" goes there without one.
 * Closing it with no other window open quits, as closing the main window does.
 */
@Composable
internal fun WelcomeWindow(app: AppState, onCloseRequest: () -> Unit) {
    val model = remember { WelcomeModel(app) }
    val state = rememberWindowState(size = WelcomeSize, position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        title = tr("Welcome to TableGrip"),
        icon = AppIcon,
        resizable = false,
        onPreviewKeyEvent = { model.onKey(it) },
    ) {
        FollowThemeAppearance(window)
        LaunchedEffect(app.welcomeRaises) {
            if (app.welcomeRaises > 0) {
                if (window.isMinimized) window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        val titleBar = rememberCustomTitleBar(window, TitleBandHeight)
        val regions = remember { TitleBarRegions() }
        if (titleBar != null) TitleBarHitTestEffect(window, titleBar, regions)
        // Without a custom title bar (Linux) the OS draws its own above the content, so no band is kept free for it.
        val band = if (titleBar != null) TitleBandHeight else 12.dp
        Zoomed {
            Row(Modifier.fillMaxSize().background(LocalIslands.current.island)) {
                BrandPanel(app, model, band)
                Box(Modifier.fillMaxHeight().width(1.dp).background(LocalIslands.current.divider))
                ConnectionsPane(app, model, band, Modifier.interactiveRegion(regions))
            }
        }
        // Dialogs started here (a new connection, an import) open over this window while the main one is hidden.
        if (!app.mainWindowVisible) AppDialogs(app)
    }
}

/** What the Welcome window lists and which row is picked; lives as long as the window. */
private class WelcomeModel(private val app: AppState) {
    val query = TextFieldState()
    val search = FocusRequester()
    var selectedId by mutableStateOf<String?>(null)
    /** Ids listed last time, to pick a connection the user has just added. */
    var knownIds: Set<String>? = null

    /** The connections in the order shown: recently opened first, then favorites, then the rest by group and name. */
    fun listing(): Listing {
        val needle = query.text.toString().trim().lowercase()
        val recents = app.recentConnections
        val matching = app.connections.profiles.filter { needle.isEmpty() || it.matches(needle) }
        val recent = matching.filter { it.id in recents }.sortedByDescending { recents[it.id] }.take(RecentLimit)
        val rest = (matching - recent.toSet()).sortedWith(compareBy({ !it.favorite }, { it.group.lowercase() }, { it.name.lowercase() }))
        return Listing(recent, rest, needle)
    }

    private fun ConnectionConfig.matches(needle: String) =
        listOf(name, host, database, user, group, type.displayName).any { it.lowercase().contains(needle) }

    fun open(config: ConnectionConfig? = selected()) = app.openFromWelcome(config)

    fun selected(): ConnectionConfig? = selectedId?.let(app.connections::byId)

    private fun move(by: Int) {
        val all = listing().all
        if (all.isEmpty()) return
        val index = all.indexOfFirst { it.id == selectedId }
        selectedId = all[(index + by).coerceIn(0, all.lastIndex)].id
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // The dialogs opened from here take their own keys.
        if (app.connections.dialog != null || app.connections.importing != null || app.connections.pendingDelete != null) return false
        val command = if (isMac) event.isMetaPressed else event.isCtrlPressed
        return when {
            command && event.key == Key.N -> true.also { app.connections.openNew() }
            command && event.key == Key.O -> true.also { openDataFile() }
            command && event.key == Key.F -> true.also { runCatching { search.requestFocus() } }
            event.key == Key.DirectionDown -> true.also { move(1) }
            event.key == Key.DirectionUp -> true.also { move(-1) }
            event.key == Key.Enter || event.key == Key.NumPadEnter -> selected()?.let { open(it); true } ?: false
            event.key == Key.Escape && query.text.isNotEmpty() -> true.also { query.clearText() }
            else -> false
        }
    }

    fun openDataFile() =
        app.connections.chooseDataFiles { files ->
            if (files.isNotEmpty()) {
                app.openFromWelcome()
                app.connections.openDataFiles(files)
            }
        }
}

private class Listing(val recent: List<ConnectionConfig>, val rest: List<ConnectionConfig>, val needle: String) {
    val all: List<ConnectionConfig> = recent + rest
}

// ---------------------------------------------------------------------------------------------------------------------
// Brand panel

@Composable
private fun BrandPanel(app: AppState, model: WelcomeModel, band: androidx.compose.ui.unit.Dp) {
    val islands = LocalIslands.current
    Box(Modifier.width(BrandWidth).fillMaxHeight().clipToBounds().background(islands.frame)) {
        BrandBackdrop(islands.frame)
        Column(Modifier.fillMaxSize().padding(top = band, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            AppMark()
            Spacer(Modifier.height(14.dp))
            Text("TableGrip", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            Spacer(Modifier.height(4.dp))
            VersionPill()
            Spacer(Modifier.height(12.dp))
            Text(
                tr("Every database, one grip."),
                color = JewelTheme.globalColors.text.info,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val command = if (isMac) "⌘" else "Ctrl+"
                StartAction(AppIcons.AddDataSource, tr("New Connection…"), "${command}N") { app.connections.openNew() }
                StartAction(AllIconsKeys.Actions.MenuOpen, tr("Open Data File…"), "${command}O") { model.openDataFile() }
                StartAction(AllIconsKeys.ToolbarDecorator.Import, tr("Import Connections…"), null) { app.connections.openImport() }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(app.settings.showWelcome, onCheckedChange = { shown -> app.updateSettings { it.copy(showWelcome = shown) } })
                Text(tr("Show this window on start"), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
            }
        }
    }
}

/** A faint gray table grid behind the mark, as on the website, fading out towards the panel's edges. */
@Composable
private fun BrandBackdrop(frame: Color) {
    val dark = JewelTheme.isDark
    val rule = JewelTheme.globalColors.text.normal
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * 0.36f)
        val step = 24.dp.toPx()
        val line = rule.copy(alpha = if (dark) 0.08f else 0.07f)
        val stroke = 1.dp.toPx()
        var x = (size.width / 2) % step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
            x += step
        }
        var y = center.y % step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
            y += step
        }
        // Fades the grid out around the mark, so it reads as a backdrop rather than graph paper.
        drawRect(Brush.radialGradient(listOf(Color.Transparent, frame.copy(alpha = 0.85f), frame), center, size.height * 0.62f))
    }
}

/** The mark flat, without the drop shadow baked into the macOS icon, as the website shows it. */
@Composable
private fun AppMark() {
    val image = remember { loadAppMark() }
    if (image != null) Image(image, contentDescription = "TableGrip", Modifier.size(104.dp))
}

private fun loadAppMark(): ImageBitmap? =
    WelcomeModel::class.java.getResourceAsStream("/icon/TableGrip-flat.png")?.use { stream ->
        org.jetbrains.skia.Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap()
    }

@Composable
private fun VersionPill() {
    val islands = LocalIslands.current
    val version = AppVersion.current
    Box(
        Modifier.clip(CircleShape).background(islands.accent.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Text(tr("Version {0}", version.version), color = islands.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StartAction(icon: IconKey, label: String, keys: String?, onClick: () -> Unit) {
    val islands = LocalIslands.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) islands.chipHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(islands.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp))
        }
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (keys != null) KeyCap(keys)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Connections

@Composable
private fun ConnectionsPane(app: AppState, model: WelcomeModel, band: androidx.compose.ui.unit.Dp, interactive: Modifier) {
    val listing = model.listing()
    val profiles = app.connections.profiles
    // A connection added from here (a new one, an import) is picked, so Enter opens it.
    LaunchedEffect(profiles.map { it.id }) {
        val ids = profiles.map { it.id }.toSet()
        val known = model.knownIds
        val added = if (known == null) emptySet() else ids - known
        model.knownIds = ids
        if (added.isNotEmpty()) model.selectedId = profiles.last { it.id in added }.id
    }
    // Keeps a row picked whenever there is one to pick.
    LaunchedEffect(listing.all.map { it.id }) {
        if (listing.all.none { it.id == model.selectedId }) model.selectedId = listing.all.firstOrNull()?.id
    }
    Column(Modifier.fillMaxSize()) {
        // The header shares the title band, so the window's top edge stays draggable around the search field.
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = if (band > 20.dp) 10.dp else 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Connections"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (profiles.isNotEmpty()) {
                    Text(
                        if (profiles.size == 1) tr("1 saved") else tr("{0} saved", profiles.size),
                        color = JewelTheme.globalColors.text.info,
                        fontSize = 11.sp,
                    )
                }
            }
            if (profiles.isNotEmpty()) {
                TextField(
                    model.query,
                    interactive.width(230.dp).focusRequester(model.search),
                    placeholder = { Text(tr("Search connections")) },
                    leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
                )
                LaunchedEffect(Unit) { runCatching { model.search.requestFocus() } }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                profiles.isEmpty() -> EmptyConnections(app, model)
                listing.all.isEmpty() ->
                    Text(
                        tr("No connections match “{0}”", model.query.text.toString().trim()),
                        Modifier.align(Alignment.Center),
                        color = JewelTheme.globalColors.text.info,
                    )
                else -> ConnectionList(app, model, listing)
            }
        }
        if (profiles.isNotEmpty()) Footer(app, model)
    }
}

@Composable
private fun ConnectionList(app: AppState, model: WelcomeModel, listing: Listing) {
    val listState = rememberLazyListState()
    val sections = listing.needle.isEmpty() && listing.recent.isNotEmpty() && listing.rest.isNotEmpty()
    // Scrolls the picked row into view as the arrow keys move it.
    LaunchedEffect(model.selectedId) {
        val index = listing.all.indexOfFirst { it.id == model.selectedId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val item = index + if (sections) (if (index >= listing.recent.size) 2 else 1) else 0
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == item && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
            listState.animateScrollToItem((item - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = listState) {
        if (sections) item("recent-header") { SectionHeader(tr("Recent")) }
        items(listing.recent, key = { "r-" + it.id }) { ConnectionRow(app, model, it, recent = true) }
        if (sections) item("all-header") { SectionHeader(tr("All Connections")) }
        items(listing.rest, key = { "a-" + it.id }) { ConnectionRow(app, model, it, recent = false) }
        item("end") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
        color = JewelTheme.globalColors.text.info,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

@Composable
private fun ConnectionRow(app: AppState, model: WelcomeModel, config: ConnectionConfig, recent: Boolean) {
    val islands = LocalIslands.current
    val selected = model.selectedId == config.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(9.dp)
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(tr("Connect")) { model.open(config) },
                ContextMenuItem(tr("Edit…")) { app.connections.openEdit(config) },
                ContextMenuItem(if (config.favorite) tr("Remove from Favorites") else tr("Add to Favorites")) { app.connections.toggleFavorite(config) },
                ContextMenuItem(tr("Delete…")) { app.connections.pendingDelete = config },
            )
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(shape)
                .background(
                    when {
                        selected -> islands.accent.copy(alpha = 0.16f)
                        hovered -> islands.chipHover
                        else -> Color.Transparent
                    }
                )
                .then(if (selected) Modifier.border(1.dp, islands.accent.copy(alpha = 0.35f), shape) else Modifier)
                .hoverable(interaction)
                .pointerInput(config.id) {
                    detectTapGestures(onTap = { model.selectedId = config.id }, onDoubleTap = { model.open(config) })
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EngineTile(app, config)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(config.name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (config.favorite) Icon(AllIconsKeys.Nodes.Favorite, tr("Favorite"), Modifier.size(12.dp))
                    EnvironmentChip(config)
                    if (config.readOnly) Text(tr("read-only"), color = JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 1)
                }
                Text(summary(config), color = JewelTheme.globalColors.text.info, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hovered || selected) {
                Tooltip({ Text(tr("Edit Connection…")) }) {
                    IconButton(onClick = { app.connections.openEdit(config) }, Modifier.size(26.dp)) {
                        Icon(AllIconsKeys.Actions.Edit, tr("Edit Connection…"), Modifier.size(16.dp))
                    }
                }
            }
            if (recent) {
                app.recentConnections[config.id]?.let {
                    Text(ago(it), color = JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            } else if (config.group.isNotBlank() && !(hovered || selected)) {
                Text(config.group, color = JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The engine's logo on a soft tile, with a dot when the connection is already open or failed. */
@Composable
private fun EngineTile(app: AppState, config: ConnectionConfig) {
    val islands = LocalIslands.current
    val dark = JewelTheme.isDark
    val dot =
        when (app.databases.status(config.id)) {
            is ConnectionStatus.Connected -> if (dark) Color(0xFF5FB865) else Color(0xFF369650)
            is ConnectionStatus.Failed -> if (dark) Color(0xFFDB5C5C) else Color(0xFFE55765)
            else -> null
        }
    Box {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(islands.frame).border(1.dp, islands.islandBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Badge(config, size = 22)
        }
        if (dot != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(11.dp).clip(CircleShape).background(islands.island).padding(2.dp).clip(CircleShape).background(dot)
            )
        }
    }
}

@Composable
private fun EmptyConnections(app: AppState, model: WelcomeModel) {
    LaunchedEffect(Unit) { app.connections.lookForImports() }
    val islands = LocalIslands.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(islands.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(AppIcons.AddDataSource, null, Modifier.size(30.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(tr("No connections yet"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val hint = app.connections.importHint
        Text(
            if (hint != null) {
                if (hint.count == 1) tr("1 connection found in {0}", hint.tools.joinToString(", ")) else tr("{0} connections found in {1}", hint.count, hint.tools.joinToString(", "))
            } else {
                tr("Connect to a database server, or open a SQLite, CSV or Excel file to look around.")
            },
            color = JewelTheme.globalColors.text.info,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hint != null) {
                DefaultButton(onClick = app.connections::openImport) { Text(if (hint.count == 1) tr("Import It…") else tr("Import Them…")) }
                OutlinedButton(onClick = { app.connections.openNew() }) { Text(tr("New Connection…")) }
            } else {
                DefaultButton(onClick = { app.connections.openNew() }) { Text(tr("New Connection…")) }
                OutlinedButton(onClick = model::openDataFile) { Text(tr("Open Data File…")) }
            }
        }
        Link(tr("Continue without a connection"), onClick = { model.open(null) })
    }
}

@Composable
private fun Footer(app: AppState, model: WelcomeModel) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalIslands.current.divider))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("↑↓")
            Text(tr("to pick"), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            KeyCap("↩")
            Text(tr("to connect"), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
        }
        OutlinedButton(onClick = { model.open(null) }) { Text(tr("Open TableGrip")) }
        val selected = model.selected()
        DefaultButton(onClick = { model.open(selected) }, enabled = selected != null) { Text(tr("Connect")) }
    }
}

/** Where the connection points, in a line: the engine, then the file, URL or user@host:port/database. */
private fun summary(config: ConnectionConfig): String {
    val target =
        when {
            config.type.isFile -> config.database.takeIf { it.isNotBlank() }?.let { path -> File(path).name.ifBlank { path } }.orEmpty()
            !config.url.isNullOrBlank() -> config.url.orEmpty()
            else ->
                buildString {
                    if (config.user.isNotBlank()) append(config.user).append('@')
                    append(config.host)
                    config.port?.let { append(':').append(it) }
                    if (config.database.isNotBlank()) append('/').append(config.database)
                }
        }
    return listOf(config.type.displayName, target).filter { it.isNotBlank() }.joinToString("  ·  ")
}

private val DateOnly = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** When a connection was last opened, the way people say it: "just now", "5 min ago", "yesterday", a date. */
private fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> tr("just now")
        minutes < 60 -> tr("{0} min ago", minutes)
        minutes < 24 * 60 -> tr("{0} h ago", minutes / 60)
        minutes < 48 * 60 -> tr("yesterday")
        minutes < 7 * 24 * 60 -> tr("{0} days ago", minutes / (24 * 60))
        else -> DateOnly.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

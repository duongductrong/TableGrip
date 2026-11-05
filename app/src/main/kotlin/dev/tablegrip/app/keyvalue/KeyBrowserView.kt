package dev.tablegrip.app.keyvalue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.grid.JsonTree
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.driver.KeyEdit
import dev.tablegrip.core.driver.KeyType
import dev.tablegrip.core.driver.KeyValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineSuccessBanner
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.component.styling.TrackClickBehavior
import org.jetbrains.jewel.intui.standalone.styling.windowsAndLinux
import androidx.compose.foundation.layout.PaddingValues
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun KeyBrowserView(tab: KeyBrowserTab) {
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        HorizontalSplitLayout(
            first = { KeyListPane(tab, onSelect = { creating = false }, onNew = { creating = true }) },
            second = { DetailPane(tab, creating, onDoneCreating = { creating = false }, onNew = { creating = true }) },
            modifier = Modifier.fillMaxSize(),
            firstPaneMinWidth = 240.dp,
            secondPaneMinWidth = 380.dp,
            state = rememberSplitLayoutState(0.34f),
        )
    }
}

// ---------------------------------------------------------------- key list

private val EditableTypes = listOf(KeyType.Text, KeyType.Hash, KeyType.List, KeyType.Set, KeyType.SortedSet, KeyType.Stream)

@Composable
private fun KeyListPane(tab: KeyBrowserTab, onSelect: () -> Unit, onNew: () -> Unit) {
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxSize()) {
        // Where we are and what we can do.
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (tab.plainValues) "Keys" else "Database ${tab.keyspace}", fontWeight = FontWeight.SemiBold)
            if (tab.readOnly) Pill("read-only", info)
            Spacer(Modifier.weight(1f))
            Tooltip({ Text("Scan again") }) { IconButton(onClick = tab::reload, enabled = !tab.scanning) { Icon(AllIconsKeys.Actions.Refresh, "Scan again") } }
            if (!tab.readOnly) {
                DefaultButton(onClick = onNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AllIconsKeys.General.Add, null, Modifier.size(14.dp))
                        Text("New key")
                    }
                }
            }
        }
        // Search and filters.
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(
                tab.pattern,
                Modifier.fillMaxWidth(),
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text(if (tab.plainValues) "Search keys, e.g. /services/*" else "Search keys, e.g. user:*") },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
                trailingIcon =
                    if (tab.pattern.text.isNotEmpty() && tab.pattern.text.toString() != "*") {
                        {
                            Icon(
                                AllIconsKeys.Actions.Close,
                                "Clear search",
                                Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand).clickable {
                                    tab.pattern.setTextAndPlaceCursorAtEnd("*")
                                    tab.reload()
                                },
                            )
                        }
                    } else null,
                onKeyboardAction = KeyboardActionHandler { tab.reload() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!tab.plainValues) {
                    val filters = listOf<KeyType?>(null) + EditableTypes
                    ListComboBox(
                        items = filters.map { it?.label?.let { label -> "Type: $label" } ?: "All types" },
                        selectedIndex = filters.indexOf(tab.typeFilter).coerceAtLeast(0),
                        onSelectedItemChange = { tab.typeFilter = filters[it] },
                        modifier = Modifier.width(130.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Tooltip({ Text(if (tab.treeView) "Show as a flat list" else "Group by \"${tab.delimiter}\" into folders") }) {
                    IconButton(onClick = { tab.treeView = !tab.treeView }) {
                        Icon(if (tab.treeView) AllIconsKeys.Actions.ListFiles else AllIconsKeys.Actions.ShowAsTree, "Toggle tree")
                    }
                }
                if (tab.treeView) {
                    Tooltip({ Text("Expand all") }) { IconButton(onClick = { tab.collapsed = emptySet() }) { Icon(AllIconsKeys.Actions.Expandall, "Expand all") } }
                    Tooltip({ Text("Collapse all") }) {
                        IconButton(onClick = { tab.collapsed = keyGroups(tab.visibleKeys, tab.delimiter) }) { Icon(AllIconsKeys.Actions.Collapseall, "Collapse all") }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            KeyList(tab, onSelect)
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.scanning) CircularProgressIndicator(Modifier.size(12.dp))
            val shown = tab.visibleKeys.size
            val count =
                when {
                    tab.scanning -> "Scanning…"
                    tab.typeFilter != null -> "$shown of ${tab.keys.size} keys"
                    else -> "%,d %s".format(shown, if (shown == 1) "key" else "keys") + if (tab.cursor != null) " loaded" else ""
                }
            Text(count, color = info, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (tab.cursor != null) Link("Load more", onClick = tab::loadMore, enabled = !tab.scanning)
        }
    }
}

@Composable
private fun KeyList(tab: KeyBrowserTab, onSelect: () -> Unit) {
    val rows =
        remember(tab.visibleKeys, tab.treeView, tab.collapsed, tab.delimiter) {
            if (tab.treeView) keyTree(tab.visibleKeys, tab.delimiter, tab.collapsed)
            else tab.visibleKeys.sortedBy { it.name }.map { KeyRow.Key(it, it.name, 0) }
        }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    if (rows.isEmpty() && !tab.scanning) {
        EmptyKeys(tab)
        return
    }

    fun move(step: Int) {
        val keyRows = rows.withIndex().filter { it.value is KeyRow.Key }
        if (keyRows.isEmpty()) return
        val current = keyRows.indexOfFirst { (it.value as KeyRow.Key).info.name == tab.selectedKey }
        val next = keyRows[(if (current < 0) 0 else current + step).coerceIn(0, keyRows.lastIndex)]
        tab.select((next.value as KeyRow.Key).info.name)
        onSelect()
        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == next.index } || visible.first().index == next.index || visible.last().index == next.index) listState.animateScrollToItem((next.index - 3).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> true.also { move(1) }
                Key.DirectionUp -> true.also { move(-1) }
                else -> false
            }
        },
        state = listState,
        // Room on the right for the scrollbar, so it never covers a TTL.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 4.dp, end = ScrollbarGutter),
    ) {
        items(rows, key = { row -> if (row is KeyRow.Group) "g:${row.path}" else "k:${(row as KeyRow.Key).info.name}" }) { row ->
            when (row) {
                is KeyRow.Group -> GroupRow(row) { tab.toggleGroup(row.path) }
                is KeyRow.Key ->
                    KeyRowView(row, selected = row.info.name == tab.selectedKey, showBadge = !tab.plainValues) {
                        focus.requestFocus()
                        tab.select(row.info.name)
                        onSelect()
                    }
            }
        }
    }
    VerticalScrollbar(listState, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp), style = visibleScrollbarStyle())
    }
}

/** Space kept clear beside a list for its scrollbar. */
private val ScrollbarGutter = 10.dp

/**
 * The theme's scrollbar, but always shown: macOS hides scrollbars until you scroll, which leaves no visible handle on a
 * long key list or table. Thin, without a track, so it stays quiet.
 */
@Composable
private fun visibleScrollbarStyle(): ScrollbarStyle {
    val base = JewelTheme.scrollbarStyle
    return remember(base) {
        ScrollbarStyle(
            colors = base.colors,
            metrics = base.metrics,
            trackClickBehavior = TrackClickBehavior.JumpToSpot,
            scrollbarVisibility = ScrollbarVisibility.AlwaysVisible.windowsAndLinux(trackThickness = 8.dp, trackPadding = PaddingValues(horizontal = 1.dp)),
        )
    }
}

@Composable
private fun EmptyKeys(tab: KeyBrowserTab) {
    val pattern = tab.pattern.text.toString().trim()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AllIconsKeys.Actions.Search, null, Modifier.size(24.dp))
        val filtered = pattern.isNotEmpty() && pattern != "*" || tab.typeFilter != null
        Text(if (filtered) "No keys match" else "This database is empty", fontWeight = FontWeight.SemiBold)
        Text(
            if (filtered) "Try a wider pattern: * matches anything, ? one character." else "Create a key to get started.",
            color = JewelTheme.globalColors.text.info,
            textAlign = TextAlign.Center,
        )
        if (filtered) {
            Link("Clear filters", onClick = {
                tab.typeFilter = null
                tab.pattern.setTextAndPlaceCursorAtEnd("*")
                tab.reload()
            })
        }
    }
}

@Composable
private fun rowBackground(selected: Boolean, hovered: Boolean): Color =
    when {
        selected -> LocalIslands.current.accent.copy(alpha = 0.28f)
        hovered -> JewelTheme.globalColors.text.normal.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

@Composable
private fun GroupRow(row: KeyRow.Group, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(4.dp))
            .background(rowBackground(false, hovered)).hoverable(interaction).clickable(onClick = onToggle)
            .padding(start = (6 + row.depth * 16).dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (row.expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, null, Modifier.size(12.dp))
        Icon(AllIconsKeys.Nodes.Folder, null, Modifier.size(16.dp))
        Text(row.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%,d".format(row.keyCount), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
    }
}

@Composable
private fun KeyRowView(row: KeyRow.Key, selected: Boolean, showBadge: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(4.dp))
            .background(rowBackground(selected, hovered)).hoverable(interaction).clickable(onClick = onClick)
            // Keys line up with folder labels: past the chevron at their depth.
            .padding(start = (6 + row.depth * 16 + if (row.depth > 0) 16 else 0).dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBadge) TypeBadge(row.info.type)
        // The label takes whatever is left and ellipsizes; the TTL keeps its natural width.
        Box(Modifier.weight(1f)) {
            Tooltip({ Text(row.info.name) }) { Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        row.info.ttlMillis?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.Vcs.History, "Expires", Modifier.size(11.dp))
                Text(formatTtl(it), color = JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

private val TypeColors =
    mapOf(
        KeyType.Text to Color(0xFF4C8DF6),
        KeyType.Hash to Color(0xFF9B6BDF),
        KeyType.List to Color(0xFF3FA66B),
        KeyType.Set to Color(0xFFD9822B),
        KeyType.SortedSet to Color(0xFFD5534B),
        KeyType.Stream to Color(0xFF2FA4B5),
        KeyType.Json to Color(0xFFB59A2F),
        KeyType.Other to Color(0xFF8A8A8A),
    )

@Composable
private fun TypeBadge(type: KeyType, large: Boolean = false) {
    val color = TypeColors.getValue(type)
    Text(
        type.label.uppercase(),
        Modifier.width(if (large) 60.dp else 48.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp)).padding(vertical = if (large) 2.dp else 1.dp),
        color = color,
        fontSize = if (large) 11.sp else 9.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp),
        color = color,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------- detail pane

@Composable
private fun DetailPane(tab: KeyBrowserTab, creating: Boolean, onDoneCreating: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        tab.message?.let { MessageBanner(tab, it) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
            val value = tab.value
            when {
                creating -> NewKeyForm(tab, onDone = onDoneCreating)
                value != null -> ValuePane(tab, value)
                tab.loadingValue -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> NoSelection(tab, onNew)
            }
        }
    }
}

@Composable
private fun MessageBanner(tab: KeyBrowserTab, message: KeyBrowserTab.Message) {
    // Confirmations fade on their own; errors stay until dismissed or replaced.
    LaunchedEffect(message) {
        if (!message.isError) {
            delay(4_000)
            if (tab.message == message) tab.message = null
        }
    }
    val modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp)
    val dismiss: org.jetbrains.jewel.ui.component.banner.BannerIconActionScope.() -> Unit = {
        iconAction(AllIconsKeys.Actions.Close, "Dismiss", "Dismiss") { tab.message = null }
    }
    if (message.isError) InlineErrorBanner(message.text, modifier, iconActions = dismiss)
    else InlineSuccessBanner(message.text, modifier, iconActions = dismiss)
}

@Composable
private fun NoSelection(tab: KeyBrowserTab, onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No key selected", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            "Pick a key on the left to see and edit its value.\nUse ↑ and ↓ to step through the list.",
            color = JewelTheme.globalColors.text.info,
            textAlign = TextAlign.Center,
        )
        if (!tab.readOnly) Link("Create a new key", onClick = onNew)
    }
}

/** A subtly raised panel inside the detail pane. */
@Composable
private fun Modifier.card(): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return clip(shape).background(JewelTheme.globalColors.text.normal.copy(alpha = 0.035f)).border(1.dp, LocalIslands.current.divider, shape)
}

@Composable
private fun ValuePane(tab: KeyBrowserTab, value: KeyValue) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KeyHeader(tab, value)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (value) {
                is KeyValue.Text -> TextValue(tab, value)
                is KeyValue.Hash ->
                    Elements(
                        tab,
                        ElementSpec(
                            type = KeyType.Hash,
                            headers = listOf("Field", "Value"),
                            weights = listOf(0.35f, 0.65f),
                            rows = value.fields.map { listOf(it.first, it.second) },
                            total = value.total,
                            remove = { KeyEdit.DeleteField(it[0]) },
                            editColumn = 1,
                            update = { row, new -> listOf(KeyEdit.SetField(row[0], new)) },
                            addLabels = listOf("Field", "Value"),
                            add = { if (it[0].isBlank()) null else KeyEdit.SetField(it[0], it[1]) },
                        ),
                    )
                is KeyValue.ListValue ->
                    Elements(
                        tab,
                        ElementSpec(
                            type = KeyType.List,
                            headers = listOf("Index", "Value"),
                            weights = listOf(0.12f, 0.88f),
                            rows = value.items.mapIndexed { i, item -> listOf(i.toString(), item) },
                            total = value.total,
                            remove = { KeyEdit.RemoveItem(it[1]) },
                            editColumn = 1,
                            update = { row, new -> listOf(KeyEdit.SetItem(row[0].toLong(), new)) },
                            addLabels = listOf("Value to append"),
                            add = { if (it[0].isEmpty()) null else KeyEdit.AppendItem(it[0]) },
                        ),
                    )
                is KeyValue.SetValue ->
                    Elements(
                        tab,
                        ElementSpec(
                            type = KeyType.Set,
                            headers = listOf("Member"),
                            weights = listOf(1f),
                            rows = value.members.map { listOf(it) },
                            total = value.total,
                            remove = { KeyEdit.RemoveMember(it[0]) },
                            editColumn = 0,
                            // Members can't change in place: swap the old one for the new.
                            update = { row, new -> if (new == row[0]) emptyList() else listOf(KeyEdit.AddMember(new), KeyEdit.RemoveMember(row[0])) },
                            addLabels = listOf("Member"),
                            add = { if (it[0].isEmpty()) null else KeyEdit.AddMember(it[0]) },
                        ),
                    )
                is KeyValue.SortedSet ->
                    Elements(
                        tab,
                        ElementSpec(
                            type = KeyType.SortedSet,
                            headers = listOf("Member", "Score"),
                            weights = listOf(0.7f, 0.3f),
                            rows = value.members.map { listOf(it.first, formatScore(it.second)) },
                            total = value.total,
                            remove = { KeyEdit.RemoveMember(it[0]) },
                            editColumn = 1,
                            update = { row, new -> new.trim().toDoubleOrNull()?.let { listOf(KeyEdit.SetScore(row[0], it)) } },
                            addLabels = listOf("Member", "Score"),
                            add = { fields -> fields[1].trim().toDoubleOrNull()?.takeIf { fields[0].isNotEmpty() }?.let { KeyEdit.SetScore(fields[0], it) } },
                            invalidHint = "Score must be a number.",
                        ),
                    )
                is KeyValue.Stream ->
                    Elements(
                        tab,
                        ElementSpec(
                            type = KeyType.Stream,
                            headers = listOf("ID", "Fields"),
                            weights = listOf(0.3f, 0.7f),
                            rows = value.entries.map { entry -> listOf(entry.id, entry.fields.joinToString("  ") { "${it.first}=${it.second}" }) },
                            total = value.total,
                            remove = { KeyEdit.DeleteEntry(it[0]) },
                            editColumn = null,
                            update = null,
                            addLabels = listOf("field=value, field=value"),
                            add = { fields -> parseFields(fields[0])?.let { KeyEdit.AddEntry(it) } },
                            invalidHint = "Write fields as name=value, separated by commas.",
                        ),
                    )
                is KeyValue.Other ->
                    Column(Modifier.fillMaxWidth().card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("A ${value.type} value", fontWeight = FontWeight.SemiBold)
                        Text("The browser can't edit this type yet; use the console to work with it.", color = JewelTheme.globalColors.text.info)
                        if (value.description.isNotEmpty()) SelectionContainer { Text(value.description, style = JewelTheme.editorTextStyle) }
                    }
            }
        }
    }
}

private enum class HeaderMode { View, Rename, Ttl, ConfirmDelete }

@Composable
private fun KeyHeader(tab: KeyBrowserTab, value: KeyValue) {
    var mode by remember(value.key) { mutableStateOf(HeaderMode.View) }
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!tab.plainValues) TypeBadge(value.type, large = true)
            if (mode == HeaderMode.Rename) {
                RenameField(tab, value.key, onClose = { mode = HeaderMode.View }, Modifier.weight(1f))
            } else {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(value.key, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Tooltip({ Text("Copy key name") }) { IconButton(onClick = { copyToClipboard(value.key); tab.message = KeyBrowserTab.Message("Copied the key name.", isError = false) }) { Icon(AllIconsKeys.Actions.Copy, "Copy key name") } }
                Tooltip({ Text("Reload value") }) { IconButton(onClick = tab::refreshValue) { Icon(AllIconsKeys.Actions.Refresh, "Reload value") } }
                if (!tab.readOnly) {
                    Tooltip({ Text("Rename key") }) { IconButton(onClick = { mode = HeaderMode.Rename }) { Icon(AllIconsKeys.Actions.Edit, "Rename key") } }
                    Tooltip({ Text("Delete key") }) { IconButton(onClick = { mode = HeaderMode.ConfirmDelete }) { Icon(AllIconsKeys.General.Delete, "Delete key") } }
                }
            }
        }
        // What the key is at a glance: expiry, size, where it lives.
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TtlChip(tab, value, onClick = if (tab.readOnly) null else ({ mode = if (mode == HeaderMode.Ttl) HeaderMode.View else HeaderMode.Ttl }))
            value.size?.let { Text(elementNoun(value.type, it), color = info) }
            (value as? KeyValue.Text)?.let { Text(formatBytes(it.value.toByteArray().size.toLong()) + if (it.truncated) "+" else "", color = info) }
            if (!tab.plainValues) Text("db${tab.keyspace}", color = info)
        }
        when (mode) {
            HeaderMode.Ttl -> TtlEditor(tab, value, onClose = { mode = HeaderMode.View })
            HeaderMode.ConfirmDelete -> DeleteConfirmation(tab, value, onClose = { mode = HeaderMode.View })
            else -> {}
        }
    }
}

@Composable
private fun RenameField(tab: KeyBrowserTab, key: String, onClose: () -> Unit, modifier: Modifier) {
    val name = remember(key) { TextFieldState(key) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val target = name.text.toString().trim()
    val exists = target != key && tab.keys.any { it.name == target }
    fun submit() {
        if (target.isEmpty() || exists) return
        tab.rename(target)
        onClose()
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TextField(
                name,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event -> isEscape(event).also { if (it) onClose() } },
                textStyle = JewelTheme.editorTextStyle,
                onKeyboardAction = KeyboardActionHandler { submit() },
            )
            if (exists) Text("A key named $target already exists.", color = JewelTheme.globalColors.text.error, fontSize = 11.sp)
        }
        DefaultButton(onClick = ::submit, enabled = target.isNotEmpty() && target != key && !exists) { Text("Rename") }
        OutlinedButton(onClick = onClose) { Text("Cancel") }
    }
}

private fun isEscape(event: KeyEvent) = event.type == KeyEventType.KeyDown && event.key == Key.Escape

/** The key's expiry, counting down live from when the value was read. */
@Composable
private fun TtlChip(tab: KeyBrowserTab, value: KeyValue, onClick: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ttl = value.ttlMillis
    if (ttl != null) {
        LaunchedEffect(value, tab.valueReadAt) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val remaining = ttl?.let { it - (now - tab.valueReadAt) }
    val text =
        when {
            remaining == null -> "No expiry"
            remaining <= 0 -> "Expired"
            else -> "Expires in ${formatTtl(remaining)}"
        }
    val color = if (remaining != null && remaining < 60_000) Color(0xFFE5A23C) else JewelTheme.globalColors.text.info
    val content =
        @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AllIconsKeys.Vcs.History, null, Modifier.size(13.dp))
                Text(text, color = color)
                if (onClick != null) Icon(AllIconsKeys.Actions.Edit, null, Modifier.size(12.dp))
            }
        }
    if (onClick == null) content()
    else {
        Tooltip({ Text("Change expiry") }) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun TtlEditor(tab: KeyBrowserTab, value: KeyValue, onClose: () -> Unit) {
    val (initialAmount, initialUnit) = value.ttlMillis?.div(1_000)?.takeIf { it > 0 }?.let(::ttlForEditing) ?: (1L to TtlUnit.Hours)
    var unit by remember(value.key) { mutableStateOf(initialUnit) }
    val amount = remember(value.key) { TextFieldState(initialAmount.toString()) }
    val seconds = ttlSeconds(amount.text.toString(), unit)
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Expire after")
            TextField(
                amount,
                Modifier.width(80.dp).onPreviewKeyEvent { event -> isEscape(event).also { if (it) onClose() } },
                onKeyboardAction = KeyboardActionHandler { seconds?.let { tab.expire(it); onClose() } },
            )
            ListComboBox(items = TtlUnit.entries.map { it.label }, selectedIndex = unit.ordinal, onSelectedItemChange = { unit = TtlUnit.entries[it] }, modifier = Modifier.width(110.dp))
            DefaultButton(onClick = { seconds?.let { tab.expire(it); onClose() } }, enabled = seconds != null) { Text("Set expiry") }
            OutlinedButton(onClick = onClose) { Text("Cancel") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick:", color = info)
            TtlPresets.forEach { (label, presetSeconds) ->
                Chip(label) {
                    tab.expire(presetSeconds)
                    onClose()
                }
            }
            Spacer(Modifier.weight(1f))
            if (value.ttlMillis != null) Link("Remove expiry", onClick = { tab.expire(null); onClose() })
        }
        if (amount.text.isNotBlank() && seconds == null) Text("Enter a whole number greater than 0.", color = JewelTheme.globalColors.text.error, fontSize = 11.sp)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = LocalIslands.current.accent
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape)
            .background(if (selected) accent.copy(alpha = 0.25f) else if (hovered) JewelTheme.globalColors.text.normal.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (selected) accent else LocalIslands.current.divider, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = 12.sp,
    )
}

@Composable
private fun DeleteConfirmation(tab: KeyBrowserTab, value: KeyValue, onClose: () -> Unit) {
    val error = JewelTheme.globalColors.text.error
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(error.copy(alpha = 0.10f)).border(1.dp, error.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Warning, null)
        Column(Modifier.weight(1f)) {
            Text("Delete ${value.key}?", fontWeight = FontWeight.SemiBold)
            Text(
                (value.size?.let { "Its ${elementNoun(value.type, it)} will be removed. " } ?: "") + "This can't be undone.",
                color = JewelTheme.globalColors.text.info,
                fontSize = 12.sp,
            )
        }
        OutlinedButton(onClick = onClose) { Text("Cancel") }
        DefaultButton(onClick = { tab.delete(); onClose() }) { Text("Delete") }
    }
}

private fun isSave(event: KeyEvent) = event.type == KeyEventType.KeyDown && event.key == Key.S && (event.isMetaPressed || event.isCtrlPressed)

@Composable
private fun TextValue(tab: KeyBrowserTab, value: KeyValue.Text) {
    val text = remember(value) { TextFieldState(value.value) }
    val current = text.text.toString()
    val json = remember(current) { parseJsonDocument(current) }
    var showJson by remember(value.key) { mutableStateOf(parseJsonDocument(value.value) != null) }
    val editable = !tab.readOnly && !value.truncated
    val dirty = current != value.value
    val info = JewelTheme.globalColors.text.info
    fun save() {
        if (editable && dirty) tab.edit(KeyEdit.SetText(current), confirmationFor = "Saved ${value.key}.")
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Text", "JSON"), selected = if (showJson && json != null) 1 else 0, enabled = listOf(true, json != null)) { showJson = it == 1 }
            if (json == null && current.isNotBlank()) Text("Not JSON", color = info, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (editable && json != null) {
                Tooltip({ Text("Pretty-print the JSON") }) {
                    Link("Format JSON", onClick = { text.setTextAndPlaceCursorAtEnd(prettyJson(json)); showJson = false })
                }
            }
            Tooltip({ Text("Copy value") }) { IconButton(onClick = { copyToClipboard(current); tab.message = KeyBrowserTab.Message("Copied the value.", isError = false) }) { Icon(AllIconsKeys.Actions.Copy, "Copy value") } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showJson && json != null) {
                Box(Modifier.fillMaxSize().card().padding(8.dp)) { JsonTree(json) }
            } else {
                TextArea(
                    text,
                    Modifier.fillMaxSize().onPreviewKeyEvent { event -> isSave(event).also { if (it) save() } },
                    textStyle = JewelTheme.editorTextStyle,
                    readOnly = !editable,
                    placeholder = { Text("Empty string") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    value.truncated -> "Showing the first %,d characters; edit it from the console.".format(value.value.length)
                    else -> "%,d characters".format(current.length) + if (json != null) " · valid JSON" else ""
                },
                color = info,
                fontSize = 12.sp,
            )
            if (dirty) Pill("Unsaved changes", Color(0xFFE5A23C))
            Spacer(Modifier.weight(1f))
            if (editable) {
                OutlinedButton(onClick = { text.setTextAndPlaceCursorAtEnd(value.value) }, enabled = dirty) { Text("Revert") }
                Tooltip({ Text("Save (⌘S)") }) { DefaultButton(onClick = ::save, enabled = dirty) { Text("Save") } }
            }
        }
    }
}

/** Applies [edit] with a confirmation once it's done. */
private fun KeyBrowserTab.edit(edit: KeyEdit, confirmationFor: String) = editAll(listOf(edit), confirmation = confirmationFor)

@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: List<Boolean> = options.map { true }, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    val accent = LocalIslands.current.accent
    Row(Modifier.clip(shape).border(1.dp, LocalIslands.current.divider, shape).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Text(
                option,
                Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) accent.copy(alpha = 0.3f) else Color.Transparent)
                    .then(if (enabled[i]) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onSelect(i) } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                color = if (enabled[i]) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.disabled,
                fontSize = 12.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- collections

/**
 * How a collection type shows as a table. [remove] maps a row to its edit; [update] maps a row and the new text of
 * [editColumn] to the edits that apply it (null when the text isn't valid); [add] maps the typed fields to an edit,
 * null when they don't make a valid element.
 */
private class ElementSpec(
    val type: KeyType,
    val headers: List<String>,
    val weights: List<Float>,
    val rows: List<List<String>>,
    val total: Long,
    val remove: (List<String>) -> KeyEdit,
    val editColumn: Int?,
    val update: ((List<String>, String) -> List<KeyEdit>?)?,
    val addLabels: List<String>,
    val add: (List<String>) -> KeyEdit?,
    val invalidHint: String? = null,
)

@Composable
private fun Elements(tab: KeyBrowserTab, spec: ElementSpec) {
    val filter = remember(tab.selectedKey) { TextFieldState() }
    var editing by remember(tab.selectedKey) { mutableStateOf<List<String>?>(null) }
    val query = filter.text.toString().trim()
    val rows = if (query.isEmpty()) spec.rows else spec.rows.filter { row -> row.any { it.contains(query, ignoreCase = true) } }
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                filter,
                Modifier.width(240.dp),
                placeholder = { Text("Filter ${elementNoun(spec.type, 2).substringAfter(' ')}") },
                leadingIcon = { Icon(AllIconsKeys.General.Filter, null, Modifier.padding(end = 6.dp)) },
            )
            Spacer(Modifier.weight(1f))
            // The header already gives the full count; say more only when the table shows part of it.
            when {
                query.isNotEmpty() -> "${rows.size} of ${elementNoun(spec.type, spec.rows.size.toLong())} match"
                spec.rows.size.toLong() != spec.total -> "Showing the first %,d".format(spec.rows.size)
                else -> null
            }?.let { Text(it, color = info, fontSize = 12.sp) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().card()) {
            Row(
                Modifier.fillMaxWidth().background(JewelTheme.globalColors.text.normal.copy(alpha = 0.04f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spec.headers.forEachIndexed { i, header -> Text(header, Modifier.weight(spec.weights[i]), fontWeight = FontWeight.SemiBold, color = info, fontSize = 12.sp) }
                Spacer(Modifier.width(ActionsWidth + ScrollbarGutter))
            }
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(if (query.isEmpty()) "No ${elementNoun(spec.type, 2).substringAfter(' ')}" else "Nothing matches \"$query\"", color = info)
                }
            }
            val tableState = rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = ScrollbarGutter), state = tableState) {
                    items(rows) { row ->
                        ElementRow(tab, spec, row, editing = editing == row, onEdit = { editing = it?.let { row } })
                        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                    }
                }
                VerticalScrollbar(tableState, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp), style = visibleScrollbarStyle())
            }
        }
        if (!tab.readOnly) AddElementRow(tab, spec)
    }
}

private val ActionsWidth = 84.dp

@Composable
private fun ElementRow(tab: KeyBrowserTab, spec: ElementSpec, row: List<String>, editing: Boolean, onEdit: (Boolean?) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val canEdit = !tab.readOnly && spec.editColumn != null && spec.update != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 30.dp).background(rowBackground(false, hovered || editing)).hoverable(interaction)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.forEachIndexed { i, cell ->
            if (editing && i == spec.editColumn) {
                InlineEditor(cell, Modifier.weight(spec.weights[i]), isValid = { spec.update!!(row, it) != null }, onCancel = { onEdit(null) }) { new ->
                    spec.update!!(row, new)?.let { edits ->
                        tab.editAll(edits)
                        onEdit(null)
                    }
                }
            } else {
                Text(
                    cell,
                    Modifier.weight(spec.weights[i]).padding(end = 8.dp)
                        .then(if (canEdit && i == spec.editColumn) Modifier.clickable { onEdit(true) } else Modifier),
                    style = JewelTheme.editorTextStyle,
                    color = if (spec.type == KeyType.List && i == 0) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // Actions appear on hover so a long table stays calm.
            if ((hovered || editing) && !editing) {
                val copied = row.getOrElse(spec.editColumn ?: (row.size - 1)) { row.last() }
                Tooltip({ Text("Copy value") }) { IconButton(onClick = { copyToClipboard(copied) }) { Icon(AllIconsKeys.Actions.Copy, "Copy value") } }
                if (canEdit) Tooltip({ Text("Edit") }) { IconButton(onClick = { onEdit(true) }) { Icon(AllIconsKeys.Actions.Edit, "Edit") } }
                if (!tab.readOnly) Tooltip({ Text("Remove") }) { IconButton(onClick = { tab.edit(spec.remove(row)) }) { Icon(AllIconsKeys.General.Delete, "Remove") } }
            }
        }
    }
}

@Composable
private fun InlineEditor(initial: String, modifier: Modifier, isValid: (String) -> Boolean, onCancel: () -> Unit, onSave: (String) -> Unit) {
    val state = remember(initial) { TextFieldState(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = isValid(state.text.toString())
    Row(modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            state,
            Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event -> isEscape(event).also { if (it) onCancel() } },
            textStyle = JewelTheme.editorTextStyle,
            outline = if (valid) org.jetbrains.jewel.ui.Outline.None else org.jetbrains.jewel.ui.Outline.Error,
            onKeyboardAction = KeyboardActionHandler { if (valid) onSave(state.text.toString()) },
        )
        DefaultButton(onClick = { onSave(state.text.toString()) }, enabled = valid) { Text("Save") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun AddElementRow(tab: KeyBrowserTab, spec: ElementSpec) {
    val inputs = remember(spec.addLabels, tab.selectedKey) { spec.addLabels.map { TextFieldState() } }
    val values = inputs.map { it.text.toString() }
    val edit = spec.add(values)
    val typed = values.any { it.isNotBlank() }
    fun submit() {
        val ready = spec.add(inputs.map { it.text.toString() }) ?: return
        tab.edit(ready)
        inputs.forEach { it.clearText() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Add ${elementNoun(spec.type, 1).substringAfter(' ')}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            inputs.forEachIndexed { i, input ->
                TextField(
                    input,
                    Modifier.weight(spec.weights.getOrElse(i) { 1f }.takeIf { inputs.size > 1 } ?: 1f),
                    textStyle = JewelTheme.editorTextStyle,
                    placeholder = { Text(spec.addLabels[i]) },
                    onKeyboardAction = KeyboardActionHandler { submit() },
                )
            }
            DefaultButton(onClick = ::submit, enabled = edit != null) { Text("Add") }
        }
        if (typed && edit == null && spec.invalidHint != null) Text(spec.invalidHint, color = JewelTheme.globalColors.text.error, fontSize = 11.sp)
        if (spec.type == KeyType.Hash && edit != null && spec.rows.any { it[0] == values[0] }) {
            Text("${values[0]} exists; adding replaces its value.", color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------- new key

/** Creates a key with its first element and, optionally, an expiry. */
@Composable
private fun NewKeyForm(tab: KeyBrowserTab, onDone: () -> Unit) {
    val types = if (tab.plainValues) listOf(KeyType.Text) else EditableTypes
    var type by remember { mutableStateOf(KeyType.Text) }
    val name = remember { TextFieldState(tab.pattern.text.toString().trim().substringBefore('*').substringBefore('?').takeIf { it != "*" }.orEmpty()) }
    val text = remember { TextFieldState() }
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val ttl = remember { TextFieldState() }
    var ttlUnit by remember { mutableStateOf(TtlUnit.Hours) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val info = JewelTheme.globalColors.text.info

    val keyName = name.text.toString()
    val exists = tab.keys.any { it.name == keyName }
    val a = first.text.toString()
    val b = second.text.toString()
    val edit =
        when (type) {
            KeyType.Text -> KeyEdit.SetText(text.text.toString())
            KeyType.Hash -> if (a.isBlank()) null else KeyEdit.SetField(a, b)
            KeyType.List -> if (a.isEmpty()) null else KeyEdit.AppendItem(a)
            KeyType.Set -> if (a.isEmpty()) null else KeyEdit.AddMember(a)
            KeyType.SortedSet -> b.trim().toDoubleOrNull()?.takeIf { a.isNotEmpty() }?.let { KeyEdit.SetScore(a, it) }
            KeyType.Stream -> parseFields(a)?.let { KeyEdit.AddEntry(it) }
            else -> null
        }
    val ttlText = ttl.text.toString()
    val ttlValue = ttlSeconds(ttlText, ttlUnit)
    val ttlValid = ttlText.isBlank() || ttlValue != null
    val ready = keyName.isNotBlank() && !exists && edit != null && ttlValid
    fun create() {
        if (ready) tab.create(keyName, edit!!, ttlValue, onCreated = onDone)
    }

    VerticallyScrollableContainer(Modifier.fillMaxSize(), style = visibleScrollbarStyle()) {
    Column(Modifier.widthIn(max = 640.dp).padding(end = ScrollbarGutter + 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("New key", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(if (tab.plainValues) "Adds a key to this etcd cluster." else "Adds a key to database ${tab.keyspace}.", color = info)
        }
        FormField("Key name", error = if (exists) "A key named $keyName already exists." else null, hint = "Use \"${tab.delimiter}\" to group keys, e.g. user${tab.delimiter}42${tab.delimiter}profile") {
            TextField(
                name,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event -> isEscape(event).also { if (it) onDone() } },
                textStyle = JewelTheme.editorTextStyle,
                outline = if (exists) org.jetbrains.jewel.ui.Outline.Error else org.jetbrains.jewel.ui.Outline.None,
                placeholder = { Text(if (tab.plainValues) "/services/api/port" else "user:42") },
                onKeyboardAction = KeyboardActionHandler { create() },
            )
        }
        if (types.size > 1) {
            FormField("Type", hint = typeDescription(type)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { candidate -> TypeOption(candidate, selected = candidate == type) { type = candidate } }
                }
            }
        }
        when (type) {
            KeyType.Text ->
                FormField("Value") {
                    TextArea(text, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("Text, a number, or JSON") })
                }
            KeyType.Hash ->
                FormField("First field", hint = "Add more fields after the key is created.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextField(first, Modifier.weight(0.4f), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("Field") })
                        TextField(second, Modifier.weight(0.6f), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("Value") })
                    }
                }
            KeyType.SortedSet ->
                FormField("First member", error = if (b.isNotBlank() && b.trim().toDoubleOrNull() == null) "Score must be a number." else null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextField(first, Modifier.weight(0.7f), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("Member") })
                        TextField(second, Modifier.weight(0.3f), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("Score") })
                    }
                }
            KeyType.Stream ->
                FormField("First entry", error = if (a.isNotBlank() && edit == null) "Write fields as name=value, separated by commas." else null, hint = "e.g. event=signup, user=42") {
                    TextField(first, Modifier.fillMaxWidth(), textStyle = JewelTheme.editorTextStyle, placeholder = { Text("field=value, field=value") })
                }
            else ->
                FormField(if (type == KeyType.List) "First item" else "First member", hint = "Redis has no empty ${type.label}s, so a key starts with one element.") {
                    TextField(first, Modifier.fillMaxWidth(), textStyle = JewelTheme.editorTextStyle)
                }
        }
        FormField("Expiry", error = if (!ttlValid) "Enter a whole number greater than 0, or leave it empty." else null, hint = "Leave empty to keep the key forever.") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Expire after", color = info)
                TextField(ttl, Modifier.width(80.dp), placeholder = { Text("never") })
                ListComboBox(items = TtlUnit.entries.map { it.label }, selectedIndex = ttlUnit.ordinal, onSelectedItemChange = { ttlUnit = TtlUnit.entries[it] }, modifier = Modifier.width(110.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = ::create, enabled = ready) { Text("Create key") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
    }
}

@Composable
private fun FormField(label: String, modifier: Modifier = Modifier, error: String? = null, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        content()
        when {
            error != null -> Text(error, color = JewelTheme.globalColors.text.error, fontSize = 11.sp)
            hint != null -> Text(hint, color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TypeOption(type: KeyType, selected: Boolean, onClick: () -> Unit) {
    val color = TypeColors.getValue(type)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.clip(shape)
            .background(if (selected) color.copy(alpha = 0.18f) else if (hovered) JewelTheme.globalColors.text.normal.copy(alpha = 0.06f) else Color.Transparent)
            .border(1.dp, if (selected) color else LocalIslands.current.divider, shape)
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(type.label, color = if (selected) color else JewelTheme.globalColors.text.normal, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

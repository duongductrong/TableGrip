package dev.tablegrip.app.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.tablegrip.app.ui.AppIcons
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.comboBoxStyle
import org.jetbrains.jewel.ui.theme.treeStyle

/**
 * The engines matching [query]: every word must appear in the engine's name, id or family, so "pg", "maria" or
 * "clickhouse" each find theirs; within each rank the list keeps its own order.
 */
internal fun engineMatches(engines: List<DatabaseType>, query: String): List<DatabaseType> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return engines
    fun haystack(type: DatabaseType) = listOf(type.displayName, type.id, type.family.name, aliases[type.family.name].orEmpty()).joinToString(" ").lowercase()
    val matches = engines.filter { type -> haystack(type).let { text -> words.all { it in text } } }
    val first = words.first()
    // Names starting with the query, then names containing it, then engines found only through their family or alias.
    return matches.sortedBy { type ->
        val name = "${type.displayName} ${type.id}".lowercase()
        when {
            type.displayName.lowercase().startsWith(first) -> 0
            words.all { it in name } -> 1
            else -> 2
        }
    }
}

/** Short names people type for a family that its own name doesn't contain. */
private val aliases = mapOf("PostgreSQL" to "postgres pg", "SqlServer" to "mssql sql server", "MongoDB" to "mongo")

/**
 * The engine picker: looks like the other combo boxes, but opens a list with a search field on top, so an engine can be
 * found by typing instead of scrolling through every one. Arrows move, Enter picks, Escape closes.
 */
@Composable
internal fun EngineComboBox(selected: DatabaseType, onChange: (DatabaseType) -> Unit, modifier: Modifier = Modifier) {
    val style = JewelTheme.comboBoxStyle
    val shape = RoundedCornerShape(style.metrics.cornerSize)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = style.metrics.minSize.height)
                .onSizeChanged { anchorWidth = it.width }
                .clip(shape)
                .background(if (hovered) style.colors.backgroundHovered else style.colors.nonEditableBackground)
                .border(style.metrics.borderWidth, if (open) style.colors.borderFocused else style.colors.border, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(style.metrics.contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.engineLogo(selected) ?: AllIconsKeys.Nodes.DataSchema, null, Modifier.size(16.dp))
            Text(selected.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(style.icons.chevronDown, null)
        }
        if (open) {
            val width = with(LocalDensity.current) { anchorWidth.toDp() }.coerceAtLeast(260.dp)
            EngineList(selected, width, onPick = { open = false; if (it != selected) onChange(it) }, onDismiss = { open = false })
        }
    }
}

@Composable
private fun EngineList(selected: DatabaseType, width: androidx.compose.ui.unit.Dp, onPick: (DatabaseType) -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val matches = engineMatches(DatabaseType.entries, query.text.toString())
    // Opens on the current engine; once something is typed, on the best match.
    var highlighted by remember { mutableIntStateOf(DatabaseType.entries.indexOf(selected).coerceAtLeast(0)) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (highlighted - 3).coerceAtLeast(0))
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(query.text) {
        if (typed) highlighted = 0
        typed = true
    }
    LaunchedEffect(highlighted, matches.size) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (matches.isNotEmpty() && visible.isNotEmpty() && (highlighted < visible.first().index || highlighted > visible.last().index)) {
            listState.scrollToItem((highlighted - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Popup(popupPositionProvider = BelowAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(width)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextField(
                query,
                Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val last = matches.lastIndex
                    when (event.key) {
                        Key.Escape -> true.also { onDismiss() }
                        Key.DirectionDown -> true.also { if (last >= 0) highlighted = if (highlighted >= last) 0 else highlighted + 1 }
                        Key.DirectionUp -> true.also { if (last >= 0) highlighted = if (highlighted <= 0) last else highlighted - 1 }
                        Key.Enter, Key.NumPadEnter -> true.also { matches.getOrNull(highlighted.coerceIn(0, maxOf(last, 0)))?.let(onPick) }
                        else -> false
                    }
                },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.size(16.dp).padding(end = 2.dp)) },
                placeholder = { Text(tr("Search {0} databases", DatabaseType.entries.size)) },
            )
            if (matches.isEmpty()) {
                Text(tr("No database matches “{0}”.", query.text.toString().trim()), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = JewelTheme.globalColors.text.info)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), state = listState) {
                items(matches.size, key = { matches[it].id }) { index ->
                    val type = matches[index]
                    EngineRow(type, current = type == selected, highlighted = index == highlighted) { onPick(type) }
                }
            }
        }
    }
}

@Composable
private fun EngineRow(type: DatabaseType, current: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(if (highlighted) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.engineLogo(type) ?: AllIconsKeys.Nodes.DataSchema, null, Modifier.size(16.dp))
        Text(type.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (current) Icon(AllIconsKeys.Actions.Checked, null, Modifier.size(14.dp))
    }
}

/** Puts a popup just below its anchor, or above it when there is no room below. */
private object BelowAnchor : PopupPositionProvider {
    private const val Gap = 4

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + Gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height - Gap).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
